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
