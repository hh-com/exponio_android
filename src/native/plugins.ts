import { Capacitor, registerPlugin } from '@capacitor/core'
import type { PermissionState, PluginListenerHandle } from '@capacitor/core'

// ---------------------------------------------------------------------------------------------
// Gemeinsame Typen (spiegeln die JSON-Strukturen der Kotlin-Plugins)
// ---------------------------------------------------------------------------------------------

export interface NativePhoto {
  id: string
  original: string
  preview: string
  thumb: string
  width: number
  height: number
  /** Ändert sich, wenn Vorschau/Thumbnail neu erzeugt wurden (z. B. nach dem Drehen). */
  version: number
}

export type FlashMode = 'off' | 'on' | 'auto'
export type AuthMethod = 'password' | 'key'
export type Protocol = 'sftp' | 'ftps'
export type ImageFormat = 'webp' | 'jpeg'
export type SecretName = 'password' | 'privateKey' | 'passphrase'

export interface CameraRect {
  x: number
  y: number
  width: number
  height: number
}

export interface ZoomInfo {
  zoom: number
  minZoom: number
  maxZoom: number
}

export interface CameraState extends ZoomInfo {
  running: boolean
  hasFlash: boolean
  rotation: number
}

export interface ServerSettings {
  protocol: Protocol
  host: string
  port: number
  username: string
  auth: AuthMethod
}

export interface ImageSettings {
  maxEdge: number
  format: ImageFormat
  quality: number
  keepMetadata: boolean
}

export interface SecretStatus {
  password: boolean
  passphrase: boolean
  privateKey: boolean
  keyType?: string
  keyEncrypted?: boolean
  passphraseOk?: boolean
  keyError?: string
}

export interface TestResult {
  ok: true
  protocol: Protocol
  /** SFTP: SSH-Version · FTPS: TLS-Version, Zertifikatsname, Aussteller */
  serverVersion: string
  fingerprint: string
  keyType: string
  newlyTrusted: boolean
  writeTested: boolean
}

export type UploadPhase = 'process' | 'connect' | 'upload' | 'finalize'

export interface UploadProgress {
  phase: UploadPhase
  current: number
  total: number
  fraction: number
}

export interface UploadResult {
  remotePath: string
  dirName: string
  count: number
  bytes: number
}

export interface UploadState {
  uploading: boolean
  pending: boolean
  dirName?: string
  total?: number
  uploaded?: number
  host?: string
}

// ---------------------------------------------------------------------------------------------
// Plugin-Schnittstellen
// ---------------------------------------------------------------------------------------------

export interface PtCameraPlugin {
  checkPermissions(): Promise<{ camera: PermissionState }>
  requestPermissions(options?: { permissions: 'camera'[] }): Promise<{ camera: PermissionState }>
  start(options: CameraRect & { flash?: FlashMode; volumeKeys?: boolean }): Promise<CameraState>
  stop(): Promise<void>
  updateRect(options: CameraRect): Promise<void>
  getState(): Promise<CameraState>
  capture(): Promise<NativePhoto>
  setZoom(options: { ratio: number }): Promise<{ zoom?: number }>
  setFlash(options: { mode: FlashMode }): Promise<void>
  focus(options: { x: number; y: number }): Promise<void>
  setVolumeKeys(options: { enabled: boolean }): Promise<void>
  openAppSettings(): Promise<void>
  addListener(event: 'volumeShutter', cb: () => void): Promise<PluginListenerHandle>
  addListener(event: 'captureStarted', cb: (e: { id: string }) => void): Promise<PluginListenerHandle>
  addListener(event: 'zoomChanged', cb: (e: ZoomInfo) => void): Promise<PluginListenerHandle>
  addListener(event: 'orientationChanged', cb: (e: { rotation: number }) => void): Promise<PluginListenerHandle>
}

export interface PtTransferPlugin {
  listPhotos(): Promise<{ photos: NativePhoto[] }>
  deletePhotos(options: { ids: string[] }): Promise<void>
  /** Verlustfrei im Uhrzeigersinn drehen (Vierteldrehungen à 90°). */
  rotatePhoto(options: { id: string; quarterTurns: number }): Promise<NativePhoto>
  setSecret(options: { name: SecretName; value: string | null }): Promise<SecretStatus>
  getSecretStatus(): Promise<SecretStatus>
  getHostKey(options: { host: string; port: number }): Promise<{ fingerprint?: string; keyType?: string }>
  resetHostKey(options: { host: string; port: number }): Promise<void>
  testConnection(options: {
    server: ServerSettings
    trustFingerprint?: string
    writeTest?: boolean
  }): Promise<TestResult>
  upload(options: { server: ServerSettings; image: ImageSettings; photoIds: string[] }): Promise<UploadResult>
  cancelUpload(): Promise<void>
  getUploadState(): Promise<UploadState>
  addListener(event: 'uploadProgress', cb: (p: UploadProgress) => void): Promise<PluginListenerHandle>
}

export const PtCamera = registerPlugin<PtCameraPlugin>('PtCamera')
export const PtTransfer = registerPlugin<PtTransferPlugin>('PtTransfer')

export const isNative = Capacitor.isNativePlatform()

/** Absoluten App-Dateipfad in eine von der WebView ladbare URL umwandeln. */
export function fileUrl(path: string): string {
  return Capacitor.convertFileSrc(path)
}

// ---------------------------------------------------------------------------------------------
// Fehler
// ---------------------------------------------------------------------------------------------

export interface NativeError {
  code: string
  message: string
  data?: Record<string, unknown>
}

export function toNativeError(e: unknown): NativeError {
  if (e && typeof e === 'object') {
    const err = e as { code?: unknown; message?: unknown; data?: unknown }
    return {
      code: typeof err.code === 'string' && err.code ? err.code : 'UNKNOWN',
      message: typeof err.message === 'string' && err.message ? err.message : 'Unbekannter Fehler',
      data: err.data && typeof err.data === 'object' ? (err.data as Record<string, unknown>) : undefined,
    }
  }
  return { code: 'UNKNOWN', message: String(e) }
}

export function dataString(err: NativeError, key: string): string | undefined {
  const v = err.data?.[key]
  return typeof v === 'string' ? v : undefined
}
