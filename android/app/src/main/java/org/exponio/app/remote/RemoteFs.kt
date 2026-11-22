package org.exponio.app.remote

import org.exponio.app.ftps.FtpsConnector
import org.exponio.app.security.SecretStore
import org.exponio.app.sftp.ErrorCode
import org.exponio.app.sftp.ServerConfig
import org.exponio.app.sftp.SftpConnector
import org.exponio.app.sftp.SftpRemoteFs
import org.exponio.app.sftp.TransferException
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream

data class RemoteStat(val isDir: Boolean, val size: Long)

/**
 * Die wenigen Dateioperationen, die Upload und Verbindungstest brauchen – identisch für SFTP und FTPS.
 * Alle Fehler kommen als [TransferException] mit verständlicher Meldung.
 */
interface RemoteFs : Closeable {
    /** Kurzbeschreibung für die Anzeige, z. B. SSH-Version oder TLS-Zertifikat. */
    val serverInfo: String

    /** Nur SFTP: Host-Key (TOFU). */
    val hostKeyFingerprint: String?
    val hostKeyType: String?
    val newlyTrusted: Boolean

    /** null = existiert nicht. */
    fun stat(path: String): RemoteStat?

    /** Dateien (keine Ordner) im Verzeichnis: Name → Größe in Bytes. */
    fun listFiles(path: String): Map<String, Long>

    fun mkdir(path: String)
    fun rmdir(path: String)
    fun delete(path: String)

    /** [onBytes] meldet übertragene Bytes; Rückgabe false = abbrechen. */
    fun upload(source: InputStream, remotePath: String, onBytes: (Long) -> Boolean)

    fun rename(from: String, to: String)

    /** Bricht laufende Operationen von einem anderen Thread aus ab (Verbindung hart schließen). */
    fun abort()
}

object RemotePath {
    fun normalizeBase(path: String): String {
        var p = path.trim()
        if (p == "~") p = "" else if (p.startsWith("~/")) p = p.substring(2)
        while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }

    fun join(base: String, name: String): String = when {
        base.isEmpty() -> name
        base.endsWith("/") -> base + name
        else -> "$base/$name"
    }
}

/** Wählt anhand des Protokolls die passende Verbindung und enthält die gemeinsamen Prüfungen. */
class RemoteConnector(
    private val sftp: SftpConnector,
    private val ftps: FtpsConnector,
    private val secrets: SecretStore,
) {

    /**
     * @param onAbort erhält vor dem Verbindungsaufbau eine Funktion, mit der ein anderer Thread
     *                die Verbindung hart abbrechen kann (Upload abbrechen).
     */
    @Throws(TransferException::class)
    fun open(
        cfg: ServerConfig,
        trustFingerprint: String? = null,
        onAbort: ((() -> Unit) -> Unit)? = null,
    ): RemoteFs = when (cfg.protocol) {
        ServerConfig.Protocol.SFTP -> {
            val conn = sftp.connect(cfg, trustFingerprint) { session -> onAbort?.invoke { session.disconnect() } }
            SftpRemoteFs(conn, sftp)
        }
        ServerConfig.Protocol.FTPS -> ftps.connect(cfg, secrets.get(SecretStore.PASSWORD), onAbort)
    }

    /**
     * Prüft, ob der Basispfad existiert und ein Verzeichnis ist. Mit [writeTest] zusätzlich ein echter
     * Schreibtest: Testordner anlegen, kleine Datei hochladen (öffnet eine Datenverbindung – bei FTPS die
     * häufigste Fehlerquelle), per Verzeichnisliste prüfen und alles wieder entfernen.
     */
    @Throws(TransferException::class)
    fun checkBasePath(fs: RemoteFs, basePath: String, writeTest: Boolean) {
        val shown = if (basePath.isEmpty()) "Das Startverzeichnis des Zugangs" else "Der Basispfad „$basePath“"
        val stat = fs.stat(basePath.ifEmpty { "." })
            ?: throw TransferException(ErrorCode.BASE_PATH_MISSING, "$shown existiert nicht auf dem Server.")
        if (!stat.isDir) {
            throw TransferException(ErrorCode.BASE_PATH_NOT_DIR, "$shown ist kein Verzeichnis.")
        }
        if (!writeTest) return

        val probeDir = RemotePath.join(basePath, ".exponio_probe_" + System.currentTimeMillis())
        try {
            fs.mkdir(probeDir)
        } catch (e: TransferException) {
            if (e.code == ErrorCode.PERMISSION_DENIED) {
                throw TransferException(ErrorCode.PERMISSION_DENIED, "Keine Schreibrechte: $shown ist schreibgeschützt.", cause = e)
            }
            throw e
        }
        val probeFile = RemotePath.join(probeDir, PROBE_NAME)
        try {
            fs.upload(ByteArrayInputStream(PROBE_BYTES), probeFile) { true }
            val size = fs.listFiles(probeDir)[PROBE_NAME]
            if (size != PROBE_BYTES.size.toLong()) {
                throw TransferException(
                    ErrorCode.VERIFY_FAILED,
                    "Schreibtest: Die Testdatei wurde nicht vollständig gespeichert (${size ?: "fehlt"} statt ${PROBE_BYTES.size} Bytes).",
                )
            }
        } finally {
            // Aufräumen nur im eigenen Testordner; Fehler dabei sind für das Ergebnis unerheblich.
            try {
                fs.delete(probeFile)
            } catch (_: TransferException) {
            }
            try {
                fs.rmdir(probeDir)
            } catch (_: TransferException) {
            }
        }
    }

    private companion object {
        const val PROBE_NAME = "probe.txt"
        val PROBE_BYTES = "Exponio Schreibtest\n".toByteArray(Charsets.UTF_8)
    }
}
