#!/usr/bin/env bash
# Drives Facet on the emulator and saves screenshots plus a log report into ./shots.
# Debug builds accept a "facet_ui_step" extra, so the script steps through screens without hunting for buttons.
set -u
PKG=app.facet.browser
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p shots
report() { echo "$*" | tee -a shots/report.txt; }

# The CI emulator is slow; when the system shows "isn't responding", choose Wait so screenshots stay clean.
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

shot() {
  sleep "${2:-2}"
  calm
  adb exec-out screencap -p > "shots/$1.png" && report "shot $1"
}

step() {
  adb shell am start -n $PKG/.MainActivity --es facet_ui_step "$1" >/dev/null 2>&1
  report "step $1"
}

# let the freshly booted emulator settle before starting
adb shell settings put global window_animation_scale 1
sleep 40
calm
adb install -r "$APK" | tee -a shots/report.txt
adb logcat -c
adb shell am start -n $PKG/.MainActivity
shot 01-welcome 15
step skip
shot 02-home 6
step menu
shot 03-menu 3
shot 03b-menu-later 6
adb shell dumpsys activity top > shots/home-menu-views.txt 2>&1
step back
shot 04-menu-closed 2
step settings
adb exec-out screencap -p > shots/05a-settings-push.png
shot 05-settings 2
step appearance
shot 06-appearance 2
step back
adb exec-out screencap -p > shots/07a-back-slide.png
shot 07-back-to-settings 2
step back
shot 08-back-home 2
step web
shot 09-web 25
step menu
shot 10-menu-over-web 4
step back
step tabs
shot 11-tabs 3
step back
adb shell cmd uimode night yes
sleep 3
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity
shot 12-home-dark 12
step menu
shot 13-menu-dark 3

report "---- logcat: Facet warnings and crashes ----"
adb logcat -d | grep -E " Facet|FATAL EXCEPTION|E AndroidRuntime|liquid glass|$PKG" | grep -v "uiautomator" | head -200 | tee -a shots/report.txt
if adb logcat -d | grep -q "FATAL EXCEPTION"; then report "RESULT: crashed"; else report "RESULT: no crash"; fi
adb shell pidof $PKG >/dev/null && report "app still running" || report "app NOT running"
exit 0
