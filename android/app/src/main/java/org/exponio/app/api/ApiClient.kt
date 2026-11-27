package org.exponio.app.api

import org.exponio.app.transfer.ErrorCode
import org.exponio.app.transfer.TransferException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Upload-Schnittstelle der Exponio-Website (/api/app/v1, Gegenstück: AppUploadController in exponio_website).
 *
 * Anmeldung mit „Authorization: Bearer <Token>“ (Backend → System → App-Zugänge), nur über HTTPS mit gültigem
 * Zertifikat (System-CAs). Weiterleitungen werden nicht verfolgt, damit das Token nie an eine andere Adresse geht.
 * Alle Fehler kommen als [TransferException] mit verständlicher Meldung.
 */
/** @param userAgent z. B. „Exponio-App/1.0.0 (Android)“ – erscheint im Log des Webservers */
class ApiClient(private val cfg: ServerConfig, private val token: String, private val userAgent: String) {

    data class Status(val site: String, val api: Int, val writeTested: Boolean)

    data class RemoteFile(val name: String, val size: Long, val sha256: String)

    data class Completed(val folder: String, val count: Int, val bytes: Long, val already: Boolean)

    @Volatile
    private var active: HttpURLConnection? = null

    @Volatile
    private var aborted = false

    /** Bricht die laufende Anfrage von einem anderen Thread aus ab (Verbindung hart schließen). */
    fun abort() {
        aborted = true
        active?.disconnect()
    }

    @Throws(TransferException::class)
    fun status(writeTest: Boolean): Status {
        val json = request("GET", "/status" + if (writeTest) "?writeTest=1" else "")
        val api = json.optInt("api", 0)
        if (api != API_VERSION) {
            throw TransferException(
                ErrorCode.NOT_EXPONIO,
                "Die Website spricht Schnittstellen-Version $api, die App Version $API_VERSION. Bitte App und Website aktualisieren.",
            )
        }
        return Status(json.optString("site", cfg.host), api, json.optBoolean("writeTested", false))
    }

    /** Dateien eines offenen Uploads auf dem Server; null = es gibt keinen. */
    @Throws(TransferException::class)
    fun openFiles(id: String): List<RemoteFile>? {
        val json = request("GET", "/uploads/$id")
        if (json.optString("state") != "open") return null
        return files(json.optJSONArray("files"))
    }

    /** [onBytes] meldet gesendete Bytes; Rückgabe false = abbrechen. */
    @Throws(TransferException::class)
    fun putFile(id: String, name: String, file: File, sha256: String, onBytes: (Long) -> Boolean): RemoteFile {
        val length = file.length()
        val json = request("PUT", "/uploads/$id/files/$name") { conn ->
            conn.setFixedLengthStreamingMode(length)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-Content-SHA256", sha256)
            conn.outputStream.use { out ->
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (!onBytes(read.toLong())) {
                            abort()
                            throw IOException("abgebrochen")
                        }
                    }
                }
            }
        }
        return RemoteFile(json.optString("name"), json.optLong("size", -1), json.optString("sha256"))
    }

    /** Schließt den Upload ab; die Website benennt den Ordner atomar um und meldet seinen Namen. */
    @Throws(TransferException::class)
    fun complete(id: String, files: List<RemoteFile>): Completed {
        val body = JSONObject().put(
            "files",
            JSONArray().apply {
                files.forEach { put(JSONObject().put("name", it.name).put("size", it.size).put("sha256", it.sha256)) }
            },
        ).toString().toByteArray(Charsets.UTF_8)
        val json = request("POST", "/uploads/$id/complete") { conn ->
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body) }
        }
        val folder = json.optString("folder")
        if (folder.isEmpty()) throw notExponio()
        return Completed(folder, json.optInt("count"), json.optLong("bytes"), json.optBoolean("already"))
    }

    // ------------------------------------------------------------------------------------------

    private fun request(method: String, path: String, send: ((HttpURLConnection) -> Unit)? = null): JSONObject {
        if (aborted) throw cancelled()
        val conn = try {
            URL(cfg.baseUrl + PREFIX + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw TransferException(ErrorCode.NOT_CONFIGURED, "Die Adresse der Website ist ungültig.", cause = e)
        }
        active = conn
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", userAgent)
            if (send != null) {
                conn.doOutput = true
                send(conn)
            }
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { readLimited(it) }.orEmpty()
            val json = if (conn.contentType?.startsWith("application/json") == true) parse(text) else null
            if (status in 200..299) return json ?: throw notExponio()
            throw httpError(status, json, conn.getHeaderField("Location"))
        } catch (e: TransferException) {
            throw e
        } catch (e: IOException) {
            if (aborted) throw cancelled()
            throw networkError(e)
        } finally {
            active = null
            conn.disconnect()
        }
    }

    private fun httpError(status: Int, json: JSONObject?, location: String?): TransferException {
        val error = json?.optJSONObject("error")
        if (error != null) {
            val details = mutableMapOf<String, Any?>("status" to status)
            error.keys().forEach { key -> if (key != "code" && key != "message") details[key] = error.opt(key)?.toString() }
            return TransferException(
                error.optString("code").ifEmpty { "HTTP_$status" },
                error.optString("message").ifEmpty { "Die Website meldet einen Fehler (HTTP $status)." },
                details,
            )
        }
        return when (status) {
            in 300..399 -> TransferException(
                ErrorCode.REDIRECT,
                if (location.isNullOrBlank()) {
                    "Die Adresse leitet weiter (HTTP $status). Bitte die endgültige Adresse der Website eintragen."
                } else {
                    "Die Adresse leitet weiter nach $location. Bitte diese Adresse (ohne Pfad) eintragen."
                },
            )
            401, 403 -> TransferException(ErrorCode.AUTH_FAILED, "Die Website lehnt das Token ab (HTTP $status).")
            404 -> TransferException(
                ErrorCode.NOT_EXPONIO,
                "Unter ${cfg.baseUrl} gibt es keine Exponio-Schnittstelle (404). Stimmt die Adresse, und ist die Website aktuell?",
            )
            413 -> TransferException(
                "FILE_TOO_LARGE",
                "Der Webserver lehnt die Datei als zu groß ab (413). Upload-Limit erhöhen (z. B. nginx client_max_body_size) oder in der App eine kleinere Kantenlänge wählen.",
            )
            in 500..599 -> TransferException(ErrorCode.SERVER_ERROR, "Die Website meldet einen Serverfehler (HTTP $status). Später erneut versuchen.")
            else -> TransferException("HTTP_$status", "Die Website antwortet unerwartet (HTTP $status).")
        }
    }

    private fun networkError(e: IOException): TransferException {
        val host = cfg.host
        return when {
            e is UnknownHostException -> TransferException(
                ErrorCode.HOST_NOT_FOUND,
                "„$host“ wurde nicht gefunden. Adresse und Internetverbindung prüfen.",
                cause = e,
            )
            e is SocketTimeoutException -> TransferException(ErrorCode.TIMEOUT, "Zeitüberschreitung: $host antwortet nicht.", cause = e)
            e is ConnectException || e is NoRouteToHostException -> TransferException(
                ErrorCode.UNREACHABLE,
                "$host ist nicht erreichbar.",
                cause = e,
            )
            e is SSLPeerUnverifiedException || (e is SSLHandshakeException && hasCertificateCause(e)) -> TransferException(
                ErrorCode.TLS_CERT_INVALID,
                "Das HTTPS-Zertifikat von $host ist ungültig oder passt nicht zur Adresse. Nichts wurde gesendet.",
                cause = e,
            )
            e is SSLException -> TransferException(ErrorCode.TLS_ERROR, "Verschlüsselte Verbindung zu $host fehlgeschlagen: ${e.message}", cause = e)
            else -> TransferException(ErrorCode.CONNECTION_LOST, "Verbindung zu $host unterbrochen: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
    }

    private fun hasCertificateCause(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is CertPathValidatorException || t is CertificateException) return true
            t = t.cause
        }
        return false
    }

    private fun files(array: JSONArray?): List<RemoteFile> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val f = array.optJSONObject(i) ?: return@mapNotNull null
            RemoteFile(f.optString("name"), f.optLong("size", -1), f.optString("sha256"))
        }
    }

    private fun parse(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (_: JSONException) {
        null
    }

    /** Antworten sind klein; eine fremde Seite (z. B. HTML-Fehlerseite) wird nicht komplett gelesen. */
    private fun readLimited(input: InputStream): String {
        val bytes = input.readNBytes(MAX_RESPONSE)
        return String(bytes, Charsets.UTF_8)
    }

    private fun notExponio() = TransferException(
        ErrorCode.NOT_EXPONIO,
        "${cfg.host} antwortet, aber nicht wie eine Exponio-Website. Stimmt die Adresse?",
    )

    private fun cancelled() = TransferException(ErrorCode.CANCELLED, "Upload abgebrochen. Die Fotos bleiben auf dem Gerät erhalten.")

    companion object {
        const val API_VERSION = 1
        private const val PREFIX = "/api/app/v1"
        private const val CHUNK = 64 * 1024
        private const val MAX_RESPONSE = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}
