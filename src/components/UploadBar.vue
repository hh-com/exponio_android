<script setup lang="ts">
import { computed, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import { photos } from '../state/group'
import { openSettings } from '../state/navigation'
import { NOTE_MAX_LENGTH, note, noteFocused, setNote } from '../state/note'
import { isServerConfigured, settings } from '../state/settings'
import { showToast } from '../state/toast'
import { cancelUpload, dismissUploadError, isUploading, progressText, startUpload, upload } from '../state/upload'

const count = computed(() => photos.value.length)
const noteField = ref<HTMLTextAreaElement | null>(null)

// Nach erfolgreichem Upload ist die Notiz geleert; solange die Erfolgsmeldung steht, bleibt das Feld weg.
const showNote = computed(() => !(upload.status === 'done' && upload.result && count.value === 0))

function onNoteInput(e: Event): void {
  setNote((e.target as HTMLTextAreaElement).value)
}

function onNoteFocus(focused: boolean): void {
  noteFocused.value = focused
}

function clearNote(): void {
  const previous = note.value
  setNote('')
  showToast('Notiz gelöscht', { action: { label: 'Rückgängig', run: () => setNote(previous) }, duration: 5000 })
}

function onUpload(): void {
  // Tastatur schließen, damit der Fortschritt sichtbar ist.
  noteField.value?.blur()
  void startUpload()
}

const percent = computed(() => Math.round((upload.progress?.fraction ?? 0) * 100))
const text = computed(() => progressText(upload.progress, settings.server.host.trim()))

const needsSettings = computed(
  () => upload.error?.code === 'HOST_KEY_UNKNOWN' ||
    upload.error?.code === 'HOST_KEY_CHANGED' ||
    upload.error?.code === 'NOT_CONFIGURED' ||
    upload.error?.code === 'NO_CREDENTIALS' ||
    upload.error?.code === 'KEY_INVALID' ||
    upload.error?.code === 'BASE_PATH_MISSING' ||
    upload.error?.code === 'BASE_PATH_NOT_DIR',
)

const resumeHint = computed(() => {
  const p = upload.pending
  if (!p || upload.status === 'running') return ''
  return `Unterbrochener Upload (${p.uploaded ?? 0}/${p.total ?? 0}) wird fortgesetzt`
})

const buttonLabel = computed(() => {
  if (count.value === 0) return 'Keine Fotos'
  return `${count.value} ${count.value === 1 ? 'Foto' : 'Fotos'} hochladen`
})
</script>

<template>
  <footer class="pb-safe px-safe relative z-10 border-t border-line bg-surface">
    <div class="px-4 py-3">
      <!-- Notiz → info.txt. Diktieren über das Mikrofon der Bildschirmtastatur. -->
      <div v-if="showNote" class="relative mb-2.5">
        <textarea
          ref="noteField"
          :value="note"
          class="field field-sizing-content block max-h-32 min-h-12 resize-none py-2.5 pr-11 leading-snug disabled:opacity-60"
          :maxlength="NOTE_MAX_LENGTH"
          :disabled="isUploading"
          placeholder="Notiz zum Objekt – tippen oder diktieren"
          aria-label="Notiz zum Objekt, wird als info.txt mit hochgeladen"
          autocapitalize="sentences"
          autocomplete="off"
          spellcheck="true"
          @input="onNoteInput"
          @focus="onNoteFocus(true)"
          @blur="onNoteFocus(false)"
        />
        <button
          v-if="note && !isUploading"
          type="button"
          class="absolute right-1 top-1 flex h-10 w-10 items-center justify-center rounded-full text-muted active:bg-line"
          aria-label="Notiz löschen"
          @click="clearNote"
        >
          <AppIcon name="x" :size="16" />
        </button>
      </div>

      <!-- Läuft -->
      <div v-if="upload.status === 'running'" class="flex items-center gap-3">
        <div class="min-w-0 flex-1">
          <div class="mb-1.5 flex items-center justify-between gap-2 text-sm">
            <span class="truncate font-medium">{{ upload.cancelling ? 'Wird abgebrochen…' : text }}</span>
            <span class="tabular-nums text-muted">{{ percent }} %</span>
          </div>
          <div class="h-2 overflow-hidden rounded-full bg-surface-3">
            <div class="h-full rounded-full bg-accent transition-[width] duration-200" :style="{ width: percent + '%' }" />
          </div>
        </div>
        <button
          type="button"
          class="btn-ghost shrink-0 px-3.5 py-2.5 text-sm"
          :disabled="upload.cancelling"
          @click="cancelUpload"
        >
          Abbrechen
        </button>
      </div>

      <!-- Fehler -->
      <div v-else-if="upload.status === 'error' && upload.error" class="space-y-2.5">
        <div class="flex items-start gap-2.5 rounded-xl border border-danger/40 bg-danger/10 p-3">
          <AppIcon name="alert" :size="18" class="mt-0.5 shrink-0 text-red-300" />
          <div class="min-w-0 flex-1 text-sm">
            <p class="font-medium text-red-200">Upload fehlgeschlagen</p>
            <p class="mt-0.5 break-words text-red-100/80">{{ upload.error.message }}</p>
            <p class="mt-1 text-xs text-muted">Die Fotos sind weiterhin auf dem Gerät gespeichert.</p>
          </div>
          <button
            type="button"
            class="-m-1 flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-red-200 active:bg-danger/20"
            aria-label="Meldung schließen"
            @click="dismissUploadError"
          >
            <AppIcon name="x" :size="16" />
          </button>
        </div>
        <div class="flex gap-2">
          <button v-if="needsSettings" type="button" class="btn-ghost flex-1" @click="openSettings">
            Einstellungen
          </button>
          <button type="button" class="btn-primary flex-1" :disabled="count === 0" @click="onUpload">
            <AppIcon name="refresh" :size="18" />
            Erneut versuchen
          </button>
        </div>
      </div>

      <!-- Erfolgreich -->
      <div
        v-else-if="upload.status === 'done' && upload.result && count === 0"
        class="flex items-center gap-3 rounded-xl border border-ok/30 bg-ok/10 p-3"
      >
        <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-ok/20 text-emerald-300">
          <AppIcon name="check" :size="18" :stroke="2.5" />
        </span>
        <div class="min-w-0 text-sm">
          <p class="font-medium text-emerald-200">
            {{ upload.result.count }} {{ upload.result.count === 1 ? 'Foto' : 'Fotos' }} hochgeladen und verifiziert
          </p>
          <p class="truncate font-mono text-xs text-muted">{{ upload.result.remotePath }}</p>
        </div>
      </div>

      <!-- Bereit -->
      <div v-else class="space-y-1.5">
        <button
          v-if="!isServerConfigured"
          type="button"
          class="btn-ghost w-full"
          @click="openSettings"
        >
          <AppIcon name="server" :size="18" />
          Server einrichten, um hochzuladen
        </button>
        <button
          v-else
          type="button"
          class="btn-primary w-full py-3.5 text-base"
          :disabled="count === 0"
          @click="onUpload"
        >
          <AppIcon name="upload" :size="19" />
          {{ buttonLabel }}
        </button>
        <p v-if="resumeHint && count > 0" class="text-center text-xs text-muted">{{ resumeHint }}</p>
      </div>
    </div>
  </footer>
</template>
