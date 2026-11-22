import { reactive, ref, watch } from 'vue'
import { PtTransfer, dataString, isNative, toNativeError } from '../native/plugins'
import type { NativeError, TestResult } from '../native/plugins'
import { hasServerBasics, isServerConfigured, serverPayload, settings } from './settings'

export type ConnectionState = 'unconfigured' | 'unknown' | 'checking' | 'ok' | 'error' | 'hostkey'

export const connection = reactive({
  state: 'unconfigured' as ConnectionState,
  message: '',
  serverVersion: '',
  checkedAt: 0,
})

/** Gespeicherter (vertrauter) Host-Key für host:port der aktuellen Einstellungen. */
export const storedHostKey = ref<{ fingerprint: string; keyType: string } | null>(null)

export interface HostKeyPrompt {
  mode: 'unknown' | 'changed'
  host: string
  port: number
  fingerprint: string
  keyType: string
  storedFingerprint?: string
}

/** Offener Dialog zur Fingerprint-Bestätigung (TOFU). */
export const hostKeyPrompt = ref<HostKeyPrompt | null>(null)
let promptResolver: ((trusted: boolean) => void) | null = null

let checkSeq = 0

export async function refreshStoredHostKey(): Promise<void> {
  // Host-Keys (TOFU) gibt es nur bei SFTP; FTPS prüft das TLS-Zertifikat über die System-CAs.
  if (!isNative || !hasServerBasics.value || settings.server.protocol !== 'sftp') {
    storedHostKey.value = null
    return
  }
  const { host, port } = serverPayload()
  try {
    const res = await PtTransfer.getHostKey({ host, port })
    storedHostKey.value = res.fingerprint ? { fingerprint: res.fingerprint, keyType: res.keyType ?? '' } : null
  } catch {
    storedHostKey.value = null
  }
}

export async function resetHostKey(): Promise<void> {
  const { host, port } = serverPayload()
  await PtTransfer.resetHostKey({ host, port })
  storedHostKey.value = null
  connection.state = isServerConfigured.value ? 'unknown' : 'unconfigured'
  connection.message = ''
}

function applyError(err: NativeError): void {
  if (err.code === 'HOST_KEY_UNKNOWN' || err.code === 'HOST_KEY_CHANGED') {
    connection.state = 'hostkey'
  } else if (err.code === 'NOT_CONFIGURED' || err.code === 'NO_CREDENTIALS') {
    connection.state = 'unconfigured'
  } else {
    connection.state = 'error'
  }
  connection.message = err.message
  connection.checkedAt = Date.now()
}

/**
 * Leiser Hintergrund-Check für die Statusanzeige (ohne Schreibtest, ohne Dialoge).
 */
export async function quickCheck(): Promise<void> {
  if (!isNative) return
  if (!isServerConfigured.value) {
    connection.state = 'unconfigured'
    connection.message = ''
    return
  }
  const seq = ++checkSeq
  connection.state = 'checking'
  try {
    const res = await PtTransfer.testConnection({ server: serverPayload(), writeTest: false })
    if (seq !== checkSeq) return
    connection.state = 'ok'
    connection.message = ''
    connection.serverVersion = res.serverVersion
    connection.checkedAt = Date.now()
  } catch (e) {
    if (seq !== checkSeq) return
    applyError(toNativeError(e))
  }
}

/**
 * Vollständiger Test aus den Einstellungen: inkl. Schreibtest und TOFU-Dialog beim ersten Kontakt.
 * Liefert das Ergebnis oder wirft einen NativeError.
 */
export async function fullTest(): Promise<TestResult> {
  const seq = ++checkSeq
  connection.state = 'checking'
  const server = serverPayload()
  let trustFingerprint: string | undefined

  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = await PtTransfer.testConnection({ server, trustFingerprint, writeTest: true })
      if (seq === checkSeq) {
        connection.state = 'ok'
        connection.message = ''
        connection.serverVersion = res.serverVersion
        connection.checkedAt = Date.now()
      }
      await refreshStoredHostKey()
      return res
    } catch (e) {
      const err = toNativeError(e)
      if (err.code === 'HOST_KEY_UNKNOWN' && attempt === 0) {
        const fingerprint = dataString(err, 'fingerprint') ?? ''
        const trusted = await askHostKey({
          mode: 'unknown',
          host: server.host,
          port: server.port,
          fingerprint,
          keyType: dataString(err, 'keyType') ?? '',
        })
        if (trusted && fingerprint) {
          trustFingerprint = fingerprint
          continue
        }
      } else if (err.code === 'HOST_KEY_CHANGED') {
        void askHostKey({
          mode: 'changed',
          host: server.host,
          port: server.port,
          fingerprint: dataString(err, 'fingerprint') ?? '',
          keyType: dataString(err, 'keyType') ?? '',
          storedFingerprint: dataString(err, 'storedFingerprint'),
        })
      }
      if (seq === checkSeq) applyError(err)
      await refreshStoredHostKey()
      throw err
    }
  }
  // Unerreichbar – die Schleife endet immer mit return oder throw.
  throw { code: 'UNKNOWN', message: 'Verbindungstest abgebrochen.' } satisfies NativeError
}

function askHostKey(prompt: HostKeyPrompt): Promise<boolean> {
  promptResolver?.(false)
  hostKeyPrompt.value = prompt
  return new Promise<boolean>((resolve) => {
    promptResolver = resolve
  })
}

export function answerHostKey(trusted: boolean): void {
  const resolve = promptResolver
  promptResolver = null
  hostKeyPrompt.value = null
  resolve?.(trusted)
}

/** Bei Änderungen an Server-Daten ist der letzte Status nicht mehr aussagekräftig. */
export function watchServerChanges(): void {
  watch(
    () => [
      settings.server.protocol,
      settings.server.host,
      settings.server.port,
      settings.server.username,
      settings.server.auth,
    ],
    () => {
      checkSeq++
      connection.state = isServerConfigured.value ? 'unknown' : 'unconfigured'
      connection.message = ''
      void refreshStoredHostKey()
    },
  )
  watch(isServerConfigured, (configured) => {
    if (!configured) connection.state = 'unconfigured'
    else if (connection.state === 'unconfigured') connection.state = 'unknown'
  })
}
