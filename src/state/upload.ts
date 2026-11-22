import { computed, reactive } from 'vue'
import { PtTransfer, isNative, toNativeError } from '../native/plugins'
import type { NativeError, UploadProgress, UploadResult, UploadState } from '../native/plugins'
import { connection } from './connection'
import { flushDeletes, loadGroup, photos } from './group'
import { imagePayload, serverPayload } from './settings'
import { setVolumeKeys } from './camera'
import { showToast } from './toast'

export type UploadStatus = 'idle' | 'running' | 'error' | 'done'

export const upload = reactive({
  status: 'idle' as UploadStatus,
  progress: null as UploadProgress | null,
  error: null as NativeError | null,
  result: null as UploadResult | null,
  pending: null as UploadState | null,
  cancelling: false,
})

export const isUploading = computed(() => upload.status === 'running')

let listenerReady = false
let doneTimer: number | undefined

export async function initUploadListener(): Promise<void> {
  if (!isNative || listenerReady) return
  listenerReady = true
  await PtTransfer.addListener('uploadProgress', (p) => {
    if (upload.status === 'running') upload.progress = p
  })
}

export async function refreshUploadState(): Promise<void> {
  if (!isNative) return
  try {
    const state = await PtTransfer.getUploadState()
    upload.pending = state.pending ? state : null
    if (state.uploading && upload.status !== 'running') {
      // WebView wurde während eines laufenden Uploads neu geladen.
      upload.status = 'running'
    }
  } catch {
    upload.pending = null
  }
}

export async function startUpload(): Promise<void> {
  if (upload.status === 'running') return
  window.clearTimeout(doneTimer)
  await flushDeletes()

  const ids = photos.value.map((p) => p.id)
  if (ids.length === 0) return

  upload.status = 'running'
  upload.error = null
  upload.result = null
  upload.cancelling = false
  upload.progress = { phase: 'process', current: 0, total: ids.length, fraction: 0 }
  void setVolumeKeys(false)

  try {
    const result = await PtTransfer.upload({ server: serverPayload(), image: imagePayload(), photoIds: ids })
    upload.result = result
    upload.status = 'done'
    upload.pending = null
    connection.state = 'ok'
    connection.message = ''
    connection.checkedAt = Date.now()
    await loadGroup()
    showToast(`${result.count} ${result.count === 1 ? 'Foto' : 'Fotos'} hochgeladen → ${result.dirName}`, {
      tone: 'success',
      duration: 4500,
    })
    doneTimer = window.setTimeout(() => {
      if (upload.status === 'done') upload.status = 'idle'
    }, 6000)
  } catch (e) {
    const err = toNativeError(e)
    if (err.code === 'CANCELLED') {
      upload.status = 'idle'
      upload.error = null
      showToast(err.message)
    } else {
      upload.status = 'error'
      upload.error = err
      if (err.code === 'HOST_KEY_UNKNOWN' || err.code === 'HOST_KEY_CHANGED') {
        connection.state = 'hostkey'
        connection.message = err.message
      } else if (
        ['UNREACHABLE', 'TIMEOUT', 'HOST_NOT_FOUND', 'CONNECTION_LOST', 'AUTH_FAILED', 'TLS_CERT_INVALID', 'TLS_ERROR'].includes(err.code)
      ) {
        connection.state = 'error'
        connection.message = err.message
      }
    }
    // Lokale Fotos bleiben unverändert – Liste trotzdem neu laden (Quelle der Wahrheit ist nativ).
    await loadGroup()
  } finally {
    upload.progress = null
    upload.cancelling = false
    void setVolumeKeys(true)
    await refreshUploadState()
  }
}

export async function cancelUpload(): Promise<void> {
  if (upload.status !== 'running') return
  upload.cancelling = true
  await PtTransfer.cancelUpload()
}

export function dismissUploadError(): void {
  if (upload.status === 'error') {
    upload.status = 'idle'
    upload.error = null
  }
}

export function progressText(p: UploadProgress | null, host: string): string {
  if (!p) return 'Upload wird vorbereitet…'
  switch (p.phase) {
    case 'process':
      return `Bild ${Math.max(1, p.current)} von ${p.total} wird optimiert…`
    case 'connect':
      return `Verbinde mit ${host}…`
    case 'upload':
      return `Bild ${p.current} von ${p.total} wird hochgeladen…`
    case 'finalize':
      return 'Upload wird geprüft und abgeschlossen…'
  }
}
