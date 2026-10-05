#!/usr/bin/env bash
# Installs the APK, walks through the main screens against the live site, downloads a range of
# chapters, then reads one with the network off. Screenshots and logs go to $2.
set -u
APK="$1"; OUT="$2"; PKG=com.kolnovel.reader
mkdir -p "$OUT"
UI="python3 android/ci/ui.py"
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
start() { adb shell am start -n $PKG/.MainActivity "$@" > /dev/null; }

adb install -r "$APK" || { echo "install failed"; exit 1; }
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb logcat -c

start; shot 01-home 25

# A real novel link from the site's front page (the runner can reach the site).
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
curl -sL -A "$UA" https://kolnovel.com/ -o "$OUT/home.html"; echo "site http: $(curl -s -o /dev/null -w '%{http_code}' -A "$UA" https://kolnovel.com/)"
NOVEL=$(grep -o 'https://kolnovel.com/series/[^"/]*/' "$OUT/home.html" | head -1)
echo "novel: $NOVEL"
rm -f "$OUT/home.html"

$UI "تصفح" x; shot 02-browse 12
$UI "الإعدادات" x; shot 03-settings 4

if [ -n "$NOVEL" ]; then
  start --es novel "$NOVEL"; shot 04-details 15
  $UI "نطاق فصول" && shot 05-range-dialog 2
  $UI "التالية 10" x
  $UI "تنزيل 10" x || $UI "تنزيل" x
  shot 06-downloading 8
  sleep 30
  start --es open downloads; shot 07-downloads 4
  start --es novel "$NOVEL"; sleep 8
  adb shell input swipe 540 1700 540 600 300; shot 08-chapter-list 3
  $UI "ابدأ القراءة" || $UI "تابع"
  shot 09-reader 10
  # Offline: the saved chapters must still open.
  adb shell cmd connectivity airplane-mode enable; sleep 3
  adb shell am force-stop $PKG
  start --es novel "$NOVEL"; shot 10-offline-details 10
  $UI "ابدأ القراءة" || $UI "تابع"
  shot 11-offline-reader 6
  adb shell cmd connectivity airplane-mode disable
fi

adb logcat -d -b crash > "$OUT/crash.txt"
adb logcat -d | grep -iE "kolnovel|AndroidRuntime|FATAL" | tail -300 > "$OUT/logcat.txt"
echo "done"
