import { computed, reactive, ref, watch } from 'vue'
import { Preferences } from '@capacitor/preferences'
import { PtTransfer, isNative, toNativeError } from '../native/plugins'
import type { FlashMode, ImageSettings, Protocol, SecretName, SecretStatus, ServerSettings } from '../native/plugins'

export interface AppSettings {
  server: ServerSettings
  image: ImageSettings
  camera: { flash: FlashMode; volumeKeys: boolean }
}

export const EDGE_PRESETS = [1200, 1600, 2000, 2400] as const
export const MIN_EDGE = 320
export const MAX_EDGE = 8000
export const DEFAULT_PORTS: Record<Protocol, number> = { sftp: 22, ftps: 21 }

const STORAGE_KEY = 'settings.v1'

function defaults(): AppSettings {
  return {
    server: { protocol: 'sftp', host: '', port: 22, username: '', auth: 'password' },
    image: { maxEdge: 1600, format: 'webp', quality: 80, keepMetadata: true },
    camera: { flash: 'off', volumeKeys: true },
  }
}

/** Nicht-geheime Einstellungen (Capacitor Preferences). Secrets liegen nativ im Keystore. */
export const settings = reactive<AppSettings>(defaults())

export const secretStatus = ref<SecretStatus>({ password: false, passphrase: false, privateKey: false })

export const settingsLoaded = ref(false)

export const hasServerBasics = computed(
  () => settings.server.host.trim() !== '' && settings.server.username.trim() !== '',
)

/** SSH-Key gibt es nur bei SFTP – FTPS meldet sich immer mit Passwort an. */
export const usesKeyAuth = computed(() => settings.server.protocol === 'sftp' && settings.server.auth === 'key')

export const isServerConfigured = computed(() => {
  if (!hasServerBasics.value) return false
  return usesKeyAuth.value ? secretStatus.value.privateKey : secretStatus.value.password
})

/** Kopie ohne Vue-Proxy, normalisiert für die native Seite. */
export function serverPayload(): ServerSettings {
  const protocol = settings.server.protocol
  return {
    protocol,
    host: settings.server.host.trim(),
    port: clampInt(settings.server.port, 1, 65535, DEFAULT_PORTS[protocol]),
    username: settings.server.username.trim(),
    auth: usesKeyAuth.value ? 'key' : 'password',
  }
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
  const s = r.server ?? {}
  const i = r.image ?? {}
  const c = r.camera ?? {}
  const protocol: Protocol = s.protocol === 'ftps' ? 'ftps' : 'sftp'
  return {
    server: {
      protocol,
      host: typeof s.host === 'string' ? s.host : d.server.host,
      port: clampInt(s.port, 1, 65535, DEFAULT_PORTS[protocol]),
      username: typeof s.username === 'string' ? s.username : d.server.username,
      auth: s.auth === 'key' ? 'key' : 'password',
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
    console.error('Secret-Status nicht lesbar', toNativeError(e))
  }
}

/** Secret setzen (leer/null = löschen). Wirft NativeError bei ungültigem Key. */
export async function saveSecret(name: SecretName, value: string | null): Promise<SecretStatus> {
  const status = await PtTransfer.setSecret({ name, value: value && value.length > 0 ? value : null })
  secretStatus.value = status
  return status
}
