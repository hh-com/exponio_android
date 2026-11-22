import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'org.exponio.app',
  appName: 'Exponio',
  webDir: 'dist',
  // Capacitor loggt sonst in Debug-Builds jeden Plugin-Aufruf samt Argumenten nach logcat,
  // also auch Passwort/Private Key aus setSecret(). Web-Konsole stattdessen über chrome://inspect.
  loggingBehavior: 'none',
  android: {
    // Die WebView ist transparent, damit die native Kamera-Vorschau (CameraX)
    // hinter ihr sichtbar wird. Alle übrigen Flächen malt die Web-UI selbst.
    backgroundColor: '#00000000',
    allowMixedContent: false,
    captureInput: false,
    // Standard: in Debug-Builds per chrome://inspect debugbar, in Release-Builds nicht.
  },
  plugins: {
    SystemBars: {
      // Dunkles UI → helle Statusbar-Icons. Insets kommen als CSS-Variablen
      // (--safe-area-inset-*) bzw. env(safe-area-inset-*) in der WebView an.
      style: 'DARK',
      insetsHandling: 'css',
      initialViewportFitValueHint: 'cover',
    },
  },
}

export default config
