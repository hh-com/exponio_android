<script setup lang="ts">
import { computed } from 'vue'

export type IconName =
  | 'settings'
  | 'x'
  | 'trash'
  | 'back'
  | 'flash'
  | 'flash-off'
  | 'upload'
  | 'check'
  | 'alert'
  | 'refresh'
  | 'eye'
  | 'eye-off'
  | 'file'
  | 'key'
  | 'server'
  | 'image'
  | 'camera'
  | 'spinner'
  | 'shield'

const props = withDefaults(defineProps<{ name: IconName; size?: number; stroke?: number }>(), {
  size: 22,
  stroke: 2,
})

const PATHS: Record<IconName, string[]> = {
  settings: ['M4 21v-7', 'M4 10V3', 'M12 21v-9', 'M12 8V3', 'M20 21v-5', 'M20 12V3', 'M1 14h6', 'M9 8h6', 'M17 16h6'],
  x: ['M18 6 6 18', 'M6 6l12 12'],
  trash: ['M3 6h18', 'M8 6V4h8v2', 'M19 6l-1 14H6L5 6', 'M10 11v6', 'M14 11v6'],
  back: ['M15 18l-6-6 6-6'],
  flash: ['M13 2 3 14h9l-1 8 10-12h-9l1-8z'],
  'flash-off': ['M13 2 3 14h9l-1 8 10-12h-9l1-8z', 'M2 2l20 20'],
  upload: ['M12 16V4', 'M7 9l5-5 5 5', 'M4 20h16'],
  check: ['M20 6 9 17l-5-5'],
  alert: ['M12 9v4', 'M12 17h.01', 'M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z'],
  refresh: ['M21 12a9 9 0 1 1-3-6.7L21 8', 'M21 3v5h-5'],
  eye: ['M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z'],
  'eye-off': ['M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M3 3l18 18'],
  file: ['M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z', 'M14 3v6h6'],
  key: ['M15.5 13a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9z', 'M12.3 11.7 3 21', 'M6 18l2.5 2.5', 'M8.5 15.5 11 18'],
  server: ['M4 4h16v6H4z', 'M4 14h16v6H4z', 'M8 7h.01', 'M8 17h.01'],
  image: ['M4 4h16v16H4z', 'M4 16l5-5 4 4 3-3 4 4', 'M15 9h.01'],
  camera: [
    'M4 7h3l2-3h6l2 3h3a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z',
    'M12 17a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
  ],
  spinner: ['M21 12a9 9 0 1 1-6.2-8.6'],
  shield: ['M12 3 4 6v6c0 5 3.4 8.3 8 9 4.6-.7 8-4 8-9V6z', 'M9 12l2 2 4-4'],
}

const paths = computed(() => PATHS[props.name])
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="stroke"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    :class="name === 'spinner' ? 'animate-spin' : undefined"
  >
    <path v-for="(d, i) in paths" :key="i" :d="d" />
  </svg>
</template>
