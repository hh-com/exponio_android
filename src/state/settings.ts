import { computed, reactive, ref, watch } from 'vue'
import { Preferences } from '@capacitor/preferences'
import { PtTransfer, isNative, toNativeError } from '../native/plugins'
import type { FlashMode, ImageSettings, SecretName, SecretStatus, ServerSettings } from '../native/plugins'

export interface AppSettings {
  server: ServerSettings
  image: ImageSettings
  camera: { flash: FlashMode; volumeKeys: boolean }
}

export const EDGE_PRESETS = [1200, 1600, 2000, 2400] as const
export const MIN_EDGE = 320
export const MAX_EDGE = 8000

const STORAGE_KEY = 'settings.v1'

function defaults(): AppSettings {
  return {
    server: { url: '' },
    image: { maxEdge: 1600, format: 'webp', quality: 80, keepMetadata: true },
    camera: { flash: 'off', volumeKeys: true },
  }
}

/** Nicht-geheime Einstellungen (Capacitor Preferences). Das Token liegt nativ im Keystore. */
export const settings = reactive<AppSettings>(defaults())

export const secretStatus = ref<SecretStatus>({ token: false })

export const settingsLoaded = ref(false)

/**
 * Eingetippte Adresse vereinheitlichen: https:// ergänzen, Schrägstriche am Ende und versehentlich mitkopierte
 * Pfade (/admin…, /api/app/…) entfernen. Die verbindliche Prüfung (z. B. kein http://) macht die native Seite.
 */
export function normalizeUrl(raw: string): string {
  let text = raw.trim()
  if (text === '') return ''
  if (!text.includes('://')) text = `https://${text}`
  try {
    const url = new URL(text)
    const segments = url.pathname.split('/').filter(Boolean)
    let end = segments.indexOf('admin')
    if (end < 0) end = segments.length
    for (let i = 0; i < end - 1; i++) {
      if (segments[i] === 'api' && segments[i + 1] === 'app') {
        end = i
        break
      }
    }
    const path = segments.slice(0, end).map((s) => `/${s}`).join('')
    return `${url.protocol}//${url.host.toLowerCase()}${path}`
  } catch {
    return text
  }
}

/** Host der eingetragenen Adresse für die Anzeige, leer wenn keine gültige Adresse eingetragen ist. */
export function urlHost(raw: string): string {
  const url = normalizeUrl(raw)
  if (url === '') return ''
  try {
    return new URL(url).host
  } catch {
    return ''
  }
}

export const hasServerUrl = computed(() => urlHost(settings.server.url) !== '')

export const isServerConfigured = computed(() => hasServerUrl.value && secretStatus.value.token)

/** Kopie ohne Vue-Proxy, normalisiert für die native Seite. */
export function serverPayload(): ServerSettings {
  return { url: normalizeUrl(settings.server.url) }
}

export function imagePayload(): ImageSettings {
  return {
    maxEdge: clampInt(settings.image.maxEdge, MIN_EDGE, MAX_EDGE, 1600),
    format: settings.image.format,
    quality: clampInt(settings.image.quality, 1, 100, 80),
    keepMetadata: settings.image.keepMetadata,
  }
}

export function clampInt(value: unknown, min: number, max: number, fallback: number): number {
  const n = typeof value === 'number' ? value : Number.parseInt(String(value), 10)
  if (!Number.isFinite(n)) return fallback
  return Math.min(max, Math.max(min, Math.round(n)))
}

function sanitize(raw: unknown): AppSettings {
  const d = defaults()
  if (!raw || typeof raw !== 'object') return d
  const r = raw as Partial<Record<keyof AppSettings, Record<string, unknown>>>
  // Ältere Versionen speicherten hier Protokoll, Host, Port und Benutzer (SFTP/FTPS) – die fallen weg.
  const s = r.server ?? {}
  const i = r.image ?? {}
  const c = r.camera ?? {}
  return {
    server: {
      url: typeof s.url === 'string' ? s.url : d.server.url,
    },
    image: {
      maxEdge: clampInt(i.maxEdge, MIN_EDGE, MAX_EDGE, d.image.maxEdge),
      format: i.format === 'jpeg' ? 'jpeg' : 'webp',
      quality: clampInt(i.quality, 1, 100, d.image.quality),
      keepMetadata: typeof i.keepMetadata === 'boolean' ? i.keepMetadata : d.image.keepMetadata,
    },
    camera: {
      flash: c.flash === 'on' || c.flash === 'auto' ? c.flash : 'off',
      volumeKeys: typeof c.volumeKeys === 'boolean' ? c.volumeKeys : d.camera.volumeKeys,
    },
  }
}

let saveTimer: number | undefined

async function persist(): Promise<void> {
  try {
    await Preferences.set({ key: STORAGE_KEY, value: JSON.stringify(settings) })
  } catch (e) {
    console.error('Einstellungen konnten nicht gespeichert werden', e)
  }
}

export async function flushSettings(): Promise<void> {
  window.clearTimeout(saveTimer)
  await persist()
}

export async function loadSettings(): Promise<void> {
  try {
    const { value } = await Preferences.get({ key: STORAGE_KEY })
    if (value) Object.assign(settings, sanitize(JSON.parse(value)))
  } catch (e) {
    console.error('Einstellungen konnten nicht geladen werden', e)
  }
  await refreshSecretStatus()
  settingsLoaded.value = true
  watch(
    settings,
    () => {
      window.clearTimeout(saveTimer)
      saveTimer = window.setTimeout(persist, 300)
    },
    { deep: true },
  )
}

export async function refreshSecretStatus(): Promise<void> {
  if (!isNative) return
  try {
    secretStatus.value = await PtTransfer.getSecretStatus()
  } catch (e) {
    console.error('Token-Status nicht lesbar', toNativeError(e))
  }
}

/** Secret setzen (leer/null = löschen). Wirft NativeError bei ungültigem Token. */
export async function saveSecret(name: SecretName, value: string | null): Promise<SecretStatus> {
  const status = await PtTransfer.setSecret({ name, value: value && value.length > 0 ? value : null })
  secretStatus.value = status
  return status
}
