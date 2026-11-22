<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from './AppIcon.vue'
import { answerHostKey, hostKeyPrompt, resetHostKey } from '../state/connection'
import { showToast } from '../state/toast'
import { toNativeError } from '../native/plugins'

const busy = ref(false)

async function onReset(): Promise<void> {
  busy.value = true
  try {
    await resetHostKey()
    answerHostKey(false)
    showToast('Gespeicherter Host-Key entfernt. Bitte „Verbindung testen“ erneut ausführen.')
  } catch (e) {
    showToast(toNativeError(e).message, { tone: 'error' })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div v-if="hostKeyPrompt" class="fixed inset-0 z-40 flex items-end justify-center bg-black/70 p-3 sm:items-center">
    <div class="pb-safe w-full max-w-md rounded-3xl border border-line bg-surface-2 p-5 shadow-2xl">
      <template v-if="hostKeyPrompt.mode === 'unknown'">
        <div class="mb-3 flex items-center gap-3">
          <span class="flex h-10 w-10 items-center justify-center rounded-full bg-accent/15 text-accent">
            <AppIcon name="shield" />
          </span>
          <h2 class="text-lg font-semibold">Server-Fingerprint bestätigen</h2>
        </div>
        <p class="text-sm text-zinc-300">
          Erste Verbindung zu
          <span class="font-mono text-zinc-100">{{ hostKeyPrompt.host }}:{{ hostKeyPrompt.port }}</span>.
          Bitte prüfen, ob der Fingerprint mit dem Server übereinstimmt:
        </p>
        <div class="my-4 rounded-xl bg-surface p-3">
          <div class="text-xs text-muted">{{ hostKeyPrompt.keyType }}</div>
          <div class="mt-1 break-all font-mono text-[13px] leading-relaxed text-zinc-100 select-text">
            {{ hostKeyPrompt.fingerprint }}
          </div>
        </div>
        <p class="mb-5 text-xs text-muted">
          Auf dem Server anzeigen mit:<br />
          <span class="font-mono text-zinc-400">ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub</span>
        </p>
        <div class="flex gap-2">
          <button type="button" class="btn-ghost flex-1" @click="answerHostKey(false)">Abbrechen</button>
          <button type="button" class="btn-primary flex-1" @click="answerHostKey(true)">Vertrauen</button>
        </div>
      </template>

      <template v-else>
        <div class="mb-3 flex items-center gap-3">
          <span class="flex h-10 w-10 items-center justify-center rounded-full bg-danger/15 text-red-300">
            <AppIcon name="alert" />
          </span>
          <h2 class="text-lg font-semibold text-red-200">Host-Key hat sich geändert!</h2>
        </div>
        <p class="text-sm text-zinc-300">
          Der Server
          <span class="font-mono text-zinc-100">{{ hostKeyPrompt.host }}:{{ hostKeyPrompt.port }}</span>
          meldet einen anderen Schlüssel als gespeichert. Das kann ein Angriff (Man-in-the-Middle) sein.
          Uploads sind gesperrt.
        </p>
        <div class="my-4 space-y-2 rounded-xl bg-surface p-3 font-mono text-[12px] leading-relaxed">
          <div>
            <div class="font-sans text-xs text-muted">Gespeichert</div>
            <div class="break-all text-zinc-300">{{ hostKeyPrompt.storedFingerprint ?? '–' }}</div>
          </div>
          <div>
            <div class="font-sans text-xs text-muted">Aktuell ({{ hostKeyPrompt.keyType }})</div>
            <div class="break-all text-red-200">{{ hostKeyPrompt.fingerprint }}</div>
          </div>
        </div>
        <p class="mb-5 text-xs text-muted">
          Nur zurücksetzen, wenn der Server bewusst neu aufgesetzt oder gewechselt wurde.
        </p>
        <div class="flex gap-2">
          <button type="button" class="btn-ghost flex-1" @click="answerHostKey(false)">Schließen</button>
          <button type="button" class="btn-danger flex-1" :disabled="busy" @click="onReset">Zurücksetzen</button>
        </div>
      </template>
    </div>
  </div>
</template>
