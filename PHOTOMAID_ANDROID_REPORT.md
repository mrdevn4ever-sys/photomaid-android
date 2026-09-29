# PhotoMaid Android – Bericht (Zwischenstand, keine Fertigmeldung)

**Status: NICHT FERTIG im Sinne von Masterprompt §54.** Code, Tests, Lint und Release-Build sind grün; es gab
**keinen** Lauf auf Emulator oder Gerät (kein KVM im Container), keine Play-Console-Konfiguration und keinen
Werbeanbieter. JVM-Screenshots sind keine Geräteabnahme.

## 1. Was aus iOS portiert wurde

Alle Screens der iOS-App (Heute, Feed Foto/Video, Datenschutz/Onboarding, Papierkorb, Aufräumen, Ordnen,
Dateien/Downloads, Verbundene Quellen, Große Dateien, Speicherübersicht, Duplikate, Später, Regeln & Routinen,
Verlauf, Pro, Einstellungen, Hilfe), die komplette Domain (PhotoMaidCore) und alle deutschen Texte.
Details: `docs/PORTING_MATRIX.md`, `docs/PARITY_MATRIX.md`.

## 2. Architektur

- `:core` – reines Kotlin/JVM, 1:1 zu PhotoMaidCore: `FeedGestureMachine` (einzige Gesten-State-Machine),
  `FeedSession`, `DecisionLedger` (Mutex statt Actor, Commit erst nach Persistenz), Journal, Tageslimit, Später,
  Duplikate, Regeln, Capabilities.
- `:app` – Jetpack Compose; `AppModel` (DI, Lesemodelle, einzige Delete-Orchestrierung); Room als einzige Wahrheit
  (eine Transaktion je `LedgerChange`); MediaStore (`MediaLibrary`), SAF-Quellen, Media3, Play Billing, WorkManager;
  Navigation Compose mit einem Back-Stack je Tab; eigenes Glass-Design-System.

## 3. Android-native Anpassungen

MediaStore statt PhotoKit (Content-URI als stabile ID), `createTrashRequest` + Systembestätigung + Einzelprüfung statt
`deleteAssets`, SAF statt Bookmarks, Play Billing statt RevenueCat, WorkManager statt UNCalendarTrigger,
`NumberPicker`/Material-DatePicker statt SwiftUI-Wheel, „⋯“-Menüs statt Swipe-Aktionen in Listen,
Glass ohne Backdrop-Blur (Performance), MediaStore-Größen werden angezeigt (auf iOS unbekannt).

## 4. Echte 1:1-Parität

Gesten und Schwellen, Commit-Semantik, Undo, Später-Logik und -Gruppen, Tageslimit 15, Journal/Abgleich,
Duplikat-Pipeline, Regeln, Capabilities, Screenstruktur und Texte. Siehe Status „MATCH“ in `docs/PARITY_MATRIX.md`.

## 5. Unvermeidbare Unterschiede

„Nur in iCloud“ entfällt (MediaStore listet nur lokale Dateien); Download-Ordner selbst ist per SAF nicht freigebbar;
Käufe sind plattformgetrennt; Reduce Motion/Kontrast über Android-Äquivalente.

## 6. Testresultate

- `:core`: **82 Tests PASS** (alle 74 iOS-Testfunktionen + Zusätze).
- `:app`: **55 Tests PASS** (11 Gesten-UI-Tests, 10 Flow-Tests, 3 Persistenztests, 31 Screenshot-Tests).
- `lintDebug`/`lintVitalRelease`: 0 Fehler. `assembleRelease` mit R8: PASS.
- Durch Tests gefundene und behobene Fehler: Feed-Animationen ohne Frame-Clock (jede Entscheidung wäre auf dem
  Gerät zurückgerollt worden), zerquetschte Zeilentitel auf schmalen Geräten/bei 200 % Schrift, volle Breite auf
  Tablets, abgebrochene Fluganimation konnte eine gespeicherte Entscheidung als Fehler verbuchen.

## 7. Device-Matrix

JVM (Robolectric-Qualifier): 320 dp, 393 dp, Fold 360/673 dp, Tablet 600/834 dp, Light/Dark, Schrift 100/150/200 %.
Emulator/Gerät API 30/34/37: **BLOCKED**. Details: `docs/QA_MATRIX.md`.

## 8. Performance

Vorkehrungen: Zielgrößen-Dekodierung (max. 2800 px), Fenster vorher/aktuell/2 nächste, kein Disk-Cache, ein Player,
Glass ohne Echtzeit-Blur, Duplikate nur für gleich große Kandidaten (50 000-Einträge-Test).
Messungen (Frame-Drops, Speicher, Startzeit) auf Gerät: **NOT RUN**.

## 9. Bekannte Restprobleme

- Keine Geräteabnahme; echte Papierkorb-Anfrage, Video-Decoding, TalkBack-Durchgang ungeprüft.
- `INTERNET`-Berechtigung durch Billing-Telemetrie (dokumentiert, nicht entfernt).
- Lint-Warnungen `PluralsCandidate` (Zähltexte wie iOS ohne Plural-Formen).

## 10. Store Readiness

minSdk 30, targetSdk 37, adaptive Icon (Platzhalter), Splash Screen, R8, Backup-Regeln, keine Debug-Logs im Release.
Offen: Application-ID (Platzhalter), Upload-Signatur, Billing-Produkt, Datensicherheit, Datenschutzerklärung-URL,
Werbeanbieter. Vollständig: `docs/RELEASE_BLOCKERS.md`.

## 11. Git

Branch `claude/android-port` (gepusht, kein PR, kein Merge). `git log --oneline` zeigt thematische Commits:
Foundation → Core → Design → Persistenz → Infrastruktur → AppModel → Feed → Screens → Tests/Fixes → Doku.

## 12. Parity Matrix

`docs/PARITY_MATRIX.md`.
