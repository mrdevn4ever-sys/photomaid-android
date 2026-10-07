PHOTOMAID ANDROID
ÜBERARBEITETE UI-TEXTE UND 16 SPRACHEN
====================================

Übergabe auf Basis der beiden bereitgestellten Textexporte.
Gemeldeter Quellstand: 0974248. Der Commit wurde nicht am Repository geprüft.

1. WAS ENTHALTEN IST
-------------------

Die Hauptdatei heißt PhotoMaid_Texte_i18n_16_Sprachen.json.
Sie enthält alle 552 beibehaltenen Kennungen in allen 16 vereinbarten Sprachen.
230 deutsche Einträge wurden überarbeitet; 322 blieben unverändert.
Die fünf im Ausgangsexport als unbenutzt markierten Kennungen fehlen unter texte.

Im tatsächlichen Eingangsexport stehen 557 Ressourcen: 536 Strings und 21 Plurals.
Die im Begleittext genannten 25 Plurals stimmen nicht mit der Datei überein.
Nach Entfernen der fünf Strings und neun Typumstellungen enthält dieses Paket
522 Strings und 30 Plurals. Das ergibt 8.832 Kennung-Sprach-Kombinationen und
9.672 einzelne Texte, wenn jede Mehrzahlform einzeln gezählt wird.

Sprachen:
  de     Deutsch
  en     Englisch
  es     Spanisch
  fr     Französisch
  it     Italienisch
  pt-PT  Portugiesisch für Portugal
  nl     Niederländisch
  pl     Polnisch
  ro     Rumänisch
  uk     Ukrainisch
  tr     Türkisch
  ru     Russisch
  ar     Arabisch
  ja     Japanisch
  ko     Koreanisch
  zh-CN  Chinesisch, vereinfacht

Dies ist die zuvor vereinbarte Sprachliste, keine statistisch belegte Rangliste
von zehn europäischen Sprachen. Brasilianisches Portugiesisch und traditionelles
Chinesisch wurden nicht als separate Sprachfassungen erstellt.

2. JSON DIREKT VERWENDEN
-----------------------

Jede Kennung bleibt unverändert und kommt unter texte genau einmal vor.

Bei type = string:
  text enthält den überarbeiteten deutschen Text.
  de enthält denselben deutschen Text.
  en, fr, es usw. enthalten jeweils den vollständig übersetzten Text.

Bei type = plurals:
  forms enthält die überarbeiteten deutschen Formen.
  de enthält dieselben deutschen Formen.
  Jedes Sprachfeld ist ein Objekt mit den Formen dieser Sprache, zum Beispiel
  en.one/en.other oder ar.zero/ar.one/ar.two/ar.few/ar.many/ar.other.

Nicht als sichtbare Texte importieren:
  source_text, source_forms, source_type: unveränderte Ausgangswerte.
  bereich, dateien, abschnitt: Kontext aus dem ursprünglichen Export.
  integrationshinweis_de, quantity_binding, placeholder_occurrences: Technikhinweise.
  Die mit _ beginnenden Wurzelfelder: Metadaten, Glossar, Sprachregister und Übergabe.

Die Felder pt-PT und zh-CN müssen wie normale JSON-Objektschlüssel angesprochen
werden, nicht als Punktnotation mit einem Minuszeichen.

Unter _sprachen stehen Eigennamen und Anzeigenamen in allen Zielsprachen.
Die lokalisierten Anzeigenamen stammen aus Babel 2.18.0. Sie sind Metadaten für
z. B. eine spätere zweizeilige Sprachauswahl; keine neuen Android-Textkennungen.
Eine Sprachauswahl-Oberfläche wurde hier nicht programmiert.

3. VOR DEM EINBAU: DIE ERFORDERLICHEN CODEANPASSUNGEN
--------------------------------------------------

Nur die Wortlaute zu ersetzen reicht wegen der folgenden Änderungen nicht aus.
Die vollständige maschinenlesbare Liste steht in CODE_AENDERUNGEN.json und auch
unter _code_aenderungen in der Hauptdatei.

A. Neun bestehende Kennungen werden von string zu plurals:

  later_in_days_again
  later_in_weeks_again
  later_in_months_again
  unit_days
  unit_weeks
  unit_months
  set_in_days
  qa_latest_photos_sub
  qa_latest_videos_sub

An den tatsächlichen Verwendungsstellen R.string und stringResource durch
R.plurals und pluralStringResource ersetzen. Die ID nach dem Punkt bleibt gleich.
Die im Export genannten Dateien stehen bei jeder Änderung. Es wurden keine
Kotlin-Dateien geöffnet oder automatisch gepatcht.

Beispiele, mit ausdrücklich beispielhaften Variablennamen:

  pluralStringResource(R.plurals.later_in_days_again, days, days)
  pluralStringResource(R.plurals.qa_latest_photos_sub, count, count)
  pluralStringResource(R.plurals.unit_days, days)

Der erste Zahlenparameter wählt die Mengenform. Die nachfolgenden Argumente
ersetzen die nummerierten Platzhalter. Die Einheitentexte unit_days/unit_weeks/
unit_months enthalten absichtlich keine Zahl und benötigen nur die quantity.
Die Zahl muss trotzdem an ihre Mengenauswahl gebunden sein. Für vollständige
Screenreader-Ansagen einen übersetzten Zeittext verwenden, nicht Zahl und
Einheit nach deutscher Wortreihenfolge zusammensetzen. [1]

B. Zwei Strings benötigen einen neuen Zahlenparameter:

  help_free_text  → %1$d = bestehendes kostenloses Tageslimit
  pro_f2d        → %1$d = dasselbe kostenlose Tageslimit

  stringResource(R.string.help_free_text, dailyFreeLimit)
  stringResource(R.string.pro_f2d, dailyFreeLimit)

Die Beispiele behaupten nicht, dass dailyFreeLimit schon so im Projekt heißt.
Den Wert an die vorhandene gemeinsame Limit-Konfiguration anschließen. Nicht
hierfür eine neue Konstante mit 20 anlegen. Es wird keine neue Preis-, Limit-
oder Bonusentscheidung getroffen.

C. done_unknown.one enthält nun ebenfalls %1$d.

Im Ausgangstext stand dort „Bei einem Element ...“. In einigen Sprachen kann
one aber z. B. auch für 21 stehen. Alle Formen enthalten deshalb die echte Zahl.
Da other bereits %1$d brauchte, sollte der Formatparameter schon existieren;
den konkreten Aufruf prüfen, nicht einen zweiten Parameter erfinden. [1, 2]

  pluralStringResource(R.plurals.done_unknown, unknownCount, unknownCount)

Zwei wichtige bestehende Aufrufe nicht nach Schema F behandeln:

  pluralStringResource(
      R.plurals.history_day_header, count, formattedDate, count
  )

Hier steuert count die Menge, aber %1$s ist das Datum und %2$d die Anzahl.

  pluralStringResource(
      R.plurals.result_allowance, remaining, remaining, selected
  )

Hier ist %1$d die noch mögliche Anzahl und %2$d die Auswahl. %1$d wird im Text
zweimal verwendet; das ist nur ein Formatargument, nicht zwei Argumente.

D. Fünf im Export unbenutzte Kennungen sind weggelassen:

  today_subtitle
  feed_empty_title
  col_summary
  onboarding_safe
  limit_bonus_done

Beim Merge gegen den aktuellen Code abgleichen, insbesondere falls seit dem
Export neue Verwendungen hinzugekommen sind. Unbenutzt im Export ist hier kein
neu durchgeführter Referenzscan des Repositorys.

4. MEHRZAHLFORMEN
----------------

Die gelieferten Formen orientieren sich an den Kardinalregeln von Unicode CLDR 48:

  de, en, nl, tr       one, other
  fr, es, it, pt-PT    one, many, other
  pl, ru, uk          one, few, many, other
  ro                  one, few, other
  ar                  zero, one, two, few, many, other
  ja, ko, zh-CN       other

Auch Französisch, Spanisch, Italienisch und Portugiesisch/Portugal erhalten many,
unter anderem für entsprechende Millionenzahlen. Die Android-Auswahl hängt von
den Sprachdaten des Geräts ab; alle nötigen Formen sind im Export vorhanden.
Sprachlich mengenunabhängige Formulierungen können absichtlich in mehreren
Formen gleich sein. Identische Varianten sind nicht automatisch ein Fehler. [1, 2]

one bedeutet nicht sprachübergreifend „genau eins“. Auf keinen Fall eigene
allgemeine if(count == 1)-Regeln für die Übersetzungen einbauen. Die Python-Regeln
im Prüftool sind ausschließlich Testcode, kein Vorschlag für die Android-App.

5. WAS AN DEN TEXTEN VERBESSERT WURDE
------------------------------------

Die Grundbegriffe bleiben Papierkorb, Später, Behalten, Pro, Schnellzugriffe und
Sammlungen. Die Entsprechungen in allen 16 Sprachen stehen im Glossar.

Texte beschreiben konkrete Handlungen und Folgen statt interne Abläufe.
„Wieder fällig“ wurde beispielsweise zu „Wieder dran“, „Verbundene Quellen“ zu
„Verbundene Ordner“. Meldungen wurden verkürzt oder vereinfacht, sofern dadurch
kein notwendiger Hinweis verloren geht. Gute kurze Texte wurden beibehalten.

Wichtige inhaltliche Präzisierungen:

• PhotoMaid-Papierkorb, Android-Papierkorb und endgültige Dateilöschung werden
  unterschieden. „Endgültig“ steht nicht mehr pauschal für jede Medienentfernung.

• Die frühere Anzeige „gespart“ bezeichnet nun aussortierten bzw. entfernten
  Umfang. Das ist ausdrücklich nicht dieselbe Größe wie aktuell frei gewordener
  Gerätespeicher. Der Hinweis, dass Fotos und Videos zunächst im Android-
  Papierkorb liegen, bleibt direkt verständlich. „Spätestens nach 30 Tagen“
  wurde nicht weitergegeben: Android dokumentiert eine typische Ablaufzeit,
  aber keinen solchen universellen Zeitpunkt der tatsächlichen Löschung. [3]

• Aus „Nichts geht aus Versehen verloren“ wurde „Du entscheidest, was gelöscht
  wird“. Eine Bestätigung ist eine konkrete Schutzmaßnahme, keine absolute
  Garantie gegen jeden möglichen Datenverlust.

• Lokale Fotoverarbeitung wird von technischen Daten für Kauf und Werbung
  getrennt. Keine pauschale Aussage, dass überhaupt keine Daten das Gerät
  verlassen. Die konkreten Datenschutzbehauptungen bleiben vom tatsächlichen
  Verhalten der eingesetzten SDKs abhängig; dieses wurde hier nicht geprüft.

• Pro als Einmalkauf, zeitlich begrenzter Pro-Funktionstest und täglicher
  Löschbonus werden nicht als dieselbe Freischaltung dargestellt. Das normale
  Tageslimit bleibt beim Funktionstest laut Quelle erhalten.

• Der kostenlose Einstieg behauptet nicht mehr pauschal, alles sei grenzenlos.
  Wischen und Sortieren bleiben unbegrenzt; Entfernen hat ein Tageslimit.

Alle deutschen Änderungen einschließlich alter und neuer Werte sind in
Aenderungen_DE.json und Aenderungen_DE.txt dokumentiert.

6. ANDROID-XML LIEGT EBENFALLS BEI
--------------------------------

Im Ordner android/app/src/main/res liegen 17 vollständige strings.xml-Dateien:
values als deutscher Standard und je eine Datei für die 16 Ziel-Locales.
Die Standarddatei und values-de sind bewusst identisch. Ohne passende
Sprachressourcen bleibt Deutsch die Standardfassung dieses Pakets. [4]

Portugiesisch: values-pt-rPT
Chinesisch:    values-zh-rCN
Arabisch:     values-ar

Entweder aus der JSON importieren ODER die fertigen XML-Dateien übernehmen.
Nicht zusätzlich ein zweites XML mit denselben Ressourcen im selben
Konfigurationsordner anlegen. Bestehende XML-Attribute, nicht exportierte
Ressourcen und neuere Projektänderungen beim Merge erhalten.

Die Datei android/optional/locales_config.xml ist eine optionale Vorlage für
die Android-App-Sprachliste. Sie ist absichtlich nicht mit einem ungeprüften
Manifest verbunden. Je nach vorhandener Konfiguration die App-Sprachliste
manuell integrieren oder die bereits verwendete Gradle-Generierung nutzen;
nicht zwei konkurrierende Registrierungen einführen. [5]

Es wurden keine Manifest-, Gradle-, Berechtigungs- oder Kaufänderungen vorgenommen.

7. RTL, LAYOUT UND DYNAMISCHE WERTE
---------------------------------

Arabisch braucht eine Prüfung in einer echten RTL-Konfiguration. Namen, Datum,
Größen und Preise können gemischte Schreibrichtungen enthalten; bei Bedarf
BidiFormatter bzw. die vorhandene gleichwertige Behandlung verwenden. [6]

Für PhotoMaid bleiben die physischen Gesten aus der Quelle bestehen:
  oben  = behalten und weiter
  links = PhotoMaid-Papierkorb
  rechts = später
  unten = zurück, ohne neue Entscheidung

Die UI darf für RTL angeordnet werden, aber nicht stillschweigend die Bedeutung
von links und rechts vertauschen. Genau das müssen die Gestentests prüfen.

Preise aus Google Play als fertigen Preistext einsetzen. Zahlen, Größen und
Datumsangaben in der ausgewählten App-Sprache formatieren. Keine deutschen
Satzfragmente oder Einheiten in %s aus dem Code nachliefern.

UI_Laengenpruefung.json enthält 39 Prüfhinweise für Buttons, Navigation oder
vergleichbar knappe Oberflächen. Das ist eine Zeichenbreiten-Heuristik mit
Beispielwerten, keine Pixelmessung und kein Nachweis von Abschneidungen.
Die Schutz- und Löschhinweise nicht bloß abschneiden, um einen Button einzupassen.

8. PRÜFUNG UND REPRODUZIERBARE TOOLS
-----------------------------------

Aus dem entpackten Ordner:

  python tools/validate_i18n.py
  python tools/validate_i18n.py --java
  python tools/export_android.py --out generated-res

Die ersten beiden Befehle verändern keine Dateien. Optional einen neuen Bericht
mit --report QA_NEU.json schreiben. Der Exporter schreibt in den angegebenen
Ausgabeordner. Vorhandene generierte strings.xml werden nur mit --force ersetzt.
Die Werkzeuge benötigen Python 3.10+; der optionale Java-Test zusätzlich JDK 11+.
Hier wurde mit Java 21.0.11 getestet.

Durchgeführt:
  • Vollständigkeit der 552 Kennungen in 16 Sprachen.
  • JSON- und JSON-Schema-Prüfung.
  • Alle 9.672 Texte einschließlich jeder Mehrzahlform auf Platzhalter geprüft.
  • Echte Java-Formatierung: 183.768 Aufrufe ohne Formatfehler.
  • Integer-Pluralabdeckung für 19 Beispielmengen je Ressource und Locale.
  • Zusätzlich 4.912 Mengenregelvergleiche gegen Babel 2.18.0, ohne Abweichung.
  • 17 XML-Dateien geparst und sämtliche Ressourcen zum JSON-Wert zurückgeprüft.
  • Feste Labelgruppen, Markenschreibweisen und problematische Steuerzeichen.

Nicht durchgeführt:
  • AAPT2-Compile, Kotlin-Compile, APK-/AAB-Build oder Installation.
  • Emulator-/Gerätescreenshots, TalkBack- oder echte RTL-Gestenprüfung.
  • Unabhängige muttersprachliche Abnahme oder juristische Datenschutzprüfung.
  • Nachweis des tatsächlichen Werbe-/Billing-SDK-Datenverkehrs.

Der Status PASS im QA-Bericht gilt nur für die ausdrücklich geprüften Daten-
und Formatbedingungen. Er bedeutet nicht „App-Release auf Geräten abgenommen“.
Die Übersetzungen sind vollständig ausgearbeitet, aber nicht von unabhängigen
muttersprachlichen Fachpersonen freigegeben.

Für den Einbau praktisch testen: Zahlen 0/1/2/5/11/21/22/101, alle drei Lösch-
zustände, Restlimit null, Teilentfernung, Wiederherstellung des Kaufs, Pro-Test
gegen Kauf und Bonus, lange Datei-/Ordnernamen sowie große Schrift. Außerdem
den genauen Grenzwertvergleich der Größenfilter am Code kontrollieren: Der
Eingangsexport enthält sowohl „ab“ als auch „größer als“.

9. NICHT TEIL DIESER ÜBERGABE
---------------------------

Keine Store-Beschreibungen, keine vollständige Datenschutzerklärung und keine
iOS-Localizable.xcstrings. Dafür liegen hier keine vollständigen Ausgangstexte
vor. Die Datenschutz-UI ist nicht mit einer Datenschutzerklärung gleichzusetzen.

Google verlangt für betroffene Dienste eine Einwilligungsbehandlung nicht nur
für die EU, sondern im beschriebenen Geltungsbereich auch für EWR, Vereinigtes
Königreich und Schweiz. Die alte EU-only-Formulierung ist daher nicht als
allgemeines Implementierungsversprechen übernommen worden. Ob der App-Ablauf
korrekt konfiguriert ist, muss am vorhandenen Projekt geprüft werden. [7]

10. QUELLEN UND ABGRENZUNG
-------------------------

Inhaltliche Quelle: die vom Nutzer bereitgestellten PhotoMaid-Textexporte.
Der SHA-256 der unveränderten Eingang-JSON steht in _info der Hauptdatei.
Verwendungsdateien und Abschnittsnamen stammen aus diesen Exporten, nicht aus
einem zusätzlichen Code-Audit.

Die folgenden externen Quellen wurden nur für die technische Einordnung und
klar benannten Präzisierungen verwendet, nicht als Quelle der App-Produktlogik:

[1] Android Developers, String resources
https://developer.android.com/guide/topics/resources/string-resource

[2] Unicode CLDR 48, Language Plural Rules
https://www.unicode.org/cldr/charts/48/supplemental/language_plural_rules.html

[3] Android Developers, MediaStore.MediaColumns – DATE_EXPIRES / IS_TRASHED
https://developer.android.com/reference/android/provider/MediaStore.MediaColumns

[4] Android Developers, Localize your app
https://developer.android.com/guide/topics/resources/localization

[5] Android Developers, Per-app language preferences
https://developer.android.com/guide/topics/resources/app-languages

[6] Android Developers, Support different languages and cultures
https://developer.android.com/training/basics/supporting-devices/languages

[7] Google, EU user consent policy
https://www.google.com/about/company/user-consent-policy/

DATEIEN IM PAKET
----------------
PhotoMaid_Texte_i18n_16_Sprachen.json   Vollständige Importdatei
PhotoMaid_i18n.schema.json             Schema für diese Übergabe
README_DE.txt                         Dieses Dokument
Deutsch_ueberarbeitet.txt              Alle deutschen Texte mit IDs
Glossar_16_Sprachen.json / .txt        Verbindliche Begriffe
Aenderungen_DE.json / .txt             Vorher-Nachher-Vergleich
CODE_AENDERUNGEN.json                  Präzise technische Übergabe
QA_BERICHT.json / .txt                 Tatsächlich durchgeführte Prüfungen
UI_Laengenpruefung.json                Hinweise für die Bildschirmprüfung
android/app/src/main/res/...          Generierte Sprachressourcen
android/optional/locales_config.xml   Optionale Sprachregister-Vorlage
tools/...                            Wiederholbare Prüfung und XML-Export
SHA256SUMS.txt                        Integritätswerte der Paketdateien
