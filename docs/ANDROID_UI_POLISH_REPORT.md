# PhotoMaid Android – Bericht „Gezielter UI-Polish“

Stand: 2026-09-29 · Basis `main` @ `ceb41c3` · nur Android-Repository geändert, iOS unverändert.

**Status: Code und JVM-Prüfungen grün. Nicht geprüft: Emulator oder Gerät.** Im Container gibt es kein
`/dev/kvm`, daher keinen Emulator, kein Gerät, keine echte Mediathek und keinen TalkBack-Durchgang.
Alle Bilder in diesem Bericht sind JVM-Screenshots (Robolectric und Roborazzi). Sie sind keine
Geräteaufnahmen und keine Geräteabnahme.

---

## Nachtrag P0 (Produktentscheidungen vom 2026-09-29, umgesetzt)

1. **Diagonalgeste ↗ – erst bestätigen, dann speichern** (Entscheidung des Nutzers, bewusste Abweichung von iOS)
   - ↗ öffnet nur die Datumsauswahl (`FeedViewModel.openCustomLater`). Es gibt keine Entscheidung, keinen
     Ledger-Eintrag und kein Weiterblättern; das Medium federt zurück.
   - Solange die Auswahl offen ist, sind Gesten, andere Aktionen, Undo und „Überspringen“ gesperrt.
   - **Bestätigen** = `Later` + `Schedule(Tag)` mit `amends`, also ein gemeinsames Undo. Danach folgt das
     nächste Medium. Es gibt keinen Standardtermin und keine Pille.
   - **Abbrechen, Zurück oder Wegwischen** = nichts geändert, gleiches Medium, kein Journal-Eintrag.
   - Die Bindung an Item und Ledger-Revision beim Öffnen wird geprüft (`CustomLaterRequest`).
     - Eine veraltete Revision wird verworfen.
     - Ein fremder oder verspäteter Callback wird ignoriert.
     - Eine doppelte Bestätigung entscheidet nur einmal.
   - Die TalkBack- und Schalter-Aktion „Später, Datum wählen“ nutzt denselben Pfad.
   - „Ändern“ in der Pille nach → funktioniert wie bisher. Abbrechen lässt dort jetzt den bisherigen Termin
     stehen, statt ihn neu zu setzen.
   - Der Core ist unverändert (`FeedGestureMachine`, `FeedSession`, `DecisionLedger`); geändert hat sich
     nur ein Kommentar.
   - Aktualisiert: `CLAUDE.md` (Android), `docs/PORTING_MATRIX.md`, `docs/PARITY_MATRIX.md` (DIFF),
     `docs/QA_MATRIX.md` und der Hilfetext. **iOS ist noch nicht angepasst** und soll nachgezogen werden.
   - Tests:
     - `FeedFlowTest.rightUpOpensPickerWithoutDecisionAndCancelChangesNothing`
     - `FeedFlowTest.rightUpConfirmSetsChosenDateAndAdvances` (Kalenderdatum in 14 Tagen, gemeinsames Undo)
     - `CustomLaterTest` (5 Tests: Öffnen ohne Entscheidung, Abbrechen, nur Originalmedium, veraltete Revision,
       fremder oder doppelter Callback)
     - Alle PASS.
   - Damit sind die Regressionskriterien 5 und 6 aus §16 jetzt **PASS**.
2. **Home: Zahl und Hauptaktion konsistent**
   - „Prüfung starten“ öffnet jetzt den gemeinsamen Feed aller offenen Medien (`FeedRequest.Source.AllMedia`,
     Titel „Medien prüfen“): Fotos, Screenshots und Videos, neueste zuerst.
   - Die Zahl auf Heute und der Feed nutzen dieselbe Funktion `MediaLibrary.reviewableMedia()` und dieselbe
     `normalQueue`.
   - Tests: `AppFlowsTest.startReviewOpensSharedFeedWithSameCount` und
     `sharedFeedContainsPhotosScreenshotsAndVideos` („1 von 6“ bei 6 offenen Medien).
3. **Einzahl und Mehrzahl überall, auch in den Papierkorbtexten.** Nur die Grammatik ist geändert,
   Bedeutung, Abläufe und Zahlen sind gleich geblieben.
   - Umgestellt auf `plurals`:
     - `hub_basket_sub` („1 Element, noch nicht gelöscht“)
     - `basket_empty_media` („Papierkorb leeren: 1 Foto/Video“)
     - `basket_delete_files`
     - `result_media_trashed` („1 Medium wurde …“)
     - `result_files_deleted`, `result_partial`, `result_allowance`
     - `count_items`, `large_in_files`, `storage_value_items`
     - `dup_not_comparable`, `dup_not_local`, `dup_move`
     - `rule_title_older`, `rule_detail_new`, `rule_days`, `rule_count`
   - Die 11 übrigen `PluralsCandidate`-Warnungen erzeugen keine falsche Sprache: Der Wert 1 wird separat
     behandelt, das Substantiv ändert sich nicht, oder die Zahl ist immer mindestens 2.
   - Test: `AppFlowsTest.singularTexts`. Die zwei Assertions „1 Fotos/Videos“ wurden auf die korrekte Form
     „1 Foto/Video“ angepasst.

**Prüfstand nach P0:**
- `:core:test`: 82 PASS
- `:app:testDebugUnitTest`: 73 PASS
- `lintDebug`: 0 Fehler, 26 Warnungen (11 davon `PluralsCandidate`, alle sprachlich korrekt)
- `assembleRelease` (R8): PASS
- Emulator und Gerät: NOT RUN

---

## 0. Konflikt: Diagonalgeste (§3) – *erledigt durch Nachtrag P0.1*

§3 verlangt: *„Öffnen der Datumsauswahl ist keine Entscheidung. Erst die Bestätigung speichert, dann geht
es weiter. Abbrechen = gleiches Medium, nichts geändert.“*

**Ist-Zustand (geprüft im Code und im Test):**

| Schritt | Heute tatsächlich | §3 verlangt |
|---|---|---|
| Wisch schräg rechts oben | `FeedViewModel.commit(LATER_CUSTOM)` speichert **sofort** „Später“ (ohne Datum). Das Journal bekommt ein Event „Auf später gelegt“, und der Feed geht zum nächsten Medium. | nichts speichern, gleiches Medium |
| Drehrad | öffnet danach, gebunden an die Quittung des ursprünglichen Mediums (`LaterReceipt`) | öffnen |
| Bestätigen | ergänzt den Termin (`Schedule`, `amends` = gemeinsames Undo) | Termin speichern, dann weiter |
| Abbrechen / Wegwischen | setzt den **Standardtermin** (2 Tage) | nichts ändern |

Dieses Verhalten ist nicht zufällig entstanden. Es ist die festgeschriebene 1:1-Portierung der iOS-App und in
drei Quellen verbindlich:

- `CLAUDE.md` (Android und iOS), Gestentabelle: *„nächstes Medium + Drehrad; Abbrechen = Standardtermin“*
- `app/src/test/.../FeedFlowTest.kt` › `rightUpOpensWheelAndCancelKeepsDefaultDate` (prüft genau das)
- iOS `FeedViewModel.swift` (Referenz für die Portierung)

Der Auftrag sagt: *„Diese Regeln prüfen und schützen, nicht das Gestenmodell umbauen“* und *„Bei einem echten
Konflikt: nicht eigenmächtig ändern, sondern konkret melden.“* Deshalb habe ich das Verhalten **nicht
verändert**.

**Was §3 heute schon erfüllt:**

- Die Datumsauswahl ist an das ursprüngliche Medium gebunden. Ein verspäteter Callback entscheidet nie
  ein anderes Medium. Das prüfen die Tests `FeedSessionTest.receiptBinding` und
  `staleCallbackNeverChangesOtherItem`, und `schedule()` vergleicht Quittungs-ID und Revision.
- Ein Termin verschiebt oder löscht nichts. Das Medium erscheint erst am Fälligkeitstag wieder in
  `dueQueue` und wird nie automatisch gelöscht.
- Undo nimmt Später und Termin gemeinsam zurück.

**Was eine Umstellung bräuchte** (Vorschlag, nicht umgesetzt):

1. Die Diagonalgeste committet nicht. Sie öffnet das Drehrad für das aktuelle Medium, und `isCommitting`
   bzw. die Gesten sind gesperrt, solange es offen ist.
2. Bestätigen erzeugt einen Befehl `Later`, gefolgt von `Schedule` (oder eine neue Aktion
   `LaterUntil(day)`), danach geht der Feed zum nächsten Medium.
3. Abbrechen verwirft alles, und der Feed bleibt auf dem Medium.
4. Anzupassen wären `CLAUDE.md` (beide Gestentabellen), `FeedFlowTest`, `docs/PARITY_MATRIX.md` und
   gegebenenfalls die iOS-App, damit die Parität erhalten bleibt.

→ **Bitte entscheiden**, ob die Diagonalgeste auf „erst bestätigen, dann speichern“ umgestellt werden soll.
Das ist eine Änderung am Entscheidungsablauf, kein UI-Polish.

---

## 1. Geänderte Screens und Dateien

| Schritt | Screen | Dateien |
|---|---|---|
| A | Heute | `feature/today/TodayScreen.kt`, `res/values/strings.xml` |
| A | Aufräumen (nur Zähler, Symbol) | `feature/cleanup/CleanUpHubScreen.kt` |
| A | Speicherübersicht (nur Screenshot-Symbol) | `feature/storage/StorageScreens.kt` |
| B | global: Hintergrund und Flächen, Zeilenkomponente | `design/Tokens.kt`, `design/Components.kt` (`SourceRow`, neu `GestureLegendRow`, entfernt `SwipeTile`) |
| C | Foto- und Video-Feed (Medienfläche, Infozeile) | `feature/review/MediaPage.kt`, `feature/review/ReviewFeedScreen.kt` (2 Zeilen) |
| D | Später | `feature/later/LaterScreen.kt` |
| D | Verlauf | `feature/history/HistoryScreen.kt`, neu `feature/history/HistoryGrouping.kt` |
| E | Pro | `feature/pro/ProScreen.kt` |
| F | Einstellungen und Gestenlegende | `feature/settings/SettingsScreen.kt` |
| Tests | – | `TestSupport.kt` (Option `visual`), neu `VisualTestMedia.kt`, neu `HistoryGroupingTest.kt`, `ScreenshotTest.kt` (+6 Feed-Geometrien), `AppFlowsTest.kt` (neuer Hometext) |
| Doku/Skript | – | `scripts/ci_emulator_smoke.sh` (neuer Hometext), `docs/PORTING_MATRIX.md`, `docs/screenshots/*` |

## 2. Sichtbare Verbesserungen

**Heute (A)**

- Die Review-Karte steht jetzt oben und hat eine klare Hauptaktion. Sie kennt drei Zustände:
  - „Aufräumen starten“ mit dem Button „Prüfung starten“, solange es noch keine Entscheidung gibt.
  - „Weiter aufräumen“ mit „Prüfung fortsetzen“, sobald es Entscheidungen gibt.
  - „Alles geprüft“ ohne Button, wenn nichts offen ist. So gibt es keinen toten Button.
- Der Untertitel ist korrekt gebeugt: „1 Element wartet …“ bzw. „3 Elemente warten auf deine Entscheidung“.
- Die Speicherkarte steht unter der Review-Karte und überstrahlt sie nicht mehr.
- Die Zähler sind rechtsbündige Zahlen („6“) statt der zweizeiligen Anzeige „6 / Elemente“.
  - TalkBack liest „6 Elemente“ bzw. „1 Element“.
  - Bei Schrift ab 130 % steht ausgeschrieben „6 Elemente“ unter dem Titel.
- „Exakt identische Kopien“ heißt jetzt „Identische Kopien“.
- Screenshots haben das eindeutigere Symbol `Screenshot` statt der Kamera (auch in Aufräumen und Speicher).
- „Wieder fällig“ ist korrekt gebeugt (Plural).

**Material (B)**

- Der Hintergrund ist neutraler (`#F3F6FA → #E6EDF6` statt `#EEF5FD → #DCEAFB`); das PhotoMaid-Blau bleibt.
- Die Flächen sind zu 94 % deckend und haben eine feine graue Kontur statt einer weißen Kante.
- Die Werte für den Dunkelmodus sind nur leicht angepasst. Hoher Kontrast und die Glas-Tokens sind
  unverändert.
- Glas bleibt auf Navigation und schwebende Controls beschränkt. Das Pro-Symbol und die Gestenkacheln
  sind nicht mehr verglast.

**Feed (C)**

- Keine weiße Karte mehr. Das Medium sitzt in einem Rahmen im exakten Seitenverhältnis, mit Fit und nie
  Fill, also ohne Beschnitt.
  - Die Ecken sind mit 12 dp nur minimal gerundet, damit Screenshots und Dokumente praktisch
    vollständig sichtbar bleiben.
  - Freie Fläche ist ruhiger App-Hintergrund.
  - Fotos und Videos nutzen dieselbe Geometrie.
- Die Medienfläche ist seitlich 8 dp statt 18 dp eingerückt.
- Neue Infozeile über der Papierkorbleiste: Datum · Größe, bei Videos zusätzlich die Dauer.
  - Die Werte stammen aus dem vorhandenen `MediaAsset` (MediaStore). Es gibt keinen zusätzlichen Scan und
    keine neue Berechtigung.
  - Eine unbekannte Größe wird weggelassen, nie als „0“ angezeigt.
- Geschützt und unverändert: Papierkorbleiste, Schließen, Rückgängig, Menü, Gesten, Richtungsanzeige, Pille.
- Die Gestenhilfe beim Öffnen schreibt die Diagonale jetzt genau aus: „Schräg hoch rechts“.

**Später (D)**

- Konkreter Typ statt „Medium“: „Foto“, „Video“, „Screenshot“ oder „Datei“.
  - „Medium“ erscheint nur noch, wenn das Element nicht mehr lesbar und der Typ deshalb unbekannt ist.
  - Der Typ kommt aus derselben MediaStore-Abfrage wie die Vorschaubilder.
- Klare Terminzeile mit vier Formen:
  - „Erneut prüfen am Sa., 4. Okt.“
  - „Heute wieder fällig“
  - „Fällig seit …“
  - „Kommt bei der nächsten Prüfung wieder“ für Elemente ohne Termin.
- Die Gruppe „Ohne Datum“ heißt jetzt „Ohne Termin“. Das beschreibt den Zustand exakt: `dueDay == null`,
  und `normalQueue` nimmt solche Elemente in der nächsten Prüfung wieder auf. Die Logik ist unverändert.
- Dateinamen sind einzeilig mit Auslassung in der Mitte.

**Verlauf (D)**

- „Auf später gelegt“ und der direkt folgende erste Termin werden zu einer Zeile:
  „Auf später gelegt · So., 4. Okt.“.
- Kriterien für die Zusammenfassung (`groupHistory`):
  - gleiches Item
  - Revision exakt +1, also nichts dazwischen, auch kein Undo
  - `schedule.from == later.to`, noch ohne Datum
- Keine Gruppierung nach Uhrzeit, Ähnlichkeit oder Session.
- Spätere Terminänderungen bleiben eigene Zeilen: „Termin gesetzt · 1. Nov.“.
- Das Journal wird weder verändert noch gekürzt. Die Filter laufen wie bisher auf den Roh-Events. Die
  Tageszahl zählt sichtbare Vorgänge und ist korrekt gebeugt („1 Eintrag“).
- Behalten- und Papierkorb-Einträge sind begrifflich unverändert.

**Pro (E)**

- Drei tatsächlich freigeschaltete Kernvorteile, belegt durch `Capabilities`:
  - Unbegrenzt bereinigen (`dailyCleanupLimit = null`)
  - Identische Kopien finden (`exactDuplicates`: Fotos, Videos und Dateien aus verbundenen Ordnern)
  - Eigene Prüfroutinen (`persistentRoutines`). Ehrlich formuliert: „beim Öffnen der App“, nicht als
    Hintergrundüberwachung.
- **Entfernt:** „Erweiterte Datei- und Speicherwerkzeuge“. `advancedFileTools` wird im App-Code nirgends
  ausgewertet, das Versprechen hatte also keinen Gegenwert.
- „Keine Werbung“ ist nur noch eine kleine Nebenzeile („Außerdem: keine Werbeangebote.“).
- SHA-256 und die Details zu Routinen und Tageslimit stehen im aufklappbaren Bereich „So funktioniert Pro“.
- Ohne Store-Konfiguration zeigt die Seite einen kompakten Status mit Info-Symbol. Es gibt keinen
  Kaufbutton und keinen erfundenen Preis.
- Die Pro-Vorschau ist ein nachgeordneter Abschnitt.
  - Ohne Werbeanbieter steht dort ein einzelner Satz, statt eines deaktivierten Buttons und drei
    Hinweiszeilen.
  - Mit Anbieter bleiben Button, Beschreibung und Limit-Hinweis unverändert.
- Unverändert: Einmalkauf, Preis nur aus Play Billing, Wiederherstellen, Entitlements, Reward-Dauer und
  -Limit, Datenschutztext.

**Einstellungen (F)**

- „So wischst du“ ist jetzt eine Liste mit fünf Zeilen statt fünf gequetschter Kacheln:
  - Behalten · Hoch
  - Papierkorb · Links
  - Später · Rechts · Standard: In 2 Tagen (der Text folgt der Einstellung)
  - Datum wählen · Schräg hoch rechts
  - Zurück · Runter
- Die Liste passt auf 320 dp und bei 200 % Schrift.
- „Später (Wisch nach rechts)“ mit dem Standard „In 2 Tagen“ bleibt unverändert.
- Bei „Datenschutz & Zugriff“ steht „Verarbeitung auf dem Gerät“ jetzt als Untertitel. Der Titel bricht
  nicht mehr zweizeilig um. Die Aussage ist nicht zu „alles offline“ vereinfacht.

## 3. Nachweis: Behalten, Papierkorb und Gesten unverändert

- `git diff` ist **leer** für:
  - `core/` (gesamte Domain, `FeedGestureMachine`, `FeedSession`, `DecisionLedger`, Journal, Tageslimit)
  - `FeedViewModel.kt`, `FeedGestures.kt`, `LaterPicker.kt`
  - `CleanupBasketScreen.kt`, `AppModel.kt`
  - `data/` (Room-Schema) und `media/` (MediaStore, Papierkorb-Operation)
- `ReviewDock` (Papierkorbleiste) steht nicht im Diff.
- Keine Texte von Papierkorb, Leiste, Bestätigung oder Ergebnis geändert. Die einzige neue Zeile, die
  „Papierkorb“ erwähnt, ist ein Pro-Detailtext.
- Gestenschwellen sind unverändert. Sie sind absolute dp-Werte in `FeedGestureMachine`. Die 20 dp breitere
  Medienfläche beeinflusst nur die Fluganimation (`exitOffset`), keine Schwelle.
- Global betroffen ist nur der Hintergrund bzw. Flächenton, auch im Papierkorb-Screen (siehe `05_basket.png`).
  Layout, Texte, Auswahl und Buttons dort sind identisch.

### Regressionskriterien (§16)

| # | Kriterium | Test | Ergebnis |
|---|---|---|---|
| 1 | Hoch behält genau das angezeigte Medium | `FeedFlowTest.upKeepsAndAdvancesExactlyOne`, `FeedSessionTest.oneKeepPerSwipe` | PASS |
| 2 | Links markiert genau das angezeigte Medium | `FeedFlowTest.leftMarksForCleanupNothingDeleted` | PASS |
| 3 | Rechts setzt den Standardtermin | `FeedFlowTest.rightLaterWithDefaultDateAndPill` | PASS |
| 4 | Standard 2 Tage | derselbe Test, Assertion „In 2 Tagen wieder“ | PASS |
| 5 | Diagonal öffnet **nur** die Auswahl | – | **FAIL gegenüber §3** (speichert sofort Später, siehe §0); PASS gegenüber CLAUDE.md und iOS |
| 6 | Abbrechen verändert nichts | `rightUpOpensWheelAndCancelKeepsDefaultDate` | **FAIL gegenüber §3** (Abbrechen = Standardtermin, siehe §0) |
| 7 | Bestätigen betrifft nur das ursprüngliche Medium | `FeedSessionTest.receiptBinding` | PASS |
| 8 | Kein verspäteter Callback entscheidet das nächste Medium | `FeedSessionTest.staleCallbackNeverChangesOtherItem` | PASS |
| 9 | Runter = Zurück ohne Entscheidung | `FeedFlowTest.downGoesBackWithoutDecision`, `downAtStartIsBumpOnly` | PASS |
| 10 | Papierkorb, Bestätigung, Tageslimit | `AppFlowsTest.confirmCleanupTrashesReconcilesAndCountsAllowance`, `cancelledSystemDialogRemovesNothing`, `deselectionIsKeptAndOnlySelectedAreRemoved` | PASS (Fake-Mediathek); Systemdialog auf Gerät: NOT RUN |
| 11 | Keine doppelten Entscheidungen | `FeedFlowTest.rapidSwipesNeverDoubleDecide`, `DecisionLedgerTest.duplicateCommand` | PASS; echter Prozesstod auf Gerät: NOT RUN |
| 12 | Der Verlauf verändert keine Events | `HistoryGroupingTest` (3 Tests: Journal vollständig, nichts verloren, nur eindeutige Paare) | PASS |

Keine Test-Assertion der geschützten Logik wurde abgeschwächt. Die einzige geänderte Assertion ist der neue
Hometext in `AppFlowsTest.permissionGrantedShowsPendingCount`, und sie ist jetzt strenger: Sie prüft
zusätzlich den Zustand „starten“.

## 4. Builds und Tests (lokal, ohne GitHub Actions)

| Befehl | Ergebnis |
|---|---|
| `./gradlew :core:test` | PASS – 82 Tests, 0 Fehler |
| `./gradlew :app:testDebugUnitTest` | PASS – 64 Tests, 0 Fehler (vorher 55, neu 3 Verlaufstests und 6 Feed-Geometrie-Screenshots) |
| `./gradlew :app:lintDebug` | PASS – 0 Fehler, 43 Warnungen (28 `PluralsCandidate` aus Texten außerhalb dieses Auftrags, u. a. Papierkorb; 10 `UseKtx`, 2 `PrivateResource`, 2 `AutoboxingStateCreation`, 1 `ModifierParameter`) |
| `./gradlew :app:assembleRelease -Pphotomaid.signWithDebug=true` | PASS – R8, 5,4 MB |
| `./gradlew :app:recordRoborazziDebug` | PASS – 37 JVM-Screenshots |
| Emulator-Smoke (`scripts/ci_emulator_smoke.sh`) | NOT RUN – kein KVM. Die Texte im Skript sind an den neuen Hometext angepasst. |
| GitHub Actions | NOT RUN – bewusst. Der Workflow läuft nur manuell (`workflow_dispatch`), ein Push löst ihn nicht aus. |

## 5. Screenshots (JVM, keine Geräteaufnahmen)

- Vorher: `docs/screenshots/before-ui-polish/` mit Heute, Feed Foto und Video, Papierkorb, Später, Verlauf,
  Pro, Einstellungen und Schrift 150 %.
- Nachher: `docs/screenshots/`. Neu sind die Feed-Geometrien:
  - `g01_feed_portrait` (Hochformat)
  - `g02_feed_landscape` (Querformat)
  - `g03_feed_panorama` (Panorama)
  - `g04_feed_screenshot_text` (Screenshot mit lesbarem Text)
  - `g05_feed_video_landscape` und `g06_feed_video_portrait` (Videos)
- Testmedien (§8): Im Repo gibt es keine geeigneten echten Testfotos. Deshalb zeichnet `VisualTestMedia`
  (nur im Testcode) feste, erkennbar synthetische Motive in den echten Geometrien.
  - Hochformat 3:4, Querformat 4:3, Panorama 3:1, Screenshot 9:19,5 mit lesbarem Text, Video 16:9 und 9:16
  - keine privaten oder lizenzpflichtigen Inhalte, kein Netzwerk, nichts im Release- oder QA-Build
  - kein Testdokument, weil die Dateiansicht nicht im Feed-Scope liegt
- Video-Screenshots zeigen das Vorschaubild mit den Player-Controls. Auf der JVM wird nicht decodiert.

## 6. Offene Punkte und nicht ausgeführte Prüfungen

1. **Diagonalgeste: Entscheidung nötig** (siehe §0).
2. Nicht auf Gerät geprüft (NOT RUN): Emulator und Gerät API 30, 34 und 37, echte Mediathek, Video-Decoding
   im neuen Rahmen, TalkBack-Durchgang, Schaltersteuerung, Frame-Drops beim Wischen, Kontrast auf echten
   Displays.
3. Schon vorher vorhanden, nicht geändert:
   - Die Zahl auf der Home-Karte zählt offene Fotos **und** Videos, „Prüfung starten“ öffnet aber den
     Foto-Feed. iOS verhält sich identisch. Ob das so bleiben soll, ist eine Produktentscheidung.
4. Vom Auftrag geschützt, deshalb nicht angefasst:
   - „%d Elemente, noch nicht gelöscht“ im Aufräumen-Hub (Papierkorb-Zeile) ist noch nicht gebeugt
     („1 Elemente“).
   - Auch die übrigen Papierkorbtexte haben noch keine Pluralformen.
5. Auf dem Pro-Screen sind Kauf und Werbevorschau weiterhin BLOCKED, weil es keine Play-Console-Produkt-ID
   und keinen Werbeanbieter gibt.
6. Kein Sicherheitsfehler gefunden. Bemerkt, aber außerhalb dieses Auftrags: Die Capability
   `advancedFileTools` existiert im Kern, wird aber nicht genutzt. Der Pro-Text verspricht sie nicht mehr.
