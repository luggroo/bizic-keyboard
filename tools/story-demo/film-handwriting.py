#!/usr/bin/env python3
"""Record two real auto-commits and their suggestions in a separate notes app."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

here=Path(__file__).resolve().parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('output',type=Path)
parser.add_argument('--adb',default=os.environ.get('ADB','adb'))
parser.add_argument('--render-only',action='store_true')
a=parser.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
if not a.render_only:
    subprocess.run([sys.executable,str(here/'record.py'),str(here/'continuous-handwriting.tsv'),str(out),'--reading-pace','--input','touch','--keyframes','--dry','--adb',a.adb],check=True)
assert (out/'saved-note.txt').read_text()=='波俐'
raw=sorted((out/'frames').glob('*.png'));tags=[p.stem.split('-',1)[1] for p in raw];seq=[]
def span(first,last,n):
    if first>=last:return
    assert n>=last-first
    seq.extend(raw[first+(last-first)*i//n] for i in range(n))
span(0,tags.index('begin0'),35)
for i in (0,1):
    begin=tags.index('begin'+str(i));auto=tags.index('auto'+str(i));committed=tags.index('commit'+str(i));after=tags.index('after'+str(i))
    ink=[j for j in range(begin,auto) if tags[j].startswith('ink')];assert len(ink)==(8 if i==0 else 9)
    span(begin,ink[0],10);span(ink[0],ink[-1]+1,60);seq.extend([raw[ink[-1]]]*31)
    span(auto,committed,10);span(committed,after+1,67)
span(tags.index('after1')+1,tags.index('saved'),25);seq.extend([raw[tags.index('saved')]]*75)
with tempfile.TemporaryDirectory(dir=out) as t:
    d=Path(t)
    for n,p in enumerate(seq):os.symlink(p,d/f'{n:05}.png')
    target=out/'continuous-handwriting.mp4'
    subprocess.run(['ffmpeg','-y','-v','error','-framerate','30','-i',str(d/'%05d.png'),'-c:v','libx264','-crf','16','-preset','veryfast','-threads','3','-pix_fmt','yuv420p','-movflags','+faststart',str(target)],check=True)
info=json.loads(subprocess.check_output(['ffprobe','-v','error','-count_frames','-show_streams','-of','json',target]));assert int(info['streams'][0]['nb_read_frames'])==len(seq)
print(target,len(seq)/30,'seconds;',len(raw),'native UI states; 波俐 verified after reopen',flush=True)
