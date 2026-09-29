# Paritätsmatrix iOS ↔ Android

Status nur: **MATCH** · **ANDROID PLATFORM ADAPTATION** · **BLOCKED BY PLATFORM** · **BUG**.
„MATCH“ bezieht sich auf Verhalten und Struktur im Code; eine Geräteabnahme steht auf beiden Plattformen aus
(iOS: App-Target laut eigenem Bericht nie auf einem Mac gebaut; Android: kein Emulator im Container).

| Feature | iOS | Android | Status | Notes |
|---|---|---|---|---|
| Gesten hoch/links/rechts/schräg/runter | `FeedGestureMachine` | `FeedGestureMachine.kt` (identische Schwellen in dp) | MATCH | 15 JVM-Tests + 12 Compose-Gestentests |
| Ablauf schräg rechts oben | sofort „Später“ + nächstes Medium, danach Drehrad, Abbrechen = Standardtermin | erst Drehrad (keine Entscheidung), Bestätigen = Später bis Datum + nächstes Medium, Abbrechen = nichts geändert | DIFF (Produktentscheidung, iOS folgt) | `FeedFlowTest` ↗-Tests, `CustomLaterTest` (5) |
| Totzonen, Hysterese, Flick, Suspendierung | ja | ja | MATCH | Zoom/TalkBack suspendieren |
| Commit mit Item-ID/Revision/Command-ID | `FeedSession` | `FeedSession.kt` | MATCH | Callback A ≠ B getestet |
| Keine Buttons im Feed, außer Bedienungshilfen | VoiceOver/Switch Control | TalkBack/Schaltersteuerung | ANDROID PLATFORM ADAPTATION | + Custom Accessibility Actions |
| Gestenhilfe beim Öffnen, abwählbar | 2,6 s | 2,6 s | MATCH | |
| Später-Pille „In 2 Tagen wieder · Ändern“ | 2,4 s | 2,4 s | MATCH | |
| Drehrad Tage/Wochen/Monate + Kalender | SwiftUI Wheel + graphical DatePicker | `NumberPicker` + Material DatePicker | ANDROID PLATFORM ADAPTATION | Abbrechen/Wegwischen = nichts geändert (Android) |
| Undo (200, Revisionsschutz, Termin gehört zu Später) | `DecisionLedger` | `DecisionLedger.kt` | MATCH | |
| Session-Zusammenfassung | ja | ja | MATCH | |
| Papierkorb (PhotoMaid-eigen) | Liste + Swipe-Aktionen | Liste + „⋯“-Menü | ANDROID PLATFORM ADAPTATION | Abwahl bleibt erhalten (iOS-Befund 1) |
| Löschen Medien | `deleteAssets` → „Zuletzt gelöscht“ | `createTrashRequest` → System-Papierkorb | ANDROID PLATFORM ADAPTATION | Systembestätigung; Einzelprüfung danach |
| Löschen Dateien | `removeItem`, endgültig | `DocumentsContract.deleteDocument`, endgültig | MATCH | eigene Bestätigung |
| Journal, Abgleich nach Neustart | ja | ja, einmal je Prozessstart | ANDROID PLATFORM ADAPTATION | iOS-Befund 4 nicht übernommen |
| Free-Limit 15 bestätigte Bereinigungen/Tag | ja | ja | MATCH | Grenzen 0/1/14/15/16 getestet |
| Pro (Einmalkauf) | RevenueCat + App Store | Google Play Billing | ANDROID PLATFORM ADAPTATION | BLOCKED: keine Store-Konfiguration (beide) |
| Rewarded-Vorschau | Protokoll, nicht konfiguriert | Protokoll, nicht konfiguriert | MATCH | Anbieter offen (beide) |
| Heute | Kennzahlen, Kategorien, fällig, Abgleich | identisch | MATCH | |
| Aufräumen-Hub (Filter Alle/Medien/Dateien) | ja | ja | MATCH | |
| Ordnen (Später, Regeln, Quellen, Aufgaben) | ja | ja | MATCH | |
| Verlauf (Filter, Tagesgruppen, Undo) | ja | ja | MATCH | |
| Später-Verwaltung (Gruppen, Datum ändern, entfernen) | ja | ja | MATCH | |
| Dateien/Downloads | `fileImporter` + Bookmarks | SAF Tree + dauerhafte Berechtigung | ANDROID PLATFORM ADAPTATION | Download-Ordner selbst nicht freigebbar |
| Downloads-Titel | erster Ordner heißt „Downloads“ | nur Ordner „Download(s)“ | ANDROID PLATFORM ADAPTATION | iOS-Befund 5 |
| Dateivorschau | Quick Look | `ACTION_VIEW` an andere App | ANDROID PLATFORM ADAPTATION | keine Archive/„Andere“ |
| Große Dateien (Filter 10–5000 MB, Sortierung) | ja | ja | MATCH | Videogrößen aus MediaStore |
| Speicherübersicht A/B/C | ja | ja + bekannte Größen je Kategorie | ANDROID PLATFORM ADAPTATION | MediaStore liefert Größe öffentlich |
| Exakte Duplikate (Größe → Teilhash → SHA-256) | ja | ja | MATCH | Hash nur im Speicher; 50k-Test |
| Regeln & Routinen | ja | ja | MATCH | Erinnerung über WorkManager |
| Erinnerungen (max. 8, ohne Dateinamen) | UserNotifications | WorkManager + NotificationManager | ANDROID PLATFORM ADAPTATION | Zustellzeit nicht exakt (Doze) |
| Datenschutz/Onboarding mit echten Zuständen | ja | ja | MATCH | keine Fake-Schalter |
| „Auswahl verwalten“ (limited) | `presentLimitedLibraryPicker` | erneute Berechtigungsanfrage (Android 14+) | ANDROID PLATFORM ADAPTATION | |
| „Nur in iCloud“ | eigener Zustand | – | BLOCKED BY PLATFORM | MediaStore listet nur lokale Dateien |
| Einstellungen (Haptik, Hinweise, Später-Standard, Erscheinungsbild) | ja | ja | MATCH | |
| Dark Mode + erhöhter Kontrast | ja | ja (Kontrast ab Android 14) | ANDROID PLATFORM ADAPTATION | |
| Liquid Glass | `glassEffect` | transluzente Glasflächen ohne Backdrop-Blur | ANDROID PLATFORM ADAPTATION | Performance vor Optik |
| App-Umschalter-Schutz | Abdeckung bei inaktiv | Abdeckung bei `ON_PAUSE` | ANDROID PLATFORM ADAPTATION | |
| Sprache | Deutsch | Deutsch | MATCH | |
| Video (1 Player, stumm, Autoplay, Position, Ton) | AVPlayer | Media3 ExoPlayer | ANDROID PLATFORM ADAPTATION | Gerät NOT RUN |
