package org.exponio.app.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import org.exponio.app.remote.RemoteFs
import org.exponio.app.remote.RemoteStat
import java.io.InputStream

/** [RemoteFs] über eine bestehende JSch-SFTP-Verbindung. */
class SftpRemoteFs(
    private val conn: SftpConnection,
    private val connector: SftpConnector,
) : RemoteFs {

    override val serverInfo: String = conn.session.serverVersion ?: ""
    override val hostKeyFingerprint: String = HostKeyStore.fingerprintOf(conn.hostKey)
    override val hostKeyType: String = HostKeyStore.keyTypeOf(conn.hostKey)
    override val newlyTrusted: Boolean = conn.newlyTrusted

    override fun stat(path: String): RemoteStat? = op("Pfad prüfen") {
        SftpConnector.statOrNull(conn.sftp, path)?.let { RemoteStat(it.isDir, it.size) }
    }

    override fun listFiles(path: String): Map<String, Long> = op("Ordner lesen") {
        val result = HashMap<String, Long>()
        for (entry in conn.sftp.ls(path)) {
            val name = entry.filename
            if (name == "." || name == ".." || entry.attrs.isDir) continue
            result[name] = entry.attrs.size
        }
        result
    }

    override fun mkdir(path: String) = op("Ordner anlegen") { conn.sftp.mkdir(path) }

    override fun rmdir(path: String) = op("Ordner entfernen") { conn.sftp.rmdir(path) }

    override fun delete(path: String) = op("Datei entfernen") { conn.sftp.rm(path) }

    override fun upload(source: InputStream, remotePath: String, onBytes: (Long) -> Boolean) = op("Upload") {
        val monitor = object : SftpProgressMonitor {
            override fun init(op: Int, src: String?, dest: String?, max: Long) {}
            override fun count(count: Long): Boolean = onBytes(count)
            override fun end() {}
        }
        conn.sftp.put(source, remotePath, monitor, ChannelSftp.OVERWRITE)
    }

    override fun rename(from: String, to: String) = op("Umbenennen") { conn.sftp.rename(from, to) }

    override fun abort() {
        conn.session.disconnect()
    }

    override fun close() = conn.close()

    private inline fun <T> op(action: String, block: () -> T): T = try {
        block()
    } catch (e: SftpException) {
        throw connector.mapSftpError(e, conn, action)
    }
}
