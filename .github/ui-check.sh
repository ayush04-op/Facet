#!/usr/bin/env bash
# Drives Facet on the emulator and saves screenshots plus a log report into ./shots.
set -u
PKG=app.facet.browser
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p shots
report() { echo "$*" | tee -a shots/report.txt; }

shot() {
  sleep "${2:-2}"
  adb exec-out screencap -p > "shots/$1.png" && report "shot $1"
}

# Taps the centre of the first element whose text or content description matches $1.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml shots/ui.xml >/dev/null 2>&1
  local b
  b=$(python3 - "$1" <<'PY'
import re, sys
t = sys.argv[1]
x = open('shots/ui.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    txt = re.search(r' text="([^"]*)"', n)
    desc = re.search(r'content-desc="([^"]*)"', n)
    if (txt and txt.group(1) == t) or (desc and desc.group(1) == t):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
)
  if [ -n "$b" ]; then
    adb shell input tap $b
    report "tap '$1' at $b"
  else
    report "MISSING '$1' on screen"
  fi
}

adb install -r "$APK" | tee -a shots/report.txt
adb logcat -c
adb shell am start -n $PKG/.MainActivity
shot 01-welcome 14
tap "Skip"
shot 02-home 6
tap "Menu"
shot 03-menu 3
tap "Settings"
shot 04-settings-push 1
shot 05-settings 2
tap "Appearance"
shot 06-appearance 2
adb shell input keyevent KEYCODE_BACK
shot 07-back-to-settings 1
adb shell input keyevent KEYCODE_BACK
shot 08-back-home 2
tap "Address"
sleep 1
adb shell input text "example.com"
adb shell input keyevent KEYCODE_ENTER
shot 09-web 20
tap "Menu"
shot 10-menu-over-web 3
adb shell input keyevent KEYCODE_BACK

report "---- logcat: Facet warnings and crashes ----"
adb logcat -d | grep -E "Facet|FATAL EXCEPTION|AndroidRuntime|liquid glass" | head -200 | tee -a shots/report.txt
if adb logcat -d | grep -q "FATAL EXCEPTION"; then report "RESULT: crashed"; else report "RESULT: no crash"; fi
adb shell pidof $PKG >/dev/null && report "app still running" || report "app NOT running"
rm -f shots/ui.xml
exit 0
