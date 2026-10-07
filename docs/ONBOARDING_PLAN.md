# Onboarding mit Angebot – Plan (2026-10-04)

Vorbild: Swipewipe-Onboarding (Bildschirmaufnahmen vom 01.10.). Übernommen wird das **Muster** (kurze animierte
Geschichte → selbst ausprobieren → Angebot mit lebender Wischkarte → Berechtigungen), nicht der Inhalt:
unsere Gesten, unser Design-System, **kein Abo, keine Testphase**, sondern „kostenlos unbegrenzt wischen mit etwas
Werbung“ + „Pro einmal kaufen, für immer“ zum Store-Preis. Status (2026-10-04): **umgesetzt** – JVM-Tests PASS (`OnboardingTest`, Screenshots `o01`–`o08`), Gerät NOT RUN.

## 1. Was wir vom Vorbild NICHT übernehmen (Regeln/Ehrlichkeit)

| Vorbild | Warum nicht | Stattdessen |
|---|---|---|
| „SUCCESS. 400 MB saved“ | Erfundene Zahl (CLAUDE.md: keine erfundenen Zahlen) | Animation ohne Zahl: Speicherbalken wird leichter, Text „Mehr Platz für Neues“ |
| 3-Tage-Testphase, Abo wöchentlich/jährlich, „Jetzt keine Zahlung fällig“ | Wir haben kein Abo | „Kostenlos – ohne Ende“ + „Pro einmalig“ |
| Glocke „Erinnerung vor Ende der Testphase“ | Es gibt nichts, das abläuft | entfällt |
| „Nutzer mit Erinnerungen räumen 5× häufiger auf“ | Erfundene Statistik | Ehrlicher Nutzen: „Erinnert dich, wenn etwas aus ‚Später‘ wieder dran ist“ |
| Echte Stockfotos von Menschen | Wirkt wie fremde Daten, Lizenzfrage | Gezeichnete Demo-Karten (Landschaften, Formen) im PhotoMaid-Stil |
| Wischen rechts = Behalten, links = Löschen | Andere Gesten | Unsere festen Gesten: hoch Behalten, links Papierkorb, rechts Später, runter zurück |

## 2. Ablauf (neue Nutzer, einmalig)

```
O1 Geschichte ─► O2 Selbst wischen ─► O3 Sicherheit ─► O4 Angebot ─► O5 Berechtigungen ─► (Google-Einwilligung EU) ─► Heute
```
- Jede Seite: „Weiter“ unten (wie im Vorbild, volle Breite), oben rechts leise „Überspringen“ (springt zu O4).
  O4 hat sofort sichtbar ✕ / „Kostenlos loslegen“ – keine versteckten Ausgänge.
- Seitenwechsel: 150-ms-Überblendung (CLAUDE.md: Öffnen nur kurzer Fade), Zurück-Taste = vorige Seite ohne Animation.
- Fortschritt: 5 kleine Punkte unten über dem Knopf.
- Bestehende Nutzer (`privacyIntroSeen = true`) sehen das Onboarding nie; neues Feld `onboardingVersion` (Int).

### O1 – „Deine Galerie, endlich aufgeräumt“ (Geschichte, automatisch)
- **Animation (≈ 4 s, Schleife):** 3×3-Raster gezeichneter Demo-Kacheln. Nacheinander bekommen 3 Kacheln das
  rote Papierkorb-Abzeichen (unser `cleanup`-Rot, Glas-Kapsel), fliegen nach links aus dem Raster, die übrigen rücken
  weich nach (Feder wie `AppMotion.standard`). Danach füllt sich das Raster neu.
- Titel: **„Deine Galerie, endlich aufgeräumt“** · Text: „Foto für Foto, ganz in deinem Tempo.“

### O2 – „Ein Wisch pro Foto“ (selbst ausprobieren, interaktiv)
- **Demo-Kartenstapel** (4 gezeichnete Karten) in der Mitte, genau wie im Feed: gleiche Wischgeste
  (`feedGestures` + **dieselbe** `FeedGestureMachine`, keine zweite State Machine), gleiche Richtungs-Pille
  („↑ Behalten“, „← Papierkorb“, „→ Später“), gleiche Fly-out-Feder, gleiche Haptik.
- Unter dem Stapel drei Chips: ↑ Behalten · ← Papierkorb · → Später. Jeder Chip bekommt einen Haken, sobald die Geste
  einmal gelungen ist; nach allen drei: kurzer Haptik-„Erfolg“, Text „Perfekt – so einfach ist das.“
- „runter“ holt die vorige Demo-Karte zurück (wie im Feed). Weiter ist immer möglich (niemand wird festgehalten).
- **Nichts wird gespeichert:** Demo ohne Ledger, ohne Medien, ohne Werbe-Zähler.
- Titel: **„Ein Wisch pro Foto“** · Text: „Hoch behalten, links in den Papierkorb, rechts für später. Probier’s aus!“

### O3 – „Nichts geht aus Versehen verloren“ (Sicherheit, automatisch)
- **Animation:** Karte wischt nach links → schrumpft in das Papierkorb-Symbol (Zähler „1“ springt wie in der
  Kopfzeile) → Papierkorb öffnet sich als angedeutetes Blatt mit „Entfernen“ → darüber ein schematischer
  Android-Bestätigungsdialog. Ohne Zahlen zu Speicher.
- Titel: **„Nichts geht aus Versehen verloren“** · Text: „Aussortiertes landet erst in PhotoMaids Papierkorb.
  Gelöscht wird nur, was du dort bestätigst – und Android fragt noch einmal nach.“
- Kleine Zeile darunter: Schloss + „Alles bleibt auf deinem Gerät. Kein Konto, kein Upload.“

### O4 – Angebot „Kostenlos – so viel du willst“ (Verkaufsseite)
Aufbau wie der Plan-Screen im Vorbild, aber ehrlich und mit unserem Modell:
1. **Lebende Wischkarte oben** (wie im Vorbild): kleiner Demo-Stapel, wischt sich alle 2 s selbst
   (hoch/links/rechts im Wechsel, mit Stempel „Behalten“/„Papierkorb“/„Später“ als Glas-Kapsel) und reagiert auch auf
   den Finger. Reduzierte Bewegung → stehendes Bild.
2. Titel **„Kostenlos – so viel du willst“**, Text „Wisch und sortier ohne Grenze. Dafür siehst du ab und zu eine kurze
   Anzeige.“
3. **Zeitleiste/Liste** (Stil des Testphasen-Strahls, aber als „Was du bekommst“):
   - ✓ **Immer kostenlos** – unbegrenzt wischen und sortieren
   - ✓ **Ab und zu eine Anzeige** – kurz, überspringbar, nie über deinen Videos
   - ✓ **Bis zu 15 pro Tag endgültig löschen** – +10 mit einem kurzen Video
   - ★ **Pro – einmal zahlen, für immer:** ohne Werbung · ohne Löschlimit · identische Kopien finden
4. **Preiszeile (nur Store-Preis):** Karte „PhotoMaid Pro · **{Preis aus Google Play}** · einmalig“ mit Abzeichen
   „Kein Abo“. Preis kommt ausschließlich aus `PurchaseGateway.product.localizedPrice`. Noch nicht geladen / offline →
   keine Zahl, Text „Preis in Google Play“, Knopf „Pro ansehen“.
5. Knöpfe:
   - Primär (volle Breite): **„Kostenlos loslegen“**
   - Sekundär (Glas, accent): **„Pro für {Preis} – einmal kaufen“** → echter Play-Kaufdialog (`purchasePro`).
     Erfolg → kurze Bestätigung „Danke! Pro ist aktiv – für immer werbefrei.“ → O5. Ausstehend/Fehler → ehrliche
     Meldung, weiter mit Free möglich.
   - Leise darunter: „Kein Abo. Keine Testphase. Bezahlt wird über Google Play.“ · „Kauf wiederherstellen“ ·
     „Datenschutz“.
6. Kein Countdown, kein durchgestrichener Fantasiepreis, kein „nur heute“.

### O5 – Berechtigungen (bestehender `PrivacyAccessScreen`, im neuen Look)
- Kopf: App-Name, Titel **„Damit PhotoMaid deine Fotos sehen kann“**.
- Zeilen (Glas-Kachel-Symbol + Text, wie im Vorbild):
  - **Fotos & Videos** – „Zum Anzeigen und Sortieren. Entfernt wird nur nach deiner Bestätigung.“ (erforderlich)
  - **Mitteilungen (optional)** – „Erinnert dich, wenn etwas aus ‚Später‘ wieder dran ist.“
- Schloss-Zeile: „Deine Medien bleiben auf deinem Gerät.“ · Knopf **„Weiter“** → Android-Dialoge.
- Danach (nur Free, EU): Google-Einwilligungsdialog (UMP) – unverändert, er kommt erst NACH dem Onboarding.

## 3. Technik

| Baustein | Datei | Inhalt |
|---|---|---|
| Ablauf | `feature/onboarding/OnboardingFlow.kt` | Schrittzustand (O1–O5) `rememberSaveable`, Überspringen, Zurück-Taste, Punkte, Fade 150 ms |
| Demo-Grafik | `feature/onboarding/DemoArtwork.kt` | 6 gezeichnete Kartenmotive (Canvas: Himmel-Verlauf, Berge, Sonne, Meer, Wald), hell/dunkel |
| Demo-Stapel | `feature/onboarding/DemoCardStack.kt` | `feedGestures` + `FeedGestureMachine`; kleines `DemoFeedState` (Offset, Richtung, Index) ohne Ledger; Auto-Wisch für O4 |
| Animationen | `feature/onboarding/StoryScenes.kt` | O1-Raster, O3-Papierkorb-Szene; `graphicsLayer`/`offset {}` (nur Layout/Draw lesen), Reduce Motion = Standbild |
| Angebot | `feature/onboarding/OfferPage.kt` | Preis aus `PurchaseGateway.product`, Kauf/Restore wie `ProScreen`, Zustände Laden/Kein Preis/Ausstehend/Fehler/Gekauft |
| Berechtigungen | `PrivacyAccessScreen.kt` | neues Layout, gleiche Logik |
| Einstieg | `navigation/Root.kt` | statt direkt `PrivacyAccessScreen`: `OnboardingFlow`; UMP-Start erst nach Abschluss (wie heute nach `privacyIntroSeen`) |
| Einstellung | `AppSettings.kt` | `onboardingVersion: Int = 0` (bestehende Nutzer mit `privacyIntroSeen` = übersprungen) |
| Texte | `res/values/strings.xml` | alle Texte oben (Deutsch), Preis nur als Platzhalter `%1$s` |
| Doku | `PORTING_MATRIX.md`, `MONETIZATION_REPORT.md` | Abweichung von iOS (Produktentscheidung), Ehrlichkeitsregeln |

Barrierefreiheit: TalkBack liest jede Seite als Überschrift + Text; Demo-Stapel bekommt Aktionen „Behalten“,
„In den Papierkorb“, „Später“ (wie Feed); Mindest-Touch 48 dp; 200 % Schrift scrollt statt abzuschneiden;
Reduzierte Bewegung schaltet alle Schleifen ab.

## 4. Tests

- `OnboardingFlowTest` (Robolectric): Reihenfolge O1–O5, Überspringen → O4, Zurück-Taste, bestehende Nutzer sehen nichts,
  UMP erst nach Abschluss.
- `OnboardingDemoTest`: hoch/links/rechts im Demo-Stapel → Chips abgehakt, **0 Ledger-Ereignisse, 0 Werbe-Zähler**;
  runter = vorige Karte; Geste unter Schwelle = nichts.
- `OfferPageTest`: Preis nur aus dem Gateway („3,99 CHF (TEST)“), ohne Produkt keine Zahl; Kauf ruft `purchasePro`;
  gekauft → Bestätigung und weiter; Lifetime-Nutzer sieht O4 nicht.
- Roborazzi: O1–O5 hell/dunkel, 200 % Schrift, ohne Preis.
- Gerät (Testagent): flüssig (gfxinfo auf O1/O2), Kaufdialog mit Lizenztester, Berechtigungsdialoge, Zurück-Taste.

## 5. Reihenfolge und Aufwand

1. Gerüst: Ablauf, Einstellung, Texte, Einstieg in `Root` (klein)
2. Demo-Grafik + Demo-Stapel (O2) – wiederverwendet für O4 (mittel)
3. Geschichten O1 und O3 (mittel)
4. Angebot O4 mit Store-Preis und Kauf (klein–mittel)
5. Berechtigungen O5 im neuen Look (klein)
6. Tests, Screenshots, Doku, Gerätetest-Prompt (mittel)

## 6. Offene Entscheidungen

1. Primärknopf auf O4: **„Kostenlos loslegen“** (empfohlen: Vertrauen, Store-Richtlinie) oder Pro zuerst?
2. Löschlimit (15/Tag, +10 per Video) auf O4 nennen? Empfohlen **ja** – ehrlich und das stärkste Pro-Argument.
3. Hintergrund: unsere Farben (empfohlen) oder ein wärmerer Onboarding-Ton wie im Vorbild?
4. Soll O4 später auch als In-App-Angebot erscheinen (z. B. aus „Ohne Werbung mit Pro“) statt der heutigen Pro-Seite?

## 7. Entscheidungen (2026-10-04) und Umsetzung

1. Primärknopf „Kostenlos loslegen“ – umgesetzt.
2. Limit offen genannt; **neu 20 pro Tag + 20 per Video** (überall in der App, `Capabilities.FREE_DAILY_CLEANUP_LIMIT`, `LimitBonus.AMOUNT`).
3. Wärmer, aber **kein Violett, keine Swipewipe-Töne**: Creme/Sand-Grund, Latte als Markenton, Blau bleibt Akzent, Bernstein/Erdton für Kacheln.
4. Angebotsbausteine auch in der App: Pro-Seite nutzt `OfferHero` + `OfferBenefits`.

Zusätzlich: Hinweiskarten-Tutorial (`design/CoachMarks.kt`) einmal auf „Heute“ (4 Schritte: Start, Papierkorb & Später,
Schnellzugriffe, Aufräumen-Tab) und im ersten Feed (3 Schritte: Papierkorb, Rückgängig, Aktionsleiste). Abdunkeln mit Aussparung,
„n/N · Tutorial überspringen · Weiter/Fertig“, blockiert solange jede Berührung (keine versehentliche Entscheidung),
Zurück = überspringen. „Heute“ freundlicher: Begrüßung nach Tageszeit, farbige Kacheln je Quelle, kräftigere Titel.
