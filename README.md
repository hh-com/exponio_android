# Exponio (Android)

Minimale Sideloading-App für Produktfotos: **Aufnehmen → Prüfen → Hochladen**.
Fotos werden in Gruppen mit der In-App-Kamera aufgenommen, im 4er-Raster und in einer Lightbox geprüft,
nativ web-optimiert (WebP/JPEG) und über **HTTPS direkt in die Exponio-Website** hochgeladen. Die Website legt
jede Gruppe als neuen Ordner mit Zeitstempel ab und importiert sie.

- **Stack:** Vue 3 (`<script setup>`, TypeScript), Vite, Tailwind CSS 4, Capacitor 8
- **Android:** compileSdk/targetSdk 36 (Android 16), minSdk 35 (Android 15) – getestet für Pixel 10
- **Nativ (Kotlin):** CameraX, Bildverarbeitung (BitmapFactory/ExifInterface), Upload per `HttpsURLConnection`
  an die Schnittstelle der Website (keine weitere Netzwerk-Bibliothek)

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

## 4. Website verbinden

Die App lädt direkt in die **Exponio-Website** hoch (`../exponio_website`). Sie kennt nur deren Adresse und ein
Token; in welchen Ordner die Fotos auf dem Server kommen, entscheidet die Website (`SCAN_DIR`, Standard
`app_uploads/` neben dem Projektordner). FTP-/SSH-Zugänge braucht es dafür nicht.

1. Im Backend der Website **System → App-Zugänge → Neuer App-Zugang** öffnen, das Gerät benennen (z. B. „Pixel 10“)
   und „Token erzeugen“ tippen. Die Seite zeigt danach **Website-Adresse** und **Token** zum Kopieren, das Token nur
   dieses eine Mal. Am einfachsten öffnest du das Backend dafür direkt auf dem Handy.
2. In der App unter **Einstellungen → Website** beides einfügen und **„Verbindung testen“** tippen.
3. Ein verlorenes Gerät sperrst du, indem du seinen App-Zugang im Backend löschst.

Technik: nur HTTPS mit gültigem Zertifikat (System-CAs, wie im Browser), `Authorization: Bearer <Token>`.
Weiterleitungen folgt die App nicht, damit das Token nie an eine andere Adresse geht. Leitet die Adresse weiter
(z. B. von `www.` auf ohne), nennt der Verbindungstest das Ziel. Das Token liegt AES-GCM-verschlüsselt mit einem
Schlüssel aus dem Android-Keystore auf dem Gerät; die WebView kann es setzen, aber nie zurücklesen. Auf der Website
ist nur sein SHA-256-Hash gespeichert. Die Schnittstelle selbst beschreibt `exponio_website/README.md`
(Abschnitt „App-Zugang“).

---

## 5. Bedienung

1. **Einstellungen** (Regler-Symbol oben rechts) → **Website**: Adresse und Token eintragen (siehe Abschnitt 4)
   und **„Verbindung testen“** tippen. Der Test prüft Zertifikat und Token, und die Website legt in ihrem
   Upload-Ordner kurz einen Testordner an und entfernt ihn wieder (Schreibrechte).
2. **Aufnehmen:** Auslöser oder Lautstärketaste, beliebig oft hintereinander. Tippen auf die Vorschau fokussiert,
   mit zwei Fingern wird gezoomt, die Zoom-Stufen 0,5× / 1× / 2× / 5× sind direkt anwählbar.
3. **Prüfen:** Thumbnail antippen → Lightbox mit Pinch-to-Zoom, Doppeltipp-Zoom, Verschieben, Wischen,
   Herunterziehen zum Schließen. Löschen per ✕ im Raster oder Papierkorb in der Lightbox
   (5 s lang „Rückgängig“ möglich). **Drehen** um 90° im Uhrzeigersinn: das zuletzt aufgenommene Foto über das
   Thumbnail rechts neben dem Auslöser, jedes andere über den Drehen-Knopf in der Lightbox. Das Drehen ist
   verlustfrei (nur EXIF-Ausrichtung des Originals), das Foto kommt gedreht auf dem Server an.
4. **Notiz (optional):** Ins Feld über dem Upload-Knopf tippen oder über das Mikrofon der Tastatur diktieren,
   z. B. Material, Maße, Mängel oder „Bild 3 zeigt die Rückseite“. Die Notiz wird sofort auf dem Gerät gespeichert
   und geht beim Upload als `info.txt` mit.
5. **Hochladen:** „N Fotos hochladen“.

### Was beim Upload passiert

1. Alle Fotos werden **nativ** optimiert: EXIF-Ausrichtung in die Pixel übernehmen, mit `inSampleSize`
   speicherschonend dekodieren, auf die maximale Kantenlänge verkleinern (nie vergrößern) und als WebP/JPEG
   mit der eingestellten Qualität speichern. EXIF-Metadaten werden auf Wunsch übernommen (Orientation = normal).
2. Die App fragt die Website, ob Token und Upload-Ordner in Ordnung sind, und was von diesem Upload schon auf dem
   Server liegt (nach einem Abbruch).
3. Upload als `01.webp`, `02.webp`, …, jede Datei mit SHA-256-Prüfsumme. Die Website prüft Größe, Prüfsumme und
   Bildformat und legt sie in `_tmp_2026-11-20_211530/` im Upload-Ordner ab. Gibt es eine Notiz, folgt sie als
   `info.txt` (UTF-8, Zeilenenden `\n`). Ohne Notiz fehlt die Datei.
4. Zum Abschluss schickt die App die vollständige Dateiliste. Die Website prüft den Ordnerinhalt dagegen und
   benennt ihn **atomar** in `2026-11-20_211530` um (bei Namenskollision `…_2`). Ein unvollständiger Ordner taucht
   so nie unter regulärem Namen auf, und der Import der Website (`app:scan`) sieht ihn erst danach.
5. Erst danach wird die lokale Gruppe (Fotos und Notiz) geleert. Auf dem Server wird nie etwas außerhalb des
   eigenen `_tmp_`-Ordners gelöscht oder überschrieben.

Bricht die Verbindung ab, bleiben alle Fotos lokal erhalten. „Erneut versuchen“ setzt mit demselben Zeitstempel
fort und sendet nur, was auf dem Server fehlt oder abweicht, auch nach einem Neustart der App. Ging nur die
Bestätigung des Abschlusses verloren, erkennt die Website den fertigen Ordner wieder, statt einen zweiten
anzulegen. Während des Uploads bleibt das Display an (`FLAG_KEEP_SCREEN_ON`).

> **Update von einer Version mit SFTP/FTPS:** Gespeicherte Server-Zugangsdaten (Passwort, Private Key, Host-Keys)
> löscht die App beim ersten Start. Ein unterbrochener SFTP/FTPS-Upload wird nicht fortgesetzt; die Fotos liegen
> noch auf dem Gerät und gehen beim nächsten Upload an die Website. Auf dem alten Server bleibt höchstens ein
> `_tmp_…`-Ordner zurück, den der Import ignoriert.
