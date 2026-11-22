package org.exponio.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Komplett native Bildverarbeitung (kein WebView-Canvas):
 *  - EXIF-Ausrichtung lesen und in die Pixel "einbacken"
 *  - speicherschonend dekodieren (BitmapFactory.Options.inSampleSize)
 *  - auf maximale Kantenlänge verkleinern (nie vergrößern)
 *  - als WebP (lossy) oder JPEG mit wählbarer Qualität speichern
 *  - optional EXIF-Metadaten ins Ergebnis übernehmen (Orientation = normal)
 */
object ImageProcessor {

    private const val TAG = "PtImage"

    /** Lange Kante der Vorschau für die Lightbox (nur über inSampleSize erreicht, daher ≥ dieser Wert). */
    private const val PREVIEW_TARGET_EDGE = 2000

    /** Obergrenze der Vorschau, falls die Kamera ungewöhnlich große Bilder liefert. */
    private const val PREVIEW_MAX_EDGE = 2600

    /** Kurze Kante der Thumbnails im 4er-Raster. */
    private const val THUMB_SHORT_EDGE = 360

    enum class Format(val extension: String, val compressFormat: Bitmap.CompressFormat) {
        WEBP("webp", Bitmap.CompressFormat.WEBP_LOSSY),
        JPEG("jpg", Bitmap.CompressFormat.JPEG);

        companion object {
            fun parse(value: String?): Format =
                if (value.equals("jpeg", ignoreCase = true) || value.equals("jpg", ignoreCase = true)) JPEG else WEBP
        }
    }

    data class Size(val width: Int, val height: Int)

    /** Metadaten, die beim Web-Export übernommen werden (Orientation wird bewusst auf "normal" gesetzt). */
    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
    )

    // ------------------------------------------------------------------------------------------
    // Öffentliche API
    // ------------------------------------------------------------------------------------------

    /**
     * Erzeugt aus einer frischen Aufnahme die Lightbox-Vorschau und das Raster-Thumbnail.
     * Beide sind bereits korrekt gedreht und enthalten keine Metadaten.
     *
     * @return Pixelmaße der Vorschau (für das Seitenverhältnis in der Lightbox)
     */
    @Throws(IOException::class)
    fun createDerivatives(source: File, previewOut: File, thumbOut: File): Size {
        val bounds = decodeBounds(source)
        val orientation = readOrientation(source)
        val sample = sampleSizeFor(max(bounds.width, bounds.height), PREVIEW_TARGET_EDGE)
        val decoded = decodeSampled(source, sample)

        val preview = transform(decoded, orientation, PREVIEW_MAX_EDGE)
        if (preview !== decoded) decoded.recycle()

        try {
            writeAtomically(previewOut) { out -> preview.compress(Bitmap.CompressFormat.JPEG, 88, out) }

            val shortEdge = min(preview.width, preview.height)
            val thumb = if (shortEdge > THUMB_SHORT_EDGE) {
                val factor = THUMB_SHORT_EDGE.toFloat() / shortEdge
                downscaleProgressive(
                    preview,
                    max(1, (preview.width * factor).roundToInt()),
                    max(1, (preview.height * factor).roundToInt()),
                )
            } else {
                preview
            }
            try {
                writeAtomically(thumbOut) { out -> thumb.compress(Bitmap.CompressFormat.JPEG, 80, out) }
            } finally {
                if (thumb !== preview) thumb.recycle()
            }
            return Size(preview.width, preview.height)
        } finally {
            preview.recycle()
        }
    }

    /**
     * Web-Optimierung eines Originalfotos.
     *
     * @param maxEdge maximale Kantenlänge in Pixeln (kleinere Bilder werden nicht vergrößert)
     * @param quality Kompressionsqualität 1–100
     * @param keepMetadata EXIF-Metadaten (Kamera, Belichtung, Datum, ggf. GPS) übernehmen
     */
    @Throws(IOException::class)
    fun processForWeb(
        source: File,
        target: File,
        maxEdge: Int,
        format: Format,
        quality: Int,
        keepMetadata: Boolean,
    ): Size {
        val bounds = decodeBounds(source)
        val orientation = readOrientation(source)
        val sample = sampleSizeFor(max(bounds.width, bounds.height), maxEdge)
        val decoded = decodeSampled(source, sample)

        // Nach inSampleSize ist der Rest-Skalierungsfaktor < 2 → ein bilinearer Schritt genügt.
        val result = transform(decoded, orientation, maxEdge)
        if (result !== decoded) decoded.recycle()

        val size = Size(result.width, result.height)
        val q = quality.coerceIn(1, 100)
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            writeTo(tmp) { out -> result.compress(format.compressFormat, q, out) }
        } finally {
            result.recycle()
        }

        if (keepMetadata) {
            copyExif(source, tmp, size)
        }

        if (target.exists() && !target.delete()) throw IOException("Kann ${target.name} nicht ersetzen")
        if (!tmp.renameTo(target)) throw IOException("Kann ${target.name} nicht speichern")
        return size
    }

    /** Liest nur den Header (Abmessungen), ohne Pixel zu dekodieren. */
    @Throws(IOException::class)
    fun decodeBounds(file: File): Size {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            throw IOException("Bild kann nicht gelesen werden: ${file.name}")
        }
        return Size(opts.outWidth, opts.outHeight)
    }

    // ------------------------------------------------------------------------------------------
    // Interna
    // ------------------------------------------------------------------------------------------

    private fun readOrientation(file: File): Int = try {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        Log.w(TAG, "EXIF nicht lesbar (${file.name}): ${e.message}")
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Größte Zweierpotenz, bei der die lange Kante nach dem Dekodieren noch ≥ target ist. */
    private fun sampleSizeFor(longEdge: Int, target: Int): Int {
        var sample = 1
        if (target <= 0) return sample
        while (longEdge / (sample * 2) >= target) sample *= 2
        return sample
    }

    @Throws(IOException::class)
    private fun decodeSampled(file: File, sample: Int): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IOException("Bild kann nicht dekodiert werden: ${file.name}")
    }

    /** Matrix für alle 8 EXIF-Orientierungen (inkl. gespiegelter Varianten). */
    private fun orientationMatrix(orientation: Int): Matrix {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                m.setRotate(180f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.setRotate(-90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> Unit
        }
        return m
    }

    /** Dreht gemäß EXIF und verkleinert (nie vergrößern) in einem gefilterten Schritt. */
    private fun transform(src: Bitmap, orientation: Int, maxLongEdge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        val scale = if (maxLongEdge in 1 until longEdge) maxLongEdge.toFloat() / longEdge else 1f
        val m = orientationMatrix(orientation)
        if (scale != 1f) m.postScale(scale, scale)
        if (m.isIdentity) return src
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Hochwertiges Verkleinern um große Faktoren: wiederholtes Halbieren, dann finaler Schritt. */
    private fun downscaleProgressive(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        var current = src
        while (current.width / 2 >= targetW && current.height / 2 >= targetH) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== src) current.recycle()
            current = next
        }
        if (current.width == targetW && current.height == targetH) return current
        val result = Bitmap.createScaledBitmap(current, targetW, targetH, true)
        if (current !== src && current !== result) current.recycle()
        return result
    }

    private fun copyExif(source: File, target: File, size: Size) {
        try {
            val src = ExifInterface(source)
            val dst = ExifInterface(target)
            for (tag in EXIF_TAGS) {
                val value = src.getAttribute(tag)
                if (!value.isNullOrEmpty()) dst.setAttribute(tag, value)
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
            dst.saveAttributes()
        } catch (e: Exception) {
            // Metadaten sind "nice to have" – das Bild selbst muss gültig bleiben.
            Log.w(TAG, "EXIF konnte nicht übernommen werden (${target.name}): ${e.message}")
        }
        // Sicherheitsnetz: Falls das Schreiben der Metadaten die Datei beschädigt hätte → Fehler.
        decodeBounds(target)
    }

    private inline fun writeTo(file: File, block: (OutputStream) -> Boolean) {
        file.parentFile?.mkdirs()
        val ok = FileOutputStream(file).use { fos ->
            val buffered = BufferedOutputStream(fos, 64 * 1024)
            val result = block(buffered)
            buffered.flush()
            fos.fd.sync()
            result
        }
        if (!ok) {
            file.delete()
            throw IOException("Bild konnte nicht kodiert werden: ${file.name}")
        }
    }

    private inline fun writeAtomically(file: File, block: (OutputStream) -> Boolean) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        writeTo(tmp, block)
        if (file.exists() && !file.delete()) throw IOException("Kann ${file.name} nicht ersetzen")
        if (!tmp.renameTo(file)) throw IOException("Kann ${file.name} nicht speichern")
    }
}
