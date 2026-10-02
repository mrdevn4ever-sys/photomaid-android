# Ausbauplan „Fertig werden statt mehr Werkzeuge“ (2026-10-04)

Ziel: Aus „ich wische durch meine Bilder“ wird ein Aufräumen, das spürbar schneller geht und bei dem man **fertig wird**.
Umfang: alle Punkte mit Wert „mittel“ oder „hoch“ aus der Produktbewertung + Einstiegs-Tutorials für jede neue Funktion.
Nicht im Umfang: KI, Tresor, Komprimierung, Kontakt-/Geräte-Cleaner, Punkte/Serien, „An diesem Tag“, Widget.
Prüfstatus: NOT RUN (nur Plan).

Feste Regeln bleiben unverändert: Gesten, eine Gesten-State-Machine, keine automatische Löschung, keine erfundenen Zahlen,
Verschieben/Vergleichen ist nie automatisch „Behalten“, Werbung nie mitten in Vergleich, Rückgängig oder Papierkorb-Bestätigung.

---

## Phase 0 – Absichern (klein, zuerst)

| # | Änderung | Details | Free/Pro |
|---|---|---|---|
| 0.1 | ~~„werden frei“~~ – **entschieden 2026-10-02: „gespart“ bleibt** | Umgesetzt: „gespart“ (de) / „saved“ (en) nach dem Leeren, der bestehende Hinweis „Speicher wird erst frei, wenn sie dort endgültig gelöscht werden“ bleibt. Keine weitere Änderung | – |
| 0.2 | Werbeabstand | Takt bleibt 7 Entscheidungen, zusätzlich **mind. 60 s seit der letzten Karte** (`FeedAdPolicy.MIN_GAP_MS`). Nie in Vergleich (Phase 3), Mehrfachauswahl (Phase 4), Rückgängig oder zwischen Papierkorb und Android-Dialog | – |
| 0.3 | Ordner vom Aufräumen ausnehmen | „Nicht beim Aufräumen anzeigen“ im Ordner-Menü (Schnellzugriff-Bearbeiten, Ordnerliste, ⋯ im Feed: „Diesen Ordner ausnehmen“). Ausgenommene Medien erscheinen in keinem Feed, keiner Zahl, keiner Monatsübersicht. Liste in Einstellungen › „Ausgenommene Ordner“ mit „Wieder anzeigen“. Gespeichert nur als Pfade (keine Medien-IDs) | Free |
| 0.4 | Große Videos besser beurteilen | Große Dateien: Grenze direkt im Kopf änderbar (Chips 50 / 100 / 250 / 500 MB / eigene), Sortierung „Größte zuerst“ als Standard (gibt es schon). Zeilen zeigen bei Videos **Dauer · Größe · Auflösung**; Vorschau mit gut greifbarer Zeitleiste | Free |

## Phase 1 – Monatsübersicht, „Neu seit dem letzten Aufräumen“, Weitermachen (hoch)

### 1.1 Monate
- Neuer Bereich in **Aufräumen › Mediathek › „Nach Monat“** und als Karte auf „Heute“ („Dein Fortschritt“).
- Liste aller Monate mit Medien (nach `DATE_TAKEN`, sonst `DATE_ADDED`), neueste oben, gruppiert nach Jahr.
- Je Monat echte Zahlen aus dem Ledger (eine Abfrage, Monate sind nur Filter – wie Schnellzugriffe):
  - **„127 von 184 durchgesehen“** = Medien mit Entscheidung (Behalten, Papierkorb, entfernt, Später mit Termin) / alle.
  - **„8 für später“** getrennt – ein Monat ist „durchgesehen“, auch wenn Später-Termine offen sind („Durchgesehen · 8 für später“).
  - Zustand: **Noch nicht begonnen** · **In Arbeit** (Fortschrittsbalken) · **Durchgesehen** (Haken).
- Tipp öffnet den Feed des Monats; ausgenommene Ordner (0.3) zählen nicht.
- Keine Speicherung je Monat: alles abgeleitet aus Medium + Ledger (eine Wahrheit, vgl. `AppModel.pendingQueue`).

### 1.2 Weitermachen
- Auf „Heute“ ersetzt die Startkarte bei laufender Arbeit den Text: **„Weitermachen: September 2026 · 57 offen“**.
- Gemerkt wird nur die zuletzt benutzte **Quelle** (Monat/Schnellzugriff/Kategorie, ohne Medien-ID) + Zeitpunkt. Der Feed
  beginnt automatisch beim ersten offenen Medium – nach App-Schließen, Neustart, abgebrochenem Systemdialog identisch.
- Ist die Quelle fertig: nächster nicht fertiger Monat wird vorgeschlagen („Weiter mit August 2026“).

### 1.3 Neu seit dem letzten Aufräumen
- Karte auf „Heute“: **„32 neue Fotos und Videos · seit deinem letzten Aufräumen“ · Ansehen**.
- „Neu“ = `DATE_ADDED` (auf dem Gerät hinzugekommen) nach dem Ende der letzten Sitzung und noch offen – auch alte Fotos,
  die heute aufs Gerät kamen. Zeitpunkt „letztes Aufräumen“ = Ende der letzten Sitzung mit mindestens einer Entscheidung.
- Verschwindet, wenn 0. Kein Ersatz für „Diese Woche“ (Aufnahmedatum).

### Technik
- `MediaAsset.dateAdded` ergänzen (Spalte wird schon abgefragt). Core: `MonthKey`, `MonthProgress(total, decided, later)`,
  `ProgressRules` (rein, testbar). `AppSettings`: `lastSessionEnd`, `continueSource` (Quelle ohne IDs).
- Tests: Fortschritt über Quellen hinweg identisch (Behalten in „Fotos“ zählt im Monat), Später ≠ fertig, ausgenommene
  Ordner zählen nicht, „Neu seit“ mit altem Aufnahme- aber neuem Hinzufüge-Datum, Weitermachen nach Prozessende.

## Phase 2 – Fotoserien vergleichen (hoch, Pro)

- **Erkennung ohne KI, ehrlich benannt:** Medien aus demselben Ordner, Aufnahmezeit ≤ 10 s Abstand zur jeweils vorigen,
  mind. 3 Stück = Serie. Bezeichnung nur **„Kurz hintereinander aufgenommen“** – nie „ähnlich“ oder „bestes Foto“.
- **Einstieg im Feed:** Ist das aktuelle Medium Teil einer Serie, erscheint in der Aktionsleiste ein Knopf **„Serie · 7“**
  (keine Entscheidung, außerhalb der Gestenfläche). Zusätzlich manuell: ⋯ › „Mit Nachbarn vergleichen“ (±5 Medien).
- **Vergleichsansicht:**
  - Oben Raster der Serie (Kacheln, ausgewählte mit Haken „Behalten“).
  - Darunter zwei Bilder groß **nebeneinander** (Hochformat: übereinander), Tipp auf eine Kachel tauscht die rechte/untere
    Seite; Doppeltipp = Vollbild mit Zoom, Zoom an beiden Seiten synchron (optional abschaltbar).
  - Nutzer wählt selbst, was bleibt. Unten eindeutig: **„2 behalten · 5 in den Papierkorb“** (PhotoMaid-Papierkorb,
    nichts gelöscht). Ein Ledger-Commit je Medium (Item-ID, Revision, Command-ID, Aktion), gemeinsames Rückgängig.
  - Nicht ausgewählt ≠ automatisch Papierkorb ohne Klick: die Aktion steht erst nach der Bestätigung fest.
- **Free:** Knopf sichtbar mit Pro-Abzeichen; Tipp → Angebot (oder 30-Minuten-Test). Nach Ablauf eines Tests bleiben
  getroffene Entscheidungen; nur neue Vergleiche sind gesperrt.
- Tests: Serienbildung (Zeitabstand, Ordnergrenze), keine Entscheidung ohne Bestätigung, Commit-Anzahl, Rückgängig,
  keine Werbekarte während der Ansicht, Pro-Sperre.

## Phase 3 – Schneller sortieren (mittel–hoch, Free)

### 3.1 Ordnerziel schneller
- Ordnerdialog oben: **Angepinnte Ordner** (max. 5, lange drücken → anpinnen), **Zuletzt verwendet** (5), **Suche**,
  dann alle Ordner. „Neuer Ordner“ bleibt.
### 3.2 Mehrfachauswahl
- In Raster-Ansichten (Monat, Schnellzugriff, Serie, Papierkorb nicht) Modus **„Auswählen“**: Kacheln antippen, Ziehen
  über mehrere Kacheln wählt einen Bereich. Unten: **„18 ausgewählt · In Ordner verschieben“** (+ „Favorit“).
- Ein einziger Android-Dialog (`createWriteRequest` mit allen URIs), danach Prüfung je Medium:
  „18 nach „Wohnungsrenovierung“ verschoben“ bzw. ehrlich „16 von 18 verschoben – 2 nicht möglich“.
- **Verschieben ist keine Entscheidung** (kein Behalten, keine Feed-Bewegung). Ordner-Schnellzugriffe aktualisieren sich.
- Neue Raster-Ansicht „Übersicht“ als Umschalter im Monat/Schnellzugriff (Feed | Raster), Feed bleibt Standard.
- Tests: ein Systemdialog für viele, Erfolgsmeldung erst nach bestätigtem Pfad, Teil-Erfolg, keine Ledger-Ereignisse.

## Phase 4 – Einstiegs-Tutorials (zu jeder neuen Funktion, nicht vergessen)

Gleiches System wie heute (`design/CoachMarks.kt`, „n/N · Tutorial überspringen · Weiter/Fertig“, einmal je Funktion,
Überspringen merkt es sich, blockiert nichts dauerhaft). Je ein eigenes „gesehen“-Flag.

| Tour | Wann | Schritte |
|---|---|---|
| **Heute (erweitert)** | Neue Nutzer: im bestehenden Heute-Tutorial; bestehende Nutzer: einmal kurz „Neu“-Tour (2 Schritte) | + „Dein Fortschritt“ (Monatskarte), + „Neu seit dem letzten Aufräumen“ |
| **Monate** | erstes Öffnen von „Nach Monat“ | 1 Monatszeile („So weit bist du“), 2 „für später“ ≠ offen, 3 Umschalter Feed/Raster |
| **Serie vergleichen** | erstes Erscheinen des Knopfs „Serie“ im Feed (nur Hinweis-Pille, 1 Schritt) + erstes Öffnen der Vergleichsansicht | 1 Raster („Tippe, was bleiben soll“), 2 Zwei Bilder groß / Zoom, 3 „Erst dieser Knopf entscheidet“ |
| **Mehrfachauswahl** | erstes Aktivieren von „Auswählen“ | 1 Tippen/Ziehen wählt, 2 „In Ordner verschieben“, 3 „Verschieben ist keine Entscheidung“ |
| **Ordner ausnehmen** | erstes Öffnen des Ordner-Menüs | 1 Schritt: „Nicht beim Aufräumen anzeigen – jederzeit in Einstellungen rückgängig“ |
| **Onboarding** | neue Nutzer | Seite 1 (Geschichte) erwähnt „Monat für Monat“; Angebot: Pro-Zeile ergänzt „Serien vergleichen“ |

Einstellungen › Hilfe: **„Tutorials erneut zeigen“** (setzt alle Flags zurück).

## Phase 5 – Qualität und Abnahme

- JVM: Core-Regeln (Monat, Serie, Ausnahmen, Werbeabstand), Compose-UI-Tests je Funktion, Screenshots hell/dunkel/200 %.
- Texte: alle neu in `strings.xml` (Deutsch), Liste neuer Schlüssel für die Übersetzung nachliefern.
- Doku: CLAUDE.md (neue Regeln: Ausnahmen, Serie, Werbeabstand, „werden frei“), PORTING_MATRIX (Abweichungen von iOS).
- Gerätetest-Prompt je Phase (Monate mit echten Medien verschiedener Monate, Serien per Burst-Aufnahmen, 50 Medien auf
  einmal verschieben, Galerie-Gegenprobe in Google Fotos/Realme-Galerie, Performance Raster mit 5 000 Medien).

## Reihenfolge und grober Aufwand (Entwicklung)

| Phase | Inhalt | Aufwand |
|---|---|---|
| 0 | Absichern (0.1–0.4) | klein |
| 1 | Monate + Weitermachen + Neu seit | mittel |
| 2 | Serien vergleichen (Pro) | mittel–groß |
| 3 | Ordnerziel + Mehrfachauswahl + Raster | mittel |
| 4 | Tutorials (laufend je Phase mitgebaut) | klein je Phase |
| 5 | Tests, Screenshots, Doku, Gerätetest | je Phase |

Jede Phase endet mit grünen Tests, perf-Build, Commit und einem Gerätetest-Prompt; Release erst nach Geräteabnahme.

## Offene Entscheidungen

1. ~~0.1: „werden frei“~~ – entschieden: **„gespart“** bleibt (2026-10-02).
2. 0.2: Mindestabstand 60 s zusätzlich zum Takt 7 – empfohlen **ja** (sonst nur Takt 7 wie heute).
3. Phase 2: Serienschwelle 10 s / mind. 3 Medien als Start – anpassbar nach Gerätetest.
4. Phase 3: Raster-Umschalter auch im normalen Kategorie-Feed (Fotos/Videos) oder nur in Monat/Schnellzugriff?

---

## Ergänzung 2026-10-02: Ideen aus dem Tagescheck (geprüft gegen den Code)

Stand der App: Commit 022b86e + Fallback Englisch/„gespart“. Prüfstatus: NOT RUN (nur Plan, nichts gebaut).

### Schon vorhanden – nichts zu tun
| Idee (Quelle) | Stand in PhotoMaid |
|---|---|
| Abschlusskontrolle vor dem Entfernen (PhotoSwooper) | Gibt es: Wischen legt nur in den PhotoMaid-Papierkorb; Leeren erst nach Vorschau + Bestätigung + Android-Dialog. Zusammenfassung zeigt „Papierkorb ansehen (n)“ |
| Größe + bei Videos Dauer auf der Karte (Android-Doku) | Gibt es: Infozeile im Feed „Datum · Größe“, bei Videos zusätzlich Dauer (`ReviewFeedScreen` Infozeile, MediaStore-Spalten, offline). Unbekannte Größe wird nicht als 0 gezeigt |
| Teilen vom aktuellen Bild (Swipelit) | Gibt es: Aktionsleiste „Teilen“ (Android-Teilen-Auswahl, kein Upload) |
| Während des Wischens in Geräteordner verschieben (Sponge) | Gibt es: Aktionsleiste „In Ordner verschieben“ (`createWriteRequest`); schneller mit Phase 3.1 |
| Videos als eigener Stapel (Swipelit) | Gibt es: Mediathek/Schnellzugriff „Videos“ |
| Fortschritt, Pause und Weiter an derselben Stelle (Swipewipe) | Teilweise: Feed beginnt immer beim ersten offenen Medium. Monatsfortschritt + „Weitermachen“ sind schon als **Phase 1.1/1.2** geplant |

### Neu in den Plan aufgenommen
| # | Änderung | Details | Wert | Free/Pro |
|---|---|---|---|---|
| 0.5 | **Ordner-Dateien in den Papierkorb statt endgültig** (Android 17+) | `DocumentsContract.trashDocument` gibt es ab API 37 (geprüft in `android.jar` 36 vs. 37). Wenn das Dokument `FLAG_SUPPORTS_TRASH` meldet: Papierkorb statt `deleteDocument`, Text „liegt im Papierkorb des Speicherorts“; sonst wie heute endgültig mit deutlicher Warnung. Größen-/„gespart“-Logik unverändert. Tests mit Fake-Provider beider Fälle | hoch (Sicherheit) | Free |
| 0.6 | **Fertige Sammlung „Fotos älter als 1 Jahr“** (Wiper) | Heute gibt es fertig nur „Videos älter als 1 Jahr“; für Fotos nur als eigene Sammlung (Pro). Fünfte fertige Sammlung `fixed(5)`, PHOTOS + `OlderThan(365)`, kostenlos | mittel | Free |
| 1.4 | **Monat erneut durchsehen** (Swipewipe) | Durchgesehene Monate zeigen einen Haken (statt durchgestrichen); Menü „Erneut durchsehen“ öffnet alle Medien des Monats inkl. behaltener. Bestehende Entscheidungen bleiben, bis neu gewischt wird (ein Commit je Wisch). Nichts wird zurückgesetzt | mittel | Free |
| 1.5 | **Später ohne Termin** („Lesezeichen“, Swipewipe) | In „Ändern“ der Später-Pille Option „Ohne Termin – ich hole es selbst“. Kommt nie automatisch zurück, steht in „Später“ unter „Ohne Termin“ mit „Zurück in den Stapel“. Core: `Later` mit `dueDay = null` als eigener Zustand, eigener Test, kein Erinnerungs-Push | mittel | Free |
| 3.3 | **Reihenfolge im Feed** (Sponge) | Chip im Feed-Kopf: Neueste (Standard) · Älteste · Größte zuerst · Zufällig. Zufall mit festem Startwert je Sitzung, damit „runter“ stabil zurückführt. „Name“ nur in Dateien-Ordnern. Gemerkt je Quelle (ohne Medien-IDs) | mittel (Größte) / gering (Zufall) | Free |

### Braucht deine Entscheidung (Regelkonflikt)
| Idee | Problem | Empfehlung |
|---|---|---|
| Tasten statt/neben Wischen (Swiftly) | Widerspricht der festen Regel „Keine Entscheidungs-Buttons im Feed (nur TalkBack/Schaltersteuerung)“ | **Nur als Bedienungshilfe**: Einstellung „Tasten zum Entscheiden“ (Standard aus) unter Bedienungshilfen, Tasten außerhalb der Gestenfläche, gleicher Commit-Pfad wie Gesten. Sonst weglassen |

### Release / Recht (Store-Schritte, kein App-Code)
| # | Punkt | Was zu tun ist |
|---|---|---|
| R1 | Play-Richtlinie Foto-/Video-Berechtigungen | `READ_MEDIA_IMAGES/VIDEO` sind nur für Apps mit Kernfunktion „Medien verwalten“ erlaubt; der Photo Picker passt für einen Aufräumer nicht (ganze Mediathek nötig). In der Play Console die **Berechtigungs-Erklärung** ausfüllen (Begründung: Galerie aufräumen, alles lokal). Text vorbereiten aus `docs/PRIVACY_DATA_MAP.md` |
| R2 | Datenschutz-URL + Data-Safety-Formular | Öffentliche Seite nötig (weiter offen). Grundlage `docs/PRIVACY_DATA_MAP.md`; Werbe-SDK (AdMob) und Kauf angeben |
| R3 | Einwilligung EWR/UK/Schweiz | Erledigt im Code (UMP vor AdMob). Im AdMob-Konto die Einwilligungsnachricht (GDPR-Message) veröffentlichen, sonst keine Anzeigen |
| R4 | Zielgruppe / Families | In der Play Console **keine Altersgruppe unter 13** wählen (Zielgruppe Erwachsene). Dann gilt die Families-Richtlinie nicht, kein Age-Screen nötig. Sollen Kinder Zielgruppe sein: nur zertifizierte Werbung, nicht personalisiert – nicht empfohlen |

### Reihenfolge (ergänzt)
0.5/0.6 kommen zu Phase 0 (klein–mittel), 1.4/1.5 zu Phase 1, 3.3 zu Phase 3; Tutorials (Phase 4) bekommen je einen Schritt
für „Ohne Termin“ und „Reihenfolge“. Alle neuen Texte in allen 16 Sprachen (Deutsch + Englisch selbst, Rest als
Übersetzungsliste).
