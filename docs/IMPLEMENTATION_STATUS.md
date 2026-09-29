# Implementierungsstatus (Android)

Stand: 2026-09-29 · Branch `claude/android-port` · Quelle: iOS `main` @ `21717f3` (read-only).

## Umgebung

Linux-Cloud-Container (Ubuntu 24.04), JDK 21, Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20, Android SDK 37.
**Kein KVM → kein Emulator, kein Gerät.** Compose-UI-Tests und Screenshots laufen auf der JVM (Robolectric 4.17,
Roborazzi 1.75, Android-Framework SDK 35). Das ist keine Geräteabnahme.

## Phasen (Masterprompt §47)

| Phase | Inhalt | Stand |
|---|---|---|
| A Foundation | Gradle-Projekt, `:core` + `:app`, Versionskatalog, Audit/Portierungsmatrix | erledigt |
| B Design System | Tokens (Light/Dark/Kontrast), Glass-Komponenten, Typografie in sp, Strings | erledigt |
| C Medien + Repository | MediaStore, versionsabhängige Berechtigungen, SAF-Quellen, Room-Persistenz | erledigt; Gerät NOT RUN |
| D Review Engine | 1:1-Port von PhotoMaidCore inkl. aller 74 iOS-Testfunktionen | erledigt, PASS |
| E Foto-Feed | Gesten → State-Machine, Commit, Pille, Drehrad, Undo, TalkBack-Aktionen | erledigt, JVM-UI-Tests PASS |
| F Video-Feed | ein ExoPlayer, stummes Autoplay, Play/Pause, Position, Ton, Lifecycle | Code fertig; Gerät NOT RUN |
| G Papierkorb + Löschen | Journal, Tageslimit, `createTrashRequest` + Einzelprüfung, SAF-Löschen, Abgleich | erledigt, JVM-UI-Tests PASS (Fake-Mediathek); Gerät NOT RUN |
| H Später + Verlauf | Gruppen, Datum ändern, Zurückstellung entfernen, Erinnerungen (WorkManager), Verlauf mit Filtern | erledigt |
| I Home + Navigation | 4 Tabs mit eigenem Back-Stack, Vollbild-Feed, Einstellungen/Pro/Datenschutz als Routen | erledigt |
| J Pro + Billing + Reward | Capabilities zentral, Play Billing (Kauf, Acknowledge, Restore, Pending, Widerruf, Offline-Cache), Rewarded „nicht konfiguriert“ | Code fertig; Kauf BLOCKED (keine Play-Konfiguration) |
| K weitere Features | Dateien, Quellen, Große Dateien, Speicherübersicht, Duplikate, Regeln & Routinen | erledigt |
| L Visual Parity | JVM-Screenshots aller Screens (Light/Dark, Geräte, Schriftgröße) | siehe `docs/QA_MATRIX.md` |
| M Device QA | Emulator/Gerät | BLOCKED (kein KVM) |

## Nächster konkreter Schritt

1. Auf einem Rechner mit Emulator: `./gradlew :app:installQa && scripts/qa_seed_emulator.sh`.
2. Feed-Durchgang (hoch/links/rechts/schräg/runter), Papierkorb leeren (Systemdialog bestätigen **und** abbrechen),
   Prozess während des Dialogs beenden → Neustart → Abgleich prüfen.
3. Play-Console-Produkt anlegen, `-Pphotomaid.playProProductId=…`, Lizenztester-Kauf/Restore/Erstattung.
