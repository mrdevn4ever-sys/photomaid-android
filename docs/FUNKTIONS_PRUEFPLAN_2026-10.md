# PhotoMaid Android – Funktionsliste und Prüfplan (Stand 2026-10-03, Commit a9a6401)

Ziel: Jede Funktion einzeln prüfen – bedienbar, verständlich, ruhig. Drei harte Regeln gelten überall:

1. **Medienfläche fest (M):** Auf einem Bildschirm ist die Fläche für Foto/Video immer gleich groß – egal ob Foto oder
   Video, Hoch- oder Querformat, läuft/pausiert, Steuerung sichtbar/ausgeblendet, lädt/Fehler, nächstes Medium.
   Das Medium wird darin nur eingepasst (Fit) oder beschnitten (Fill) – nie gestreckt, nie springend.
2. **Werkzeuge nie über dem Inhalt (W):** Werkzeuge, Regler, Tasten, Leisten und Blätter liegen in einem eigenen
   Bereich neben/unter der Medienfläche – nicht über dem Foto/Video. Einzige Ausnahme: Vollbild-Betrachter und Feed
   dürfen eine schmale Kopf-/Fußleiste haben, die per Tipp **vollständig** verschwindet.
3. **Immer bedienbar (B):** Jede Taste ≥ 48 dp, nicht von System-Leisten, Tastatur, Blättern oder anderen Tasten
   verdeckt; jeder Bedienbereich erreichbar (scrollt, wenn er nicht passt); Zurück funktioniert überall.

Prüfstatus je Zeile: **PASS / FAIL / NOT RUN / BLOCKED**. Ein JVM-Screenshot ist keine Geräteabnahme.

---

## 1. Prüfmethode

### A. Automatisch (JVM, vor jeder APK)
| Nr. | Prüfung | Wie |
|---|---|---|
| A1 | Medienfläche fest | Neuer Test `StageConsistencyTest`: je Bildschirm mit Medium die Grenzen der Fläche (`*.stage`-Tag) messen für Foto hoch, Foto quer, Panorama, Video hoch, Video quer, Laden, Fehler, Steuerung an/aus → alle Rechtecke müssen **gleich** sein (± 1 px). |
| A2 | Nichts über dem Inhalt | Im selben Test: Grenzen aller klickbaren Knoten (außer erlaubten Betrachter-Leisten) dürfen die Medienfläche nicht schneiden. |
| A3 | Bedienbar | Alle klickbaren Knoten ≥ 48 × 48 dp, innerhalb des sichtbaren Fensters (nach `performScrollTo`), nicht hinter Navigations-/Statusleiste. |
| A4 | Screenshot-Raster | Jeder Bildschirm in: 393 dp hell, 393 dp dunkel, 360 dp, Schrift 200 %, Arabisch (RTL), Querformat. Sichtprüfung auf Abschneiden, Überlappen, leere Flächen. |
| A5 | Bestehende Suites | `:core:test`, `:app:testDebugUnitTest`, `lintDebug`, `assembleRelease`. |

### B. Gerät (Realme, perf-APK, Dummy-Daten erlaubt)
Testdaten vorher: ≥ 30 Fotos (hoch, quer, Panorama, Screenshot, RAW+JPEG-Paar), ≥ 6 Videos (hoch, quer, 4K, sehr kurz < 3 s,
lang > 5 min, ohne Ton), 2 Ordner mit Unterordner, 3 Favoriten, 1 Foto mit GPS, 1 HEIC, 1 PNG, 1 großes Video > 100 MB.
Je Funktion: Schritte ausführen, Erwartung abhaken, bei FAIL Bildschirmfoto + Zeile + kurzer Satz ins Fehlerprotokoll (Abschnitt 4).
Jede Medien-Zeile (M) zusätzlich: **5× hintereinander Foto ↔ Video wechseln und auf Größensprung achten.**

---

## 2. Funktionsliste mit Prüfschritten

Spalte „Regeln“: welche der drei harten Regeln hier besonders zählt.

### 2.1 Start, Einführung, Berechtigungen
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| S1 | Einführung O1–O5 (Geschichte, Demo-Karten) | Erststart, durchwischen, Zurück | flüssig, Text lesbar bei 200 %, Überspringen möglich | B |
| S2 | Angebotsseite (auch später in der App) | Ende der Einführung, Schließen | ruhige Seite, Schließen gut sichtbar, kein Kauf ohne Tipp | B |
| S3 | Datenschutz & Zugriff | Zugriff erlauben / ablehnen / „Nur ausgewählte“ | jeder Fall mit klarer Folge-Ansicht, keine leere App | B |
| S4 | Coach-Marks Heute/Feed/Editor/Monate/Serien/Raster | je einmal auslösen | Hinweis zeigt auf das richtige Element, verdeckt nichts dauerhaft, kommt nicht wieder | W, B |

### 2.2 Heute (Tab 1)
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| H1 | Hero „Jetzt/Weiter aufräumen“ mit Vorschau | öffnen, Feed starten, zurück | Vorschaubilder gleich groß, Zahl echt | M |
| H2 | Wieder fällig (Später) | Medium mit fälligem Termin | erscheint, öffnet Feed mit genau diesen | B |
| H3 | Papierkorb-/Später-Zeile | antippen | öffnet richtige Ansicht | B |
| H4 | Schnellzugriffe (max. 6, hinzufügen, bearbeiten, ziehen) | hinzufügen, umsortieren, entfernen | Zähler stimmen, Ziehen ohne Ruckeln | B |
| H5 | Gerätespeicher-Zeile → Speicherübersicht | antippen | Zahlen nur gemessen, unbekannt ≠ 0 | – |
| H6 | Pro-Hinweis (Free) | als Free ansehen | dezent, schließbar | B |

### 2.3 Aufräum-Feed (Kernfunktion)
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| F1 | Wisch hoch = Behalten | 10× | nächstes Medium, genau 1 Schritt | M |
| F2 | Wisch links = Papierkorb | 10×, auch auf Favorit | Favorit fragt nach (Schutz an) | M |
| F3 | Wisch rechts = Später + Pille „In 2 Tagen · Ändern“ | rechts, „Ändern“, anderes Datum | Pille verdeckt das Medium nicht, Datum übernommen | M, W |
| F4 | Wisch runter = vorheriges Medium | mehrmals | keine Entscheidung | M |
| F5 | Totzonen / zu kurz / abgebrochen | schräge und kurze Wische | nichts passiert | – |
| F6 | Richtungs-Pille beim Ziehen | langsam ziehen | dezent, nicht bei „runter“ | W |
| F7 | Rückgängig (Kopfzeile) | nach jeder Aktion | Medium zurück, Zustand korrekt | B |
| F8 | Video im Feed: Tipp = Pause/Play, Spulen | Video hoch/quer, Tipp, Zeitleiste | **Fläche gleich groß wie bei Fotos**, Steuerung nicht über dem Bild oder ausblendbar | M, W |
| F9 | Aktionsleiste ♡ · Teilen · Ordner · ✏️ · ⋯ | jede Taste | außerhalb der Gestenfläche, nie Entscheidung; < 360 dp „Teilen“ im ⋯ | W, B |
| F10 | Tasten zum Entscheiden (Einstellung) | einschalten, nutzen | gleiche Wirkung wie Geste, liegen unter dem Medium | W, B |
| F11 | Werbekarte (Free) | 30+ Entscheidungen | nach Aufwärmphase, alle 7, runter/Rückgängig überspringt | M |
| F12 | Sitzungs-Zusammenfassung | Feed beenden | echte Zahlen, ruhige Anzeige unten | B |
| F13 | Tageslimit 20 + Limit-Blatt + Video-Bonus +20 | 21. Entfernung | Blatt erklärt, Bonus nur nach Video | B |
| F14 | Reihenfolge (neu/alt/groß …) | umstellen | nur Abfolge ändert sich | – |

### 2.4 Aufräumen (Tab 3)
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| C1 | Kategorien/Hub (Fotos, Videos, Screenshots …) | jede öffnen | Zahlen stimmen, Feed startet gefiltert | B |
| C2 | Sammlungen (fertige + eigene Pro) | öffnen, eigene anlegen | nur Filter, Zähler stimmen | B |
| C3 | Monate + „Weitermachen“ + „Neu seit dem letzten Aufräumen“ | Monat öffnen, abbrechen, weiter | Fortschritt korrekt | B |
| C4 | Fotoserien vergleichen (Pro) | Serie öffnen, n behalten · m Papierkorb | **Vergleichsbilder gleich groß**, Tasten nicht über Bildern | M, W |
| C5 | Raster / Mehrfachauswahl | Raster öffnen, wählen, verschieben | nie Entscheidung, Auswahlleiste verdeckt keine Kacheln (letzte Reihe erreichbar) | W, B |
| C6 | Große Dateien (Grenze änderbar) | 100 MB → 50 MB | unbekannte Größe separat | – |
| C7 | Exakte Duplikate | Scan, eine behalten, Rest Papierkorb | Fortschritt sichtbar, abbrechbar | B |
| C8 | Dateien/Quellen (SAF-Ordner) | Ordner verbinden, Browser, entfernen | Entfernen löscht nichts | B |
| C9 | Speicherübersicht | öffnen | nur gemessene Werte | – |
| C10 | Ausgenommene Ordner | Ordner ausnehmen | verschwinden aus Aufräumen, nicht aus „Fotos“ | – |

### 2.5 Papierkorb & Löschen
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| P1 | PhotoMaid-Papierkorb, Sortierung (hinzugefügt/aufgenommen/Größe) | sortieren, Vorschau, abwählen | Untere Taste verdeckt keine Kachel | W, B |
| P2 | Leeren → Android-Papierkorb | leeren, Systemdialog OK/Abbrechen | Abbrechen ändert nichts | B |
| P3 | „Endgültig löschen“ (Einstellung, Standard aus) | einschalten, leeren | deutlich andere Bestätigung | B |
| P4 | Android-Papierkorb-Ansicht (Restzeit, wiederherstellen, löschen) | wählen, wiederherstellen | zurückgeholt = behalten | B |
| P5 | RAW+JPEG-Paar | JPEG in Papierkorb | RAW geht mit | – |

### 2.6 Später & Verlauf
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| L1 | Später-Liste (fällig/kommend, Datum ändern, jetzt prüfen, entfernen) | jede Aktion | Datumsgruppen echt | B |
| L2 | Später ohne Termin (Beiseitegelegt) | anlegen, wiederfinden | kommt nie von selbst | – |
| L3 | Erinnerung (Benachrichtigung) | Opt-in, Termin abwarten/umstellen | Tipp öffnet fällige Medien | – |
| V1 | Verlauf (Alle/Behalten/Bereinigt/Später) | Filter wechseln | echte Ereignisse | B |
| V2 | Bearbeitet (Liste der gespeicherten Fassungen, Original wiederherstellen) | öffnen, wiederherstellen | Vorschauen gleich groß | M |

### 2.7 Fotos / Mediathek (Tab 2)
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| G1 | Zeitachse Tag/Monat/Jahr (zwei Finger) | zoomen | Kacheln quadratisch, kein Springen | M |
| G2 | Filter + Suche (ohne Akzente) | „Cafe“ findet „Café“ | – | B |
| G3 | Auswahl + Aktionen (Papierkorb, Ordner, Film, Diashow) | 5 wählen, jede Aktion | Auswahlleiste verdeckt keine Kacheln | W, B |
| G4 | Betrachter: blättern, runter = schließen, Zoom | Foto ↔ Video blättern | **Foto- und Videofläche gleich (Vollbild, Fit)**, kein Größensprung beim Blättern | M |
| G5 | Betrachter-Video: Tipp blendet Steuerung + Infos ganz aus | Tipp, Tipp | alles weg / alles wieder da, kein Flackern | M, W |
| G6 | Favorit ♡ im Betrachter | an/aus | kein Flackern (Gerätefehler 2026-10-02) | M |
| G7 | Info (Datum, Größe, Ordner, Kamera) | öffnen | Blatt überdeckt Medium nur solange offen, schließbar | W |
| G8 | Ordneransicht, Sortierung je Ordner, Gruppen, Neuer Ordner | anlegen, verschieben | nie Entscheidung | B |
| G9 | Favoriten-Ansicht | öffnen | nur Systemfavoriten | – |
| G10 | In PhotoMaid ausblenden / Ausgeblendet | ausblenden, wieder einblenden | Datei unverändert | – |
| G11 | Diashow | starten, Pause, Tempo | Bilder gleich groß, Steuerung ausblendbar | M, W |
| G12 | Kopfhörer-Tasten | Play/Pause/Spulen | nie „Weiter“ | – |

### 2.7a Organisieren (Paket 1)
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| O1 | Alben: anlegen aus Auswahl, hinzufügen, entfernen, umbenennen, löschen | je Aktion, danach Datei in Galerie-App prüfen | Dateien unverändert, keine Entscheidung (`OrganizeUiTest`) | B |
| O2 | Schlagwörter: vergeben (bei allen/einigen), Seite je Schlagwort, überall entfernen, Suche | „#Rechnung“ vergeben, „rechnung“ suchen | Treffer, keine Erkennung | B |
| O3 | Notiz im Betrachter › Info | Notiz speichern, App neu starten | Notiz bleibt, Datei unverändert | – |
| O4 | Auswahlkorb: hinzufügen aus verschiedenen Monaten, Neustart, Album aus Korb, Korb leeren | je Aktion | bleibt nach Neustart; Leeren ändert keine Datei | B |
| O5 | Wiederfinden / Fehlende | Medium außerhalb löschen, Album öffnen | „n nicht gefunden“, erst „Aus der Liste entfernen“ entfernt | – |
| O6 | Update von Version 1 (bestehende Installation) | APK über alte Version installieren | Entscheidungen/Einstellungen erhalten (Migration 1→2, `OrganizeStoreTest`) | – |

### 2.7b Arbeitsabläufe (Paket 2)
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| W1 | Einstiege Studio / Auswahl › ⋯ | beide öffnen | Liste, Vorlagen, Tippen = Ausführen | B |
| W2 | Editor | Ablauf anlegen, ändern, löschen | gespeichert, nur gültige Kombinationen | B |
| W3 | Vorschau + Budget | Schritte lesen, „Zusammen höchstens“ | Text passt zum Ergebnis | B |
| W4 | Zielgröße Foto/Video | 2 MB / 20 MB, echte Galerie-Dateien prüfen | keine Datei über dem Ziel; sonst Grund (`WorkflowRunTest`) | – |
| W5 | Nur teilen | an echte App teilen | nichts in der Galerie gespeichert | – |

### 2.8 Studio (Tab 4)
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| T1 | Studio-Übersicht (Erstellen, Werkzeuge, Entwürfe) | scrollen | alle Einträge erreichbar über der Tab-Leiste | B |
| T2 | Medienauswahl (Picker) | wählen, Grenze, Abbrechen | nie Entscheidung, Fertig-Taste verdeckt nichts | W, B |
| T3 | Collage Raster/Frei/Streifen, Felder, Hintergrund, Export | jede Art, Feld verschieben/zoomen | Leinwand fest, Werkzeugleiste darunter, Vorschau = Ergebnis | M, W |
| T4 | Entwürfe (Free 3 / Pro 50) | speichern, öffnen, löschen | – | B |
| T5 | Video zusammenfügen | Clips sortieren, kürzen, Übergang, Format, Musik, Speichern | **Vorschau fest 50 % Höhe**, Reiter darunter | M, W |
| T6 | Diashow als Video | Dauer, sanfter Zoom, Speichern | wie T5 | M, W |
| T7 | Werkzeug Verkleinern | 5 Fotos, 2048 px, Start | Kopien, Ergebnis mit echten Größen | B |
| T8 | Werkzeug Umwandeln (HEIC → JPEG/PNG) | HEIC + PNG | schon passende übersprungen | B |
| T9 | Werkzeug Standort entfernen | Foto mit GPS | Kopie ohne GPS (in Info prüfen) | B |
| T10 | Originale danach in den Android-Papierkorb | Schalter an | eine Systemfrage für alle | B |
| T11 | Screenshots zusammenfügen | 3 Screenshots | ein langes Bild, nicht verzerrt; **Vorschau-Fläche fest** | M |
| T12 | GIF aus Fotos (Größe, Zeit, Ganzes Bild/Ausfüllen) | 4 Fotos | GIF läuft in Galerie | M |
| T13 | GIF aus Video (bis 10 s) | Video, Bereich wählen | **auf Gerät noch nie geprüft** | M |
| T14 | Teilen ohne Speichern | Collage teilen | Systemteilen, keine Datei in Galerie | – |

### 2.9 Editor Foto (✏️ aus Feed, Betrachter, Studio)
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| E1 | Bühne fest (56 % Höhe) | Hoch-/Querformat/Panorama öffnen, alle Reiter durchklicken | **Bühne gleich groß in jedem Reiter**, Panel scrollt darunter | M, W |
| E2 | Zuschneiden, Drehen, Spiegeln, Begradigen, Perspektive | je anwenden | Griffe auf dem Bild sind die einzige Ausnahme von W – müssen greifbar bleiben | B |
| E3 | Anpassen (Licht, Farbe, Details, Farbmischer) | Regler ziehen | Vorschau sofort, Regler unter dem Bild | W |
| E4 | Filter + eigene Vorlagen | Filter wählen, Stärke | – | W |
| E5 | Retusche: Rote Augen, Bereiche (Kreis/Verlauf/Pinsel) | anwenden | – | B |
| E6 | Markieren: Text, Sticker, Zeichnen, Formen, Abdecken | je einmal, Text-Tastatur | **Tastatur verdeckt Eingabe nicht** | W, B |
| E7 | Rahmen | wählen | – | – |
| E8 | Größe (nur proportional, nie hochskalieren) | Größe ändern | – | – |
| E9 | Speichern: Original behalten / Original ersetzen | beide | neue Datei, Ersetzen → Android-Papierkorb | B |
| E10 | Pro-Sperre (Vorschau frei) | als Free Regler nutzen, speichern | Pro-Hinweis statt Speichern | B |
| E11 | Detail-Lupe / Histogramm | öffnen | verdeckt Bild nur kurz und klein | W |

### 2.10 Editor Video
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| EV1 | Videobühne fest, groß | Hoch-/Quervideo, alle Reiter | **gleich groß in jedem Reiter und wie die Foto-Bühne** | M, W |
| EV2 | Kürzen + Bereiche entfernen (Zeitleiste) | Griffe ziehen | Griffe ≥ 48 dp | B |
| EV3 | Tempo | 0,5× / 2× | – | – |
| EV4 | Ton: Lautstärke, Blenden, entfernen, als M4A | je einmal | – | – |
| EV5 | Format Ausfüllen / Ganzes Video, Seitenverhältnis | umschalten | nie verzerrt | M |
| EV6 | Farbe/Filter (Gerätefehler 2026-10-02) | Filter wählen | Vorschau ändert sich sichtbar | – |
| EV7 | Text/Sticker mit Zeitfenster, Standbild | anlegen | – | W |
| EV8 | Export (Qualität, Größe „ca.“, Vordergrunddienst) | speichern, App verlassen | Benachrichtigung mit Fortschritt | B |

### 2.11 Pro, Werbung, Einstellungen, Bedienungshilfen
| ID | Funktion | Prüfen | Erwartet | Regeln |
|---|---|---|---|---|
| X1 | Pro-Seite, Kauf (Testkonto), Wiederherstellen | nur ansehen/Testkauf | keine echten Käufe | B |
| X2 | Pro-Test 30 min (Rewarded) | Video ansehen | Pro-Werkzeuge an, Limit bleibt | – |
| X3 | UMP-Einwilligung, Werbung erst danach | Erststart | Einwilligung vor jeder Anzeige; keine Werbung anklicken | – |
| X4 | Einstellungen: Haptik, Erscheinungsbild, Später-Standard, Gestenhilfe, Favoriten schützen, Tasten zum Entscheiden, Endgültig löschen, Ausgenommene Ordner, Datenschutz | jede umschalten | wirkt sofort, bleibt nach Neustart | B |
| X5 | Dunkelmodus | System dunkel | alle Bildschirme lesbar | – |
| X6 | Schrift 200 % / Anzeigegröße groß | Systemeinstellung | nichts abgeschnitten, Medienfläche bleibt fest | M, B |
| X7 | TalkBack | Feed + Editor | Entscheidungs-Tasten erscheinen, alles beschriftet | B |
| X8 | Arabisch (RTL) / andere Sprache | Systemsprache | Gestenpfeile physisch, Zeitleisten nicht gespiegelt | – |
| X9 | Querformat / Tablet-Breite | drehen | Medienfläche fest, Werkzeuge daneben statt darüber | M, W |
| X10 | Zurück-Geste überall | jeder Bildschirm | ohne Animation zurück, nichts geht verloren ohne Nachfrage | B |

---

## 3. Bekannte Verdachtsstellen aus dem Code (zuerst prüfen)

| Stelle | Verdacht | Regel |
|---|---|---|
| `ToolsScreen` Vorschau | Höhe nur begrenzt (`heightIn(max = 420.dp)`): Fläche ändert sich je nach Bildform | M |
| `MediaPage` (Feed) | Rahmen folgt dem Seitenverhältnis des Mediums – prüfen, ob die äußere Fläche fest bleibt, auch bei unbekanntem Verhältnis/Laden | M |
| `VideoEditor` | Bühne über `aspectRatio` des Ausgabeformats – prüfen, ob gleich groß wie Foto-Bühne (`EditorScreen` 56 %) | M |
| `MovieScreen` | 50 % statt 56 % wie im Editor – vereinheitlichen? | M |
| `CollageScreen` | Werkzeugbereich `heightIn(max = 280.dp)` – Leinwandgröße je Reiter prüfen | M, W |
| Feed-Video, Betrachter-Video, Diashow | Steuerleisten liegen über dem Bild – müssen per Tipp vollständig verschwinden | W |
| `SeriesCompare` | Vergleichskacheln unterschiedlich groß bei gemischten Formaten? | M |

## 4. Ablauf

1. **Runde 1 – automatisch (A1–A5):** `StageConsistencyTest` und Overlap-/Tastengrößen-Test schreiben, alle Verdachtsstellen
   aus Abschnitt 3 messen und beheben, Screenshot-Raster erzeugen und sichten. Ergebnis: Liste PASS/FAIL je ID.
2. **Runde 2 – Gerät:** neue perf-APK, Abschnitt 2 von oben nach unten (ca. 2–3 h). Fehler ins Protokoll.
3. **Runde 3 – Nachbesserung:** jeden FAIL beheben, betroffene IDs erneut prüfen (Gerät + JVM).
4. Abnahme erst, wenn alle Zeilen PASS oder begründet BLOCKED sind.

### Fehlerprotokoll (Vorlage)
| ID | Gerät/Breite | Schritte | Erwartet | Tatsächlich | Bildschirmfoto | Status |
|---|---|---|---|---|---|---|
| | | | | | | FAIL |
