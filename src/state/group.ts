import { computed, reactive, ref } from 'vue'
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
  // Versions-Parameter: Nach dem Drehen bleibt der Dateipfad gleich, die WebView soll trotzdem neu laden.
  const v = `?v=${p.version}`
  return {
    id: p.id,
    thumbUrl: fileUrl(p.thumb) + v,
    previewUrl: fileUrl(p.preview) + v,
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

// ---- Drehen ----------------------------------------------------------------------------------

/**
 * Angetippte Vierteldrehungen je Foto, die Vorschau/Thumbnail noch nicht enthalten. Bis die native
 * Seite fertig ist, dreht die Oberfläche die Bilder per CSS – so wirkt jeder Tipp sofort.
 */
const pendingTurns = reactive<Record<string, number>>({})

/** Fotos, deren Bilder gerade getauscht wurden: CSS-Drehung springt dann ohne Animation zurück. */
const settling = reactive(new Set<string>())

const rotations = new Map<string, Promise<void>>()

/** Noch ausstehende Vierteldrehungen (für die CSS-Vorschau) und ob ein Wechsel animiert werden darf. */
export function rotationOf(id: string): { turns: number; animate: boolean } {
  return { turns: pendingTurns[id] ?? 0, animate: !settling.has(id) }
}

/** Stil für quadratische Thumbnails (object-cover): CSS-Drehung entspricht dort exakt dem Ergebnis. */
export function thumbTurnStyle(id: string): Record<string, string> {
  const { turns, animate } = rotationOf(id)
  return {
    transform: `rotate(${turns * 90}deg)`,
    transition: animate ? 'transform 180ms ease-out' : 'none',
  }
}

/** Foto um 90° im Uhrzeigersinn drehen. Schnelle Tipps werden zu einem nativen Aufruf zusammengefasst. */
export function rotatePhoto(id: string): void {
  if (!isNative) return
  pendingTurns[id] = (pendingTurns[id] ?? 0) + 1
  if (!rotations.has(id)) rotations.set(id, runRotation(id))
}

/** Vor dem Upload: warten, bis alle angetippten Drehungen gespeichert sind. */
export async function settleRotations(): Promise<void> {
  while (rotations.size > 0) await Promise.all(rotations.values())
}

async function runRotation(id: string): Promise<void> {
  try {
    for (;;) {
      const turns = pendingTurns[id] ?? 0
      if (turns <= 0) break
      const photo = toPhoto(await PtTransfer.rotatePhoto({ id, quarterTurns: turns }))
      await preload(photo)
      replacePhoto(photo)
      pendingTurns[id] = (pendingTurns[id] ?? 0) - turns
      markSettling(id)
    }
  } catch (e) {
    showToast(toNativeError(e).message, { tone: 'error' })
    await loadGroup()
  } finally {
    delete pendingTurns[id]
    rotations.delete(id)
  }
}

function replacePhoto(photo: Photo): void {
  const index = photos.value.findIndex((p) => p.id === photo.id)
  if (index !== -1) photos.value[index] = photo
  if (pendingDeletes.has(photo.id)) pendingDeletes.set(photo.id, photo)
}

function markSettling(id: string): void {
  settling.add(id)
  requestAnimationFrame(() => requestAnimationFrame(() => settling.delete(id)))
}

/** Neue Bilder vorab dekodieren, damit der Wechsel von CSS-Drehung auf echte Drehung nicht flackert. */
async function preload(photo: Photo): Promise<void> {
  await Promise.all(
    [photo.thumbUrl, photo.previewUrl].map(async (url) => {
      const img = new Image()
      img.src = url
      try {
        await img.decode()
      } catch {
        // Anzeige lädt das Bild notfalls selbst.
      }
    }),
  )
}
