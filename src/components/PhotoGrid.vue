<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import { pendingCaptures, photos, removePhoto, thumbTurnStyle } from '../state/group'
import { openLightbox } from '../state/navigation'
import { isUploading } from '../state/upload'

const scroller = ref<HTMLElement | null>(null)

function badge(index: number): string {
  const digits = Math.max(2, String(photos.value.length).length)
  return String(index + 1).padStart(digits, '0')
}

// Neue Aufnahme → ans Ende scrollen, damit das neueste Foto sichtbar ist.
watch(
  () => photos.value.length + pendingCaptures.value,
  async (now, before) => {
    if (now <= before) return
    await nextTick()
    scroller.value?.scrollTo({ top: scroller.value.scrollHeight, behavior: 'smooth' })
  },
)
</script>

<template>
  <section class="flex min-h-0 flex-1 flex-col bg-surface">
    <div class="flex items-center justify-between px-4 pb-2 pt-1">
      <div class="flex items-baseline gap-2">
        <h2 class="text-[15px] font-semibold">Aktuelle Gruppe</h2>
        <span class="rounded-full bg-surface-3 px-2 py-0.5 text-xs font-semibold tabular-nums text-zinc-300">
          {{ photos.length }} {{ photos.length === 1 ? 'Foto' : 'Fotos' }}
        </span>
      </div>
      <span v-if="photos.length > 0" class="text-xs text-muted">Tippen zum Prüfen</span>
    </div>

    <div ref="scroller" class="scroll-area min-h-0 flex-1 px-3 pb-3">
      <div
        v-if="photos.length === 0 && pendingCaptures === 0"
        class="flex h-full min-h-24 flex-col items-center justify-center gap-1 rounded-2xl border border-dashed border-line px-6 text-center"
      >
        <p class="text-sm text-zinc-300">Noch keine Fotos in dieser Gruppe.</p>
        <p class="text-xs text-muted">Auslöser oder Lautstärketaste drücken.</p>
      </div>

      <ul v-else class="grid grid-cols-4 gap-1.5">
        <li
          v-for="(photo, index) in photos"
          :key="photo.id"
          class="relative aspect-square overflow-hidden rounded-xl bg-surface-3"
        >
          <button
            type="button"
            class="block h-full w-full active:opacity-80"
            :aria-label="`Foto ${index + 1} ansehen`"
            @click="openLightbox(index)"
          >
            <img
              :src="photo.thumbUrl"
              class="h-full w-full object-cover"
              :style="thumbTurnStyle(photo.id)"
              alt=""
              draggable="false"
              decoding="async"
              loading="lazy"
            />
          </button>
          <span
            class="pointer-events-none absolute bottom-1 left-1 rounded-md bg-black/60 px-1.5 py-0.5 text-[11px] font-semibold tabular-nums text-white"
          >
            {{ badge(index) }}
          </span>
          <button
            type="button"
            class="absolute right-0 top-0 flex h-10 w-10 items-start justify-end p-1.5 disabled:opacity-0"
            :disabled="isUploading"
            :aria-label="`Foto ${index + 1} löschen`"
            @click.stop="removePhoto(photo.id)"
          >
            <span class="flex h-7 w-7 items-center justify-center rounded-full bg-black/65 text-white active:bg-danger">
              <AppIcon name="x" :size="15" :stroke="2.5" />
            </span>
          </button>
        </li>
        <li
          v-for="n in pendingCaptures"
          :key="'pending-' + n"
          class="animate-pulse-soft flex aspect-square items-center justify-center rounded-xl bg-surface-3 text-muted"
        >
          <AppIcon name="spinner" :size="18" />
        </li>
      </ul>
    </div>
  </section>
</template>
