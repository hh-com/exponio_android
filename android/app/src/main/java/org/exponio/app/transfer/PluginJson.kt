package org.exponio.app.transfer

import com.getcapacitor.JSObject
import org.exponio.app.sftp.Uploader
import org.exponio.app.store.PhotoStore

/** Gemeinsame JSON-Abbildungen für beide Plugins. */
object PluginJson {

    fun photo(photo: PhotoStore.Photo): JSObject {
        val json = JSObject()
        json.put("id", photo.id)
        json.put("original", photo.original.absolutePath)
        json.put("preview", photo.preview.absolutePath)
        json.put("thumb", photo.thumb.absolutePath)
        json.put("width", photo.width)
        json.put("height", photo.height)
        return json
    }

    fun progress(progress: Uploader.Progress): JSObject {
        val json = JSObject()
        json.put("phase", progress.phase)
        json.put("current", progress.current)
        json.put("total", progress.total)
        json.put("fraction", progress.fraction)
        return json
    }
}
