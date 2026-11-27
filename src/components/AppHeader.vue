<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import { connection, quickCheck } from '../state/connection'
import { openSettings } from '../state/navigation'
import { settings, urlHost } from '../state/settings'
import { isUploading } from '../state/upload'

const status = computed(() => {
  switch (connection.state) {
    case 'unconfigured':
      return { label: 'Website verbinden', dot: 'bg-zinc-500', text: 'text-zinc-300', pulse: false }
    case 'unknown':
      return { label: 'Ungeprüft', dot: 'bg-zinc-400', text: 'text-zinc-300', pulse: false }
    case 'checking':
      return { label: 'Prüfe…', dot: 'bg-warn', text: 'text-amber-200', pulse: true }
    case 'ok':
      return { label: 'Verbunden', dot: 'bg-ok', text: 'text-emerald-200', pulse: false }
    case 'error':
    default:
      return { label: 'Keine Verbindung', dot: 'bg-danger', text: 'text-red-200', pulse: false }
  }
})

const hostLabel = computed(() => urlHost(settings.server.url))

function onStatusTap(): void {
  if (connection.state === 'unconfigured') {
    openSettings()
  } else if (connection.state !== 'checking' && !isUploading.value) {
    void quickCheck()
  }
}
</script>

<template>
  <header class="pt-safe px-safe relative z-10 bg-surface">
    <div class="flex h-14 items-center gap-3 px-4">
      <div class="min-w-0 flex-1">
        <div class="text-[17px] font-semibold leading-tight tracking-tight">Exponio</div>
        <div v-if="hostLabel" class="truncate text-xs text-muted">{{ hostLabel }}</div>
      </div>

      <button
        type="button"
        class="flex max-w-[55%] items-center gap-2 rounded-full border border-line bg-surface-2 py-1.5 pl-2.5 pr-3 text-[13px] font-medium active:bg-surface-3"
        :class="status.text"
        :title="connection.message || status.label"
        @click="onStatusTap"
      >
        <span class="h-2.5 w-2.5 shrink-0 rounded-full" :class="[status.dot, status.pulse ? 'animate-pulse-soft' : '']" />
        <span class="truncate">{{ status.label }}</span>
      </button>

      <button
        type="button"
        class="-mr-1 flex h-11 w-11 items-center justify-center rounded-full text-zinc-200 active:bg-surface-3"
        aria-label="Einstellungen"
        @click="openSettings"
      >
        <AppIcon name="settings" />
      </button>
    </div>
  </header>
</template>
