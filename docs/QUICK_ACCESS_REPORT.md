# Schnellzugriffe + globale Review-Identität (2026-09-30)

Branch `claude/product-ux-favorites`. Prüfstatus: PASS / FAIL / NOT RUN.

## 1. Architektur
- **Quelle = Abfrage, kein Zustand.** `QuickAccessItem(kind, folder?)` (`core/quickaccess/QuickAccess.kt`) beschreibt nur,
  *welche* Medien dazugehören. Kein QuickAccessDecision/-History/-Later – es gibt nur den bestehenden Ledger.
- **Resolver:** `resolveSource(item, snapshot)` filtert eine gemeinsame Momentaufnahme (`reviewableMedia()`:
  Fotos inkl. Screenshots + Videos, neueste zuerst). Eine MediaStore-Abfrage für Hero + alle Kacheln, keine je Kachel.
- **Eligibility (global):** `AppModel.pendingQueue(assets)` = `ReviewQueues.normalQueue` über den Ledger – dieselbe Regel
  für Hero, Tab „Aufräumen“, Schnellzugriffe und Feed. `statusFor(items, snapshot, pendingIds)` = reine Filter im Speicher.
- **Ein Feed:** `FeedRequest.Source.Quick(item)` öffnet denselben Review-Feed (Gesten, Revisionen, Undo unverändert).

## 2. Quellen
Fotos · Videos · Screenshots (MediaStore-Ordner `…/Screenshots/`, keine Bilderkennung) · Neueste Fotos (50) ·
Neueste Videos (20) · Diese Woche (lokale Kalenderwoche nach Region, DE: ab Montag 00:00) · Ordner (MediaStore
`RELATIVE_PATH`; sichtbar „Ordner auswählen“, weil Android keine universellen Alben kennt). Grenzen zentral in `QuickAccess`.

## 3. Persistenz
`KeyValueStore`-Schlüssel `quickaccess.v1`: Liste aus Art + optionalem Ordnerpfad + Reihenfolge – **nie Medien-IDs**.
Fehlt der Eintrag (Neuinstallation oder Update): Fotos + Videos. Additiv, keine Migration anderer Daten (Test
`upgradeGetsDefaultsWithoutTouchingDecisions`). Max. 6, Duplikate/leere Ordner werden beim Laden bereinigt.

## 4. Globale Media Identity
Bestehende Identität: MediaStore-Content-URI (`ItemReference.photo(id)`). Keine zweite Identität.

## 5. Globale Decision-Semantik
Eine Entscheidung = ein Ledger-Eintrag am Medium. Behalten, Papierkorb, Später (mit Termin) entfernen es aus jeder
offenen Quelle; Undo stellt es überall wieder her. Fällige Später-Medien kommen – wie bisher – über „… sind wieder dran“
auf Heute zurück (bestehende Später-Logik; keine zweite Scheduler-Logik).

## 6. Count-Semantik
Zahl = offene Mitglieder der Quelle; „✓“ = Mitglieder vorhanden, keins offen; „Leer“ = keine Mitglieder (z. B. gelöschter
Ordner). Hero = Anzahl verschiedener offener IDs (keine Summe über Quellen). Nichts wird gespeichert.

## 7. Home
Hero → Papierkorb/Später (feste Karten) → „Schnellzugriffe“ (eine leichte Karte, eine Zeile je Quelle:
Symbol · Name · **Zahl rechts** · Chevron, „+ Hinzufügen“, „Bearbeiten“ = sortieren/entfernen) → Gerätespeicher.
Zahlen stehen bei jeder Schriftgröße rechts (auch in `SourceRow`/Tab „Aufräumen“; nur lange Detailtexte stapeln).
Lange Wörter trennen bei großer Schrift mit Silbentrennung statt mitten im Wort.

## 8. Tests (alle PASS)
- core `QuickAccessTest` (4): Standard, Hinzufügen/Entfernen/Sortieren/Limit, Speicherform, Kalenderwoche/Zeitzone.
- app `ReviewSourcesTest` (11): A Keep in „Neueste“ → aus allen Quellen, 1 Event · B Papierkorb im Ordner, nichts
  gelöscht · C Später bis fällig · D Undo überall · E Hero zählt 1 statt 4 · F/G neue Quelle respektiert Keep/Später ·
  H extern gelöscht · I neues Foto in mehreren Quellen als eine Identität · §39 100 Fotos → 10 / 5 (Neueste 50) / 3
  (Woche) offen, Hero 10 · leerer Ordner · Migration.
- app `QuickAccessUiTest` (8): UI-Ende-zu-Ende inkl. J (schneller Quellenwechsel ohne veralteten Eintrag).
- `HubPendingTest` (6), `ScreenshotTest` (inkl. r01–r08, q03/q04, k01–k05).

## 9. Build / Lint
Siehe Abschlussmeldung im Chat (core, app, lint, assembleRelease, `git diff --check`).

## 10. Screenshots (`docs/screenshots/`)
r01 alles geprüft (A) · r02 offen: Papierkorb 2, Später 3, Fotos 5, Videos 2 (B) · q03 Picker (C) · r04 vier Quellen (D) ·
r05 Dark (E) · r06 320 dp (F) · r07 Schrift 150 % (G) · r08 Schrift 200 % auf 320 dp · q04 Bearbeiten · k05 Aufräumen 200 %.

## 11. Gerätetest
NOT RUN (kein Gerät im Build-Container). Ablauf §40 für den Geräte-Agenten offen.

## 12. Bekannte Einschränkungen
- „Neueste Fotos“ = 50 (Prompt-Beispiel nannte 20); zentral änderbar.
- „Ordner“ statt „Album“: Galerie-Apps zeigen Ordner meist als Album; eigene App-Alben (Google Fotos) sind nicht lesbar.
- Diagonal „↗ Datum“ bleibt entfernt (Produktentscheidung 2026-09-29); eigenes Datum über „Ändern“ in der Pille.

## 13. Bewusst nicht implementiert
Momente, Orts-/Urlaubserkennung, Gesichter/Objekte, ähnliche Fotos, automatische Alben, KI/ML, Cloud, neue Cleaner.
Neue Quellen = neuer `QuickSourceKind` + ein Fall in `resolveSource`.

**Beweis:** `ReviewSourcesTest.testA_keepInLatestRemovesFromAllSourcesWithOneEvent` und
`QuickAccessUiTest.oneDecisionUpdatesEverySourceAndHeroCountsOnce`: ein Foto in mehreren Quellen wird nach EINER
Entscheidung (genau ein Ledger-Event) in keiner anderen Quelle erneut vorgelegt.
