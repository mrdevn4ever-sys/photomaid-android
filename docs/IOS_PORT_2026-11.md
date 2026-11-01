# iOS → Android: Portierung des aktuellen GaleriQ-Stands (01.11.2026)

Vorlage: iOS-Repository `mrdevn4ever-sys/photomaid`, Branch `claude/experiment-artifact-design` (GaleriQ 1.0, zur
App-Prüfung eingereicht; `main` ist dort seit 19.10. veraltet). Die iOS-App wurde **nicht** verändert (read-only).
Android-Branch: `claude/galeriq-ios-port` (aufbauend auf der GaleriQ-Umbenennung `d1ef62e`).

Prüfstatus-Vokabular: PASS / FAIL / NOT RUN / BLOCKED. JVM-Tests sind keine Geräteabnahme.

## 1. Produktname GaleriQ

| Bereich | Stand |
|---|---|
| Sichtbare Texte (16 Sprachen), Ordner `Pictures/`, `Movies/`, `Documents/`, `Music/GaleriQ/`, Dateinamen, EXIF-Software, PDF-Producer, Datenschutz-URL `…/galeriq/` | erledigt (Commit `d1ef62e`, jetzt in diesem Branch) |
| README, CLAUDE.md, lebende Doku (Funktionsliste, Datenschutzkarte, Blocker, Portierungsmatrix), Code-Kommentare, CI-Artefaktname, QA-Ordner `Pictures/GaleriQ-QA` | erledigt (dieser Branch) |
| „Arrumo“ | kommt in keinem der drei Repos mehr vor (geprüft auf allen Branches) |
| Bewusst **nicht** umbenannt | Paket `app.photomaid…`, `applicationId app.photomaid.android`, Klassen (`PhotoMaidDatabase` …), Datenbankdatei `photomaid.db`, Gradle-Eigenschaften `photomaid.*`, Theme-Namen. Gleiche Regel wie iOS (CLAUDE.md iOS: „Interne Namen … bleiben PhotoMaid – nie umbenennen, Daten bleiben“). Eine Umbenennung von Datenbank/`applicationId` würde vorhandene Daten bzw. die App-Identität verlieren. |
| Historische Berichte (datierte Pläne/Testprotokolle) | unverändert gelassen (Zeitdokumente) |

## 2. Übertragen

| iOS | Android | Status |
|---|---|---|
| `Games/PhotoMemoryGame`, `PhotoMemoryBoardLayout`, `PhotoMemorySelection` | `core/.../games/PhotoMemory.kt` | Kern-Tests PASS |
| `Games/PhotoSnakeGame`, `Motion`, `Viewport`, `Layout`, `Style` (Vorlieben, Futter, Figuren) | `core/.../games/PhotoSnake.kt` (+ `PhotoSnakeSwipe`) | Kern-Tests PASS (gleicher Zufallsgenerator, gleiche Seeds wie iOS-Tests) |
| `Games/PhotoBlocksGame`, `Input`, `Photos`, `Shapes`, `Archive` | `core/.../games/PhotoBlocks.kt` | Kern-Tests PASS |
| `Games/PhotoGameRecords`, `PhotoGameStopwatch` | `core/.../games/PhotoGameRecords.kt` | Kern-Tests PASS |
| `Progress/ReviewProgress`, `ReviewLevel`, `Achievements` (24 Abzeichen), `GameMedal` | `core/.../progress/ReviewProgress.kt` | Kern-Tests PASS |
| Seite „Erfolge“ (Level, Serie, Tagesziel, diese Woche, Abzeichen) | `feature/games/AchievementsScreen.kt`, Einstieg auf „Heute“, 45 iOS-Texte | Kompiliert; Gerät NOT RUN |
| „Kurze Pause“: Übersicht, Paare, Foto-Snake, Foto Blocks, Bestwerte | `feature/games/*` (Compose), Einstieg auf „Heute“ und im Studio, Route `games` | Kompiliert; Gerät NOT RUN |
| Spieltexte (115 Kennungen) | aus `Localizable.xcstrings` in alle 16 `strings.xml` übernommen (`scripts/import_ios_strings.py`, lehnt Texte mit iPhone/iCloud ab) + 2 eigene Android-Texte | übernommen |
| Snake-Grafiken (Köpfe von oben, Futter, Wiese) | `res/drawable-nodpi/snake_*` | eigens für das Projekt erzeugt (iOS `docs/snakephoto/ASSET_KIT_README_DE.md`) |

Regeln eingehalten: Spielen ist nie eine Entscheidung, kein Ledger, keine Werbung in Spielen; Spielstände nur Zahlen (keine
Foto-Kennungen), lokal im `KeyValueStore`; Fotos nur als 256-px-Vorschauen (Blocks 768 px) im Arbeitsspeicher, aus
`AppModel.photos` (ohne ausgeblendete Medien und ausgenommene Ordner); nie verzerrt (Beschnitt/Einpassen); Snake nur per
Wischgeste gesteuert (Start/Weiter auch per Knopf); Richtungen in RTL physisch.

### Bewusste Vereinfachungen der Spiele gegenüber iOS (noch offen)

- Startseiten zeigen ein Symbol statt der animierten Spielszene (`GameShowcase`).
- Kein Ton (iOS `SnakeAudio`), kein „Schrittweise spielen“, keine Nachtwald-Hintergrundbilder für Paare.
- Paare: Quellen Mediathek/Favoriten/Neueste und „Ohne Fotos“; **fehlt**: Album/Ordner als Quelle, eigene Auswahl (2–18 Fotos),
  „Fehlpaare manuell verdecken“ als eigene Kategorie.
- Snake: **fehlt** die dauerhafte eigene Fotogalerie (bis 100 kleine Kopien) und Kopf-Animationen (Blinzeln, Fressen).
- Blocks: Foto des Spielstands wird nach dem Neustart der App neu gewählt (iOS speichert ebenfalls keine Kennungen, nutzt aber
  Seed + Quelle).
- Bestwerte-Seite vereinfacht (ohne „Zuletzt geknackt“, Filter).

## 3. Nicht übertragen – mit Grund

| iOS-Funktion | Grund | Was nötig wäre |
|---|---|---|
| **Erfolge – Rest** (Serien-Chip und Tagesziel-Balken auf „Heute“, Wochenrückblick, Feed-Pillen „Neues Abzeichen“, Einstellungen „Erfolge anzeigen“/Tagesziel Aus/25/50/100, Abzeichen für Monate und Studio-Ergebnisse) | Seite „Erfolge“ ist da (Tagesziel fest 50 wie iOS-Standard); Abzeichen, deren Daten auf Android noch nicht angebunden sind (Monate, erste Collage/PDF/Film), werden ausgeblendet statt falsch „offen“ gezeigt | Monatsfortschritt und „Bearbeitet“-Liste anbinden, Einstellungen ergänzen |
| **Bildanalyse** (ähnliche/unscharfe Fotos, Smart Review, Freistellen, Texterkennung, Dokumentscanner) | iOS nutzt Apple Vision/VisionKit (E7, nur iOS). Android-Regel: „keine KI/ML“ | Deine Entscheidung; technisch z. B. ML Kit (on-device) |
| **Tresor** (verschlüsselter, versteckter Bereich mit Code, Notizen, Dateien, Bearbeiten im Tresor) | Android-Regel G2: „Kein Tresor“. Zusätzlich sicherheitskritisch (Schlüsselverwaltung, Play-Richtlinien) | Deine Entscheidung; technisch Android Keystore + verschlüsselte Dateien |
| **Wischen & Tasten frei belegen** (E18, `FeedControls`) | Android-Regel: Gesten „FEST“ | Deine Entscheidung; Kern ließe sich direkt übertragen |
| **Neues Pro-Modell** (E19: alle Werkzeuge frei, 100 Entfernungen/Tag + 100 per Video, Werbung beim Kaltstart ab 4. Start, Werbekarte alle 50 Entscheidungen, kein Pro-Test) | Android hat abweichende, ausdrückliche Produktentscheidungen (20/Tag, Pro-Test, Karte alle 7). Geschäftsregel – nicht still ändern | Deine Entscheidung |
| **Artefakt-Design** (Bricolage/Figtree-Schriften, Bernstein-Palette, schwebende Tableiste mit Mittelknopf, Begrüßung nach Tageszeit, Zahlenschloss-Rad, Erscheinungsbild Standard dunkel) | Kompletter visueller Umbau der App; nur in den Spielen übernommen (Farben) | Eigener Auftrag |
| **Archiv/Sicherung** auf eigenen Datenträger (USB/SSD) mit optionalem Papierkorb der geprüften Originale | Nicht in diesem Durchgang | Über SAF (Ordner wählen, Kopien prüfen) machbar |
| **PDF bearbeiten** (Seiten zusammenführen/ordnen, Unterschrift; `PdfEdit`) | Nicht in diesem Durchgang | Android `PdfRenderer` + eigener `PdfWriter` |
| Größenschätzung „ca.“ (E17, für Fotos nur in iCloud) | iOS-Eigenheit (iCloud); Android misst immer | entfällt (Plattform) |
| iCloud-Fotos laden, Live Photos, iPhone Duo, Liquid Glass, ATT-Abfrage | Plattformgrenze | entfällt |
| Suche im Tab „Fotos“ entfernt (iOS) | iOS-Grund (Dateinamen-Lesen langsam, keine Personennamen) gilt auf Android nicht | Android behält die Suche |

## 4. Prüfungen

| Prüfung | Status |
|---|---|
| `./gradlew :core:test` (inkl. neuer `GamesTest`, `ReviewProgressTest`) | siehe Abschlussmeldung |
| `./gradlew :app:compileDebugKotlin` | siehe Abschlussmeldung |
| `./gradlew :app:testDebugUnitTest`, `lintDebug`, `assembleRelease` | siehe Abschlussmeldung |
| Geräte-/Emulatorlauf der Spiele | NOT RUN (kein KVM im Container) |
