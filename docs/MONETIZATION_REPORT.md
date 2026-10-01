# Werbung + Kaufmomente (2026-09-30)

Branch `claude/product-ux-favorites`. Prüfstatus: PASS / FAIL / NOT RUN / BLOCKED.

## 1. Werbekarte im Feed (nur Free)
- Regel zentral in `core/.../ads/FeedAdPolicy.kt`: alle **7** Entscheidungen, die ersten **30** Entscheidungen nach der
  Installation werbefrei, höchstens **30** Anzeigen je lokalem Tag (seit 2026-10-02, Feed-Karte + Zusammenfassung gemeinsam), Mindestdauer **1,5 s**. Zähler `feedads.v1` (nur Zahlen).
- Die Karte ist **nie eine Entscheidung**: kein Ledger-Eintrag, zählt nicht fürs Tageslimit, „links“ legt nichts in den
  Papierkorb. Jede Wischrichtung überspringt sie nach 1,5 s, außerdem der Knopf „Weiter“ (vorher gedimmt).
  „runter“ (Zurück) und Rückgängig überspringen sofort.
- Beim Erscheinen stoppt der Player (keine Werbung über laufendem Video) und die Später-Pille verschwindet.
- Beschriftung „Anzeige“, darunter „Ohne Werbung mit Pro“ (öffnet Pro). TalkBack-Aktion „Weiter“.
- Lifetime-Pro und Pro-Test (Rewarded) sehen keine Karten.

## 2. AdMob + Einwilligung
- `play-services-ads` 25.5.0, `user-messaging-platform` 4.0.0. `MobileAdsInitProvider` ist entfernt; das SDK startet
  erst, wenn UMP `canRequestAds()` erlaubt (nach dem Datenschutz-Intro, nie für Lifetime-Pro, nie im QA-Modus).
- Native Anzeige in `NativeAdView` (Pflichtansicht), Rewarded (30-Minuten-Pro-Test) über AdMob mit Belohnung nur über
  `onUserEarnedReward`.
- IDs: debug/qa = offizielle Google-Test-IDs. Release nur über `-Pphotomaid.admobAppId`, `-Pphotomaid.admobNativeUnit`,
  `-Pphotomaid.admobRewardedUnit`; ohne sie ist Werbung im Release vollständig aus. Echte IDs nie im Repo.
- Einstellungen: „Werbe-Einwilligung ändern“, wenn UMP das verlangt (EU).
- Anfragen enthalten nie Medien, Dateinamen, Pfade, Hashes oder IDs (leerer `AdRequest`).

## 3. Kaufmomente
- **Zusammenfassung:** nach dem ersten Durchgang mit Aussortiertem einmal eine deutliche Karte („Stark aufgeräumt!“ …),
  danach eine leise Zeile „N aussortiert. Mit Pro ohne Tageslimit und ohne Werbung.“ Nicht für Lifetime-Pro.
- **Limit früh:** ab weniger als 5 freien Entfernungen „Heute noch 3 von 15 frei · Ohne Limit mit Pro“ (Zusammenfassung,
  Papierkorb). Echte Zahl aus dem Ledger.
- **Limit-Blatt:** „Alle N entfernen – mit Pro“ ist primär, „X davon entfernen“ ist sekundär.
- **Heute:** leise Pro-Zeile nur für Free und erst ab dem zweiten Nutzungstag (`firstSeenDay`).
- **Pro-Seite:** gezeichnete Kopfgrafik (hell/dunkel), neuer erster Vorteil „Ohne Werbung“, Text „Einmal zahlen, für immer
  werbefrei“. Datenschutz-Intro nennt AdMob, wenn es konfiguriert ist.

## 4. Tests
- core `FeedAdPolicyTest` (3).
- app `FeedAdsTest` (7): Karte nach der fälligen Entscheidung, keine Entscheidung, Mindestdauer, nächste Karte nach
  weiteren 7, Aufwärmphase, Lifetime/Pro-Test ohne Werbung, Zurück überspringt sofort, Pro-Link.
- app `PurchaseMomentsTest` (5), `BasketTest` angepasst.
- Screenshots `z01`–`z09` in `docs/screenshots/` (Fake-Anzeige ohne SDK).

## 5. Nicht geprüft / offen
- Echte AdMob-Anzeigen, Einwilligungsdialog und Rewarded auf einem Gerät: **NOT RUN** (kein Gerät, kein AdMob-Konto).
- Offen für den Inhaber (RELEASE_BLOCKERS #4): AdMob-Konto, Anzeigenblöcke, Einwilligungsnachricht, Play-„Datensicherheit“
  (Werbe-ID), `AD_ID`-Erklärung, Preis (2.99–3.99).

## 6. Mehr Umsatz bei wenig Nerven (2026-10-02)

Reihenfolge nach Umsatz pro Ärger. Umgesetzt (Prüfstatus JVM: PASS, Gerät: NOT RUN):

| # | Hebel | Umsetzung |
|---|---|---|
| 2 | Anzeige in der Zusammenfassung | `SummaryAd` ganz unten unter „Fertig“ (Zusammenfassung und Knöpfe zuerst sichtbar), gekennzeichnet „Anzeige“, darunter „Ohne Werbung mit Pro“. Nur Free, nach der Aufwärmphase, nie neben dem einmaligen deutlichen Pro-Angebot, nie für Pro-Test/Lifetime. Einmal je Durchgang (zurück und wieder vor = dieselbe Anzeige). Zählt zur Tagesgrenze. Scrollen über der Anzeige: die Karte übernimmt die Bewegung ab Schwelle (Initial-Pass) und scrollt selbst inkl. Schwung – die Anzeige erhält CANCEL, Scrollen ist nie ein Klick. Vorladen, wenn der Durchgang in ≤ 2 Medien endet |
| 3 | Video-Bonus „+10 für heute“ | Im Limit-Blatt unter „Alle N entfernen – mit Pro“ (Pro bleibt primär): „Video ansehen: +10 für heute“ + „Kurze Werbung, 1× täglich. Gilt nur heute.“ Nach dem Reward-Callback geht es mit derselben Auswahl direkt weiter (Android-Bestätigung bzw. Limit-Blatt mit neuer Zahl). Abbruch/No-Fill verbraucht nichts. Getrennt vom 30-Minuten-Pro-Test (beides am selben Tag möglich). Für Free und Pro-Test, nie Lifetime |
| 4 | Tagesgrenze 20 → 30 | `FeedAdPolicy.DAILY_CAP = 30` (Takt 7 und Aufwärmphase 30 unverändert) |
| 1 | Mediation | vorbereitet, siehe `docs/AD_MEDIATION.md` (Konten/Adapter durch den Inhaber) |

Tests: core `EntitlementTest.limitBonusAddsTenForTodayOnly`, `FeedAdPolicyTest` (Grenze 30); app `MonetizationTest` (7):
Bonus schaltet +10 frei und führt direkt zur Bestätigung, abgebrochenes Video schaltet nichts frei, Lifetime ohne Bonus,
Zusammenfassungs-Anzeige nur für Free nach der Aufwärmphase, nicht neben dem deutlichen Angebot, nicht für den Pro-Test.

