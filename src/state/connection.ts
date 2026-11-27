import { reactive, watch } from 'vue'
import { PtTransfer, isNative, toNativeError } from '../native/plugins'
import type { NativeError, TestResult } from '../native/plugins'
import { isServerConfigured, secretStatus, serverPayload, settings } from './settings'

export type ConnectionState = 'unconfigured' | 'unknown' | 'checking' | 'ok' | 'error'

export const connection = reactive({
  state: 'unconfigured' as ConnectionState,
  message: '',
  /** Name der Website aus ihren Einstellungen (nach erfolgreicher Prüfung) */
  site: '',
  checkedAt: 0,
})

/** Diese Fehler betreffen die Verbindung selbst (nicht einzelne Dateien) – dann ist der Status „Keine Verbindung“. */
export const CONNECTION_ERRORS = [
  'UNREACHABLE',
  'TIMEOUT',
  'HOST_NOT_FOUND',
  'CONNECTION_LOST',
  'AUTH_FAILED',
  'TLS_CERT_INVALID',
  'TLS_ERROR',
  'REDIRECT',
  'NOT_EXPONIO',
  'SERVER_ERROR',
  'STORAGE_UNAVAILABLE',
]

let checkSeq = 0

function applyError(err: NativeError): void {
  connection.state = err.code === 'NOT_CONFIGURED' || err.code === 'NO_CREDENTIALS' ? 'unconfigured' : 'error'
  connection.message = err.message
  connection.checkedAt = Date.now()
}

function applyOk(res: TestResult): void {
  connection.state = 'ok'
  connection.message = ''
  connection.site = res.site
  connection.checkedAt = Date.now()
}

/** Leiser Hintergrund-Check für die Statusanzeige (ohne Schreibtest). */
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
    if (seq === checkSeq) applyOk(res)
  } catch (e) {
    if (seq === checkSeq) applyError(toNativeError(e))
  }
}

/**
 * Vollständiger Test aus den Einstellungen: Adresse, Zertifikat, Token und ein echter Schreibtest der Website
 * im Upload-Verzeichnis. Liefert das Ergebnis oder wirft einen NativeError.
 */
export async function fullTest(): Promise<TestResult> {
  const seq = ++checkSeq
  connection.state = 'checking'
  try {
    const res = await PtTransfer.testConnection({ server: serverPayload(), writeTest: true })
    if (seq === checkSeq) applyOk(res)
    return res
  } catch (e) {
    const err = toNativeError(e)
    if (seq === checkSeq) applyError(err)
    throw err
  }
}

/** Bei Änderungen an Adresse oder Token ist der letzte Status nicht mehr aussagekräftig. */
export function watchServerChanges(): void {
  watch(
    () => [settings.server.url, secretStatus.value.tokenHint],
    () => {
      checkSeq++
      connection.state = isServerConfigured.value ? 'unknown' : 'unconfigured'
      connection.message = ''
      connection.site = ''
    },
  )
  watch(isServerConfigured, (configured) => {
    if (!configured) connection.state = 'unconfigured'
    else if (connection.state === 'unconfigured') connection.state = 'unknown'
  })
}
