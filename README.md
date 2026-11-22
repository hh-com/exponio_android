# Exponio (Android)

Minimale Sideloading-App für Produktfotos: **Aufnehmen → Prüfen → Hochladen**.
Fotos werden in Gruppen mit der In-App-Kamera aufgenommen, im 4er-Raster und in einer Lightbox geprüft,
nativ web-optimiert (WebP/JPEG) und per **FTPS** (z. B. Webhosting) oder **SFTP** in einen neuen Ordner
mit Zeitstempel hochgeladen.

- **Stack:** Vue 3 (`<script setup>`, TypeScript), Vite, Tailwind CSS 4, Capacitor 8
- **Android:** compileSdk/targetSdk 36 (Android 16), minSdk 35 (Android 15) – getestet für Pixel 10
- **Nativ (Kotlin):** CameraX, Bildverarbeitung (BitmapFactory/ExifInterface), SFTP via `com.github.mwiede:jsch`,
  FTPS via `commons-net` + Bouncy Castle JSSE

---

## 1. Voraussetzungen (einmalig)

| Werkzeug | Version |
|---|---|
| Node.js | ≥ 22 (getestet mit 24) |
| JDK | 21 (`sudo apt install openjdk-21-jdk`). Das JBR von Android Studio ist Java 25 und für Gradle 8.14 zu neu. `android/gradle/gradle-daemon-jvm.properties` lässt Gradle deshalb immer ein lokales JDK 21 verwenden, auch beim Bauen aus Studio heraus. |
| Android SDK | Platform 36, Build-Tools, Platform-Tools |

**Android SDK ohne Android Studio (Linux):**

```bash
mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
# "Command line tools only" von https://developer.android.com/studio#command-tools laden, dann:
unzip ~/Downloads/commandlinetools-linux-*_latest.zip && mv cmdline-tools latest

echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
echo 'export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH' >> ~/.bashrc
source ~/.bashrc

yes | sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Alternativ Android Studio installieren. Der SDK-Pfad landet dann automatisch in `android/local.properties`
(`sdk.dir=/home/<user>/Android/Sdk`).

**Pixel 10 vorbereiten:** Einstellungen → Über das Telefon → 7× auf „Build-Nummer“ tippen →
Entwickleroptionen → **USB-Debugging** aktivieren. Danach `adb devices` ausführen und den Zugriff auf dem Handy bestätigen.

---

> Ausführliche Schritt-für-Schritt-Anleitung nach Code-Änderungen (inkl. Versionsnummern, Release, Frisch-Klon): **[BUILD.md](BUILD.md)**

## 2. Bauen & installieren (Debug-APK)

```bash
npm install                 # Abhängigkeiten
npm run build               # Typecheck (vue-tsc) + Vite-Build nach dist/
npx cap sync android        # Web-Build + Plugins ins Android-Projekt kopieren
cd android && ./gradlew assembleDebug && cd ..
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Kurzform:

```bash
npm run apk:debug           # build + cap sync + assembleDebug
npm run install:debug       # adb install -r …/app-debug.apk
```

Die APK liegt unter `android/app/build/outputs/apk/debug/app-debug.apk`. Ohne USB kannst du sie auch per
Datei aufs Handy kopieren und dort öffnen (einmalig „Unbekannte Apps installieren“ für den Dateimanager erlauben).

> **Updates ohne Datenverlust:** Debug-APKs werden mit `~/.android/debug.keystore` signiert. Solange du immer
> auf demselben Rechner baust (oder diese Datei sicherst), installiert `adb install -r` jede neue Version
> **über** die alte. Einstellungen, Zugangsdaten und Fotos bleiben erhalten. Bei einer anderen Signatur verweigert
> Android das Update, dann wäre eine Deinstallation nötig und alle App-Daten gingen verloren.

Debugging der WebView (nur Debug-Build): Chrome am PC → `chrome://inspect`.

**Im Emulator testen** (ohne Handy). Die Rückkamera zeigt dort eine virtuelle 3D-Szene, Aufnahme und Vorschau funktionieren:

```bash
emulator -list-avds                          # z. B. Medium_Phone_API_37.0 (Android ≥ 15 nötig, minSdk 35)
emulator -avd Medium_Phone_API_37.0 &        # oder in Android Studio: Device Manager → ▶
adb wait-for-device && npm run install:debug
adb shell am start -n org.exponio.app/.MainActivity
adb logcat | grep -E 'Capacitor|CXCP|exponio'   # Logs der App
```

Sind Emulator und Pixel gleichzeitig verbunden, wählst du das Ziel mit `adb -s <seriennummer> install …` (Liste: `adb devices`).

---

## 3. Optional: Release-APK mit eigenem Keystore

Nur nötig, wenn du unabhängig vom Debug-Keystore signieren willst. **Wähle eine Variante (Debug oder Release)
und bleib dabei**, denn ein Wechsel der Signatur erfordert eine Neuinstallation.

```bash
# einmalig – die .jks-Datei und die Passwörter gut sichern!
keytool -genkeypair -v -keystore android/exponio-release.jks \
  -alias exponio -keyalg RSA -keysize 4096 -validity 36500

cat > android/keystore.properties <<'EOF'
storeFile=exponio-release.jks
storePassword=DEIN_STORE_PASSWORT
keyAlias=exponio
keyPassword=DEIN_KEY_PASSWORT
EOF

npm run apk:release
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

`keystore.properties` und `*.jks` stehen in `.gitignore`. Ohne `keystore.properties` wird der Release-Build
unsigniert erzeugt.

Für jedes Update `versionCode` (und `versionName`) in `android/app/build.gradle` erhöhen.

---

## 4. Server vorbereiten

Uploads landen immer im **Startverzeichnis des Zugangs** (dort, wo der Benutzer nach dem Login steht).
Den Zugang also so anlegen, dass er direkt im gewünschten Upload-Ordner startet.

### FTPS (Webhosting)

1. Im Hosting-Kundenmenü einen zusätzlichen FTP-Benutzer anlegen, dessen Verzeichnis der Upload-Ordner ist.
2. In der App als **Host den Servernamen aus dem Zertifikat** eintragen, z. B. `ftp.example.com`, nicht die IP.
   Das Zertifikat wird wie im Browser geprüft (System-CAs + Hostname). Den Namen zeigt z. B.
   `openssl s_client -starttls ftp -connect <IP>:21 </dev/null | grep subject`.
3. Port 21, Anmeldung per Passwort (explizites TLS, „FTP über TLS“).

Technik: Steuer- und Datenverbindung sind immer verschlüsselt (`AUTH TLS`, `PROT P`), es gibt keinen
Klartext-Fallback. Viele Server (ProFTPD-Standard) verlangen, dass die Datenverbindung die
TLS-Sitzung der Steuerverbindung wiederverwendet. Das erledigt Bouncy Castle JSSE. Dafür wird **TLS 1.2**
(ECDHE + AES-GCM) verwendet, weil Bouncy Castle TLS-1.3-Sitzungen dafür nicht fortsetzen kann.

### SFTP (eigener Server)

1. SFTP-Benutzer mit Schreibrechten in seinem Startverzeichnis. Upload-Benutzer ohne Shell brauchen in
   `sshd_config` `ForceCommand internal-sftp` (sonst „SFTP abgelehnt“).
2. Fingerprint des Servers zum Abgleich beim ersten Verbinden anzeigen:
   ```bash
   ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
   ```
3. Für Key-Login den öffentlichen Schlüssel in `~/.ssh/authorized_keys` des Upload-Benutzers eintragen.
   Unterstützt werden Ed25519, ECDSA und RSA (OpenSSH- oder PEM-Format, optional mit Passphrase).

---

## 5. Bedienung

1. **Einstellungen** (Regler-Symbol oben rechts): Protokoll (FTPS/SFTP), Host, Port, Benutzer und Passwort
   (bei SFTP alternativ Private Key) eintragen und **„Verbindung testen“** tippen. Der Test prüft auch die
   Schreibrechte: Er legt kurz einen Testordner mit einer kleinen Datei an und entfernt beides wieder.
   Nur SFTP: Beim ersten Kontakt wird der Host-Key-Fingerprint angezeigt. Nach „Vertrauen“ wird er gespeichert
   und bei jeder Verbindung geprüft (TOFU). Ändert er sich später, sind Uploads gesperrt, bis du ihn in den
   Einstellungen bewusst zurücksetzt.
2. **Aufnehmen:** Auslöser oder Lautstärketaste, beliebig oft hintereinander. Tippen auf die Vorschau fokussiert,
   mit zwei Fingern wird gezoomt, die Zoom-Stufen 0,5× / 1× / 2× / 5× sind direkt anwählbar.
3. **Prüfen:** Thumbnail antippen → Lightbox mit Pinch-to-Zoom, Doppeltipp-Zoom, Verschieben, Wischen,
   Herunterziehen zum Schließen. Löschen per ✕ im Raster oder Papierkorb in der Lightbox
   (5 s lang „Rückgängig“ möglich).
4. **Hochladen:** „N Fotos hochladen“.

### Was beim Upload passiert

1. Alle Fotos werden **nativ** optimiert: EXIF-Ausrichtung in die Pixel übernehmen, mit `inSampleSize`
   speicherschonend dekodieren, auf die maximale Kantenlänge verkleinern (nie vergrößern) und als WebP/JPEG
   mit der eingestellten Qualität speichern. EXIF-Metadaten werden auf Wunsch übernommen (Orientation = normal).
2. Im Startverzeichnis des Zugangs entsteht `_tmp_2026-11-20_211530/`.
3. Upload als `01.webp`, `02.webp`, … Jede Datei wird auf dem Server auf die exakte Größe geprüft.
4. Nach Prüfung des vollständigen Ordnerinhalts folgt ein **atomares Umbenennen** in `2026-11-20_211530`
   (bei Namenskollision `…_2`). Ein unvollständiger Produktordner taucht so nie unter regulärem Namen auf.
5. Erst danach wird die lokale Gruppe geleert. Auf dem Server wird nie etwas außerhalb des eigenen `_tmp_`-Ordners
   gelöscht oder überschrieben.

Bricht die Verbindung ab, bleiben alle Fotos lokal erhalten. „Erneut versuchen“ setzt im selben `_tmp_`-Ordner
fort und überspringt bereits verifizierte Dateien, auch nach einem Neustart der App. Während des Uploads bleibt
das Display an (`FLAG_KEEP_SCREEN_ON`).

---

## 6. Projektstruktur

```
src/                                  Vue-Oberfläche
  App.vue                             Layout, Lebenszyklus, Zurück-Taste
  components/AppHeader.vue            Kopfzeile (Einstellungen, Status)
  components/AppIcon.vue              SVG-Icons
  components/AppSnackbar.vue          Toasts inkl. „Rückgängig“
  components/HostKeyDialog.vue        Bestätigung des SFTP-Host-Key-Fingerprints (TOFU)
  components/CameraPanel.vue          Vorschau-Fenster, Fokus/Zoom, Auslöserleiste
  components/PhotoGrid.vue            4-spaltiges Raster mit Löschen
  components/PhotoLightbox.vue        Vollbild: Pinch/Doppeltipp/Pan/Swipe
  components/UploadBar.vue            Upload-Button, Fortschritt, Fehler
  components/SettingsView.vue         Server, Zugangsdaten, Host-Key, Web-Optimierung
  state/*.ts                          Zustand (Einstellungen, Gruppe, Kamera, Verbindung, Upload, Navigation, Toast, Shoot)
  version.ts                          Anzeige-Version (muss zu build.gradle passen, siehe BUILD.md)
  native/plugins.ts                   TypeScript-Schnittstellen der nativen Plugins

android/app/src/main/java/org/exponio/app/
  MainActivity.kt                     Plugin-Registrierung, Lautstärketasten
  camera/CameraPlugin.kt              CameraX-Vorschau hinter der transparenten WebView, Aufnahme
  transfer/TransferPlugin.kt          Brücke: Gruppe, Secrets, Host-Keys, Test, Upload
  transfer/PluginJson.kt              JSON-Hilfen für die Plugin-Schnittstelle
  remote/RemoteFs.kt                  gemeinsame Dateioperationen (SFTP/FTPS), Verbindungs- und Schreibtest
  ftps/FtpsConnector.kt               FTPS: TLS-Prüfung, Session-Reuse für Datenverbindungen, Fehlertexte
  store/PhotoStore.kt                 Gruppe in filesDir (überlebt Neustarts)
  image/ImageProcessor.kt             EXIF-Drehung, Skalierung, WebP/JPEG, Metadaten
  sftp/SftpConnector.kt               JSch-Verbindung, TOFU-Host-Key-Prüfung, Fehlertexte
  sftp/SftpRemoteFs.kt                SFTP-Variante von RemoteFs
  sftp/Uploader.kt                    _tmp_-Upload, Verifikation, atomares rename, Wiederaufnahme
  sftp/HostKeyStore.kt                gespeicherte Host-Keys pro host:port
  security/SecretStore.kt             AES-GCM mit Schlüssel im Android Keystore
```

**Datenschutz/Sicherheit:** Passwort, Private Key und Passphrase werden nur nativ verschlüsselt gespeichert
(Android Keystore) und nie zurück an die WebView gegeben. Cloud-Backup und Geräte-Transfer sind deaktiviert.
Es werden keine Storage-Berechtigungen verwendet, alle Fotos liegen im app-eigenen Speicher.

---

## 7. Fehlerbehebung

| Problem | Lösung |
|---|---|
| `SDK location not found` | `ANDROID_HOME` setzen oder `android/local.properties` mit `sdk.dir=…` anlegen |
| `Unsupported class file major version` / JDK-Fehler | Gradle mit JDK 21 ausführen (`java -version`, ggf. `JAVA_HOME`). Gradle findet kein JDK 21 → `openjdk-21-jdk` installieren |
| Pixel erscheint nicht in `adb devices` bzw. „no permissions“ | `sudo apt install android-sdk-platform-tools-common` (udev-Regeln), Kabel neu einstecken, USB-Debugging auf dem Handy bestätigen |
| Kamera-Vorschau im Emulator kurz schwarz | Normal, die virtuelle Szene braucht nach dem Start 1–2 s |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signatur hat sich geändert (anderer Keystore) → einmal deinstallieren |
| Schwarze Vorschau | Kamera-Berechtigung prüfen (App-Info → Berechtigungen) |
| „Host-Key hat sich geändert“ | Nur wenn der Server bewusst gewechselt wurde: Einstellungen → Host-Key zurücksetzen |
| „Das TLS-Zertifikat gilt nicht für …“ (FTPS) | Als Host genau den in der Meldung genannten Namen eintragen, nicht die IP |
| „Datenverbindung fehlgeschlagen“ (FTPS) | Firewall/Router muss ausgehende Verbindungen zu den Passiv-Ports des Servers erlauben |
| „SFTP abgelehnt bzw. sofort beendet“ | Server-seitig `ForceCommand internal-sftp` für den Benutzer setzen (Benutzer ohne Shell) |
| „Keine Schreibrechte“ | Der Zugang darf in seinem Startverzeichnis keine Ordner/Dateien anlegen → Rechte beim Hoster prüfen |

---

## 8. Hinweise zum Veröffentlichen

Das Repository enthält keine Zugangsdaten. Server-Zugänge und Schlüssel werden erst zur Laufzeit in der App
eingegeben und liegen verschlüsselt im Android Keystore des Geräts. Nicht ins Repository gehören
`android/keystore.properties`, `*.jks`/`*.keystore` und `android/local.properties` (stehen in `.gitignore`).
Nach einem frischen Klon: `npm install && npx cap sync android` (siehe [BUILD.md](BUILD.md)).
