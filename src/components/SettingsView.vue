<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import {
  DEFAULT_PORTS,
  EDGE_PRESETS,
  MAX_EDGE,
  MIN_EDGE,
  clampInt,
  flushSettings,
  hasServerBasics,
  refreshSecretStatus,
  saveSecret,
  secretStatus,
  settings,
  usesKeyAuth,
} from '../state/settings'
import { connection, fullTest, refreshStoredHostKey, resetHostKey, storedHostKey } from '../state/connection'
import { closeSettings } from '../state/navigation'
import { showToast } from '../state/toast'
import { isUploading } from '../state/upload'
import { toNativeError } from '../native/plugins'
import type { NativeError, Protocol, SecretName, TestResult } from '../native/plugins'
import { APP_VERSION } from '../version'

// ---- Zugangsdaten (werden nie zurück in die WebView gelesen) ---------------------------------

const passwordInput = ref('')
const passphraseInput = ref('')
const keyInput = ref('')
const showPassword = ref(false)
const savingSecret = ref<SecretName | null>(null)
const secretError = ref<Partial<Record<SecretName, string>>>({})
const keyFile = ref<HTMLInputElement | null>(null)

async function commitSecret(name: SecretName, value: string): Promise<void> {
  if (!value) return
  savingSecret.value = name
  secretError.value = { ...secretError.value, [name]: undefined }
  try {
    await saveSecret(name, value)
    if (name === 'password') passwordInput.value = ''
    if (name === 'passphrase') passphraseInput.value = ''
    if (name === 'privateKey') keyInput.value = ''
    showToast(name === 'privateKey' ? 'Private Key gespeichert' : name === 'password' ? 'Passwort gespeichert' : 'Passphrase gespeichert', {
      tone: 'success',
      duration: 2000,
    })
  } catch (e) {
    secretError.value = { ...secretError.value, [name]: toNativeError(e).message }
  } finally {
    savingSecret.value = null
  }
}

async function removeSecret(name: SecretName): Promise<void> {
  savingSecret.value = name
  try {
    await saveSecret(name, null)
  } catch (e) {
    showToast(toNativeError(e).message, { tone: 'error' })
  } finally {
    savingSecret.value = null
  }
}

async function onKeyFileChosen(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 64 * 1024) {
    secretError.value = { ...secretError.value, privateKey: 'Die Datei ist zu groß für einen Private Key.' }
    return
  }
  const text = await file.text()
  await commitSecret('privateKey', text)
}

const keySummary = computed(() => {
  const s = secretStatus.value
  if (!s.privateKey) return ''
  if (s.keyError) return s.keyError
  const parts = [s.keyType ?? 'Key']
  if (s.keyEncrypted) parts.push(s.passphraseOk ? 'verschlüsselt · Passphrase korrekt' : 'verschlüsselt · Passphrase fehlt/falsch')
  else parts.push('unverschlüsselt')
  return parts.join(' · ')
})

const keyOk = computed(() => {
  const s = secretStatus.value
  return s.privateKey && !s.keyError && (!s.keyEncrypted || s.passphraseOk === true)
})

// ---- Server-Felder -------------------------------------------------------------------------------

function normalizePort(): void {
  settings.server.port = clampInt(settings.server.port, 1, 65535, DEFAULT_PORTS[settings.server.protocol])
}

/** Beim Wechsel den Standard-Port mitziehen – ein bewusst eigener Port bleibt erhalten. */
function setProtocol(protocol: Protocol): void {
  if (settings.server.protocol === protocol) return
  const port = Number(settings.server.port)
  if (!Number.isFinite(port) || port === DEFAULT_PORTS[settings.server.protocol]) {
    settings.server.port = DEFAULT_PORTS[protocol]
  }
  settings.server.protocol = protocol
  testResult.value = null
  testError.value = null
}

const isFtps = computed(() => settings.server.protocol === 'ftps')

// ---- Verbindungstest -------------------------------------------------------------------------------

const testing = ref(false)
const testResult = ref<TestResult | null>(null)
const testError = ref<NativeError | null>(null)

const canTest = computed(() => {
  if (!hasServerBasics.value || testing.value || isUploading.value) return false
  return usesKeyAuth.value ? secretStatus.value.privateKey : secretStatus.value.password
})

async function runTest(): Promise<void> {
  normalizePort()
  await flushSettings()
  testing.value = true
  testResult.value = null
  testError.value = null
  try {
    testResult.value = await fullTest()
  } catch (e) {
    testError.value = toNativeError(e)
  } finally {
    testing.value = false
  }
}

async function onResetHostKey(): Promise<void> {
  const ok = window.confirm(
    'Gespeicherten Host-Key wirklich entfernen?\n\nBeim nächsten Verbindungstest muss der Fingerprint erneut bestätigt werden.',
  )
  if (!ok) return
  try {
    await resetHostKey()
    testResult.value = null
    showToast('Host-Key zurückgesetzt')
  } catch (e) {
    showToast(toNativeError(e).message, { tone: 'error' })
  }
}

// ---- Web-Optimierung -------------------------------------------------------------------------------

const isPreset = computed(() => (EDGE_PRESETS as readonly number[]).includes(settings.image.maxEdge))

function normalizeEdge(): void {
  settings.image.maxEdge = clampInt(settings.image.maxEdge, MIN_EDGE, MAX_EDGE, 1600)
}

function normalizeQuality(): void {
  settings.image.quality = clampInt(settings.image.quality, 1, 100, 80)
}

onMounted(() => {
  void refreshSecretStatus()
  void refreshStoredHostKey()
})

async function onBack(): Promise<void> {
  normalizePort()
  normalizeEdge()
  normalizeQuality()
  await flushSettings()
  closeSettings()
}

defineExpose({ onBack })
</script>

<template>
  <div class="fixed inset-0 z-20 flex flex-col bg-surface">
    <header class="pt-safe px-safe border-b border-line bg-surface">
      <div class="flex h-14 items-center gap-1 px-2">
        <button
          type="button"
          class="flex h-11 w-11 items-center justify-center rounded-full active:bg-surface-3"
          aria-label="Zurück"
          @click="onBack"
        >
          <AppIcon name="back" :size="24" />
        </button>
        <h1 class="text-[17px] font-semibold">Einstellungen</h1>
      </div>
    </header>

    <main class="scroll-area px-safe min-h-0 flex-1">
      <div class="mx-auto max-w-xl space-y-5 px-4 py-5 pb-[calc(var(--sab)+24px)]">
        <!-- SERVER ------------------------------------------------------------------------------>
        <section class="space-y-3">
          <h2 class="flex items-center gap-2 px-1 text-xs font-semibold uppercase tracking-wider text-muted">
            <AppIcon name="server" :size="15" /> Server
          </h2>
          <div class="card space-y-4">
            <div>
              <span class="label">Protokoll</span>
              <div class="grid grid-cols-2 gap-1 rounded-xl bg-surface-3 p-1">
                <button
                  type="button"
                  class="rounded-lg py-2.5 text-sm font-medium transition"
                  :class="!isFtps ? 'bg-accent text-white' : 'text-zinc-300'"
                  @click="setProtocol('sftp')"
                >
                  SFTP
                </button>
                <button
                  type="button"
                  class="rounded-lg py-2.5 text-sm font-medium transition"
                  :class="isFtps ? 'bg-accent text-white' : 'text-zinc-300'"
                  @click="setProtocol('ftps')"
                >
                  FTPS
                </button>
              </div>
              <p class="mt-1.5 text-xs text-muted">
                <template v-if="isFtps">
                  FTP mit TLS-Verschlüsselung, z. B. für Webhosting. Als Host den Servernamen aus dem Zertifikat
                  eintragen (steht im Kundenbereich des Hosters), nicht die IP-Adresse.
                </template>
                <template v-else>Über SSH, Anmeldung per Passwort oder SSH-Key.</template>
              </p>
            </div>

            <div class="grid grid-cols-[1fr_96px] gap-3">
              <div>
                <label class="label" for="host">Host</label>
                <input
                  id="host"
                  v-model.trim="settings.server.host"
                  class="field"
                  type="text"
                  inputmode="url"
                  autocapitalize="off"
                  autocomplete="off"
                  spellcheck="false"
                  :placeholder="isFtps ? 'ftp.example.com' : 'server.example.com'"
                />
              </div>
              <div>
                <label class="label" for="port">Port</label>
                <input
                  id="port"
                  v-model.number="settings.server.port"
                  class="field tabular-nums"
                  type="number"
                  inputmode="numeric"
                  min="1"
                  max="65535"
                  @blur="normalizePort"
                />
              </div>
            </div>

            <div>
              <label class="label" for="user">Benutzername</label>
              <input
                id="user"
                v-model.trim="settings.server.username"
                class="field"
                type="text"
                autocapitalize="off"
                autocomplete="off"
                spellcheck="false"
                placeholder="upload"
              />
            </div>

            <div v-if="!isFtps">
              <span class="label">Anmeldung</span>
              <div class="grid grid-cols-2 gap-1 rounded-xl bg-surface-3 p-1">
                <button
                  type="button"
                  class="rounded-lg py-2.5 text-sm font-medium transition"
                  :class="settings.server.auth === 'password' ? 'bg-accent text-white' : 'text-zinc-300'"
                  @click="settings.server.auth = 'password'"
                >
                  Passwort
                </button>
                <button
                  type="button"
                  class="rounded-lg py-2.5 text-sm font-medium transition"
                  :class="settings.server.auth === 'key' ? 'bg-accent text-white' : 'text-zinc-300'"
                  @click="settings.server.auth = 'key'"
                >
                  SSH Private Key
                </button>
              </div>
            </div>

            <!-- Passwort -->
            <div v-if="!usesKeyAuth">
              <label class="label" for="pw">Passwort</label>
              <div class="relative">
                <input
                  id="pw"
                  v-model="passwordInput"
                  class="field pr-12"
                  :type="showPassword ? 'text' : 'password'"
                  autocomplete="new-password"
                  autocapitalize="off"
                  spellcheck="false"
                  :placeholder="secretStatus.password ? '•••••••• gespeichert – zum Ändern neu eingeben' : 'Passwort'"
                  @change="commitSecret('password', passwordInput)"
                  @keydown.enter.prevent="commitSecret('password', passwordInput)"
                />
                <button
                  type="button"
                  class="absolute inset-y-0 right-0 flex w-12 items-center justify-center text-muted"
                  :aria-label="showPassword ? 'Passwort verbergen' : 'Passwort anzeigen'"
                  @click="showPassword = !showPassword"
                >
                  <AppIcon :name="showPassword ? 'eye-off' : 'eye'" :size="19" />
                </button>
              </div>
              <div class="mt-1.5 flex items-center justify-between text-xs">
                <span v-if="savingSecret === 'password'" class="text-muted">Speichere…</span>
                <span v-else-if="secretError.password" class="text-red-300">{{ secretError.password }}</span>
                <span v-else-if="secretStatus.password" class="flex items-center gap-1 text-emerald-300">
                  <AppIcon name="check" :size="13" :stroke="3" /> Verschlüsselt im Android-Keystore gespeichert
                </span>
                <span v-else class="text-muted">Wird verschlüsselt im Android-Keystore gespeichert.</span>
                <button
                  v-if="secretStatus.password"
                  type="button"
                  class="font-medium text-red-300"
                  @click="removeSecret('password')"
                >
                  Entfernen
                </button>
              </div>
            </div>

            <!-- Private Key -->
            <div v-else class="space-y-3">
              <div>
                <div class="mb-1.5 flex items-center justify-between">
                  <label class="block text-[13px] font-medium text-muted" for="key">Private Key (OpenSSH / PEM)</label>
                  <button type="button" class="flex items-center gap-1 text-[13px] font-medium text-accent" @click="keyFile?.click()">
                    <AppIcon name="file" :size="15" /> Datei wählen
                  </button>
                  <input ref="keyFile" type="file" class="hidden" @change="onKeyFileChosen" />
                </div>
                <textarea
                  id="key"
                  v-model="keyInput"
                  class="field h-28 resize-none font-mono text-xs leading-relaxed"
                  autocapitalize="off"
                  autocomplete="off"
                  spellcheck="false"
                  :placeholder="secretStatus.privateKey ? 'Key gespeichert – zum Ersetzen neuen Key einfügen' : '-----BEGIN OPENSSH PRIVATE KEY-----\n…\n-----END OPENSSH PRIVATE KEY-----'"
                  @change="commitSecret('privateKey', keyInput)"
                />
                <div class="mt-1.5 flex items-center justify-between gap-2 text-xs">
                  <span v-if="savingSecret === 'privateKey'" class="text-muted">Prüfe Key…</span>
                  <span v-else-if="secretError.privateKey" class="text-red-300">{{ secretError.privateKey }}</span>
                  <span
                    v-else-if="secretStatus.privateKey"
                    class="flex items-center gap-1"
                    :class="keyOk ? 'text-emerald-300' : 'text-amber-300'"
                  >
                    <AppIcon :name="keyOk ? 'key' : 'alert'" :size="13" /> {{ keySummary }}
                  </span>
                  <span v-else class="text-muted">Ed25519, ECDSA oder RSA. Wird verschlüsselt gespeichert.</span>
                  <button
                    v-if="secretStatus.privateKey"
                    type="button"
                    class="shrink-0 font-medium text-red-300"
                    @click="removeSecret('privateKey')"
                  >
                    Entfernen
                  </button>
                </div>
              </div>

              <div>
                <label class="label" for="pp">Passphrase (optional)</label>
                <input
                  id="pp"
                  v-model="passphraseInput"
                  class="field"
                  type="password"
                  autocomplete="new-password"
                  autocapitalize="off"
                  spellcheck="false"
                  :placeholder="secretStatus.passphrase ? '•••••••• gespeichert' : 'Nur für verschlüsselte Keys'"
                  @change="commitSecret('passphrase', passphraseInput)"
                  @keydown.enter.prevent="commitSecret('passphrase', passphraseInput)"
                />
                <div class="mt-1.5 flex items-center justify-between text-xs">
                  <span v-if="secretError.passphrase" class="text-red-300">{{ secretError.passphrase }}</span>
                  <span v-else class="text-muted">{{ secretStatus.passphrase ? 'Passphrase gespeichert' : '' }}</span>
                  <button
                    v-if="secretStatus.passphrase"
                    type="button"
                    class="font-medium text-red-300"
                    @click="removeSecret('passphrase')"
                  >
                    Entfernen
                  </button>
                </div>
              </div>
            </div>

            <p class="text-xs text-muted">
              Uploads landen im Startverzeichnis des Zugangs, pro Upload in einem neuen Ordner wie
              <span class="font-mono text-zinc-400">2026-11-20_211530</span>.
            </p>

            <button type="button" class="btn-primary w-full" :disabled="!canTest" @click="runTest">
              <AppIcon :name="testing ? 'spinner' : 'refresh'" :size="18" />
              {{ testing ? 'Teste Verbindung…' : 'Verbindung testen' }}
            </button>

            <div v-if="testResult" class="rounded-xl border border-ok/30 bg-ok/10 p-3 text-sm">
              <p class="flex items-center gap-1.5 font-medium text-emerald-200">
                <AppIcon name="check" :size="16" :stroke="3" />
                Verbindung OK{{ testResult.writeTested ? ' · Schreibrechte OK' : '' }}
              </p>
              <p class="mt-1 break-all text-xs text-muted">{{ testResult.serverVersion }}</p>
              <p v-if="testResult.newlyTrusted" class="mt-1 text-xs text-emerald-300/80">
                Host-Key wurde gespeichert und wird ab jetzt bei jeder Verbindung geprüft.
              </p>
            </div>
            <div v-else-if="testError" class="rounded-xl border border-danger/40 bg-danger/10 p-3 text-sm">
              <p class="flex items-center gap-1.5 font-medium text-red-200">
                <AppIcon name="alert" :size="16" /> Test fehlgeschlagen
              </p>
              <p class="mt-1 break-words text-red-100/80">{{ testError.message }}</p>
            </div>
            <p v-else-if="connection.state === 'ok' && !testing" class="text-center text-xs text-emerald-300/80">
              Letzte Prüfung erfolgreich.
            </p>
          </div>
        </section>

        <!-- TLS (FTPS) ------------------------------------------------------------------------->
        <section v-if="isFtps" class="space-y-3">
          <h2 class="flex items-center gap-2 px-1 text-xs font-semibold uppercase tracking-wider text-muted">
            <AppIcon name="shield" :size="15" /> Verschlüsselung (TLS)
          </h2>
          <div class="card text-sm text-muted">
            Das Zertifikat des Servers wird bei jeder Verbindung wie im Browser geprüft: gültige Zertifizierungsstelle
            und passender Hostname. Ohne gültiges Zertifikat wird nichts gesendet, auch nicht das Passwort.
          </div>
        </section>

        <!-- HOST-KEY ---------------------------------------------------------------------------->
        <section v-else class="space-y-3">
          <h2 class="flex items-center gap-2 px-1 text-xs font-semibold uppercase tracking-wider text-muted">
            <AppIcon name="shield" :size="15" /> Host-Key (Trust on First Use)
          </h2>
          <div class="card space-y-3">
            <template v-if="storedHostKey">
              <div>
                <div class="text-xs text-muted">
                  Vertraut für {{ settings.server.host.trim() }}:{{ settings.server.port }} · {{ storedHostKey.keyType }}
                </div>
                <div class="mt-1 break-all font-mono text-[13px] text-zinc-200 select-text">
                  {{ storedHostKey.fingerprint }}
                </div>
              </div>
              <button type="button" class="btn-danger w-full" @click="onResetHostKey">
                Gespeicherten Host-Key zurücksetzen
              </button>
              <p class="text-xs text-muted">Nur nötig, wenn der Server gewechselt oder neu aufgesetzt wurde.</p>
            </template>
            <p v-else class="text-sm text-muted">
              Noch kein Host-Key gespeichert. Beim ersten „Verbindung testen“ wird der Fingerprint angezeigt
              und nach deiner Bestätigung gespeichert.
            </p>
          </div>
        </section>

        <!-- WEB-OPTIMIERUNG --------------------------------------------------------------------->
        <section class="space-y-3">
          <h2 class="flex items-center gap-2 px-1 text-xs font-semibold uppercase tracking-wider text-muted">
            <AppIcon name="image" :size="15" /> Web-Optimierung
          </h2>
          <div class="card space-y-5">
            <div>
              <span class="label">Maximale Kantenlänge</span>
              <div class="flex flex-wrap gap-2">
                <button
                  v-for="edge in EDGE_PRESETS"
                  :key="edge"
                  type="button"
                  class="chip tabular-nums"
                  :class="settings.image.maxEdge === edge ? 'chip-active' : ''"
                  @click="settings.image.maxEdge = edge"
                >
                  {{ edge }} px
                </button>
              </div>
              <div class="mt-3 flex items-center gap-3">
                <label class="shrink-0 text-sm text-muted" for="edge">Eigener Wert</label>
                <input
                  id="edge"
                  v-model.number="settings.image.maxEdge"
                  class="field tabular-nums"
                  :class="isPreset ? '' : 'border-accent'"
                  type="number"
                  inputmode="numeric"
                  :min="MIN_EDGE"
                  :max="MAX_EDGE"
                  step="10"
                  @blur="normalizeEdge"
                />
                <span class="text-sm text-muted">px</span>
              </div>
              <p class="mt-1.5 text-xs text-muted">Seitenverhältnis bleibt erhalten, kleinere Fotos werden nicht vergrößert.</p>
            </div>

            <div>
              <span class="label">Ausgabeformat</span>
              <div class="grid grid-cols-2 gap-1 rounded-xl bg-surface-3 p-1">
                <button
                  type="button"
                  class="rounded-lg py-2.5 text-sm font-medium transition"
                  :class="settings.image.format === 'webp' ? 'bg-accent text-white' : 'text-zinc-300'"
                  @click="settings.image.format = 'webp'"
                >
                  WebP
                </button>
                <button
                  type="button"
                  class="rounded-lg py-2.5 text-sm font-medium transition"
                  :class="settings.image.format === 'jpeg' ? 'bg-accent text-white' : 'text-zinc-300'"
                  @click="settings.image.format = 'jpeg'"
                >
                  JPEG
                </button>
              </div>
            </div>

            <div>
              <div class="mb-1.5 flex items-baseline justify-between">
                <label class="block text-[13px] font-medium text-muted" for="quality">Qualität</label>
                <span class="text-sm font-semibold tabular-nums">{{ settings.image.quality }} %</span>
              </div>
              <input
                id="quality"
                v-model.number="settings.image.quality"
                type="range"
                min="1"
                max="100"
                step="1"
                class="w-full accent-[var(--color-accent)]"
                @change="normalizeQuality"
              />
              <div class="mt-1 flex justify-between text-[11px] text-muted">
                <span>kleiner</span><span>empfohlen 75–85 %</span><span>besser</span>
              </div>
            </div>

            <label class="flex items-center justify-between gap-4">
              <span>
                <span class="block text-[15px]">EXIF-Metadaten übernehmen</span>
                <span class="block text-xs text-muted">Kamera, Belichtung, Aufnahmedatum (Ausrichtung wird korrigiert)</span>
              </span>
              <input v-model="settings.image.keepMetadata" type="checkbox" class="h-6 w-6 shrink-0 accent-[var(--color-accent)]" />
            </label>
          </div>
        </section>

        <!-- KAMERA ------------------------------------------------------------------------------>
        <section class="space-y-3">
          <h2 class="flex items-center gap-2 px-1 text-xs font-semibold uppercase tracking-wider text-muted">
            <AppIcon name="camera" :size="15" /> Kamera
          </h2>
          <div class="card">
            <label class="flex items-center justify-between gap-4">
              <span>
                <span class="block text-[15px]">Lautstärketasten als Auslöser</span>
                <span class="block text-xs text-muted">Nur solange die Kamera-Vorschau sichtbar ist</span>
              </span>
              <input v-model="settings.camera.volumeKeys" type="checkbox" class="h-6 w-6 shrink-0 accent-[var(--color-accent)]" />
            </label>
          </div>
        </section>

        <p class="px-1 pt-2 text-center text-xs text-muted">
          Exponio {{ APP_VERSION }} · Fotos bleiben im app-eigenen Speicher, bis der Upload vollständig
          verifiziert ist. Auf dem Server wird nie etwas gelöscht oder überschrieben.
        </p>
      </div>
    </main>
  </div>
</template>
