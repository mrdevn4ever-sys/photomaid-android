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
- Keine Entscheidungs-Buttons im Feed – außer bei TalkBack/Schaltersteuerung oder wenn der Nutzer die Bedienungshilfe
  „Tasten zum Entscheiden“ einschaltet (Einstellungen, Standard aus; Produktentscheidung 2026-10-02). Tasten rufen exakt
  `FeedViewModel.perform` auf (derselbe Weg wie die Geste). Unter dem Medium sitzt die kompakte
  Aktionsleiste ♡ Favorit · Teilen · In Ordner verschieben · ✏️ Bearbeiten · ⋯ – außerhalb der Gestenfläche, nie eine
  Entscheidung (unter 360 dp wandert „Teilen“ ins ⋯-Menü).
- Favoriten = MediaStore-Systemfavoriten (`createFavoriteRequest`), „Favoriten schützen“ (Standard an): Wisch nach
  links auf einen Favoriten fragt nach. „Album“ gibt es als Systembegriff nicht: nur „In Ordner verschieben“.
- Schnellzugriffe auf „Heute“ (max. 6, Standard Fotos + Videos) sind nur Abfragen (`QuickAccessItem`, gespeichert ohne
  Medien-IDs). Entscheidungen hängen am Medium im Ledger; Zähler immer über `AppModel.pendingQueue` – nie je Quelle speichern.
- Pro-Test (Rewarded, 30 min, 1× pro lokalem Tag) schaltet Pro-Werkzeuge frei, hebt das Papierkorb-Limit NIE auf
  (`hasProToolsAccess` ≠ `hasUnlimitedTrashAccess`).
- Tageslimit Free: 20 endgültige Entfernungen pro lokalem Tag (Produktentscheidung 2026-10-04, vorher 15).
- Video-Bonus (Rewarded, Produktentscheidung 2026-10-02/04): im Limit-Blatt freiwillig „+20 für heute“, 1× pro lokalem Tag, getrennt
  vom Pro-Test, nur nach SDK-Reward-Callback (`RewardLedger.grantingBonus`, `Capabilities.cleanupBonus`). Erhöht nur die Zahl des
  laufenden Tages – nie unbegrenzt, nie für Lifetime; nur der Kauf hebt das Limit auf.
- Werbung nur in Free (`Capabilities.Tier.FREE`): native Werbekarte im Feed alle 7 Entscheidungen (`core/.../ads/FeedAdPolicy.kt`:
  30 Entscheidungen Aufwärmphase, max. 30/Tag gesamt, 1,5 s Mindestdauer, zusätzlich mind. 60 s seit der letzten Karte) und eine ruhige Anzeige ganz unten in der
  Sitzungs-Zusammenfassung (nicht neben dem einmaligen deutlichen Pro-Angebot, zählt zur Tagesgrenze). Die Karte ist NIE eine Entscheidung und zählt nicht fürs
  Limit; „runter“ und Rückgängig überspringen sofort. AdMob startet erst nach UMP-Einwilligung; Anfragen enthalten nie Medien,
  Dateinamen, Pfade, Hashes oder IDs. Echte AdMob-IDs nie im Repo (Gradle-Properties), sonst Werbung aus.

- Ausbau 2026-10 (alles ohne Bildanalyse, alles lokal):
  - Ausgenommene Ordner: `AppModel.photos` ist `ExcludingMediaLibrary` (ohne ausgenommene Ordner inkl. Unterordner);
    `allPhotos` nur für die Ordnerauswahl selbst. Gespeichert werden nur Pfade.
  - Monate (`core/.../progress/MonthProgress.kt`) und „Neu seit dem letzten Aufräumen“ (`DATE_ADDED`) sind nur Filter, nichts je
    Monat gespeichert. „Durchgesehen“ = Behalten, Papierkorb, entfernt oder Später mit Termin/ohne Termin.
  - „Später ohne Termin“ = `DecisionAction.Bookmark` (`bookmarked`): kommt nie von selbst zurück, nur über Später › Beiseitegelegt.
  - Fotoserien (Pro, `core/.../organize/PhotoSeries.kt`): gleicher Ordner, ≤ 10 s Abstand, ≥ 3 – nur „kurz hintereinander
    aufgenommen“, nie „ähnlich“/„bestes Foto“. Erst „n behalten · m in den Papierkorb“ entscheidet (ein Commit je Medium), keine Werbung dabei.
  - Raster/Mehrfachauswahl und Verschieben sind NIE eine Entscheidung (kein Ledger-Eintrag). Reihenfolge ändert nur die Abfolge.
  - Ordner-Dateien: ab Android 17 (API 37) mit `FLAG_SUPPORTS_TRASH` per `DocumentsContract.trashDocument` in den Papierkorb
    des Speicherorts, sonst endgültig mit Warnung.

- Editor (2026-10, `feature/editor/`, Kern `core/.../edit/`): nicht destruktives Rezept (`EditRecipe`), Vorschau = Export
  (gleiche Geometrie/Farbmatrix). Speichern nur als neue Datei: „Original behalten“ (Kopie) oder „Original ersetzen“
  (Original per `createTrashRequest` in den Android-Papierkorb, wiederherstellbar in „Bearbeitet“) – nie überschreiben.
  Gespeicherte Fassung = „Behalten“; Ersetzen zählt nicht zum Tageslimit. Vorauswahl = zuletzt gewählt (`editSaveMode`).
  Free: Zuschneiden/Drehen/Spiegeln/Begradigen; Regler, Filter, Größe, Video kürzen speichern nur mit `hasProToolsAccess`
  (Vorschau frei). Nie hochskalieren, keine Größenschätzung vor dem Speichern, keine Werbung im Editor.

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
- UI-Texte ausschließlich in `res/values*/strings.xml`: 16 Sprachen (`locales_config.xml`): `values` = Englisch (Fallback
  für nicht unterstützte Sprachen), `values-de` = Deutsch (Leitsprache, Tests laufen auf `de`).
  Neue Kennung immer in allen 16 Dateien; Mengen nur als `plurals` (CLDR-Formen), Begriffe laut `docs/i18n/Glossar_16_Sprachen.txt`.
  Gestenpfeile über `GestureIcons` (nie AutoMirrored): die Fingerrichtung bleibt auch in RTL (Arabisch) physisch.

## Befehle

```sh
./gradlew :core:test                      # Domain-Kern
./gradlew :app:testDebugUnitTest          # Robolectric (Room, Compose-UI-Tests)
./gradlew :app:recordRoborazziDebug       # JVM-Screenshots nach app/build/outputs/roborazzi
./gradlew :app:assembleRelease :app:lintDebug
```

Prüfstatus immer PASS / FAIL / NOT RUN / BLOCKED. JVM-Screenshots sind keine Geräteabnahme.
