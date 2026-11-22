package org.exponio.app.transfer

import android.view.WindowManager
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.exponio.app.ftps.FtpsConnector
import org.exponio.app.image.ImageProcessor
import org.exponio.app.remote.RemoteConnector
import org.exponio.app.remote.RemotePath
import org.exponio.app.security.SecretStore
import org.exponio.app.sftp.ErrorCode
import org.exponio.app.sftp.HostKeyStore
import org.exponio.app.sftp.ServerConfig
import org.exponio.app.sftp.SftpConnector
import org.exponio.app.sftp.TransferException
import org.exponio.app.sftp.Uploader
import org.exponio.app.store.PhotoStore
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Brücke zwischen Web-UI und nativer Logik: Gruppenverwaltung, Zugangsdaten, Host-Keys (SFTP),
 * Verbindungstest und Upload (SFTP oder FTPS). Netzwerk & Bildverarbeitung laufen auf eigenen Threads,
 * damit der Capacitor-Plugin-Thread (und damit z. B. cancelUpload) nie blockiert.
 */
@CapacitorPlugin(name = "PtTransfer")
class TransferPlugin : Plugin() {

    private lateinit var store: PhotoStore
    private lateinit var secrets: SecretStore
    private lateinit var hostKeys: HostKeyStore
    private lateinit var connector: SftpConnector
    private lateinit var remote: RemoteConnector
    private lateinit var uploader: Uploader

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "pt-io") }
    private val networkExecutor: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "pt-net") }
    private val uploadRunning = AtomicBoolean(false)

    override fun load() {
        store = PhotoStore.get(context)
        secrets = SecretStore.get(context)
        hostKeys = HostKeyStore.get(context)
        connector = SftpConnector(secrets, hostKeys)
        remote = RemoteConnector(connector, FtpsConnector(), secrets)
        uploader = Uploader(context, remote, store)
    }

    // ------------------------------------------------------------------------------------------
    // Foto-Gruppe
    // ------------------------------------------------------------------------------------------

    @PluginMethod
    fun listPhotos(call: PluginCall) {
        ioExecutor.execute {
            try {
                val photos = JSArray()
                store.list().forEach { photos.put(PluginJson.photo(it)) }
                call.resolve(JSObject().put("photos", photos))
            } catch (e: Exception) {
                call.reject("Fotos konnten nicht geladen werden: ${e.message}", "IO_ERROR")
            }
        }
    }

    @PluginMethod
    fun deletePhotos(call: PluginCall) {
        if (uploadRunning.get()) {
            call.reject("Während des Uploads kann nichts gelöscht werden.", ErrorCode.BUSY)
            return
        }
        val ids = stringList(call, "ids")
        ioExecutor.execute {
            store.delete(ids)
            call.resolve()
        }
    }

    @PluginMethod
    fun rotatePhoto(call: PluginCall) {
        if (uploadRunning.get()) {
            call.reject("Während des Uploads kann nichts gedreht werden.", ErrorCode.BUSY)
            return
        }
        val id = call.getString("id").orEmpty()
        val quarterTurns = call.getInt("quarterTurns", 1) ?: 1
        ioExecutor.execute {
            try {
                call.resolve(PluginJson.photo(store.rotate(id, quarterTurns)))
            } catch (e: OutOfMemoryError) {
                call.reject("Zu wenig Arbeitsspeicher zum Drehen.", "IO_ERROR")
            } catch (e: Exception) {
                call.reject("Foto konnte nicht gedreht werden: ${e.message}", "IO_ERROR")
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Zugangsdaten (write-only aus Sicht der WebView)
    // ------------------------------------------------------------------------------------------

    @PluginMethod
    fun setSecret(call: PluginCall) {
        val name = call.getString("name")
        if (name == null || name !in SecretStore.NAMES) {
            call.reject("Unbekanntes Secret.", "INVALID_ARGUMENT")
            return
        }
        val value = call.getString("value")?.let { if (name == SecretStore.PRIVATE_KEY) normalizeKey(it) else it }
        ioExecutor.execute {
            try {
                if (name == SecretStore.PRIVATE_KEY && !value.isNullOrBlank()) {
                    // Vor dem Speichern prüfen, ob JSch den Key lesen kann.
                    connector.inspectKey(value, null)
                }
                secrets.put(name, value?.takeIf { it.isNotEmpty() })
                call.resolve(secretStatus())
            } catch (e: TransferException) {
                reject(call, e)
            } catch (e: Exception) {
                call.reject("Speichern fehlgeschlagen: ${e.message}", "IO_ERROR")
            }
        }
    }

    @PluginMethod
    fun getSecretStatus(call: PluginCall) {
        ioExecutor.execute {
            try {
                call.resolve(secretStatus())
            } catch (e: Exception) {
                call.reject("Status nicht lesbar: ${e.message}", "IO_ERROR")
            }
        }
    }

    private fun secretStatus(): JSObject {
        val result = JSObject()
        result.put("password", secrets.has(SecretStore.PASSWORD))
        result.put("passphrase", secrets.has(SecretStore.PASSPHRASE))
        val key = secrets.get(SecretStore.PRIVATE_KEY)
        result.put("privateKey", key != null)
        if (key != null) {
            try {
                val info = connector.inspectKey(key, secrets.get(SecretStore.PASSPHRASE))
                result.put("keyType", info.type)
                result.put("keyEncrypted", info.encrypted)
                if (info.passphraseOk != null) result.put("passphraseOk", info.passphraseOk)
            } catch (e: TransferException) {
                result.put("keyError", e.message)
            }
        }
        return result
    }

    // ------------------------------------------------------------------------------------------
    // Host-Keys (TOFU)
    // ------------------------------------------------------------------------------------------

    @PluginMethod
    fun getHostKey(call: PluginCall) {
        val host = call.getString("host").orEmpty()
        val port = call.getInt("port", 22) ?: 22
        val stored = if (host.isBlank()) null else hostKeys.get(host, port)
        val result = JSObject()
        if (stored != null) {
            result.put("fingerprint", stored.fingerprint)
            result.put("keyType", stored.type)
        }
        call.resolve(result)
    }

    @PluginMethod
    fun resetHostKey(call: PluginCall) {
        val host = call.getString("host").orEmpty()
        val port = call.getInt("port", 22) ?: 22
        if (host.isNotBlank()) hostKeys.remove(host, port)
        call.resolve()
    }

    // ------------------------------------------------------------------------------------------
    // Verbindungstest
    // ------------------------------------------------------------------------------------------

    @PluginMethod
    fun testConnection(call: PluginCall) {
        val cfg = try {
            serverConfig(call)
        } catch (e: TransferException) {
            reject(call, e)
            return
        }
        val trust = call.getString("trustFingerprint")?.takeIf { it.isNotBlank() }
        val writeTest = call.getBoolean("writeTest", true) ?: true
        networkExecutor.execute {
            try {
                remote.open(cfg, trust).use { fs ->
                    remote.checkBasePath(fs, RemotePath.normalizeBase(cfg.basePath), writeTest)
                    val result = JSObject()
                    result.put("ok", true)
                    result.put("protocol", cfg.protocol.id)
                    result.put("serverVersion", fs.serverInfo)
                    result.put("fingerprint", fs.hostKeyFingerprint ?: "")
                    result.put("keyType", fs.hostKeyType ?: "")
                    result.put("newlyTrusted", fs.newlyTrusted)
                    result.put("writeTested", writeTest)
                    call.resolve(result)
                }
            } catch (e: TransferException) {
                reject(call, e)
            } catch (e: Exception) {
                call.reject("Verbindungstest fehlgeschlagen: ${e.message}", ErrorCode.UNKNOWN)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------------------------------

    @PluginMethod
    fun upload(call: PluginCall) {
        val cfg = try {
            serverConfig(call)
        } catch (e: TransferException) {
            reject(call, e)
            return
        }
        val image = imageSettings(call.getObject("image") ?: JSObject())
        val ids = stringList(call, "photoIds")

        if (!uploadRunning.compareAndSet(false, true)) {
            call.reject("Es läuft bereits ein Upload.", ErrorCode.BUSY)
            return
        }
        setKeepScreenOn(true)
        networkExecutor.execute {
            try {
                val result = uploader.run(cfg, image, ids) { progress ->
                    notifyListeners("uploadProgress", PluginJson.progress(progress))
                }
                val json = JSObject()
                json.put("remotePath", result.remotePath)
                json.put("dirName", result.dirName)
                json.put("count", result.count)
                json.put("bytes", result.bytes)
                call.resolve(json)
            } catch (e: TransferException) {
                reject(call, e)
            } catch (e: Throwable) {
                call.reject("Unerwarteter Fehler: ${e.message ?: e.javaClass.simpleName}", ErrorCode.UNKNOWN)
            } finally {
                uploadRunning.set(false)
                setKeepScreenOn(false)
            }
        }
    }

    @PluginMethod
    fun cancelUpload(call: PluginCall) {
        if (uploadRunning.get()) uploader.cancel()
        call.resolve()
    }

    @PluginMethod
    fun getUploadState(call: PluginCall) {
        ioExecutor.execute {
            val result = JSObject()
            result.put("uploading", uploadRunning.get())
            val job = uploader.pendingJob()
            if (job != null) {
                result.put("pending", true)
                result.put("dirName", job.dirName)
                result.put("total", job.photoIds.size)
                result.put("uploaded", job.uploaded.size)
                result.put("host", job.host)
                result.put("basePath", job.basePath)
            } else {
                result.put("pending", false)
            }
            call.resolve(result)
        }
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        // Laufende Uploads dürfen zu Ende laufen; der Prozess lebt meist weiter.
        ioExecutor.shutdown()
    }

    // ------------------------------------------------------------------------------------------
    // Hilfsfunktionen
    // ------------------------------------------------------------------------------------------

    private fun setKeepScreenOn(on: Boolean) {
        val act = activity ?: return
        act.runOnUiThread {
            if (on) {
                act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    @Throws(TransferException::class)
    private fun serverConfig(call: PluginCall): ServerConfig {
        val server = call.getObject("server")
            ?: throw TransferException(ErrorCode.NOT_CONFIGURED, "Server-Einstellungen fehlen.")
        val protocol = ServerConfig.Protocol.parse(server.getString("protocol"))
        val host = server.getString("host").orEmpty().trim()
        val username = server.getString("username").orEmpty().trim()
        val port = server.getInteger("port", protocol.defaultPort) ?: protocol.defaultPort
        // FTPS kennt nur Passwort-Anmeldung.
        val auth = if (protocol == ServerConfig.Protocol.SFTP && server.getString("auth") == "key") {
            ServerConfig.AuthMethod.KEY
        } else {
            ServerConfig.AuthMethod.PASSWORD
        }
        val basePath = server.getString("basePath").orEmpty()
        if (host.isEmpty() || username.isEmpty()) {
            throw TransferException(ErrorCode.NOT_CONFIGURED, "Server-Adresse und Benutzername müssen eingetragen sein.")
        }
        return ServerConfig(host, port, username, auth, basePath, protocol)
    }

    private fun imageSettings(json: JSObject): Uploader.ImageSettings {
        val maxEdge = (json.getInteger("maxEdge", 1600) ?: 1600).coerceIn(200, 10000)
        val quality = (json.getInteger("quality", 80) ?: 80).coerceIn(1, 100)
        val format = ImageProcessor.Format.parse(json.getString("format"))
        val keepMetadata = json.getBoolean("keepMetadata", true) ?: true
        return Uploader.ImageSettings(maxEdge, format, quality, keepMetadata)
    }

    private fun stringList(call: PluginCall, name: String): List<String> {
        val array = call.getArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optString(i, null)?.takeIf { it.isNotEmpty() } }
    }

    /** Zeilenumbrüche vereinheitlichen und Whitespace trimmen (häufig beim Einfügen aus Messengern). */
    private fun normalizeKey(raw: String): String {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        return if (text.isEmpty()) text else text + "\n"
    }

    private fun reject(call: PluginCall, e: TransferException) {
        val data = JSObject()
        e.details.forEach { (k, v) -> data.put(k, v ?: JSONObject.NULL) }
        call.reject(e.message ?: e.code, e.code, null, data)
    }
}
