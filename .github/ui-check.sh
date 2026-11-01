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
shot() { sleep "${2:-2}"; calm; adb exec-out screencap -p > "shots/$1.png" && report "shot $1"; }
step() { adb shell am start -n $PKG/.MainActivity --es facet_ui_step "$1" >/dev/null 2>&1; report "step $1"; }
scrollup() { adb shell input swipe 540 1500 540 700 600; report "scrolled"; }
relaunch() { adb shell am force-stop $PKG; sleep 1; adb shell am start -n $PKG/.MainActivity >/dev/null; report "relaunched"; }

adb shell settings put global window_animation_scale 1
sleep 40
calm
adb install -r "$APK" | tee -a shots/report.txt
report "RAM: $(adb shell cat /proc/meminfo | head -1)"
adb logcat -c
adb shell am start -n $PKG/.MainActivity
shot 01-welcome 15
step skip
shot 02-home-auto 6
scrollup
shot 03-home-auto-scrolled 3
step menu
shot 04-menu-auto 6
step back
step settings
shot 05-settings 4
step appearance
shot 06-appearance 3
scrollup
shot 06b-appearance-glass 3
step back
step back

# Full tier: live blur with the lens, web pages in a TextureView
step glass-full
sleep 3
relaunch
shot 07-home-full 12
scrollup
shot 08-home-full-scrolled 3
step menu
shot 09-menu-full 6
step back
step web
shot 10-web-full 25
step menu
shot 11-menu-over-web-full 6
step back

report "---- logcat: Facet warnings and crashes ----"
adb logcat -d | grep -E " Facet|FATAL EXCEPTION|E AndroidRuntime|glass|$PKG" | grep -v -E "uiautomator|WindowManager|ziparchive|ImsResolver|CarrierSvc|ConnectivityService|NetworkFactory" | head -150 | tee -a shots/report.txt
if adb logcat -d | grep -q "FATAL EXCEPTION"; then report "RESULT: crashed"; else report "RESULT: no crash"; fi
adb shell pidof $PKG >/dev/null && report "app still running" || report "app NOT running"
exit 0
