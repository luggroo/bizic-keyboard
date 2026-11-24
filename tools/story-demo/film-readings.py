#!/usr/bin/env python3
"""Record four Jishou spelling choices in an Android editor and render their actual UI states."""

import argparse
import csv
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "jishou-readings.tsv"
FPS = 30
FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"


def display_frames(tag, position, choices, commit):
    if tag == "saved":
        return 66
    if tag == "opening":
        return 17
    if tag == "mode-options":
        return 20
    if position == choices:
        return 44
    if position == commit - 1:
        return 42
    if position == commit:
        return 28
    if choices < position < commit:
        return 12
    if tag in {"press", "draft"}:
        return 9
    if tag.startswith("after"):
        return 20
    return 8


def render(folder, rows):
    frames = sorted((folder / "frames").glob("*.png"))
    tags = [frame.stem.split("-", 1)[1] for frame in frames]
    expected = "".join(row["text"] + row["after"].replace(r"\n", "\n") for row in rows)
    if (folder / "saved-note.txt").read_text() != expected or "outcome=PASS" not in (folder / "run.log").read_text():
        raise ValueError("The four selected forms must match the note after saving and reopening.")
    starts = [0] + [tags.index(f"begin{i}") for i in range(1, len(rows))]
    chapter_ranges = zip(starts, starts[1:] + [len(frames)])
    big = ImageFont.truetype(FONT, 27, index=2)
    small = ImageFont.truetype(FONT, 17, index=2)
    target = folder / "jishou-readings-android.mp4"
    chapters = []
    playback = []

    with tempfile.TemporaryDirectory(dir=folder) as tmp:
        work = Path(tmp)
        for i, (row, (start, end)) in enumerate(zip(rows, chapter_ranges)):
            choice = tags.index(f"choices{i}", start, end)
            commit = tags.index(f"commit{i}", choice + 1, end)
            if tags[commit - 1] != "press":
                raise ValueError("The selected candidate must have a recorded touch before it is committed.")
            reading = row["keys"].split(":")[1]
            title = f"{reading}　输入「{row['text']}」"
            chapters.append((len(playback), title))
            for index in range(start, end):
                with Image.open(frames[index]) as original:
                    image = original.convert("RGB")
                canvas = Image.new("RGB", (image.width, image.height + 88), "#faf8f2")
                canvas.paste(image, (0, 88))
                pen = ImageDraw.Draw(canvas)
                pen.text((20, 7), "土家语输入法 · 安卓记事本", font=small, fill="#795345")
                pen.text((20, 36), title, font=big, fill="#362b25")
                pen.line((0, 87, image.width, 87), fill="#d6c9b9")
                path = work / f"k{index}.png"
                canvas.save(path)
                playback.extend([path] * display_frames(tags[index], index, choice, commit))

        for i, frame in enumerate(playback):
            (work / f"f{i:05}.png").symlink_to(frame)
        ends = [start for start, _ in chapters[1:]] + [len(playback)]
        metadata = work / "metadata.txt"
        metadata.write_text(";FFMETADATA1\ntitle=吉首文白输入 · 架和稼\n" + "".join(
            f"[CHAPTER]\nTIMEBASE=1/{FPS}\nSTART={start}\nEND={end}\ntitle={title}\n"
            for (start, title), end in zip(chapters, ends)
        ))
        subprocess.run([
            "ffmpeg", "-y", "-v", "error", "-framerate", str(FPS), "-i", str(work / "f%05d.png"),
            "-i", str(metadata), "-map", "0:v:0", "-map_chapters", "1", "-map_metadata", "1",
            "-c:v", "libx264", "-crf", "16", "-threads", "3", "-preset", "veryfast",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(target)
        ], check=True)

    check = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
        "-show_entries", "stream=nb_read_frames:chapter=start_time,end_time", "-of", "json", str(target)
    ]))
    if int(check["streams"][0]["nb_read_frames"]) != len(playback) or len(check["chapters"]) != len(chapters):
        raise ValueError("Encoded frames or chapter markers do not match the recording.")
    print(f"{target}  {len(playback) / FPS:.3f}s  {len(frames)} native states")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Capture directory for the note, UI states and MP4")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--render-only", action="store_true", help="Render an existing verified capture")
    args = parser.parse_args()
    folder = args.output.resolve()
    folder.mkdir(parents=True, exist_ok=True)
    if not args.render_only:
        subprocess.run([
            sys.executable, str(HERE / "record.py"), str(SCRIPT), str(folder), "--reading-pace",
            "--input", "touch", "--keyframes", "--dry", "--adb", args.adb
        ], check=True)
    with SCRIPT.open(encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream, delimiter="\t"))
    render(folder, rows)


if __name__ == "__main__":
    main()
