#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${ANDROID_KEYSTORE_BASE64:?Missing repository secret ANDROID_KEYSTORE_BASE64}"
: "${ANDROID_STORE_PASSWORD:?Missing repository secret ANDROID_STORE_PASSWORD}"
: "${ANDROID_KEY_ALIAS:?Missing repository secret ANDROID_KEY_ALIAS}"
export ANDROID_KEY_PASSWORD="${ANDROID_KEY_PASSWORD:-$ANDROID_STORE_PASSWORD}"
[[ ${VERSION:?Missing APK version} =~ ^[0-9]+(\.[0-9]+)+$ ]]
# Public certificate on both APKs published as v0.3.6.
certificate=76a10e80a87bd4a6e5eab23767a303e5c8f7652eaaaa741df10f4c905f20773b
tools="${ANDROID_HOME:?Missing Android SDK}/build-tools/35.0.0"
work=$(mktemp -d "${RUNNER_TEMP:-/tmp}/bizic-sign.XXXXXXXX")
trap 'rm -rf "$work"' EXIT
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$work/store"
unset ANDROID_KEYSTORE_BASE64
mkdir "$work/output"

sign() {
  local source="$1" name="$2" output actual
  output="$work/output/土家语输入法_${name}_v${VERSION}.apk"
  "$tools/zipalign" -c -P 16 4 "$source"
  "$tools/apksigner" sign --ks "$work/store" --ks-key-alias "$ANDROID_KEY_ALIAS" \
    --ks-pass env:ANDROID_STORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
    --v4-signing-enabled false --out "$output" "$source"
  actual=$("$tools/apksigner" verify --print-certs "$output" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p')
  if [[ "$actual" != "$certificate" ]]; then
    echo 'Signing certificate differs from the public 0.3.6 release.' >&2
    return 1
  fi
}
sign unsigned/academy-handwriting.apk 学堂_有手写
sign unsigned/academy-typing-only.apk 学堂_无手写
sign unsigned/keyboard-handwriting.apk 纯输入法_有手写
sign unsigned/keyboard-typing-only.apk 纯输入法_无手写
(cd "$work/output" && sha256sum -- *.apk > SHA256SUMS)
mkdir signed
cp "$work/output/"* signed/
