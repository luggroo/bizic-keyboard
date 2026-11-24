#!/usr/bin/env bash
set -euo pipefail
: "${WASI_SDK_PATH:?Point WASI_SDK_PATH at WASI SDK 25 or later}"
root=$(cd "$(dirname "$0")/../.." && pwd)
out="${1:?Output wasm file required}"
mapfile -t symbols < <(sed -n 's/^.*\b\(tji_[a-z0-9_]*\)(.*/\1/p' "$root/shared/include/tujia_core.h")
link=()
for symbol in "${symbols[@]}" malloc free; do link+=("-Wl,--export=$symbol"); done
"$WASI_SDK_PATH/bin/clang++" -std=c++17 -Oz -mexec-model=reactor -fexceptions -ffunction-sections -fdata-sections \
  -I "$root/shared/src" -I "$root/shared/include" "$root"/shared/src/{tujia_core,lexicon,strokes,edit}.cpp \
  "$root/tools/web-demo/wasi-exceptions.cpp" "${link[@]}" -Wl,--gc-sections -Wl,--strip-all -o "$out"
