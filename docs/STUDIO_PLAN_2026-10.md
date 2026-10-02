# PhotoMaid Studio – Plan (2026-10-02)

Ziel: Wer aufräumt, will oft gleich etwas machen – korrigieren, schneiden, zusammenstellen, beschriften. PhotoMaid soll
**alles anbieten, was sich mit Fotos und Videos lokal auf dem Telefon machen lässt** – mit dem gleichen Anspruch wie der
Editor: Vorschau = Ergebnis, Original nie überschrieben, ehrliche Hinweise, flüssig auch auf Mittelklasse-Geräten.

Grenzen bleiben: kein Upload, kein Konto, kein Server. Was nur mit KI/ML ginge, steht in Abschnitt 9 als **Entscheidung**,
weil CLAUDE.md „keine KI/ML“ heute verbietet.

---

## 1. Was dazukommt (Überblick)

| Bereich | Neu |
|---|---|
| **Collage** (Priorität 1) | Raster-Vorlagen 2–20 Fotos, verschiebbare Trennlinien, Freie Collage, Streifen (neben-/untereinander, z. B. lange Screenshots), Abstand, Ecken, Hintergrund, Format (1:1, 4:5, 9:16 Story, 16:9, A4), je Feld zoomen/verschieben/tauschen/ersetzen/filtern, Text + Sticker darüber |
| **Ebenen für Fotos** | Text, Sticker (Emoji + eigene Vektor-Sticker), Zeichnen (Stift, Marker, Radierer), Formen (Pfeil, Rahmen, Kreis, Linie), **Unkenntlich machen** (Weichzeichnen/Verpixeln eines Bereichs – z. B. Namen in Screenshots), Rahmen/Passepartout, Wasserzeichen/Signatur |
| **Mehr Fotokorrektur** | Belichtung, Lichter, Schatten, Weiß-/Schwarzpunkt, Farbton (Tint), Vignette, Körnung, Klarheit, Perspektive (4 Ecken), Rote Augen (antippen) |
| **Video einzeln** | Teilen, Mittelstück herausschneiden, Geschwindigkeit 0,25×–4×, Ton leiser/stumm, Filter + Regler, Zuschneiden/Drehen/Spiegeln, Text/Sticker mit Zeitbereich, Standbild als Foto, **Komprimieren** (1080p/720p, spart echten Speicher) |
| **Video zusammenfügen** | Mehrere Clips (Videos und Fotos) in Reihenfolge, je Clip kürzen/drehen/Geschwindigkeit/Filter, Übergang „Blende“ oder „Schwarz“, Hintergrundmusik aus eigenen Dateien, Lautstärke Clip/Musik, Format (Original, 9:16, 1:1, 16:9) |
| **Diashow** | Fotos → Video (Dauer je Bild, sanfter Zoom, Musik, Übergänge) |
| **Stapel-Werkzeuge** | Viele Fotos auf einmal verkleinern/komprimieren/umwandeln (HEIC→JPEG), Metadaten/Standort entfernen, Screenshots zusammenfügen; Ergebnis „x MB gespart“ erst **nach** dem Schreiben gemessen |
| **Exporte** | JPEG/PNG/WebP, GIF aus Fotos oder kurzem Video, MP4 (H.264/HEVC je Gerät), Ton als M4A |
| **Entwürfe** | Collagen und Video-Projekte lokal speichern und später weiterbearbeiten |

---

## 2. Navigation – wo der Nutzer das findet

**Vorschlag: vierter Tab „Studio“** (Heute · Aufräumen · **Studio** · Verlauf).

Studio-Startseite:
- Groß oben: **Collage** · **Video zusammenfügen** · **Diashow**.
- Werkzeuge: Screenshots zusammenfügen · Fotos verkleinern/komprimieren · Videos komprimieren · Umwandeln · Standort entfernen · GIF erstellen.
- **Entwürfe** (zuletzt bearbeitet zuerst) und **Bearbeitet** (bisherige Liste, zieht aus Aufräumen hierher um).

Weitere Einstiege (alle ohne Entscheidung im Feed, nichts wird gelöscht):
- **Stift-Symbol im Feed** → Editor des Mediums (bestehend, wird um die neuen Reiter erweitert).
- **Raster/Mehrfachauswahl** (bestehend) → neue Aktionen „Collage“, „Video zusammenfügen“, „Diashow“, „Verkleinern…“.
- **Fotoserie** (Pro, bestehend) → „Als Collage“.

Jeder Studio-Ablauf beginnt mit einer **Medienauswahl** (eigenes Raster mit Reihenfolge-Nummern, Ordner-/Monatsfilter,
Fotos/Videos/alle, max. Anzahl sichtbar) – die gleiche Komponente für Collage, Video und Stapel.

---

## 3. Fundament (einmal bauen, überall nutzen)

### 3.1 Ebenen-Modell (Core, JVM-testbar)
`Layer` = Text | Sticker | Zeichnung | Form | Unkenntlich | Bild. Jede Ebene: Mitte (normiert 0…1), Größe, Drehung,
Deckkraft, Reihenfolge, bei Video zusätzlich Zeitbereich. Reine Daten → Rückgängig/Wiederholen, Entwürfe, Tests.

### 3.2 Ein Renderer für Vorschau und Export
`LayerRenderer` zeichnet Ebenen auf ein Android-`Canvas` – in der Vorschau in Bildschirmgröße, beim Export in voller
Auflösung, bei Video als Bild pro Ebene, das Media3 als `BitmapOverlay` einblendet. **Gleicher Code = gleiches Ergebnis.**

### 3.3 Bedienung der Ebenen (Premium-Gefühl)
- Ziehen, mit zwei Fingern skalieren und drehen; **Einrasten** an Mitte/Rändern/0°/90° mit Hilfslinien und Haptik.
- Antippen = auswählen (Rahmen mit Griffen), zweimal antippen = Text bearbeiten, Ziehen auf den Papierkorb unten = löschen.
- Leiste der Auswahl: Duplizieren · Nach vorne/hinten · Deckkraft · Farbe · Löschen.
- TalkBack: jede Ebene als Element mit Aktionen (verschieben ±, größer/kleiner, drehen, löschen).

### 3.4 Schriften und Sticker
- **Schriften:** 10–12 Schriften mit freier Lizenz (SIL OFL) in der App gebündelt, zusätzlich Systemschriften;
  Stile: Füllung, Kontur, Schatten, Hintergrund-Pille, Ausrichtung, Zeilenabstand. Alle 16 Sprachen inkl. Arabisch
  (rechts nach links), Japanisch, Koreanisch, Chinesisch müssen darstellbar sein → je Schrift Abdeckung prüfen, sonst
  Systemschrift als Rückfall.
- **Sticker:** Emoji (gebündeltes EmojiCompat, gleich auf allen Geräten) + ein eigenes Vektor-Set (Pfeile, Formen,
  Herzen, Sterne, Sprechblasen, Datum/Ort-Stempel aus EXIF). Keine fremden Sticker-Pakete ohne geklärte Lizenz.

### 3.5 Entwürfe
Projekt als JSON im App-Speicher (Medien-IDs, Ebenen, Einstellungen) – nie hochgeladen, in der Datenschutz-Übersicht
aufgeführt. Fehlt ein Medium später (gelöscht/verschoben), zeigt das Projekt eine leere Stelle mit „Ersetzen“.

### 3.6 Lange Exporte
Video-Exporte laufen als Vordergrunddienst (Typ `mediaProcessing`, Android 15+) mit Benachrichtigung „Video wird erstellt ·
42 %“ und Abbrechen; die App darf dabei verlassen werden. Fotos/Collagen schreiben direkt.

### 3.7 Speichern
Wie im Editor: neue Datei per `MediaStore` mit `IS_PENDING`, Collagen/Diashows/zusammengefügte Videos in
`Pictures/PhotoMaid/` bzw. `Movies/PhotoMaid/` (eigene Werke haben kein Original). Neue Dateien gelten als „Behalten“.
Werbung: nie im Studio, nie während eines Exports.

---

## 4. Collage (Priorität 1)

### 4.1 Arten
1. **Raster** – ~60 Vorlagen für 2–20 Fotos (klassisch, Magazin, Polaroid, Filmstreifen). **Trennlinien ziehbar**:
   Felder werden größer/kleiner, die Vorlage bleibt sauber.
2. **Frei** – Fotos wie Ebenen frei platzieren, drehen, überlappen, optional weißer Rand/Schatten („Polaroid“).
3. **Streifen** – nebeneinander oder untereinander in Originalbreite, z. B. Screenshots zu einem langen Bild;
   optional überlappende Kopfzeilen abschneiden (manuell mit Griff, keine Erkennung).

### 4.2 Pro Feld
Antippen → Foto im Feld verschieben/zoomen (Doppeltipp = einpassen/füllen), Drehen, Spiegeln, Filter, Regler,
Ersetzen, Entfernen; **Ziehen auf ein anderes Feld = tauschen** (mit Vorschau beim Ziehen).

### 4.3 Gesamt
Format (1:1, 4:5, 3:4, 9:16, 16:9, A4/A5 hoch/quer), Abstand innen/außen, Eckenradius, Hintergrund (Farbe, Verlauf,
unscharfes Foto), Rahmen, danach Text/Sticker/Zeichnen über alles.

### 4.4 Qualität
- Vorschau aus verkleinerten Bildern (Feldgröße auf dem Bildschirm), Export lädt jedes Foto **nur in der Auflösung
  seines Feldes** → auch 20 Fotos à 50 MP ohne Speicherproblem.
- Export: lange Kante 2048 / 4096 px oder „Druck A4 300 dpi“; ehrliche Anzeige der echten Größe erst nach dem Speichern.
- EXIF: Aufnahmedatum = jetzt, kein Ort (eine Collage hat keinen einzelnen Ort).

---

## 5. Foto-Editor: neue Reiter

Bestehende Reiter Zuschneiden · Anpassen · Filter · Größe bekommen Gesellschaft:
- **Anpassen** erweitert: Belichtung, Lichter, Schatten, Weiß, Schwarz, Tint, Vignette, Körnung, Klarheit
  (Lichter/Schatten/Klarheit als GPU-Shader `RuntimeShader` ab Android 13, darunter CPU in Kacheln).
- **Text** · **Sticker** · **Zeichnen** (Stift/Marker/Neon/Radierer, Stärke, Farbe) · **Formen**.
- **Unkenntlich**: Rechteck oder Pinsel, Weichzeichnen oder Verpixeln, Stärke. Wird beim Export **in die Pixel
  gerechnet** (nicht nur überlagert) – sonst wäre es nicht wirklich unkenntlich.
- **Perspektive**: vier Ecken ziehen (Dokumente, Gebäude), Raster.
- **Rote Augen**: auf das Auge tippen → Rot im kleinen Kreis entsättigt (klassische Farbregel, keine Erkennung).
- **Rahmen**: weißer/farbiger Rand, Passepartout für Instagram 4:5.

Free/Pro wie bisher: siehe Abschnitt 8.

---

## 6. Video-Editor (ein Clip)

Reiter: **Kürzen** (bestehend) · **Schneiden** (teilen, Mittelstück entfernen – Zeitleiste mit mehreren Bereichen) ·
**Tempo** (0,25×–4×, Ton wird angepasst) · **Ton** (Lautstärke, stumm) · **Filter/Anpassen** (gleiche Filter wie Fotos) ·
**Zuschneiden** (Seitenverhältnis, drehen, spiegeln) · **Text/Sticker** (mit Ein-/Ausblendzeit auf der Zeitleiste) ·
**Standbild** (aktuelles Bild als Foto speichern) · **Komprimieren** (Auflösung 1080p/720p/480p, Qualität;
Schätzung **nicht** angezeigt – erst nach dem Export „gespart: x MB“, gemessen).

Technik: Media3 Transformer mit Effekten (`Crop`, `ScaleAndRotateTransformation`, `Presentation`, `SpeedChangeEffect`,
`OverlayEffect`); Farbe über einen eigenen GL-Effekt mit **derselben 4×5-Farbmatrix** wie bei Fotos (Media3s
`RgbMatrix` hat keinen Offset → eigener Shader). **Live-Vorschau mit Effekten** über ExoPlayer `setVideoEffects`.

---

## 7. Video zusammenfügen und Diashow

- Zeitleiste unten mit allen Clips als Vorschaubild-Streifen; Ziehen = Reihenfolge, Antippen = Clip bearbeiten
  (alles aus Abschnitt 6), „+“ fügt weitere hinzu, Fotos werden zu Standbild-Clips (Dauer wählbar, sanfter Zoom).
- Übergänge je Naht: keiner · Blende · über Schwarz (0,3–1,5 s).
- Ausgabeformat: wie erster Clip · 9:16 · 1:1 · 16:9; Clips mit anderem Format → einpassen (Ränder: Schwarz,
  Farbe oder unscharf) oder füllen.
- Musik: eigene Audiodatei über die System-Dateiauswahl (keine mitgelieferte Musik → keine Lizenzfragen), Startpunkt,
  Ein-/Ausblenden, Lautstärke Musik/Originalton.
- Vorschau des Ganzen mit Media3 `CompositionPlayer`; Export als Vordergrunddienst.
- Grenzen ehrlich: z. B. max. 50 Clips / 30 min, 4K nur wenn das Gerät 4K kodieren kann (Encoder-Fähigkeiten abfragen),
  sonst automatisch 1080p mit Hinweis.

---

## 8. Free / Pro (Vorschlag – zu entscheiden)

| Funktion | Free | Pro |
|---|---|---|
| Zuschneiden, Drehen, Spiegeln, Begradigen, Perspektive | ✓ | ✓ |
| Text, Sticker, Zeichnen, Formen, Unkenntlich machen | ✓ | ✓ |
| Collage | bis 4 Fotos, alle Vorlagen | bis 20 Fotos, Freie Collage, A4-Druck |
| Regler, Filter, Größe, Rahmen | Vorschau ✓, Speichern mit Pro/Test (wie heute) | ✓ |
| Video kürzen, schneiden, Tempo, Ton, Filter, Text, Komprimieren | Pro/Test | ✓ |
| Video zusammenfügen, Diashow, Musik, GIF | Pro/Test | ✓ |
| Stapel-Werkzeuge | bis 10 Dateien | unbegrenzt |
| Entwürfe | 3 | unbegrenzt |

Kein Wasserzeichen in Free (wirkt billig, passt nicht zu „Premium“).

---

## 9. Was lokal nicht (oder nur mit KI) geht – Entscheidung nötig

| Wunsch | Lokal möglich? |
|---|---|
| Hintergrund entfernen / freistellen, Objekte wegradieren, Gesichter verschönern, „Automatisch verbessern“, KI-Hochskalieren, automatische Untertitel | Nur mit On-Device-ML (z. B. ML Kit, läuft offline, ohne Upload). **Heute verboten** („keine KI/ML“). Wenn erlaubt: eigener Schritt S7 |
| „Auto“ ohne KI (Histogramm strecken, Weißabgleich aus Graupunkt antippen) | ja, klassische Bildmathematik – widerspricht aber „keine Bildanalyse“ aus dem Ausbau 2026-10 → Entscheidung |
| Video rückwärts, Stabilisieren | rückwärts: ja, aber langsam/speicherintensiv (nur kurze Clips); Stabilisieren: sehr aufwendig → nicht geplant |
| Ortsnamen | nur über Googles Geocoder (sendet Koordinaten) → nicht geplant; Ort-Stempel zeigt Koordinaten oder nichts |

---

## 10. Reihenfolge und Aufwand

| Schritt | Inhalt | Aufwand |
|---|---|---|
| S0 | Fundament: Ebenen-Modell, Renderer, Gesten, Schriften/Sticker, Medienauswahl, Entwürfe, Studio-Tab | groß |
| S1 | **Collage** (Raster mit Trennlinien, Frei, Streifen, Felder, Hintergrund, Export) | groß |
| S2 | Foto-Editor: Text, Sticker, Zeichnen, Formen, Unkenntlich, Rahmen | mittel |
| S3 | Foto-Korrektur erweitert: Lichter/Schatten/Belichtung/Vignette/Körnung/Klarheit, Perspektive, Rote Augen | mittel |
| S4 | Video-Editor einzeln: Schneiden, Tempo, Ton, Filter mit Live-Vorschau, Zuschneiden, Text/Sticker, Standbild, Komprimieren, Vordergrunddienst | groß |
| S5 | Video zusammenfügen, Diashow, Übergänge, Musik | groß |
| S6 | Stapel-Werkzeuge, Screenshots zusammenfügen, GIF, Umwandeln | mittel |
| S7 | (nur falls freigegeben) On-Device-KI-Werkzeuge | groß |

Jeder Schritt endet wie bisher: Tests (JVM + Robolectric + Screenshots hell/dunkel/RTL/320 dp), 16 Sprachen,
Perf-Build, Commit, Gerätetest-Prompt. Geräte-Pflicht ab S4: Export-Zeiten 1080p/4K, Wärme/Akku, Abbruch, Hintergrund.

---

## 11. Entscheidungen (2026-10-02)

1. **Keine KI/ML** – bleibt verboten (Abschnitt 9: Freistellen, Wegradieren, Auto-Verbessern, Untertitel entfallen).
2. Alles andere aus diesem Plan wird gebaut. Studio als vierter Tab, Free/Pro wie Abschnitt 8, Reihenfolge wie Abschnitt 10.
3. Kein automatisches „Auto“ (auch nicht per Histogramm). **Histogramm und Warnung für abgeschnittene Lichter/Tiefen**
   sind erlaubt – sie zeigen nur an, sie entscheiden nichts.

## 12. Ergänzungen aus den Nutzer-Hinweisen (2026-10-02)

- **Eine Pixel-Pipeline für Vorschau und Export** (`core/.../edit/PixelPipeline.kt`, reines Kotlin, JVM-getestet):
  Belichtung, Lichter, Schatten, Weiß, Schwarz, Helligkeit, Kontrast als **getrennte Tonwertkurve** (Lichter ändern nur
  helle Bereiche), Wärme/Tönung/Sättigung/Dynamik, selektive Farben (8 Farbbereiche), Filter mit Stärke, Vignette,
  Körnung, Klarheit, Schärfen (Stärke/Radius/Schwelle). Vorschau rechnet beim Ziehen auf einer kleinen Stellvertreter-
  Fassung und danach in Vorschaugröße – gleicher Code wie der Export. Für Video wird daraus eine 3D-Farbtabelle (LUT)
  erzeugt (Media3 `SingleColorLut`) → Video-Farben = Foto-Farben.
- **100-%-Ansicht**: zwei Finger zoomen, Doppeltipp = 100 %; der sichtbare Ausschnitt wird in voller Auflösung nachgerechnet.
- **Eigene Vorlagen** (Einstellungen speichern, keine Bilder) und Anwenden auf mehrere Fotos.
- **Abdecken**: deckend (empfohlen), Verpixeln, Unschärfe – immer in die Pixel gerechnet; geteilte/gespeicherte Datei
  ohne Standort, Vorschaubilder im Cache werden nach dem Teilen gelöscht.
- **Video**: abgeschnittene Teile werden wirklich entfernt (Neukodierung des Anfangs, keine MP4-Edit-Lists);
  „Ton entfernen“ entfernt die Tonspur; Ein-/Ausblenden; „Ausfüllen“ vs. „Ganzes Video zeigen“ ausdrücklich wählbar;
  Tempo 0,5×/1×/1,5×/2× (+0,25×/4× unter „Weitere“), Vorschau = Export; Standbild in Originalauflösung dekodiert;
  Exportprofile Hohe Qualität · Ausgewogen · Kleine Datei mit **ausdrücklich als „ca.“ bezeichneter Schätzung** aus
  Bitrate × Dauer, danach die echte Größe; Encoder-Fähigkeiten (HEVC/4K/HDR) werden je Gerät abgefragt.
- **HDR**: nur als erhalten anzeigen, wenn der ganze Pfad es kann; sonst SDR-Export mit Hinweis.
- **Formate**: Qualität nur bei JPEG/WebP; PNG verlustfrei ohne Qualitätsregler; Auflösung, Format, Kompression getrennt.
- **Exportjournal**: jeder begonnene Export wird notiert; nach Absturz/Abbruch werden halbfertige (IS_PENDING) Einträge
  beim nächsten Start entfernt – nie als Erfolg gezeigt.
- **Ehrliche Größen**: „Kopie ist 120 MB kleiner“ – nie „gespart“, solange das Original existiert.
- **Bearbeiten und teilen**: Export nur in den Cache, Teilen über das System, danach gelöscht – keine Galeriekopie.
- **Nach dem Speichern**: optional direkt „In Ordner verschieben“ (keine Entscheidung).
- Eigene Exporte gelten als „Behalten“ und erscheinen nicht erneut als offene Aufgabe.
- Keine Pille „Behalten · Bearbeiten“ nach dem Wischen (Nutzerentscheidung 2026-10-02 bleibt: nur Stift-Symbol).

## 13. Umsetzungsstand

| Phase | Stand | Commit |
|---|---|---|
| P1 Fundament | fertig | be0fce4 |
| P2 Ebenen | fertig | 7799145 |
| P3 Collage + Studio-Tab + Medienauswahl + Entwürfe | fertig (JVM-Tests/Screenshots PASS, Gerät NOT RUN) | 3b2695a |
| P4 Perspektive, Farbmischer, Retusche (Kreis/Verlauf/Pinsel), Rote Augen | fertig (JVM-Tests/Screenshots PASS, Gerät NOT RUN) | 7df02f7 |
| P5 Video-Editor | in Arbeit | – |
| G1–G4 Mediathek (Tab „Fotos“, Ordnerbaum, Suche, Player, Diashow, Papierkorb, RAW) | geplant, siehe `GALLERY_PLAN_2026-10.md` | – |
| P6–P7 | offen (nach G1–G4) | – |

