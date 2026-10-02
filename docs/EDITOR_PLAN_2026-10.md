# Bearbeiten in PhotoMaid – Prüfung und Plan (2026-10-02)

Prüfstatus: NOT RUN (nur Plan, nichts gebaut). Grundlage: 10 Ideen aus dem Tagescheck (Tidy, Tidy Gallery, Swipe to
Clean, Gallery, GlidePic-Review) + Nutzerwunsch: **beim Speichern wählen „Original behalten“ (Kopie) oder „Original
anpassen“**. Anspruch: Premium-Qualität, nichts halbfertig. Feste Regeln bleiben: alles lokal, keine KI, keine
erfundenen Zahlen, nichts ohne ausdrückliche Bestätigung, Bearbeiten ist nie eine Wisch-Entscheidung.

---

## 1. Bewertung der 10 Ideen

| # | Idee | Urteil | Begründung / Änderung gegenüber dem Vorschlag |
|---|---|---|---|
| 1 | Zuschneiden | **übernehmen** | Kernfunktion. Ergänzt um Seitenverhältnisse und Begradigen (sonst nicht „premium“) |
| 2 | Drehen und spiegeln | **übernehmen** | Bei JPEG reines Drehen **verlustfrei** über EXIF-Ausrichtung (kein Neukodieren) – Qualitätsvorteil |
| 3 | Helligkeit, Kontrast, Sättigung | **übernehmen** | Mit 4 und 5 zu **einem** Bereich „Anpassen“ zusammenlegen (eine Vorschau, eine Pipeline) |
| 4 | Schärfe und Wärme | **übernehmen, Schärfe eingeschränkt** | Wärme = Farbmatrix (überall). Schärfe braucht Faltung: GPU erst ab Android 13 (`RuntimeShader`); darunter CPU – nur bis zur Speichergrenze, sonst Regler ausgeblendet und erklärt |
| 5 | Einfache Filter | **übernehmen** | Feste, eingebaute Farbmatrizen, mit Stärke-Regler und Vorschau-Leiste aus dem eigenen Foto. Kein Download, keine KI |
| 6 | Größe ändern | **übernehmen** | Nie hochskalieren; Vorgaben (lange Kante 4096/2048/1080 px) + eigene Werte mit Seitenverhältnis-Schloss. Dateigröße erst **nach** dem Speichern anzeigen (keine Schätzung) |
| 7 | Bearbeiten nach Behalten | **übernehmen, geändert** | Entschieden: Stift-Symbol in der Aktionsleiste für jedes Medium (statt Pille nach „Behalten“). Bearbeiten selbst entscheidet nichts |
| 8 | Video kürzen | **übernehmen** | Media3 Transformer; Schnitt mit Vorschau. Ohne Neukodierung wo möglich (Trim-Optimierung), sonst gleiche Qualität neu kodiert |
| 9 | Liste der Bearbeitungen | **übernehmen** | Aufräumen › „Bearbeitet“: von PhotoMaid gespeicherte Kopien und angepasste Originale. Nur lokal |
| 10 | Original nie überschreiben | **ersetzt durch Wahl** | Nutzerwunsch: Standard „Original behalten“; „Original anpassen“ als bewusste zweite Wahl – sicher umgesetzt (Abschnitt 2) |

Nicht übernommen: automatische Verbesserung, „Auto-Schärfen“, KI-Filter, Hintergrund entfernen, Cloud-Effekte.

---

## 2. Kernentscheidung: „Original behalten“ oder „Original anpassen“

Speichern-Dialog am Ende jeder Bearbeitung (ein Blatt, zwei große Optionen, Standard vorausgewählt = zuletzt gewählte):

**A. Als Kopie speichern – Original bleibt** (Standard)
- Neue Datei im selben Ordner, Name `Original_bearbeitet.jpg` (Zählung bei Kollision), gleiches Aufnahmedatum und
  gleicher Ordner → steht in der Galerie direkt neben dem Original.
- Kein Systemdialog nötig (eigene Datei per `MediaStore` mit `IS_PENDING`, erst nach vollständigem Schreiben sichtbar).

**B. Original anpassen** – zwei technische Wege geprüft:

| | B1 **Ersetzen** (empfohlen) | B2 Direkt überschreiben (nicht empfohlen) |
|---|---|---|
| Ablauf | Bearbeitete Fassung als neue Datei (Name, Ordner, Aufnahmedatum, Favorit wie Original), danach Original per `MediaStore.createTrashRequest` in den **Android-Papierkorb** (eine Systembestätigung) | `createWriteRequest` + Bytes der bestehenden Datei überschreiben |
| Rückgängig | Original 30 Tage im Android-Papierkorb wiederherstellbar; PhotoMaid zeigt „Original wiederherstellen“ in „Bearbeitet“ | nur mit eigener Sicherungskopie (doppelter Speicher, PhotoMaid müsste sie verwalten) |
| Formate | jedes Ausgabeformat möglich (z. B. HEIC → JPEG) | Format muss gleich bleiben; HEIC nur mit HEVC-Encoder des Geräts |
| Fehlerfall | Schreiben scheitert → Original unberührt | Abbruch mitten im Schreiben kann das Original beschädigen |
| Cloud (Google Fotos) | neue Datei wird hochgeladen, alte landet im Cloud-Papierkorb | gleiche Datei geändert, Verhalten je Anbieter unklar |

Empfehlung **B1**. Ehrlich im Dialog: „Das Original kommt in den Android-Papierkorb und lässt sich 30 Tage
wiederherstellen. Speicher wird erst danach frei.“ Ersetzen zählt **nicht** zum Tageslimit (es ist kein Aufräumen) –
Abweichung in `PORTING_MATRIX` dokumentieren.

Medien-IDs im Ledger: bei B1 bekommt die neue Datei die Entscheidung des Originals (meist „Behalten“) als eigener
Verlaufseintrag `EDIT_REPLACED`; das Original wird `REMOVED` mit Grund „ersetzt“ (nicht als Löschung gezählt, nicht in
„gespart“).

---

## 3. Editor-Aufbau (ein Bildschirm, nicht fünf)

Vollbild, Medium oben, unten vier Reiter: **Zuschneiden · Anpassen · Filter · Größe** (bei Videos: **Kürzen**).
Kopfzeile: Abbrechen · Rückgängig/Wiederholen · Speichern.

Für alle Werkzeuge gemeinsam (Premium-Grundlagen):
- **Live-Vorschau** auf GPU, flüssig beim Ziehen (Ziel 60 fps auf dem Realme-Testgerät); Export mit derselben
  Pipeline in voller Auflösung → Vorschau = Ergebnis.
- **Vorher/Nachher**: Finger auf dem Bild halten zeigt das Original.
- **Rückgängig/Wiederholen** im Editor (eigener Stapel, unabhängig vom Wisch-Undo).
- Alle Einstellungen als **Rezept** (`EditRecipe`, nicht destruktiv bis zum Speichern); „Zurücksetzen“ je Reiter.
- Abbrechen mit Änderungen fragt nach („Änderungen verwerfen?“).
- Haptik beim Einrasten (90°, Nullstellung, Seitenverhältnis), Regler mit Doppeltipp = 0, Zahlenwert sichtbar.
- TalkBack: Regler als `ProgressBarRangeInfo` mit Schritten, Zuschneiden zusätzlich über Knöpfe (Ränder ±).
- Hell/Dunkel, 200 % Schrift, kleine Telefone, RTL; Texte in allen 16 Sprachen.

### 3.1 Zuschneiden & Drehen (Ideen 1, 2)
- Rahmen mit Ecken- und Kantengriffen, Drittel-Raster beim Ziehen, Zoom/Verschieben des Bildes im Rahmen.
- Seitenverhältnisse: Frei · Original · 1:1 · 4:5 · 3:4 · 2:3 · 9:16 · 16:9 (Hochformat/Querformat umschaltbar).
- Begradigen ±45° mit feinem Raster; 90°-Drehen; horizontal/vertikal spiegeln.
- **Verlustfrei**: nur Drehen/Spiegeln bei JPEG → EXIF-Ausrichtung setzen, Bilddaten unverändert.
- Ausschnitt in voller Auflösung über `BitmapRegionDecoder` (nur der Ausschnitt wird dekodiert → auch sehr große Fotos).

### 3.2 Anpassen (Ideen 3, 4)
- Regler −100…+100: Helligkeit, Kontrast, Sättigung, Wärme, (Schärfe 0…100).
- Umsetzung: verkettete Farbmatrix (Helligkeit/Kontrast/Sättigung/Wärme) in einem Durchgang; Schärfe als Unschärfe-Maske
  (`RuntimeShader` ab API 33, CPU-Fallback mit Kachelung darunter). Keine automatischen Werte.

### 3.3 Filter (Idee 5)
- 8 feste Filter: Original · Schwarzweiß · Schwarzweiß kontrastreich · Warm · Kühl · Sepia · Verblasst · Lebendig.
- Leiste mit Vorschaubildern aus dem aktuellen Foto, Stärke-Regler 0–100 %. Filter + Regler kombinierbar (Reihenfolge fest).

### 3.4 Größe (Idee 6)
- Vorgaben „lange Kante“ 4096 / 2048 / 1080 px (nur angeboten, wenn kleiner als das Original), eigene Breite × Höhe
  mit Schloss. Anzeige: „4032 × 3024 → 2048 × 1536“. Nie Hochskalieren.
- Größe erst nach dem Speichern echt anzeigen („1,2 MB gespeichert“) – vorher keine Schätzung.

### 3.5 Video kürzen (Idee 8)
- Zeitleiste mit Vorschaubildern, zwei Griffe, Vorschau mit dem vorhandenen Player, Anzeige „0:12 – 0:47 · 35 s“.
- Media3 Transformer mit Trim-Optimierung (nur der Anfang bis zum nächsten Schlüsselbild wird neu kodiert, Rest kopiert)
  → gleiche Qualität, schnell. Fortschritt in %, Abbrechen jederzeit, läuft als Vordergrundarbeit weiter, wenn die
  App kurz verlassen wird. Ton bleibt; Drehung/HDR-Kennzeichnung wird übernommen, sonst ehrlicher Hinweis.

### 3.6 Einstieg (Idee 7) – entschieden 2026-10-02
- **Ein Bearbeiten-Symbol (Stift) in der Aktionsleiste** unter dem Medium: ♡ Favorit · Teilen · Ordner · **Bearbeiten** · ⋯.
  Gilt für jedes Foto und Video (Video öffnet „Kürzen“), egal ob schon entschieden. Keine Pille nach „Behalten“, kein
  zusätzlicher ⋯-Eintrag. Liegt außerhalb der Gestenfläche, ist nie eine Entscheidung.
- Nach dem Speichern zurück an dieselbe Feed-Stelle; das Medium bleibt sichtbar (bei „Original anpassen“ die neue Fassung).
- Schmale Telefone (< 360 dp): Leiste bleibt einzeilig – „Teilen“ wandert dort ins ⋯-Menü, damit Papierkorb-Zähler und
  Serie-Knopf Platz behalten (Screenshot-Test 320 dp).
- Bearbeiten-Knopf in der Papierkorb-Vorschau: nein (dort wird entfernt, nicht bearbeitet).

### 3.7 Liste „Bearbeitet“ (Idee 9)
- Aufräumen › Mediathek › „Bearbeitet“: Raster aller von PhotoMaid gespeicherten Kopien/angepassten Originale,
  neueste zuerst, mit Kennzeichen „Kopie“ bzw. „Original angepasst“ und Verknüpfung zum Original (falls noch da).
- Bei „Original angepasst“: „Original wiederherstellen“, solange es im Android-Papierkorb liegt (Prüfung über `IS_TRASHED`).
- Gespeichert lokal in Room (`edit_record`: neue URI, Quelle-URI, Modus, Zeitpunkt, Art) – nie hochgeladen.
- Einträge, deren Datei verschwunden ist, fallen weg (kein „toter“ Eintrag).

---

## 4. Qualität und Sicherheit (das, was „premium“ ausmacht)

| Thema | Umsetzung |
|---|---|
| Metadaten | EXIF übernehmen (Aufnahmedatum, Kamera, Belichtung, GPS) per `androidx.exifinterface`; Ausrichtung nach Anwenden zurück auf „normal“; `DATE_TAKEN` ausdrücklich gleich setzen → Galerie-Sortierung bleibt. Option im Speichern-Blatt: „Standort entfernen“ (Standard aus) |
| Formate | JPEG → JPEG (Qualität 95), PNG → PNG (Screenshots verlustfrei), WebP → WebP, HEIC → als Kopie JPEG (Hinweis), HEIC ersetzen nur über B1 |
| Farbraum / HDR | Display-P3 bleibt erhalten (Bitmap-Farbraum + ICC beim Kodieren). Ultra-HDR-Fotos (Android 14+, Gain-Map): bei Zuschneiden/Drehen/Größe wird die Gain-Map mitgeführt; bei Farb-Anpassungen ehrlicher Hinweis „HDR-Helligkeit geht verloren“ |
| Große Fotos | Vorschau immer verkleinert; Export prüft verfügbaren Speicher (`memoryClass`). Reicht er nicht: Zuschneiden über Ausschnitt-Dekodierung bleibt möglich, Regler/Filter nur bis zur sicheren Größe – sonst klare Meldung statt Absturz |
| Atomar | Kopie mit `IS_PENDING=1` schreiben, erst nach geprüftem Ergebnis (Datei lesbar, Maße stimmen) sichtbar machen; Fehler → Eintrag entfernen, Original unberührt |
| Parallel geändert | Vor dem Speichern `DATE_MODIFIED` des Originals vergleichen; geändert → Hinweis, nichts überschrieben |
| Berechtigungen | Kopie: keine. Ersetzen: eine Android-Bestätigung (Papierkorb). Kein `MANAGE_EXTERNAL_STORAGE` |
| Werbung | Nie im Editor, nie zwischen Speichern und Systemdialog; Bearbeiten zählt nicht zum Werbezähler |
| Datenschutz | Keine Bilddaten an Werbung/Server; Editor arbeitet offline |

---

## 5. Free / Pro (Vorschlag)

| Funktion | Free | Pro |
|---|---|---|
| Zuschneiden, Drehen, Spiegeln, Begradigen | ✓ | ✓ |
| Original behalten oder anpassen | ✓ | ✓ |
| Anpassen (Regler), Filter, Größe ändern | Vorschau ✓, Speichern mit Pro oder 30-Minuten-Test | ✓ |
| Video kürzen | Pro oder 30-Minuten-Test | ✓ |
| Liste „Bearbeitet“ | ✓ | ✓ |

---

## 6. Technik

- Neue Abhängigkeiten: `androidx.exifinterface`, `androidx.media3:media3-transformer` + `media3-effect` (gleiche
  Version wie Player), optional `androidx.heifwriter`. Keine Netzwerkbibliothek.
- Core (testbar auf JVM): `EditRecipe` (Ausschnitt normalisiert 0…1, Drehung, Spiegeln, Regler, Filter + Stärke,
  Zielgröße), `ColorMatrices` (reine Mathematik), `CropMath` (Seitenverhältnis, Begradigen, Grenzen), `ResizeRules`
  (nie hochskalieren, Rundung), `SaveMode` (COPY/REPLACE), neue `HistoryKind`s `EDIT_SAVED`, `EDIT_REPLACED`.
- App: `feature/editor/` (Screen, ViewModel, Werkzeuge), `media/MediaEditor.kt` (Dekodieren, Rendern, Kodieren, EXIF,
  MediaStore-Insert/Ersetzen), `media/VideoTrimmer.kt` (Transformer), Room `edit_record` (Migration v1 → v2).
- Rezept-Anwendung identisch für Vorschau und Export (eine Funktion, zwei Auflösungen).

## 7. Tests und Abnahme

- JVM: Rezept-Mathematik (Ausschnitt nach Drehung, Seitenverhältnis-Grenzen, Begradigen ohne leere Ecken), Farbmatrix
  (Neutralstellung = Identität, Kombination), Größenregeln, Verlauf/Ledger bei Ersetzen.
- Robolectric (Pixel-Tests auf kleinen Bitmaps): Filter ändert nur die Kopie, Original-Bytes gleich (SHA-256), Maße
  der Kopie, EXIF-Datum übernommen, `IS_PENDING`-Abbruch hinterlässt nichts, Ersetzen ruft genau einen Papierkorb-
  Dialog, Abbrechen im Dialog lässt beides bestehen.
- Gerät (Pflicht, weil JVM das nicht prüfen kann): 50-MP-Foto, HEIC, Ultra-HDR, Video 4K 2 min kürzen, Galerie-
  Gegenprobe (Datum/Ort/Sortierung), Google-Fotos-Verhalten bei Ersetzen, Performance der Live-Vorschau.

## 8. Reihenfolge und Aufwand

| Schritt | Inhalt | Aufwand |
|---|---|---|
| E1 | Speicher-Kern: Kopie/Ersetzen, EXIF, Formate, `IS_PENDING`, Liste „Bearbeitet“, Ledger | mittel |
| E2 | Editor-Gerüst + Zuschneiden/Drehen/Spiegeln/Begradigen (+ verlustfreies Drehen) | mittel–groß |
| E3 | Anpassen + Filter (GPU-Pipeline, Vorher/Nachher) | mittel |
| E4 | Größe ändern | klein |
| E5 | Video kürzen (Transformer, Zeitleiste, Fortschritt) | mittel–groß |
| E6 | Einstiege (Pille nach Behalten, ⋯), Tutorial-Schritte, 16 Sprachen, Screenshots, Gerätetest-Prompt | mittel |

Jeder Schritt endet mit grünen Tests, perf-Build, Commit und Gerätetest-Prompt.

## 9. Entscheidungen (2026-10-02)

1. „Original anpassen“ = **B1 Ersetzen** (neue Datei + Original in den Android-Papierkorb). ✔
2. Gespeicherte Kopie gilt automatisch als „Behalten“ (eigener Verlaufseintrag `EDIT_SAVED`). ✔
3. Free/Pro wie Abschnitt 5. ✔
4. Vorauswahl im Speichern-Blatt: zuletzt gewählte Speicherart (`editSaveMode`), beim ersten Mal „Original behalten“. ✔
5. Bearbeiten für jedes Medium über das Stift-Symbol in der Aktionsleiste. ✔

## 10. Umsetzungsstand (2026-10-02)

E1–E6 umgesetzt. Abweichungen vom Plan:
- Liste „Bearbeitet“ liegt im KeyValueStore (`edits.v1`, max. 500) statt einer Room-Migration – keine Schemaänderung nötig.
- Ledger: kein eigener `HistoryKind`; die neue Fassung bekommt eine normale „Behalten“-Entscheidung, das ersetzte Original
  verlässt Papierkorb/Später (Unmark/RemoveDeferral).
- Schärfen läuft auf der CPU (in Streifen) statt über `RuntimeShader`; Farbe live über die GPU (`ColorFilter.colorMatrix`).
- „Parallel geändert“: geprüft wird, ob das Original vor dem Speichern noch existiert; ein Vergleich von `DATE_MODIFIED`
  folgt, sobald `MediaAsset` das Feld trägt.
- Video kürzen läuft im Vordergrund des Editors (Fortschritt, Abbrechen); Weiterlaufen nach Verlassen der App ist offen.

Prüfstatus: siehe Commit/Bericht (JVM PASS/FAIL je Lauf). Gerätetest: NOT RUN.

