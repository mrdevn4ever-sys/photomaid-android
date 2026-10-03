# Editor-Redesign 2026-10 – Abschlussbericht

Ausgangsstand: `b27fbf4` (identisch mit dem weitergeleiteten Bericht; Arbeitsbaum sauber). Branch `claude/product-ux-favorites`.
Projektregeln: `CLAUDE.md` (kein `AGENTS.md` vorhanden). Plan/Prüflog: `docs/FUNKTIONS_PRUEFPLAN_2026-10.md`, Issue #3.
Status immer PASS / FAIL / NOT RUN / BLOCKED. JVM-Screenshots und Robolectric-Tests sind keine Geräteabnahme.

## 1. Was neu ist (Komponenten)

| Komponente | Datei | Aufgabe |
|---|---|---|
| `EditorPalette` + `EditorTheme` | `design/EditorChrome.kt` | Ruhige Arbeitsfarben hell/dunkel (Hintergrund, Fläche, Text, Nebentext, neutrale Bühne). Die bestehenden Panels lesen weiter `PM.colors`; Akzent und Signalfarben bleiben PhotoMaid. |
| `EditorLayoutPolicy` | `design/EditorChrome.kt` | Eine Policy für alle Editoren: Kopfzeile, Bühne, Hilfsleiste, Werkzeugzone, Navigation aus Fenstergröße, Schrift und Tastatur – nie aus dem Medium. Modi: gestapelt, seitlich (breite niedrige Fenster), Tastatur. Ersetzt die getrennten 56-%-/50-%-Werte. |
| `EditorScaffold` | `design/EditorChrome.kt` | Gemeinsames Gerüst (Insets, Bühne ohne Rundung/Schatten, Hilfsleiste, scrollende Werkzeugzone mit fester Höhe, Navigation). |
| `EditorTopBar`, `EditorPrimaryButton`, `CancelButton` | `design/` | Abbrechen links, optionaler Titel (weicht zuerst), Speichern rechts (eine Zeile). |
| `EditorActionButton`, `EditorHoldButton`, `EditorStatus` | `design/EditorChrome.kt` | Hilfsleiste außerhalb der Bühne: 22-dp-Symbole, 48×48-dp-Ziele, Zustand über Fläche + Semantik (nicht nur Farbe); Halten = Vergleich, TalkBack: Antippen schaltet um. Zeile scrollt bei großer Schrift statt abzuschneiden. |
| `EditorDock` | `design/EditorChrome.kt` | Werkzeugnavigation: vier Hauptwerkzeuge + „Mehr“ (Runde 2), feine Trennlinie oben, aktiver Eintrag mit Akzent + Balken. Bei normaler Schrift passt alles; bei großer Schrift scrollt die Leiste und holt den aktiven Eintrag automatisch ins Bild. Nie kleinere Schrift je Eintrag, keine zweite Reihe. |
| `EditorSubTabs` | `design/EditorChrome.kt` | Ruhige Textreiter (Runde 2): Gruppen in Anpassen (Licht · Farbe · Farbmischer · Detail · Effekte) und die Werkzeuge unter „Mehr“; aktiv = Text kräftig + Akzentstrich, Punkt = geändert. |
| `EditorToolButton` | `design/EditorChrome.kt` | Ein Stil für alle Einzelwerkzeuge (Anpassen, Markieren): feiner Ring, aktiv = Akzentring/-symbol/-beschriftung, Punkt = geändert (TalkBack: „geändert“). Ersetzt die großen gefüllten blauen Kreise. |
| `EditorWideButton` | `design/EditorChrome.kt` | Flache Haupt-/Nebentaste ohne Verlauf/Glanz (Speichern, Collage speichern, Pro freischalten, Nur teilen). |
| `EditorExportPage` | `design/EditorChrome.kt` | Speichern als eigener Schritt (ganze Ansicht statt Blatt über dem Bild). |
| `VideoPreview` / `rememberVideoPreview` / `VideoTransport` | `feature/editor/VideoEditor.kt` | Ein Player je Editor (bleibt über Werkzeugwechsel inkl. Markieren erhalten); Play/Zeit/Vorschau-stumm/Standbild in der Hilfsleiste. |

Entfernt: `FitLabel` (Schriftverkleinerung je Beschriftung), `EditorTabs`, `CollageTabs`, Overlays auf der Bühne (Histogramm-/Warn-Tasten, Zoom-/Original-Kapsel, Film-Zeitkapsel), abgerundete Masken um Video/Film-Vorschau.

## 2. Zuordnung alt → neu → Funktions-ID / Test

| Bereich (ID) | Vorher | Nachher | Tests |
|---|---|---|---|
| Foto-Editor (E1–E11) | eigene Spalte, Bühne 56 %, Größenzeile oben, Undo/Redo im Kopf, Histogramm/Warnung als Tasten auf dem Bild | `EditorScaffold`; Undo/Redo, Status (Größe/Zoom/Original), Vergleich, Histogramm, Warnung in der Hilfsleiste; Histogramm-Anzeige in der Werkzeugzone | EditorTest (19), StudioFoundationTest (9), RetouchTest (6), MarkupTest (6), StageConsistencyTest |
| Vergleich (E1/E11) | nur Finger halten auf dem Bild | zusätzlich Halten-Taste in der Hilfsleiste (TalkBack: umschalten), bei Zuschneiden/Markieren/Retusche deaktiviert | StudioFoundationTest, TouchTargetTest |
| Markieren/Text (E6) | Text und Sticker als Blatt über dem Foto | Text-Editor und Sticker-Auswahl in der Werkzeugzone; Fokus + IME-Aktion „Fertig“, Zurück schließt | MarkupTest, StageConsistencyTest.keyboard… |
| Speichern (E9) | `ModalBottomSheet` über dem Bild | `EditorExportPage` (gleiche Optionen, gleiche Tags) | EditorTest (Kopie/Ersetzen/Ordner/Teilen) |
| Video-Editor (EV1–EV8) | Player je Bühne, bei Markieren neu erzeugt; Bedienzeile im Bühnenbereich; Maske 12 dp | ein Player je Editor; Transport in der Hilfsleiste; keine Maske; Zeitleiste direkt unter der Hilfsleiste | VideoEditorTest (7), EditorTest.trimVideo*, StageConsistencyTest |
| Collage (T3/T4) | Leinwand 56 %, Undo/Redo im Kopf, eigenes Speichern-Blatt | `EditorScaffold` (gleiche Bühne wie Editor), Status Ausgabegröße, `EditorExportPage` | CollageScreenTest (8), StageConsistencyTest |
| Film/Diashow (T5/T6) | Bühne 50 %, Play/Zeit als Kapsel auf der Vorschau | `EditorScaffold`, Play/Zeit in der Hilfsleiste, eigene Navigation mit Symbolen | MovieTest (3), StageConsistencyTest |
| Werkzeuge (T7–T13) | Formular mit Vorschau 38 % und Start-Taste unten | Vorschau als Bühne (gleiche Policy), Starten oben rechts; Stapel-Werkzeuge ohne Bühne mit gleicher Kopfzeile | ToolsTest (5), PlanGapTest.gif… |
| Serienvergleich (C4) | App-Farben, Bildflächen in Karten | Editorfarben, neutrale Bildflächen; Aufbau unverändert (zwei gleich große Flächen, Auswahl darunter) | ExpansionUiTest, StageConsistencyTest |
| Medienauswahl (T2) | – | **unverändert** (Galerie-Raster im App-Stil, keine Bearbeitungsfläche) | CollageScreenTest, ToolsTest |
| Feed/Betrachter | – | **nicht geändert** (außerhalb des Auftrags) | FeedFlowTest, LibraryTest |

Keine Funktion entfernt oder versteckt; keine neue Funktion, keine Änderung an Engine, Export, Codec, HDR, Monetarisierung.

## 2b. Runde 2 (Rückmeldung 03.10.2026)

| Punkt | Umsetzung | Nachweis |
|---|---|---|
| 1 Navigation vollständig | Foto: Zuschnitt · Anpassen · Filter · Markieren · Mehr (Retusche, Rahmen, Größe). Video: Kürzen · Anpassen · Filter · Markieren · Mehr (Tempo, Ton, Format). Collage-Raster: Layout · Format · Hintergrund · Foto · Mehr (Abstand, Markieren). „Mehr“ öffnet das zuletzt genutzte Werkzeug der Gruppe; Unterreiter oben in der Werkzeugzone. Neue Texte `dock_crop` („Zuschnitt“), `dock_more` (16 Sprachen). | `runde2_light_a.png`, `runde2_font.png` (150/200 % scrollt, aktiver Eintrag sichtbar) |
| 2 Anpassen ruhiger | Gruppen als Textreiter, Einzelkorrekturen als einheitliche Ring-Symbole, Name + Wert am Regler (Wert mit fester Breite, Tabellenziffern) | `runde2_light_a.png`, `runde2_dark_a.png` |
| 3 Trennung | Bühne fest → Werkzeugzone scrollt eigenständig (Abstand unten `xl`, etwas höher: 31 % statt 26 % der freien Höhe, 140–260 dp) → feste Navigation mit Trennlinie. Bühnenhöhe weiter unabhängig vom Werkzeug. | `StageConsistencyTest`, `EditorLayoutPolicyTest` |
| 4 Video sichtbar | Standbild (gleiche Pipeline wie Export) liegt auf der Bühne, bis der Player das erste Bild zeigt; Filmstreifen fällt auf dieselbe Bildquelle zurück, wenn `MediaMetadataRetriever` nichts liefert. Kürzen-Hinweis: „Ziehe die Griffe, um Anfang und Ende festzulegen.“ Technik hinter „Exportinfo“ (aufklappbar): nur Anfang/Ende → „versucht, nur bis zum nächsten Schlüsselbild neu zu kodieren, sonst ganzes Video“ (Media3 `experimentalSetTrimOptimizationEnabled` kann auf volle Neukodierung zurückfallen); sonst „ganzes Video wird neu kodiert“ – gleiche Bedingung wie der Exportweg (`EditSaver.isTrimOnly`). | `runde2_light_b.png`, `runde2_dark_a.png`, `vorher_nachher_*_video_trim.png` |
| 5 Speichern kompakt | Zwei Radiozeilen in einer Gruppe: „Als Kopie speichern – Original bleibt unverändert.“ (immer vorausgewählt) und „Kopie speichern und Original in den Papierkorb“ (nur ausdrücklich). Ordner/Standort darunter, Haupttaste passend zur Wahl („Kopie speichern“ / „Speichern, Original in den Papierkorb“), „Nur teilen“ als Nebentaste. „Original ersetzen“ umbenannt (es entsteht immer eine neue Datei). „30 Tage“ entfernt: die Frist legt Android fest (`MediaStore.DATE_EXPIRES`), der Text sagt jetzt „solange Android es aufbewahrt“. | `EditorTest.replaceModeIsExplicitAndNeverPreselected`, `vorher_nachher_*_export.png` |
| 6 Stile | Auswahl überall gleich (zarte Akzentfläche + Akzentrand + Akzenttext), Werkzeuge im Ringstil, Haupttasten flach. Zustände: aktiv (Akzent), deaktiviert (neutrale Fläche + Nebentext), Export läuft (Speichern deaktiviert, Fortschrittsansicht mit Abbrechen). | alle Aufnahmen |

Unverändert beibehalten: feste Bühne, Werkzeuge außerhalb des Bildes, gemeinsames Gerüst, Letterbox-Fläche.

## 3. Geschützte Regeln

- **A – feste Bühne:** `EditorLayoutPolicy` hängt nur von Fenster, Schrift, Tastatur ab. Gemessen: Foto-Editor, Video-Editor, Collage und Film haben dieselbe Bühne (`allEditorsShareOneStageGeometry`); die Bühne springt in keinem Bild eines Werkzeugwechsels (`stageNeverJumpsDuringToolSwitches`, 8 Wechsel × 24 Bilder); Tastatur öffnet/schließt → exakt zurück.
- **B – kein Chrome auf dem Medium:** Bühnen enthalten nur das Werkstück und Bearbeitungsmarkierungen (Zuschnittrahmen, Ebenenauswahl, Collage-Griffe). Gemessen für Video-Editor, Feed, Film, Serienvergleich, Betrachter.
- **C – bedienbar:** `TouchTargetTest` (24 Bildschirme, ≥ 48 dp) + neue Prüfung „keine überlappenden Trefferflächen“ für Editor, Collage, Film.

## 4. Kontraste (gemessen, WCAG)

Dunkel: Text/Hintergrund 17,6:1 · Nebentext/Fläche 8,8:1 · Nebentext/Navigation 7,3:1 · Akzent/Fläche 6,4:1.
Hell: Text/Hintergrund 15,5:1 · Nebentext/Hintergrund 5,4:1 · Nebentext/Navigation 4,8:1 · Akzent/Navigation 4,6:1 · Weiß auf Akzentfläche 4,7:1.

## 5. Gemeldete Lücke unter Querformat-Videos (Feed)

Untersucht: In `ReviewFeedScreen` folgt die Infozeile unmittelbar auf die Medienfläche (`weight(1f)`), es gibt keinen zusätzlichen Abstandshalter. Der Leerraum entsteht innerhalb der festen Fit-Fläche, wenn ein Querformat-Video eingepasst wird – nach Auftrag kein Layoutfehler. Im Editor liegt die Zeitleiste direkt an der Bühnenkante (Hilfsleiste → Werkzeugzone), nicht an der Bildkante.

## 6. Vorher/Nachher-Aufnahmen

Reproduzierbar mit `./gradlew :app:recordRoborazziDebug --tests '*EditorShotsTest*'` (Robolectric, Testbilder `landscape_sunset` 1200×900 als 4032×3024 gemeldet, `portrait_lake`, `video_coast`; Fenster 393×852 dp, 360×800 dp dunkel, Schrift 150/200 %, Arabisch, 852×393 dp Querformat).
Vorher = Stand `b27fbf4`, Nachher = Stand dieses Berichts; Kontaktbögen in `docs/screenshots/editor-redesign/`.
Tastaturfall: Robolectric zeigt keine echte Tastatur – die Aufnahme simuliert 300 dp (Querformat 200 dp) belegte Fläche über `LocalEditorImeOverride`; ein Vorher-Bild des Tastaturfalls gibt es nicht (vorher lag der Text-Editor in einem Blatt).
Runde 2: Videos zeigen jetzt auch in Robolectric ein Bild (Standbild-Untergrund + Filmstreifen aus der Export-Bildquelle); bewegte Wiedergabe nur auf dem Gerät.
Runde-2-Kontaktbögen: `runde2_light_*.png`, `runde2_dark_*.png`, `runde2_font.png`, `runde2_land.png`; Vorher/Nachher: `vorher_nachher_{light393,dark360}_{photo_adjust,video_trim,export}.png`.

## 7. Offen (Gerät)

NOT RUN / BLOCKED ohne Realme: echte Videowiedergabe und Scrubbing, echte Tastatur (IME-Wechsel, Cursor, Zurück), TalkBack-Bedienung, echte Exporte, Gestennavigation/Drei-Tasten-Navigation/Displayausschnitt am Gerät, Performance-Messung von Reglern/Scrubbing (nur Build, keine Messung). Offene Punkte zu Kauf, Werbung, Benachrichtigungen bleiben unverändert offen.
