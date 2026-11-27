package org.exponio.app.transfer

import android.view.WindowManager
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.exponio.app.api.ApiClient
import org.exponio.app.api.ServerConfig
import org.exponio.app.image.ImageProcessor
import org.exponio.app.security.SecretStore
import org.exponio.app.store.PhotoStore
import org.exponio.app.upload.Uploader
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Brücke zwischen Web-UI und nativer Logik: Gruppenverwaltung, Notiz, Token, Verbindungstest und Upload an die
 * Exponio-Website. Netzwerk & Bildverarbeitung laufen auf eigenen Threads, damit der Capacitor-Plugin-Thread
 * (und damit z. B. cancelUpload) nie blockiert.
 */
@CapacitorPlugin(name = "PtTransfer")
class TransferPlugin : Plugin() {

    private lateinit var store: PhotoStore
    private lateinit var secrets: SecretStore
    private lateinit var uploader: Uploader
    private lateinit var userAgent: String

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "pt-io") }
    private val networkExecutor: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "pt-net") }
    private val uploadRunning = AtomicBoolean(false)

    override fun load() {
        store = PhotoStore.get(context)
        secrets = SecretStore.get(context)
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        userAgent = "Exponio-App/${version ?: "?"} (Android)"
        uploader = Uploader(context, store, userAgent)
        // Vertraute SSH-Host-Keys früherer Versionen (SFTP) werden nicht mehr gebraucht.
        ioExecutor.execute { context.deleteSharedPreferences(LEGACY_HOST_KEYS) }
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

    @PluginMethod
    fun getNote(call: PluginCall) {
        ioExecutor.execute {
            call.resolve(JSObject().put("text", store.note()))
        }
    }

    @PluginMethod
    fun setNote(call: PluginCall) {
        if (uploadRunning.get()) {
            call.reject("Während des Uploads kann die Notiz nicht geändert werden.", ErrorCode.BUSY)
            return
        }
        val text = call.getString("text").orEmpty()
        ioExecutor.execute {
            try {
                store.setNote(text)
                call.resolve()
            } catch (e: Exception) {
                call.reject("Notiz konnte nicht gespeichert werden: ${e.message}", "IO_ERROR")
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Token (write-only aus Sicht der WebView)
    // ------------------------------------------------------------------------------------------

    @PluginMethod
    fun setSecret(call: PluginCall) {
        val name = call.getString("name")
        if (name == null || name !in SecretStore.NAMES) {
            call.reject("Unbekanntes Secret.", "INVALID_ARGUMENT")
            return
        }
        // Beim Kopieren aus Browser oder Messenger rutschen gern Leerzeichen und Zeilenumbrüche mit.
        val value = call.getString("value")?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() }
        if (value != null && !TOKEN_PATTERN.matches(value)) {
            call.reject(
                "Das ist kein Exponio-Token. Es beginnt mit „exp_“ und steht nach dem Anlegen eines App-Zugangs im Backend der Website.",
                ErrorCode.TOKEN_INVALID,
            )
            return
        }
        ioExecutor.execute {
            try {
                secrets.put(name, value)
                call.resolve(secretStatus())
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

    /** Ob ein Token gespeichert ist und seine letzten vier Zeichen – so zeigt sie auch das Backend der Website. */
    private fun secretStatus(): JSObject {
        val result = JSObject()
        val token = secrets.get(SecretStore.TOKEN)
        result.put("token", token != null)
        if (token != null) result.put("tokenHint", token.takeLast(4))
        return result
    }

    // ------------------------------------------------------------------------------------------
    // Verbindungstest
    // ------------------------------------------------------------------------------------------

    @PluginMethod
    fun testConnection(call: PluginCall) {
        val cfg: ServerConfig
        val token: String
        try {
            cfg = serverConfig(call)
            token = token()
        } catch (e: TransferException) {
            reject(call, e)
            return
        }
        val writeTest = call.getBoolean("writeTest", true) ?: true
        networkExecutor.execute {
            try {
                val status = ApiClient(cfg, token, userAgent).status(writeTest)
                val result = JSObject()
                result.put("ok", true)
                result.put("url", cfg.baseUrl)
                result.put("site", status.site)
                result.put("api", status.api)
                result.put("writeTested", status.writeTested)
                call.resolve(result)
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
        val cfg: ServerConfig
        val token: String
        try {
            cfg = serverConfig(call)
            token = token()
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
                val result = uploader.run(cfg, token, image, ids) { progress ->
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
                result.put("uploaded", job.uploaded.count { it != Uploader.NOTE_FILE_NAME })
                result.put("host", job.host)
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
            ?: throw TransferException(ErrorCode.NOT_CONFIGURED, "Die Adresse der Website fehlt.")
        return ServerConfig.parse(server.getString("url"))
    }

    @Throws(TransferException::class)
    private fun token(): String = secrets.get(SecretStore.TOKEN)
        ?: throw TransferException(ErrorCode.NO_CREDENTIALS, "Es ist noch kein Token gespeichert (Einstellungen → Website).")

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

    private fun reject(call: PluginCall, e: TransferException) {
        val data = JSObject()
        e.details.forEach { (k, v) -> data.put(k, v ?: JSONObject.NULL) }
        call.reject(e.message ?: e.code, e.code, null, data)
    }

    private companion object {
        const val LEGACY_HOST_KEYS = "pt_host_keys"

        /** Format der Website: "exp_" + 43 Zeichen Base64url (App\Entity\AppToken) */
        val TOKEN_PATTERN = Regex("^exp_[A-Za-z0-9_-]{20,200}$")
    }
}
