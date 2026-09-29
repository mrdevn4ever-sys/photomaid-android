#!/usr/bin/env bash
# End-to-end-Durchgang auf einem Emulator (CI) mit dem QA-Build und synthetischen Medien.
# Onboarding → Heute → Feed (echte Swipes: hoch, links, rechts, runter) → Papierkorb → echter
# System-Papierkorbdialog (MediaStore.createTrashRequest) → bestätigen → Ergebnis.
# Screenshots nach $OUT, Abbruch bei jedem Absturz von PhotoMaid.
# NUR Emulator/QA-Gerät – niemals auf einem privaten Gerät.
set -euo pipefail
APK=${1:-app/build/outputs/apk/qa/app-qa.apk}
PKG=app.photomaid.android.qa
OUT=${OUT:-emulator-screens}
mkdir -p "$OUT"
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "API $API"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "screenshot $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true; adb exec-out cat /sdcard/ui.xml; }

# Mittelpunkt des ersten Knotens, dessen text oder content-desc den Suchtext enthält.
find_center() {
  dump | python3 -c '
import re,sys,xml.etree.ElementTree as ET
needle=sys.argv[1]
try: root=ET.fromstring(sys.stdin.read())
except Exception: sys.exit(1)
for n in root.iter("node"):
    if needle in (n.get("text") or "") or needle in (n.get("content-desc") or ""):
        x1,y1,x2,y2=map(int,re.findall(r"\d+",n.get("bounds")))
        print((x1+x2)//2,(y1+y2)//2); sys.exit(0)
sys.exit(1)' "$1"
}

wait_text() { # $1 Text, $2 Sekunden
  for _ in $(seq 1 "${2:-20}"); do find_center "$1" >/dev/null && return 0; sleep 1; done
  echo "Text nicht gefunden: $1"; dump | head -c 4000; shot "fail_$(echo "$1" | tr -cd '[:alnum:]')"; return 1
}

tap_text() { wait_text "$1" "${2:-20}"; read -r x y < <(find_center "$1"); adb shell input tap "$x" "$y"; sleep 1; }

check_crash() {
  if adb logcat -d -b crash | grep -q "$PKG"; then adb logcat -d -b crash | tail -60; echo "ABSTURZ"; exit 1; fi
}

adb logcat -c || true
adb install -r -g "$APK"
if [ "$API" -ge 33 ]; then
  for p in READ_MEDIA_IMAGES READ_MEDIA_VIDEO POST_NOTIFICATIONS; do adb shell pm grant "$PKG" "android.permission.$p" || true; done
else
  adb shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE || true
fi
adb shell settings put global window_animation_scale 1 || true

adb shell am start -W -n "$PKG/app.photomaid.MainActivity" --ez qa-fixtures true --ez qa-seed-media true
wait_text "Deine Privatsphäre zuerst" 40
shot 01_onboarding
tap_text "Weiter"
wait_text "auf deine Entscheidung" 30
shot 02_today
check_crash

tap_text "Prüfung starten"
wait_text "Medien prüfen" 20
sleep 2
shot 03_feed
read -r W H < <(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr 'x' ' ')
CX=$((W / 2)); CY=$((H / 2))
swipe() { adb shell input swipe "$CX" "$CY" "$((CX + $1))" "$((CY + $2))" 180; sleep 2; }
swipe 0 -$((H / 3))        # hoch = Behalten
shot 04_after_keep
swipe -$((W / 2)) 0        # links = Papierkorb
wait_text "Papierkorb: 1" 10
shot 05_after_trash
swipe $((W / 2)) 0         # rechts = Später + Pille
wait_text "In 2 Tagen wieder" 5 || true
shot 06_later_pill
sleep 3
swipe 0 $((H / 3))         # runter = vorheriges Medium
shot 07_after_back
check_crash

tap_text "Papierkorb: 1"
wait_text "1 im Papierkorb" 15
shot 08_basket
tap_text "Papierkorb leeren"
tap_text "über Android entfernen"
sleep 3
shot 09_system_trash_dialog
# Systemdialog (Sprache des Emulators) bestätigen.
if find_center "Allow" >/dev/null; then tap_text "Allow"
elif find_center "Zulassen" >/dev/null; then tap_text "Zulassen"
elif find_center "In den Papierkorb" >/dev/null; then tap_text "In den Papierkorb"
elif find_center "Move to trash" >/dev/null; then tap_text "Move to trash"
else echo "Kein bekannter Bestätigungsknopf"; dump | head -c 4000; fi
wait_text "Papierkorb des Systems verschoben" 20
shot 10_result
check_crash

# Prozessende + Neustart: gespeicherte Entscheidungen bleiben (In-Memory-QA-Speicher gilt nur je Prozess;
# hier wird nur geprüft, dass der Neustart ohne Absturz gelingt).
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/app.photomaid.MainActivity" --ez qa-fixtures true
wait_text "Deine Privatsphäre zuerst" 40
check_crash
echo "SMOKE OK"
