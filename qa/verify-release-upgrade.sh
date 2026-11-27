#!/usr/bin/env bash
# Run only on an isolated Android emulator: this removes the test package.
set -euo pipefail
pkg=biz.tujia.ime
ime="$pkg/.TujiaIme"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
for old_apk in old/*.apk; do
  for new_apk in signed/*.apk; do
    echo "Testing $old_apk -> $new_apk"
    adb uninstall "$pkg" >/dev/null 2>&1 || true
    adb install "$old_apk"
    for ((i=0; i<30; i++)); do
      adb shell ime list -a -s | tr -d '\r' | grep -Fq "$pkg/" && break
      sleep 1
    done
    adb shell ime enable "$ime"
    adb shell ime set "$ime"
    adb install -r "$new_apk"
    current=$(adb shell settings get secure default_input_method | tr -d '\r')
    [[ "$current" == "$ime" || "$current" == "$pkg/$pkg.TujiaIme" ]]
    adb shell dumpsys package "$pkg" | grep -q 'versionCode=15 '
    adb shell ime list -s | tr -d '\r' | grep -Fq "$pkg/"
  done
done
