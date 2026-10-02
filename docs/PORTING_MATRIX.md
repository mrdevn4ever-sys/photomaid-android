# PhotoMaid iOS → Android: Audit und Portierungsmatrix

Quelle der Wahrheit: iOS-Repository `mrdevn4ever-sys/photomaid` (Stand `main` @ `21717f3`), **read-only**.
Status-Vokabular der Parität: MATCH · ANDROID PLATFORM ADAPTATION · BLOCKED BY PLATFORM · BUG.

## 1. Befund iOS-Repository

- 116 Dateien, ~10 000 Zeilen Swift. Zwei Schichten:
  `PhotoMaidCore` (plattformneutrale Domain, 74 Tests) und `PhotoMaid/` (SwiftUI-App, iOS 27).
- Laut eigenem Bericht (`PHOTOMAID_IOS_FINAL_REPORT.md`) ist die iOS-App **„NICHT FERTIG“**: das App-Target
  wurde im iOS-Repo nie auf einem Mac kompiliert; Käufe und Rewarded sind BLOCKED (keine Konfiguration).
  Für den Port bedeutet das: das **Verhalten im Code** ist die Wahrheit, nicht die Mockups (`docs/drafts`
  zeigen einen älteren Stand mit „Vormerken/Auswahlkorb“ statt „Papierkorb“).
- Einzige Sprache: Deutsch (`Localizable.xcstrings`, 375 Einträge, Quellsprache `de`).
- Dark Mode: ja (eigene Nachtblau-Palette), zusätzlich Werte für „Erhöhter Kontrast“.

## 2. Screen-Inventar (iOS) → Android

| # | Screen | iOS-Datei(en) | Navigation iOS | Android (Compose) | Navigation Android |
|---|---|---|---|---|---|
| 1 | Heute | `Features/Today/TodayView.swift` | Tab 1 | `feature/today/TodayScreen.kt` | Bottom-Tab 1 |
| 2/3 | Feed Foto/Video | `ReviewFeed/ReviewFeedView.swift`, `MediaPage.swift`, `FeedViewModel.swift` | `fullScreenCover` | `feature/review/ReviewFeedScreen.kt`, `MediaPage.kt`, `FeedViewModel.kt` | Vollbild-Route `feed`, Tabbar aus |
| – | Drehrad „Später“ | `LaterPickerSheet` | Sheet | `LaterPickerSheet` (ModalBottomSheet, Wheel-Picker) | Sheet |
| – | Session-Zusammenfassung | `SessionSummaryView` | im Feed | `SessionSummary` | im Feed |
| 4 | Datenschutz & Zugriff (Onboarding) | `Onboarding/PrivacyAccessView.swift` | `fullScreenCover` beim 1. Start, Sheet aus Heute/Einstellungen | `feature/onboarding/PrivacyAccessScreen.kt` | Vollbild beim 1. Start, sonst Route |
| 5 | Papierkorb | `CleanupBasket/CleanupBasketView.swift` | Push (Hub) / Sheet (Feed) | `feature/cleanup/CleanupBasketScreen.kt` | Route `basket` |
| 6 | Aufräumen-Hub | `CleanUpHub/CleanUpHubView.swift` | Tab 2 | `feature/cleanup/CleanUpHubScreen.kt` | Bottom-Tab 2 |
| – | Ordnen | `OrganizeView` (in `CleanUpHubView.swift`) | Tab 3 | `feature/organize/OrganizeScreen.kt` | Bottom-Tab 3 |
| 7 | Downloads/Dateien | `FileBrowser/FileBrowserView.swift`, `FileIndex.swift` | Push | `feature/files/FileBrowserScreen.kt` | Route `files` |
| – | Verbundene Quellen | `SourcesView` | Push | `feature/files/SourcesScreen.kt` | Route `sources` |
| 8 | Große Dateien | `Storage/LargeFilesView.swift` | Push | `feature/storage/LargeFilesScreen.kt` | Route `large` |
| 9 | Duplikate | `Duplicates/DuplicatesView.swift` | Push | `feature/duplicates/DuplicatesScreen.kt` | Route `duplicates` |
| 10 | Speicherübersicht | `StorageInsightsView` | Push | `feature/storage/StorageInsightsScreen.kt` | Route `storage` |
| 11 | Später | `Later/LaterView.swift` | Push | `feature/later/LaterScreen.kt` | Route `later` |
| 12 | Regeln & Routinen | `Rules/RulesView.swift`, `RulesStore.swift` | Push | `feature/rules/RulesScreen.kt` (+ `RuleEditor`) | Route `rules` |
| 13 | Verlauf | `History/HistoryView.swift` | Tab 4 | `feature/history/HistoryScreen.kt` | Bottom-Tab 4 |
| 14 | PhotoMaid Pro | `Pro/ProView.swift` | Sheet | `feature/pro/ProScreen.kt` | Route `pro` |
| 15 | Einstellungen | `Settings/SettingsView.swift` (+ `HelpView`) | Sheet über Zahnrad | `feature/settings/SettingsScreen.kt`, `HelpScreen.kt` | Route `settings`, `help` |

Nicht vorhanden in iOS (daher auch **nicht** auf Android): eigener Video-Tab, „Ähnliche/Unscharfe“,
Junk-/Health-Scores, RAM/Battery-Booster, Kategorie-GB je Fotoart, Konto, Cloud.

## 3. Domain (PhotoMaidCore) → `:core` (reines Kotlin/JVM, auf Linux testbar)

| iOS-Typ | Zweck | Android |
|---|---|---|
| `FeedGestureMachine` | einzige Gesten-State-Machine (Totzonen, Hysterese, Flick, Suspendierung) | `core/feed/FeedGestureMachine.kt` (1:1, gleiche Schwellen in dp) |
| `FeedSession` | Commit an Item-ID + Revision + Command-ID, Terminleiste, Zusammenfassung | `core/feed/FeedSession.kt` |
| `DecisionLedger` (actor) | Entscheidungen, Korrektur, Idempotenz, Undo (200), Journal, Tageslimit | `core/decisions/DecisionLedger.kt` (`Mutex`-geschützt, synchroner Commit) |
| `LedgerPersistence` | atomarer Commit | Interface; Produktion Room-Transaktion (`app/data/RoomLedgerPersistence.kt`) |
| `LaterPlanning` | Presets, Drehrad-Intervalle, Queues, Gruppen | `core/later/LaterPlanning.kt` |
| `LocalDay`, `Clock` | Kalendertag + Kalenderarithmetik | `core/model/LocalDay.kt` (`java.time.LocalDate`) |
| `OperationModel` | Journal-Zustände, Outcome, Probe, DayUsage | `core/cleanup/OperationModel.kt` |
| `DuplicateFinder` | Größe → Teilhash (64 KiB) → Streaming-SHA-256 | `core/duplicates/DuplicateFinder.kt` (`MessageDigest`) |
| `ReviewRules` | Regeln → Review-Aufgaben | `core/rules/ReviewRules.kt` |
| `Capabilities`, `RewardLedger` | einzige Free/Pro/Rewarded-Auflösung | `core/capabilities/Capabilities.kt` |
| `StorageOverview`, `KnownSize`, `SizeTally` | ehrliche Zahlen | `core/model`, `core/storage` |

Alle 74 Swift-Testfunktionen werden als JUnit-Tests portiert (`core/src/test`).

## 4. Plattform-Mapping

| Bereich | iOS | Android | Status |
|---|---|---|---|
| UI | SwiftUI, Liquid Glass | Jetpack Compose, eigenes `Glass*`-Design-System | ANDROID PLATFORM ADAPTATION |
| Mediathek | PhotoKit (`PHAsset.localIdentifier`) | MediaStore (`_ID` + Collection-URI, `DATE_MODIFIED`/`GENERATION` für Änderungen) | ANDROID PLATFORM ADAPTATION |
| Screenshots | `mediaSubtypes & photoScreenshot` | `RELATIVE_PATH` bzw. Bucket „Screenshots“ (Heuristik, dokumentiert) | ANDROID PLATFORM ADAPTATION |
| Berechtigung | readWrite, 5 Zustände inkl. `limited` | `READ_MEDIA_IMAGES/VIDEO` (33+), `READ_MEDIA_VISUAL_USER_SELECTED` (34+ = „Ausgewählte Fotos“), `READ_EXTERNAL_STORAGE` (≤32) | ANDROID PLATFORM ADAPTATION |
| „Auswahl verwalten“ | `presentLimitedLibraryPicker` | erneute Berechtigungsanfrage (System zeigt Auswahl) | ANDROID PLATFORM ADAPTATION |
| Löschen Fotos/Videos | `deleteAssets` → „Zuletzt gelöscht“ | `MediaStore.createTrashRequest` → System-Papierkorb (30 Tage), Systembestätigung | MATCH (Semantik) |
| Löschbarkeit | `canPerform(.delete)` | Trash-Request für eigene/fremde Medien über System-Dialog | ANDROID PLATFORM ADAPTATION |
| Abgleich | Gesamtergebnis PhotoKit | Ergebnis-Code **plus** Existenzprüfung je Item (`IS_TRASHED`) | ANDROID PLATFORM ADAPTATION (strenger) |
| Mediengröße | Fotos unbekannt (keine öffentliche API) | `MediaStore.MediaColumns.SIZE` ist öffentlich → bekannte Größe | ANDROID PLATFORM ADAPTATION (siehe §6) |
| „Nur in iCloud“ | `PHImageResultIsInCloudKey` | entfällt (MediaStore listet nur lokale Medien) | BLOCKED BY PLATFORM (kein Äquivalent nötig) |
| Vorschau | `PHCachingImageManager` | Coil 3 mit Zielgröße, `ContentResolver.loadThumbnail` | ANDROID PLATFORM ADAPTATION |
| Video | ein `AVPlayer` | ein Media3-`ExoPlayer` | MATCH |
| Ordnerzugriff | `fileImporter` + security-scoped Bookmark | SAF `OpenDocumentTree` + `takePersistableUriPermission` | ANDROID PLATFORM ADAPTATION |
| Datei löschen | `removeItem`, nur endgültig | `DocumentsContract.deleteDocument`, nur endgültig | MATCH |
| Dateivorschau | Quick Look | `ACTION_VIEW` mit `FLAG_GRANT_READ_URI_PERMISSION` (kein Archiv/„Andere“) | ANDROID PLATFORM ADAPTATION |
| Mitteilungen | `UNCalendarNotificationTrigger`, max. 8 | WorkManager (einmalig 09:00 / wöchentlich 10:00) + `POST_NOTIFICATIONS` (33+) | ANDROID PLATFORM ADAPTATION |
| Einstellungen | Core Data `CDKeyValue` (JSON) | Room-Tabelle `key_value` (JSON) – eine Wahrheit wie iOS | MATCH |
| Persistenz | Core Data, eine Transaktion je `LedgerChange` | Room, `withTransaction` je `LedgerChange` | MATCH |
| Käufe | RevenueCat + App Store, Non-Consumable | Google Play Billing (einmaliges In-App-Produkt), Produkt-ID per Build-Konfiguration | ANDROID PLATFORM ADAPTATION |
| Rewarded | Protokoll, **nicht konfiguriert** | Protokoll, **nicht konfiguriert** (kein Werbe-SDK) | MATCH (beide BLOCKED) |
| Gerätespeicher | `volumeAvailableCapacityForImportantUsage` | `StorageStatsManager.getTotalBytes/getFreeBytes` | ANDROID PLATFORM ADAPTATION |
| Haptik | `sensoryFeedback(.impact/.warning)` | `HapticFeedbackType` / `View.performHapticFeedback` | MATCH |
| App-Switcher-Schutz | Overlay bei `scenePhase != active` | Overlay bei `ON_PAUSE` (kein `FLAG_SECURE`: Screenshots erlaubt wie iOS) | ANDROID PLATFORM ADAPTATION |
| Icons | SF Symbols | Material Icons (Rounded) mit ähnlicher Form | ANDROID PLATFORM ADAPTATION |

## 5. Produktlogik (verbindlich, identisch zu iOS)

- Gesten (tatsächliche Fingerbewegung): hoch = Behalten · links = Papierkorb · rechts (gerade/schräg unten ≤ 45°)
  = Später mit Standardtermin (Vorgabe 2 Tage, 1/2/3/7) + Pille „In 2 Tagen wieder · Ändern“ ·
  schräg rechts oben (≈25°–50°) = Drehrad öffnen (noch keine Entscheidung; Bestätigen = Später bis Datum + nächstes Medium,
  Abbrechen = nichts geändert) · runter = vorheriges Medium (keine Entscheidung).
  Totzonen dazwischen. Schwellen: deadZone 14, lockRatio 1,6, unlock 6, commit 110, flick 650/s ab 36 (dp).
- Keine Aktions-Buttons im Feed, außer bei TalkBack/Schaltersteuerung (iOS: VoiceOver/Switch Control).
  Gestenhilfe blendet beim Öffnen 2,6 s ein (abwählbar). Rückgängig in der Kopfzeile.
- Free: 15 **bestätigte** Bereinigungen je lokalem Kalendertag (Reservierung bei Vorbereitung,
  Buchung auf den Reservierungstag). Pro: unbegrenzt, Duplikate, erweiterte Werkzeuge, dauerhafte Routinen,
  keine Werbung. Rewarded: 30 Min., 1×/Tag, nur nach Reward-Callback. Android Free: Werbekarte alle 7 Entscheidungen (s. Abweichungen).
- Später: Termin als lokaler Kalendertag, Gruppen Wieder fällig / Morgen / 7 Tage / ab Datum / ohne Datum.
- Verlauf: Filter Alle / Behalten / Bereinigt / Später; keine „x GB freigegeben“.

## 6. Bewusste, dokumentierte Abweichungen

| Thema | IOS_BEHAVIOR | ANDROID_IMPLEMENTATION | Grund |
|---|---|---|---|
| Fotogrößen | „Größe unbekannt“ (keine öffentliche PhotoKit-API) | MediaStore `SIZE` wird angezeigt, wenn > 0; sonst „Größe unbekannt“ | Plattform liefert echte Werte; Regel „keine erfundenen Zahlen“ bleibt erfüllt |
| Speicherübersicht B | nur Anzahl für Fotos/Videos | Anzahl **und** Summe bekannter Größen („mind.“ bei unbekannten) | wie oben |
| iCloud-Hinweise | „Nur in iCloud“ / „Zuletzt gelöscht“ / iCloud-Sync | „Papierkorb des Systems“ (30 Tage); Cloud-Sync anderer Apps (z. B. Google Fotos) ausdrücklich nicht einsehbar | Plattform |
| Tageslimit-Anzeige | `Heute noch x von 15` | identisch | – |
| Werbung (Free) | iOS: nur Rewarded geplant (Anbieter offen) | **Abweichung (Produktentscheidung 2026-09-30):** native Werbekarte (AdMob) im Feed nach je 7 Entscheidungen; erste 30 Entscheidungen werbefrei, max. 30/Tag (Feed-Karte + Zusammenfassung, 2026-10-02); ruhige Anzeige ganz unten in der Sitzungs-Zusammenfassung (nicht neben dem deutlichen Pro-Angebot); Video-Bonus „+10 für heute“ im Limit-Blatt (1× täglich, nur Reward-Callback, nie unbegrenzt); Karte ist nie eine Entscheidung, zählt nicht fürs Limit, jede Wischrichtung überspringt sie nach 1,5 s, „runter“/Rückgängig sofort; nie während Videowiedergabe (Player stoppt), nie für Pro/Pro-Test. Einwilligung per Google UMP vor dem SDK-Start (`MobileAdsInitProvider` entfernt). Rewarded über AdMob. | Umsatz Free-Stufe; iOS wird ggf. nachgezogen |
| Onboarding + Tutorial | iOS: Datenschutz-Intro + Gestenhilfe | **Abweichung (Produktentscheidung 2026-10-04):** 5-stufiges Onboarding (Geschichte, Üben mit Demo-Stapel ohne Speicherung, Sicherheit, Angebot „kostenlos unbegrenzt + Pro einmalig“ zum Store-Preis, Berechtigungen) und Hinweiskarten auf „Heute“/im Feed; Free-Limit 20/Tag + Video-Bonus 20; warme Farbpalette | Umsatz, Verständnis; iOS ggf. nachziehen |
| Nach dem Leeren | Meldung „N Medien … verschoben“ | **Ergänzung (2026-10-04):** Vollbild „Papierkorb geleert“ mit „gespart“ = Summe der **bekannten Größen tatsächlich entfernter** Elemente (vor dem Löschen gemessen; keine Kandidatengrößen, unbekannt ≠ 0 → „mind.“, ohne bekannte Größe keine Zahl), Kacheln Entfernt / Heute behalten, Summe „insgesamt gespart“ (lokal), Hinweis: Medien liegen im Android-Papierkorb, Platz frei nach dessen Leerung (spätestens 30 Tage); Dateien sofort | Motivation; Ehrlichkeitsregel bleibt |
| Wisch-Schwellen | iOS `GestureConfig`: Commit 110 pt, Schwung 650 pt/s + 36 pt, lockRatio 1.6 | **Abweichung (Gerätebefund 2026-10-04 „zu schwerfällig“):** 72 dp, Schwung 380 dp/s + 20 dp, lockRatio 1.3 (Achse bis ≈ 38°); Totzone rechts ≤ 45° ↔ hoch/runter ab ≈ 52° bleibt; eine Geste = ein Schritt unverändert | Bedienung; iOS ggf. nachziehen |
| Kaufmomente | – | Zusammenfassung: einmalig deutliches Pro-Angebot nach dem ersten Durchgang mit Aussortiertem, danach eine leise Zeile; Limit ab < 5 frei vorab sichtbar; Limit-Blatt: „Alle N entfernen – mit Pro“ primär; leiser Pro-Hinweis auf „Heute“ ab Nutzungstag 2 (nur Free) | Umsatz |
| „Dateien im Container“ (QA) | QA-Fixtures nur in Debug + Startargument | QA-Fixtures nur im `qa`-Build-Typ | Plattform |
| Ordner-Cloud-Anbieter | iCloud Drive abgelehnt | SAF-Anbieter mit `FLAG_VIRTUAL_DOCUMENT`/virtuellen Dateien werden nicht gelöscht; Google-Drive-Tree abgelehnt (Authority `com.google.android.apps.docs.storage`) | Plattform |

Gefundene iOS-Auffälligkeiten (nicht absichtlich nachgebaut):

| # | IOS_BEHAVIOR | EXPECTED_BEHAVIOR | ANDROID_IMPLEMENTATION |
|---|---|---|---|
| 1 | `CleanupBasketView.task(id: basket.count)` setzt die Auswahl bei jeder Anzahländerung auf „alle“ zurück – eine manuelle Abwahl geht verloren, sobald ein anderes Item hinzukommt/wegfällt | Abwahl bleibt für verbleibende Items erhalten, neue Items sind ausgewählt | Auswahl = vorige Auswahl ∩ Korb ∪ neue Items |
| 2 | `PhotoLibraryService.delete` meldet `.partial(requested)` wenn nicht alle IDs löschbar sind, ohne Nachprüfung | Nur tatsächlich entfernte Items zählen | Nach Systemdialog Existenzprüfung je Item → `partial(removed)` |
| 3 | `LargeFilesView` zählt als „unbekannt“ nur `size == .unknown`, Mindestgröße gilt nur für bekannte | identisch (korrekt) | identisch |
| 4 | `AppModel.onForeground` ruft bei **jedem** Resume `recoverAfterLaunch` + `resolveReconciliation`. Kehrt die App während des System-Löschdialogs in den Vordergrund zurück, wird eine laufende `submitted`-Operation als `reconciliationRequired` markiert und (Items existieren noch) sofort als `failed` abgeschlossen; die spätere Bestätigung wird dann verworfen | Abgleich nur nach echtem Neustart bzw. nie während einer laufenden Operation | Einmal je Prozessstart und nie während `runCleanup` (`recoveredThisProcess`, `cleanupInFlight`) |
| 5 | Der erste über den Dateibrowser verbundene Ordner heißt immer „Downloads“ (`asDownloads: importAsDownloads && downloadsSource == nil`, `importAsDownloads` ist immer `true`) – auch wenn es z. B. „Rechnungen“ ist | Titel „Downloads“ nur für eine Downloads-Quelle | Nur Ordner namens „Download“/„Downloads“ werden als Downloads-Quelle markiert |
| 6 | `DecisionRules.transition` lehnt jede Aktion auf `.removed` ab, `normalQueue` schließt `.removed` aus. Holt jemand ein entferntes Medium aus „Zuletzt gelöscht“/dem System-Papierkorb zurück (gleiche ID), taucht es nie wieder auf | Ein wieder vorhandenes Medium ist wieder offen und neu entscheidbar | Kandidaten sind nur vorhandene Medien → `REMOVED` gilt dort als offen; Behalten/Papierkorb/Später erlaubt (Verlauf: Entscheidung), Rückgängig führt zurück auf „entfernt“; Termin-/Markierungsaktionen bleiben ungültig (Gerätetest Lauf 4, K5) |

Android-spezifische Befunde während des Ports (behoben):

| # | Befund | Behebung |
|---|---|---|
| A1 | Feed-Animationen im `viewModelScope` (keine `MonotonicFrameClock`) → jede Entscheidung wäre auf dem Gerät mit „nicht gespeichert“ zurückgerollt worden (gefunden durch Compose-UI-Test) | Animationen laufen im Kompositions-Scope; ohne UI wird gesprungen |
| A2 | `SourceRow`: langer Detailtext drückte den Titel auf schmalen Geräten auf ~0 Breite | Detailspalte höchstens so breit wie der Titel (`weight(1f, fill = false)`) |

Weitere Android-Anpassungen ohne iOS-Gegenstück:

| Thema | Android | Grund |
|---|---|---|
| Wischaktionen in Listen (iOS `swipeActions`, ohne Vollwisch) | Sichtbares „⋯“-Aktionsmenü je Zeile (Papierkorb, Später, Dateien, Große Dateien) bzw. sichtbare Buttons (Quellen, Regeln) | Android-Listen haben keine Standard-Wischaktionen; Menü ist zugänglicher und verhindert versehentliche Aktionen |
| Drehrad | `android.widget.NumberPicker` (natives Rad), Kalender = Material-DatePicker | nativ |
| Wegwischen des Drehrad-Sheets | wie „Abbrechen“ → nichts geändert | iOS sperrt das Wegwischen (`interactiveDismissDisabled`) |
| Ablauf schräg rechts oben | **Abweichung von iOS (Produktentscheidung des Nutzers, 2026-09-29):** Öffnen des Drehrads ist keine Entscheidung; erst Bestätigen speichert „Später bis Datum“ (Later + Schedule mit `amends`, ein Undo) und blättert weiter; Abbrechen/Zurück/Wegwischen ändert nichts. Bindung an Item + Revision beim Öffnen. | iOS speichert sofort „Später“ und setzt beim Abbrechen den Standardtermin – versehentliches Treffen der Diagonale erzeugte eine Entscheidung. iOS wird nachgezogen. |
| Hash-Cache (Masterprompt §22) | **kein** persistenter Hash-Cache | iOS hält SHA-256 nur im Speicher (Datenschutzkarte); Teilhash + Größenfilter begrenzen den Aufwand |
| Reduce Motion | „Animationen entfernen“ (`ANIMATOR_DURATION_SCALE = 0`) | Android-Äquivalent |
| Erhöhter Kontrast | Android 14+ `UiModeManager.contrast ≥ 0,5` | Android-Äquivalent |
| UI-Polish (Auftrag „Gezielter UI-Polish“, nur Android) | Heute: Review-Karte zuerst mit Hauptaktion, Zähler als Zahl + Plural; Feed ohne weiße Karte, Rahmen im Seitenverhältnis, Infozeile; Später mit Medientyp/Termin; Verlauf fasst Später + ersten Termin zusammen; Pro mit drei echten Kernvorteilen; Gestenlegende als Liste | ausdrücklicher Auftrag des Nutzers; Verhalten, Gesten und Datenmodell unverändert. Details: `docs/ANDROID_UI_POLISH_REPORT.md` |
| Umbau 2026-09-29 (Produktentscheidung, nur Android) | Diagonale ↗ entfällt (rechts bis 45° = Später, Termin über „Ändern“); Tipp aufs Video = Pause/Play + dünne Fortschrittsleiste; keine Richtungs-Kapsel; „Regeln & Routinen“ → **Sammlungen** (dauerhafte Filter, fertige gratis, eigene Pro); Tab „Ordnen“ entfällt, Sammlungen als Reiter in „Aufräumen“; Tabbar nur auf Tab-Startseiten; Navigation schiebt statt überblendet | Nutzerwunsch nach Gerätetest; iOS folgt. Details: `docs/REWORK_2026-09-29.md` |
| Ausrichtung | Hochformat gesperrt (`screenOrientation="portrait"`). Ab Android 16 ignoriert das System die Sperre auf großen Bildschirmen (≥ 600 dp): Tablets und aufgeklappte Foldables drehen weiter; seitliche Safe-Insets bleiben dafür aktiv | Produktentscheidung 2026-09-29: der Feed ist vertikal, Querformat bringt auf dem Telefon keinen Nutzen |
| Navigation/Rückmeldung (Nutzerwunsch nach Gerätevideo) | Zurück und Tabwechsel sofort, Öffnen 150-ms-Fade, `enableOnBackInvokedCallback=false` (keine schrumpfende Zurück-Vorschau); dezente Richtungs-Pille beim Wischen wieder da (hoch/links/rechts) | Schrumpf-/Schiebe-Effekt beim Zurück wirkte unruhig; ohne Pille fehlte Orientierung beim Wischen |
| Sprachen (2026-10) | 16 Sprachen aus dem Übersetzungspaket, Fallback Englisch (`values`), Deutsch in `values-de`, nach dem Leeren „gespart“ (de) / „saved“ (en), App-Sprache über Android 13+ (`localeConfig`); Größen-Einheiten und Datumsformate je Sprache; RTL ohne gespiegelte Gestenpfeile | Nutzerauftrag; Längenhinweise in `docs/i18n/UI_Laengenpruefung.json` |

## 7. Blocker / Plattformgrenzen

- Kein KVM im Build-Container: **kein Emulator, kein Gerät**. UI-Tests und Screenshots laufen auf der JVM
  (Robolectric + Roborazzi). Das ist keine Geräteabnahme.
- Keine Play-Console-Konfiguration: Produkt-ID fehlt → „Käufe sind in dieser Version noch nicht verfügbar“ (wie iOS).
- Kein Werbeanbieter entschieden (wie iOS) → Rewarded „nicht verfügbar“.
- Application-ID `app.photomaid.android` ist ein **Platzhalter** (iOS: `app.photomaid.ios`, ebenfalls Platzhalter).

## Produkt-UX-Pass 2026-09-29 (Android zuerst, iOS noch offen)

| Thema | Android | Grund |
|---|---|---|
| Aktionsleiste im Feed (♡, Teilen, Ordner, ⋯) | neu | Produktentscheidung Masterprompt; iOS-Port offen |
| Favorit | MediaStore `IS_FAVORITE` + `createFavoriteRequest` (Systembestätigung) | iOS wäre `PHAsset.isFavorite` über `PHAssetChangeRequest` |
| Favoriten schützen | Einstellung, Standard an | neu |
| Album | nur „In Ordner verschieben“ (`createWriteRequest` + `RELATIVE_PATH`) | Android hat kein universelles Album-API; iOS hätte echte Alben (`PHAssetCollection`) |
| Papierkorb-Vorschau, Limit vor Bestätigung | neu | Masterprompt §15/§16 |
| Entitlements | `FREE`/`TEMPORARY_PRO`/`LIFETIME_PRO` explizit | fachlich identisch zu iOS (Vorschau ändert Limit nicht) |
