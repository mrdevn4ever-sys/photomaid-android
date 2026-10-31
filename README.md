# GaleriQ (Android)

Native Android-Version von GaleriQ (Kotlin, Jetpack Compose): lokaler Ordnungs- und Aufräumassistent
für Fotos, Videos, Screenshots und freigegebene Ordner. 1:1-Port der iOS-App.

**Hoch behalten · Links Papierkorb · Rechts später · Runter zurück.**

## Schnellstart am PC (Windows, macOS, Linux)

### Variante A – Android Studio (empfohlen)

1. [Android Studio](https://developer.android.com/studio) in der **aktuellen stabilen Version** installieren
   (das Projekt nutzt AGP 9.4 / Gradle 9.7 / Kotlin 2.4; ältere Studio-Versionen können das Projekt nicht öffnen).
2. Repository klonen:
   ```sh
   git clone https://github.com/mrdevn4ever-sys/photomaid-android.git
   ```
3. In Android Studio **File › Open** → Ordner `photomaid-android` wählen. Beim ersten Öffnen lädt Studio
   Gradle, Abhängigkeiten und das **Android-SDK 37** (bei Nachfrage „Install“ bestätigen).
4. Oben die Run-Konfiguration **app** wählen, ein Gerät (USB-Debugging) oder einen Emulator auswählen → **▶ Run**.

### Variante B – Kommandozeile

Voraussetzung: JDK 21 und ein Android-SDK (z. B. aus Android Studio). Pfad zum SDK in `local.properties`
(`sdk.dir=C\:\\Users\\<du>\\AppData\\Local\\Android\\Sdk` bzw. `sdk.dir=/Users/<du>/Library/Android/sdk`)
oder über die Umgebungsvariable `ANDROID_HOME`.

```sh
# Windows: gradlew.bat statt ./gradlew
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # direkt auf ein angeschlossenes Gerät installieren
./gradlew :core:test :app:testDebugUnitTest   # Tests
```

### Variante C – APK selbst bauen und aufs Handy kopieren

`gradlew.bat :app:assembleRelease -Pphotomaid.signWithDebug=true` (Windows) bzw. `./gradlew …` erzeugt
`app/build/outputs/apk/release/app-release.apk` (klein, mit Debug-Schlüssel signiert, nur zum Testen).
Aufs Handy kopieren, „Installation aus unbekannten Quellen“ erlauben, installieren.

GitHub Actions läuft **nicht** automatisch; der Workflow `CI` kann bei Bedarf manuell gestartet werden.

## Hinweise

- Mindestens Android 11 (API 30).
- Käufe sind ohne Play-Console-Produkt deaktiviert („noch nicht verfügbar“). Produkt-ID beim Bauen setzen:
  `./gradlew assembleRelease -Pphotomaid.playProProductId=<id>`.
- QA-Build mit synthetischen Testbildern (nur Emulator/Testgerät): `./gradlew :app:installQa`, dann
  `adb shell am start -n app.photomaid.android.qa/app.photomaid.MainActivity --ez qa-fixtures true --ez qa-seed-media true`.

## Dokumentation

- Regeln: [`CLAUDE.md`](CLAUDE.md)
- Audit: [`docs/PORTING_MATRIX.md`](docs/PORTING_MATRIX.md) · Parität: [`docs/PARITY_MATRIX.md`](docs/PARITY_MATRIX.md)
- Status/QA/Blocker: [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md), [`docs/QA_MATRIX.md`](docs/QA_MATRIX.md), [`docs/RELEASE_BLOCKERS.md`](docs/RELEASE_BLOCKERS.md)
- Bericht: [`PHOTOMAID_ANDROID_REPORT.md`](PHOTOMAID_ANDROID_REPORT.md)
