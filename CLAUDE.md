# PhotoMaid Android: verbindliche Regeln

Native Android-App (Kotlin, Jetpack Compose). 1:1-Port der iOS-App (`mrdevn4ever-sys/photomaid`, read-only).
Kein Flutter, React Native, WebView-Oberfläche. Bei Widerspruch gewinnt das Verhalten der iOS-App
(außer Plattformgrenze, Sicherheit, Store-Policy, eindeutiger Bug – dann in `docs/PORTING_MATRIX.md` dokumentieren).

## Gesten (FEST, tatsächliche Fingerbewegung)

| Finger | Bedeutung | Danach |
|---|---|---|
| hoch | **Behalten** | nächstes Medium |
| links | **Papierkorb** (PhotoMaid-eigen, nichts gelöscht) | nächstes Medium |
| rechts (gerade oder schräg nach oben/unten, ≤ 45°) | **Später** mit Standardtermin (Vorgabe 2 Tage) | nächstes Medium + Pille „In 2 Tagen wieder · Ändern“ (eigener Termin über „Ändern“) |
| runter | **Vorheriges Medium** (keine Entscheidung) | – |

- Keine Diagonalgeste (Produktentscheidung 2026-09-29, Abweichung von iOS): zwischen 45° und „hoch“ bzw. „runter“ liegen Totzonen.
- Tipp aufs Video = Pause/Play (keine Entscheidung). Beim Ziehen eine dezente Richtungs-Pille (hoch/links/rechts), keine bei „runter“.
- Navigation: Zurück und Tabwechsel ohne Animation; Öffnen nur kurzer leichter Fade (150 ms). Predictive-Back-Vorschau aus.
- Eine einzige Gesture-/Decision-State-Machine: `core/.../feed/FeedGestureMachine.kt`.
- Nichts tun = keine Entscheidung. Eine Geste = maximal ein Schritt. Commit trägt Item-ID, Revision, Command-ID, Aktion.
- Keine Entscheidungs-Buttons im Feed (nur bei TalkBack/Schaltersteuerung). Unter dem Medium sitzt die kompakte
  Aktionsleiste ♡ Favorit · Teilen · In Ordner verschieben · ⋯ – außerhalb der Gestenfläche, nie eine Entscheidung.
- Favoriten = MediaStore-Systemfavoriten (`createFavoriteRequest`), „Favoriten schützen“ (Standard an): Wisch nach
  links auf einen Favoriten fragt nach. „Album“ gibt es als Systembegriff nicht: nur „In Ordner verschieben“.
- Schnellzugriffe auf „Heute“ (max. 6, Standard Fotos + Videos) sind nur Abfragen (`QuickAccessItem`, gespeichert ohne
  Medien-IDs). Entscheidungen hängen am Medium im Ledger; Zähler immer über `AppModel.pendingQueue` – nie je Quelle speichern.
- Pro-Test (Rewarded, 30 min, 1× pro lokalem Tag) schaltet Pro-Werkzeuge frei, hebt das Papierkorb-Limit NIE auf
  (`hasProToolsAccess` ≠ `hasUnlimitedTrashAccess`).
- Video-Bonus (Rewarded, Produktentscheidung 2026-10-02): im Limit-Blatt freiwillig „+10 für heute“, 1× pro lokalem Tag, getrennt
  vom Pro-Test, nur nach SDK-Reward-Callback (`RewardLedger.grantingBonus`, `Capabilities.cleanupBonus`). Erhöht nur die Zahl des
  laufenden Tages – nie unbegrenzt, nie für Lifetime; nur der Kauf hebt das Limit auf.
- Werbung nur in Free (`Capabilities.Tier.FREE`): native Werbekarte im Feed alle 7 Entscheidungen (`core/.../ads/FeedAdPolicy.kt`:
  30 Entscheidungen Aufwärmphase, max. 30/Tag gesamt, 1,5 s Mindestdauer) und eine ruhige Anzeige ganz unten in der
  Sitzungs-Zusammenfassung (nicht neben dem einmaligen deutlichen Pro-Angebot, zählt zur Tagesgrenze). Die Karte ist NIE eine Entscheidung und zählt nicht fürs
  Limit; „runter“ und Rückgängig überspringen sofort. AdMob startet erst nach UMP-Einwilligung; Anfragen enthalten nie Medien,
  Dateinamen, Pfade, Hashes oder IDs. Echte AdMob-IDs nie im Repo (Gradle-Properties), sonst Werbung aus.

## Verbote

- Keine automatische Löschung. Kein Backend, Konto, Upload, keine KI/ML.
- Sammlungen (statt „Regeln & Routinen“) sind reine Filter ohne Auslöser/Hintergrundarbeit; fertige für alle, eigene = Pro.
- Keine erfundenen Zahlen; unbekannte Größe ≠ 0. Keine simulierten Scans/Berechtigungen/Käufe.
- Kein `MANAGE_EXTERNAL_STORAGE`, keine Accessibility-/Shell-Tricks zum Löschen.
- Synthetische Daten nur in Tests, Previews und im Build-Typ `qa`.
- Kein automatischer Merge/Release.

## Architektur

- `:core` (Kotlin/JVM, auf Linux testbar): Domain-Port von PhotoMaidCore.
- `:app` (Android): Compose-Features, Design System (`design/`), Infrastruktur (MediaStore, SAF, Media3,
  Play Billing, WorkManager), Room-Persistenz (eine Transaktion je `LedgerChange`).
- UI-Texte ausschließlich in `res/values/strings.xml` (Deutsch, aus `Localizable.xcstrings` übernommen).

## Befehle

```sh
./gradlew :core:test                      # Domain-Kern
./gradlew :app:testDebugUnitTest          # Robolectric (Room, Compose-UI-Tests)
./gradlew :app:recordRoborazziDebug       # JVM-Screenshots nach app/build/outputs/roborazzi
./gradlew :app:assembleRelease :app:lintDebug
```

Prüfstatus immer PASS / FAIL / NOT RUN / BLOCKED. JVM-Screenshots sind keine Geräteabnahme.
