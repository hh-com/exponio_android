package org.exponio.app.ftps

import org.exponio.app.remote.RemoteFs
import org.exponio.app.remote.RemoteStat
import org.exponio.app.sftp.ErrorCode
import org.exponio.app.sftp.ServerConfig
import org.exponio.app.sftp.TransferException
import org.apache.commons.net.MalformedServerReplyException
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPConnectionClosedException
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.BCSSLSocket
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * FTPS mit explizitem TLS („FTP über TLS“, meist Port 21) – für Webhosting ohne SFTP.
 *
 * - Steuer- und Datenverbindung sind immer verschlüsselt (AUTH TLS, PBSZ 0, PROT P), es gibt keinen
 *   Klartext-Fallback. Das Passwort wird erst nach erfolgreicher Zertifikatsprüfung gesendet.
 * - Das Zertifikat wird wie im Browser geprüft: Kette gegen die System-CAs + Hostname. Darum muss als Host
 *   der Name eingetragen werden, für den das Zertifikat gilt (z. B. „ftp.example.com“, nicht die IP).
 * - TLS läuft über Bouncy Castle JSSE: Viele Server (ProFTPD-Standard) akzeptieren eine Datenverbindung nur,
 *   wenn sie die TLS-Sitzung der Steuerverbindung wiederverwendet. Androids eigener TLS-Stack bietet dafür
 *   keine API, Bouncy Castle schon ([BCSSLSocket.setBCSessionToResume]) – allerdings nur mit TLS 1.2.
 *
 * [trustManager] und [hostnameVerifier] sind nur für Tests austauschbar; Standard sind die System-Vorgaben.
 */
class FtpsConnector(
    private val trustManager: X509TrustManager? = null,
    private val hostnameVerifier: HostnameVerifier? = null,
) {

    @Throws(TransferException::class)
    fun connect(
        cfg: ServerConfig,
        password: String?,
        onAbort: ((() -> Unit) -> Unit)? = null,
    ): FtpsRemoteFs {
        val host = cfg.host.trim()
        val user = cfg.username.trim()
        if (host.isEmpty() || user.isEmpty()) {
            throw TransferException(ErrorCode.NOT_CONFIGURED, "Server-Adresse und Benutzername müssen eingetragen sein.")
        }
        if (cfg.port !in 1..65535) {
            throw TransferException(ErrorCode.NOT_CONFIGURED, "Ungültiger Port: ${cfg.port}")
        }
        if (password.isNullOrEmpty()) {
            throw TransferException(ErrorCode.NO_CREDENTIALS, "Es ist kein Passwort hinterlegt.")
        }

        val verifier = RecordingVerifier(hostnameVerifier ?: HttpsURLConnection.getDefaultHostnameVerifier())
        val client = SessionReusingFtpsClient(sslContext(), sniHostOf(host))
        client.setHostnameVerifier(verifier)
        client.controlEncoding = "UTF-8"
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.defaultTimeout = READ_TIMEOUT_MS
        client.setDataTimeout(Duration.ofMillis(READ_TIMEOUT_MS.toLong()))
        // EPSV liefert nur den Port → Datenverbindung geht immer an denselben Host (robust hinter NAT).
        client.setUseEPSVwithIPv4(true)
        // TLS 1.2 (ECDHE + AES-GCM): Bouncy Castle kann eine TLS-1.3-Sitzung nicht für die Datenverbindung
        // fortsetzen – ProFTPD lehnt die Datenverbindung dann ab. Mit 1.2 klappt das zuverlässig.
        client.setEnabledProtocols(arrayOf("TLSv1.2"))
        onAbort?.invoke { client.disconnectQuietly() }

        try {
            // Begrüßung, AUTH TLS, TLS-Handshake inkl. Zertifikats- und Hostname-Prüfung.
            client.connect(host, cfg.port)
        } catch (e: IOException) {
            client.disconnectQuietly()
            throw mapConnectError(e, host, cfg.port, verifier)
        }

        try {
            if (!client.login(user, password)) {
                throw TransferException(
                    ErrorCode.AUTH_FAILED,
                    "Anmeldung fehlgeschlagen – Benutzername oder Passwort prüfen. (${client.replyText()})",
                )
            }
            client.execPBSZ(0)
            client.execPROT("P")
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw TransferException(ErrorCode.REMOTE_ERROR, "Binärmodus wurde abgelehnt: ${client.replyText()}")
            }
            client.enterLocalPassiveMode()
            val mlst = client.hasFeature("MLST")
            return FtpsRemoteFs(client, describe(client), mlst)
        } catch (e: TransferException) {
            client.disconnectQuietly()
            throw e
        } catch (e: SSLException) {
            client.disconnectQuietly()
            throw TransferException(
                ErrorCode.TLS_ERROR,
                "Der Server lehnt die verschlüsselte Datenverbindung ab (PROT P): ${e.message}",
                cause = e,
            )
        } catch (e: IOException) {
            client.disconnectQuietly()
            throw TransferException(ErrorCode.CONNECTION_LOST, "Die Verbindung zum Server ist bei der Anmeldung abgebrochen.", cause = e)
        }
    }

    private fun sslContext(): SSLContext {
        val context = SSLContext.getInstance("TLS", jsseProvider)
        context.init(null, arrayOf<TrustManager>(trustManager ?: systemTrustManager()), SecureRandom())
        return context
    }

    private fun mapConnectError(e: IOException, host: String, port: Int, verifier: RecordingVerifier): TransferException {
        verifier.mismatchNames?.let { names ->
            val message = if (names.isEmpty()) {
                "Das TLS-Zertifikat des Servers passt nicht zu „$host“."
            } else {
                "Das TLS-Zertifikat gilt nicht für „$host“, sondern für ${names.joinToString(", ")}. " +
                    "Bitte genau diesen Namen als Host eintragen."
            }
            return TransferException(ErrorCode.TLS_CERT_INVALID, message, mapOf("certNames" to names.joinToString(",")), e)
        }
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is UnknownHostException ->
                    return TransferException(ErrorCode.HOST_NOT_FOUND, "Server „$host“ wurde nicht gefunden (DNS).", cause = e)
                is SocketTimeoutException ->
                    return TransferException(ErrorCode.TIMEOUT, "Zeitüberschreitung – Server „$host“ antwortet nicht.", cause = e)
                is NoRouteToHostException, is ConnectException ->
                    return TransferException(
                        ErrorCode.UNREACHABLE,
                        "Server „$host“ ist auf Port $port nicht erreichbar (Port/Firewall prüfen, FTPS meist 21).",
                        cause = e,
                    )
                is CertificateException, is CertPathValidatorException ->
                    return TransferException(
                        ErrorCode.TLS_CERT_INVALID,
                        "Das TLS-Zertifikat des Servers ist nicht vertrauenswürdig (abgelaufen, selbst signiert oder unvollständig).",
                        cause = e,
                    )
            }
            cause = cause.cause
        }
        return when (e) {
            is MalformedServerReplyException -> TransferException(
                ErrorCode.CONNECTION_FAILED,
                "Auf Port $port antwortet kein FTP-Server (FTPS meist Port 21).",
                cause = e,
            )
            is FTPConnectionClosedException -> TransferException(
                ErrorCode.CONNECTION_FAILED,
                "Der Server hat die Verbindung sofort beendet: ${e.message}",
                cause = e,
            )
            is SSLHandshakeException -> TransferException(
                ErrorCode.TLS_ERROR,
                "TLS-Handshake fehlgeschlagen: ${e.message}",
                cause = e,
            )
            is SSLException -> if (e.message.orEmpty().matches(Regex("""^\d{3}[ -].*""", RegexOption.DOT_MATCHES_ALL))) {
                // Antwort auf AUTH TLS war negativ → Server kann kein FTPS.
                TransferException(
                    ErrorCode.TLS_ERROR,
                    "Der Server bietet kein verschlüsseltes FTP (FTPS) an: ${e.message?.trim()}",
                    cause = e,
                )
            } else {
                TransferException(ErrorCode.TLS_ERROR, "TLS-Fehler: ${e.message}", cause = e)
            }
            else -> TransferException(
                ErrorCode.CONNECTION_FAILED,
                "Verbindung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        }
    }

    private fun describe(client: SessionReusingFtpsClient): String {
        val session = client.controlSession
        val parts = mutableListOf("FTPS", session?.protocol ?: "TLS")
        val cert = try {
            session?.peerCertificates?.firstOrNull() as? X509Certificate
        } catch (_: Exception) {
            null
        }
        if (cert != null) {
            certNames(cert).firstOrNull()?.let { parts += it }
            rdn(cert.issuerX500Principal, "O")?.let { parts += it }
        }
        return parts.joinToString(" · ")
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Eigene Provider-Instanz statt Registrierung – Android hat bereits einen (gekürzten) „BC“-Provider. */
        private val jsseProvider by lazy { BouncyCastleJsseProvider(BouncyCastleProvider()) }

        /**
         * System-CAs (inkl. Network-Security-Config). Bewusst als einfacher X509TrustManager weitergereicht:
         * Die Kette wird gegen die System-CAs geprüft, den Hostnamen prüft anschließend der HostnameVerifier.
         */
        fun systemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            val delegate = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
            return object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    delegate.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
                    delegate.checkServerTrusted(chain, authType)

                override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
            }
        }

        /** SNI nur für Hostnamen, nicht für IP-Adressen. */
        internal fun sniHostOf(host: String): String? {
            if (host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return null
            return host.trimEnd('.')
        }

        internal fun certNames(cert: X509Certificate): List<String> {
            val sans = try {
                cert.subjectAlternativeNames
            } catch (_: Exception) {
                null
            }
            val names = sans.orEmpty().mapNotNull { entry ->
                val type = entry.getOrNull(0) as? Int
                if (type == 2 || type == 7) entry.getOrNull(1) as? String else null // 2 = DNS, 7 = IP
            }
            return names.ifEmpty { listOfNotNull(rdn(cert.subjectX500Principal, "CN")) }
        }

        private fun rdn(principal: X500Principal, key: String): String? =
            principal.getName(X500Principal.RFC2253)
                .split(Regex("""(?<!\\),"""))
                .map { it.trim() }
                .firstOrNull { it.startsWith("$key=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.replace("\\", "")
    }
}

/**
 * FTPS-Client, der für jede Datenverbindung die TLS-Sitzung der Steuerverbindung wiederverwendet
 * (und denselben SNI-Namen sendet) – sonst lehnen ProFTPD & Co. die Datenverbindung ab.
 */
internal class SessionReusingFtpsClient(
    context: SSLContext,
    private val sniHost: String?,
) : FTPSClient(false, context) {

    val controlSession: SSLSession?
        get() = (_socket_ as? SSLSocket)?.session

    override fun _prepareDataSocket_(socket: Socket) {
        val control = _socket_
        if (socket is BCSSLSocket && control is BCSSLSocket) {
            control.bcSession?.let { socket.setBCSessionToResume(it) }
        }
        if (socket is SSLSocket && sniHost != null) {
            val params = socket.sslParameters
            params.serverNames = listOf(SNIHostName(sniHost))
            socket.sslParameters = params
        }
    }

    fun replyText(): String = replyString?.trim().orEmpty()

    fun disconnectQuietly() {
        try {
            disconnect()
        } catch (_: IOException) {
        }
    }
}

/** Merkt sich bei fehlgeschlagener Hostname-Prüfung die Namen aus dem Zertifikat für eine hilfreiche Meldung. */
private class RecordingVerifier(private val delegate: HostnameVerifier) : HostnameVerifier {
    @Volatile
    var mismatchNames: List<String>? = null
        private set

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val ok = delegate.verify(hostname, session)
        if (!ok) {
            mismatchNames = try {
                (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { FtpsConnector.certNames(it) }.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }
        return ok
    }
}

class FtpsRemoteFs internal constructor(
    private val client: SessionReusingFtpsClient,
    override val serverInfo: String,
    private val mlst: Boolean,
) : RemoteFs {

    override val hostKeyFingerprint: String? = null
    override val hostKeyType: String? = null
    override val newlyTrusted: Boolean = false

    @Volatile
    private var activeStream: OutputStream? = null

    override fun stat(path: String): RemoteStat? = io("Pfad prüfen") {
        if (mlst) {
            val file = client.mlistFile(path)
            when {
                file != null -> RemoteStat(file.isDirectory, file.size)
                client.replyCode == 550 || client.replyCode == 450 -> null
                else -> throw replyError("Pfad prüfen")
            }
        } else {
            // Ohne MLST: Datei per SIZE, Verzeichnis per CWD erkennen.
            val size = client.getSize(path)?.trim()?.toLongOrNull()
            if (size != null) {
                RemoteStat(false, size)
            } else {
                val cwd = client.printWorkingDirectory()
                if (cwd != null && client.changeWorkingDirectory(path)) {
                    client.changeWorkingDirectory(cwd)
                    RemoteStat(true, 0)
                } else {
                    null
                }
            }
        }
    }

    override fun listFiles(path: String): Map<String, Long> = io("Ordner lesen") {
        val files: Array<out FTPFile?> = if (mlst) client.mlistDir(path) else client.listFiles(path)
        // Scheitert die Datenverbindung, liefert commons-net eine leere Liste – darum den Reply prüfen.
        if (!FTPReply.isPositiveCompletion(client.replyCode)) throw replyError("Ordner lesen")
        files.filterNotNull().filter { it.isFile }.associate { it.name to it.size }
    }

    override fun mkdir(path: String) = io("Ordner anlegen") {
        if (!client.makeDirectory(path)) throw replyError("Ordner anlegen")
    }

    override fun rmdir(path: String) = io("Ordner entfernen") {
        if (!client.removeDirectory(path)) throw replyError("Ordner entfernen")
    }

    override fun delete(path: String) = io("Datei entfernen") {
        if (!client.deleteFile(path)) throw replyError("Datei entfernen")
    }

    override fun rename(from: String, to: String) = io("Umbenennen") {
        if (!client.rename(from, to)) throw replyError("Umbenennen")
    }

    override fun upload(source: InputStream, remotePath: String, onBytes: (Long) -> Boolean) = io("Upload") {
        val out = client.storeFileStream(remotePath) ?: throw replyError("Upload")
        activeStream = out
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (!onBytes(n.toLong())) {
                    throw TransferException(ErrorCode.CANCELLED, "Upload abgebrochen.")
                }
            }
            out.close()
        } catch (e: Throwable) {
            try {
                out.close()
            } catch (_: IOException) {
            }
            throw e
        } finally {
            activeStream = null
        }
        if (!client.completePendingCommand()) throw replyError("Upload")
    }

    override fun abort() {
        try {
            activeStream?.close()
        } catch (_: IOException) {
        }
        client.disconnectQuietly()
    }

    override fun close() {
        try {
            if (client.isConnected) {
                client.soTimeout = 5_000
                client.logout()
            }
        } catch (_: IOException) {
        }
        client.disconnectQuietly()
    }

    private inline fun <T> io(action: String, block: () -> T): T = try {
        block()
    } catch (e: FTPConnectionClosedException) {
        throw TransferException(ErrorCode.CONNECTION_LOST, "Der Server hat die Verbindung beendet ($action).", cause = e)
    } catch (e: SSLException) {
        throw TransferException(ErrorCode.TLS_ERROR, "TLS-Fehler bei der Datenverbindung ($action): ${e.message}", cause = e)
    } catch (e: IOException) {
        throw TransferException(
            ErrorCode.CONNECTION_LOST,
            "Die Verbindung zum Server ist abgebrochen ($action): ${e.message ?: e.javaClass.simpleName}",
            cause = e,
        )
    }

    private fun replyError(action: String): TransferException {
        val code = client.replyCode
        val text = client.replyText()
        return when (code) {
            530 -> TransferException(ErrorCode.AUTH_FAILED, "Nicht angemeldet ($action): $text")
            421 -> TransferException(ErrorCode.CONNECTION_LOST, "Der Server hat die Verbindung beendet ($action): $text")
            425, 426, 522 -> TransferException(ErrorCode.CONNECTION_FAILED, "Datenverbindung fehlgeschlagen ($action): $text")
            552 -> TransferException(ErrorCode.REMOTE_ERROR, "Speicherplatz auf dem Server erschöpft ($action): $text")
            550, 553 -> TransferException(ErrorCode.PERMISSION_DENIED, "Vom Server abgelehnt ($action): $text")
            else -> TransferException(ErrorCode.REMOTE_ERROR, "FTP-Fehler bei „$action“: $text")
        }
    }
}
