<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, watchEffect } from 'vue'
import { App as CapApp } from '@capacitor/app'
import type { PluginListenerHandle } from '@capacitor/core'
import AppHeader from './components/AppHeader.vue'
import AppSnackbar from './components/AppSnackbar.vue'
import CameraPanel from './components/CameraPanel.vue'
import PhotoGrid from './components/PhotoGrid.vue'
import PhotoLightbox from './components/PhotoLightbox.vue'
import SettingsView from './components/SettingsView.vue'
import UploadBar from './components/UploadBar.vue'
import { initCameraListeners, refreshPermission, requestPermission, setCameraActive, setVolumeKeys } from './state/camera'
import { connection, quickCheck, watchServerChanges } from './state/connection'
import { flushDeletes, loadGroup, photos, removePhoto, rotatePhoto } from './state/group'
import { closeLightbox, lightboxIndex, view } from './state/navigation'
import { flushNote, loadNote, noteFocused } from './state/note'
import { flushSettings, loadSettings, settings, settingsLoaded } from './state/settings'
import { shoot } from './state/shoot'
import { initUploadListener, isUploading, refreshUploadState } from './state/upload'
import { isNative } from './native/plugins'

const settingsRef = ref<InstanceType<typeof SettingsView> | null>(null)
const handles: PluginListenerHandle[] = []

/** Status älter als 5 Minuten → beim Zurückkehren in die App neu prüfen. */
const STALE_MS = 5 * 60 * 1000

// Kamera läuft in der Hauptansicht (auch hinter der deckenden Lightbox, damit sie sofort bereit ist).
const cameraActive = computed(() => settingsLoaded.value && view.value === 'main')
watchEffect(() => setCameraActive(cameraActive.value))

// Lautstärketasten nur als Auslöser, wenn die Vorschau sichtbar ist, kein Upload läuft und niemand tippt.
watch(
  [lightboxIndex, isUploading, () => settings.camera.volumeKeys, noteFocused],
  () => {
    void setVolumeKeys(lightboxIndex.value === null && !isUploading.value && !noteFocused.value)
  },
)

function onVolumeShutter(): void {
  if (view.value === 'main' && lightboxIndex.value === null) shoot()
}

function onBack(): void {
  if (lightboxIndex.value !== null) {
    closeLightbox()
  } else if (view.value === 'settings') {
    void settingsRef.value?.onBack()
  } else {
    void CapApp.minimizeApp()
  }
}

onMounted(async () => {
  await loadSettings()
  watchServerChanges()
  await Promise.all([loadGroup(), loadNote(), initUploadListener(), refreshUploadState()])

  if (!isNative) return

  await initCameraListeners(onVolumeShutter, () => undefined)
  const permission = await refreshPermission()
  if (permission === 'prompt') await requestPermission()

  void quickCheck()

  handles.push(await CapApp.addListener('backButton', onBack))
  handles.push(
    await CapApp.addListener('pause', () => {
      void flushDeletes()
      void flushSettings()
      void flushNote()
    }),
  )
  handles.push(
    await CapApp.addListener('resume', () => {
      void refreshPermission()
      void refreshUploadState()
      if (!isUploading.value && connection.state !== 'checking' && Date.now() - connection.checkedAt > STALE_MS) {
        void quickCheck()
      }
    }),
  )
})

onBeforeUnmount(() => {
  for (const h of handles) void h.remove()
})
</script>

<template>
  <!-- Hauptansicht: transparent, damit die native Kamera-Vorschau im "Loch" sichtbar ist -->
  <div class="fixed inset-0 flex flex-col">
    <AppHeader />
    <CameraPanel />
    <PhotoGrid />
    <UploadBar />
  </div>

  <SettingsView v-if="view === 'settings'" ref="settingsRef" />

  <PhotoLightbox
    v-if="lightboxIndex !== null"
    :photos="photos"
    :start-index="lightboxIndex"
    :can-delete="!isUploading"
    :can-rotate="!isUploading"
    @close="closeLightbox"
    @delete="removePhoto"
    @rotate="rotatePhoto"
  />

  <AppSnackbar />
</template>
