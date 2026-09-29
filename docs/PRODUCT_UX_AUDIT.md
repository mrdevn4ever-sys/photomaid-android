# Produkt-UX-Audit & technische Entscheidungen (2026-09-29)

Basis: Branch `claude/premium-pass` @ `ca53fc7`. Arbeitsbranch: `claude/product-ux-favorites`.
Build: minSdk 30 (Android 11), target/compileSdk 37, Compose BOM 2026.09, AGP 9.4.1, Kotlin 2.4.20.

## Phase A – Ist-Zustand

1. **Existiert:** Feed mit fester Gestenmaschine (`core/feed/FeedGestureMachine.kt`), Ledger mit Undo, Später +
   Pille „Ändern“, Papierkorb (PhotoMaid-eigen) → `MediaStore.createTrashRequest` mit Systembestätigung und
   Abgleich, Tageslimit 15 (`DecisionLedger.prepareCleanup`, Capabilities `dailyCleanupLimit`), Sammlungen,
   Duplikate (SHA-256, „Identische Kopien“), große Dateien, Play-Billing-Einmalkauf, `RewardLedger`
   (30 min, 1× pro lokalem Tag), `EntitlementCenter`, Einstellungen, QA-Build-Typ `qa`.
2. **Fehlt:** Favorit setzen/entfernen, Favoritenschutz, Teilen, Ordner/Album-Aktion, Aktionsleiste im Feed,
   Vollbildvorschau im Papierkorb, Limitprüfung vor dem Bestätigen, explizite Trennung Pro-Werkzeuge/Limit,
   eigene Rewarded-UX (Zustände „bereits genutzt“, Hinweis zum Limit prominent).
3. **UI-Probleme (Screenshots `01_today_light.png`, `02_feed_photo.png`):** Heute zeigt Titel „Weiter aufräumen“
   UND CTA „Prüfung fortsetzen“ (doppelt, technisch); Speicherkarte mit großer Zahl ist visuell fast gleich stark
   wie der CTA; die Kategorienliste (Fotos/Videos/Screenshots/Dateien/Duplikate/Später) dupliziert den Tab
   „Aufräumen“ 1:1. Im Feed ist unter dem Medium Leerraum; „⋯“ sitzt oben, fern vom Daumen.
4. **Favoriten heute:** `IS_FAVORITE` wird gelesen (`MediaAsset.isFavorite`), aber nirgends angezeigt oder genutzt.
5. **Android-Systemfavoriten:** `MediaStore.IS_FAVORITE` (API 30+) lesen; schreiben mit
   `MediaStore.createFavoriteRequest(resolver, uris, value)` (API 30+) – Android zeigt **immer** eine
   Systembestätigung (außer die App hat `MANAGE_MEDIA`; nicht angefragt). Fotos und Videos. Wert ist ein
   MediaStore-Feld; ob eine Galerie-App ihn anzeigt, hängt von der App ab (Google Fotos/OEM-Galerien führen teils
   eigene Favoriten) → nicht garantiert, nicht auf Gerät verifiziert.
6. **Album/Sortieren realistisch:** Android hat **kein** universelles Album-API. MediaStore kennt nur Ordner
   (`RELATIVE_PATH`, `BUCKET_DISPLAY_NAME`); Galerie-Apps (Samsung, Realme/Oppo, Google Fotos „Auf dem Gerät“)
   zeigen Ordner als Alben, eigene App-Alben (Google Fotos, Samsung-Albumgruppen) sind privat. Möglich und
   dokumentiert: Datei per `createWriteRequest` (Systembestätigung) + `update(RELATIVE_PATH)` verschieben. Die
   Medien-ID/Content-URI bleibt dabei gleich; keine Kopie. Einschränkung: Bilder nur unter `DCIM/`, `Pictures/`,
   Videos unter `DCIM/`, `Movies/`, `Pictures/`. Backup-Einstellungen (z. B. Google Fotos) gelten je Ordner.
7. **Premium heute:** `Capabilities.resolve(purchase, rewards, now)`: PRO (Kauf) → Limit `null`; Vorschau →
   Werkzeuge an, Limit 15; FREE → Limit 15. Fachlich schon richtig getrennt, aber nur implizit über
   `dailyCleanupLimit`.
8. **Rewarded-Code:** Abstraktion `RewardedAdGateway` + `UnconfiguredRewardedAdGateway`. **Kein Ad-SDK, kein
   Anbieter, keine Test-IDs, kein Consent-Flow.** Belohnung nur bei `RewardedAdResult.Rewarded`.
9. **Tageslimit:** `core/decisions/DecisionLedger.prepareCleanup` (reserviert/zählt je `LocalDay`), UI
   `CleanupBasketScreen` – bisher erst nach der App-Bestätigung abgelehnt (Sackgasse).
10. **Betroffene Dateien:** `TodayScreen`, `ReviewFeedScreen`, `FeedViewModel`, `PhotoLibraryService`,
    `AppSettings`, `SettingsScreen`, `CleanupBasketScreen`, `ProScreen`, `Capabilities`, `RewardedAds`,
    `strings.xml`, Tests.
11. **Risiken:** Systemdialoge (Favorit/Verschieben) unterbrechen den Feed → Ergebnis immer per erneuter Abfrage
    prüfen; Verschieben ändert den Ablageort (Backup je Ordner) → im Sheet klar sagen; Aktionsleiste darf keine
    Gesten auslösen → außerhalb der Gestenfläche platzieren.

## Phase B – Entscheidungen

| Funktion | Einstufung | Umsetzung |
|---|---|---|
| Favoriten | direkt möglich, **User Confirmation erforderlich** (API 30+, ganze minSdk) | `createFavoriteRequest` → Systemdialog → Wert neu lesen; nur der gelesene Wert gilt |
| Album/Sortieren | **nur Ordner/Move möglich**, Anzeige OEM-abhängig | „In Ordner verschieben“ (kein „Album“-Versprechen): `createWriteRequest` + `RELATIVE_PATH`, danach prüfen |
| Teilen | nichts vorhanden → neu, einfach | `ACTION_SEND` + `createChooser`, `FLAG_GRANT_READ_URI_PERMISSION`, `ClipData`, echter MIME-Typ |
| Rewarded Pro | Ledger/Gateway vorhanden; **kein Anbieter** | UX + Zustände vollständig; ohne Anbieter wird der Bereich ausgeblendet. Anbieterwahl (SDK, Consent, Datenschutzangaben) ist Produkt-/Rechtsentscheidung des Inhabers → Release-Blocker, kein Ad-SDK in diesem Auftrag |
| Entitlements | vorhanden, implizit | `hasProToolsAccess` / `hasUnlimitedTrashAccess` explizit; Tier `FREE`/`TEMPORARY_PRO`/`LIFETIME_PRO` |
| Home | Hierarchie | Ein Hero mit „Jetzt aufräumen“/„Weiter aufräumen“; Kategorien/Werkzeuge nur noch unter „Aufräumen“ (dort alle vorhanden); Später/Papierkorb als kompakte Zeile; Speicher klein am Ende |

**Default „Favoriten schützen“ = AN** (neue und bestehende Installationen; das Feld ist neu, es gab vorher kein
Favoritenverhalten, also keine Verhaltensänderung für bestehende Entscheidungen). Begründung: Favorit = starkes
Nutzersignal. Jederzeit in den Einstellungen abschaltbar; der Dialog erklärt sich selbst.

**Favorit entfernen im Schutzdialog:** entfernt nur den Favoriten (Systemdialog), das Medium bleibt stehen. Danach
Hinweis „Favorit entfernt. Wische nach links, um es in den Papierkorb zu legen.“ – kein automatisches Weiterlegen
(eindeutig, keine versteckte Aktion).
