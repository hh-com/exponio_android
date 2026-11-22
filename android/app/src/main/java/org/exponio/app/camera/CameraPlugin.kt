package org.exponio.app.camera

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import org.exponio.app.store.PhotoStore
import org.exponio.app.transfer.PluginJson
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * In-App-Kamera auf Basis von CameraX für schnelle Serienaufnahmen ohne Bestätigungsdialog.
 *
 * Die native PreviewView liegt direkt HINTER der (transparenten) WebView im selben Container.
 * Die Web-UI meldet per start()/updateRect() die Position des Vorschau-Fensters in CSS-Pixeln;
 * alles außerhalb dieses Fensters malt die Web-UI deckend selbst. So bleiben Bedienelemente,
 * Fokus-Ring etc. normales HTML über dem Live-Bild.
 */
@CapacitorPlugin(
    name = "PtCamera",
    permissions = [Permission(strings = [Manifest.permission.CAMERA], alias = "camera")],
)
class CameraPlugin : Plugin() {

    private var previewView: PreviewView? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var startGeneration = 0
    private var orientationListener: OrientationEventListener? = null
    private var targetRotation = Surface.ROTATION_0
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    @Volatile
    private var imageCapture: ImageCapture? = null

    @Volatile
    private var running = false

    @Volatile
    private var volumeKeysEnabled = true

    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "pt-capture") }

    override fun load() {
        orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                // Activity ist auf Hochformat fixiert → Gerätelage separat auswerten, damit
                // quer gehaltene Fotos korrekt ausgerichtet gespeichert werden (EXIF-Orientation).
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation != targetRotation) {
                    targetRotation = rotation
                    imageCapture?.targetRotation = rotation
                    emit("orientationChanged", JSObject().put("rotation", rotationDegrees(rotation)))
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Vorschau starten / stoppen / positionieren
    // ------------------------------------------------------------------------------------------

    @PluginMethod
    fun start(call: PluginCall) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            call.reject("Kamera-Berechtigung fehlt.", "PERMISSION_DENIED")
            return
        }
        val rect = readRect(call)
        if (rect == null) {
            call.reject("Ungültiger Vorschaubereich.", "INVALID_ARGUMENT")
            return
        }
        call.getString("flash")?.let { flashMode = parseFlash(it) }
        volumeKeysEnabled = call.getBoolean("volumeKeys", true) ?: true

        activity.runOnUiThread {
            val view = ensurePreviewView()
            applyRect(view, rect)
            view.visibility = View.VISIBLE

            val generation = ++startGeneration
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (generation != startGeneration) {
                    // Inzwischen wurde stop() aufgerufen.
                    call.resolve(stateJson())
                    return@addListener
                }
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    bindUseCases(cameraProvider, view)
                    call.resolve(stateJson())
                } catch (e: Exception) {
                    running = false
                    imageCapture = null
                    call.reject("Kamera konnte nicht gestartet werden: ${e.message}", "CAMERA_ERROR")
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        activity.runOnUiThread {
            stopInternal()
            call.resolve()
        }
    }

    @PluginMethod
    fun updateRect(call: PluginCall) {
        val rect = readRect(call)
        if (rect == null) {
            call.reject("Ungültiger Vorschaubereich.", "INVALID_ARGUMENT")
            return
        }
        activity.runOnUiThread {
            previewView?.let { applyRect(it, rect) }
            call.resolve()
        }
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        activity.runOnUiThread { call.resolve(stateJson()) }
    }

    // ------------------------------------------------------------------------------------------
    // Aufnahme
    // ------------------------------------------------------------------------------------------

    @PluginMethod
    fun capture(call: PluginCall) {
        val capture = imageCapture
        if (!running || capture == null) {
            call.reject("Kamera ist nicht bereit.", "NOT_READY")
            return
        }
        val store = PhotoStore.get(context)
        val id = store.newId()
        val file = store.incomingFile(id)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        activity.runOnUiThread {
            previewView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }

        capture.takePicture(options, captureExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onCaptureStarted() {
                emit("captureStarted", JSObject().put("id", id))
            }

            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                try {
                    val photo = store.commitCapture(id)
                    call.resolve(PluginJson.photo(photo))
                } catch (e: Exception) {
                    file.delete()
                    call.reject("Foto konnte nicht gespeichert werden: ${e.message}", "SAVE_FAILED")
                } catch (e: OutOfMemoryError) {
                    file.delete()
                    call.reject("Zu wenig Arbeitsspeicher für das Foto.", "SAVE_FAILED")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                call.reject("Aufnahme fehlgeschlagen: ${exception.message}", "CAPTURE_FAILED")
            }
        })
    }

    // ------------------------------------------------------------------------------------------
    // Zoom, Blitz, Fokus
    // ------------------------------------------------------------------------------------------

    @PluginMethod
    fun setZoom(call: PluginCall) {
        val ratio = call.getFloat("ratio")
        if (ratio == null) {
            call.reject("ratio fehlt.", "INVALID_ARGUMENT")
            return
        }
        activity.runOnUiThread {
            val cam = camera
            if (cam == null) {
                call.resolve()
                return@runOnUiThread
            }
            val state = cam.cameraInfo.zoomState.value
            val clamped = if (state != null) ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio) else ratio
            cam.cameraControl.setZoomRatio(clamped)
            call.resolve(JSObject().put("zoom", clamped.toDouble()))
        }
    }

    @PluginMethod
    fun setFlash(call: PluginCall) {
        flashMode = parseFlash(call.getString("mode"))
        imageCapture?.flashMode = flashMode
        call.resolve()
    }

    @PluginMethod
    fun focus(call: PluginCall) {
        val x = call.getFloat("x")
        val y = call.getFloat("y")
        if (x == null || y == null) {
            call.reject("x/y fehlen.", "INVALID_ARGUMENT")
            return
        }
        activity.runOnUiThread {
            val view = previewView
            val cam = camera
            if (view == null || cam == null || view.width == 0 || view.height == 0) {
                call.resolve()
                return@runOnUiThread
            }
            val point = view.meteringPointFactory.createPoint(
                x.coerceIn(0f, 1f) * view.width,
                y.coerceIn(0f, 1f) * view.height,
            )
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
            call.resolve()
        }
    }

    @PluginMethod
    fun setVolumeKeys(call: PluginCall) {
        volumeKeysEnabled = call.getBoolean("enabled", true) ?: true
        call.resolve()
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        call.resolve()
    }

    /** Wird von MainActivity für Lautstärketasten aufgerufen. true = Ereignis verbraucht. */
    fun handleVolumeKey(event: KeyEvent): Boolean {
        if (!running || !volumeKeysEnabled) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            emit("volumeShutter", JSObject())
        }
        return true
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        orientationListener?.disable()
        captureExecutor.shutdown()
    }

    // ------------------------------------------------------------------------------------------
    // Interna (Main-Thread)
    // ------------------------------------------------------------------------------------------

    private fun bindUseCases(cameraProvider: ProcessCameraProvider, view: PreviewView) {
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val captureSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .build()
        preview.setSurfaceProvider(view.surfaceProvider)

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .setJpegQuality(95)
            .setResolutionSelector(captureSelector)
            .setTargetRotation(targetRotation)
            .build()

        camera?.cameraInfo?.zoomState?.removeObservers(activity)
        cameraProvider.unbindAll()
        val cam = cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        camera = cam
        imageCapture = capture
        running = true
        cam.cameraInfo.zoomState.observe(activity) { state -> emit("zoomChanged", zoomJson(state)) }
        orientationListener?.enable()
    }

    private fun stopInternal() {
        startGeneration++
        camera?.cameraInfo?.zoomState?.removeObservers(activity)
        provider?.unbindAll()
        camera = null
        imageCapture = null
        running = false
        orientationListener?.disable()
        previewView?.visibility = View.GONE
    }

    private fun ensurePreviewView(): PreviewView {
        previewView?.let { return it }
        val webView = bridge.webView
        val parent = webView.parent as ViewGroup
        val view = PreviewView(context).apply {
            // TextureView-Modus: korrekte Z-Reihenfolge unter der transparenten WebView.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        parent.addView(view, 0, ViewGroup.LayoutParams(1, 1))
        webView.setBackgroundColor(Color.TRANSPARENT)
        previewView = view
        return view
    }

    /** Rechteck kommt in CSS-Pixeln relativ zum WebView-Viewport. */
    private fun applyRect(view: PreviewView, rect: RectF) {
        val webView = bridge.webView
        val density = context.resources.displayMetrics.density
        val width = max(1, (rect.width() * density).roundToInt())
        val height = max(1, (rect.height() * density).roundToInt())
        val params = view.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            view.layoutParams = params
        }
        view.x = webView.x + rect.left * density
        view.y = webView.y + rect.top * density
    }

    private fun readRect(call: PluginCall): RectF? {
        val x = call.getFloat("x") ?: return null
        val y = call.getFloat("y") ?: return null
        val w = call.getFloat("width") ?: return null
        val h = call.getFloat("height") ?: return null
        if (w <= 0f || h <= 0f) return null
        return RectF(x, y, x + w, y + h)
    }

    private fun stateJson(): JSObject {
        val json = JSObject()
        json.put("running", running)
        val cam = camera
        json.put("hasFlash", cam?.cameraInfo?.hasFlashUnit() ?: false)
        json.put("rotation", rotationDegrees(targetRotation))
        val zoom = cam?.cameraInfo?.zoomState?.value
        json.put("zoom", (zoom?.zoomRatio ?: 1f).toDouble())
        json.put("minZoom", (zoom?.minZoomRatio ?: 1f).toDouble())
        json.put("maxZoom", (zoom?.maxZoomRatio ?: 1f).toDouble())
        return json
    }

    private fun zoomJson(state: ZoomState?): JSObject {
        val json = JSObject()
        json.put("zoom", (state?.zoomRatio ?: 1f).toDouble())
        json.put("minZoom", (state?.minZoomRatio ?: 1f).toDouble())
        json.put("maxZoom", (state?.maxZoomRatio ?: 1f).toDouble())
        return json
    }

    private fun parseFlash(value: String?): Int = when (value) {
        "on" -> ImageCapture.FLASH_MODE_ON
        "auto" -> ImageCapture.FLASH_MODE_AUTO
        else -> ImageCapture.FLASH_MODE_OFF
    }

    private fun rotationDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun emit(event: String, data: JSObject) {
        notifyListeners(event, data)
    }
}
