# PhotoMaid Mediathek – Plan (2026-10-02)

Ziel: PhotoMaid wird eine vollständige Foto-App – nicht nur Aufräumen und Bearbeiten, sondern auch **ansehen,
finden, ordnen, abspielen**. Alles lokal, ohne KI, ohne Server. Grundlage sind zwölf belegte Nutzerwünsche aus Reviews
und Issue-Trackern anderer Galerie-Apps (Aves, Fossify, Gallery Go), plus RAW-Wunsch. Eine schlichte chronologische
Übersicht hat niemand ausdrücklich verlangt – sie ist trotzdem das Gerüst, an dem die Wünsche hängen (ohne Übersicht
gibt es keinen Ort für Ordner, Favoriten, Suche, Diashow).

Feste Regeln bleiben: **Ansehen ist nie eine Entscheidung** (kein Ledger-Eintrag durch Öffnen, Blättern, Abspielen);
die Gesten des Aufräum-Feeds bleiben unverändert; keine automatische Löschung; unbekannte Größe ≠ 0.

---

## 1. Die Wünsche und was PhotoMaid daraus macht

| # | Wunsch (Quelle) | Heute in PhotoMaid | Plan |
|---|---|---|---|
| 1 | Unterordner sehen, Sortierung je Ordner statt global (Aves, Review 24.12.2025) | flache Ordnerliste („In Ordner verschieben“, Ausnahmen) | **Ordnerbaum** mit aufklappbaren Unterordnern; **Sortierung je Ordner** gemerkt (Pfad → Sortierung), Vorgabe für neue Ordner einstellbar |
| 2 | Eigene Ordner anlegen, Ordner in Ordnern bündeln (Gallery Go, 27.08.2026) | neuer Ordner nur beim Verschieben | „Neuer Ordner“ (entsteht mit dem ersten verschobenen Medium – MediaStore kennt keine leeren Ordner, Hinweis im Dialog); **Gruppen** = Ordner zusammenfassen ohne Dateien zu bewegen (nur Pfade gespeichert); echtes Verschachteln = Verschieben mit Systembestätigung |
| 3 | Nicht jeder Unterordner ein eigenes Album, Liste wird lang (Aves #2200) | – | Baum statt Albenliste; Unterordner erscheinen eingerückt unter dem Elternordner; Ansicht „Flach“ optional |
| 4 | Favoriten nach Sammlung sortierbar (Aves, 09.06.2025) | Favoriten = Systemfavoriten, eine Liste | Favoriten-Ansicht **gruppiert nach Ordner** oder chronologisch, umschaltbar |
| 5 | Favoriten nur innerhalb eines Ordners (Fossify #230) | – | Filter „Nur Favoriten“ in jedem Ordner (ein Tipp, gemerkt je Ordner) |
| 6 | Suche ignoriert Akzente, „é“ = „e“ (Aves #2290) | keine Suche | **Suche** über Dateiname, Ordnerpfad, Monat/Jahr, Art (Foto/Video/Screenshot/RAW), Kamera (EXIF-Modell); Vergleich nach `Normalizer` NFD ohne Akzente, ohne Groß/klein, auch ß = ss. Kein Ortsname (Geocoder braucht Netz) |
| 7 | Diashow Album für Album zufällig (Aves #2109) | – | **Diashow-Wiedergabe** (ansehen, nicht exportieren): Reihenfolge chronologisch · zufällig · **Album für Album zufällig** (Ordner gemischt, innerhalb zufällig oder chronologisch); Dauer, Übergang (Überblenden/keiner), Videos mitspielen ja/nein; Bildschirm bleibt an |
| 8 | Play/Pause über Bluetooth-Kopfhörer (Fossify, 09.04.2026) | Feed-Player ohne MediaSession | Vollbild-Videoplayer mit **Media3 `MediaSession`** (Kopfhörer-Tasten, Sperrbildschirm optional aus); im Aufräum-Feed ebenfalls Play/Pause über Kopfhörer – nie eine Entscheidung |
| 9 | Wisch nach oben abschaltbar (Aves #1817) | Feed: hoch = Behalten (fest) | Im **Betrachter** gibt es keine Wisch-Aktion nach oben (nur blättern links/rechts, runter = schließen) – nichts abzuschalten; der Aufräum-Feed bleibt bewusst wie er ist (Kerngeste, CLAUDE.md) |
| 10 | In verstecktes Album schieben, ohne es sichtbar zu machen (Aves #2268) | Ausgenommene Ordner (nur PhotoMaid) | **„In PhotoMaid ausblenden“** für einzelne Medien und Ordner – direkt aus Auswahl, ohne die Ausblendliste zu öffnen; ehrlich beschriftet: andere Apps sehen sie weiter. Kein „Tresor“ mit Dateiverschiebung in den App-Speicher (Deinstallation würde Fotos löschen) |
| 11 | Löschen soll weg sein, nicht im Papierkorb hängen (Fossify, 25.04.2026) | endgültig = Android-Papierkorb (`createTrashRequest`, 30 Tage) | Einstellung **„Endgültig löschen statt Android-Papierkorb“** (Standard aus) → `createDeleteRequest`; immer mit Systembestätigung und eigenem deutlichem Hinweis „nicht wiederherstellbar“; Tageslimit gilt gleich |
| 12 | Papierkorb nach Löschzeit sortiert (Aves #2087) | PhotoMaid-Papierkorb ohne Sortierung | PhotoMaid-Papierkorb sortierbar: **zuletzt hineingelegt** (Ledger-Zeit, Standard) · Aufnahmedatum · Größe; neue Ansicht **Android-Papierkorb** (`QUERY_ARG_MATCH_TRASHED`) sortiert nach Ablauf („noch 12 Tage“), Wiederherstellen |
| + | RAW hinter dem JPEG, nur auf Wunsch zeigen (Aves #1893) | RAW wie jedes Bild | **RAW+JPEG-Paare** (gleicher Ordner, gleicher Dateiname ohne Endung, DNG/CR3/NEF/ARW/RAF/ORF/RW2) werden als ein Eintrag mit „RAW“-Abzeichen gezeigt; Einstellung „RAW einzeln zeigen“. Aufräumen fragt bei Paaren „Beide in den Papierkorb / nur JPEG / nur RAW“ |

Bearbeitung kam in Galerie-Reviews vor (ist mit dem Studio abgedeckt). Übersicht, Alben und Videowiedergabe kamen in
Aufräum-App-Reviews nicht vor – sie sind also kein Grund, das Aufräumen umzubauen, sondern eine Ergänzung daneben.

---

## 2. Navigation

Neuer Tab **„Fotos“** an zweiter Stelle: Heute · **Fotos** · Aufräumen · Studio · Verlauf (5 Tabs, Material-Grenze;
Beschriftungen einzeilig, geprüft bei 320 dp).

Oben im Tab ein Umschalter (SegmentedPicker): **Zeitachse · Ordner · Favoriten** und eine Suchzeile. Videos,
Screenshots, RAW sind Filter (Chips), keine eigenen Seiten.

- **Zeitachse**: Raster nach Tag/Monat/Jahr, mit zwei Fingern Stufe wechseln (Jahr → Monat → Tag), schneller
  Scroll-Griff mit Monatsanzeige. Kopfzeilen „Oktober 2026 · 214“ (echte Zahl, sonst keine).
- **Ordner**: Baum (siehe §1, 1–3), je Ordner Vorschaubild, Anzahl, Größe (nur wenn bekannt).
- **Favoriten**: nach Ordner gruppiert oder chronologisch.
- **Betrachter** (Vollbild): blättern links/rechts, runter = schließen, Doppeltipp/zwei Finger = Zoom, Info-Blatt
  (Datum, Größe, Auflösung, Kamera, Ordner) per Knopf. Unten die bekannte Aktionsleiste ♡ · Teilen · Ordner · ✏️ · ⋯
  (⋯: In PhotoMaid ausblenden, Diashow ab hier, **Aufräumen ab hier** → Feed derselben Quelle).
- **Auswahl**: langes Drücken + Ziehen (wie im Raster heute): Verschieben, Favorit, Collage, Ausblenden, In den
  PhotoMaid-Papierkorb (das ist eine Entscheidung – derselbe Weg wie im Feed, `FeedViewModel.perform`-Logik bzw.
  `AppModel.decide`, mit Tageslimit-Prüfung).

Der bestehende Aufräum-Feed bleibt der Ort für Entscheidungen; „Fotos“ ist der Ort zum Ansehen.

---

## 3. Technik

- **Abfragen**: MediaStore mit `RELATIVE_PATH`, `DATE_TAKEN`/`DATE_ADDED`, `IS_FAVORITE`, `SIZE`, `MIME_TYPE`;
  Seitenweise (Paging 3) statt alles zu laden; Monatsköpfe aus den Daten, nichts gespeichert. Ausgenommene Ordner und
  ausgeblendete Medien wirken überall (`ExcludingMediaLibrary` erweitert).
- **Ordnerbaum** (core, JVM-getestet): aus den Pfaden gebaut; Gruppen und Sortierung je Pfad im KeyValue-Speicher
  (nur Pfade, keine Medien-IDs).
- **Suche** (core): `SearchIndex` aus Name/Pfad/Datum/Art/Kamera, normalisiert (NFD, Akzente entfernt, `ß→ss`,
  Kleinbuchstaben); Treffer = alle Wörter kommen vor. Kamera aus EXIF nur, wenn schon gelesen (kein Vollscan beim
  Tippen; Index im Hintergrund, abbrechbar).
- **Video**: ein `ExoPlayer` + `MediaSession` (Media3 Session) für Betrachter und Feed; Audiofokus; Kopfhörer-Tasten
  Play/Pause, kein „Weiter“ = keine Entscheidung.
- **Diashow**: reine Reihenfolge-Funktion in core (`SlideshowOrder`: chronologisch, zufällig, Album für Album) mit
  festem Zufalls-Startwert je Start (wiederholbar testbar); `FLAG_KEEP_SCREEN_ON`.
- **Löschen**: `createDeleteRequest` nur bei eingeschalteter Einstellung; Journal wie beim Export; Ergebnis ehrlich
  („3 gelöscht, 1 abgelehnt“).
- **Android-Papierkorb**: `QUERY_ARG_MATCH_TRASHED` + `DATE_EXPIRES`; Wiederherstellen per `createTrashRequest(…, false)`.
- **RAW-Paare** (core): Paarbildung nach Ordner + Basisname + RAW-MIME/Endung; reine Anzeigefunktion.

---

## 4. Phasen und Reihenfolge

Studio-Video (P5) zuerst, wie besprochen. Danach die Mediathek, weil die Diashow-Wiedergabe (G3) und die
Ordner/Suche (G1/G2) die Grundlage für P6 (Diashow-Export, Zusammenfügen) und P7 (Stapel) sind.

| Phase | Inhalt | Aufwand |
|---|---|---|
| **P5** | Video-Editor (laut Studio-Plan) | groß |
| **G1** | Tab „Fotos“: Zeitachse mit Zoomstufen, Betrachter, Info-Blatt, Auswahl, Suche (akzentfrei) | groß |
| **G2** | Ordnerbaum, Sortierung je Ordner, neue Ordner, Gruppen, Favoriten je Ordner/gruppiert, In PhotoMaid ausblenden | mittel |
| **G3** | Vollbild-Videoplayer mit MediaSession (Kopfhörer), Diashow-Wiedergabe mit „Album für Album zufällig“ | mittel |
| **G4** | PhotoMaid-Papierkorb sortierbar, Android-Papierkorb-Ansicht, „Endgültig löschen“ (Einstellung), RAW+JPEG-Paare | mittel |
| **P6** | Video zusammenfügen, Diashow-Export, Übergänge, Musik | groß |
| **P7** | Stapel-Werkzeuge, GIF, Umwandeln, Standort entfernen | mittel |

Jede Phase: core-Tests, Compose-Tests, Screenshots (hell/dunkel, 320 dp, RTL, 200 % Schrift), 16 Sprachen, Doku,
Commit. Geräteabnahme bleibt NOT RUN, bis der Test-Agent sie macht.

---

## 5. Vorschläge, die ich so umsetze, sofern du nichts anderes sagst

1. **5. Tab „Fotos“** an zweiter Stelle (statt die Übersicht in „Aufräumen“ zu verstecken).
2. **„Endgültig löschen“ ist eine Einstellung, Standard aus** – Android-Papierkorb (30 Tage) bleibt Standard, weil
   versehentliches Löschen sonst nicht rückgängig zu machen ist.
3. **Verstecken = „In PhotoMaid ausblenden“**, ehrlich beschriftet; kein Tresor mit Dateiverschiebung.
4. **Feed-Gesten bleiben fest** (hoch = Behalten ist nicht abschaltbar); der neue Betrachter hat keine Wisch-Aktion nach oben.
5. **Kein Ortsname/Karte** (Ortsnamen bräuchten Netz); Koordinaten nur im Info-Blatt, wenn vorhanden.

## 6. Umsetzungsstand

| Phase | Stand |
|---|---|
| G1 Tab „Fotos“: Zeitachse (Tag/Monat/Jahr, zwei Finger, Scroll-Griff mit Datum), Filter, Suche ohne Akzente, Auswahl (Favorit, Verschieben, Collage, PhotoMaid-Papierkorb mit Favoritenschutz), Betrachter (blättern, runter schließen, Zoom, Info, Ab hier aufräumen, Papierkorb mit Rückgängig) | fertig (JVM-Tests/Screenshots PASS, Gerät NOT RUN). Kamera-Modell noch nicht in der Suche (EXIF-Index folgt mit G2) |
| G2–G4 | offen |

