#!/usr/bin/env bash
# Drives Facet on the emulator and saves screenshots plus a log report into ./shots.
# Debug builds accept a "facet_ui_step" extra, so the script steps through screens without hunting for buttons.
set -u
PKG=app.facet.browser
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p shots
report() { echo "$*" | tee -a shots/report.txt; }

calm() {
  for _ in 1 2 3; do
    if adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedWindow" | grep -qi "not responding"; then
      adb shell input keyevent KEYCODE_DPAD_DOWN; adb shell input keyevent KEYCODE_DPAD_DOWN; adb shell input keyevent KEYCODE_ENTER
      report "dismissed a system 'not responding' dialog"
      sleep 2
    else
      break
    fi
  done
}
A="timeout 25 adb"
shot() {
  sleep "${2:-2}"; calm
  $A exec-out screencap -p > "shots/$1.png"
  local n; n=$(stat -c %s "shots/$1.png" 2>/dev/null || echo 0)
  report "shot $1 ($n bytes)"
  [ "$n" -gt 0 ] || { report "  screen capture failed; top window: $($A shell dumpsys window | grep -m1 mCurrentFocus)"; $A shell pidof $PKG >/dev/null && report "  app alive" || report "  app NOT running"; }
}
step() { $A shell am start -n $PKG/.MainActivity --es facet_ui_step "$1" >/dev/null 2>&1; report "step $1"; }
scrollup() { $A shell input swipe 540 1500 540 700 600; report "scrolled"; }
relaunch() { $A shell am force-stop $PKG; sleep 1; $A shell am start -n $PKG/.MainActivity >/dev/null; report "relaunched"; }
frames() { $A shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky frames|50th|90th|95th|99th" | sed "s/^/  [$1] /" | tee -a shots/report.txt; $A shell dumpsys gfxinfo $PKG reset >/dev/null; }

adb shell settings put global window_animation_scale 1
sleep 40
calm
adb install -r "$APK" | tee -a shots/report.txt
report "RAM: $(adb shell cat /proc/meminfo | head -1)"
adb logcat -c
(adb logcat -v time > shots/logcat-full.txt 2>&1 &)
adb shell am start -n $PKG/.MainActivity
shot 01-welcome 15
step skip
shot 02-home 6
frames home-idle
scrollup; scrollup
shot 03-home-scrolled 3
frames home-scroll
step menu
shot 04-menu 6
step back
step settings
shot 05-settings 4
step appearance
shot 06-appearance 3
scrollup
shot 06b-appearance-more 3
step back
step back

step web
shot 07-web 25
step menu
shot 08-menu-over-web 6
step back

pkill -f "adb logcat" 2>/dev/null
report "---- log: Facet warnings and crashes ----"
grep -E " Facet|FATAL|Fatal signal|E AndroidRuntime|glass|RenderThread|libhwui|DEBUG  :|backtrace" shots/logcat-full.txt | grep -v -E "uiautomator|ziparchive" | head -150 | tee -a shots/report.txt
if grep -qE "FATAL EXCEPTION|Fatal signal" shots/logcat-full.txt; then report "RESULT: crashed"; else report "RESULT: no crash"; fi
$A shell pidof $PKG >/dev/null && report "app still running" || report "app NOT running"
gzip -9 shots/logcat-full.txt

# The release build (R8-shrunk, as published): install it fresh, open the welcome and a web page, and check it runs.
REL=app/build/outputs/apk/release/app-release.apk
if [ -f "$REL" ]; then
  report "---- release build ----"
  $A shell am force-stop $PKG
  adb uninstall $PKG | tee -a shots/report.txt
  adb install "$REL" | tee -a shots/report.txt
  adb logcat -c
  (adb logcat -v time > shots/logcat-release.txt 2>&1 &)
  $A shell am start -n $PKG/.MainActivity >/dev/null
  shot 20-release-welcome 15
  $A shell am start -a android.intent.action.VIEW -d "https://example.com" $PKG >/dev/null
  shot 21-release-web 25
  pkill -f "adb logcat" 2>/dev/null
  grep -E "FATAL|Fatal signal|E AndroidRuntime|ClassNotFound|NoSuchMethod|NoSuchField" shots/logcat-release.txt | head -60 | tee -a shots/report.txt
  if grep -qE "FATAL EXCEPTION|Fatal signal" shots/logcat-release.txt; then report "RELEASE RESULT: crashed"; else report "RELEASE RESULT: no crash"; fi
  $A shell pidof $PKG >/dev/null && report "release app still running" || report "release app NOT running"
  gzip -9 shots/logcat-release.txt
fi
exit 0
