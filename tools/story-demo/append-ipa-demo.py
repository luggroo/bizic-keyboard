"""Append verified native IPA input states to the vowel-gesture screen recording."""
import argparse
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory

from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('recording', type=Path)
parser.add_argument('frames', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('--start-seconds', type=float, default=0)
args = parser.parse_args()
states = [
    ('main', '切换到音标键盘', 2),
    ('m', '输入 m', 1.2),
    ('ma', '输入 a，查看 ma 的候选', 2.5),
    ('detail', '长按候选查看「奶」的释义', 3),
    ('committed', '点「输入这个候选」，写入 mã²¹', 3),
]
font = ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc', 24, index=2)
with TemporaryDirectory() as directory:
    tmp = Path(directory)
    with Image.open(args.frames / 'ipa-ux-main.png') as first:
        width, height = first.size
    args.output.parent.mkdir(parents=True, exist_ok=True)
    ff = ['ffmpeg', '-y', '-v', 'error']
    encode = ['-c:v', 'libx264', '-threads', '3', '-preset', 'veryfast', '-pix_fmt', 'yuv420p', '-an']
    subprocess.run([*ff, '-ss', str(args.start_seconds), '-i', args.recording,
                    '-vf', f'fps=30,scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,pad=iw:ih+60:0:60:color=0xfbf8ef,drawtext=fontfile=/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc:text=长按元音并滑向声调:x=14:y=12:fontsize=24:fontcolor=0x362b25,setsar=1',
                    *encode, tmp/'tone.mp4'], check=True)
    sequence = []
    for name, caption, duration in states:
        with Image.open(args.frames/f'ipa-ux-{name}.png') as screen:
            frame = Image.new('RGB', (width, height+60), '#fbf8ef')
            frame.paste(screen, (0,60))
            ImageDraw.Draw(frame).text((14,12), caption, font=font, fill='#362b25')
            frame.save(tmp/f'{name}.png')
        sequence.extend([f"file '{name}.png'", f'duration {duration}'])
    sequence.append("file 'committed.png'")
    (tmp/'frames.txt').write_text('\n'.join(sequence)+'\n')
    subprocess.run([*ff,'-f','concat','-safe','0','-i',tmp/'frames.txt','-vf','fps=30,setsar=1',*encode,tmp/'ipa.mp4'],check=True)
    (tmp/'clips.txt').write_text("file 'tone.mp4'\nfile 'ipa.mp4'\n")
    subprocess.run([*ff,'-f','concat','-safe','0','-i',tmp/'clips.txt','-c','copy','-movflags','+faststart',args.output],check=True)
