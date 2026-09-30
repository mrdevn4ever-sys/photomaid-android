# Smoothness- und UI-Audit (2026-09-30)

Branch `claude/product-ux-favorites`. Grundlage: Code-Durchsicht des Feeds, der Listen und des Design-Systems
sowie der JVM-Screenshots. Prüfstatus PASS / FAIL / NOT RUN / BLOCKED. Gerät: NOT RUN (Container ohne Gerät).

## Befunde und Umsetzung

| # | Befund | Wirkung auf dem Gerät | Umsetzung |
|---|---|---|---|
| S1 | Feed las den Wisch-Versatz **in der Komposition** (`model.offset.value` im Body von `MediaStage`/`AdStage`) | jeder Drag-Frame komponierte die ganze Bühne neu (Vorschaubild, Labels, Prefetch-Schleife) → Ruckeln auf Mittelklasse-Geräten | Versatz, Drehung und Einblendung werden nur noch in `offset {}`/`graphicsLayer {}` gelesen (Layout/Zeichnen) |
| S2 | Unter dem Medium lag **nichts**; das nächste Medium erschien erst nach Flug + Speichern | leere Fläche beim Wischen, nächstes Bild „ploppt“ auf | Zweite Karte: das nächste Medium liegt unsichtbar darunter, blendet beim Wischen ein (0,94 → 1,0) und wird nach dem Commit ohne Neuladen zur oberen Karte (gleicher `key`). Kein Player, keine Bedienung, für TalkBack unsichtbar |
| S3 | Hinausfliegen mit fester Dauer (220 ms Tween), **ignorierte die Wischgeschwindigkeit**, Flug immer exakt auf der Achse | schneller Wisch wirkte gebremst, Knick in der Bahn | Kritisch gedämpfte Feder mit der Release-Geschwindigkeit als Startwert (`AppMotion.flyOut`); die Querbewegung des Fingers bleibt erhalten |
| S4 | Tipp aufs Video wartete **immer 280 ms** auf einen möglichen Doppeltipp | Pause/Play fühlte sich träge an | Sofort, solange nicht gezoomt (Doppeltipp setzt nur den Zoom zurück) |
| S5 | Gesten-Callbacks wurden beim ersten Aufbau **eingefroren** (`pointerInput(density)` mit Lambdas) | veraltete Werte für Zoom und „Animationen entfernen“ in laufenden Gesten | `rememberUpdatedState` für alle Callbacks, ohne die laufende Geste neu zu starten |
| S6 | Papierkorb, Später, Verlauf, Große Dateien, Dateien: `LazyColumn` mit **ganzen Abschnitten in einem Item** (`forEachIndexed`) | bei hunderten Einträgen alle Zeilen und Vorschaubilder auf einmal → Sekunden-Hänger beim Öffnen, Ruckeln beim Scrollen. Verlauf war zusätzlich auf 300 je Tag gekappt | `lazyListSection`: jede Zeile ein eigenes Lazy-Item im gleichen Kartenlook (Rundung nur am Gruppenrand, durchgehende Kontur, Trenner); Kappungen entfallen |
| S7 | Harte Zustandswechsel: Zusammenfassung und Werbekarte erschienen schlagartig | wirkt billig | `appearOnce()`: 280 ms Fade + 12 dp Anheben (aus bei „Animationen entfernen“) |
| S8 | Bilder erscheinen hart (erstes Feed-Bild, Listen-Vorschaubilder beim Scrollen) | leichtes Aufblitzen | **Nicht umgesetzt:** Coil-Crossfade getestet und wieder entfernt – in den JVM-Screenshots blieben die Bilder leer, ohne Gerät nicht belegbar. Feed-Medien sind vorgeladen (Speichercache), dort tritt es kaum auf |
| S9 | Tippbare Karten hatten nur Ripple | fühlt sich „flach“ an | Karten geben beim Drücken leicht nach (0,98), Buttons wie bisher (0,96) |

## Bewusst unverändert (geprüft, in Ordnung)
- Navigation: Zurück/Tabwechsel ohne Animation, Öffnen 150-ms-Fade (Nutzerentscheidung).
- Home-Hierarchie, Statuskarten, Schnellzugriffe (Zahlen rechts), Pro-Seite, Werbekarte: visuell konsistent.
- Vorladen: vorheriges, aktuelles, zwei nächste Medien auf Zielgröße, nur Speichercache.
- Kein Echtzeit-Blur (auf schwachen Geräten zu teuer beim Wischen).

## Offen (nur auf dem Gerät messbar)
- Frame-Timing (Perfetto/Macrobenchmark) für 100 schnelle Entscheidungen und 1 000-Einträge-Papierkorb: NOT RUN.
- Subjektiver Eindruck der Flugfeder (Steifigkeit 420) auf dem Realme: NOT RUN; zentral in `AppMotion.flyOut` justierbar.
