<script setup lang="ts">
import { hideToast, toast } from '../state/toast'

function runAction(): void {
  const action = toast.value?.action
  hideToast()
  action?.run()
}
</script>

<template>
  <Transition
    enter-active-class="transition duration-200 ease-out"
    enter-from-class="translate-y-4 opacity-0"
    leave-active-class="transition duration-150 ease-in"
    leave-to-class="translate-y-4 opacity-0"
  >
    <div
      v-if="toast"
      :key="toast.id"
      class="pointer-events-none fixed inset-x-0 z-50 flex justify-center px-3"
      style="bottom: calc(var(--sab) + 96px)"
    >
      <div
        class="pointer-events-auto flex max-w-md items-center gap-3 rounded-2xl border px-4 py-3 text-sm shadow-xl"
        :class="{
          'border-line bg-surface-3 text-zinc-100': toast.tone === 'neutral',
          'border-ok/40 bg-[#0f2a1a] text-emerald-100': toast.tone === 'success',
          'border-danger/40 bg-[#2a1012] text-red-100': toast.tone === 'error',
        }"
        role="status"
      >
        <span class="min-w-0 flex-1 break-words">{{ toast.message }}</span>
        <button
          v-if="toast.action"
          type="button"
          class="-my-1 shrink-0 rounded-lg px-2 py-1 font-semibold text-accent active:bg-white/10"
          @click="runAction"
        >
          {{ toast.action.label }}
        </button>
      </div>
    </div>
  </Transition>
</template>
