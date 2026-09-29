# QA-Matrix (Android)

Status: PASS / FAIL / NOT RUN / BLOCKED. Stand: 2026-09-29, Branch `claude/android-port`.

## Automatisiert – `:core` (JVM, `./gradlew :core:test`)

| Suite | Tests | Status |
|---|---|---|
| GestureMachineTest (jede Richtung, Bedeutung, Diagonale, Totzonen, zu kurz, Flick, Gegenschwung, Hysterese, Achse, Suspendierung, kein Doppel-Commit, Systemabbruch, langsam/kurz) | 15 | PASS |
| DecisionLedgerTest (inkl. 5 Seeds × 300 generierte Folgen) | 14 | PASS |
| FeedSessionTest (inkl. 100 Entscheidungen) | 12 | PASS |
| LaterTest (Presets, Sommerzeit Zürich, Mitternacht, Zeitzonenwechsel, Queues, Neustart, Gruppen) | 8 | PASS |
| CleanupOperationTest (Limit 0/1/14/15/16, 40 vs. 7, Konkurrenz, verlorener Callback, DB-Fehler, Alias, Uhrwechsel) | 15 | PASS |
| DuplicateTest (inkl. 50 000 Metadaten ohne Full-Hash ohne Kandidatenfilter) | 7 | PASS |
| RuleTest | 4 | PASS |
| CapabilityTest | 6 | PASS |
| SerializationTest (Payload-Roundtrip) | 1 | PASS |
| **Summe** | **82** | **PASS** (alle 74 iOS-Testfunktionen portiert + Android-Zusätze) |

## Automatisiert – `:app` (Robolectric SDK 35, `./gradlew :app:testDebugUnitTest`)

| Suite | Tests | Status |
|---|---|---|
| FeedFlowTest (Compose-UI, echte Gesten: hoch, links, rechts + Pille, runter, Anschlag, Diagonale + kurzer Drag, abgebrochener Drag, schnelle Mehrfach-Swipes, schräg rechts oben: Öffnen ohne Entscheidung + Abbrechen ändert nichts, Bestätigen mit Kalenderdatum + gemeinsames Undo, Undo, Session-Ende) | 12 | PASS |
| AppFlowsTest (Onboarding → Tabs, Berechtigung nicht angefragt/verweigert/erteilt, Papierkorb bestätigen/abbrechen/Abwahl, Pro gesperrt/entsperrt, Einstellungen) | 10 | PASS |
| RoomLedgerPersistenceTest (Roundtrip nach Neustart, Journal → reconciliationRequired, tolerantes Dekodieren) | 3 | PASS |
| ScreenshotTest (Roborazzi, 31 Aufnahmen → `docs/screenshots/`) | 31 | PASS |
| **Summe** | **55** | **PASS** |

## Build / statische Prüfung

| Prüfung | Status |
|---|---|
| `assembleDebug` | PASS |
| `assembleRelease` (R8, Ressourcen-Shrinking; mit Debug-Key signiert) | PASS – 5,4 MB |
| `lintDebug` / `lintVitalRelease` | PASS – 0 Fehler, 44 Warnungen (v. a. `PluralsCandidate`: Zähltexte wie iOS ohne Plural) |
| Berechtigungsaudit Release-APK (`aapt2 dump permissions`) | PASS mit Befund, s. `PRIVACY_DATA_MAP.md` (INTERNET aus Billing-Telemetrie) |
| Exportierte Komponenten: nur `MainActivity` (Launcher), `SystemJobService` (`BIND_JOB_SERVICE`), `DiagnosticsReceiver` und `ProfileInstallReceiver` (`DUMP`) | PASS |
| Suche TODO/FIXME/Beispielpreise | PASS – keine Treffer |

## Visuell (JVM-Screenshots, keine Geräteabnahme)

Geprüft (Referenz: iOS-Mockups `docs/reference` und `docs/drafts` im iOS-Repo):
Heute, Feed Foto, Feed Video, Datenschutz, Papierkorb, Aufräumen, Ordnen, Dateien, Große Dateien, Duplikate,
Speicherübersicht, Später, Regeln, Verlauf, Pro (Free/aktiv), Einstellungen – Light und Dark;
kleines Telefon (320 dp), Fold außen (360 dp) / innen (673 dp), 8"-Tablet (600 dp), 11"-Tablet (834 dp),
Schriftgröße 150 % und 200 %. Status: **PASS (JVM)**.
Befunde daraus behoben: Titelumbruch bei 200 %, leere Detailspalte, volle Breite auf Tablets.

## Manuell / Gerät

| Prüfung | Status |
|---|---|
| Emulator/Gerät API 30 / 34 / 37 | BLOCKED – kein KVM im Container |
| Echte MediaStore-Papierkorb-Anfrage (bestätigen/abbrechen/Prozessende) | NOT RUN |
| Video-Decoding, 4K, HEIC, 10 000 Fotos, Frame-Drops (Perfetto/Macrobenchmark) | NOT RUN |
| TalkBack- und Schaltersteuerungs-Durchgang | NOT RUN (Custom Actions und Buttons per UI-Test vorhanden) |
| Predictive Back, Rotation, Process Death auf Gerät | NOT RUN |
| Play-Billing-Kauf/Restore/Erstattung (Lizenztester) | BLOCKED – kein Produkt in der Play Console |
| Rewarded | BLOCKED – kein Anbieter (wie iOS) |
