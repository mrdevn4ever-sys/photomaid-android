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

