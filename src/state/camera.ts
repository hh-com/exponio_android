import { reactive } from 'vue'
import { PtCamera, isNative, toNativeError } from '../native/plugins'
import type { CameraRect, CameraState, FlashMode } from '../native/plugins'
import { settings } from './settings'

export type CameraPermission = 'unknown' | 'granted' | 'prompt' | 'denied'

export const camera = reactive({
  permission: 'unknown' as CameraPermission,
  /** true sobald CameraX gebunden ist */
  running: false,
  starting: false,
  error: '',
  hasFlash: false,
  zoom: 1,
  minZoom: 1,
  maxZoom: 1,
  /** UI-Drehung in Grad, damit Symbole bei quer gehaltenem Gerät aufrecht bleiben */
  rotation: 0,
})

let rect: CameraRect | null = null
let wantActive = false
let chain: Promise<void> = Promise.resolve()
let listenersReady = false

function applyState(state: CameraState): void {
  camera.running = state.running
  camera.hasFlash = state.hasFlash
  camera.zoom = state.zoom
  camera.minZoom = state.minZoom
  camera.maxZoom = state.maxZoom
  camera.rotation = state.rotation
}

export async function initCameraListeners(onVolumeShutter: () => void, onCaptureStarted: () => void): Promise<void> {
  if (!isNative || listenersReady) return
  listenersReady = true
  await PtCamera.addListener('volumeShutter', onVolumeShutter)
  await PtCamera.addListener('captureStarted', onCaptureStarted)
  await PtCamera.addListener('zoomChanged', (z) => {
    camera.zoom = z.zoom
    camera.minZoom = z.minZoom
    camera.maxZoom = z.maxZoom
  })
  await PtCamera.addListener('orientationChanged', (e) => {
    camera.rotation = e.rotation
  })
}

export async function refreshPermission(): Promise<CameraPermission> {
  if (!isNative) {
    camera.permission = 'denied'
    return camera.permission
  }
  try {
    const { camera: state } = await PtCamera.checkPermissions()
    camera.permission = state === 'granted' ? 'granted' : state === 'denied' ? 'denied' : 'prompt'
  } catch {
    camera.permission = 'denied'
  }
  void reconcile()
  return camera.permission
}

export async function requestPermission(): Promise<CameraPermission> {
  if (!isNative) return 'denied'
  try {
    const { camera: state } = await PtCamera.requestPermissions({ permissions: ['camera'] })
    camera.permission = state === 'granted' ? 'granted' : 'denied'
  } catch {
    camera.permission = 'denied'
  }
  void reconcile()
  return camera.permission
}

export function openAppSettings(): void {
  void PtCamera.openAppSettings()
}

/** Position des Vorschau-Fensters (CSS-Pixel relativ zum Viewport) melden. */
export function setCameraRect(next: CameraRect | null): void {
  rect = next
  if (rect && camera.running) {
    void PtCamera.updateRect(rect).catch(() => undefined)
  }
  void reconcile()
}

export function setCameraActive(active: boolean): void {
  if (active && !wantActive) camera.error = ''
  wantActive = active
  void reconcile()
}

/** Nach einem Startfehler erneut versuchen. */
export function restartCamera(): void {
  camera.error = ''
  void reconcile()
}

/** Start/Stop strikt nacheinander abarbeiten, damit sich schnelle Wechsel nicht überholen. */
function reconcile(): Promise<void> {
  chain = chain.then(doReconcile, doReconcile)
  return chain
}

async function doReconcile(): Promise<void> {
  if (!isNative) return
  const shouldRun = wantActive && camera.permission === 'granted' && rect !== null
  // Nach einem Fehler nicht endlos neu starten – erst restartCamera() oder erneutes Aktivieren.
  if (shouldRun && !camera.running && !camera.error) {
    camera.starting = true
    camera.error = ''
    try {
      const state = await PtCamera.start({
        ...(rect as CameraRect),
        flash: settings.camera.flash,
        volumeKeys: settings.camera.volumeKeys,
      })
      applyState(state)
      // Rechteck kann sich während des Starts geändert haben.
      if (rect) await PtCamera.updateRect(rect)
    } catch (e) {
      camera.running = false
      camera.error = toNativeError(e).message
    } finally {
      camera.starting = false
    }
  } else if (!shouldRun && camera.running) {
    try {
      await PtCamera.stop()
    } catch (e) {
      console.warn('Kamera-Stopp fehlgeschlagen', toNativeError(e))
    } finally {
      camera.running = false
    }
  }
}

export async function setFlash(mode: FlashMode): Promise<void> {
  settings.camera.flash = mode
  if (isNative) await PtCamera.setFlash({ mode })
}

export async function setZoom(ratio: number): Promise<void> {
  const clamped = Math.min(camera.maxZoom, Math.max(camera.minZoom, ratio))
  camera.zoom = clamped
  if (isNative && camera.running) await PtCamera.setZoom({ ratio: clamped })
}

export async function focusAt(x: number, y: number): Promise<void> {
  if (isNative && camera.running) await PtCamera.focus({ x, y })
}

export async function setVolumeKeys(enabled: boolean): Promise<void> {
  if (isNative) await PtCamera.setVolumeKeys({ enabled: enabled && settings.camera.volumeKeys })
}
