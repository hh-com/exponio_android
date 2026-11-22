import { computed, ref } from 'vue'
import { camera } from './camera'
import { capturePhoto, pendingCaptures } from './group'
import { isUploading } from './upload'

/** Wird bei jedem Auslösen erhöht → Blitz-Animation in der Vorschau. */
export const shutterTick = ref(0)

/** Max. gleichzeitig laufende Aufnahmen (schützt vor Speicherspitzen bei Dauerfeuer). */
const MAX_PARALLEL = 4

export const canShoot = computed(() => camera.running && !isUploading.value)

export function shoot(): void {
  if (!canShoot.value || pendingCaptures.value >= MAX_PARALLEL) return
  shutterTick.value++
  void capturePhoto()
}
