# Gerätetest 2026-09-29 – Befunde und Behebung

Gerät: Realme RMX3933, Android 14 / API 34, 720×1600 · Build `main` @ `3a171a2` · Tester: externer Agent per adb.
Ergebnis des Tests: 19 PASS, 5 FAIL, 2 NOT RUN, keine Crash-Log-Treffer. Der System-Papierkorb wurde nie bestätigt.

| # | Befund (Gerät) | Ursache | Behebung | Nachweis |
|---|---|---|---|---|
| 1 | **Leerer Bildschirm** nach Schließen des Feeds (und danach auch bei HOME/Resume), erst `force-stop` half | `onClose` rief `popBackStack()` auf und löschte sofort die Feed-Anfrage. Die Ausblend-Komposition sah dann `req == null` und navigierte über den Zweig „nach Prozessende“ ein **zweites Mal** zurück. Das entfernte auch die Tabs, der Back-Stack war leer. | Die Anfrage wird erst bei `ON_DESTROY` des Eintrags entfernt. Zurücknavigiert wird nur, solange genau dieser Eintrag oben liegt (`navigation/Root.kt`). | `DeviceFindingsTest.closingFeedReturnsToTodayRepeatedly` (3× öffnen und schließen): **FAIL mit dem alten Code, PASS mit dem Fix** |
| 2 | **Video:** „Vorschau konnte nicht geladen werden“, Play/Pause nicht prüfbar | Der generische Video-Frame-Decoder von Coil lieferte für ein vorhandenes Video kein Standbild. Der Grund ist auf dem Gerät nicht eindeutig feststellbar. Ohne Standbild wurde auch die Wiedergabe gesperrt. | (a) Neuer `media/VideoPosterFetcher`: holt das Standbild für MediaStore-Videos über den System-Thumbnailer (`ContentResolver.loadThumbnail`), ersatzweise über ein skaliertes Einzelbild. (b) Fehlt das Standbild trotzdem, bleibt das Video abspielbar. Das erste tatsächlich gerenderte Bild (`onRenderedFirstFrame`) gilt als „gesehen“ und schaltet die Gesten frei. „Ohne Entscheidung überspringen“ bleibt verfügbar. | `DeviceFindingsTest.videoPosterFetcherMatchesOnlyMediaStoreVideos`; Wiedergabe auf dem Gerät: **NOT RUN (erneut testen)** |
| 3 | Nach Widerruf von `READ_MEDIA_IMAGES` nur „Fotos 0“, **kein Hinweis** | Der Zustand wurde als „eingeschränkt“ (`LIMITED`) erkannt, „Heute“ hat dafür aber keinen Hinweis gezeigt. Waren nur Fotos oder nur Videos freigegeben (ohne „Ausgewählte“), galt das sogar als „kein Zugriff“. | Nur Fotos oder nur Videos gilt jetzt als `LIMITED`. „Heute“ zeigt dann die Karte „Eingeschränkter Zugriff · Zugriff ändern“. Auf der Datenschutzseite ist das Abzeichen jetzt „Eingeschränkt“, dazu gibt es den Weg „Einstellungen öffnen“. | `DeviceFindingsTest.limitedAccessShowsHint` |
| 4 | Verlauf zeigt zehnmal „Bereinigung nicht ausgeführt“ | Kein Fehler, ein Darstellungsproblem: Ein abgebrochener Systemdialog protokolliert korrekt ein Event je Element. | Direkt aufeinanderfolgende Events **desselben Vorgangs** (gleiche `operationID`) und **derselben Art** werden eine Zeile: „Bereinigung nicht ausgeführt · 10 Elemente“. Das Journal bleibt unverändert. | `HistoryGroupingTest.sameOperationBecomesOneRowOthersStaySeparate` |
| 5 | Test-Screenshot erscheint nicht unter „Screenshots“ | **Kein Fehler:** Screenshots erkennt PhotoMaid am Speicherort (Screenshots-Ordner), nicht am Bildinhalt. Das ist gewollt, weil es keine Bilderkennung gibt. Eine Datei in `Pictures/PhotoMaid-Test/` ist ein Foto. | – | – |
| 6 | Die Glas-Tabbar ist schlecht lesbar, darunter scrollender Text überlagert die Beschriftungen (Hinweis des Nutzers) | Die Glasfläche war zu 58 % transparent. | Neuer Token `glassBar`: Die Tabbar ist deckend, Lichtkante, Kontur und Schatten bleiben. Die Papierkorbleiste im Feed ist unverändert. | Screenshots `01_today_*`, `13_history*` |

Nicht geprüft (NOT RUN im Test): Datumsauswahl ↗ offen + HOME, weil der Test wegen Befund 1 abgebrochen wurde; „So wischst du“ vollständig.

## Nächster Gerätetest (gezielt)

1. Feed öffnen → Schließen (5×), Feed → HOME → App öffnen (3×): „Heute“ muss jedes Mal erscheinen.
2. Video im Feed: Standbild sichtbar. Falls nicht: Hinweis „Kein Vorschaubild – spiel das Video ab …“, Play startet, danach funktionieren die Gesten.
3. ↗ öffnen → HOME → App öffnen: keine Entscheidung entstanden.
4. `pm revoke … READ_MEDIA_IMAGES` → „Heute“ zeigt „Eingeschränkter Zugriff“.
5. Papierkorb leeren → System-Dialog **ablehnen** → Verlauf zeigt **eine** Zeile „Bereinigung nicht ausgeführt · n Elemente“.
6. Heute nach unten scrollen: Die Tabbar-Beschriftungen sind lesbar.

## Zweiter Gerätetest (Nachprüfung der Fixes) – 2026-09-29

Branch `claude/device-test-fixes` @ `e1beed4`, dasselbe Gerät (Realme RMX3933, Android 14/API 34, 720×1600).
Testmedien: 4 synthetische Bilder und 2 synthetische 10-s-Videos im eigenen Testordner. Der System-Papierkorb wurde nur abgelehnt.

| Test | Ergebnis |
|---|---|
| R1 Feed 5× schließen und Systemzurück: Heute mit Inhalt | PASS |
| R2 HOME/Resume 3× | PASS |
| R3 ↗ Datumsauswahl offen + HOME → gleiche Position, keine Entscheidung, Abbrechen | PASS |
| R4 Video: Standbild, Play/Pause, Fortschritt, Ton, danach Geste | PASS |
| R5 Eingeschränkter Zugriff: Karte „Eingeschränkt“, Wiederherstellung | PASS |
| R6 Papierkorb abgelehnt → genau eine Zeile „Bereinigung nicht ausgeführt · 3 Elemente“ | PASS |
| R7 Tabbar beim Scrollen lesbar (Heute, Verlauf) | PASS |
| R8 Gestenhilfe: alle fünf Zeilen | PASS |
| R9 Regressionslauf inkl. ↗ Abbrechen/Bestätigen, Undo, 20× hoch | PASS |

9 PASS, 0 FAIL, 0 NOT RUN, 0 BLOCKED. Keine Crash-Log-Treffer.

Kosmetische Beobachtung aus den Screenshots (kein Fehler): Zwischen Tabbar und Android-Navigationsleiste
scrollt Inhalt sichtbar weiter, ein schmaler Streifen unter der Tabbar. Optional für später: Hintergrund-Verlauf
unter der Tabbar.

Weiterhin nicht auf dem Gerät geprüft: echter bestätigter Löschweg über den System-Dialog (bewusst nur von Hand),
Android 11/12/13, TalkBack-Durchgang, Play-Billing-Kauf.
