# Bild-Assets

| Datei (`app/src/main/res/drawable-nodpi/`) | Herkunft | Verwendung |
|---|---|---|
| `demo_*.webp` (11, inkl. `demo_monument`, `demo_piazza`) | Eigene Fotos des App-Inhabers (2026-10-04 bereitgestellt), Rechte beim Inhaber; keine erkennbaren Gesichter | Onboarding (Raster, Übungsstapel, Sicherheit), Angebot/Pro-Seite |

Verarbeitung: auf max. 800 px verkleinert, als WebP (q 80) neu kodiert, **alle Metadaten entfernt** (kein GPS, keine
Kamera-, Datums- oder ICC-Daten); geprüft per Pillow (`getexif()` leer). Zusammen ca. 710 KB.
Die Bilder sind reine Illustration: nie mit den Medien der Nutzer vermischt, nie hochgeladen, nie an Werbung übergeben.

`demo_piazza` wird nur als kleine Kachel im Raster gezeigt: am Gebäude hängt ein politisches Banner, das groß lesbar wäre.
