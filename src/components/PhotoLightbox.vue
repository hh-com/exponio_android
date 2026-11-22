<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import type { Photo } from '../state/group'

const props = defineProps<{ photos: Photo[]; startIndex: number; canDelete: boolean }>()
const emit = defineEmits<{ close: []; delete: [id: string] }>()

const GAP = 16
const MAX_SCALE = 5
const DOUBLE_TAP_SCALE = 2.5
const DOUBLE_TAP_MS = 280
const SWIPE_DURATION = 240

// ---- Zustand ---------------------------------------------------------------------------------

const stage = ref<HTMLElement | null>(null)
const width = ref(window.innerWidth)
const height = ref(window.innerHeight)

const index = ref(Math.min(Math.max(0, props.startIndex), Math.max(0, props.photos.length - 1)))
const currentId = ref(props.photos[index.value]?.id ?? '')

const scale = ref(1)
const tx = ref(0)
const ty = ref(0)
const track = ref(0)
const dismissY = ref(0)
const animating = ref(false)
const chromeVisible = ref(true)

const current = computed<Photo | undefined>(() => props.photos[index.value])
const total = computed(() => props.photos.length)

// Liste ändert sich (Löschen / Rückgängig) → aktuelles Foto halten bzw. Nachbar zeigen.
watch(
  () => props.photos.map((p) => p.id).join('|'),
  () => {
    if (props.photos.length === 0) {
      emit('close')
      return
    }
    const found = props.photos.findIndex((p) => p.id === currentId.value)
    if (found >= 0) {
      index.value = found
    } else {
      index.value = Math.min(index.value, props.photos.length - 1)
      resetZoom(false)
    }
    currentId.value = props.photos[index.value]?.id ?? ''
  },
)

watch(index, () => {
  currentId.value = props.photos[index.value]?.id ?? ''
})

// ---- Geometrie -------------------------------------------------------------------------------

function fitSize(photo: Photo | undefined): { w: number; h: number } {
  if (!photo) return { w: width.value, h: height.value }
  const r = Math.min(width.value / photo.width, height.value / photo.height)
  return { w: photo.width * r, h: photo.height * r }
}

function bounds(s: number): { maxX: number; maxY: number } {
  const { w, h } = fitSize(current.value)
  return {
    maxX: Math.max(0, (w * s - width.value) / 2),
    maxY: Math.max(0, (h * s - height.value) / 2),
  }
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v))
}

/** Gummiband-Effekt jenseits der Grenzen. */
function rubber(v: number, max: number): number {
  if (v > max) return max + (v - max) * 0.3
  if (v < -max) return -max + (v + max) * 0.3
  return v
}

function resetZoom(animate = true): void {
  animating.value = animate
  scale.value = 1
  tx.value = 0
  ty.value = 0
}

function settleZoom(): void {
  animating.value = true
  if (scale.value <= 1.02) {
    resetZoom(true)
    return
  }
  scale.value = clamp(scale.value, 1, MAX_SCALE)
  const { maxX, maxY } = bounds(scale.value)
  tx.value = clamp(tx.value, -maxX, maxX)
  ty.value = clamp(ty.value, -maxY, maxY)
}

function zoomAt(clientX: number, clientY: number, target: number): void {
  // Bildpunkt unter dem Finger bleibt an Ort und Stelle.
  const rect = stage.value?.getBoundingClientRect()
  const px = clientX - (rect?.left ?? 0) - width.value / 2
  const py = clientY - (rect?.top ?? 0) - height.value / 2
  const qx = (px - tx.value) / scale.value
  const qy = (py - ty.value) / scale.value
  scale.value = target
  tx.value = px - target * qx
  ty.value = py - target * qy
  settleZoom()
}

// ---- Gesten ----------------------------------------------------------------------------------

type Mode = 'none' | 'pending' | 'pan' | 'swipe' | 'dismiss' | 'pinch'

const pointers = new Map<number, { x: number; y: number }>()
let mode: Mode = 'none'
let startX = 0
let startY = 0
let startTx = 0
let startTy = 0
let startScale = 1
let startDist = 1
let anchorX = 0
let anchorY = 0
let samples: { x: number; y: number; t: number }[] = []
let lastTap = { t: 0, x: 0, y: 0 }
let tapTimer: number | undefined
let swipeTimer: number | undefined

function localPoint(x: number, y: number): { x: number; y: number } {
  const rect = stage.value?.getBoundingClientRect()
  return { x: x - (rect?.left ?? 0) - width.value / 2, y: y - (rect?.top ?? 0) - height.value / 2 }
}

function pinchInfo(): { dist: number; mid: { x: number; y: number } } {
  const [a, b] = [...pointers.values()]
  return {
    dist: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)),
    mid: localPoint((a.x + b.x) / 2, (a.y + b.y) / 2),
  }
}

function beginPinch(): void {
  const { dist, mid } = pinchInfo()
  mode = 'pinch'
  startDist = dist
  startScale = scale.value
  anchorX = (mid.x - tx.value) / scale.value
  anchorY = (mid.y - ty.value) / scale.value
  if (track.value !== 0) {
    animating.value = true
    track.value = 0
  }
  dismissY.value = 0
}

function beginSingle(x: number, y: number): void {
  mode = 'pending'
  startX = x
  startY = y
  startTx = tx.value
  startTy = ty.value
  samples = [{ x, y, t: performance.now() }]
}

function onPointerDown(e: PointerEvent): void {
  if (swipeTimer !== undefined) return
  stage.value?.setPointerCapture(e.pointerId)
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  animating.value = false
  if (pointers.size === 1) beginSingle(e.clientX, e.clientY)
  else if (pointers.size === 2) beginPinch()
}

function onPointerMove(e: PointerEvent): void {
  if (!pointers.has(e.pointerId)) return
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })

  if (mode === 'pinch' && pointers.size >= 2) {
    const { dist, mid } = pinchInfo()
    const s = clamp(startScale * (dist / startDist), 0.7, MAX_SCALE * 1.3)
    scale.value = s
    tx.value = mid.x - s * anchorX
    ty.value = mid.y - s * anchorY
    return
  }
  if (pointers.size !== 1) return

  const dx = e.clientX - startX
  const dy = e.clientY - startY
  samples.push({ x: e.clientX, y: e.clientY, t: performance.now() })
  if (samples.length > 6) samples.shift()

  if (mode === 'pending') {
    if (Math.hypot(dx, dy) < 8) return
    if (scale.value > 1.02) mode = 'pan'
    else if (Math.abs(dx) >= Math.abs(dy)) mode = 'swipe'
    else if (dy > 0) mode = 'dismiss'
    else mode = 'none'
  }

  if (mode === 'pan') {
    const { maxX, maxY } = bounds(scale.value)
    tx.value = rubber(startTx + dx, maxX)
    ty.value = rubber(startTy + dy, maxY)
  } else if (mode === 'swipe') {
    const atStart = index.value === 0 && dx > 0
    const atEnd = index.value === total.value - 1 && dx < 0
    track.value = atStart || atEnd ? dx * 0.3 : dx
  } else if (mode === 'dismiss') {
    dismissY.value = Math.max(0, dy)
  }
}

function velocity(): { vx: number; vy: number } {
  if (samples.length < 2) return { vx: 0, vy: 0 }
  const a = samples[0]
  const b = samples[samples.length - 1]
  const dt = Math.max(1, b.t - a.t)
  return { vx: (b.x - a.x) / dt, vy: (b.y - a.y) / dt }
}

function finishSwipe(): void {
  const { vx } = velocity()
  const threshold = width.value * 0.18
  let dir = 0
  if ((track.value < -threshold || vx < -0.45) && index.value < total.value - 1) dir = 1
  else if ((track.value > threshold || vx > 0.45) && index.value > 0) dir = -1

  animating.value = true
  if (dir === 0) {
    track.value = 0
    return
  }
  track.value = -dir * (width.value + GAP)
  swipeTimer = window.setTimeout(() => {
    swipeTimer = undefined
    animating.value = false
    index.value += dir
    track.value = 0
    scale.value = 1
    tx.value = 0
    ty.value = 0
  }, SWIPE_DURATION)
}

function finishDismiss(): void {
  const { vy } = velocity()
  if (dismissY.value > 130 || vy > 0.8) {
    emit('close')
    return
  }
  animating.value = true
  dismissY.value = 0
}

function handleTap(x: number, y: number): void {
  const now = performance.now()
  if (now - lastTap.t < DOUBLE_TAP_MS && Math.hypot(x - lastTap.x, y - lastTap.y) < 40) {
    window.clearTimeout(tapTimer)
    lastTap = { t: 0, x: 0, y: 0 }
    if (scale.value > 1.02) resetZoom(true)
    else zoomAt(x, y, DOUBLE_TAP_SCALE)
    return
  }
  lastTap = { t: now, x, y }
  window.clearTimeout(tapTimer)
  tapTimer = window.setTimeout(() => {
    chromeVisible.value = !chromeVisible.value
  }, DOUBLE_TAP_MS)
}

function onPointerUp(e: PointerEvent, cancelled = false): void {
  if (!pointers.has(e.pointerId)) return
  pointers.delete(e.pointerId)

  if (mode === 'pinch') {
    if (pointers.size === 1) {
      // Weiter mit dem verbleibenden Finger schieben.
      const [p] = [...pointers.values()]
      mode = 'pan'
      startX = p.x
      startY = p.y
      startTx = tx.value
      startTy = ty.value
      samples = []
    } else if (pointers.size === 0) {
      mode = 'none'
      settleZoom()
    }
    return
  }
  if (pointers.size > 0) return

  const finished = mode
  mode = 'none'
  if (finished === 'pending' && !cancelled) handleTap(e.clientX, e.clientY)
  else if (finished === 'pan') settleZoom()
  else if (finished === 'swipe') finishSwipe()
  else if (finished === 'dismiss') finishDismiss()
}

// ---- Darstellung -----------------------------------------------------------------------------

const slides = computed(() => {
  const result: { photo: Photo; i: number }[] = []
  for (let i = index.value - 1; i <= index.value + 1; i++) {
    const photo = props.photos[i]
    if (photo) result.push({ photo, i })
  }
  return result
})

const transition = computed(() =>
  animating.value ? `transform ${SWIPE_DURATION}ms cubic-bezier(0.2, 0.8, 0.2, 1)` : 'none',
)

function slideStyle(i: number): Record<string, string> {
  const offset = (i - index.value) * (width.value + GAP) + track.value
  return { transform: `translate3d(${offset}px, 0, 0)`, transition: transition.value }
}

function imageStyle(slide: { photo: Photo; i: number }): Record<string, string> {
  const { w, h } = fitSize(slide.photo)
  const isCurrent = slide.i === index.value
  const s = isCurrent ? scale.value : 1
  const x = isCurrent ? tx.value : 0
  const y = isCurrent ? ty.value : 0
  return {
    width: `${w}px`,
    height: `${h}px`,
    left: `${(width.value - w) / 2}px`,
    top: `${(height.value - h) / 2}px`,
    transform: `translate3d(${x}px, ${y}px, 0) scale(${s})`,
    transition: transition.value,
  }
}

const backdropOpacity = computed(() => Math.max(0.35, 1 - dismissY.value / 500))
const stageStyle = computed(() => ({
  transform: `translate3d(0, ${dismissY.value}px, 0)`,
  transition: transition.value,
}))

function onDelete(): void {
  const photo = current.value
  if (!photo || !props.canDelete) return
  resetZoom(false)
  emit('delete', photo.id)
}

// ---- Größe -----------------------------------------------------------------------------------

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  resizeObserver = new ResizeObserver(() => {
    if (!stage.value) return
    width.value = stage.value.clientWidth
    height.value = stage.value.clientHeight
  })
  if (stage.value) {
    width.value = stage.value.clientWidth
    height.value = stage.value.clientHeight
    resizeObserver.observe(stage.value)
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.clearTimeout(tapTimer)
  window.clearTimeout(swipeTimer)
})
</script>

<template>
  <div class="fixed inset-0 z-30 select-none" role="dialog" aria-label="Fotoansicht">
    <div class="absolute inset-0 bg-black" :style="{ opacity: backdropOpacity }" />

    <div
      ref="stage"
      class="absolute inset-0 touch-none overflow-hidden"
      :style="stageStyle"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp($event)"
      @pointercancel="onPointerUp($event, true)"
    >
      <div v-for="slide in slides" :key="slide.photo.id" class="absolute inset-0" :style="slideStyle(slide.i)">
        <img
          :src="slide.photo.previewUrl"
          class="absolute max-w-none origin-center will-change-transform"
          :style="imageStyle(slide)"
          alt=""
          draggable="false"
          decoding="async"
        />
      </div>
    </div>

    <!-- Bedienleiste oben -->
    <div
      class="pt-safe px-safe pointer-events-none absolute inset-x-0 top-0 bg-gradient-to-b from-black/75 to-transparent transition-opacity duration-200"
      :class="chromeVisible && dismissY === 0 ? 'opacity-100' : 'opacity-0'"
    >
      <div class="flex h-16 items-center justify-between px-2" :class="chromeVisible ? 'pointer-events-auto' : ''">
        <button
          type="button"
          class="flex h-12 w-12 items-center justify-center rounded-full text-white active:bg-white/15"
          aria-label="Schließen"
          @click="emit('close')"
        >
          <AppIcon name="x" :size="24" />
        </button>
        <span class="rounded-full bg-black/40 px-3 py-1 text-[15px] font-semibold tabular-nums text-white">
          {{ index + 1 }} / {{ total }}
        </span>
        <button
          type="button"
          class="flex h-12 w-12 items-center justify-center rounded-full text-white active:bg-danger/40 disabled:opacity-30"
          :disabled="!canDelete"
          aria-label="Foto löschen"
          @click="onDelete"
        >
          <AppIcon name="trash" :size="22" />
        </button>
      </div>
    </div>

    <!-- Hinweis unten -->
    <div
      class="pb-safe pointer-events-none absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/60 to-transparent transition-opacity duration-200"
      :class="chromeVisible && dismissY === 0 && scale <= 1.02 ? 'opacity-100' : 'opacity-0'"
    >
      <p class="px-4 pb-4 pt-8 text-center text-xs text-white/70">
        Wischen zum Blättern · Doppeltippen oder Spreizen zum Zoomen
      </p>
    </div>
  </div>
</template>
