#!/usr/bin/env bash
set -euo pipefail
out=build/device-qa
mkdir -p "$out"
{
    adb shell getprop ro.build.fingerprint
    adb shell wm size
    adb shell wm density
    adb shell settings get secure navigation_mode
    sha256sum build/qa-keyboard.apk build/fixture.apk
} | tee "$out/device.txt"

collect() {
    adb logcat -d -b crash > "$out/crash.log" || true
    adb shell dumpsys input_method > "$out/input-method.txt" || true
    adb exec-out screencap -p > "$out/final.png" || true
}
trap collect EXIT
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
TUJIA_E2E_EVIDENCE="$out" ./device-test/run.sh build/qa-keyboard.apk build/fixture.apk 2>&1 | tee "$out/instrumentation.log"
