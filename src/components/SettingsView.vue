<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import {
  EDGE_PRESETS,
  MAX_EDGE,
  MIN_EDGE,
  clampInt,
  flushSettings,
  hasServerUrl,
  normalizeUrl,
  refreshSecretStatus,
  saveSecret,
  secretStatus,
  settings,
} from '../state/settings'
import { connection, fullTest } from '../state/connection'
import { closeSettings } from '../state/navigation'
import { showToast } from '../state/toast'
import { isUploading } from '../state/upload'
import { toNativeError } from '../native/plugins'
import type { NativeError, TestResult } from '../native/plugins'
import { APP_VERSION } from '../version'

// ---- Website-Adresse ------------------------------------------------------------------------------

function normalizeServerUrl(): void {
  settings.server.url = normalizeUrl(settings.server.url)
}

const insecureUrl = computed(() => /^http:\/\//i.test(settings.server.url.trim()))

// ---- Token (wird nie zurück in die WebView gelesen) ----------------------------------------------

const tokenInput = ref('')
const showToken = ref(false)
const savingToken = ref(false)
const tokenError = ref('')

async function commitToken(): Promise<void> {
  const value = tokenInput.value.trim()
  if (!value) return
  savingToken.value = true
  tokenError.value = ''
  try {
    await saveSecret('token', value)
    tokenInput.value = ''
    testResult.value = null
    testError.value = null
    showToast('Token gespeichert', { tone: 'success', duration: 2000 })
  } catch (e) {
    tokenError.value = toNativeError(e).message
  } finally {
    savingToken.value = false
  }
}

async function removeToken(): Promise<void> {
  const ok = window.confirm('Token wirklich entfernen?\n\nDanach kann die App nicht mehr hochladen, bis du ein neues einträgst.')
  if (!ok) return
  savingToken.value = true
  try {
    await saveSecret('token', null)
    testResult.value = null
    testError.value = null
  } catch (e) {
    showToast(toNativeError(e).message, { tone: 'error' })
  } finally {
    savingToken.value = false
  }
}

// ---- Verbindungstest -------------------------------------------------------------------------------

const testing = ref(false)
const testResult = ref<TestResult | null>(null)
const testError = ref<NativeError | null>(null)

const canTest = computed(() => hasServerUrl.value && secretStatus.value.token && !testing.value && !isUploading.value)

async function runTest(): Promise<void> {
  normalizeServerUrl()
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
})

async function onBack(): Promise<void> {
  normalizeServerUrl()
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
        <!-- WEBSITE ----------------------------------------------------------------------------->
        <section class="space-y-3">
          <h2 class="flex items-center gap-2 px-1 text-xs font-semibold uppercase tracking-wider text-muted">
            <AppIcon name="server" :size="15" /> Website
          </h2>
          <div class="card space-y-4">
            <p class="text-sm text-muted">
              Die App lädt direkt in deine Exponio-Website hoch, die Website legt die Fotos selbst im richtigen Ordner
              ab. Adresse und Token bekommst du im Backend unter
              <span class="text-zinc-200">System → App-Zugänge → Neuer App-Zugang</span>.
            </p>

            <div>
              <label class="label" for="url">Adresse der Website</label>
              <input
                id="url"
                v-model.trim="settings.server.url"
                class="field"
                type="url"
                inputmode="url"
                autocapitalize="off"
                autocomplete="off"
                spellcheck="false"
                placeholder="https://exponio.example.org"
                @blur="normalizeServerUrl"
              />
              <p v-if="insecureUrl" class="mt-1.5 text-xs text-red-300">
                Nur https:// ist erlaubt, sonst ginge das Token unverschlüsselt übers Netz.
              </p>
            </div>

            <div>
              <label class="label" for="token">Token</label>
              <div class="relative">
                <input
                  id="token"
                  v-model="tokenInput"
                  class="field pr-12 font-mono text-sm"
                  :type="showToken ? 'text' : 'password'"
                  autocomplete="off"
                  autocapitalize="off"
                  spellcheck="false"
                  :placeholder="secretStatus.token ? `exp_…${secretStatus.tokenHint ?? ''} gespeichert – zum Ersetzen einfügen` : 'exp_…'"
                  @change="commitToken"
                  @keydown.enter.prevent="commitToken"
                />
                <button
                  type="button"
                  class="absolute inset-y-0 right-0 flex w-12 items-center justify-center text-muted"
                  :aria-label="showToken ? 'Token verbergen' : 'Token anzeigen'"
                  @click="showToken = !showToken"
                >
                  <AppIcon :name="showToken ? 'eye-off' : 'eye'" :size="19" />
                </button>
              </div>
              <div class="mt-1.5 flex items-center justify-between gap-2 text-xs">
                <span v-if="savingToken" class="text-muted">Speichere…</span>
                <span v-else-if="tokenError" class="text-red-300">{{ tokenError }}</span>
                <span v-else-if="secretStatus.token" class="flex items-center gap-1 text-emerald-300">
                  <AppIcon name="key" :size="13" /> Endet auf {{ secretStatus.tokenHint }} · verschlüsselt im Android-Keystore
                </span>
                <span v-else class="text-muted">Wird verschlüsselt im Android-Keystore gespeichert.</span>
                <button
                  v-if="secretStatus.token"
                  type="button"
                  class="shrink-0 font-medium text-red-300"
                  @click="removeToken"
                >
                  Entfernen
                </button>
              </div>
            </div>

            <button type="button" class="btn-primary w-full" :disabled="!canTest" @click="runTest">
              <AppIcon :name="testing ? 'spinner' : 'refresh'" :size="18" />
              {{ testing ? 'Teste Verbindung…' : 'Verbindung testen' }}
            </button>

            <div v-if="testResult" class="rounded-xl border border-ok/30 bg-ok/10 p-3 text-sm">
              <p class="flex items-center gap-1.5 font-medium text-emerald-200">
                <AppIcon name="check" :size="16" :stroke="3" />
                Verbunden mit {{ testResult.site }}{{ testResult.writeTested ? ' · Ablage OK' : '' }}
              </p>
              <p class="mt-1 break-all text-xs text-muted">{{ testResult.url }}</p>
            </div>
            <div v-else-if="testError" class="rounded-xl border border-danger/40 bg-danger/10 p-3 text-sm">
              <p class="flex items-center gap-1.5 font-medium text-red-200">
                <AppIcon name="alert" :size="16" /> Test fehlgeschlagen
              </p>
              <p class="mt-1 break-words text-red-100/80">{{ testError.message }}</p>
            </div>
            <p v-else-if="connection.state === 'ok' && !testing" class="text-center text-xs text-emerald-300/80">
              Letzte Prüfung erfolgreich{{ connection.site ? ` · ${connection.site}` : '' }}.
            </p>
          </div>
        </section>

        <!-- SICHERHEIT -------------------------------------------------------------------------->
        <section class="space-y-3">
          <h2 class="flex items-center gap-2 px-1 text-xs font-semibold uppercase tracking-wider text-muted">
            <AppIcon name="shield" :size="15" /> Sicherheit
          </h2>
          <div class="card text-sm text-muted">
            Verbunden wird nur über HTTPS mit gültigem Zertifikat, Weiterleitungen folgt die App nicht. Ohne gültiges
            Zertifikat wird nichts gesendet, auch nicht das Token. Ein verlorenes Gerät sperrst du, indem du seinen
            App-Zugang im Backend löschst.
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
