package org.exponio.app.store

import android.content.Context
import android.util.Log
import org.exponio.app.image.ImageProcessor
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Ablage der aktuellen Foto-Gruppe im app-eigenen Verzeichnis (filesDir, Scoped-Storage-konform,
 * keine Storage-Permissions). Bewusst nicht im cacheDir, da Android den Cache jederzeit leeren darf –
 * die Gruppe soll App-Neustarts und Abstürze überleben, bis der Upload verifiziert ist.
 *
 * Struktur:
 *   files/group/incoming/<id>.jpg  – CameraX schreibt hierhin (unvollständig bis commit), dazu Arbeitskopien beim Drehen
 *   files/group/photos/<id>.jpg    – Originale (Quelle der Wahrheit, Reihenfolge = Dateiname)
 *   files/group/previews/<id>.jpg  – Lightbox-Vorschau (~2000 px, gedreht)
 *   files/group/thumbs/<id>.jpg    – Raster-Thumbnail (~360 px, gedreht)
 *   files/group/note.txt           – Notiz zur Gruppe (wird beim Upload als info.txt mitgeschickt)
 */
class PhotoStore private constructor(context: Context) {

    data class Photo(
        val id: String,
        val original: File,
        val preview: File,
        val thumb: File,
        val width: Int,
        val height: Int,
    )

    private val root = File(context.filesDir, "group")
    private val incomingDir = File(root, "incoming")
    private val photosDir = File(root, "photos")
    private val previewsDir = File(root, "previews")
    private val thumbsDir = File(root, "thumbs")
    private val noteFile = File(root, "note.txt")

    private val sizeCache = ConcurrentHashMap<String, ImageProcessor.Size>()
    private var lastMillis = 0L
    private var counter = 0

    init {
        listOf(incomingDir, photosDir, previewsDir, thumbsDir).forEach { it.mkdirs() }
        // Reste abgebrochener Aufnahmen (App wurde während des Speicherns beendet) entfernen.
        incomingDir.listFiles()?.forEach { it.delete() }
        // Reihenfolge auch dann stabil halten, wenn die Systemuhr zurückgestellt wurde.
        lastMillis = photosDir.listFiles()
            ?.mapNotNull { f -> f.nameWithoutExtension.takeIf { ID_PATTERN.matches(it) }?.substring(0, 13)?.toLongOrNull() }
            ?.maxOrNull() ?: 0L
        counter = 999
    }

    /** Monoton steigende, sortierbare ID: <13-stellige Millisekunden>_<3-stelliger Zähler>. */
    @Synchronized
    fun newId(): String {
        val now = System.currentTimeMillis()
        if (now > lastMillis) {
            lastMillis = now
            counter = 0
        } else if (++counter > 999) {
            lastMillis += 1
            counter = 0
        }
        return String.format(Locale.US, "%013d_%03d", lastMillis, counter)
    }

    fun incomingFile(id: String) = File(incomingDir, "$id.jpg")
    fun originalFile(id: String) = File(photosDir, "$id.jpg")
    private fun previewFile(id: String) = File(previewsDir, "$id.jpg")
    private fun thumbFile(id: String) = File(thumbsDir, "$id.jpg")

    /**
     * Übernimmt eine fertig gespeicherte Aufnahme in die Gruppe: Vorschau + Thumbnail erzeugen,
     * dann das Original atomar per rename nach photos/ verschieben.
     */
    @Throws(IOException::class)
    fun commitCapture(id: String): Photo {
        requireValidId(id)
        val incoming = incomingFile(id)
        if (!incoming.isFile || incoming.length() == 0L) throw IOException("Aufnahme wurde nicht gespeichert")
        val size = ImageProcessor.createDerivatives(incoming, previewFile(id), thumbFile(id))
        val original = originalFile(id)
        if (!incoming.renameTo(original)) {
            incoming.copyTo(original, overwrite = true)
            incoming.delete()
        }
        sizeCache[id] = size
        return Photo(id, original, previewFile(id), thumbFile(id), size.width, size.height)
    }

    /**
     * Dreht ein Foto verlustfrei um Vielfache von 90° im Uhrzeigersinn (nur EXIF-Ausrichtung des Originals)
     * und baut Vorschau + Thumbnail neu. Der Web-Export liest dieselbe Ausrichtung → kommt gedreht an.
     */
    @Synchronized
    @Throws(IOException::class)
    fun rotate(id: String, quarterTurns: Int): Photo {
        requireValidId(id)
        val original = originalFile(id)
        if (!original.isFile) throw IOException("Foto existiert nicht mehr")
        val turns = Math.floorMod(quarterTurns, 4)
        if (turns == 0) return photo(id)

        // Auf einer Kopie arbeiten und atomar ersetzen: Ein Abbruch mittendrin lässt das Original intakt.
        // incoming/ wird beim Start geleert, verwaiste Kopien verschwinden also von selbst.
        val tmp = File(incomingDir, "$id.rotate.jpg")
        try {
            original.copyTo(tmp, overwrite = true)
            ImageProcessor.rotateLossless(tmp, turns * 90)
            ImageProcessor.decodeBounds(tmp)
            // Alte Ableitungen vorher entfernen: Bricht es danach ab, baut photo() sie passend zum Original neu.
            previewFile(id).delete()
            thumbFile(id).delete()
            sizeCache.remove(id)
            if (!tmp.renameTo(original)) throw IOException("Gedrehtes Foto konnte nicht gespeichert werden")
        } finally {
            tmp.delete()
        }
        return photo(id)
    }

    /** Alle Fotos der Gruppe in Aufnahme-Reihenfolge. Fehlende Ableitungen werden nachgebaut. */
    @Synchronized
    fun list(): List<Photo> {
        val originals = photosDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
            ?: emptyList()
        return originals.mapNotNull { file ->
            val id = file.nameWithoutExtension
            if (!ID_PATTERN.matches(id)) return@mapNotNull null
            try {
                photo(id)
            } catch (e: Exception) {
                // Nur vollständig geschriebene Aufnahmen landen in photos/ – ist eine trotzdem nicht
                // dekodierbar, ist sie unbrauchbar und wird entfernt, damit sie den Upload nicht blockiert.
                Log.e(TAG, "Defektes Foto $id wird entfernt: ${e.message}")
                delete(listOf(id))
                null
            }
        }
    }

    @Throws(IOException::class)
    fun photo(id: String): Photo {
        requireValidId(id)
        val original = originalFile(id)
        if (!original.isFile) throw IOException("Foto $id existiert nicht")
        val preview = previewFile(id)
        val thumb = thumbFile(id)
        val size = if (!preview.isFile || !thumb.isFile) {
            ImageProcessor.createDerivatives(original, preview, thumb).also { sizeCache[id] = it }
        } else {
            sizeCache.getOrPut(id) { ImageProcessor.decodeBounds(preview) }
        }
        return Photo(id, original, preview, thumb, size.width, size.height)
    }

    fun exists(id: String): Boolean = ID_PATTERN.matches(id) && originalFile(id).isFile

    @Synchronized
    fun delete(ids: Collection<String>) {
        for (id in ids) {
            if (!ID_PATTERN.matches(id)) continue
            originalFile(id).delete()
            previewFile(id).delete()
            thumbFile(id).delete()
            sizeCache.remove(id)
        }
    }

    /** Notiz zur Gruppe, genau so, wie sie eingegeben wurde. Leer, wenn keine vorhanden ist. */
    @Synchronized
    fun note(): String =
        try {
            if (noteFile.isFile) noteFile.readText() else ""
        } catch (e: IOException) {
            Log.e(TAG, "Notiz nicht lesbar: ${e.message}")
            ""
        }

    /** Speichert die Notiz atomar; eine leere Notiz entfernt die Datei. */
    @Synchronized
    @Throws(IOException::class)
    fun setNote(text: String) {
        val value = text.take(MAX_NOTE_LENGTH)
        if (value.isBlank()) {
            noteFile.delete()
            return
        }
        val tmp = File(root, "note.txt.tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(noteFile)) {
            tmp.delete()
            throw IOException("Notiz konnte nicht gespeichert werden")
        }
    }

    @Synchronized
    fun clearNote() {
        noteFile.delete()
    }

    private fun requireValidId(id: String) {
        if (!ID_PATTERN.matches(id)) throw IOException("Ungültige Foto-ID")
    }

    companion object {
        private const val TAG = "PtStore"

        /** Obergrenze für die Notiz in Zeichen (gleicher Wert wie NOTE_MAX_LENGTH in src/state/note.ts). */
        const val MAX_NOTE_LENGTH = 5000

        /** Schützt zusätzlich vor Pfad-Manipulation über IDs aus der WebView. */
        val ID_PATTERN = Regex("^\\d{13}_\\d{3}$")

        @Volatile
        private var instance: PhotoStore? = null

        fun get(context: Context): PhotoStore =
            instance ?: synchronized(this) {
                instance ?: PhotoStore(context.applicationContext).also { instance = it }
            }
    }
}
