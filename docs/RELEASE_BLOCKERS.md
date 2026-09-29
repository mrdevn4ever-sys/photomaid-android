# Release-Blocker (Android)

Kein Release, kein Upload in die Play Console und keine Fertigmeldung, solange ein Punkt offen ist.

| # | Blocker | Art | Wer |
|---|---|---|---|
| 1 | **Keine Geräte-/Emulatorläufe**: Build-Container ohne KVM. UI-Tests und Screenshots liefen nur auf der JVM (Robolectric/Roborazzi) – keine Geräteabnahme | Verifikation | QA mit Emulator (API 30/34/37) und echten Geräten |
| 2 | Application-ID `app.photomaid.android` ist ein Platzhalter; kein Upload-Keystore/Signatur | Konfiguration | Inhaber |
| 3 | Google Play Billing: In-App-Produkt (einmalig, nicht verbrauchbar) in der Play Console anlegen, Produkt-ID per `-Pphotomaid.playProProductId=…` setzen, Lizenztester, Preis (zuletzt 3.99 CHF diskutiert) | Konfiguration | Inhaber |
| 4 | Rewarded-Werbeanbieter nicht entschieden (wie iOS); SDK, Test-Ad-Unit, UMP/Consent, Datensicherheit fehlen. UX, Zustände und Ledger sind fertig und getestet (Fake-Anbieter); ohne Anbieter wird „30 Minuten Pro testen“ ausgeblendet | Entscheidung | Inhaber |
| 5 | Kauf-Parität iOS↔Android: iOS nutzt RevenueCat; Android nutzt direkt Play Billing. Ein Kauf auf der einen Plattform schaltet die andere **nicht** frei (auch iOS-Vertrag: „Android-Käufe werden nicht als iOS-Pro gewertet“) | Produktentscheidung | Inhaber |
| 6 | Reale MediaStore-Papierkorb-Anfrage mit QA-Medien auf Gerät (Bestätigen, Abbrechen, eingeschränkter Zugriff, Prozessende während des Dialogs) | Abnahme | QA (`scripts/qa_seed_emulator.sh`) |
| 7 | SAF-Anbieter: Verhalten von Dritt-Anbietern (Offline, virtuelle Dateien, Löschen) nur für bekannte Cloud-Authorities abgelehnt | Technik/Spike | Entwickler |
| 8 | Downloads: Der Android-Download-Ordner selbst ist per SAF nicht freigebbar (Plattform, ab Android 11) – nur Unterordner | Plattformgrenze (dokumentiert) | – |
| 9 | Visuelle Abnahme Android ↔ iOS auf Geräten (iOS-Runtime-Screenshots stehen laut iOS-Report ebenfalls noch aus) | Abnahme | QA |
| 10 | TalkBack-/Schaltersteuerungs-Durchgang, Schriftgröße 200 % auf Gerät, erhöhter Kontrast (Android 14+) | Abnahme | QA |
| 11 | Performance auf Gerät (Macrobenchmark/Perfetto): 100 schnelle Entscheidungen, 30 Videowechsel, 10 000 Fotos, 4K-Video, HEIC | Abnahme | QA |
| 12 | Finales App-Icon (aktuell eigener Vektor-Platzhalter) | Design | Inhaber |
| 13 | Datenschutzerklärung-URL (`-Pphotomaid.privacyPolicyUrl=…`), Play-„Datensicherheit“, Altersfreigabe, Marken-/Store-Konflikt „PhotoMaid“ | Recht/Compliance | Inhaber |
| 14 | Menschliche Code-Review | Abnahme | Inhaber |

| 9 | Favorit/Verschieben: Systemdialoge (`createFavoriteRequest`, `createWriteRequest`) und Sichtbarkeit in OEM-Galerien (Realme, Samsung, Google Fotos) nur auf echtem Gerät prüfbar | Geräteabnahme | Inhaber/Testagent |
