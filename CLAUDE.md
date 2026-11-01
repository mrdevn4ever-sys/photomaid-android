# GaleriQ Android: verbindliche Regeln

Native Android-App (Kotlin, Jetpack Compose). 1:1-Port der iOS-App (`mrdevn4ever-sys/photomaid`, read-only).
**Produktname seit 30.10.2026: „GaleriQ“** (vorher PhotoMaid, kurz Arrumo): sichtbar in allen Texten, Ordnern (`Pictures/GaleriQ/`,
`Movies/GaleriQ/`, `Documents/GaleriQ/`, `Music/GaleriQ/`), Dateinamen und Metadaten; Datenschutz unter
https://mrdevn4ever-sys.github.io/galeriq/. Paket `app.photomaid…`, Klassen, Datenbank und Log-Tags bleiben intern „PhotoMaid“ (wie iOS: Daten bleiben, nie umbenennen).
Kein Flutter, React Native, WebView-Oberfläche. Bei Widerspruch gewinnt das Verhalten der iOS-App
(außer Plattformgrenze, Sicherheit, Store-Policy, eindeutiger Bug – dann in `docs/PORTING_MATRIX.md` dokumentieren).

## Gesten (FEST, tatsächliche Fingerbewegung)

| Finger | Bedeutung | Danach |
|---|---|---|
| hoch | **Behalten** | nächstes Medium |
| links | **Papierkorb** (GaleriQ-eigen, nichts gelöscht) | nächstes Medium |
| rechts (gerade oder schräg nach oben/unten, ≤ 45°) | **Später** mit Standardtermin (Vorgabe 2 Tage) | nächstes Medium + Pille „In 2 Tagen wieder · Ändern“ (eigener Termin über „Ändern“) |
| runter | **Vorheriges Medium** (keine Entscheidung) | – |

- Keine Diagonalgeste (Produktentscheidung 2026-09-29, Abweichung von iOS): zwischen 45° und „hoch“ bzw. „runter“ liegen Totzonen.
- Tipp aufs Video = Pause/Play (keine Entscheidung). Beim Ziehen eine dezente Richtungs-Pille (hoch/links/rechts), keine bei „runter“.
- Navigation: Zurück und Tabwechsel ohne Animation; Öffnen nur kurzer leichter Fade (150 ms). Predictive-Back-Vorschau aus.
- Eine einzige Gesture-/Decision-State-Machine: `core/.../feed/FeedGestureMachine.kt`.
- Nichts tun = keine Entscheidung. Eine Geste = maximal ein Schritt. Commit trägt Item-ID, Revision, Command-ID, Aktion.
- Keine Entscheidungs-Buttons im Feed – außer bei TalkBack/Schaltersteuerung oder wenn der Nutzer die Bedienungshilfe
  „Tasten zum Entscheiden“ einschaltet (Einstellungen, Standard aus; Produktentscheidung 2026-10-02). Tasten rufen exakt
  `FeedViewModel.perform` auf (derselbe Weg wie die Geste). Unter dem Medium sitzt die kompakte
  Aktionsleiste ♡ Favorit · Teilen · In Ordner verschieben · ✏️ Bearbeiten · ⋯ – außerhalb der Gestenfläche, nie eine
  Entscheidung (unter 360 dp wandert „Teilen“ ins ⋯-Menü).
- Favoriten = MediaStore-Systemfavoriten (`createFavoriteRequest`), „Favoriten schützen“ (Standard an): Wisch nach
  links auf einen Favoriten fragt nach. „Album“ gibt es als Systembegriff nicht: nur „In Ordner verschieben“
  (GaleriQ-eigene Alben siehe „Organisieren“ – nie eine Datei-Aktion).
- Schnellzugriffe auf „Heute“ (max. 6, Standard Fotos + Videos) sind nur Abfragen (`QuickAccessItem`, gespeichert ohne
  Medien-IDs). Entscheidungen hängen am Medium im Ledger; Zähler immer über `AppModel.pendingQueue` – nie je Quelle speichern.
- Pro-Test (Rewarded, 30 min, 1× pro lokalem Tag) schaltet Pro-Werkzeuge frei, hebt das Papierkorb-Limit NIE auf
  (`hasProToolsAccess` ≠ `hasUnlimitedTrashAccess`).
- Tageslimit Free: 20 endgültige Entfernungen pro lokalem Tag (Produktentscheidung 2026-10-04, vorher 15).
- Video-Bonus (Rewarded, Produktentscheidung 2026-10-02/04): im Limit-Blatt freiwillig „+20 für heute“, 1× pro lokalem Tag, getrennt
  vom Pro-Test, nur nach SDK-Reward-Callback (`RewardLedger.grantingBonus`, `Capabilities.cleanupBonus`). Erhöht nur die Zahl des
  laufenden Tages – nie unbegrenzt, nie für Lifetime; nur der Kauf hebt das Limit auf.
- Werbung nur in Free (`Capabilities.Tier.FREE`): native Werbekarte im Feed alle 7 Entscheidungen (`core/.../ads/FeedAdPolicy.kt`:
  30 Entscheidungen Aufwärmphase, max. 30/Tag gesamt, 1,5 s Mindestdauer, zusätzlich mind. 60 s seit der letzten Karte) und eine ruhige Anzeige ganz unten in der
  Sitzungs-Zusammenfassung (nicht neben dem einmaligen deutlichen Pro-Angebot, zählt zur Tagesgrenze). Die Karte ist NIE eine Entscheidung und zählt nicht fürs
  Limit; „runter“ und Rückgängig überspringen sofort. AdMob startet erst nach UMP-Einwilligung; Anfragen enthalten nie Medien,
  Dateinamen, Pfade, Hashes oder IDs. Echte AdMob-IDs nie im Repo (Gradle-Properties), sonst Werbung aus.

- Ausbau 2026-10 (alles ohne Bildanalyse, alles lokal):
  - Ausgenommene Ordner: `AppModel.photos` ist `ExcludingMediaLibrary` (ohne ausgenommene Ordner inkl. Unterordner);
    `allPhotos` nur für die Ordnerauswahl selbst. Gespeichert werden nur Pfade.
  - Monate (`core/.../progress/MonthProgress.kt`) und „Neu seit dem letzten Aufräumen“ (`DATE_ADDED`) sind nur Filter, nichts je
    Monat gespeichert. „Durchgesehen“ = Behalten, Papierkorb, entfernt oder Später mit Termin/ohne Termin.
  - „Später ohne Termin“ = `DecisionAction.Bookmark` (`bookmarked`): kommt nie von selbst zurück, nur über Später › Beiseitegelegt.
  - Fotoserien (Pro, `core/.../organize/PhotoSeries.kt`): gleicher Ordner, ≤ 10 s Abstand, ≥ 3 – nur „kurz hintereinander
    aufgenommen“, nie „ähnlich“/„bestes Foto“. Erst „n behalten · m in den Papierkorb“ entscheidet (ein Commit je Medium), keine Werbung dabei.
  - Raster/Mehrfachauswahl und Verschieben sind NIE eine Entscheidung (kein Ledger-Eintrag). Reihenfolge ändert nur die Abfolge.
  - Ordner-Dateien: ab Android 17 (API 37) mit `FLAG_SUPPORTS_TRASH` per `DocumentsContract.trashDocument` in den Papierkorb
    des Speicherorts, sonst endgültig mit Warnung.

- Mediathek (Tab „Fotos“, 2. Stelle von 5: Heute · Fotos · Aufräumen · Studio · Verlauf; `feature/library/`, Kern
  `core/.../library/Timeline.kt`): Zeitachse Tag/Monat/Jahr (zwei Finger), Filter, Suche ohne Akzente (lokal), Auswahl,
  Vollbild-Betrachter (blättern, runter = schließen, Zoom; keine Wisch-Aktion nach oben). Ansehen ist NIE eine
  Entscheidung; nur „In den Papierkorb“ (GaleriQ-eigen, Favoriten geschützt, Rückgängig) geht über den Ledger.
  Ansichten Zeitachse · Ordner · Favoriten (G2, `feature/library/FoldersScreen.kt`, Kern `core/.../library/FolderTree.kt`):
  Ordnerbaum, Sortierung je Ordner, Gruppen (nur Name + Pfade), Neuer Ordner (Medien wählen → verschieben). „In GaleriQ
  ausblenden“ (`hiddenMedia`/`hiddenFolders`) gilt für Fotos UND Aufräumen (`AppModel.photos`); der Tab „Fotos“ nutzt
  `AppModel.visiblePhotos` (vom Aufräumen ausgenommene Ordner bleiben dort sichtbar). Kein Tresor, Dateien unverändert.
  G3: Kopfhörer-Tasten über `MediaSession` auf `FeedPlayer.ButtonsOnly` (nur Play/Pause/Spulen, NIE „Weiter“ – eine
  Taste ist nie ein Schritt oder eine Entscheidung). Diashow (`feature/library/SlideshowScreen.kt`, Kern
  `core/.../library/Slideshow.kt`) ist nur Ansehen. Mediensteuerung/Zeitleisten werden in RTL nicht gespiegelt.
  G4: „Endgültig löschen“ ist eine Einstellung (Standard AUS, `PHOTO_LIBRARY_DELETE_PERMANENTLY`, `createDeleteRequest`,
  Systembestätigung); Android-Papierkorb-Ansicht (`SystemTrashScreen`) nur auf ausdrückliche Auswahl. RAW+JPEG-Paare
  (`core/.../library/RawPairs.kt`, `RawIndex`): RAW hinter dem JPEG, geht bei Papierkorb/Ausblenden/Verschieben mit.
  Plan G1–G4: `docs/GALLERY_PLAN_2026-10.md`.
- Organisieren (Ausbau Paket 1, 2026-10, `feature/organize/`, Kern `core/.../organize/Organize.kt`, Room v2 per AutoMigration):
  GaleriQ-eigene Alben (bewusst zusammengestellt, Mehrfachzuordnung, Ansicht „Alben“ im Tab „Fotos“), eigene
  Schlagwörter + Notizen (Betrachter › Info, Suche findet sie), dauerhafter Auswahlkorb (keine Aufräum-Bedeutung, ≠ Papierkorb).
  Ordner = Speicherort · Sammlung = Suchbedingung · Album = Nutzerauswahl. Alles nur Verwaltungsdaten: „Aus Album entfernen“,
  „Album löschen“, „Schlagwort entfernen“, „Korb leeren“ ändern nie eine Datei und sind nie eine Entscheidung (kein Ledger).
  Bezüge über Medien-ID + Fingerabdruck (Name, Größe, Aufnahmezeit, nur eindeutig, `Rematch`); nicht Auffindbares wird
  sichtbar gezählt und nur auf Wunsch entfernt. Keine Erkennung – nur, was der Nutzer selbst einträgt. Free, ohne Werbung.
- Arbeitsabläufe (Ausbau Paket 2, 2026-10, `feature/workflow/`, Kern `core/.../batch/Workflow.kt`): benannte Abläufe aus vorhandenen
  Schritten (Format, längste Seite, Qualität, Zielgröße je Foto/Video oder gemeinsames Budget, Standort, Namensschema
  „Basis_001“, Ordner, Speichern/Nur teilen). Vorschau „Das passiert“ vor dem Start, Ergebnisliste (fertig/übersprungen/
  Ziel nicht erreicht/fehlgeschlagen). Zielgröße = Vorgabe: jedes Ergebnis wird gemessen (`TargetSize`, Video `VideoTarget` +
  ein Nachkodieren); nicht erreichbar → KEINE Datei, Grund mit kleinster gemessener Größe. Abläufe löschen nie, legen nichts
  in einen Papierkorb, entscheiden nie über Quellen; gespeicherte Ergebnisse = „Behalten“. Free bis 10 Dateien je Durchgang.
- Teilen-Ziel + App-Shortcuts (2026-10, `system/AppEntry.kt`, `system/AppShortcuts.kt`, `feature/share/ShareScreen.kt`, Kern
  `core/.../share/Share.kt`): „An GaleriQ senden“ (`ACTION_SEND`/`SEND_MULTIPLE`, nur `image/*`/`video/*`) öffnet nur die Seite
  „Übernommen“ (Ablauf anwenden, Bearbeiten bei genau einem Medium, Album, Auswahlkorb). Zuordnung über MediaStore-ID, sonst nur
  eindeutig über Name + Größe (`ShareMatch`); Rest wird gezählt, nie kopiert. Dynamische Shortcuts (feste Ziele + bis zu 4
  Abläufe, Direktes Teilen je Ablauf über `res/xml/shortcuts.xml`) öffnen nur Ansichten: nie Entscheidung, nie Löschen, nie
  ein Start ohne Tipp, keine Medien-IDs. Kein Shortcut/Intent darf je löschen oder entscheiden.
- Dokumente (Ausbau Paket 4, 2026-10, `feature/document/PdfScreen.kt`, Export `media/PdfExporter.kt`, Kern
  `core/.../document/PdfLayout.kt` + eigener `PdfWriter`): mehrseitiges PDF aus Fotos (A4/A5/US Letter, Ausrichtung, Rand,
  1/2/4/6/12 je Seite, Einpassen oder Füllen – nie verzerren, Beschriftung Nummer/Dateiname/Datum als Bild, Reihenfolge).
  Vorschau = Export (gleiche Aufteilung, gleicher Zeichenweg). Bilder neu als JPEG eingebettet: keine EXIF-/Standortdaten,
  keine Dateinamen als Text, Dokument-Info nur „GaleriQ“. Speichern nach `Documents/GaleriQ/` (kein Medium, keine
  Entscheidung, Quellen unverändert) oder nur teilen; Größe gemessen. Free bis 10 Fotos, 1–2 je Seite; Kontaktbögen (ab 4)
  und bis 200 Fotos mit `hasProToolsAccess`. Keine Text-/Dokumenterkennung. Einstiege: Studio, Auswahl › ⋯, Teilen-Ziel.
- Studio (4. Tab, 2026-10, `feature/studio/`, Kern `core/.../studio/`): Collage (Raster mit Trennlinien, Frei, Streifen),
  Foto bearbeiten, Entwürfe, „Bearbeitet“. Collage = neues Bild in `Pictures/GaleriQ/` (gilt als „Behalten“, Quellen
  bleiben ohne Entscheidung), Vorschau und Export über denselben `CollageRenderer`. Free: Raster/Streifen bis 4 Fotos, 3
  Entwürfe; Pro/30-Minuten-Test: bis 20 Fotos, Frei, A4-Druck, 50 Entwürfe – ausprobieren immer frei, kein Wasserzeichen.
  Medienauswahl (`MediaPickerScreen`) ist nie eine Entscheidung. Neue String-Kennungen vorher auf Kollision prüfen.
- Editor-Oberfläche (Nutzerwunsch 2026-10-07, `design/MediaEditorChrome.kt`): wie die Fotos-App, immer dunkel – ✕/✓-Kapseln,
  Rückgängig · TITEL · Vergleich/⋯, große Bühne, schmale Werkzeugzone (runde Werkzeuge + `EditorRuler`) für Anpassen/Filter/
  Zuschneiden/Kürzen, höhere Zone für Werkzeuge mit vielen Einstellungen, Reiterkapsel. Zoom mit zwei Fingern auf jeder Bühne
  (`ZoomableStage`, nur Ansicht, nie im Rezept). Nichts Bedienbares auf dem Bild außer dem Werkzeug selbst.
- Editor (2026-10, `feature/editor/`, Kern `core/.../edit/`): nicht destruktives Rezept (`EditRecipe`), Vorschau = Export
  (gleiche Geometrie/Farbmatrix). Speichern nur als neue Datei: „Als Kopie speichern“ oder „Kopie speichern und Original in den Papierkorb“
  (`SaveMode.REPLACE`, Original per `createTrashRequest` in den Android-Papierkorb, wiederherstellbar in „Bearbeitet“) – nie überschreiben.
  Gespeicherte Fassung = „Behalten“; Ersetzen zählt nicht zum Tageslimit. Vorauswahl immer „Als Kopie speichern“; „Kopie speichern und Original in den Papierkorb“ nur auf ausdrückliche Wahl (Redesign 2026-10).
  Free: Zuschneiden/Drehen/Spiegeln/Begradigen; Regler, Filter, Größe, Video kürzen speichern nur mit `hasProToolsAccess`
  (Vorschau frei). Nie hochskalieren, keine Größenschätzung vor dem Speichern, keine Werbung im Editor.
  P4: Perspektive (`EditRecipe.perspective`, vier Ecken → `Homography`, kostenlos wie Zuschneiden) und Rote Augen
  (`redEyes`, Farbregel, kostenlos); Farbmischer (helligkeitserhaltend) und Bereiche (`locals`: Kreis/Verlauf/Pinsel,
  rein manuell) speichern mit Pro. Keine Erkennung von Gesichtern, Augen, Himmel o. Ä.
  P5 Video (`feature/editor/VideoEditor.kt`, Kern `core/.../studio/VideoPlan.kt`, Export `media/VideoEditExporter.kt`):
  Kürzen, Bereiche wirklich entfernen, Tempo, Ton (Lautstärke, Blenden, Ton entfernen = keine Tonspur), Format
  (Ausfüllen/Ganzes Video), Farbe als Farbtabelle aus derselben Pipeline, Text/Sticker mit Zeitfenster, Standbild,
  Ton als M4A. Vorschau = Export (gleiche Media3-Effekte). Nur Anfang/Ende gekürzt → schneller Weg ohne Neukodierung.
  Ausnahme zur Größenregel (Nutzer-Hinweis 2026-10-02): Video-Profile zeigen eine ausdrücklich als „ca.“ beschriftete
  Schätzung. Lange Exporte über `ExportCoordinator` + Vordergrunddienst; nie hochskalieren.
  P6 Film/Diashow (`feature/studio/MovieScreen.kt`, Kern `core/.../studio/MoviePlan.kt`, Export `media/MovieExporter.kt`):
  Vorschau = Export (eine Komposition), Übergänge „keiner“/„über Schwarz“, Musik nur aus eigener Datei, Ergebnis in
  `Movies/GaleriQ/` = „Behalten“, Quellen ohne Entscheidung, Speichern mit `hasProToolsAccess`.
  P7 Werkzeuge (`feature/studio/ToolsScreen.kt`, Kern `core/.../batch/`): Verkleinern, Umwandeln, Standort entfernen
  (Stapel, Free bis 10 Dateien), Screenshots zusammenfügen, GIF (`GifEncoder`, Pro). Immer neue Dateien = „Behalten“,
  Quellen ohne Entscheidung; Originale nur auf Wunsch in den Android-Papierkorb (Standard aus). Größen nur gemessen,
  Ersparnis erst, wenn alle Originale im Papierkorb liegen und alle Größen bekannt sind (`BatchSummary.freedLaterBytes`), und
  immer als „noch nicht frei“ (frei erst, wenn Android den Papierkorb leert); keine feste Papierkorb-Frist behaupten.

- Kurze Pause + Erfolge (iOS-Port 2026-11, `feature/games/`, Kern `core/.../games/`, `core/.../progress/ReviewProgress.kt`):
  Paare, Foto-Snake (nur Wischgesten lenken, Start/Weiter auch per Knopf, keine Richtungstasten), Foto Blocks, Bestwerte,
  Erfolge (Level, Serie mit einem Pausentag je Woche, Tagesziel 50, Abzeichen nur aus echten Daten, nie fürs Löschen).
  Spielen/Erfolge sind NIE eine Entscheidung (kein Ledger), keine Werbung; Spielstände nur Zahlen (`GameStore`,
  `KeyValueStore`), Fotos nur als Vorschau im Arbeitsspeicher aus `AppModel.photos`. Bericht: `docs/IOS_PORT_2026-11.md`.
  Texte aus iOS übernehmen: `scripts/import_ios_strings.py`.

## Verbote

- NIE verzerren (Nutzer-Grundsatz 2026-10-02): Fotos/Videos nur einpassen (Fit) oder beschneiden (Fill/Crop), nie
  strecken. Videos immer über `playback/FitVideo` (`ContentFrame`, `ContentScale.Fit`), Größe ändern nur proportional
  (`ResizeRules`, kein freies Verhältnis), Collage-Felder beschneiden oder passen ein.

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
- UI-Texte ausschließlich in `res/values*/strings.xml`: 16 Sprachen (`locales_config.xml`): `values` = Englisch (Fallback
  für nicht unterstützte Sprachen), `values-de` = Deutsch (Leitsprache, Tests laufen auf `de`).
  Neue Kennung immer in allen 16 Dateien; Mengen nur als `plurals` (CLDR-Formen), Begriffe laut `docs/i18n/Glossar_16_Sprachen.txt`.
  Gestenpfeile über `GestureIcons` (nie AutoMirrored): die Fingerrichtung bleibt auch in RTL (Arabisch) physisch.

## Befehle

```sh
./gradlew :core:test                      # Domain-Kern
./gradlew :app:testDebugUnitTest          # Robolectric (Room, Compose-UI-Tests)
./gradlew :app:recordRoborazziDebug       # JVM-Screenshots nach app/build/outputs/roborazzi
./gradlew :app:assembleRelease :app:lintDebug
```

Prüfstatus immer PASS / FAIL / NOT RUN / BLOCKED. JVM-Screenshots sind keine Geräteabnahme.
