#!/usr/bin/env bash
# Exercise both the portable core and the Java entry points used by the keyboard.
set -euo pipefail
cd "$(dirname "$0")/.."
suite="${1:-all}"
case "$suite" in all|words|strokes) ;; *) echo 'Usage: test-core.sh [all|words|strokes]' >&2; exit 2;; esac
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cxx="${CXX:-c++}"
case "$(uname)" in
    Darwin) jni_platform=darwin; extension=dylib; linker=-dynamiclib
            : "${JAVA_HOME:=$(/usr/libexec/java_home)}" ;;
    Linux)  jni_platform=linux; extension=so; linker=-shared
            if [[ -z "${JAVA_HOME:-}" ]]; then
                jdk_compiler="$(command -v javac)"
                JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$jdk_compiler")")")"
            fi ;;
    *) echo 'Use Linux or macOS to run these host JNI checks.' >&2; exit 2 ;;
esac
jni=app/src/main/cpp/jni.cpp
core=(shared/src/lexicon.cpp shared/src/strokes.cpp shared/src/edit.cpp)
"$cxx" -std=c++17 -O2 -fPIC "$linker" -Ishared/src \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/$jni_platform" \
    "$jni" "${core[@]}" -o "$work/libtujia_core.$extension"
src=app/src/main/java/biz/tujia/ime
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$work" \
    "$src/NativeCore.java" "$src/CandidateEngine.java" "$src/HanChoice.java" "$src/HanUnitBrackets.java" "$src/StrokeRecognizer.java" "$src/IpaKeyboard.java" \
    tests/CandidateEngineTest.java tests/HanChoiceTest.java tests/HanUnitStyleTest.java tests/StrokeRecognizerTest.java tests/IpaKeyboardTest.java
java=("$JAVA_HOME/bin/java" -Xcheck:jni "-Djava.library.path=$work" -cp "$work")
if [[ "$suite" != strokes ]]; then
    "$cxx" -std=c++17 -O2 -Ishared/src shared/src/lexicon.cpp shared/tests/lexicon_test.cpp -o "$work/words"
    "$work/words" shared/bundle/Assets/words.tsv
    "${java[@]}" CandidateEngineTest shared/bundle/Assets/words.tsv
    "${java[@]}" biz.tujia.ime.HanChoiceTest shared/bundle/Assets/words.tsv
    "${java[@]}" biz.tujia.ime.HanUnitStyleTest
    "${java[@]}" biz.tujia.ime.IpaKeyboardTest app/src/main/assets/ipa-completions.tsv
fi
if [[ "$suite" != words ]]; then
    "$cxx" -std=c++17 -O2 -Ishared/src shared/src/strokes.cpp shared/tests/strokes_test.cpp -o "$work/strokes"
    "$work/strokes" shared/bundle/Assets/handwriting/medians.tsv
    "${java[@]}" biz.tujia.ime.StrokeRecognizerTest shared/bundle/Assets/handwriting/medians.tsv
fi
