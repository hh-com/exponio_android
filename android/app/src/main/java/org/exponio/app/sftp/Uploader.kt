package org.exponio.app.sftp

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.exponio.app.image.ImageProcessor
import org.exponio.app.remote.RemoteConnector
import org.exponio.app.remote.RemoteFs
import org.exponio.app.remote.RemotePath
import org.exponio.app.store.PhotoStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * One-Way-Upload einer Foto-Gruppe:
 *
 *  1. Alle Fotos nativ web-optimieren (Cache in files/upload/work, wiederverwendbar beim Retry).
 *  2. Verbinden (SFTP: Host-Key muss bekannt sein; FTPS: TLS-Zertifikat muss gültig sein), Basispfad prüfen.
 *  3. Temporären Ordner `_tmp_<Zeitstempel>` anlegen bzw. wiederverwenden.
 *  4. Dateien `01.webp`, `02.webp`, … hochladen, jede per stat() auf exakte Größe verifizieren.
 *  5. Vollständigen Ordnerinhalt verifizieren, dann atomar per rename in `<Zeitstempel>` umbenennen.
 *  6. Erst danach lokale Gruppe löschen.
 *
 * Der Fortschritt wird in files/upload/job.json persistiert: Bricht die Verbindung ab (oder wird die App
 * beendet), setzt der nächste Versuch im selben temporären Ordner fort und überspringt bereits
 * verifizierte Dateien. Auf dem Server wird nie etwas außerhalb des eigenen _tmp_-Ordners gelöscht.
 */
class Uploader(
    context: Context,
    private val remote: RemoteConnector,
    private val store: PhotoStore,
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
        val protocol: String,
        val host: String,
        val port: Int,
        val username: String,
        val basePath: String,
        val dirName: String,
        var finalName: String?,
        val uploaded: MutableMap<String, String>,
        var photoIds: List<String>,
    ) {
        fun matches(cfg: ServerConfig): Boolean =
            protocol == cfg.protocol.id &&
                host == HostKeyStore.normalizeHost(cfg.host) &&
                port == cfg.port &&
                username == cfg.username.trim() &&
                basePath == RemotePath.normalizeBase(cfg.basePath)

        fun toJson(): JSONObject = JSONObject().apply {
            put("protocol", protocol)
            put("host", host)
            put("port", port)
            put("username", username)
            put("basePath", basePath)
            put("dirName", dirName)
            put("finalName", finalName ?: JSONObject.NULL)
            put("uploaded", JSONObject().apply { uploaded.forEach { (k, v) -> put(k, v) } })
            put("photoIds", JSONArray().apply { photoIds.forEach { put(it) } })
        }

        companion object {
            fun fromJson(json: JSONObject): Job {
                val uploadedJson = json.optJSONObject("uploaded") ?: JSONObject()
                val uploaded = mutableMapOf<String, String>()
                uploadedJson.keys().forEach { k -> uploaded[k] = uploadedJson.getString(k) }
                val idsJson = json.optJSONArray("photoIds") ?: JSONArray()
                val ids = (0 until idsJson.length()).map { idsJson.getString(it) }
                return Job(
                    protocol = json.optString("protocol", ServerConfig.Protocol.SFTP.id),
                    host = json.getString("host"),
                    port = json.getInt("port"),
                    username = json.getString("username"),
                    basePath = json.getString("basePath"),
                    dirName = json.getString("dirName"),
                    finalName = if (json.isNull("finalName")) null else json.optString("finalName"),
                    uploaded = uploaded,
                    photoIds = ids,
                )
            }
        }
    }

    private class Entry(val remoteName: String, val file: File, val key: String, val size: Long)

    private val baseDir = File(context.filesDir, "upload")
    private val workDir = File(baseDir, "work")
    private val jobFile = File(baseDir, "job.json")

    private val cancelled = AtomicBoolean(false)

    /** Bricht die aktive Verbindung hart ab (von einem anderen Thread aus). */
    @Volatile
    private var activeAbort: (() -> Unit)? = null

    private var lastEmit = 0L
    private var lastPhase = ""
    private var lastCurrent = -1

    init {
        workDir.mkdirs()
    }

    fun cancel() {
        cancelled.set(true)
        val abort = activeAbort ?: return
        // Schließen kann blockieren → nicht auf dem aufrufenden Thread.
        Thread({
            try {
                abort()
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
        if (job == null || !job.matches(cfg)) {
            job = Job(
                protocol = cfg.protocol.id,
                host = HostKeyStore.normalizeHost(cfg.host),
                port = cfg.port,
                username = cfg.username.trim(),
                basePath = RemotePath.normalizeBase(cfg.basePath),
                dirName = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()),
                finalName = null,
                uploaded = mutableMapOf(),
                photoIds = photoIds,
            )
        }
        job.photoIds = photoIds
        saveJob(job)

        // ---- Phase 1: Web-Optimierung (nativ) -------------------------------------------------
        val total = photoIds.size
        val digits = max(2, total.toString().length)
        val entries = ArrayList<Entry>(total)
        photoIds.forEachIndexed { index, id ->
            checkCancelled()
            emit(onProgress, Progress(PHASE_PROCESS, index + 1, total, PROCESS_WEIGHT * index / total), force = true)
            val work = File(workDir, "${id}_${image.key}.${image.format.extension}")
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
            entries += Entry(remoteName, work, work.name, work.length())
        }
        // Veraltete Zwischenstände (andere Einstellungen, gelöschte Fotos) aufräumen.
        val keep = entries.map { it.file.name }.toSet()
        workDir.listFiles()?.forEach { if (it.name !in keep) it.delete() }

        // ---- Phase 2: Verbinden + Übertragen ---------------------------------------------------
        checkCancelled()
        emit(onProgress, Progress(PHASE_CONNECT, 0, total, PROCESS_WEIGHT), force = true)
        val fs = try {
            remote.open(cfg, null) { abort -> activeAbort = abort }
        } catch (e: TransferException) {
            activeAbort = null
            if (cancelled.get()) throw cancelledException()
            throw e
        }
        try {
            return transfer(fs, job, entries, onProgress)
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
            activeAbort = null
            fs.close()
        }
    }

    private fun transfer(
        fs: RemoteFs,
        job: Job,
        entries: List<Entry>,
        onProgress: (Progress) -> Unit,
    ): Result {
        val base = job.basePath
        remote.checkBasePath(fs, base, writeTest = false)

        val tmpName = TMP_PREFIX + job.dirName
        val tmpPath = RemotePath.join(base, tmpName)
        val total = entries.size
        val totalBytes = max(1L, entries.sumOf { it.size })
        val expectedNames = entries.map { it.remoteName }.toSet()

        // Sonderfall: Rename wurde beim letzten Versuch ausgeführt, die Bestätigung ging aber verloren.
        job.finalName?.let { finalName ->
            val finalPath = RemotePath.join(base, finalName)
            val tmpExists = fs.stat(tmpPath) != null
            val finalStat = fs.stat(finalPath)
            if (!tmpExists && finalStat?.isDir == true && listingMatches(fs.listFiles(finalPath), entries)) {
                return finish(job, entries, finalPath, finalName)
            }
            job.finalName = null
            saveJob(job)
        }

        val tmpStat = fs.stat(tmpPath)
        if (tmpStat == null) {
            fs.mkdir(tmpPath)
            job.uploaded.clear()
            saveJob(job)
        } else if (!tmpStat.isDir) {
            throw TransferException(ErrorCode.REMOTE_ERROR, "„$tmpName“ existiert auf dem Server bereits als Datei.")
        }

        // Reste früherer Versuche (andere Nummerierung/Format) – nur im eigenen _tmp_-Ordner.
        val remoteBefore = fs.listFiles(tmpPath)
        for (name in remoteBefore.keys) {
            if (name !in expectedNames) {
                try {
                    fs.delete(RemotePath.join(tmpPath, name))
                } catch (e: TransferException) {
                    Log.w(TAG, "Konnte Altdatei $name nicht entfernen: ${e.message}")
                }
            }
        }

        var doneBytes = 0L
        entries.forEachIndexed { index, entry ->
            checkCancelled()
            val remotePath = RemotePath.join(tmpPath, entry.remoteName)
            val alreadyDone = job.uploaded[entry.remoteName] == entry.key && remoteBefore[entry.remoteName] == entry.size

            if (!alreadyDone) {
                emit(onProgress, Progress(PHASE_UPLOAD, index + 1, total, uploadFraction(doneBytes, totalBytes)), force = true)
                job.uploaded.remove(entry.remoteName)
                saveJob(job)

                // Unvollständige Datei eines früheren Versuchs (nur im eigenen _tmp_-Ordner) zuerst entfernen –
                // viele FTP-Server erlauben kein Überschreiben.
                if (entry.remoteName in remoteBefore) fs.delete(remotePath)

                val startBytes = doneBytes
                var sent = 0L
                FileInputStream(entry.file).use { input ->
                    fs.upload(input, remotePath) { count ->
                        sent += count
                        emit(
                            onProgress,
                            Progress(PHASE_UPLOAD, index + 1, total, uploadFraction(startBytes + sent, totalBytes)),
                            force = false,
                        )
                        !cancelled.get()
                    }
                }
                checkCancelled()

                val remoteSize = fs.stat(remotePath)?.size ?: -1L
                if (remoteSize != entry.size) {
                    throw TransferException(
                        ErrorCode.VERIFY_FAILED,
                        "Bild ${index + 1}: Größe auf dem Server stimmt nicht ($remoteSize statt ${entry.size} Bytes).",
                    )
                }
                job.uploaded[entry.remoteName] = entry.key
                saveJob(job)
            }
            doneBytes += entry.size
            emit(onProgress, Progress(PHASE_UPLOAD, index + 1, total, uploadFraction(doneBytes, totalBytes)), force = true)
        }

        // ---- Phase 3: Gesamtverifikation + atomares Umbenennen ---------------------------------
        checkCancelled()
        emit(onProgress, Progress(PHASE_FINALIZE, total, total, 0.98), force = true)
        val listing = fs.listFiles(tmpPath)
        entries.forEachIndexed { index, entry ->
            val size = listing[entry.remoteName]
            if (size != entry.size) {
                throw TransferException(
                    ErrorCode.VERIFY_FAILED,
                    "Verifikation fehlgeschlagen: ${entry.remoteName} (Bild ${index + 1}) fehlt oder ist unvollständig.",
                )
            }
        }
        for (extra in listing.keys - expectedNames) {
            try {
                fs.delete(RemotePath.join(tmpPath, extra))
            } catch (e: TransferException) {
                throw TransferException(
                    ErrorCode.VERIFY_FAILED,
                    "Unerwartete Datei „$extra“ im Upload-Ordner kann nicht entfernt werden.",
                )
            }
        }

        val finalName = pickFreeName(fs, base, job.dirName)
        val finalPath = RemotePath.join(base, finalName)
        job.finalName = finalName
        saveJob(job)
        checkCancelled()
        fs.rename(tmpPath, finalPath)

        if (fs.stat(finalPath)?.isDir != true) {
            throw TransferException(ErrorCode.VERIFY_FAILED, "Umbenennen des Upload-Ordners wurde vom Server nicht bestätigt.")
        }
        return finish(job, entries, finalPath, finalName)
    }

    private fun finish(job: Job, entries: List<Entry>, finalPath: String, finalName: String): Result {
        store.delete(job.photoIds)
        workDir.listFiles()?.forEach { it.delete() }
        synchronized(this) { jobFile.delete() }
        return Result(finalPath, finalName, entries.size, entries.sumOf { it.size })
    }

    private fun pickFreeName(fs: RemoteFs, base: String, dirName: String): String {
        for (i in 1..99) {
            val name = if (i == 1) dirName else "${dirName}_$i"
            if (fs.stat(RemotePath.join(base, name)) == null) return name
        }
        throw TransferException(ErrorCode.REMOTE_ERROR, "Kein freier Ordnername für „$dirName“ gefunden.")
    }

    private fun listingMatches(listing: Map<String, Long>, entries: List<Entry>): Boolean =
        entries.all { listing[it.remoteName] == it.size }

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
            Log.w(TAG, "Job-Datei defekt, wird verworfen: ${e.message}")
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
        const val TMP_PREFIX = "_tmp_"

        const val PHASE_PROCESS = "process"
        const val PHASE_CONNECT = "connect"
        const val PHASE_UPLOAD = "upload"
        const val PHASE_FINALIZE = "finalize"

        private const val PROCESS_WEIGHT = 0.25
        private const val UPLOAD_WEIGHT = 0.72
    }
}
