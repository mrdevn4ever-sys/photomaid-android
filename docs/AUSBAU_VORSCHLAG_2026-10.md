# Nächste Ausbaustufe – Vorschlag 03.10.2026

Grundlage: Rückmeldung zur `docs/FUNKTIONSLISTE.md`. Richtung: keine weiteren Filter, sondern ein zusammenhängender
Ablauf **auswählen → ordnen → verbessern → ausgeben → kontrolliert archivieren**. Den Aufräum-Feed ergänzen, nicht überladen.
Status: **Paket 1 (Organisieren) umgesetzt** am 04.10.2026 (JVM-Tests PASS, Gerät NOT RUN); alle übrigen Punkte NOT RUN (nicht beauftragt).

Spalte „Regeln“: Prüfung gegen `CLAUDE.md` (lokal, keine KI, nie automatisch löschen, keine erfundenen Zahlen, Ansehen ≠ Entscheidung).

## Empfohlene Reihenfolge

| # | Paket | Inhalt | Regeln / Grenzen | Technik |
|---|---|---|---|---|
| 1 | Organisieren | Virtuelle Alben (bewusst zusammengestellt, Mehrfachzuordnung, „Aus Album entfernen“ ≠ löschen), eigene Schlagwörter + Notizen, dauerhafter Auswahlkorb (übersteht Unterbrechungen, keine Aufräum-Bedeutung, kein eigener Tab) | Ordner = Speicherort, Sammlung = Suchbedingung, Album = Nutzerauswahl. Nur PhotoMaid-intern. Keine Erkennung. Nie eine Entscheidung, keine neue Wischrichtung | Room, Viele-zu-viele-Tabellen; Zuordnung über MediaStore-ID + Abgleich (Größe/Datum), fehlende Zuordnungen sichtbar |
| 2 | Arbeitsabläufe | Benannte Abläufe aus vorhandenen Stapelwerkzeugen (z. B. JPEG → 2.000 px → Standort entfernen → Name → Teilen) mit Vorschau „was passiert“ und Ergebnisliste (erfolgreich/übersprungen/fehlgeschlagen); Ziel-Dateigröße (z. B. ≤ 2 MB je Foto, ≤ 20 MB Video, Budget je Auswahl) mit Mindestauflösung/-qualität | Kein Löschen in Abläufen. Zielgröße ist Vorgabe, keine Behauptung: Ergebnis wird gemessen; nicht erreichbar → erneut verarbeiten oder klar erklären, nie stillschweigend zu groß | `core/.../batch/` erweitern; Foto: Qualitäts-/Größensuche mit Messung; Video: Bitrate aus Ziel, danach Messung |
| 3 | Teilen-Ziel | „Mit PhotoMaid bearbeiten“: Bearbeiten, Verkleinern, PDF, zum Album; bei mehreren Bildern direkt Mehrfachauswahl | Übergabe ist nie „Behalten“ und erzeugt keine dauerhafte Kopie ohne Speichern | `ACTION_SEND` / `ACTION_SEND_MULTIPLE`, URI-Rechte prüfen, nur Bild/Video-MIME |
| 4 | Dokumente | Mehrseitiges PDF (Seiten sortieren, Ränder, Seitenformat), Kontaktbögen mit Nummern/Beschriftung; Vier-Ecken-Perspektive für Dokumentseiten wiederverwenden | Keine Text-/Dokumenterkennung. Ergänzt die A4-Collage | `PdfDocument` / `PrintedPdfDocument`, Druckframework |
| 5 | Archivieren | Auf USB/SD auslagern: kopieren → Ziel erneut lesen und mit Quelle vergleichen (Prüfsumme) → Ergebnis → optional Originale zur Bereinigung auswählen; Sicherung der PhotoMaid-Daten (klein) und Projektarchiv (mit ausgewählten Medien) | Zweite Kopie auf demselben Speicher ist kein Auslagern. Geprüfte Kopie ≠ Garantie für den Datenträger. Unterbrechung: nur vollständig geprüfte Dateien gelten. Speicherziel sichtbar (SAF kann Cloud-Anbieter zeigen → nicht automatisch „lokal“). Wiederherstellen ordnet neu zu, Lücken als Liste | SAF (`ACTION_OPEN_DOCUMENT_TREE`), Anbieter-Fähigkeiten prüfen, Journal je Datei |
| 6 | Video-Markierungen | Benannte Zeitmarken + Zusammenstellung markierter Szenen; bewegliche Abdeckungen/Pfeile mit manuellen Schlüsselpositionen und Zwischenwerten | Datei bleibt unverändert. Zwischenpositionen folgen keiner erkannten Bewegung – Nutzer prüft den Abschnitt; nie „automatisch anonymisiert“ | Media3 zeitabhängige Overlays (Frame-Zeitstempel) |

## Fotoeditor danach (bevorzugt)

| Werkzeug | Grenze | Technik |
|---|---|---|
| Gradationskurven (RGB + einzeln R/G/B) | Unter „Erweitert“, gleiche Vorschau-/Export-Pipeline | Kurve → LUT in `EditEngine` |
| Weißabgleich-Pipette | Mittel über kleinen Bereich; überbelichtete/fast schwarze Stellen → keine Korrektur, Hinweis | Ergebnis setzt Wärme/Tönung (weiter regelbar) |
| Klonstempel | Quellpunkt, Größe, weiche Kante, Deckkraft, Rückgängig; nie „Objekt entfernen“, nichts wird generiert | Neue Ebenenart im Rezept |

Später / eigener Auftrag: LUT-Import (`.cube`, Größenlimit, Farbraum), manuell freistellen → eigene Sticker (PNG), Untertitel (SRT/WebVTT, manuell, keine Spracherkennung), Bild-in-Bild (zunächst genau zwei Quellen), Tempo je Bereich + Standbild halten (`SpeedProvider`), Sprachkommentar (nur nach Tippen, keine Transkription), Motion Photos (nur unterstützte Varianten), Aufnahmedatum korrigieren (zuerst nur PhotoMaid-Zeitachse; EXIF nur in Kopien, formatabhängig), Stapel umbenennen + Ablageregeln (Vorschau alt → neu, Konflikte, nur nach Bestätigung, nie im Hintergrund), frei gewählter Vergleich (2–4 Bilder, gemeinsamer Zoom, Entscheidung nur ausdrücklich), Lesbarkeitsprüfung („nicht unterstützt“ ≠ „vermutlich beschädigt“, nie automatisch löschen), App-Sperre (sperrt nur PhotoMaid), Widget (keine Vorschaubilder standardmäßig, keine Löschaktion). RAW-Entwicklung und Mehrspurschnitt deutlich später (eigene Qualitäts- und Lizenzprüfung).

## Schon umgesetzte Präzisierungen aus derselben Rückmeldung

- „frei“: Die App zeigt keine freigegebene Größe beim Verschieben in den Android-Papierkorb; Texte sagen „Speicher wird erst frei, wenn Android den Papierkorb leert“. Restliche Formulierungen mit „etwa 30 Tage“ und „Etwa … werden frei“ in den Werkzeugen werden auf „solange Android sie aufbewahrt“ bzw. „noch nicht frei“ umgestellt.
- „Alles lokal“: Funktionsliste bezieht das jetzt ausdrücklich auf die Medienverarbeitung; Käufe (Google Play) und Werbung (AdMob, nur Free, nach Einwilligung) sind getrennt genannt – wie in der App-Datenschutzseite.
