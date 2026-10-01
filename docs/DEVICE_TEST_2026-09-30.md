# Gerätetest 2026-09-30 – Befunde und Behebung

Gerät: realme RMX3933, Android 14, 720×1600 · Debug-Build `fc53227` · externer Test-Agent per adb.
Ergebnis: 15 PASS, 4 FAIL, 33 NOT RUN (Werkzeug-Limit des Agenten), 1 BLOCKED (frische Installation ohne `pm clear`).
System-Papierkorb nie bestätigt, keine Dateien gelöscht.

| # | Befund (Gerät) | Ursache | Behebung | Nachweis |
|---|---|---|---|---|
| D5 (HOCH) | Ziehen auf der Video-Fortschrittsleiste löste „Später“ aus (1/7 → 2/7) | Die Feed-Geste sollte abbrechen, wenn ein Kind-Control die Bewegung verbraucht. Die Prüfung nutzte `positionChange()`, das bei verbrauchten Änderungen immer 0 liefert – der Abbruch griff nie | Prüfung mit `positionChangeIgnoreConsumed()`; zusätzlich: hat ein Kind-Control schon den Finger-Down übernommen, ist es von Anfang an keine Feed-Geste (`FeedGestures.kt`) | `VideoSeekTest.draggingSeekBarNeverLocksADirection`: **FAIL mit altem Code, PASS mit Fix**; Gegenprobe `sameDragOnVideoStillLocksDirection` PASS |
| B7/B8 | Gerätespeicher unter der Tabbar, nicht wegscrollbar | Listenende der Tab-Seiten war fest 96 dp (auf „Heute“ sogar 72 dp) ohne die 3-Tasten-Navigation | `TabBarEndSpacer()`: Tabbar + Systemnavigation (`navigationBarsPadding`); Übergang über der Leiste kräftiger (40 dp, fast deckend) | Heute, Aufräumen, Verlauf |
| D3 | 28 % janky Frames bei 30 schnellen Wischen | Gemessen mit **Debug**-APK (Compose ist dort deutlich langsamer; nicht repräsentativ). Unabhängig davon: Lesemodell (Filtern/Sortieren aller Einträge) lief bei jeder Entscheidung auf dem Main-Thread; der Feed komponierte bei jeder Ledger-Änderung komplett neu | Lesemodell auf `Dispatchers.Default`, serialisiert, immer aus dem neuesten Stand; Feed beobachtet nur Undo-Status und Papierkorb-Anzahl (`derivedStateOf`) | Neumessung mit **Release**-Build nötig |
| – | (Screenshot 27) Später-Pille verdeckte bei Videos den Stumm-Schalter | Pille saß 20 dp über dem unteren Rand | Bei Videos 72 dp (über Stumm-Schalter und Fortschrittsleiste) | – |

Nicht reproduzierbar im JVM-Test: Video-Commits hängen unter Robolectric an ExoPlayer (auf dem Gerät funktionieren sie laut Bericht) – deshalb prüft der Regressionstest das Einrasten der Richtung statt eines Commits.

Offen: 33 NOT RUN aus dem Bericht (Werbekarten-Zyklus, Kaufmomente, Aufräumen-Tab, Schnellzugriff-Bearbeiten, 200 %, TalkBack, Release-Gegenprobe, große Listen) → nächster Gerätetest.

## Folgetest 2026-09-30 (Commit `687304c`)

Ergebnis: 17 PASS, 2 FAIL, 18 NOT RUN. Bestätigt behoben: R1 (Spulen keine Entscheidung), R2 (Gerätespeicher über der Tabbar),
R5 (Später-Pille über der Video-Bedienung). A1 (frische Installation per QA-App), F2 (Limit-Blatt), C4/C5, G1–G5, H1/H3 PASS.

| # | Befund | Ursache | Behebung | Nachweis |
|---|---|---|---|---|
| E5 (HOCH) | „Runter“ und Rückgängig ließen die Werbekarte stehen | Folge des Fixes aus `687304c`: „Kind-Control übernimmt den Finger → keine Feed-Geste“. Die echte Anzeige (`NativeAdView`) nimmt **jede** Berührung an – Wische über der Anzeige kamen nicht mehr an. JVM-Tests nutzten eine Anzeige ohne Android-View. Rückgängig brach zudem ab, solange noch gespeichert wurde, bevor es die Karte schloss | Werbekarte: `feedGestures(yieldToChildren = false)`; zusätzlich übernimmt die Karte ab Berührungsschwelle die Geste (Initial-Pass) – die Anzeige erhält ein CANCEL, **ein Wisch zählt nie als Anzeigen-Klick**. Rückgängig schließt die Karte immer zuerst. Test-Attrappe verhält sich jetzt wie `NativeAdView` (nimmt Berührungen an) | `FeedAdsTest.swipesStartingOnTheAdContentStillWork`, `skipSwipeOnTheAdContentAfterMinimumTime`: **FAIL vor dem Fix, PASS danach** |
| R3 | Verlauf: Einträge „hinter der Tabbar“ (uiautomator y=1549–1600) | Kein Fehler: Das sind Zeilen hinter der Systemnavigation während des Scrollens; sichtbar ist nur der Übergang (Screenshot) | – | Screenshot R3_history_bottom |
| R4 | Release-Performance nicht messbar | Release-APK war unsigniert (Windows/PowerShell: mehrzeiliger Befehl mit `\` → Properties gingen verloren) | Neuer Build-Typ **`perf`**: wie Release (R8, nicht debuggable), Debug-Signatur, Google-Test-Anzeigen, gleiche App-ID. Ein Befehl ohne Properties: `gradlew :app:assemblePerf` | APK geprüft: signiert, nicht debuggable |

## Lauf 4 – Löschen Ende-zu-Ende (2026-10-02, Commit `9d51d02`, perf-APK)

Testgerät ohne echte Daten, Löschen ausdrücklich erlaubt. Ergebnis: K1, K3, K4, K6 PASS · K2 FAIL (nur Text) · K5 FAIL ·
K7, K8, Block P NOT RUN (Werkzeuglimit des Agenten). Logcat: 0 FATAL, 0 ANR.

Belegt auf dem Gerät: Android-Dialog nennt exakt die gewählten Dateien; nach Bestätigung liegen sie im System-Papierkorb
(Google Fotos); Tageslimit entfernt exakt 12 von 15 und bietet danach nur Pro; Ablehnen entfernt nichts und verbraucht kein
Limit; App-Wechsel bei offenem Dialog ohne Doppelbuchung; extern gelöschte Medien verschwinden aus Feed und Zählern.

| # | Befund | Ursache | Behebung | Nachweis |
|---|---|---|---|---|
| K2 | Verlauf zeigt „Nicht entfernt · 2 Elemente“ statt „Bereinigung nicht ausgeführt · 2 Elemente“ | Kein App-Fehler: veraltete Erwartung im Testauftrag; der Text wurde bei der Microcopy-Überarbeitung gekürzt (`h_not_performed`) | – (Verhalten korrekt) | Bericht: Dateien vorhanden, Limit unverändert, genau ein Eintrag |
| K5 | Aus dem System-Papierkorb wiederhergestelltes Foto erscheint nicht wieder in PhotoMaid | MediaStore behält beim Wiederherstellen die ID; im Ledger ist das Item endgültig `REMOVED`, die Warteschlange schloss es aus und keine Aktion war erlaubt (von iOS übernommen, PORTING_MATRIX #6) | Vorhandenes Medium mit `REMOVED` gilt als offen; Behalten/Papierkorb/Später erlaubt (Verlauf: Entscheidung), Rückgängig zurück auf „entfernt“ | `DecisionLedgerTest.restoredFromSystemTrashIsPendingAgain`; Gerät: Nachtest nötig |


## Lauf 5 (2026-10-02, Commit `77354e6`, perf-APK)

PASS: K5-Nachtest (wiederhergestelltes Foto wieder offen, Behalten/Rückgängig), Limit-Blatt (4 Texte), M1b (Bonus nach
Reward-Callback → 12/25 → 8/25), M1c (Bonus 1× täglich), M2 (Anzeige unter „Fertig“, Scrollen über der Anzeige öffnet
sie nicht, gleiche Anzeige nach „Zum letzten Medium“), UMP-Testeinwilligung. NOT RUN: M1a (zweimal zu spät geschlossen –
die Google-Testanzeige hatte die Belohnung schon vergeben), K7, P, K8, M3, Logcat.

| # | Befund | Ursache | Behebung |
|---|---|---|---|
| M2 | AdMob-„native ad validator“: „1 implementation issue found“ (Zusammenfassung) | Sehr wahrscheinlich MediaView zu klein: feste Kartenhöhe 280 dp, Texte + Knopf ließen der MediaView nur wenige dp (Pflicht ≥ 120 × 120 dp). Zusätzlich lag die Kennzeichnung „Anzeige“ außerhalb der `NativeAdView` | Kompakte Anzeige mit fester MediaView-Höhe 168 dp, Höhe nach Inhalt; Feed-Karte MediaView mindestens 120 dp; Kennzeichnung „Anzeige“ jetzt innerhalb der `NativeAdView`; leere Assets ausgeblendet. Gerät: Nachtest + „See issues“ nötig |

## Lauf 6 (2026-10-02, Commit `4f05322`, perf-APK) – Zwischenstand

PASS: Update per Fast-forward ohne Datenverlust; Feed-Werbekarte – AdMob-Validator „No implementation issues found“ (Fix
`4f05322` wirkt); Pro-Test per Rewarded („Pro-Test aktiv … Limit bleibt unverändert“); Duplikate: genau die drei Kopien im
System-Papierkorb, Originale erhalten (Limit-Ablesung offen). NOT RUN: P, K8, M1a, M3, Logcat.

| # | Befund | Ursache | Behebung | Nachweis |
|---|---|---|---|---|
| V (1a) | Nach kurzem Durchgang (2 Medien) keine Anzeige in der Zusammenfassung | Die Zusammenfassung nahm nur eine bereits geladene Anzeige; das Vorladen (≤ 2 Medien vor Ende) war noch nicht fertig → einmal „keine“, danach nie wieder | Bis 8 s nachfassen, solange die Zusammenfassung offen ist; die Anzeige erscheint dann ganz unten (nichts darüber verschiebt sich) | `MonetizationTest.summaryAdAppearsWhenItFinishesLoadingLate` |

### Lauf 6 – Abschluss (Commit `04bbb2a`)

PASS: V1a-Nachtest (Anzeige unter „Fertig“, Validator „No implementation issues found“), K7 (nur die 3 Kopien entfernt,
Originale erhalten, Limit 8/25 → 5/25 – Pro-Test hebt das Limit nicht auf), M3 (3 native Anzeigen ≤ 30), Logcat
(0 FATAL, 0 ANR). BLOCKED: P (Pro-Test vor Messbeginn abgelaufen), M1a (Bonus heute verbraucht). FAIL: K8.

| # | Befund | Ursache | Behebung | Nachweis |
|---|---|---|---|---|
| K8 | Eine Datei erschien mehrfach im Papierkorb (9 → 14), Löschen blockiert („Tageslimit erreicht“), Datei blieb | Dieselbe Datei war über mehrere verbundene Ordner erreichbar (z. B. „Download“ und „Download/Test“) und bekam je Ordner einen eigenen Verweis. Dateiliste, Speicher-Übersicht und Papierkorb zählten sie mehrfach; Leeren hätte dieselbe Datei mehrfach gegen das Limit gebucht. Die Alias-Prüfung im Ledger wurde vom App-Code nie genutzt | Dateiliste/Speicher: je Datei (Anbieter + Dokument-ID) ein Eintrag, bevorzugt der schon entschiedene. Papierkorb zählt je Datei einmal. Leeren übergibt Alias-Schlüssel (doppelte Verweise werden vor Android und vor dem Limit abgelehnt). Nach dem Löschen verlassen ältere Doppel-Markierungen den Papierkorb (nur „Zurücklegen“) | `FileAliasTest.sameFileViaTwoFoldersCountsOnce` |

## Lauf 7 (2026-10-03 Gerätetag manuell, Commit `13d924d`, perf-APK)

PASS: K8-Nachtest (Datei 1× in der Liste, nur die Datei gelöscht, Limit exakt −1, Verlauf „Entfernt (bestätigt)“),
P HOCH (2,84 % janky, p90 15 ms), P LINKS (2,89 %, p90 18 ms), Logcat (0 FATAL, 0 ANR).
M1a: mit Google-Testanzeigen nicht prüfbar (die Testanzeige vergibt die Belohnung sofort) – abgedeckt durch
`MonetizationTest.closedBonusVideoGrantsNothing`; Gerätetest mit echter Anzeige im Release-Lauf.

| # | Befund | Ursache | Behebung |
|---|---|---|---|
| P RECHTS | 0,67 % janky, aber p50 22 ms / p90 26 ms (HOCH/LINKS: p90 15/18 ms) | Nur „Später“ macht nach dem Speichern zusätzliche Arbeit: (1) Erinnerung einplanen – WorkManager-Abfragen + Einplanen bei JEDEM Wisch, obwohl der Standardtag gleich bleibt; (2) die Später-Pille wurde bei jedem Wisch aus- und wieder eingeblendet (zwei zusätzliche Animationen parallel zum Wegfliegen) | (1) Einmal je Tag und Prozess einplanen; (2) bei aufeinanderfolgenden „Später“ bleibt die Pille stehen und wird nur ersetzt. Test `FeedFlowTest.consecutiveLaterKeepsOnePillForTheLatestItem`. Gerät: Neumessung nötig |

### Lauf 7b (Gerätetag 2026-10-04, Commit `af24c1c`, perf-APK, Pro-Test aktiv, keine Werbekarten)

| Richtung | Janky | p50 | p90 | p99 | Status |
|---|---|---|---|---|---|
| RECHTS / Später | 2,83 % | 13 ms | 20 ms | 27 ms | PASS (vorher p90 26 ms) |
| HOCH / Behalten | 2,65 % | 11 ms | 15 ms | 20 ms | PASS |

„Ändern“ nach zwei „Später“ hintereinander betrifft das zuletzt gewischte Medium (Später-Bereich: 1. Medium Standardtermin
6.10., 2. Medium per Drehrad 7.10.) – PASS. Damit sind alle drei Wischrichtungen auf dem Gerät innerhalb der Grenzen
(janky < 10 %, p90 < 25 ms; LINKS aus Lauf 7: 2,89 %, p90 18 ms).
