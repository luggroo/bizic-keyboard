#!/usr/bin/env bash
# Run on an unlocked, idle, dedicated test device with the IME already installed.
set -euo pipefail
if (( $# < 2 || $# > 3 )); then
  echo 'Usage: qa/measure-android.sh performance-test.apk result-dir [baseline-ime]' >&2
  exit 2
fi
fixture=$1
out=$2
adb_bin=${ADB:-adb}
ime=biz.tujia.ime/.TujiaIme
test_pkg=biz.tujia.ime.perf
mkdir -p "$out"
get_setting() { "$adb_bin" shell settings get secure "$1" | tr -d '\r'; }
old_ime=$(get_setting default_input_method)
old_hw=$(get_setting show_ime_with_hard_keyboard)
enabled=$(get_setting enabled_input_methods)
base=${3:-$old_ime}
if [[ "$base" == biz.tujia.ime/* || "$base" == null ]]; then
  echo 'Supply another enabled keyboard as baseline (adb shell ime list -s).' >&2
  exit 2
fi
[[ "$("$adb_bin" shell ime list -s | tr -d '\r')" == *"$base"* ]] || { echo 'Baseline IME is not enabled.' >&2; exit 2; }
added=false
if ! "$adb_bin" shell pm path "$test_pkg" | grep -q '^package:'; then added=true; fi
restore() {
  local code=$?
  trap - EXIT
  set +e
  "$adb_bin" shell ime set "$old_ime" >/dev/null
  if [[ "$enabled" != *"biz.tujia.ime/"* ]]; then "$adb_bin" shell ime disable "$ime" >/dev/null; fi
  if [[ "$old_hw" == null ]]; then "$adb_bin" shell settings delete secure show_ime_with_hard_keyboard >/dev/null
  else "$adb_bin" shell settings put secure show_ime_with_hard_keyboard "$old_hw" >/dev/null; fi
  if $added; then "$adb_bin" uninstall "$test_pkg" >/dev/null; fi
  exit "$code"
}
trap restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"$adb_bin" install -r "$fixture" >/dev/null
"$adb_bin" shell settings put secure show_ime_with_hard_keyboard 1
"$adb_bin" shell ime enable "$ime" >/dev/null
{
  "$adb_bin" get-serialno
  "$adb_bin" shell getprop ro.build.fingerprint
  "$adb_bin" shell getprop ro.build.version.sdk
  "$adb_bin" shell getprop ro.product.cpu.abi
  "$adb_bin" shell wm size
  "$adb_bin" shell wm density
  "$adb_bin" shell settings get system font_scale
  "$adb_bin" shell settings get global animator_duration_scale
  app_path=$("$adb_bin" shell pm path biz.tujia.ime | tr -d '\r' | sed -n '1s/^package://p')
  "$adb_bin" shell sha256sum "$app_path"
  sha256sum "$fixture"
} >"$out/device.txt"
"$adb_bin" shell am instrument -w -r -e baseline "$base" -e cold "${COLD_ROUNDS:-3}" \
  -e warm "${WARM_ROUNDS:-8}" -e keys "${KEY_ROUNDS:-32}" "$test_pkg/.PerformanceTest" | tee "$out/guest.log"
grep -q 'INSTRUMENTATION_RESULT: outcome=PASS' "$out/guest.log"
python3 "$(dirname "$0")/summarize-performance.py" "$out/guest.log" >"$out/summary.txt"
cat "$out/summary.txt"
