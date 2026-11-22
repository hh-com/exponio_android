import { ref } from 'vue'

export interface ToastAction {
  label: string
  run: () => void
}

export interface Toast {
  id: number
  message: string
  tone: 'neutral' | 'success' | 'error'
  action?: ToastAction
}

export const toast = ref<Toast | null>(null)

let timer: number | undefined
let seq = 0

export function showToast(
  message: string,
  options: { tone?: Toast['tone']; action?: ToastAction; duration?: number } = {},
): void {
  window.clearTimeout(timer)
  toast.value = { id: ++seq, message, tone: options.tone ?? 'neutral', action: options.action }
  const id = seq
  timer = window.setTimeout(() => {
    if (toast.value?.id === id) toast.value = null
  }, options.duration ?? 3500)
}

export function hideToast(): void {
  window.clearTimeout(timer)
  toast.value = null
}
