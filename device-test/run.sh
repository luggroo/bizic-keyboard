#!/usr/bin/env bash
# Run the actual IME across processes on an unlocked test device.
set -euo pipefail

project="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
adb_bin="${ADB:-${ANDROID_HOME:-}/platform-tools/adb}"
if [[ ! -x "$adb_bin" ]]; then adb_bin="$(command -v adb)"; fi
if (( $# > 2 )); then echo 'Usage: device-test/run.sh [ime.apk] [test-fixture.apk]' >&2; exit 2; fi
ime_apk="${1:-$work/ime.apk}"
fixture_apk="${2:-$work/fixture.apk}"

# Build before changing anything on the connected device.
if (( $# < 1 )); then "$project/tools/build-apk.sh" "$ime_apk"; fi
if (( $# < 2 )); then "$project/tools/build-apk.sh" --device-test "$fixture_apk"; fi
[[ -r "$ime_apk" && -r "$fixture_apk" ]] || { echo 'Both APKs are required.' >&2; exit 2; }
[[ "$("$adb_bin" get-state)" = device ]] || { echo 'Connect an unlocked Android device (ANDROID_SERIAL selects one).' >&2; exit 2; }

ime_pkg=biz.tujia.ime
fixture_pkg=biz.tujia.ime.e2e
ime_component="$ime_pkg/.TujiaIme"
setting() { "$adb_bin" shell settings get secure "$1" | tr -d '\r'; }
previous_ime="$(setting default_input_method)"
previous_hardware="$(setting show_ime_with_hard_keyboard)"
previous_enabled="$(setting enabled_input_methods)"
if [[ -z "$previous_ime" || "$previous_ime" == null ]]; then
    echo 'The device needs an existing default keyboard before this test can restore it.' >&2
    exit 2
fi
had_enabled=false
IFS=: read -r -a enabled_items <<< "$previous_enabled"
for entry in "${enabled_items[@]}"; do
    component="${entry%%;*}"
    if [[ "$component" == "$ime_component" || "$component" == "$ime_pkg/$ime_pkg.TujiaIme" ]]; then had_enabled=true; fi
done

added_ime=false
added_fixture=false
changed=false
cleanup() {
    local status=$? failed=0
    trap - EXIT
    set +e
    if $changed; then
        "$adb_bin" shell ime set "$previous_ime" >/dev/null || failed=1
        if ! $had_enabled; then "$adb_bin" shell ime disable "$ime_component" >/dev/null || failed=1; fi
        if [[ "$previous_hardware" == null ]]; then
            "$adb_bin" shell settings delete secure show_ime_with_hard_keyboard >/dev/null || failed=1
        else
            "$adb_bin" shell settings put secure show_ime_with_hard_keyboard "$previous_hardware" >/dev/null || failed=1
        fi
    fi
    if $added_fixture; then "$adb_bin" uninstall "$fixture_pkg" >/dev/null || failed=1; fi
    if $added_ime; then "$adb_bin" uninstall "$ime_pkg" >/dev/null || failed=1; fi
    rm -rf "$work"
    if (( failed )); then echo 'Device cleanup did not fully succeed.' >&2; exit 1; fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# An APK already installed by the user is left untouched. Only identical builds
# may be reused, so a pass always corresponds to the two APKs supplied above.
install_if_needed() {
    local pkg="$1" apk="$2" marker="$3" paths path
    paths="$("$adb_bin" shell pm path "$pkg" | tr -d '\r')" || paths=""
    if [[ "$paths" == package:* ]]; then
        path="$(sed -n '1s/^package://p' <<< "$paths")"
        "$adb_bin" pull "$path" "$work/installed.apk" >/dev/null
        if ! cmp -s "$work/installed.apk" "$apk"; then
            echo "Another build of $pkg is installed. Select its matching APK or use a clean test device." >&2
            exit 2
        fi
    else
        "$adb_bin" install "$apk"
        printf -v "$marker" '%s' true
    fi
}
install_if_needed "$ime_pkg" "$ime_apk" added_ime
install_if_needed "$fixture_pkg" "$fixture_apk" added_fixture
changed=true
"$adb_bin" shell settings put secure show_ime_with_hard_keyboard 1
"$adb_bin" shell ime enable "$ime_component" >/dev/null
"$adb_bin" shell ime set "$ime_component" >/dev/null
selected="$(setting default_input_method)"
if [[ "$selected" != "$ime_component" && "$selected" != "$ime_pkg/$ime_pkg.TujiaIme" ]]; then
    echo "Android did not select the test IME (current: $selected)." >&2
    exit 1
fi
suite="${TUJIA_E2E_SUITE:-all}"
case "$suite" in all|core|host|window|web|system|sounds|compact) ;; *) echo "TUJIA_E2E_SUITE must be all, core, host, window, web, system, sounds or compact" >&2; exit 2 ;; esac
capture=0
if [[ -n "${TUJIA_E2E_EVIDENCE:-}" ]]; then
    capture=1
    mkdir -p "$TUJIA_E2E_EVIDENCE"
    for frame in failure prediction-han-before prediction-han-after prediction-jin-before prediction-jin-after compact-idle compact-typing compact-more compact-handwriting; do
        "$adb_bin" shell rm -f "/sdcard/Android/data/$fixture_pkg/files/$frame.png"
    done
fi
"$adb_bin" shell am instrument -w -r -e suite "$suite" -e captureFailure "$capture" "$fixture_pkg/.KeyboardTest" | tee "$work/raw-result"
if [[ "$capture" == 1 ]]; then
    for frame in failure prediction-han-before prediction-han-after prediction-jin-before prediction-jin-after compact-idle compact-typing compact-more compact-handwriting; do
        remote="/sdcard/Android/data/$fixture_pkg/files/$frame.png"
        if "$adb_bin" shell test -f "$remote"; then
            "$adb_bin" pull "$remote" "$TUJIA_E2E_EVIDENCE/$frame.png"
        fi
    done
fi
tr -d '\r' < "$work/raw-result" > "$work/result"
grep -Fxq 'INSTRUMENTATION_RESULT: outcome=PASS' "$work/result"
grep -Fxq 'INSTRUMENTATION_CODE: -1' "$work/result"
