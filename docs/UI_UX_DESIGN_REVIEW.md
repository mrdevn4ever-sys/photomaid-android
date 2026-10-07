# UI/UX-Design-Review – Umsetzung (2026-09-30)

Branch `claude/product-ux-favorites`. Grundlage: Design-Review im Chat (P0/P1/P2), vollständig umgesetzt.
Prüfstatus: core + app-Tests PASS, lint PASS, assembleRelease PASS. Gerät: NOT RUN.
Gestenlogik unverändert (keine Diagonale auf Android, Produktentscheidung 2026-09-29).

## P0
- **Kontrast (WCAG AA):** `accent` = Text/Symbol (#0064D2 Light, #4DA3FF Dark), neu `accentFill` = Fläche mit weißer
  Schrift (#0071E3 / #0A84FF). Light: Papierkorb #D70015, Später #0E8FA8, Erfolg #248A3D.
- **Tabbar:** Auswahl als Akzent-Tönung (12 %) statt weißer Kapsel; 32-dp-Übergang über der Leiste.
- **Hero:** 1 Bild = volle Breite, 2 = Hälften, 3 = Drittel; Laden = ruhige Flächen in der späteren Geometrie.

## P1
- Aufräumen-Tab: zwei Gruppen **Mediathek** (Fotos, Videos, Screenshots) und **Werkzeuge** (Große Dateien „ab 100 MB“,
  Duplikate mit Pro-Abzeichen, Dateien, Speicherübersicht). Papierkorb/Später nur noch auf „Heute“; keine „durchsehen“-Untertitel.
- „Heute“: Titel „Heute“, Claim nur noch im Onboarding. Papierkorb + Später (+ „wieder dran“) als **eine** Gruppe, Nullen leise.
- Rhythmus: 24 dp zwischen Blöcken, 8 dp Überschrift → Gruppe (`AppSpacing.block`/`headerGap`).
- Ein Zeilen-Bauteil `ListRow` (32-dp-Kachel, 56/64 dp, Status rechts: Zahl · ✓ · „Leer“ · Label) für Heute, Aufräumen,
  Sammlungen und Sheets; `SourceRow` auf dieselbe Geometrie gebracht.
- Später überall Uhr (`Schedule`) in Türkis; „Neueste“ ohne Verlauf-Symbol.
- Hinzufügen-Sheet: stabiler Katalog mit echter offener Anzahl, vorhandene Quellen „Auf Heute“ (nicht antippbar),
  Ordner direkt im Sheet (die 5 größten, dann „Alle Ordner …“).
- Bearbeiten-Sheet: ⊖ links, Ziehgriff rechts (Haptik je Schritt), „Fertig“; TalkBack-Aktionen „Nach vorn/Nach hinten“;
  Hinweis „Entfernen löscht nichts“.
- Sammlungen: Status rechts wie Schnellzugriffe, Einleitung eine Zeile, „Eigene Sammlung“ als Zeile mit Pro-Abzeichen.

## P2
- Large Title 32/38 SemiBold −0,4 sp; Abschnittsüberschriften 13 sp Versalien (vorgelesen in normaler Schreibweise);
  Mindestschrift 12 sp; Seitenrand 16 dp; Radien Hero 24 / Gruppe 18 / Kachel 9.
- Zahlen rollen beim Ändern (200 ms); neue Schnellzugriffe kurz hervorgehoben + Haptik; Gruppen animieren ihre Höhe;
  Hero-Bilder blenden zum nächsten Medium über. Alles aus bei „Animationen entfernen“.
- Hero-Bilder randlos; Gestenzeile nur bis zur ersten Gestenhilfe, mit Symbolen statt Pfeilzeichen.
- Speicher ohne Karte (leise Zeile + Balken); Pro-Hinweis als normale Zeile.

## Tests / Screenshots
Angepasst: QuickAccessUiTest (Katalog, Ordner direkt, Sortieren per Aktion), HubPendingTest (Später nur auf Heute),
ReworkTest, AppFlowsTest, DeviceFindingsTest. Neue Screenshots `d01`/`d02` (Hero mit 1/2 Medien).
`docs/screenshots/`: r01/r02/r05/r07/r08 (Heute), k02 (Aufräumen), q03/q04 (Sheets), 12 (Sammlungen), x01, h01.

## Offen
Gerätetest (Ziehen im Sheet, Tabbar-Übergang, Haptik, Kontrast im Sonnenlicht): NOT RUN.
