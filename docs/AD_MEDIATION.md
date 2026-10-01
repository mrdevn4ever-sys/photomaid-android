# AdMob-Mediation – Vorbereitung (Hebel 1)

Ziel: mehr Umsatz je Anzeige ohne zusätzliche Anzeigen. Mehrere Netzwerke bieten auf dieselbe Fläche (Feed-Karte,
Zusammenfassung, Rewarded); die App zeigt nicht häufiger Werbung. Prüfstatus: **NOT RUN** (Konten fehlen).

## Was im Code schon passt
- Alle Anzeigen laufen über AdMob (`AdMobFeedAds`, `AdMobRewardedGateway`). Mediation wird im AdMob-Konto konfiguriert –
  die Lade-/Anzeigelogik der App bleibt unverändert.
- Einwilligung über Google UMP vor dem SDK-Start. UMP gibt die Einwilligung an die in AdMob eingetragenen
  Mediationspartner weiter (Anzeigenpartner-Liste in der Datenschutz-Nachricht).
- Anfragen bleiben leer (keine Medien, Namen, Pfade, Hashes, IDs) – gilt auch für Adapter, da die App nichts mitgibt.

## Schritte für den Inhaber (nicht automatisierbar)
1. AdMob → Mediation → Mediationsgruppe je Format (Native, Rewarded), Land/Plattform Android.
2. Netzwerke wählen (Empfehlung zum Start, beide mit Bidding): **AppLovin**, **Meta Audience Network**; später optional
   Unity Ads, Mintegral, Pangle. Je Netzwerk eigenes Konto + App + Anzeigenblöcke anlegen und in AdMob verknüpfen.
3. Datenschutz & Nachrichten → DSGVO-Nachricht → Anzeigenpartner um die gewählten Netzwerke ergänzen.
4. Play Console → Datensicherheit: Angaben um die Netzwerke ergänzen (Werbe-ID, Geräte-IDs).
5. Erst danach Adapter einbinden (eine Abhängigkeit je Netzwerk, Versionen aus der jeweiligen AdMob-Mediationsanleitung):
   `com.google.ads.mediation:applovin:<version>`, `com.google.ads.mediation:facebook:<version>` in `app/build.gradle.kts`
   (nur `release`/`perf`, nicht `qa`). Kein Adapter ohne Konto – unkonfigurierte Adapter bringen nichts und vergrößern die APK.
6. Gegenprobe: AdMob „Ad Inspector“ in einem `perf`-Build (Testgerät eingetragen), je Gruppe eine Testanfrage.

## Nicht tun
- Keine Interstitials/App-Open-Anzeigen (stören den Fluss, widersprechen „minimal nerven“).
- Keine Anzeigenhäufigkeit über `FeedAdPolicy` hinaus erhöhen, um Mediation „auszunutzen“.
