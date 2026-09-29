#!/usr/bin/env bash
# Legt synthetische QA-Medien (Präfix QA_) auf einem Emulator/QA-Gerät an und startet den QA-Build.
# NUR für Emulatoren oder dedizierte QA-Geräte – niemals auf einem privaten Gerät ausführen.
# Gegenstück zu scripts/qa_seed_simulator.sh (iOS).
set -euo pipefail
command -v adb >/dev/null || { echo "adb fehlt"; exit 1; }
command -v convert >/dev/null || { echo "ImageMagick (convert) fehlt"; exit 1; }
tmp=$(mktemp -d)
colors=(7FB3F5 F5B97F 8FD6A4 E58FB0 B39DDB 80CBC4 FFE082 90A4AE)
for i in $(seq 0 19); do
  c=${colors[$((i % ${#colors[@]}))]}
  convert -size 1200x1600 "xc:#$c" -fill white -draw "circle 600,700 600,400" "$tmp/QA_$i.jpg"
done
# Exakte Duplikate (gleiche Bytes, anderer Name)
cp "$tmp/QA_0.jpg" "$tmp/QA_dup_A.jpg"; cp "$tmp/QA_0.jpg" "$tmp/QA_dup_B.jpg"
adb shell mkdir -p /sdcard/Pictures/QA /sdcard/Pictures/Screenshots
adb push "$tmp"/QA_*.jpg /sdcard/Pictures/QA/ >/dev/null
adb push "$tmp/QA_1.jpg" /sdcard/Pictures/Screenshots/QA_Screenshot_1.jpg >/dev/null
adb shell 'for f in /sdcard/Pictures/QA/* /sdcard/Pictures/Screenshots/QA_*; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null; done' || true
adb shell am start -n app.photomaid.android.qa/app.photomaid.MainActivity --ez qa-fixtures true
echo "QA-Medien angelegt: $(ls "$tmp" | wc -l) Dateien"
