import { ref } from 'vue'

export type View = 'main' | 'settings'

export const view = ref<View>('main')

/** Index des in der Lightbox geöffneten Fotos (null = geschlossen). */
export const lightboxIndex = ref<number | null>(null)

export function openSettings(): void {
  lightboxIndex.value = null
  view.value = 'settings'
}

export function closeSettings(): void {
  view.value = 'main'
}

export function openLightbox(index: number): void {
  lightboxIndex.value = index
}

export function closeLightbox(): void {
  lightboxIndex.value = null
}
