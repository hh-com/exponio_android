import { computed, ref } from 'vue'
import { PtCamera, PtTransfer, fileUrl, isNative, toNativeError } from '../native/plugins'
import type { NativePhoto } from '../native/plugins'
import { showToast } from './toast'

export interface Photo {
  id: string
  thumbUrl: string
  previewUrl: string
  width: number
  height: number
}

const UNDO_MS = 5000

/** Sichtbare Fotos der aktuellen Gruppe (ohne vorgemerkte Löschungen), sortiert nach Aufnahme. */
export const photos = ref<Photo[]>([])

/** Anzahl laufender Aufnahmen (Platzhalter im Raster). */
export const pendingCaptures = ref(0)

export const photoCount = computed(() => photos.value.length)

/** Zuletzt gelöschte Fotos, die per "Rückgängig" wiederhergestellt werden können. */
const pendingDeletes = new Map<string, Photo>()
let deleteTimer: number | undefined

function toPhoto(p: NativePhoto): Photo {
  return {
    id: p.id,
    thumbUrl: fileUrl(p.thumb),
    previewUrl: fileUrl(p.preview),
    width: Math.max(1, p.width),
    height: Math.max(1, p.height),
  }
}

function insertSorted(list: Photo[], photo: Photo): void {
  if (list.some((p) => p.id === photo.id)) return
  const index = list.findIndex((p) => p.id > photo.id)
  if (index === -1) list.push(photo)
  else list.splice(index, 0, photo)
}

export async function loadGroup(): Promise<void> {
  if (!isNative) return
  try {
    const { photos: list } = await PtTransfer.listPhotos()
    photos.value = list.filter((p) => !pendingDeletes.has(p.id)).map(toPhoto)
  } catch (e) {
    showToast(toNativeError(e).message, { tone: 'error' })
  }
}

export async function capturePhoto(): Promise<boolean> {
  pendingCaptures.value++
  try {
    const native = await PtCamera.capture()
    insertSorted(photos.value, toPhoto(native))
    return true
  } catch (e) {
    const err = toNativeError(e)
    if (err.code !== 'NOT_READY') showToast(err.message, { tone: 'error' })
    return false
  } finally {
    pendingCaptures.value--
  }
}

/** Sofort aus der Ansicht entfernen; endgültig gelöscht wird nach Ablauf des Rückgängig-Fensters. */
export function removePhoto(id: string): void {
  const index = photos.value.findIndex((p) => p.id === id)
  if (index === -1) return
  const [photo] = photos.value.splice(index, 1)
  pendingDeletes.set(id, photo)

  window.clearTimeout(deleteTimer)
  deleteTimer = window.setTimeout(() => void flushDeletes(), UNDO_MS)

  const count = pendingDeletes.size
  showToast(count === 1 ? 'Foto gelöscht' : `${count} Fotos gelöscht`, {
    action: { label: 'Rückgängig', run: undoDeletes },
    duration: UNDO_MS,
  })
}

export function undoDeletes(): void {
  window.clearTimeout(deleteTimer)
  for (const photo of pendingDeletes.values()) insertSorted(photos.value, photo)
  pendingDeletes.clear()
}

/** Vorgemerkte Löschungen sofort ausführen (vor Upload, beim Pausieren der App). */
export async function flushDeletes(): Promise<void> {
  window.clearTimeout(deleteTimer)
  if (pendingDeletes.size === 0) return
  const ids = [...pendingDeletes.keys()]
  pendingDeletes.clear()
  try {
    await PtTransfer.deletePhotos({ ids })
  } catch (e) {
    showToast(toNativeError(e).message, { tone: 'error' })
    await loadGroup()
  }
}
