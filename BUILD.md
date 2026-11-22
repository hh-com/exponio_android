# BUILD.md – Code ändern, bauen, aufs Handy bringen

Diese Anleitung beschreibt den kompletten Ablauf nach einer Änderung am Code. Allgemeine Infos zur App
stehen in der [README.md](README.md).

## Inhalt

1. [Überblick: Was gehört wohin?](#1-überblick-was-gehört-wohin)
2. [Einmalige Einrichtung](#2-einmalige-einrichtung)
3. [Standard-Ablauf nach einer Code-Änderung](#3-standard-ablauf-nach-einer-code-änderung)
4. [Welche Änderung braucht welchen Schritt?](#4-welche-änderung-braucht-welchen-schritt)
5. [Neue Version aufs Handy](#5-neue-version-aufs-handy)
6. [Version erhöhen](#6-version-erhöhen)
7. [Release-APK mit eigenem Keystore](#7-release-apk-mit-eigenem-keystore)
8. [Schneller entwickeln (Dev-Tipps)](#8-schneller-entwickeln-dev-tipps)
9. [Frischer Klon / neuer Rechner](#9-frischer-klon--neuer-rechner)
10. [Abhängigkeiten aktualisieren](#10-abhängigkeiten-aktualisieren)
11. [Fehlersuche](#11-fehlersuche)
12. [Checkliste vor dem Veröffentlichen auf Git](#12-checkliste-vor-dem-veröffentlichen-auf-git)

---

## 1. Überblick: Was gehört wohin?

Die App besteht aus zwei Teilen, die zusammen in **eine APK** gepackt werden:

| Teil | Ort | Sprache | Wird zu |
|---|---|---|---|
| Oberfläche (UI) | `src/` | Vue 3 + TypeScript + Tailwind | `dist/` (Vite-Build) |
| Native Funktionen (Kamera, Bildverarbeitung, FTPS/SFTP, Verschlüsselung) | `android/app/src/main/java/org/exponio/app/` | Kotlin | Teil der APK |
| Android-Konfiguration | `android/app/build.gradle`, `AndroidManifest.xml`, `res/` | Gradle/XML | Teil der APK |
| Capacitor-Konfiguration | `capacitor.config.ts` | TypeScript | `android/app/src/main/assets/capacitor.config.json` (generiert) |

Ablauf eines Builds:

```
src/ (Vue)  ──npm run build──►  dist/  ──npx cap sync android──►  android/app/src/main/assets/public/
                                                                          │
Kotlin-Code + Gradle  ──────────────── ./gradlew assembleDebug ───────────┴──►  app-debug.apk  ──adb install──►  Handy
```

**Wichtig:** Das Android-Projekt enthält eine *Kopie* des Web-Builds. Wer nur `src/` ändert und
`npx cap sync android` vergisst, installiert die alte Oberfläche. `npm run apk:debug` erledigt alle Schritte in
der richtigen Reihenfolge.

Generierte Dateien (nicht von Hand bearbeiten, stehen in `.gitignore`):
`dist/`, `android/app/src/main/assets/public/`, `android/app/src/main/assets/capacitor*.json`,
`android/app/src/main/res/xml/config.xml`, `android/capacitor-cordova-android-plugins/`, `android/.gradle/`,
`android/app/build/`, `node_modules/`.

---

## 2. Einmalige Einrichtung

Ausführliche Anleitung: [README.md, Abschnitt 1](README.md#1-voraussetzungen-einmalig). Kurzfassung:

| Werkzeug | Anforderung | Prüfen mit |
|---|---|---|
| Node.js | ≥ 22 | `node -v` |
| JDK | **21** (nicht 25!) | `java -version` |
| Android SDK | Platform 36, Build-Tools 36.0.0, Platform-Tools | `sdkmanager --list_installed` |
| `ANDROID_HOME` | z. B. `~/Android/Sdk` | `echo $ANDROID_HOME` |
| adb | aus Platform-Tools | `adb version` |

Das Handy braucht **USB-Debugging** (Einstellungen → Entwickleroptionen). `adb devices` muss das Gerät als
`device` (nicht `unauthorized`) listen.

Die Datei `android/local.properties` (`sdk.dir=…`) wird von Android Studio angelegt oder muss selbst erstellt
werden, falls `ANDROID_HOME` nicht gesetzt ist. Sie ist rechnerspezifisch und nicht im Repository.

---

## 3. Standard-Ablauf nach einer Code-Änderung

Im Projektverzeichnis (`exponio_android/`):

```bash
# 1. (nur falls package.json / Abhängigkeiten geändert wurden)
npm install

# 2. Alles bauen: Typecheck → Vite-Build → Sync ins Android-Projekt → Gradle-Debug-APK
npm run apk:debug

# 3. Handy per USB anschließen und installieren
npm run install:debug
```

Das war's. Die App wird **über** die alte Version installiert, Einstellungen, Zugangsdaten und lokale Fotos
bleiben erhalten (siehe [Abschnitt 5](#5-neue-version-aufs-handy)).

Was `npm run apk:debug` im Einzelnen tut (`package.json`):

| Schritt | Befehl | Bedeutung |
|---|---|---|
| a | `vue-tsc --noEmit` | TypeScript-/Vue-Typprüfung. Schlägt sie fehl, bricht der Build ab. |
| b | `vite build` | Erzeugt `dist/` |
| c | `npx cap sync android` | Kopiert `dist/` und Plugin-Konfiguration ins Android-Projekt |
| d | `cd android && ./gradlew assembleDebug` | Kompiliert Kotlin, packt die APK |

Ergebnis: `android/app/build/outputs/apk/debug/app-debug.apk`

---

## 4. Welche Änderung braucht welchen Schritt?

| Du hast geändert … | Mindestens nötig |
|---|---|
| Nur `src/**` (Vue/TS/CSS) | `npm run apk:debug` (oder `npm run sync`, dann in Android Studio ▶ Run) |
| Nur Kotlin unter `android/app/src/main/java/**` | `cd android && ./gradlew assembleDebug`, dann `npm run install:debug` (Web-Build muss nicht neu) |
| `AndroidManifest.xml`, `res/**`, `android/app/build.gradle` | `cd android && ./gradlew assembleDebug` + installieren |
| `capacitor.config.ts` | `npx cap sync android` + Gradle-Build (die Config wird ins Android-Projekt kopiert!) |
| `package.json` (neue/aktualisierte npm-Pakete) | `npm install` → `npm run apk:debug` |
| Neues Capacitor-Plugin | `npm install <plugin>` → `npx cap sync android` (trägt es in `capacitor.settings.gradle`/`capacitor.build.gradle` ein) → Gradle-Build |
| Neue Kotlin-Abhängigkeit | in `android/app/build.gradle` unter `dependencies` ergänzen → Gradle-Build |
| Neue Android-Berechtigung | `AndroidManifest.xml` ergänzen (Datenschutz-Hinweis in README anpassen) |
| Neues natives Plugin (eigene Kotlin-Klasse) | Klasse mit `@CapacitorPlugin` anlegen, in `MainActivity.kt` **vor** `super.onCreate()` per `registerPlugin(...)` eintragen, TypeScript-Schnittstelle in `src/native/plugins.ts` ergänzen |
| Nativer Plugin-Aufruf geändert (Signatur) | Kotlin **und** `src/native/plugins.ts` zusammen ändern, sonst Laufzeitfehler |

Im Zweifel: `npm run apk:debug` ist immer sicher.

---

## 5. Neue Version aufs Handy

### 5.1 Per USB (empfohlen)

```bash
adb devices                 # Handy muss als "device" erscheinen
npm run install:debug       # = adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

`-r` bedeutet „ersetzen unter Beibehaltung der Daten“. Danach App neu starten (falls sie lief, wird sie beendet).

Mehrere Geräte (z. B. Emulator + Handy)? Ziel angeben:

```bash
adb -s <seriennummer> install -r android/app/build/outputs/apk/debug/app-debug.apk
```

App direkt starten und Logs ansehen:

```bash
adb shell am start -n org.exponio.app/.MainActivity
adb logcat | grep -E 'Capacitor|CXCP|exponio|Pt[A-Z]'
```

### 5.2 Ohne USB (per Datei)

1. APK `android/app/build/outputs/apk/debug/app-debug.apk` aufs Handy kopieren (Cloud, Mail, `adb push`, …).
2. Auf dem Handy in der Dateien-App antippen → „Installieren“. Einmalig muss „Unbekannte Apps installieren“
   für die verwendete App erlaubt werden.

### 5.3 Warum bleiben die Daten erhalten? (wichtig!)

Android akzeptiert ein Update nur, wenn die neue APK **mit demselben Schlüssel signiert** ist wie die
installierte. Debug-APKs werden mit `~/.android/debug.keystore` signiert, der beim ersten Build automatisch
entsteht.

- **Sichere `~/.android/debug.keystore`** (z. B. in dein Backup), wenn du den Rechner wechselst oder neu
  aufsetzt, und kopiere sie auf den neuen Rechner, bevor du dort das erste Mal baust.
- Hat sich der Schlüssel geändert, meldet adb `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Dann hilft nur
  Deinstallieren (`adb uninstall org.exponio.app`). **Dabei gehen Zugangsdaten, Einstellungen und noch nicht
  hochgeladene Fotos verloren.** Vorher also alle Fotos hochladen.
- Die Zugangsdaten werden an den Android-Keystore *des Geräts* gebunden und sind nicht in Backups. Nach einer
  Neuinstallation müssen sie neu eingegeben werden.
- Wechsle nicht zwischen Debug- und Release-Signatur (siehe Abschnitt 7).

---

## 6. Version erhöhen

Für ein „sauberes“ Update (und damit man die installierte Version erkennt) die Versionsnummer an
**drei Stellen** synchron halten:

| Datei | Feld | Zweck |
|---|---|---|
| `android/app/build.gradle` | `versionCode` (ganze Zahl, **jedes Mal +1**) und `versionName` (z. B. `"1.1.0"`) | Was Android als App-Version kennt. Ein *niedrigerer* `versionCode` lässt sich nicht über eine neuere installieren. |
| `src/version.ts` | `APP_VERSION` | Anzeige in der App |
| `package.json` | `"version"` | Konsistenz im Projekt |

Danach normal bauen (`npm run apk:debug`) und installieren. Bei Debug-Builds ist die Erhöhung technisch nicht
zwingend (gleicher `versionCode` wird mit `-r` ersetzt), bei Release-Builds sollte sie immer erfolgen.

Anschließend die Version im Git-Tag festhalten (optional):

```bash
git tag v1.1.0 && git push --tags
```

---

## 7. Release-APK mit eigenem Keystore

Nur nötig, wenn du unabhängig vom Debug-Keystore signieren willst (z. B. für mehrere Entwicklungsrechner).
Eine Release-APK ist optimiert (kein Debug-Modus, WebView nicht per `chrome://inspect` debugbar).

**Entscheide dich für Debug oder Release und bleibe dabei** – ein Wechsel erfordert eine Neuinstallation und
löscht die App-Daten.

### 7.1 Keystore einmalig erzeugen

```bash
keytool -genkeypair -v -keystore android/exponio-release.jks \
  -alias exponio -keyalg RSA -keysize 4096 -validity 36500
```

**Sichere die `.jks`-Datei und beide Passwörter an einem Ort außerhalb des Repositorys** (Passwortmanager,
Backup). Geht der Keystore verloren, lassen sich keine Updates mehr einspielen.

### 7.2 Signaturdaten eintragen

`android/keystore.properties` anlegen (steht in `.gitignore`, **niemals committen**):

```properties
storeFile=exponio-release.jks
storePassword=DEIN_STORE_PASSWORT
keyAlias=exponio
keyPassword=DEIN_KEY_PASSWORT
```

`storeFile` ist relativ zum Ordner `android/`.

### 7.3 Bauen und installieren

```bash
npm run apk:release
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

Ohne `keystore.properties` wird die Release-APK **unsigniert** erzeugt und lässt sich nicht installieren.

Optional prüfen, womit eine APK signiert ist:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
  android/app/build/outputs/apk/release/app-release.apk
```

---

## 8. Schneller entwickeln (Dev-Tipps)

**Nur Oberfläche testen im Browser** (Kamera/Upload sind dort nicht verfügbar, nützlich für Layout):

```bash
npm run dev          # Vite-Dev-Server, http://localhost:5173
```

**Typprüfung allein:**

```bash
npm run typecheck
```

**Android Studio:** Ordner `android/` öffnen und ▶ Run. Vor dem Run nach Web-Änderungen einmal
`npm run sync` im Projektstamm ausführen. Das JDK 21 wird durch `android/gradle/gradle-daemon-jvm.properties`
automatisch erzwungen.

**WebView debuggen (nur Debug-Build):** Handy per USB, in Chrome `chrome://inspect` öffnen und die App
auswählen (Konsole, Elemente, Netzwerk). Das Capacitor-Logging ist bewusst deaktiviert
(`loggingBehavior: 'none'`), damit keine Passwörter in logcat landen.

**Emulator statt Handy:**

```bash
emulator -list-avds
emulator -avd <name> &            # Android ≥ 15 nötig (minSdk 35)
adb wait-for-device && npm run install:debug
```

**Sauber neu bauen**, wenn etwas „komisch“ ist:

```bash
cd android && ./gradlew clean && cd ..
npm run apk:debug
```

Hilft das nicht: `rm -rf dist android/app/build android/.gradle` und erneut bauen.

---

## 9. Frischer Klon / neuer Rechner

Nach `git clone` fehlen die generierten Dateien. Reihenfolge:

```bash
git clone <repo-url> exponio_android && cd exponio_android

# 1. Werkzeuge installieren (Abschnitt 2), ANDROID_HOME setzen
#    oder android/local.properties mit  sdk.dir=/pfad/zum/Android/Sdk  anlegen

# 2. Abhängigkeiten + Web-Build + Sync (erzeugt assets/public, capacitor.*.json, cordova-Modul)
npm install
npm run sync

# 3. APK bauen und installieren
cd android && ./gradlew assembleDebug && cd ..
npm run install:debug
```

**Ganz wichtig bei Rechnerwechsel:** `~/.android/debug.keystore` vom alten Rechner mitnehmen (Abschnitt 5.3),
sonst lässt sich die App auf dem Handy nicht aktualisieren, ohne die Daten zu verlieren.

---

## 10. Abhängigkeiten aktualisieren

Versionen sind bewusst **exakt gepinnt** (keine `^`/`~`), damit Builds reproduzierbar bleiben.

| Was | Wo | Hinweis |
|---|---|---|
| Capacitor (`@capacitor/*`) | `package.json` | Alle Capacitor-Pakete auf dieselbe Hauptversion bringen, danach `npm install && npx cap sync android` |
| Vue, Vite, Tailwind, TypeScript | `package.json` | danach `npm run build` (Typecheck!) |
| CameraX, JSch, Bouncy Castle, commons-net, ExifInterface | `android/app/build.gradle` | Bouncy Castle (`bcprov`/`bctls`) immer **gleiche Version** |
| Android Gradle Plugin / Kotlin | `android/build.gradle` | AGP 8.13 braucht Gradle 8.14 und JDK 17–21 |
| Gradle | `android/gradle/wrapper/gradle-wrapper.properties` | `cd android && ./gradlew wrapper --gradle-version <x>` |
| compileSdk / targetSdk / minSdk | `android/variables.gradle` | Beim Anheben passende SDK-Platform installieren (`sdkmanager "platforms;android-NN"`) |

Prüfen auf bekannte Schwachstellen:

```bash
npm audit
```

Nach jedem Update: bauen, auf dem Handy testen (Foto aufnehmen, Verbindung testen, Upload), erst dann committen.

---

## 11. Fehlersuche

| Problem | Lösung |
|---|---|
| Änderung an der Oberfläche erscheint nicht auf dem Handy | `npx cap sync android` vergessen. Besser immer `npm run apk:debug` verwenden. App auf dem Handy ggf. komplett schließen und neu öffnen. |
| `vue-tsc` meldet Fehler und der Build bricht ab | Fehler beheben, Typprüfung ist Absicht (`npm run typecheck`). |
| `SDK location not found` | `ANDROID_HOME` setzen oder `android/local.properties` anlegen |
| `Unsupported class file major version` / Gradle startet nicht | Es läuft JDK 25 statt 21. `java -version`, `sudo apt install openjdk-21-jdk`, ggf. `JAVA_HOME` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Andere Signatur als die installierte App (anderer Debug-Keystore oder Debug↔Release). Alten Keystore wiederherstellen oder deinstallieren (Datenverlust!) |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | `versionCode` ist kleiner als der installierte. Erhöhen oder `adb install -r -d …` |
| `INSTALL_FAILED_OLDER_SDK` | Gerät/Emulator hat Android < 15 (minSdk 35) |
| `adb devices` leer / `unauthorized` / `no permissions` | Kabel prüfen, USB-Debugging an, Dialog am Handy bestätigen, `sudo apt install android-sdk-platform-tools-common`, `adb kill-server && adb start-server` |
| `Duplicate class` / `2 files found with path META-INF/…` | Neue Bibliothek bringt doppelte Metadaten mit: in `android/app/build.gradle` unter `packaging.resources.excludes` ergänzen |
| Neues Plugin „not implemented on android“ | `npx cap sync android` nachholen und neu bauen. Eigene Plugins in `MainActivity.kt` registrieren |
| Gradle hängt / sehr langsam beim ersten Mal | Normal: lädt Gradle und Abhängigkeiten (mehrere hundert MB). Danach schnell. |

---

## 12. Checkliste vor dem Veröffentlichen auf Git

```bash
# Sicherstellen, dass nichts Geheimes oder Generiertes getrackt wird
git status --short
git ls-files | grep -Ei 'keystore|\.jks|\.pem|local\.properties|\.env|\.apk|node_modules|/build/|assets/public' \
  && echo "ACHTUNG: sensible/generierte Dateien im Index" || echo "OK"

# Nach Passwörtern/Schlüsseln im Code suchen
grep -rnEi "BEGIN (RSA|OPENSSH|EC) PRIVATE|password *= *['\"][^'\"]+['\"]" src android/app/src/main/java
```

- [ ] `android/keystore.properties`, `*.jks`, `android/local.properties` sind **nicht** im Repository
- [ ] Keine echten Hostnamen/Zugangsdaten in README, Code oder Beispielen
- [ ] `versionCode`/`versionName`/`APP_VERSION`/`package.json` stimmen überein
- [ ] `npm run build` und `cd android && ./gradlew assembleDebug` laufen fehlerfrei durch
- [ ] Eine Lizenz festlegen (`LICENSE`), falls das Repository öffentlich wird

Die Signatur-Dateien (`~/.android/debug.keystore`, `android/exponio-release.jks`) gehören **nie** in Git,
sondern in ein privates Backup.
