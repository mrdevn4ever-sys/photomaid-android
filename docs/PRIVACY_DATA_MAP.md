# Datenschutz-Datenkarte (Android) und Google-Play-„Datensicherheit“

Gegenstück zu `docs/PRIVACY_DATA_MAP.md` der iOS-App.

| Daten | Ort | Verlässt Gerät? | Backup | Schutz |
|---|---|---|---|---|
| Entscheidungen, Verlauf, Undo, Journal, Tageskontingent | Room (`photomaid.db`, App-internes Verzeichnis) | Nein | Android-Backup des Nutzers (`backup_rules.xml`, `data_extraction_rules.xml`): bewusst enthalten, damit Entscheidungen eine Wiederherstellung überleben (wie iOS) | App-Sandbox, Dateiverschlüsselung des Systems |
| Ordnerfreigaben (SAF-Tree-URIs), Regeln, Einstellungen, Reward-Ledger, Kauf-Cache | Room (`key_value`) | Nein | wie oben | wie oben |
| Vorschaubilder | nur Arbeitsspeicher (Coil-Speichercache, 20 %); **kein** Disk-Cache | Nein | – | – |
| SHA-256-Hashes | nur im Speicher während des Duplikat-Scans | Nein | – | – |
| Medien, Dateinamen, Pfade, Medien-/Dokument-IDs, Metadaten | nur lokal gelesen | **Nein** | – | – |
| Kaufdaten | Google Play (Billing Library) | Ja (technisch, durch Google Play) | – | Google |
| Werbedaten | Google AdMob + UMP (nur Free, erst nach Einwilligung) | Ja (durch Google: IP, Geräte-/Werbe-ID, Interaktionen, Diagnose) | – | Anfragen ohne Medien, Namen, Pfade, Hashes, IDs |

## Netzwerk

- PhotoMaid-Code selbst stellt **keine** Netzwerkverbindung her (kein HTTP-Client, Coil ohne Netzwerk-Fetcher).
- Das gebaute APK enthält trotzdem `INTERNET` + `ACCESS_NETWORK_STATE` (geprüft mit `aapt2 dump permissions`
  auf `app-release.apk`): `INTERNET` bringt `com.google.android.datatransport:transport-backend-cct` mit, das die
  **Google Play Billing Library** für eigene Diagnosedaten nutzt; `ACCESS_NETWORK_STATE`/`WAKE_LOCK` stammen aus
  Media3 (lokale Wiedergabe), `RECEIVE_BOOT_COMPLETED`/`FOREGROUND_SERVICE` aus WorkManager (Erinnerungen).
  Die Berechtigungen werden bewusst **nicht** per `tools:node="remove"` entfernt (kein Eingriff in das Billing-SDK).
  Für die Play-„Datensicherheit“ sind die Angaben von Google zur Billing Library zu übernehmen.
- Keine eigenen Analytics- oder Crash-SDKs. Werbung: Google AdMob (Free), gestartet erst nach UMP-Einwilligung.

## Berechtigungen

| Berechtigung | API | Zweck | Anfrage |
|---|---|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | 33+ | Fotos/Videos anzeigen und sortieren | nur per Button („Zugriff erlauben“) nach Erklärung |
| `READ_MEDIA_VISUAL_USER_SELECTED` | 34+ | „Ausgewählte Fotos“ (iOS: limited) | mit obiger Anfrage |
| `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | 30–32 | wie oben | wie oben |
| `POST_NOTIFICATIONS` | 33+ | optionale Erinnerungen (ohne Dateinamen) | nur per Button („Mitteilungen erlauben“) |
| `com.android.vending.BILLING` | alle | Pro-Einmalkauf | – |
| Von Bibliotheken | – | `INTERNET` (Billing-Telemetrie), `ACCESS_NETWORK_STATE`, `WAKE_LOCK` (Media3), `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` (WorkManager) | keine Laufzeitabfrage |
| Nicht verwendet | – | `MANAGE_EXTERNAL_STORAGE`, Standort, Kamera, Kontakte | – |

Löschen von Medien: ausschließlich `MediaStore.createTrashRequest` mit Systembestätigung (kein Schreibrecht nötig).
Löschen von Dateien: ausschließlich `DocumentsContract.deleteDocument` in vom Nutzer freigegebenen Ordnern.

## Aussagen in der App

- „Analyse und Sortierung erfolgen auf deinem Gerät.“ ✔
- „Es gibt keinen PhotoMaid-Server und kein Konto.“ ✔
- Cloud-Hinweis: Synchronisiert ein Cloud-Dienst (z. B. Google Fotos) die Mediathek, können Löschungen andere Geräte
  betreffen – PhotoMaid kann das nicht einsehen. ✔
- Nicht verwendet: „100 % offline“, „100 % privat“.

## Entwurf „Datensicherheit“ (Play Console) – vom Inhaber zu bestätigen

- Erfasste Daten: **keine** durch den Entwickler (Käufe werden von Google Play verarbeitet).
- Geteilte Daten: keine.
- Datenverschlüsselung bei Übertragung: nicht zutreffend (keine Übertragung durch die App).
- Löschanfrage: Deinstallation entfernt alle lokalen Daten; es gibt kein Konto.
- Werbe-SDK (AdMob) ist enthalten: in der Play Console angeben „Geräte- oder andere IDs“ (Werbe-ID) und
  „App-Aktivität/Interaktionen“ sowie „Diagnose“ als **vom SDK erfasst und geteilt** (Zweck Werbung, Analyse, Betrugsschutz),
  nicht optional in Free; Google-Angaben zum Mobile Ads SDK übernehmen.
- Öffentliche Datenschutzerklärung: https://mrdevn4ever-sys.github.io/photomaid/privacy/ (DE), …/en/ (EN), Stand 2026-10-02.
- Zielgruppe: ab 13 (keine Altersgruppe unter 13, keine Families-Richtlinie).

Keine rechtliche Freigabe durch diese Arbeit.
