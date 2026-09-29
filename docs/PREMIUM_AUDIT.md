# Premium-Audit PhotoMaid Android

**Stand:** 2026-09-29 · Basis `claude/feed-collections-rework` @ `94da267`

Nach Entscheidung des Nutzers gilt:
- keine Diagonalgeste
- drei Tabs
- Sammlungen statt Regeln

Die Punkte des Masterprompts, die ↗, einen „Ordnen“-Tab oder „Prüfroutinen“ voraussetzen (§3, §12, §14, §18, §39, §44, §50), sind dadurch überholt.

**Grundlage des Audits:**
- Code-Durchsicht aller Screens
- zwei Gerätetests auf dem Realme RMX3933: 1. Test 19/5/2, 2. Test 9/0/0
- JVM-Screenshots

Bewertet ist das Nutzererlebnis auf dem Gerät, nicht die Wirkung statischer Screenshots.

## Bereits in Ordnung (belegt, nicht anfassen)

| Bereich | Beleg |
|---|---|
| Gesten, Undo, Papierkorb, Systemdialog, Reconcile | Kern-Tests; Gerätetest R1–R9 |
| Kein doppeltes Zurück, keine leeren Screens nach Schließen/HOME | `DeviceFindingsTest`; R1/R2 |
| Video: Standbild, Tipp-Pause, Fortschritt, Ton, Player-Freigabe bei `ON_STOP` | R4; `FeedPlayer.release()` |
| Eingeschränkter Zugriff wird erkannt | R5 |
| Edge-to-edge (`enableEdgeToEdge`), Predictive Back im Manifest aktiv (`enableOnBackInvokedCallback`) | Manifest, `MainActivity` |
| TalkBack-Alternativen zu Gesten (Custom Actions, Buttons nur mit TalkBack/Schalter) | `ReviewFeedScreen` |
| Große Schrift: gestapelte Zeilen ab 130 % | `SourceRow`; Screenshots bei 150 % und 200 % |
| Icons stammen aus einer Familie (Material *Rounded*) | im Code keine Filled- oder Outlined-Icons |
| Vorladen im Feed: vorheriges, aktuelles und zwei nächste Medien, Dekodierung auf Zielgröße, kein Disk-Cache | `FeedViewModel.windowItems`, `rememberPreviewPx` |

## P0 – vor Release beheben

1. **Video-Wiedergabefehler bleiben stumm.**
   - `FeedPlayer` hat keinen `onPlayerError`-Pfad.
   - Kann ein Video nicht dekodiert werden, bleibt ein schwarzes Bild mit Play-Symbol zurück, ohne Erklärung und ohne Ausweg außer Wischen.
2. **Querformat: Inhalte unter der 3-Tasten-Navigation und der Kamera-Aussparung.**
   - Die Screens polstern nur oben und unten.
   - Auf dem Realme liegt im Querformat die Tastenleiste rechts über dem Inhalt (Gerätetest-Screenshot `37_landscape`).
3. **Fehler beim Laden eines Vorschaubilds (Foto) sind eine Sackgasse.**
   - Es gibt nur „Ohne Entscheidung überspringen“, kein „Erneut versuchen“.
   - Der Text ist technisch („Vorschau konnte nicht geladen werden“).

## P1 – deutlich spürbare Qualität

4. **Die Kopfzeile im Feed ist zu schwer.**
   - Drei Glas-Buttons mit Schatten konkurrieren mit dem Medium.
5. **Keine Rückmeldung, ab wann ein Wisch zählt.**
   - Man spürt nicht, ab welcher Strecke Loslassen eine Entscheidung auslöst.
   - Nötig ist eine dezente, eindeutige Rückmeldung an der Schwelle.
   - Das heißt nicht, die Kapsel zurückzubringen, die der Nutzer entfernt haben wollte.
6. **Die Gestenhilfe erscheint bei jedem Öffnen des Feeds**, weil die Standardeinstellung „an“ ist. Das verdeckt das Medium immer wieder.
7. **Leere Zustände sind uneinheitlich.**
   - Später, Verlauf, Duplikate und Große Dateien zeigen nur eine Textzeile in einer Karte.
   - Die Formulierungen sind teils generisch („Noch keine Einträge.“, „Keine exakten Duplikate gefunden.“).
8. **Große Dateien: +/−-Zähler in 50-MB-Schritten.**
   - Das ist dasselbe Muster, das der Nutzer bei den Regeln abgelehnt hat.
   - Der Untertitel im Aufräumen-Bereich („in deinen Ordnern“) stimmt nicht, denn die Liste enthält auch Videos.
9. **Lade- und Fehlertexte.** Beispiele: „Prüfe …“ bei den Duplikaten, und die Ergebnismeldung heißt bei jedem Ausgang nur „Ergebnis“.
10. **Rückgängig hat keine Bewegung**: Das Medium ist einfach wieder da.
11. **Zugriffskarten**:
    - Eingeschränkter Zugriff wird in Warnfarbe gezeigt, obwohl nichts kaputt ist.
    - Bei abgelehntem Zugriff heißt der Knopf „Mehr erfahren“ statt „Zugriff ändern“.
12. **Sammlungen lesen die Mediathek einmal pro Sammlung.** Das sind 4 oder mehr MediaStore-Abfragen bei jeder Änderung. Eine Abfrage je Quelle reicht.

## P2 – Feinschliff

13. Die Bewegungs-Tokens sind aufgeteilt (`AppMotion` und `NavMotion`), und es gibt keine gemeinsamen Stufen schnell, normal und betont.
14. Eigene Buttons (Prominent, Glas, Icon) haben außer dem Ripple keine Rückmeldung beim Drücken.
15. Die Tabbar-Beschriftung ist mit 11 sp zu klein.
16. Ein erschöpftes Tageslimit wird in Rot gezeigt. Das ist eine Grenze, kein Fehler.
17. Haptik fehlt an drei Stellen: bei bestätigtem Termin, bei abgeschlossener Bereinigung und an der Entscheidungsschwelle.
18. Unterseiten haben am Ende noch den Tabbar-Abstand (96 dp), obwohl die Tabbar dort ausgeblendet ist.

## Bewusst nicht umgesetzt

- **„Freigegebener Speicher“ nach der Bereinigung.** Android legt die Medien zunächst in den System-Papierkorb (etwa 30 Tage). Speicher ist dann noch nicht frei, deshalb wäre jede Zahl falsch.
- **Tablet-Neugestaltung.** Die lesbare Breite von 720 dp und die Feed-Geometrie reichen; zuerst muss das Telefon stimmen.
- **Eigener System-Löschdialog.** Das bleibt Androids Aufgabe.
