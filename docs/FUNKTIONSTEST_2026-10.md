# PhotoMaid Android – Funktionstest aller Bereiche (2026-10-07)

Auftrag: jede Funktion der App einmal durchtesten, Texte menschlicher machen, Rundgang durch alle Bereiche.
Grundlage: `docs/FUNKTIONS_PRUEFPLAN_2026-10.md` (jede ID dort hat hier eine Zeile).
Prüfstatus: **PASS / FAIL / NOT RUN / BLOCKED**. Alles hier läuft automatisch auf der JVM (Robolectric). Das ist keine
Geräteabnahme: was nur echte Hardware zeigt, steht als **NOT RUN (Gerät)** in der Restliste.

Gesamtlauf (Stand `53d8ced`): Core **227/227 PASS** · App **567/567 PASS** · Lint **0 Fehler** · `assemblePerf` PASS. APK `PhotoMaid-af55578.apk` (perf, SHA-256 `697684a9…3347`, Zertifikat `c408b7d4…`). Geräteprüfung: NOT RUN.

## 1. Was neu ist

| Neu | Was es prüft | Ergebnis |
|---|---|---|
| `AppCrawlTest` (Free + Pro) | Tippt auf **jeden** Einstieg auf allen fünf Tabs (Heute 9, Fotos 13, Aufräumen 11, Studio 13, Verlauf 4 = 50 je Lauf), wartet, geht zurück. Kein Absturz, jeder Einstieg öffnet etwas, und das **Ledger bleibt leer** (Öffnen ist nie eine Entscheidung). | PASS 2/2 |
| `SettingsFunctionTest` | Jeder Schalter in den Einstellungen ändert wirklich seinen Wert. „Endgültig löschen“ zeigt erst die Erklärung; Abbrechen lässt es aus, Bestätigen schaltet es ein. Standardtermin „Später“ = 7 Tage, Erscheinungsbild = Dunkel, Einstiege öffnen nur Ansichten. Hilfe › „Rundgang und Tipps noch einmal zeigen“ setzt alle sieben Hinweise zurück. | PASS 2/2 |
| `FunctionGapTest` | Favorit im Betrachter (System-Favorit an/aus, Ablehnung zeigt Hinweis, keine Entscheidung) · Verlauf-Filter · Speicherübersicht (echte Anzahl und Größe, unbekannt ≠ 0) · Schwelle „Große Dateien“ (500 MB / 1 GB / 100 MB, gemerkt) · Studio › PDF und Diashow (Auswahl → richtige Seite) · Fotos › Auswahl › ⋯ › PDF · Später › „Jetzt ansehen“ (ein Element bzw. alle fälligen) | PASS 7/7 |
| `ShareEntryTest` (erweitert) | Teilen-Ziel › PDF übernimmt genau die geteilten Fotos | PASS 11/11 |
| `EditorTest.stripLocationToolPathIsLosslessAndRemovesOnlyGps` | „Standort entfernen“ auf demselben Weg wie das Werkzeug: Bild bitgenau gleich, nur GPS weg, Aufnahmezeit bleibt, Original unverändert | PASS (nach Fehlerbehebung, siehe 2) |
| `OnboardingTest` (angepasst) | Rundgang mit 8 Schritten: Heute, Fotos, Aufräumen, Studio, Verlauf, Einstellungen | PASS |

## 2. Gefundene Fehler und Behebungen

| Nr. | Fehler | Folge für Nutzer | Behebung | Status |
|---|---|---|---|---|
| B1 | **„Standort entfernen“ kodierte JPEGs neu.** Der verlustfreie Weg (Bytes kopieren, nur EXIF ändern) griff nur, wenn das Foto zusätzlich gedreht wurde. Ohne Drehung lief jedes Foto durch die normale Neukodierung. | Kopie leicht schlechter und anders groß als nötig, obwohl nur der Standort weg sollte | `ImageEditEngine.export`: der verlustfreie Weg gilt jetzt auch, wenn das Rezept nichts am Bild ändert (`isIdentity`). Neuer Test sichert bitgleiche Pixel. | PASS |
| B2 | `FeedAdsTest.nextCardAfterSevenMoreDecisions` und `PremiumPassTest.gestureHintShownOnFirstFeedOnly` liefen in der Gesamtsuite je einmal in die 30-s-Grenze, allein PASS | keine (nur Testlast) | Wartezeit 60 s wie bei `MonetizationTest` | PASS |
| B4 | `G4Test` suchte noch „Medien endgültig löschen“; der Text heißt jetzt „Elemente endgültig löschen“ (T2) | keine (Testtext veraltet) | Test angepasst | PASS |
| B3 | Tab-Leiste hatte keine Test-Kennungen | keine | `apptab.<TAB>` je Tab (nur Semantik) | PASS |
| B5 | **Gespeicherte Zahlen hingen von der Gerätesprache ab** (gefunden beim Paket-5-Screenshot auf Arabisch): `String.format` schrieb arabisch-indische Ziffern in den Schlüssel des Tageszählers (`LocalDay`) und in den Monat für „Weitermachen“ (`MonthKey`) | Arabisch: „Weitermachen“ fand den Monat nicht wieder; nach einem Sprachwechsel konnte es den Tageszähler doppelt geben | Immer ISO/ASCII (`LocalDate`/`YearMonth.toString`); ältere Einträge werden weiter gelesen, beim Tageszähler gilt der ASCII-Schlüssel | PASS |

Sonst hat kein Test einen Fehler der App gezeigt.

## 3. Jede Funktion → Test → Status

| ID | Funktion | Test(s) | Status |
|---|---|---|---|
| A1–A2 | Medienfläche fest, nichts über dem Inhalt | StageConsistencyTest (10), ToolZoneVisibilityTest (8) | PASS |
| A3 | Bedienbar (≥ 48 dp) | TouchTargetTest (alle Bildschirme) | PASS |
| A4 | Screenshot-Raster | ScreenshotTest (212), RasterShotsTest, EditorShotsTest | PASS (Sichtprüfung JVM) |
| A5 | Bestehende Suites | Gesamtlauf oben | siehe oben |
| S1 | Einführung O1–O5 | OnboardingTest.fullFlowPracticeSavesNothingAndOfferShowsStorePrice, skipJumpsToOfferAndBackReturns | PASS |
| S2 | Angebotsseite | OnboardingTest (Angebot, Lifetime überspringt) | PASS |
| S3 | Zugriff erlauben/ablehnen/teilweise | AppFlowsTest.permission*, DeviceFindingsTest.limitedAccessShowsHint | PASS |
| S4 | Rundgang + Tipps | OnboardingTest (8 Schritte), PlanGapTest.coachMarksShowOnceAndAreRemembered, **SettingsFunctionTest.helpResetsEveryTour** | PASS |
| H1 | Hero | QuickAccessUiTest.oneDecisionUpdatesEverySourceAndHeroCountsOnce, ReviewSourcesTest | PASS |
| H2 | Wieder fällig | ReviewSourcesTest.testC_laterHidesUntilDueThenReviewableAgain | PASS |
| H3 | Papierkorb-/Später-Zeile | PlanGapTest.todayRowsOpenTheirScreens, **AppCrawlTest** | PASS |
| H4 | Schnellzugriffe | QuickAccessUiTest (8) | PASS |
| H5 | Speicherzeile → Übersicht | PlanGapTest.todayRowsOpenTheirScreens, **FunctionGapTest.storageOverviewShowsRealCountsAndSizes** | PASS |
| H6 | Pro-Hinweis | PurchaseMomentsTest.todayProHintOnlyFromDayTwoForFree | PASS |
| F1–F5 | Wische hoch/links/rechts/runter, Totzonen | FeedFlowTest (14), core GestureMachineTest | PASS |
| F6 | Richtungs-Pille | PremiumPassTest.directionPillWhileDragging | PASS |
| F7 | Rückgängig | FeedFlowTest.undoRestoresPreviousState, ReviewSourcesTest | PASS |
| F8 | Video im Feed | ReworkTest.singleTap*, VideoSeekTest, StageConsistencyTest.feed* | PASS (echte Wiedergabe: NOT RUN) |
| F9 | Aktionsleiste | FeedActionsTest (16) | PASS |
| F10 | Tasten zum Entscheiden | ExpansionUiTest.decisionButtons*, **SettingsFunctionTest** (Schalter) | PASS |
| F11 | Werbekarte | FeedAdsTest (10), core FeedAdPolicyTest | PASS (echte AdMob: NOT RUN) |
| F12 | Zusammenfassung | FeedFlowTest.sessionEndShowsRealSummary, MonetizationTest | PASS |
| F13 | Tageslimit + Bonus | BasketTest, MonetizationTest.bonus* | PASS |
| F14 | Reihenfolge | ExpansionUiTest.orderOldestFirstThenKeepHitsOldest | PASS |
| C1 | Hub/Kategorien | HubPendingTest (6), **AppCrawlTest** | PASS |
| C2 | Sammlungen | ReworkTest.*Collection* | PASS |
| C3 | Monate / Weitermachen / Neu seit | ExpansionUiTest.months*, todayShowsContinueAndNewSince | PASS |
| C4 | Serienvergleich | ExpansionUiTest.series*, StageConsistencyTest.seriesCompare* | PASS |
| C5 | Raster/Mehrfachauswahl | ExpansionUiTest.gridMovesSelectionWithoutDecisions | PASS |
| C6 | Große Dateien, Grenze änderbar | PlanGapTest.largeFilesSeparateUnknownSizes, **FunctionGapTest.largeFilesThresholdFiltersAndIsRemembered** | PASS |
| C7 | Exakte Duplikate | AppFlowsTest.proUnlockedAllowsExactDuplicates, core DuplicateRulesCapabilityTest | PASS |
| C8 | Dateien/Quellen (SAF) | FileAliasTest | PASS (echte Ordnerauswahl: NOT RUN) |
| C9 | Speicherübersicht | **FunctionGapTest.storageOverviewShowsRealCountsAndSizes** | PASS |
| C10 | Ausgenommene Ordner | ExpansionUiTest.excludeFolder*, FoldersTest.excludedFolderStaysVisibleInLibraryButNotInCleanup | PASS |
| P1–P5 | Papierkorb, Leeren, Endgültig, Android-Papierkorb, RAW | G4Test (5), BasketTest (4), AppFlowsTest.confirm*/cancelled* | PASS (Systemdialoge: NOT RUN) |
| L1 | Später-Liste | PlanGapTest.laterRemoveKeepsTheFile, CustomLaterTest (5), **FunctionGapTest.laterReviewNowOpensOnlyThatItemAndDueButtonOpensAllDue** | PASS |
| L2 | Später ohne Termin | ExpansionUiTest.laterWithoutDateIsBookmarkedAndListed | PASS |
| L3 | Erinnerung | – | NOT RUN (Gerät: echte Benachrichtigung) |
| V1 | Verlauf + Filter | HistoryGroupingTest (4), PremiumPassTest.historyEmptyState, **FunctionGapTest.historyFiltersShowOnlyMatchingEvents** | PASS |
| V2 | Bearbeitet | EditorTest.editsListRestoresReplacedOriginal | PASS |
| G1–G3 | Zeitachse, Suche, Auswahl | LibraryTest (5), **FunctionGapTest.librarySelectionOpensPdfWithChosenPhotos** | PASS |
| G4–G5 | Betrachter, Leisten per Tipp | LibraryTest.viewerPages*, StageConsistencyTest.viewer* | PASS |
| G6 | Favorit im Betrachter | **FunctionGapTest.viewerFavoriteWritesSystemFavoriteAndIsNoDecision** | PASS (Systemdialog: NOT RUN) |
| G7 | Info | PlanGapTest.viewerInfoShowsDetails | PASS |
| G8–G10 | Ordner, Favoriten, Ausblenden | FoldersTest (10) | PASS |
| G11–G12 | Diashow, Kopfhörertasten | SlideshowUiTest (6) | PASS (echte Kopfhörer: NOT RUN) |
| O1–O5 | Alben, Schlagwörter, Notiz, Auswahlkorb, Wiederfinden | OrganizeUiTest (7), OrganizeStoreTest, core OrganizeTest | PASS |
| O6 | Update von Version 1 | OrganizeStoreTest.migrationFromVersion1KeepsLedgerData | PASS |
| W1–W5 | Abläufe: Einstiege, Editor, Vorschau, Zielgröße, Nur teilen | WorkflowRunTest (8), core WorkflowTest | PASS |
| D1 | PDF-Einstiege (Studio, Auswahl, Teilen) | **FunctionGapTest.studioPdfAndSlideshowEntriesPickThenOpen**, **librarySelectionOpensPdfWithChosenPhotos**, **ShareEntryTest.shareScreenOffersActionsAndBasketIsNoDecision** | PASS |
| D2–D5 | Seiten, Vorschau = Ergebnis, Speichern/Teilen, Datenschutz | PdfExportTest (6), PdfShotsTest (4), core PdfTest | PASS |
| SH1–SH3 | Senden an PhotoMaid, nicht in der Mediathek, Direktes Teilen | ShareEntryTest (11), core ShareTest | PASS (echtes Teilen aus anderer App: NOT RUN) |
| SH4 | App-Shortcuts | ShareEntryTest.shortcutsOpenOnlyViews | PASS (Launcher: NOT RUN) |
| T1–T2 | Studio, Medienauswahl | ToolsTest.studioOffersToolsThroughThePicker, CollageScreenTest, **AppCrawlTest** | PASS |
| T3–T4 | Collage, Entwürfe | CollageScreenTest (8), StageConsistencyTest.collage* | PASS |
| T5 | Video zusammenfügen | MovieTest (3) | PASS |
| T6 | Diashow als Video | MovieTest, **FunctionGapTest.studioPdfAndSlideshowEntriesPickThenOpen** | PASS |
| T7–T8 | Verkleinern, Umwandeln | ToolsTest | PASS |
| T9 | Standort entfernen | ToolsTest.freeBatchIsLimitedAndGifNeedsPro, **EditorTest.stripLocationToolPathIsLosslessAndRemovesOnlyGps** | PASS (nach B1) |
| T10–T12 | Originale in den Papierkorb, Zusammenfügen, GIF aus Fotos | ToolsTest | PASS |
| T13 | GIF aus Video | PlanGapTest.gifFromVideoUsesFramesOfTheChosenPart | PASS (echte Videobilder: NOT RUN) |
| T14 | Teilen ohne Speichern | StudioFoundationTest.shareOnlyCreatesNoGalleryCopy | PASS |
| E1 | Bühne groß, Werkzeuge schmal (wie Fotos-App) | EditorLayoutPolicyTest (4), ToolZoneVisibilityTest (8), EditorShotsTest (7) | PASS |
| E1a | Zoom mit zwei Fingern | EditorZoomTest (3) | PASS (echte Finger: NOT RUN) |
| E2–E11 | Zuschneiden … Histogramm | EditorTest (20), StudioFoundationTest (9), RetouchTest (6), MarkupTest (6) | PASS |
| EV1–EV8 | Video-Editor | VideoEditorTest (7), EditorTest.trimVideo* | PASS (Export-Benachrichtigung: NOT RUN) |
| X1 | Pro-Seite | AppFlowsTest.proLockedAndUnlocked, **AppCrawlTest…Pro** | PASS (echter Kauf: BLOCKED, Testgerät ohne Kauf) |
| X2 | Pro-Test 30 min | RewardedProTest (9) | PASS |
| X3 | Einwilligung/Werbung | FeedAdsTest, MonetizationTest | PASS (echte UMP/AdMob: NOT RUN) |
| X4 | Einstellungen | **SettingsFunctionTest.everySettingChangesItsValue**, FeedActionsTest.protectionDefaultsOnAndPersists, G4Test | PASS |
| X5–X6 | Dunkel, Schrift 200 % | RasterShotsTest, ScreenshotTest | PASS |
| X7 | TalkBack | ExpansionUiTest.decisionButtons* | PASS (echtes TalkBack: NOT RUN) |
| X8 | Arabisch (RTL) | RasterShotsTest.arabic | PASS |
| X9 | Querformat/Tablet | ScreenshotTest m_tablet*, m_fold_* | PASS |
| X10 | Zurück | DeviceFindingsTest.closingFeedReturnsToTodayRepeatedly, **AppCrawlTest** (50 × Zurück je Lauf) | PASS |

### Paket 5 (Auslagern, Sichern) – 2026-10-08

| ID | Funktion | Test(s) | Status |
|---|---|---|---|
| AR1 | Einstiege (Studio, Auswahl › ⋯, Album/Schlagwort/Korb › ⋯) | ArchiveUiTest.entriesFromStudioSelectionAndAlbumOpenArchiving, selectionMenuOpensArchiving | PASS |
| AR2 | Ziel einordnen, freier Platz | core ArchiveTest.targetKindSeparatesDeviceRemovableAndOtherApps, spaceIsOnlyEnoughWhenEverythingIsKnown; ArchiveUiTest.sameDeviceWarnsAndOffersNoTrash, notEnoughSpaceBlocksTheStartAndFreeIsLimited | PASS (echte SD/USB/Cloud: NOT RUN) |
| AR3 | Kopieren und prüfen, Ordner, Prüfliste | ArchiveEngineTest.copiesChecksEveryFileAndKeepsOriginals, aCopyThatDiffersIsRemovedAndNeverCounts; core listIsValidCsvAndNeverAFormula, folderNamesNeverDependOnTheDeviceLanguage | PASS |
| AR4 | Abbruch, Unterbrechung, voll | ArchiveEngineTest.cancelInTheMiddleRemovesThePartialCopy, afterARestartOnlyCheckedCopiesCountAndTheHalfCopyIsRemoved, fullDestinationStopsCleanly | PASS (Stick abziehen: NOT RUN) |
| AR5 | Originale in den Papierkorb (nur auf Tipp) | ArchiveEngineTest.originalsGoToTrashOnlyOnRequestOnlyFromOwnStorageAndFavoritesStay, ArchiveUiTest.copyAndCheckThenOfferTrashOnlyOnRequest | PASS |
| AR6 | Zweiter Durchgang | ArchiveEngineTest.secondRunFindsIdenticalFilesAndNeverOverwritesADifferentOne | PASS |
| BK1 | Sichern | ArchiveEngineTest.backupMovesToANewPhoneAndNeverOverwrites (Inhalt), ArchiveUiTest.backupPage… | PASS (echte Dateiauswahl: NOT RUN) |
| BK2 | Wiederherstellen auf neuem Gerät | ArchiveEngineTest.backupMovesToANewPhoneAndNeverOverwrites, core restoreFindsMediaOnANewPhoneAndNeverOverwrites | PASS |
| BK3 | Nichts überschreiben, „Endgültig löschen“ nie | dieselben + notABackupAndTooNewAreRecognised | PASS |
| – | Tasten ≥ 48 dp, Aufnahmen hell/dunkel/200 %/Arabisch | TouchTargetTest („Auslagern“, „Sichern“), ArchiveShotsTest (6) | PASS (Sichtprüfung JVM) |

## 4. Texte und Rundgang

- **Rundgang (T1):** 8 Schritte über alle Bereiche (Heute, Fotos, Aufräumen, Studio, Verlauf, Einstellungen), jederzeit
  wieder über Einstellungen › Hilfe › „Rundgang und Tipps noch einmal zeigen“. Test: OnboardingTest, SettingsFunctionTest.
- **Texte (T2):** 8 neue und 42 überarbeitete Texte, alle in 16 Sprachen. Fachbegriffe wie „Neukodierung“, „Bitrate“, „HDR-Gainmap“,
  „EXIF“, „Ledger“ und „Medium“ sind durch Alltagssprache ersetzt („Video wird neu gespeichert“, „Standort- und Kameradaten“, „Foto/Video“).

## 5. Restliste: nur auf dem Gerät prüfbar (NOT RUN)

Echte Videowiedergabe und Videobilder (F8, G5, T13), Benachrichtigungen (L3, EV8), Android-Systemdialoge (P2–P4, G6, T10, E9),
echter Kauf (X1, BLOCKED: Testgerät ohne echte Käufe), UMP/AdMob (X3), TalkBack (X7), Teilen aus anderer App und
Launcher-Shortcuts (SH1, SH4), Ordnerauswahl über SAF (C8), Zoom mit echten Fingern (E1a), Kopfhörertasten (G12).
Bekannte Fehler auf dem Gerät: keine gemeldet.
