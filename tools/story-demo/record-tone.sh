#!/usr/bin/env bash
# Capture the real Android screen while the tone and IPA gestures are exercised.
set -euo pipefail
keyboard_apk=${1:?Pass the keyboard APK}
fixture_apk=${2:?Pass the device-test APK}
output=${3:?Pass the output MP4 path}
adb_bin=${ADB:-adb}
"$adb_bin" shell rm -f /sdcard/tone-demo.mp4
"$adb_bin" shell screenrecord --time-limit 180 --bit-rate 4000000 /sdcard/tone-demo.mp4 &
recorder_pid=$!
stop_recording() {
  "$adb_bin" shell 'for p in $(pidof screenrecord); do kill -2 "$p"; done' || true
  wait "$recorder_pid" || true
}
trap stop_recording EXIT
TUJIA_E2E_SUITE=tone ./device-test/run.sh "$keyboard_apk" "$fixture_apk"
stop_recording
trap - EXIT
"$adb_bin" pull /sdcard/tone-demo.mp4 "$output"
test -s "$output"
