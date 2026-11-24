#!/usr/bin/env python3
"""Assemble consecutive screen recordings; preserve the opening and the saved-note review."""
from pathlib import Path
import subprocess, sys
directory=Path(sys.argv[1]).resolve()
parts=sorted(directory.glob('part-*.mp4'))
if not parts: raise ValueError('No captured screen segments')
def run(cmd): subprocess.run(cmd,check=True)
def listing(name, paths):
    p=directory/name
    p.write_text(''.join("file '"+f.name+"'\n" for f in paths),encoding='utf-8')
    return p
ff=['ffmpeg','-y','-hide_banner','-loglevel','error']
src=listing('original.ffconcat',parts)
run(ff+['-f','concat','-safe','1','-i',str(src),'-c','copy','-movflags','+faststart',str(directory/'full.mp4')])
if len(parts)==1 or '--real-time' in sys.argv[2:]:
    import shutil
    shutil.copyfile(directory/'full.mp4',directory/'preview.mp4')
else:
    quick=[]
    for i,p in enumerate(parts):
        t=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',str(p)],text=True).strip())
        if i==0:
            # On a cold system the editor appears before the IME is loaded.
            # Speed the initial wait, then give the first candidate time to read.
            timing=directory/'opening-timing.tsv'
            values=dict(line.split('\t') for line in timing.read_text().splitlines()) if timing.exists() else {}
            begin=max(0,float(values.get('typing',16))-1)
            end=max(begin+1,float(values.get('committed',23))+.7)
            expr=f'if(lt(T,{begin}),T/6,if(lt(T,{end}),{begin}/6+T-{begin},{begin}/6+{end-begin}+(T-{end})/6))'
        elif i==len(parts)-1:
            edge=max(0,t-10)
            expr=f'if(lt(T,{edge}),T/6,{edge}/6+T-{edge})'
        else: expr='T/6'
        q=directory/f'preview-{i:03d}.mp4'
        run(ff+['-i',str(p),'-vf',"settb=AVTB,setpts='"+expr+"/TB',fps=24,format=yuv420p",'-an','-c:v','libx264','-threads','2','-preset','veryfast','-crf','21',str(q)])
        quick.append(q)
    src=listing('preview.ffconcat',quick)
    run(ff+['-f','concat','-safe','1','-i',str(src),'-c','copy','-movflags','+faststart',str(directory/'preview.mp4')])
for f in ['full.mp4','preview.mp4']:
    run(['ffprobe','-v','error','-show_entries','format=duration,size','-of','csv=p=0',str(directory/f)])
