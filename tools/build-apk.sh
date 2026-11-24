#!/usr/bin/env bash
# Build and verify a debug-signed APK with Android 35, NDK r28c and a JDK.
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:?请设置 ANDROID_HOME 指向 Android SDK}"
build_tools="$ANDROID_HOME/build-tools/35.0.0"
platform_jar="$ANDROID_HOME/platforms/android-35/android.jar"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi

source_root="$root_dir/app/src/main"
java_dir="$source_root/java"
assets_dir="$root_dir/shared/bundle/Assets"
application_id="biz.tujia.ime"
if [[ "${1:-}" == "--performance-test" ]]; then
  shift
  source_root="$root_dir/qa/device"
  java_dir="$source_root/src"
  assets_dir=""
  application_id="biz.tujia.ime.perf"
elif [[ "${1:-}" == "--device-test" ]]; then
  shift
  source_root="$root_dir/device-test"
  java_dir="$source_root/src"
  assets_dir=""
  application_id="biz.tujia.ime.e2e"
fi
out="${1:-$root_dir/build/$application_id.apk}"
case "$out" in /*) ;; *) out="$PWD/$out";; esac
if (( $# > 1 )); then echo 'Usage: build-apk.sh [--device-test|--performance-test] [output.apk]' >&2; exit 1; fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/generated" "$work/classes" "$work/dex"
manifest="$source_root/AndroidManifest.xml"
# Gradle supplies the applicationId. Standalone aapt2 requires the corresponding
# manifest attribute as well. Keep version settings in sync with the app module.
python3 - "$manifest" "$work/AndroidManifest.xml" "$application_id" <<'PY'
from pathlib import Path
import sys
from xml.etree import ElementTree as ET
source, destination, package = sys.argv[1:]
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
tree = ET.parse(source)
if 'package' not in tree.getroot().attrib:
    tree.getroot().set('package', package)
tree.write(destination, encoding='utf-8', xml_declaration=True)
PY
resources=()
if [[ -d "$source_root/res" ]]; then
  "$build_tools/aapt2" compile --dir "$source_root/res" -o "$work/resources.zip"
  resources+=("$work/resources.zip")
fi
if [[ -n "$assets_dir" ]]; then resources+=(-A "$assets_dir"); fi
"$build_tools/aapt2" link -I "$platform_jar" --manifest "$work/AndroidManifest.xml" \
  --java "$work/generated" --min-sdk-version 23 --target-sdk-version 35 \
  --version-code 5 --version-name 0.3.0 -o "$work/app.apk" "${resources[@]}"

mapfile -d '' sources < <(find "$java_dir" "$work/generated" -name '*.java' -print0)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$platform_jar:$build_tools/core-lambda-stubs.jar" \
  -d "$work/classes" "${sources[@]}"
jar cf "$work/classes.jar" -C "$work/classes" .
"$build_tools/d8" --min-api 23 --lib "$platform_jar" --output "$work/dex" "$work/classes.jar"
(cd "$work/dex" && zip -q -0 "$work/app.apk" classes*.dex)
if [[ "$application_id" == "biz.tujia.ime" ]]; then
  ndk="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
  cmake_dir="$ANDROID_HOME/cmake/3.22.1/bin"
  cmake_bin="${CMAKE_BIN:-$cmake_dir/cmake}"
  ninja_bin="${NINJA_BIN:-$cmake_dir/ninja}"
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    native_dir="$work/native/$abi"
    "$cmake_bin" -S "$root_dir/shared" -B "$native_dir" -G Ninja \
      -DCMAKE_MAKE_PROGRAM="$ninja_bin" \
      -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-23 -DANDROID_STL=c++_static \
      -DCMAKE_BUILD_TYPE=MinSizeRel
    "$cmake_bin" --build "$native_dir" --target tujia_core
    "$cmake_bin" --install "$native_dir" --prefix "$work" --strip
  done
  (cd "$work" && zip -q -0 "$work/app.apk" lib/*/libtujia_core.so)
fi
"$build_tools/zipalign" -P 16 -f 4 "$work/app.apk" "$work/aligned.apk"

# This script is for local/debug builds only. A signing key is never bundled.
keystore="${TUJIA_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
if [[ ! -f "$keystore" ]]; then
  mkdir -p "$(dirname "$keystore")"
  (umask 077; keytool -genkeypair -keystore "$keystore" -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname 'CN=Android Debug,O=Android,C=US')
fi
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false \
  --out "$work/signed.apk" "$work/aligned.apk"
"$build_tools/zipalign" -c -P 16 4 "$work/signed.apk"
"$build_tools/apksigner" verify --verbose "$work/signed.apk"
mkdir -p "$(dirname "$out")"
cp "$work/signed.apk" "$out"
echo "APK: $out"
