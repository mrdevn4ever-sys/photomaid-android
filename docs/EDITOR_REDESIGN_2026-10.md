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
| `EditorDock` | `design/EditorChrome.kt` | Werkzeugnavigation: deckende Fläche mit feinem Rand (kein Blur), Symbol + Beschriftung in einheitlichem Stil; passt es nicht, scrollt die Leiste. |
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
Videos zeigen in Robolectric kein Bild (schwarze Fläche) – echte Wiedergabe nur auf dem Gerät.

## 7. Offen (Gerät)

NOT RUN / BLOCKED ohne Realme: echte Videowiedergabe und Scrubbing, echte Tastatur (IME-Wechsel, Cursor, Zurück), TalkBack-Bedienung, echte Exporte, Gestennavigation/Drei-Tasten-Navigation/Displayausschnitt am Gerät, Performance-Messung von Reglern/Scrubbing (nur Build, keine Messung). Offene Punkte zu Kauf, Werbung, Benachrichtigungen bleiben unverändert offen.
