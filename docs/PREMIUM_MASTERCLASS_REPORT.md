# PhotoMaid Android – Premium-Pass (Bericht)

- Stand: 2026-09-29
- Branch: `claude/premium-pass`
- Basis: `claude/feed-collections-rework` @ `94da267`, darunter `claude/device-test-fixes` (Gerätetest 2: 9/0/0)

**Status:** Code, Tests, Lint und Release-Build sind grün. **Ein Gerätetest dieses Stands fehlt noch (NOT RUN).**
„Premium fertig“ gilt erst nach realer QA auf dem Realme.

## 0. Grundlage und Abweichungen vom Prompt

Der Nutzer hat entschieden, auf dem Umbau-Branch ohne Diagonalgeste aufzubauen. Dadurch sind folgende Punkte des Prompts überholt und nicht umgesetzt:

- ↗ als Datumsauswahl: §3, §12 ↗, §14 „CUSTOM DATE READY“, §18 ↗, §39 ↗
  - Stattdessen gilt: rechts bis 45° = Später, der eigene Termin kommt über „Ändern“ in der Pille.
  - Die TalkBack-Aktion „Später, Datum wählen“ bleibt; sie öffnet erst die Auswahl, erst Bestätigen entscheidet.
- Vier Tabs mit „Ordnen“ (§44): Es bleibt bei drei Tabs, Heute · Aufräumen · Verlauf.
- „Eigene Prüfroutinen“ (§50, §57): Der Pro-Vorteil heißt „Eigene Sammlungen“. Sammlungen haben keine Auslöser und keine Hintergrundarbeit.

Das Audit mit P0/P1/P2 steht in `docs/PREMIUM_AUDIT.md`.

## 1. Geänderte Bereiche

| Bereich | Vorher | Nachher | Datei(en) |
|---|---|---|---|
| **Video-Fehler (P0)** | Wiedergabefehler blieben stumm (schwarzes Bild + Play) | `onPlayerError`, ruhiger Zustand „Dieses Video konnte nicht wiedergegeben werden.“ mit **Erneut versuchen** / **Weiter ohne Entscheidung** | `playback/FeedPlayer.kt`, `feature/review/MediaPage.kt` |
| **Querformat-Insets (P0)** | Inhalt unter der 3-Tasten-Leiste und der Aussparung | `AppBackground` weicht seitlich `safeDrawing` aus; der Hintergrund bleibt edge-to-edge | `design/Components.kt` |
| **Vorschau-Fehler (P0)** | Sackgasse „Vorschau konnte nicht geladen werden“ + Überspringen | „Dieses Medium kann gerade nicht angezeigt werden.“ + kurzer Grund + **Erneut versuchen** (lädt neu) / **Weiter ohne Entscheidung** | `MediaPage.kt`, `FeedViewModel.retryPreview` |
| **Feed-Kopfzeile** | 3 Glas-Buttons (48 dp, mit Schatten) | leise Variante: 40 dp sichtbarer Kreis ohne Schatten, Touch weiterhin 48 dp | `GlassIconButton(quiet = true)` |
| **Entscheidungsschwelle** | keine Rückmeldung, ab wann Loslassen zählt | feine 2-dp-Kontur in Bedeutungsfarbe (blau/rot/türkis) plus **eine** Haptik `GestureThresholdActivate`; keine Kapsel, kein Text; „runter“ nie | `FeedGestureMachine.readyToCommit` (liest nur, Schwellen unverändert), `FeedViewModel`, `MediaPage` |
| **Rückgängig** | Medium war einfach wieder da | gleitet von oben herein, dazu Haptik | `FeedViewModel.undo` |
| **Gestenhilfe** | bei jedem Öffnen (Standard „an“) | automatisch **nur beim ersten Feed**, danach nur per Einstellung; Standard „aus“ | `AppSettings.gestureHintSeen`, `ReviewFeedScreen` |
| **Leere Zustände** | Textzeile in einer Karte | einheitlich `EmptyState` (Symbol im weichen Akzentkreis, Titel, Satz, ggf. Geste) | Später, Verlauf (auch „leerer Filter“), Duplikate, Große Dateien, Papierkorb, Dateien |
| **Ladezustände** | „Prüfe …“, Feed nur mit Spinner | „Identische Dateien werden verglichen …“ (mit echtem Fortschritt), „Medien werden geladen …“ | Strings, `ReviewFeedScreen` |
| **Ergebnis Papierkorb** | jede Meldung heißt „Ergebnis“ | Titel je Ausgang: *Bereinigung abgeschlossen*, *Teilweise ausgeführt*, *Nicht ausgeführt*, *Tageslimit erreicht*; Haptik nur bei Erfolg. **Meldungstexte und Semantik unverändert.** | `CleanupBasketScreen.kt` |
| **Tageslimit erschöpft** | rot | neutral (Grenze, kein Fehler) | `CleanupBasketScreen.kt` |
| **Große Dateien** | +/−-Zähler in 50-MB-Schritten; Kennzahl „0 Byte in 0 Dateien“; Untertitel „in deinen Ordnern“ (falsch, auch Videos) | Schnellwahl 50/100/250/500 MB · 1/2 GB; Kennzahl nur mit Treffern; „Keine zugängliche Datei ist größer als 100 MB.“; Untertitel „Große Videos und Dateien“ | `StorageScreens.kt`, gemeinsame `ChoiceChips` |
| **Zugriff** | eingeschränkt in Warnfarbe; abgelehnt mit „Mehr erfahren“ | Akzentfarbe, „PhotoMaid kann aktuell nur einen Teil deiner Medien sehen …“; abgelehnt: „PhotoMaid kann deine Fotos gerade nicht sehen“ + **Zugriff ändern** | `TodayScreen.kt`, Strings |
| **Heute „Alles geprüft“** | „Gerade wartet nichts …“ | „Im Moment wartet nichts auf deine Entscheidung.“ | Strings |
| **Duplikate** | „Exakte Duplikate finden“, SHA-256 im Haupttext | „Identische Kopien finden“; die Technik steht unter „Wie funktioniert das?“ | Strings, `DuplicatesScreen.kt` |
| **Pro** | „Einmal kaufen, dauerhaft nutzen. Kein Abo.“ | „Mehr Kontrolle, keine laufenden Kosten. Einmalkauf, kein Abo.“ | Strings |
| **Sammlungen (Performance)** | eine MediaStore-Abfrage je Sammlung | eine Abfrage je Quelle | `CollectionsStore.results` |
| **Unterseiten** | 96-dp-Tabbar-Abstand, obwohl dort keine Tabbar ist | `PageEndSpacer` (Systemleiste + 24 dp) | `navigation/Root.kt` + 5 Screens |

## 2. Design-System

- **Neu in `design/Tokens.kt`:**
  - `AppMotion.fast()` (120 ms) und `AppMotion.normal()` (280 ms); daneben bestehen `standard()` (Feder, Feed) und `commit()`.
  - `AppStroke`: hairline, regular, intent, progress
  - `AppIconSize`: 18, 22, 28, 44 dp
  - `AppControl`: quietIcon 40, largeButton 52, tabBarItem 54 dp
  - `AppRadii.control`
- Die Navigation nutzt jetzt `AppMotion` (vorher eigenes `NavMotion` mit festen Zahlen).
- Druck-Feedback `Modifier.pressScale` (0,96, 120 ms) für Prominent-, Glas- und Icon-Buttons. Der Ripple bleibt, es gibt keine Federshow.
- Typografie: Die Tabbar-Beschriftungen haben jetzt 12 sp statt 11 sp. Die übrigen Rollen bleiben: largeTitle, title, headline, body, subheadline, footnote, caption.
- Icons stammen weiterhin aus einer Familie (Material Rounded). Neu ist nur `BrokenImage` für den Problemzustand.

**Haptik-Sprache** (App-Schalter + Systemeinstellung über `performHapticFeedback`):

| Ereignis | Typ |
|---|---|
| Behalten | Confirm |
| Papierkorb | ContextClick |
| Später | SegmentTick |
| Schwelle erreicht (einmal) | GestureThresholdActivate |
| Termin bestätigt | ToggleOn |
| Rückgängig | VirtualKey |
| Anschlag am Anfang | SegmentFrequentTick (vorher Reject – das ist kein Fehler) |
| Bereinigung abgeschlossen | Confirm |

Keine Haptik bei Scrollen, Tabs, normalen Buttons oder passiven Zuständen.

## 3. Gesten-Verifikation

- **Schwellen:** unverändert. `readyToCommit` liest nur die bestehende Commit-Bedingung. Der Test `readyToCommitMatchesCommitCondition` beweist die Gleichheit für 6 Bewegungen.
- **Bestehende Tests:** Alle Gesten-, Undo-, Receipt-, Papierkorb- und Gerätetest-Regressionstests laufen unverändert grün:
  - `FeedFlowTest`, `CustomLaterTest`, `DeviceFindingsTest`, `ReworkTest`
- **Neu (`PremiumPassTest`):**
  - Schwelle nur einmal fühlbar, keine Meldung bei kurzen Bewegungen oder „runter“
  - Kontur erscheint erst ab der Schwelle
  - Vorschaufehler: Erneut versuchen und Weiter entscheiden nichts
  - Gestenhilfe beim ersten Feed
  - leere Zustände

## 4. Prüfungen (lokal, ohne GitHub Actions)

| Prüfung | Ergebnis |
|---|---|
| `./gradlew :core:test` | PASS – 83 |
| `./gradlew :app:testDebugUnitTest` | PASS – 95 (davon 40 JVM-Screenshots) |
| `./gradlew :app:lintDebug` | PASS – 0 Fehler, 26 Warnungen (11 `PluralsCandidate` sprachlich korrekt, 10 `UseKtx`, 2 `AutoboxingStateCreation` in Duplikate – schon vorher vorhanden) |
| `./gradlew :app:assembleRelease -Pphotomaid.signWithDebug=true` | PASS – R8, 5,3 MB |
| Gerätetest dieses Stands | **NOT RUN** |

## 5. Performance

Umgesetzt:
- Sammlungen lesen die Mediathek einmal je Quelle.
- Vorschaubilder werden auf die Zielgröße dekodiert, vorheriges, aktuelles und zwei nächste Medien werden vorgeladen, es gibt keinen Disk-Cache.
- Es gibt nur einen Player; er stoppt bei Mediumwechsel und wird bei `ON_STOP` freigegeben.
- Hashing und MediaStore laufen auf `Dispatchers.IO`.

Nicht gemessen (NOT RUN): Frame-Drops beim Wischen, Startzeit und Speicher auf dem Realme.

## 6. Accessibility

- TalkBack-Aktionen im Feed bleiben: Aktionen, Play/Pause, Spulen per `setProgress`.
- Leere Zustände haben Überschriften-Semantik.
- Chips melden `selected`.
- Touch-Ziele sind ≥ 48 dp, auch bei den leisen Feed-Buttons.
- Große Schrift: JVM-Screenshots bei 150 und 200 % sind geprüft.

Nicht geprüft (NOT RUN): TalkBack-Durchgang auf dem Gerät, Schaltersteuerung.

## 7. Screenshots (JVM, keine Geräteaufnahmen)

- Vorher: `docs/screenshots/before-premium/`
  - Feed, Große Dateien, Später, Verlauf, Heute
- Nachher: `docs/screenshots/`
  - neu: `p01_later_empty`, `p02_history_empty`, `p03_basket_empty`, `p04_feed_problem`

## 8. Bewusst nicht gebaut

- Keine Anzeige „freigegebener Speicher“: Die Medien liegen zunächst etwa 30 Tage im Android-Papierkorb, jede Zahl wäre falsch.
- Kein eigener Systemdialog, kein Health Score, kein Scanner-Look, kein Konfetti.
- Keine Tablet-Neugestaltung vor der Abnahme auf dem Telefon.

## 9. Bekannte Restpunkte und Release-Blocker

1. **Gerätetest dieses Stands (P0 für RC).** Zu prüfen auf dem Realme:
   - Schwellen-Kontur und Haptik beim Wischen
   - leise Kopfzeile
   - Querformat mit 3-Tasten-Navigation
   - Video-Fehlerzustand, falls reproduzierbar
   - Gestenhilfe nur beim ersten Mal
   - leere Zustände
   - Ergebnis-Titel im Papierkorb (nur „Ablehnen“)
   - Außerdem der Umbau-Branch: Tipp-Pause, Sammlungen, Unterseiten ohne Tabbar
2. **iOS** kennt weder den Umbau noch diesen Pass. Das ist dokumentiert in `docs/PORTING_MATRIX.md` und `docs/REWORK_2026-09-29.md`.
3. **Store:** finale Application-ID, eigener Signaturschlüssel, Play-Produkt, Datenschutzerklärung, Data Safety, Store-Eintrag, AAB.
