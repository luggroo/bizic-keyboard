#!/usr/bin/env bash
# Host inference test; argument is the desktop ONNX Runtime jar of the pinned version.
set -euo pipefail
cd "$(dirname "$0")/.."
jar_file="${1:?Pass the ONNX Runtime 1.20.0 desktop jar}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
case "$(uname)" in Linux) platform=linux; extension=so; link=-shared;; Darwin) platform=darwin; extension=dylib; link=-dynamiclib;; *) exit 1;; esac
"${CXX:-c++}" -std=c++17 -O2 -fPIC "$link" -Ishared/src -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/$platform" \
  app/src/main/cpp/jni.cpp shared/src/{lexicon,strokes,edit}.cpp -o "$work/libtujia_core.$extension"
src=app/src/main/java/biz/tujia/ime
javac -encoding UTF-8 -cp "$jar_file" -d "$work" "$src/NativeCore.java" "$src/CandidateEngine.java" \
  "$src/StrokeRecognizer.java" "$src/OchwRecognizer.java" tests/OchwRecognizerTest.java
java -Djava.library.path="$work" -cp "$work:$jar_file" biz.tujia.ime.OchwRecognizerTest \
  app/src/main/assets/ochwpro/model.onnx app/src/main/assets/ochwpro/characters.txt shared/bundle/Assets/handwriting/medians.tsv
