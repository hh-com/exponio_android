<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import {
  camera,
  focusAt,
  openAppSettings,
  requestPermission,
  restartCamera,
  setCameraRect,
  setFlash,
  setZoom,
} from '../state/camera'
import { pendingCaptures, photos } from '../state/group'
import { openLightbox } from '../state/navigation'
import { settings } from '../state/settings'
import { canShoot, shoot, shutterTick } from '../state/shoot'
import { isNative } from '../native/plugins'
import type { FlashMode } from '../native/plugins'

const hole = ref<HTMLElement | null>(null)
let resizeObserver: ResizeObserver | null = null
let rectFrame = 0

// ---- Position des nativen Vorschaufensters synchron halten -----------------------------------

function reportRect(): void {
  cancelAnimationFrame(rectFrame)
  rectFrame = requestAnimationFrame(() => {
    const el = hole.value
    if (!el) return
    const r = el.getBoundingClientRect()
    if (r.width < 2 || r.height < 2) {
      setCameraRect(null)
      return
    }
    setCameraRect({ x: r.left, y: r.top, width: r.width, height: r.height })
  })
}

onMounted(() => {
  resizeObserver = new ResizeObserver(reportRect)
  if (hole.value) resizeObserver.observe(hole.value)
  window.addEventListener('resize', reportRect)
  reportRect()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', reportRect)
  cancelAnimationFrame(rectFrame)
  setCameraRect(null)
})

// ---- Fokus per Tipp, Zoom per Pinch ----------------------------------------------------------

const focusRing = ref<{ x: number; y: number; key: number } | null>(null)
let focusKey = 0
const pointers = new Map<number, { x: number; y: number }>()
let pinch: { dist: number; zoom: number } | null = null
let tap: { x: number; y: number; t: number; moved: boolean } | null = null
let zoomFrame = 0
let pendingZoom: number | null = null

function distance(): number {
  const [a, b] = [...pointers.values()]
  return Math.hypot(a.x - b.x, a.y - b.y)
}

function scheduleZoom(value: number): void {
  pendingZoom = value
  if (zoomFrame) return
  zoomFrame = requestAnimationFrame(() => {
    zoomFrame = 0
    if (pendingZoom !== null) void setZoom(pendingZoom)
    pendingZoom = null
  })
}

function onPointerDown(e: PointerEvent): void {
  if (!camera.running) return
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  if (pointers.size === 1) {
    tap = { x: e.clientX, y: e.clientY, t: performance.now(), moved: false }
  } else if (pointers.size === 2) {
    tap = null
    pinch = { dist: Math.max(1, distance()), zoom: camera.zoom }
  }
}

function onPointerMove(e: PointerEvent): void {
  if (!pointers.has(e.pointerId)) return
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  if (pinch && pointers.size >= 2) {
    scheduleZoom(pinch.zoom * (distance() / pinch.dist))
  } else if (tap && Math.hypot(e.clientX - tap.x, e.clientY - tap.y) > 12) {
    tap.moved = true
  }
}

function onPointerUp(e: PointerEvent): void {
  if (!pointers.has(e.pointerId)) return
  pointers.delete(e.pointerId)
  if (pointers.size < 2) pinch = null
  if (pointers.size === 0) {
    if (tap && !tap.moved && performance.now() - tap.t < 450 && hole.value) {
      const r = hole.value.getBoundingClientRect()
      const x = tap.x - r.left
      const y = tap.y - r.top
      focusRing.value = { x, y, key: ++focusKey }
      void focusAt(x / r.width, y / r.height)
    }
    tap = null
  }
}

function onPointerCancel(e: PointerEvent): void {
  pointers.delete(e.pointerId)
  if (pointers.size < 2) pinch = null
  if (pointers.size === 0) tap = null
}

// ---- Zoom-Stufen -----------------------------------------------------------------------------

const zoomStops = computed(() => {
  const stops: number[] = []
  if (camera.minZoom < 0.95) stops.push(camera.minZoom)
  for (const z of [1, 2, 5]) if (z <= camera.maxZoom + 0.01) stops.push(z)
  return stops
})

function formatZoom(z: number): string {
  const rounded = Math.round(z * 10) / 10
  return (Number.isInteger(rounded) ? rounded.toFixed(0) : rounded.toFixed(1).replace('.', ',')) + '×'
}

const activeStop = computed(() => {
  let best = zoomStops.value[0] ?? 1
  for (const z of zoomStops.value) if (Math.abs(z - camera.zoom) < Math.abs(best - camera.zoom)) best = z
  return Math.abs(best - camera.zoom) < 0.08 ? best : null
})

const showLiveZoom = computed(() => camera.running && activeStop.value === null)

// ---- Blitz & Auslöser ------------------------------------------------------------------------

const FLASH_ORDER: FlashMode[] = ['off', 'auto', 'on']

function cycleFlash(): void {
  const next = FLASH_ORDER[(FLASH_ORDER.indexOf(settings.camera.flash) + 1) % FLASH_ORDER.length]
  void setFlash(next)
}

const flashLabel = computed(() =>
  settings.camera.flash === 'on' ? 'Blitz an' : settings.camera.flash === 'auto' ? 'Blitz automatisch' : 'Blitz aus',
)

const shutterFlash = ref(0)
watch(shutterTick, () => {
  shutterFlash.value++
})

/** Symbole bei quer gehaltenem Gerät mitdrehen (Activity bleibt im Hochformat). */
const iconRotation = computed(() => {
  const r = camera.rotation
  return `rotate(${r === 270 ? -90 : r}deg)`
})

const lastPhoto = computed(() => photos.value[photos.value.length - 1] ?? null)

function openLast(): void {
  if (photos.value.length > 0) openLightbox(photos.value.length - 1)
}
</script>

<template>
  <section class="relative flex flex-none flex-col">
    <!-- Vorschaubereich: das "Loch" ist transparent, die native PreviewView liegt dahinter -->
    <div class="relative overflow-hidden" style="height: min(calc(100vw * 4 / 3), 46vh)">
      <div
        ref="hole"
        class="absolute inset-y-0 left-1/2 aspect-[3/4] max-w-[calc(100%-24px)] -translate-x-1/2 touch-none rounded-[18px]"
        style="box-shadow: 0 0 0 100vmax var(--color-surface)"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointercancel="onPointerCancel"
      >
        <!-- Kamera nicht verfügbar / Berechtigung / Fehler -->
        <div
          v-if="!isNative || camera.permission !== 'granted' || camera.error"
          class="absolute inset-0 flex flex-col items-center justify-center gap-3 rounded-[18px] bg-surface-2 p-6 text-center"
        >
          <AppIcon name="camera" :size="34" class="text-muted" />
          <template v-if="!isNative">
            <p class="text-sm text-muted">Die Kamera ist nur in der Android-App verfügbar.</p>
          </template>
          <template v-else-if="camera.error">
            <p class="text-sm text-zinc-300">{{ camera.error }}</p>
            <button type="button" class="btn-ghost px-4 py-2 text-sm" @click="restartCamera">Erneut versuchen</button>
          </template>
          <template v-else-if="camera.permission === 'denied'">
            <p class="text-sm text-zinc-300">Kein Kamera-Zugriff. Bitte in den App-Einstellungen erlauben.</p>
            <div class="flex gap-2">
              <button type="button" class="btn-ghost px-4 py-2 text-sm" @click="requestPermission">Erneut fragen</button>
              <button type="button" class="btn-primary px-4 py-2 text-sm" @click="openAppSettings">Einstellungen</button>
            </div>
          </template>
          <template v-else>
            <p class="text-sm text-zinc-300">Für Produktfotos wird der Kamera-Zugriff benötigt.</p>
            <button type="button" class="btn-primary px-4 py-2 text-sm" @click="requestPermission">Kamera erlauben</button>
          </template>
        </div>

        <!-- Startet -->
        <div
          v-else-if="!camera.running"
          class="absolute inset-0 flex items-center justify-center rounded-[18px] bg-black/60 text-muted"
        >
          <AppIcon name="spinner" :size="28" />
        </div>

        <!-- Auslöse-Blitz -->
        <div
          v-if="shutterFlash > 0"
          :key="shutterFlash"
          class="animate-shutter pointer-events-none absolute inset-0 rounded-[18px] bg-white"
        />

        <!-- Fokus-Ring -->
        <div
          v-if="focusRing"
          :key="focusRing.key"
          class="animate-focus pointer-events-none absolute h-16 w-16 rounded-full border-2 border-white/90"
          :style="{ left: focusRing.x + 'px', top: focusRing.y + 'px' }"
        />

        <!-- Zoom-Stufen -->
        <div
          v-if="camera.running && zoomStops.length > 1"
          class="pointer-events-none absolute inset-x-0 bottom-3 flex justify-center"
        >
          <div
            class="pointer-events-auto flex items-center gap-1 rounded-full bg-black/45 p-1 backdrop-blur-sm"
            @pointerdown.stop
          >
            <button
              v-for="z in zoomStops"
              :key="z"
              type="button"
              class="flex h-9 min-w-9 items-center justify-center rounded-full px-2 text-[13px] font-semibold transition"
              :class="activeStop === z ? 'bg-white/90 text-black' : 'text-white'"
              :style="{ transform: iconRotation }"
              @click="setZoom(z)"
            >
              {{ formatZoom(z) }}
            </button>
            <span
              v-if="showLiveZoom"
              class="flex h-9 min-w-11 items-center justify-center rounded-full bg-white/90 px-2 text-[13px] font-semibold text-black"
            >
              {{ formatZoom(camera.zoom) }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- Auslöserleiste -->
    <div class="relative grid h-[92px] grid-cols-3 items-center bg-surface px-6">
      <div class="flex justify-start">
        <button
          type="button"
          class="relative flex h-12 w-12 items-center justify-center rounded-full bg-surface-3 text-zinc-100 transition active:bg-line disabled:opacity-40"
          :disabled="!camera.running || !camera.hasFlash"
          :aria-label="flashLabel"
          :style="{ transform: iconRotation }"
          @click="cycleFlash"
        >
          <AppIcon :name="settings.camera.flash === 'off' ? 'flash-off' : 'flash'" :size="20" />
          <span
            v-if="settings.camera.flash === 'auto'"
            class="absolute bottom-1.5 right-2 text-[10px] font-bold leading-none text-amber-300"
          >A</span>
          <span
            v-if="settings.camera.flash === 'on'"
            class="absolute bottom-1.5 right-2 h-1.5 w-1.5 rounded-full bg-amber-300"
          />
        </button>
      </div>

      <div class="flex justify-center">
        <button
          type="button"
          class="group relative flex h-[72px] w-[72px] items-center justify-center rounded-full border-[3px] border-white/90 transition disabled:opacity-35"
          :disabled="!canShoot"
          aria-label="Foto aufnehmen"
          @click="shoot"
        >
          <span class="h-[58px] w-[58px] rounded-full bg-white transition group-active:scale-90" />
          <span
            v-if="pendingCaptures > 0"
            class="absolute -right-1 -top-1 flex h-6 min-w-6 items-center justify-center rounded-full bg-accent px-1.5 text-xs font-semibold"
          >
            <AppIcon name="spinner" :size="12" :stroke="3" />
          </span>
        </button>
      </div>

      <div class="flex justify-end">
        <button
          type="button"
          class="relative h-12 w-12 overflow-hidden rounded-xl border border-line bg-surface-3 transition active:scale-95 disabled:opacity-40"
          :disabled="!lastPhoto"
          aria-label="Letztes Foto ansehen"
          :style="{ transform: iconRotation }"
          @click="openLast"
        >
          <img v-if="lastPhoto" :src="lastPhoto.thumbUrl" class="h-full w-full object-cover" alt="" draggable="false" />
          <AppIcon v-else name="image" :size="20" class="m-auto text-muted" />
        </button>
      </div>
    </div>
  </section>
</template>
