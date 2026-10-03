# PhotoMaid Android – alle Funktionen

Stand 03.10.2026, Branch `claude/product-ux-favorites`. Reine Übersicht, was die App kann. Wie jede Funktion
geprüft wird, steht in `docs/FUNKTIONS_PRUEFPLAN_2026-10.md` (gleiche Kennungen in Klammern).

Grundsätze, die überall gelten:
- Alles läuft lokal auf dem Gerät: kein Konto, kein Server, kein Upload, keine KI oder Bilderkennung.
- PhotoMaid löscht nie von selbst. Endgültig entfernt wird nur nach deiner Bestätigung, normalerweise über den Android-Papierkorb.
- Ansehen, Auswählen, Verschieben und Bearbeiten sind nie eine Aufräum-Entscheidung.
- Zahlen sind gemessen, nie geschätzt. Ist eine Größe unbekannt, steht „unbekannt“ da, nicht 0. Die einzige Ausnahme sind Video-Exportprofile, deren Größe ausdrücklich als „ca.“ beschriftet ist.
- Fotos und Videos werden nie verzerrt, nur eingepasst oder beschnitten.
- Die App gibt es in 16 Sprachen, mit Hell- und Dunkelmodus, Schrift bis 200 %, TalkBack, Arabisch (RTL) und Querformat.

App-Aufbau: fünf Tabs **Heute · Fotos · Aufräumen · Studio · Verlauf**.

---

## 1. Start, Einführung, Berechtigungen
- Einführung in fünf Schritten mit Demo-Karten, jederzeit überspringbar (S1)
- Angebotsseite am Ende der Einführung, später auch in der App aufrufbar (S2)
- Datenschutz- und Zugriffsseite: Zugriff erlauben, ablehnen oder „nur ausgewählte“, jeweils mit passender Folgeansicht (S3)
- Kurze Hinweise (Coach-Marks) beim ersten Besuch von Heute, Feed, Editor, Monaten, Serien und Raster; jeder kommt nur einmal (S4)

## 2. Heute (Tab 1)
- Großer Einstieg „Jetzt aufräumen“ / „Weiter aufräumen“ mit Vorschau und echter Anzahl (H1)
- „Wieder fällig“: Medien, deren Später-Termin erreicht ist (H2)
- Zeilen für Papierkorb und Später mit direktem Sprung (H3)
- Bis zu 6 Schnellzugriffe (Standard: Fotos und Videos): hinzufügen, umsortieren, entfernen. Sie sind nur Abfragen, gespeichert ohne Medien-IDs (H4)
- Gerätespeicher-Zeile, öffnet die Speicherübersicht (H5)
- Dezenter Pro-Hinweis in der Free-Version, schließbar (H6)

## 3. Aufräum-Feed (Kernfunktion)
- Wischgesten nach der tatsächlichen Fingerbewegung (F1–F5):
  - **hoch** = Behalten
  - **links** = PhotoMaid-Papierkorb (es wird noch nichts gelöscht)
  - **rechts** = Später mit Standardtermin (Vorgabe 2 Tage) und der Pille „In 2 Tagen wieder · Ändern“, über die man einen eigenen Termin wählt
  - **runter** = vorheriges Medium (keine Entscheidung)
  - Totzonen, zu kurze oder abgebrochene Wische: keine Aktion; eine Geste ist höchstens ein Schritt
- Dezente Richtungs-Pille beim Ziehen, nicht bei „runter“ (F6)
- Rückgängig in der Kopfzeile (F7)
- Videos im Feed: Tippen = Pause/Play, Zeitleiste zum Spulen; Video- und Fotofläche sind gleich groß (F8)
- Aktionsleiste unter dem Medium, nie eine Entscheidung (F9):
  - ♡ Favorit (Android-Systemfavorit)
  - Teilen (unter 360 dp im ⋯-Menü)
  - In Ordner verschieben
  - ✏️ Bearbeiten
  - ⋯ weitere Aktionen
- „Favoriten schützen“ (Standard an): ein Wisch nach links auf einen Favoriten fragt nach (F2)
- Bedienungshilfe „Tasten zum Entscheiden“ (Standard aus; bei TalkBack und Schaltersteuerung automatisch): Die Tasten wirken genau wie die Gesten (F10)
- Werbekarte (nur Free): erst nach 30 Entscheidungen, dann alle 7, mindestens 60 s Abstand, höchstens 30 am Tag. Sie ist nie eine Entscheidung; „runter“ und Rückgängig überspringen sie (F11)
- Sitzungs-Zusammenfassung mit echten Zahlen, einem einmaligen Pro-Angebot und einer ruhigen Anzeige ganz unten (F12)
- Tageslimit Free: 20 endgültige Entfernungen pro Tag. Das Limit-Blatt bietet freiwillig per Video „+20 für heute“ an, 1× pro Tag; nur der Kauf hebt das Limit auf (F13)
- Reihenfolge wählbar (neu, alt, groß …); sie ändert nur die Abfolge (F14)

## 4. Aufräumen (Tab 3)
- Kategorien: Fotos, Videos, Screenshots usw. – jeweils mit Anzahl, der Feed startet gefiltert (C1)
- Sammlungen = reine Filter: fertige für alle, eigene mit Pro (C2)
- Monate mit Fortschritt („durchgesehen“), „Weitermachen“ und „Neu seit dem letzten Aufräumen“ (C3)
- Später ohne Termin („Beiseitegelegt“): kommt nie von selbst zurück (L2)
- Fotoserien vergleichen (Pro): gleicher Ordner, ≤ 10 s Abstand, mindestens 3 Fotos – nur „kurz hintereinander aufgenommen“, nie „ähnlich“. Erst „n behalten · m in den Papierkorb“ entscheidet; keine Werbung dabei (C4)
- Raster mit Mehrfachauswahl und Verschieben, nie eine Entscheidung (C5)
- Große Dateien mit einstellbarer Grenze; Dateien mit unbekannter Größe getrennt (C6)
- Exakte Duplikate: Suche mit Fortschritt, abbrechbar; eine Datei behalten, der Rest in den Papierkorb (C7)
- Dateien/Quellen: Ordner per Android-Dateiauswahl verbinden, durchsuchen, wieder trennen. Trennen löscht nichts. Ab Android 17 kommen Ordner-Dateien in den Papierkorb des Speicherorts, sonst gibt es eine Warnung vor dem endgültigen Löschen (C8)
- Speicherübersicht nur mit gemessenen Werten (C9)
- Ausgenommene Ordner (inkl. Unterordner): sie fehlen in Aufräumen, bleiben aber unter „Fotos“ sichtbar (C10)

## 5. Papierkorb & Löschen
- PhotoMaid-Papierkorb: sortierbar (hinzugefügt, aufgenommen, Größe), mit Vorschau und Abwählen (P1)
- Leeren schiebt die Medien in den Android-Papierkorb; Android fragt nach, Abbrechen ändert nichts (P2)
- „Endgültig löschen“ als Einstellung (Standard aus), mit deutlich anderer Systembestätigung (P3)
- Ansicht des Android-Papierkorbs, nur auf ausdrückliche Auswahl: Restzeit sehen, wiederherstellen, löschen (P4)
- RAW+JPEG-Paare: Die RAW-Datei liegt hinter dem JPEG und geht beim Papierkorb, Ausblenden und Verschieben mit (P5)

## 6. Später & Verlauf (Tab 5)
- Später-Liste nach Fälligkeit (fällig/kommend): Datum ändern, jetzt prüfen, entfernen (L1)
- Erinnerung per Benachrichtigung (Opt-in); Tippen öffnet die fälligen Medien (L3)
- Verlauf mit Filtern Alle / Behalten / Bereinigt / Später (V1)
- „Bearbeitet“: Liste gespeicherter Fassungen; ersetzte Originale lassen sich wiederherstellen (V2)

## 7. Fotos – Mediathek (Tab 2)
- Zeitachse Tag/Monat/Jahr, mit zwei Fingern umschalten (G1)
- Filter und Suche, lokal und ohne Akzente („Cafe“ findet „Café“) (G2)
- Auswahl mit Aktionen: Papierkorb, In Ordner verschieben, Film, Diashow (G3)
- Vollbild-Betrachter: blättern, runter = schließen, Zoom; keine Wisch-Aktion nach oben (G4)
- Betrachter-Video: Tippen blendet Steuerung und Infos aus/ein (G5)
- Favorit ♡ im Betrachter (G6)
- Info: Datum, Größe, Ordner, Kamera (G7)
- Ordneransicht: Ordnerbaum, Sortierung je Ordner, Gruppen (nur Name und Pfade), „Neuer Ordner“ (Medien wählen → verschieben) (G8)
- Favoriten-Ansicht mit den Systemfavoriten (G9)
- „In PhotoMaid ausblenden“ für Medien und Ordner, gilt für Fotos und Aufräumen; Dateien bleiben unverändert, es gibt keinen Tresor (G10)
- „In den Papierkorb“ aus der Mediathek: PhotoMaid-eigen, Favoriten geschützt, mit Rückgängig
- Diashow: Start, Pause, Tempo (G11)
- Kopfhörer-Tasten: nur Play/Pause/Spulen, nie „Weiter“ (G12)

## 8. Studio (Tab 4)
- Übersicht: Erstellen, Werkzeuge, Entwürfe, „Bearbeitet“ (T1)
- Medienauswahl, nie eine Entscheidung (T2)
- **Collage** (T3, T4):
  - Arten: Raster mit Trennlinien, Frei, Streifen
  - Felder verschieben, zoomen und tauschen; Abstand, Hintergrund, Format, Markieren
  - Vorschau = Export, neues Bild in `Pictures/PhotoMaid/`
  - Free: Raster/Streifen bis 4 Fotos, 3 Entwürfe. Pro bzw. 30-Minuten-Test: bis 20 Fotos, Frei, A4-Druck, 50 Entwürfe
  - Kein Wasserzeichen
- **Video zusammenfügen** (T5): Clips sortieren und kürzen, Übergänge „keiner“ oder „über Schwarz“, Format, Musik aus eigener Datei. Ergebnis in `Movies/PhotoMaid/`, Speichern mit Pro
- **Diashow als Video** (T6): Dauer pro Bild, sanfter Zoom
- **Werkzeuge** (Stapel, Free bis 10 Dateien; Ergebnisse immer als neue Dateien):
  - Verkleinern (T7)
  - Umwandeln, z. B. HEIC → JPEG/PNG; passende Dateien werden übersprungen (T8)
  - Standort entfernen (T9)
  - Originale auf Wunsch in den Android-Papierkorb (Standard aus) (T10)
  - Screenshots zusammenfügen (T11)
  - GIF aus Fotos und GIF aus Video bis 10 s, beides Pro (T12, T13)
  - „frei“ wird erst angezeigt, wenn alle Originale im Papierkorb sind und alle Größen bekannt sind
- Teilen ohne Speichern (T14)

## 9. Foto-Editor (✏️ aus Feed, Betrachter oder Studio)
- Gemeinsames Editor-Gerüst (Redesign 2026-10):
  - feste Bühne, die nicht vom Medium oder Werkzeug abhängt
  - keine Bedienelemente auf dem Bild
  - Hilfsleiste mit Rückgängig/Wiederholen, Größe/Zoom, Vergleich (halten), Histogramm und Warnung
  - scrollende Werkzeugzone
  - Navigation **Zuschnitt · Anpassen · Filter · Markieren · Mehr** (unter Mehr: Retusche, Rahmen, Größe) (E1)
- Zuschnitt: Seitenverhältnisse, Drehen, Spiegeln, Begradigen; Perspektive über vier Ecken (kostenlos) (E2)
- Anpassen (Speichern mit Pro, Vorschau frei) (E3):
  - Gruppen Licht, Farbe, Farbmischer (helligkeitserhaltend), Detail, Effekte
  - Regler: Belichtung, Helligkeit, Kontrast, Lichter, Schatten, Weiß, Schwarz, Wärme, Tönung, Sättigung, Dynamik, Klarheit, Schärfen (mit Radius und Schwelle), Vignette, Körnung u. a.
- Filter mit Stärke und eigene Vorlagen (sichern, umbenennen, löschen) (E4)
- Retusche: Rote Augen (Farbregel, kostenlos) und Bereiche Kreis/Verlauf/Pinsel (rein manuell, Pro); keine Erkennung von Gesichtern, Augen oder Himmel (E5)
- Markieren: Text (Tastatur direkt in der Werkzeugzone), Sticker, Zeichnen, Formen, Abdecken (E6)
- Rahmen (E7)
- Größe: nur proportional, nie hochskalieren (E8)
- Speichern als eigene Seite (E9):
  - **Als Kopie speichern** – das Original bleibt unverändert (immer vorausgewählt)
  - **Kopie speichern und Original in den Papierkorb** – nur auf ausdrückliche Wahl; die Aufbewahrungsfrist legt Android fest
  - Zielordner und „Standort entfernen“
  - Nur teilen (ohne Kopie)
  - Die gespeicherte Fassung gilt als „Behalten“ und zählt nicht zum Tageslimit
- Pro-Sperre: Die Vorschau ist frei, Speichern zeigt den Pro-Hinweis bzw. „Nur Zuschnitt speichern“ (E10)
- Detail-Lupe, Histogramm, Warnung bei überstrahlten oder abgesoffenen Stellen (E11)
- Keine Werbung im Editor

## 10. Video-Editor
- Navigation **Kürzen · Anpassen · Filter · Markieren · Mehr** (unter Mehr: Tempo, Ton, Format) (EV1)
- Ein Player pro Editor. Ein Standbild liegt auf der Bühne, bis das erste Videobild erscheint. Hilfsleiste mit Play/Pause, Zeit, Vorschau stumm und Standbild sichern
- Kürzen mit Filmstreifen, Griffen und Abspielposition; Bereiche wirklich entfernen. „Exportinfo“ erklärt, ob schnell gekürzt oder neu kodiert wird (EV2)
- Tempo, z. B. 0,5× bis 2× (EV3)
- Ton: Lautstärke, Ein-/Ausblenden, Ton entfernen (keine Tonspur), Ton als M4A sichern (EV4)
- Format: Ausfüllen oder ganzes Video, Seitenverhältnis, Drehen – nie verzerrt (EV5)
- Farbe und Filter als Farbtabelle aus derselben Pipeline wie der Export (EV6)
- Text und Sticker mit Zeitfenster; Standbild als Foto sichern (EV7)
- Export mit Qualitätsprofilen (Größe als „ca.“), langer Export im Vordergrunddienst mit Fortschritt; nur gekürzte Videos werden auf dem schnellen Weg ohne komplette Neukodierung gespeichert (EV8)

## 11. Pro, Werbung, Einstellungen, Bedienungshilfen
- Pro-Seite mit Einmalkauf über Google Play Billing (kein Abo) und „Käufe wiederherstellen“ (X1)
- Pro-Test: 30 Minuten per Video, 1× pro Tag. Er schaltet die Pro-Werkzeuge frei, hebt aber nie das Papierkorb-Limit auf (X2)
- Werbung nur in Free, erst nach UMP-Einwilligung; Anfragen enthalten nie Medien, Namen, Pfade oder IDs (X3)
- Einstellungen (X4):
  - Haptik
  - Erscheinungsbild (Hell/Dunkel/System)
  - Standardtermin für Später
  - Gestenhilfe
  - Favoriten schützen
  - Tasten zum Entscheiden
  - Endgültig löschen
  - Ausgenommene Ordner
  - Erinnerungen
  - Datenschutz
- Dunkelmodus auf allen Bildschirmen (X5)
- Große Schrift bis 200 %: nichts abgeschnitten, Leisten scrollen statt zu schrumpfen (X6)
- TalkBack: alles beschriftet, Entscheidungs-Tasten im Feed (X7)
- 16 Sprachen inkl. Arabisch (RTL). Gestenpfeile bleiben physisch, Zeitleisten werden nicht gespiegelt (X8)
- Querformat und breite Fenster: Werkzeuge neben der Bühne statt darüber (X9)
- Zurück-Geste überall ohne Animation, nichts geht ohne Nachfrage verloren (X10)
