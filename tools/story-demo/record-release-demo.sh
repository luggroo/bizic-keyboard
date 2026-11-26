#!/usr/bin/env bash
set -euo pipefail
scene="${1:?Choose a recording scene}"
work=build/demo
out="$work/$scene"
mkdir -p "$out" "$work/movies"
adb shell wm size 480x800
adb shell wm density 160
adb install -r "$work/keyboard.apk"
adb install -r "$work/story-notes.apk"
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell ime enable biz.tujia.ime/.TujiaIme
adb shell ime set biz.tujia.ime/.TujiaIme
case "$scene" in
  song) python3 tools/story-demo/film-song.py "$out"
        cp "$out/shange-half-speed.mp4" "$work/movies/shange-demo.mp4" ;;
  handwriting) python3 tools/story-demo/film-handwriting.py "$out"
        cp "$out/continuous-handwriting.mp4" "$work/movies/" ;;
  readings) python3 tools/story-demo/film-readings.py "$out"
        cp "$out/jishou-readings-android.mp4" "$work/movies/" ;;
  prediction) python3 tools/story-demo/record.py tools/story-demo/prediction-spelling.tsv "$out" --reading-pace --input touch --keyframes --dry
        python3 tools/story-demo/join-prediction.py --standalone /dev/null "$out" "$work/movies/one-tap.mp4" ;;
  tone) bash tools/story-demo/record-tone.sh "$work/keyboard.apk" "$work/device-test.apk" "$work/movies/tone-demo.mp4" ;;
  *) exit 2 ;;
esac
for film in "$work"/movies/*.mp4; do ffmpeg -v error -xerror -i "$film" -f null -; done
