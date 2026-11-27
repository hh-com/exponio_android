package org.exponio.app.upload

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.exponio.app.api.ApiClient
import org.exponio.app.api.ServerConfig
import org.exponio.app.image.ImageProcessor
import org.exponio.app.store.PhotoStore
import org.exponio.app.transfer.ErrorCode
import org.exponio.app.transfer.TransferException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * One-Way-Upload einer Foto-Gruppe an die Exponio-Website:
 *
 *  1. Alle Fotos nativ web-optimieren (Cache in files/upload/work, wiederverwendbar beim Retry).
 *  2. Verbinden: Token und Schreibrechte der Website prüfen (GET /status).
 *  3. Fragen, was vom Upload schon auf dem Server liegt (GET /uploads/<Zeitstempel>).
 *  4. Fehlende Dateien `01.webp`, `02.webp`, … einzeln senden (PUT, mit SHA-256). Die Website prüft jede Datei
 *     und legt sie in `_tmp_<Zeitstempel>/` ab. Gibt es eine Notiz zur Gruppe, folgt sie als `info.txt` (UTF-8).
 *  5. Abschließen (POST /complete mit der vollständigen Dateiliste): Die Website prüft den Ordnerinhalt und
 *     benennt ihn atomar in `<Zeitstempel>` um (bei Kollision `…_2`).
 *  6. Erst danach lokale Gruppe (Fotos + Notiz) löschen.
 *
 * Wohin die Dateien auf dem Server kommen, entscheidet die Website. Der Fortschritt steht in files/upload/job.json:
 * Bricht die Verbindung ab (oder wird die App beendet), setzt der nächste Versuch mit demselben Zeitstempel fort und
 * sendet nur, was auf dem Server fehlt oder abweicht. Ging nur die Bestätigung des Abschlusses verloren, findet die
 * Website den fertigen Ordner wieder, statt einen zweiten anzulegen.
 */
class Uploader(
    context: Context,
    private val store: PhotoStore,
    private val userAgent: String,
) {

    data class ImageSettings(
        val maxEdge: Int,
        val format: ImageProcessor.Format,
        val quality: Int,
        val keepMetadata: Boolean,
    ) {
        val key: String get() = "${maxEdge}_${format.extension}_${quality}_${if (keepMetadata) "m" else "n"}"
    }

    data class Progress(val phase: String, val current: Int, val total: Int, val fraction: Double)

    data class Result(val remotePath: String, val dirName: String, val count: Int, val bytes: Long)

    class Job(
        val baseUrl: String,
        val dirName: String,
        /** Abschluss wurde gesendet, die Antwort steht aus – beim nächsten Versuch zuerst nachfragen. */
        var completing: Boolean,
        /** Vom Server bestätigte Dateien (nur für die Anzeige „x/y“; maßgeblich ist beim Fortsetzen der Server). */
        val uploaded: MutableSet<String>,
        var photoIds: List<String>,
    ) {
        val host: String get() = try {
            ServerConfig(baseUrl).host
        } catch (_: Exception) {
            baseUrl
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("baseUrl", baseUrl)
            put("dirName", dirName)
            put("completing", completing)
            put("uploaded", JSONArray().apply { uploaded.forEach { put(it) } })
            put("photoIds", JSONArray().apply { photoIds.forEach { put(it) } })
        }

        companion object {
            fun fromJson(json: JSONObject): Job {
                val uploadedJson = json.optJSONArray("uploaded") ?: JSONArray()
                val idsJson = json.optJSONArray("photoIds") ?: JSONArray()
                return Job(
                    baseUrl = json.getString("baseUrl"),
                    dirName = json.getString("dirName"),
                    completing = json.optBoolean("completing", false),
                    uploaded = (0 until uploadedJson.length()).map { uploadedJson.getString(it) }.toMutableSet(),
                    photoIds = (0 until idsJson.length()).map { idsJson.getString(it) },
                )
            }
        }
    }

    /** Eine hochzuladende Datei. [label] benennt sie in Fehlermeldungen („Bild 3“, „Notiz“). */
    private class Entry(val remoteName: String, val file: File, val size: Long, val sha256: String, val label: String) {
        fun describe() = ApiClient.RemoteFile(remoteName, size, sha256)
    }

    private val baseDir = File(context.filesDir, "upload")
    private val workDir = File(baseDir, "work")
    private val jobFile = File(baseDir, "job.json")

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var activeClient: ApiClient? = null

    private var lastEmit = 0L
    private var lastPhase = ""
    private var lastCurrent = -1

    init {
        workDir.mkdirs()
    }

    fun cancel() {
        cancelled.set(true)
        val client = activeClient ?: return
        // Schließen kann blockieren → nicht auf dem aufrufenden Thread.
        Thread({
            try {
                client.abort()
            } catch (_: Exception) {
            }
        }, "pt-cancel").start()
    }

    @Synchronized
    fun pendingJob(): Job? = loadJob()

    /** Läuft blockierend auf einem Hintergrund-Thread. */
    @Throws(TransferException::class)
    fun run(
        cfg: ServerConfig,
        token: String,
        image: ImageSettings,
        photoIds: List<String>,
        onProgress: (Progress) -> Unit,
    ): Result {
        cancelled.set(false)
        lastPhase = ""
        lastCurrent = -1

        if (photoIds.isEmpty()) {
            throw TransferException(ErrorCode.NOTHING_TO_UPLOAD, "Die Gruppe enthält keine Fotos.")
        }
        photoIds.forEachIndexed { index, id ->
            if (!store.exists(id)) {
                throw TransferException(ErrorCode.PHOTO_MISSING, "Foto ${index + 1} fehlt lokal – bitte Gruppe neu laden.")
            }
        }

        var job = loadJob()
        if (job == null || job.baseUrl != cfg.baseUrl) {
            job = Job(
                baseUrl = cfg.baseUrl,
                dirName = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()),
                completing = false,
                uploaded = mutableSetOf(),
                photoIds = photoIds,
            )
        }
        job.photoIds = photoIds
        saveJob(job)

        // ---- Phase 1: Web-Optimierung (nativ) -------------------------------------------------
        val total = photoIds.size
        val digits = max(2, total.toString().length)
        val entries = ArrayList<Entry>(total + 1)
        photoIds.forEachIndexed { index, id ->
            checkCancelled()
            emit(onProgress, Progress(PHASE_PROCESS, index + 1, total, PROCESS_WEIGHT * index / total), force = true)
            // Ausrichtung im Namen: Wird ein Foto nach einem abgebrochenen Upload gedreht, gilt der Zwischenstand nicht mehr.
            val orientation = ImageProcessor.readOrientation(store.originalFile(id))
            val work = File(workDir, "${id}_o${orientation}_${image.key}.${image.format.extension}")
            if (!work.isFile || work.length() == 0L) {
                try {
                    ImageProcessor.processForWeb(
                        store.originalFile(id),
                        work,
                        image.maxEdge,
                        image.format,
                        image.quality,
                        image.keepMetadata,
                    )
                } catch (e: OutOfMemoryError) {
                    throw TransferException(ErrorCode.PROCESSING_FAILED, "Bild ${index + 1}: zu wenig Arbeitsspeicher.")
                } catch (e: Exception) {
                    throw TransferException(
                        ErrorCode.PROCESSING_FAILED,
                        "Bild ${index + 1} konnte nicht optimiert werden: ${e.message}",
                        cause = e,
                    )
                }
            }
            val remoteName = (index + 1).toString().padStart(digits, '0') + "." + image.format.extension
            entries += Entry(remoteName, work, work.length(), sha256(work), "Bild ${index + 1}")
        }
        // Notiz als letzte Datei: Ihr Inhalt steckt im Namen der Arbeitsdatei, eine Änderung nach einem
        // abgebrochenen Upload wird also wie ein gedrehtes Foto erneut übertragen.
        noteEntry()?.let { entries += it }
        // Veraltete Zwischenstände (andere Einstellungen, gelöschte Fotos, geänderte Notiz) aufräumen.
        val keep = entries.map { it.file.name }.toSet()
        workDir.listFiles()?.forEach { if (it.name !in keep) it.delete() }

        // ---- Phase 2: Verbinden + Übertragen ---------------------------------------------------
        checkCancelled()
        emit(onProgress, Progress(PHASE_CONNECT, 0, total, PROCESS_WEIGHT), force = true)
        val client = ApiClient(cfg, token, userAgent)
        activeClient = client
        try {
            if (cancelled.get()) throw cancelledException()
            return transfer(client, job, entries, cfg, onProgress)
        } catch (e: TransferException) {
            if (cancelled.get()) throw cancelledException()
            throw e
        } catch (e: Exception) {
            if (cancelled.get()) throw cancelledException()
            Log.e(TAG, "Upload fehlgeschlagen", e)
            throw TransferException(
                ErrorCode.CONNECTION_LOST,
                "Upload unterbrochen: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        } finally {
            activeClient = null
        }
    }

    private fun transfer(
        client: ApiClient,
        job: Job,
        entries: List<Entry>,
        cfg: ServerConfig,
        onProgress: (Progress) -> Unit,
    ): Result {
        // Token und Schreibrechte prüfen, bevor Daten fließen
        client.status(writeTest = false)

        val total = job.photoIds.size
        val manifest = entries.map { it.describe() }

        // Sonderfall: Abschluss wurde beim letzten Versuch gesendet, die Bestätigung ging aber verloren.
        if (job.completing) {
            try {
                return finish(job, entries, client.complete(job.dirName, manifest), cfg)
            } catch (e: TransferException) {
                if (e.code != ErrorCode.UPLOAD_NOT_FOUND && e.code != ErrorCode.VERIFY_FAILED) throw e
                job.completing = false
                saveJob(job)
            }
        }

        // Was liegt schon auf dem Server? Gleiche Prüfsumme = schon übertragen, alles andere wird (neu) gesendet.
        val remote = client.openFiles(job.dirName).orEmpty().associateBy { it.name }
        job.uploaded.retainAll(remote.keys)
        val totalBytes = max(1L, entries.sumOf { it.size })
        var doneBytes = 0L

        entries.forEachIndexed { index, entry ->
            checkCancelled()
            // Der Fortschritt zählt Bilder; die Notiz am Ende läuft unter dem letzten Bild mit.
            val current = minOf(index + 1, total)
            val there = remote[entry.remoteName]
            if (there == null || there.size != entry.size || there.sha256 != entry.sha256) {
                emit(onProgress, Progress(PHASE_UPLOAD, current, total, uploadFraction(doneBytes, totalBytes)), force = true)
                job.uploaded.remove(entry.remoteName)
                saveJob(job)

                val startBytes = doneBytes
                var sent = 0L
                val saved = client.putFile(job.dirName, entry.remoteName, entry.file, entry.sha256) { count ->
                    sent += count
                    emit(
                        onProgress,
                        Progress(PHASE_UPLOAD, current, total, uploadFraction(startBytes + sent, totalBytes)),
                        force = false,
                    )
                    !cancelled.get()
                }
                checkCancelled()
                if (saved.size != entry.size || saved.sha256 != entry.sha256) {
                    throw TransferException(
                        ErrorCode.VERIFY_FAILED,
                        "${entry.label}: Die Website bestätigt eine andere Datei (${saved.size} statt ${entry.size} Bytes).",
                    )
                }
            }
            job.uploaded.add(entry.remoteName)
            saveJob(job)
            doneBytes += entry.size
            emit(onProgress, Progress(PHASE_UPLOAD, current, total, uploadFraction(doneBytes, totalBytes)), force = true)
        }

        // ---- Phase 3: Abschluss – die Website prüft alles und benennt den Ordner atomar um -----
        checkCancelled()
        emit(onProgress, Progress(PHASE_FINALIZE, total, total, 0.98), force = true)
        job.completing = true
        saveJob(job)
        val completed = try {
            client.complete(job.dirName, manifest)
        } catch (e: TransferException) {
            // Eindeutig nicht abgeschlossen → nächster Versuch gleicht wieder mit dem Server ab
            if (e.code == ErrorCode.VERIFY_FAILED || e.code == ErrorCode.UPLOAD_NOT_FOUND) {
                job.completing = false
                saveJob(job)
            }
            throw e
        }
        return finish(job, entries, completed, cfg)
    }

    private fun finish(job: Job, entries: List<Entry>, completed: ApiClient.Completed, cfg: ServerConfig): Result {
        store.delete(job.photoIds)
        store.clearNote()
        workDir.listFiles()?.forEach { it.delete() }
        synchronized(this) { jobFile.delete() }
        return Result("${cfg.host} › ${completed.folder}", completed.folder, job.photoIds.size, entries.sumOf { it.size })
    }

    /**
     * Notiz der Gruppe als Arbeitsdatei, oder null ohne Notiz. Zeilenenden werden vereinheitlicht, Leerraum
     * am Anfang und Ende entfernt; die Datei ist UTF-8 ohne BOM und endet mit einem Zeilenumbruch.
     */
    private fun noteEntry(): Entry? {
        val text = store.note().replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return null
        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        val hash = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val work = File(workDir, "note_${hash.take(16)}.txt")
        if (!work.isFile || work.length() != bytes.size.toLong()) work.writeBytes(bytes)
        return Entry(NOTE_FILE_NAME, work, bytes.size.toLong(), hash, "Notiz")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun uploadFraction(done: Long, total: Long): Double =
        PROCESS_WEIGHT + UPLOAD_WEIGHT * (done.toDouble() / total).coerceIn(0.0, 1.0)

    /** Drosselt Fortschritts-Events auf ~10/s, Phasen- und Bildwechsel gehen immer sofort raus. */
    private fun emit(onProgress: (Progress) -> Unit, progress: Progress, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val changed = progress.phase != lastPhase || progress.current != lastCurrent
        if (!force && !changed && now - lastEmit < 100) return
        lastEmit = now
        lastPhase = progress.phase
        lastCurrent = progress.current
        try {
            onProgress(progress)
        } catch (e: Exception) {
            Log.w(TAG, "Fortschritt konnte nicht gemeldet werden: ${e.message}")
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw cancelledException()
    }

    private fun cancelledException() = TransferException(
        ErrorCode.CANCELLED,
        "Upload abgebrochen. Die Fotos bleiben auf dem Gerät erhalten.",
    )

    @Synchronized
    private fun loadJob(): Job? {
        if (!jobFile.isFile) return null
        return try {
            Job.fromJson(JSONObject(jobFile.readText()))
        } catch (e: Exception) {
            // auch: Job eines älteren App-Stands (SFTP/FTPS) – die Fotos liegen ja noch lokal
            Log.w(TAG, "Job-Datei unbrauchbar, wird verworfen: ${e.message}")
            jobFile.delete()
            null
        }
    }

    @Synchronized
    private fun saveJob(job: Job) {
        baseDir.mkdirs()
        val tmp = File(baseDir, "job.json.tmp")
        tmp.writeText(job.toJson().toString())
        if (!tmp.renameTo(jobFile)) {
            jobFile.writeText(job.toJson().toString())
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "PtUploader"
        const val NOTE_FILE_NAME = "info.txt"

        const val PHASE_PROCESS = "process"
        const val PHASE_CONNECT = "connect"
        const val PHASE_UPLOAD = "upload"
        const val PHASE_FINALIZE = "finalize"

        private const val PROCESS_WEIGHT = 0.25
        private const val UPLOAD_WEIGHT = 0.72
    }
}
