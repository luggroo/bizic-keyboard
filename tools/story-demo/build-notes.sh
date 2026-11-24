#!/usr/bin/env bash
# Build the independent Android notes app and its on-device playback driver.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME}"
root=$(cd "$(dirname "$0")" && pwd)
out="${1:?Output APK path required}"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
sdk="$ANDROID_HOME/build-tools/35.0.0"
android="$ANDROID_HOME/platforms/android-35/android.jar"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex" "$(dirname "$out")"
"$sdk/aapt2" link -I "$android" --manifest "$root/AndroidManifest.xml" --min-sdk-version 23 --target-sdk-version 28 --version-code 1 --version-name 1.0 -o "$work/base.apk"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$android:$sdk/core-lambda-stubs.jar" -d "$work/classes" "$root"/src/biz/tujia/storynotes/*.java
jar cf "$work/code.jar" -C "$work/classes" .
"$sdk/d8" --min-api 23 --lib "$android" --output "$work/dex" "$work/code.jar"
(cd "$work/dex" && zip -q "$work/base.apk" classes.dex)
"$sdk/zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
key="${TUJIA_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
[[ -f "$key" ]] || { echo "Provide a debug keystore using TUJIA_DEBUG_KEYSTORE" >&2; exit 2; }
"$sdk/apksigner" sign --ks "$key" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false --out "$out" "$work/aligned.apk"
"$sdk/apksigner" verify "$out"
