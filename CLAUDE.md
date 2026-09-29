# PhotoMaid Android: verbindliche Regeln

Native Android-App (Kotlin, Jetpack Compose). 1:1-Port der iOS-App (`mrdevn4ever-sys/photomaid`, read-only).
Kein Flutter, React Native, WebView-Oberfläche. Bei Widerspruch gewinnt das Verhalten der iOS-App
(außer Plattformgrenze, Sicherheit, Store-Policy, eindeutiger Bug – dann in `docs/PORTING_MATRIX.md` dokumentieren).

## Gesten (FEST, tatsächliche Fingerbewegung)

| Finger | Bedeutung | Danach |
|---|---|---|
| hoch | **Behalten** | nächstes Medium |
| links | **Papierkorb** (PhotoMaid-eigen, nichts gelöscht) | nächstes Medium |
| rechts (gerade oder schräg nach oben/unten, ≤ 45°) | **Später** mit Standardtermin (Vorgabe 2 Tage) | nächstes Medium + Pille „In 2 Tagen wieder · Ändern“ (eigener Termin über „Ändern“) |
| runter | **Vorheriges Medium** (keine Entscheidung) | – |

- Keine Diagonalgeste (Produktentscheidung 2026-09-29, Abweichung von iOS): zwischen 45° und „hoch“ bzw. „runter“ liegen Totzonen.
- Tipp aufs Video = Pause/Play (keine Entscheidung). Keine Richtungs-Kapsel beim Ziehen.
- Eine einzige Gesture-/Decision-State-Machine: `core/.../feed/FeedGestureMachine.kt`.
- Nichts tun = keine Entscheidung. Eine Geste = maximal ein Schritt. Commit trägt Item-ID, Revision, Command-ID, Aktion.
- Keine Aktions-Buttons im Feed (nur bei TalkBack/Schaltersteuerung).

## Verbote

- Keine automatische Löschung. Kein Backend, Konto, Upload, keine KI/ML.
- Sammlungen (statt „Regeln & Routinen“) sind reine Filter ohne Auslöser/Hintergrundarbeit; fertige für alle, eigene = Pro.
- Keine erfundenen Zahlen; unbekannte Größe ≠ 0. Keine simulierten Scans/Berechtigungen/Käufe.
- Kein `MANAGE_EXTERNAL_STORAGE`, keine Accessibility-/Shell-Tricks zum Löschen.
- Synthetische Daten nur in Tests, Previews und im Build-Typ `qa`.
- Kein automatischer Merge/Release.

## Architektur

- `:core` (Kotlin/JVM, auf Linux testbar): Domain-Port von PhotoMaidCore.
- `:app` (Android): Compose-Features, Design System (`design/`), Infrastruktur (MediaStore, SAF, Media3,
  Play Billing, WorkManager), Room-Persistenz (eine Transaktion je `LedgerChange`).
- UI-Texte ausschließlich in `res/values/strings.xml` (Deutsch, aus `Localizable.xcstrings` übernommen).

## Befehle

```sh
./gradlew :core:test                      # Domain-Kern
./gradlew :app:testDebugUnitTest          # Robolectric (Room, Compose-UI-Tests)
./gradlew :app:recordRoborazziDebug       # JVM-Screenshots nach app/build/outputs/roborazzi
./gradlew :app:assembleRelease :app:lintDebug
```

Prüfstatus immer PASS / FAIL / NOT RUN / BLOCKED. JVM-Screenshots sind keine Geräteabnahme.
