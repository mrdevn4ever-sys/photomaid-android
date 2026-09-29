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
  schräg rechts oben (≈25°–50°) = Später mit Drehrad · runter = vorheriges Medium (keine Entscheidung).
  Totzonen dazwischen. Schwellen: deadZone 14, lockRatio 1,6, unlock 6, commit 110, flick 650/s ab 36 (dp).
- Keine Aktions-Buttons im Feed, außer bei TalkBack/Schaltersteuerung (iOS: VoiceOver/Switch Control).
  Gestenhilfe blendet beim Öffnen 2,6 s ein (abwählbar). Rückgängig in der Kopfzeile.
- Free: 15 **bestätigte** Bereinigungen je lokalem Kalendertag (Reservierung bei Vorbereitung,
  Buchung auf den Reservierungstag). Pro: unbegrenzt, Duplikate, erweiterte Werkzeuge, dauerhafte Routinen,
  keine Werbung. Rewarded: 30 Min., 1×/Tag, nur nach Reward-Callback.
- Später: Termin als lokaler Kalendertag, Gruppen Wieder fällig / Morgen / 7 Tage / ab Datum / ohne Datum.
- Verlauf: Filter Alle / Behalten / Bereinigt / Später; keine „x GB freigegeben“.

## 6. Bewusste, dokumentierte Abweichungen

| Thema | IOS_BEHAVIOR | ANDROID_IMPLEMENTATION | Grund |
|---|---|---|---|
| Fotogrößen | „Größe unbekannt“ (keine öffentliche PhotoKit-API) | MediaStore `SIZE` wird angezeigt, wenn > 0; sonst „Größe unbekannt“ | Plattform liefert echte Werte; Regel „keine erfundenen Zahlen“ bleibt erfüllt |
| Speicherübersicht B | nur Anzahl für Fotos/Videos | Anzahl **und** Summe bekannter Größen („mind.“ bei unbekannten) | wie oben |
| iCloud-Hinweise | „Nur in iCloud“ / „Zuletzt gelöscht“ / iCloud-Sync | „Papierkorb des Systems“ (30 Tage); Cloud-Sync anderer Apps (z. B. Google Fotos) ausdrücklich nicht einsehbar | Plattform |
| Tageslimit-Anzeige | `Heute noch x von 15` | identisch | – |
| „Dateien im Container“ (QA) | QA-Fixtures nur in Debug + Startargument | QA-Fixtures nur im `qa`-Build-Typ | Plattform |
| Ordner-Cloud-Anbieter | iCloud Drive abgelehnt | SAF-Anbieter mit `FLAG_VIRTUAL_DOCUMENT`/virtuellen Dateien werden nicht gelöscht; Google-Drive-Tree abgelehnt (Authority `com.google.android.apps.docs.storage`) | Plattform |

Gefundene iOS-Auffälligkeiten (nicht absichtlich nachgebaut):

| # | IOS_BEHAVIOR | EXPECTED_BEHAVIOR | ANDROID_IMPLEMENTATION |
|---|---|---|---|
| 1 | `CleanupBasketView.task(id: basket.count)` setzt die Auswahl bei jeder Anzahländerung auf „alle“ zurück – eine manuelle Abwahl geht verloren, sobald ein anderes Item hinzukommt/wegfällt | Abwahl bleibt für verbleibende Items erhalten, neue Items sind ausgewählt | Auswahl = vorige Auswahl ∩ Korb ∪ neue Items |
| 2 | `PhotoLibraryService.delete` meldet `.partial(requested)` wenn nicht alle IDs löschbar sind, ohne Nachprüfung | Nur tatsächlich entfernte Items zählen | Nach Systemdialog Existenzprüfung je Item → `partial(removed)` |
| 3 | `LargeFilesView` zählt als „unbekannt“ nur `size == .unknown`, Mindestgröße gilt nur für bekannte | identisch (korrekt) | identisch |

## 7. Blocker / Plattformgrenzen

- Kein KVM im Build-Container: **kein Emulator, kein Gerät**. UI-Tests und Screenshots laufen auf der JVM
  (Robolectric + Roborazzi). Das ist keine Geräteabnahme.
- Keine Play-Console-Konfiguration: Produkt-ID fehlt → „Käufe sind in dieser Version noch nicht verfügbar“ (wie iOS).
- Kein Werbeanbieter entschieden (wie iOS) → Rewarded „nicht verfügbar“.
- Application-ID `app.photomaid.android` ist ein **Platzhalter** (iOS: `app.photomaid.ios`, ebenfalls Platzhalter).
