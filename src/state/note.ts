import { ref } from 'vue'
import { PtTransfer, isNative, toNativeError } from '../native/plugins'
import { showToast } from './toast'

/** Gleicher Wert wie PhotoStore.MAX_NOTE_LENGTH. */
export const NOTE_MAX_LENGTH = 5000

const SAVE_DELAY_MS = 400

/**
 * Notiz zur aktuellen Gruppe (getippt oder per Tastatur-Mikrofon diktiert). Sie wird nativ neben den
 * Fotos gespeichert, geht beim Upload als info.txt in den Ordner und wird danach mit der Gruppe gelöscht.
 */
export const note = ref('')

/** Textfeld hat den Fokus: Lautstärketasten sollen dann nicht auslösen. */
export const noteFocused = ref(false)

let saved = ''
let saveTimer: number | undefined

export async function loadNote(): Promise<void> {
  if (!isNative) return
  try {
    const { text } = await PtTransfer.getNote()
    note.value = text
    saved = text
  } catch (e) {
    showToast(toNativeError(e).message, { tone: 'error' })
  }
}

export function setNote(text: string): void {
  note.value = text
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => void flushNote(), SAVE_DELAY_MS)
}

/** Ungespeicherte Änderung sofort sichern (vor dem Upload, beim Pausieren). false bei einem Fehler. */
export async function flushNote(): Promise<boolean> {
  window.clearTimeout(saveTimer)
  const text = note.value
  if (!isNative || text === saved) return true
  try {
    await PtTransfer.setNote({ text })
    saved = text
    return true
  } catch (e) {
    showToast(toNativeError(e).message, { tone: 'error' })
    return false
  }
}
