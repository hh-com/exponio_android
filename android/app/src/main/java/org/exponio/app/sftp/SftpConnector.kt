package org.exponio.app.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchAlgoNegoFailException
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import org.exponio.app.security.SecretStore
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

/** Fehler mit stabilem Code für die Web-UI und einer verständlichen deutschen Meldung. */
class TransferException(
    val code: String,
    message: String,
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : Exception(message, cause)

object ErrorCode {
    const val NOT_CONFIGURED = "NOT_CONFIGURED"
    const val NO_CREDENTIALS = "NO_CREDENTIALS"
    const val KEY_INVALID = "KEY_INVALID"
    const val HOST_NOT_FOUND = "HOST_NOT_FOUND"
    const val UNREACHABLE = "UNREACHABLE"
    const val TIMEOUT = "TIMEOUT"
    const val HOST_KEY_UNKNOWN = "HOST_KEY_UNKNOWN"
    const val HOST_KEY_CHANGED = "HOST_KEY_CHANGED"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val CONNECTION_FAILED = "CONNECTION_FAILED"
    const val CONNECTION_LOST = "CONNECTION_LOST"
    const val BASE_PATH_MISSING = "BASE_PATH_MISSING"
    const val BASE_PATH_NOT_DIR = "BASE_PATH_NOT_DIR"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val REMOTE_ERROR = "REMOTE_ERROR"
    const val TLS_ERROR = "TLS_ERROR"
    const val TLS_CERT_INVALID = "TLS_CERT_INVALID"
    const val VERIFY_FAILED = "VERIFY_FAILED"
    const val PROCESSING_FAILED = "PROCESSING_FAILED"
    const val PHOTO_MISSING = "PHOTO_MISSING"
    const val NOTHING_TO_UPLOAD = "NOTHING_TO_UPLOAD"
    const val CANCELLED = "CANCELLED"
    const val BUSY = "BUSY"
    const val UNKNOWN = "UNKNOWN"
}

data class ServerConfig(
    val host: String,
    val port: Int,
    val username: String,
    val auth: AuthMethod,
    val basePath: String,
    val protocol: Protocol = Protocol.SFTP,
) {
    enum class AuthMethod { PASSWORD, KEY }

    enum class Protocol(val id: String, val defaultPort: Int) {
        SFTP("sftp", 22),
        FTPS("ftps", 21);

        companion object {
            fun parse(value: String?): Protocol = entries.firstOrNull { it.id == value } ?: SFTP
        }
    }
}

class SftpConnection(
    val session: Session,
    val sftp: ChannelSftp,
    val hostKey: ByteArray,
    val newlyTrusted: Boolean,
) : Closeable {
    override fun close() {
        try {
            sftp.disconnect()
        } catch (_: Exception) {
        }
        try {
            session.disconnect()
        } catch (_: Exception) {
        }
    }
}

class SftpConnector(
    private val secrets: SecretStore,
    private val hostKeys: HostKeyStore,
) {

    data class KeyInfo(val type: String, val encrypted: Boolean, val passphraseOk: Boolean?)

    /**
     * Baut eine authentifizierte SFTP-Verbindung auf.
     *
     * Host-Key-Prüfung (TOFU): Ist für host:port noch kein Schlüssel gespeichert, wird die Verbindung
     * mit HOST_KEY_UNKNOWN (inkl. Fingerprint) abgelehnt. Erst wenn der Nutzer den Fingerprint
     * bestätigt und er als [trustFingerprint] mitgegeben wird, wird er akzeptiert und nach
     * erfolgreicher Anmeldung dauerhaft gespeichert. Ein abweichender Schlüssel → HOST_KEY_CHANGED.
     *
     * @param onSession wird vor connect() mit der Session aufgerufen (für Abbruch von außen)
     */
    @Throws(TransferException::class)
    fun connect(
        cfg: ServerConfig,
        trustFingerprint: String? = null,
        onSession: ((Session) -> Unit)? = null,
    ): SftpConnection {
        validate(cfg)
        val jsch = JSch()
        var password: String? = null

        when (cfg.auth) {
            ServerConfig.AuthMethod.KEY -> {
                val key = secrets.get(SecretStore.PRIVATE_KEY)
                    ?: throw TransferException(ErrorCode.NO_CREDENTIALS, "Es ist kein Private Key hinterlegt.")
                val passphrase = secrets.get(SecretStore.PASSPHRASE)
                val info = inspectKey(key, passphrase)
                if (info.encrypted && info.passphraseOk != true) {
                    throw TransferException(
                        ErrorCode.KEY_INVALID,
                        if (passphrase.isNullOrEmpty()) "Der Private Key ist verschlüsselt – bitte Passphrase eintragen."
                        else "Die Passphrase für den Private Key ist falsch.",
                    )
                }
                try {
                    jsch.addIdentity(
                        "exponio",
                        key.toByteArray(StandardCharsets.UTF_8),
                        null,
                        passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8),
                    )
                } catch (e: JSchException) {
                    throw TransferException(ErrorCode.KEY_INVALID, keyErrorMessage(e), cause = e)
                }
            }
            ServerConfig.AuthMethod.PASSWORD -> {
                password = secrets.get(SecretStore.PASSWORD)
                    ?: throw TransferException(ErrorCode.NO_CREDENTIALS, "Es ist kein Passwort hinterlegt.")
            }
        }

        val host = cfg.host.trim()
        val stored = hostKeys.get(host, cfg.port)
        val repository = TofuRepository(stored, trustFingerprint)

        val session = try {
            jsch.getSession(cfg.username.trim(), host, cfg.port)
        } catch (e: JSchException) {
            throw TransferException(ErrorCode.CONNECTION_FAILED, "Ungültige Verbindungsdaten: ${e.message}", cause = e)
        }
        session.setHostKeyRepository(repository)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("HashKnownHosts", "no")
        session.setConfig("MaxAuthTries", "3")
        session.setConfig(
            "PreferredAuthentications",
            if (cfg.auth == ServerConfig.AuthMethod.KEY) "publickey" else "password,keyboard-interactive",
        )
        configureHostKeyAlgorithms(session, stored?.type)
        if (password != null) session.setPassword(password.toByteArray(Charsets.UTF_8))
        session.setUserInfo(OneShotUserInfo(password))
        session.setTimeout(READ_TIMEOUT_MS)
        session.setServerAliveInterval(KEEPALIVE_MS)
        session.setServerAliveCountMax(3)

        onSession?.invoke(session)

        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } catch (e: JSchException) {
            session.disconnect()
            throw mapConnectError(e, host, repository, stored)
        }

        val presented = repository.presentedKey
        if (repository.acceptedNew && presented != null) {
            hostKeys.put(host, cfg.port, presented)
        }

        val channel = try {
            (session.openChannel("sftp") as ChannelSftp).also { it.connect(CONNECT_TIMEOUT_MS) }
        } catch (e: Exception) {
            session.disconnect()
            throw TransferException(
                ErrorCode.CONNECTION_FAILED,
                "Anmeldung ok, aber der Server hat SFTP abgelehnt bzw. sofort beendet. " +
                    "Häufig: Benutzer ohne Shell (nologin) → in sshd_config „ForceCommand internal-sftp“ " +
                    "setzen, oder falsche Rechte am ChrootDirectory. (${e.message})",
                cause = e,
            )
        }
        return SftpConnection(session, channel, presented ?: ByteArray(0), repository.acceptedNew)
    }

    fun mapSftpError(e: SftpException, conn: SftpConnection?, action: String): TransferException {
        if (conn != null && !conn.session.isConnected) {
            return TransferException(ErrorCode.CONNECTION_LOST, "Die Verbindung zum Server ist abgebrochen ($action).", cause = e)
        }
        return when (e.id) {
            ChannelSftp.SSH_FX_PERMISSION_DENIED ->
                TransferException(ErrorCode.PERMISSION_DENIED, "Keine Berechtigung auf dem Server ($action).", cause = e)
            ChannelSftp.SSH_FX_NO_SUCH_FILE ->
                TransferException(ErrorCode.REMOTE_ERROR, "Pfad nicht gefunden ($action).", cause = e)
            ChannelSftp.SSH_FX_NO_CONNECTION, ChannelSftp.SSH_FX_CONNECTION_LOST ->
                TransferException(ErrorCode.CONNECTION_LOST, "Die Verbindung zum Server ist abgebrochen ($action).", cause = e)
            else -> {
                if (e.cause is IOException) {
                    TransferException(ErrorCode.CONNECTION_LOST, "Die Verbindung zum Server ist abgebrochen ($action).", cause = e)
                } else {
                    TransferException(ErrorCode.REMOTE_ERROR, "SFTP-Fehler bei „$action“: ${e.message ?: "unbekannt"}", cause = e)
                }
            }
        }
    }

    /** Analysiert einen Private Key (Typ, verschlüsselt?, Passphrase korrekt?) ohne ihn zu speichern. */
    @Throws(TransferException::class)
    fun inspectKey(privateKey: String, passphrase: String?): KeyInfo {
        val kp = try {
            KeyPair.load(JSch(), privateKey.toByteArray(StandardCharsets.UTF_8), null)
        } catch (e: JSchException) {
            throw TransferException(ErrorCode.KEY_INVALID, keyErrorMessage(e), cause = e)
        }
        try {
            val encrypted = kp.isEncrypted
            val passOk: Boolean? = if (!encrypted) {
                null
            } else if (passphrase.isNullOrEmpty()) {
                false
            } else {
                kp.decrypt(passphrase.toByteArray(StandardCharsets.UTF_8))
            }
            val type = try {
                kp.keyTypeString
            } catch (_: Exception) {
                "unbekannt"
            }
            return KeyInfo(type, encrypted, passOk)
        } finally {
            kp.dispose()
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun validate(cfg: ServerConfig) {
        if (cfg.host.isBlank() || cfg.username.isBlank()) {
            throw TransferException(ErrorCode.NOT_CONFIGURED, "Server-Adresse und Benutzername müssen eingetragen sein.")
        }
        if (cfg.port !in 1..65535) {
            throw TransferException(ErrorCode.NOT_CONFIGURED, "Ungültiger Port: ${cfg.port}")
        }
    }

    /**
     * Zertifikats-Varianten entfernen (wir prüfen den reinen Host-Key per TOFU) und – wenn bereits
     * ein Schlüssel gespeichert ist – dessen Algorithmus bevorzugen, damit der Server denselben
     * Schlüsseltyp präsentiert (wie OpenSSH es mit known_hosts macht).
     */
    private fun configureHostKeyAlgorithms(session: Session, storedType: String?) {
        val current = session.getConfig("server_host_key") ?: return
        val algorithms = current.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("-cert-v01@openssh.com") }
        if (algorithms.isEmpty()) return
        val preferred = when (storedType) {
            null -> emptyList()
            "ssh-rsa" -> listOf("rsa-sha2-512", "rsa-sha2-256")
            else -> listOf(storedType)
        }.filter { it in algorithms }
        val ordered = preferred + algorithms.filter { it !in preferred }
        session.setConfig("server_host_key", ordered.joinToString(","))
    }

    private fun mapConnectError(
        e: JSchException,
        host: String,
        repository: TofuRepository,
        stored: HostKeyStore.StoredKey?,
    ): TransferException {
        val presented = repository.presentedKey
        if (e is JSchUnknownHostKeyException && presented != null) {
            return TransferException(
                ErrorCode.HOST_KEY_UNKNOWN,
                "Der Server ist noch nicht vertrauenswürdig. Bitte den Fingerprint prüfen und bestätigen.",
                mapOf(
                    "fingerprint" to HostKeyStore.fingerprintOf(presented),
                    "keyType" to HostKeyStore.keyTypeOf(presented),
                ),
                e,
            )
        }
        if (e is JSchChangedHostKeyException && presented != null) {
            return TransferException(
                ErrorCode.HOST_KEY_CHANGED,
                "ACHTUNG: Der Host-Key des Servers hat sich geändert! Upload gesperrt. " +
                    "Nur wenn der Server bewusst gewechselt wurde, den gespeicherten Fingerprint in den Einstellungen zurücksetzen.",
                mapOf(
                    "fingerprint" to HostKeyStore.fingerprintOf(presented),
                    "keyType" to HostKeyStore.keyTypeOf(presented),
                    "storedFingerprint" to stored?.fingerprint,
                ),
                e,
            )
        }
        val message = e.message.orEmpty()
        if (message.startsWith("Auth fail") || message.startsWith("Auth cancel")) {
            return TransferException(
                ErrorCode.AUTH_FAILED,
                "Anmeldung fehlgeschlagen – Benutzername, Passwort bzw. Key prüfen.",
                cause = e,
            )
        }
        if (e is JSchAlgoNegoFailException) {
            return TransferException(
                ErrorCode.CONNECTION_FAILED,
                "Keine gemeinsamen Verschlüsselungsverfahren mit dem Server: $message",
                cause = e,
            )
        }
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is UnknownHostException ->
                    return TransferException(ErrorCode.HOST_NOT_FOUND, "Server „$host“ wurde nicht gefunden (DNS).", cause = e)
                is SocketTimeoutException ->
                    return TransferException(ErrorCode.TIMEOUT, "Zeitüberschreitung – Server „$host“ antwortet nicht.", cause = e)
                is NoRouteToHostException, is ConnectException ->
                    return TransferException(ErrorCode.UNREACHABLE, "Server „$host“ ist nicht erreichbar (Port/Firewall prüfen).", cause = e)
            }
            cause = cause.cause
        }
        if (message.contains("timeout", ignoreCase = true)) {
            return TransferException(ErrorCode.TIMEOUT, "Zeitüberschreitung beim Verbindungsaufbau zu „$host“.", cause = e)
        }
        return TransferException(ErrorCode.CONNECTION_FAILED, "Verbindung fehlgeschlagen: ${message.ifEmpty { e.javaClass.simpleName }}", cause = e)
    }

    private fun keyErrorMessage(e: JSchException): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("passphrase", ignoreCase = true) -> "Die Passphrase für den Private Key ist falsch."
            message.contains("invalid privatekey", ignoreCase = true) ->
                "Der Private Key ist ungültig oder hat ein nicht unterstütztes Format."
            else -> "Private Key kann nicht gelesen werden: $message"
        }
    }

    /** Host-Key-Prüfung gegen den lokalen TOFU-Speicher. */
    private class TofuRepository(
        private val stored: HostKeyStore.StoredKey?,
        private val trustFingerprint: String?,
    ) : HostKeyRepository {

        @Volatile
        var presentedKey: ByteArray? = null
            private set

        @Volatile
        var acceptedNew: Boolean = false
            private set

        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.NOT_INCLUDED
            presentedKey = key.copyOf()
            if (stored != null) {
                return if (stored.blob.contentEquals(key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
            }
            if (trustFingerprint != null && trustFingerprint == HostKeyStore.fingerprintOf(key)) {
                acceptedNew = true
                return HostKeyRepository.OK
            }
            return HostKeyRepository.NOT_INCLUDED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "exponio-tofu"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /**
     * Liefert das Passwort genau einmal (kein Endlos-Retry mit falschem Passwort),
     * beantwortet keyboard-interactive einmalig und lehnt alle Ja/Nein-Rückfragen ab.
     */
    private class OneShotUserInfo(private val password: String?) : UserInfo, UIKeyboardInteractive {
        private var keyboardInteractiveAnswered = false

        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = password
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) {}

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            if (password == null || prompt == null || keyboardInteractiveAnswered) return null
            keyboardInteractiveAnswered = true
            return Array(prompt.size) { password }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val KEEPALIVE_MS = 15_000

        fun statOrNull(sftp: ChannelSftp, path: String): SftpATTRS? = try {
            sftp.stat(path)
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e
        }
    }
}
