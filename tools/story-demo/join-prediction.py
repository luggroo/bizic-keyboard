#!/usr/bin/env python3
"""Append a fresh native Android free-input chapter to the four verified song chapters."""
import argparse,json,os,shutil,subprocess,tempfile
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont

parser=argparse.ArgumentParser()
parser.add_argument('song',type=Path)
parser.add_argument('prediction',type=Path)
parser.add_argument('movie',type=Path)
parser.add_argument('--standalone',action='store_true',help='Render just the prediction chapter')
parser.add_argument('--short',type=Path,help='Filename of standalone native chapter (defaults to one-tap.mp4)')
args=parser.parse_args()
song=args.song.resolve();capture=args.prediction.resolve();target=args.movie.resolve()
assert (capture/'saved-note.txt').read_text()=='bízīkǎ mǒng gǎ。\n秘兹佧萌夹。'
files=sorted((capture/'frames').glob('*.png'));tags=[p.stem.split('-',1)[1] for p in files]
sequence=[]
def show(label,start,end,length):
    if start<end:
        n=max(length,end-start)
        previous=None
        for i in range(n):
            frame=start+(end-start)*i//n
            if frame!=previous and tags[frame]=='mode-options':
                sequence.extend([(label,frame)]*21)
            sequence.append((label,frame))
            previous=frame
show('试写一段拼音',0,tags.index('begin0'),24)
for i in range(6):
    label=['拼出 bízīkǎ','点选 mǒng','接着点选 gǎ','切换到汉字记音','点选「萌」','输入 ga，点选「夹」'][i]
    begin=tags.index('begin'+str(i));choice=tags.index('choices'+str(i));commit=tags.index('commit'+str(i));after=tags.index('after'+str(i))
    show(label,begin,choice,90 if i in (0,3) else 40 if i==5 else 14)
    sequence.extend([(label,choice)]*49)
    show(label,choice+1,commit,10)
    show(label,commit,after,37)
    end=tags.index('begin'+str(i+1)) if i<5 else tags.index('saved')
    show(label,after,end,15)
sequence.extend([('保存后的试写正文',tags.index('saved'))]*65)
fontfile='/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
small=ImageFont.truetype(fontfile,17,index=2);large=ImageFont.truetype(fontfile,27,index=2)
def probe(p):
    return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_chapters','-of','json',p]))

with tempfile.TemporaryDirectory() as temp:
    d=Path(temp);cache={}
    for i,(label,n) in enumerate(sequence):
        key=(label,n)
        if key not in cache:
            raw=Image.open(files[n]).convert('RGB');frame=Image.new('RGB',(480,908),'#fbf8ef');frame.paste(raw,(0,108))
            draw=ImageDraw.Draw(frame);draw.text((22,11),'安卓记事本 · 自由输入',font=small,fill='#8c442d')
            draw.text((22,45),label,font=large,fill='#292a24');draw.line((0,107,480,107),fill='#c6bcaf')
            mapped=d/f'real-{len(cache)}.png';frame.save(mapped);cache[key]=mapped
        os.symlink(cache[key],d/f'{i:05}.png')
    second=d/'free.mp4'
    subprocess.run(['ffmpeg','-y','-v','error','-framerate','30','-i',str(d/'%05d.png'),'-c:v','libx264','-crf','16','-preset','veryfast','-threads','3','-pix_fmt','yuv420p',second],check=True)
    if args.standalone:
        shutil.copyfile(second,target)
        raise SystemExit(0)
    meta=probe(song);n_start=int(meta['streams'][0]['nb_frames']);assert len(meta['chapters'])==4
    shutil.copyfile(second,args.short or target.with_name('one-tap.mp4'))
    metadata=d/'chapters.ffmeta'
    chapters=[(round(float(ch['start_time'])*30),round(float(ch['end_time'])*30),ch['tags']['title']) for ch in meta['chapters']]
    chapters.append((n_start,n_start+len(sequence),'自由输入：连续点选音节'))
    metadata.write_text(';FFMETADATA1\ntitle=山歌两首与自由输入 · 安卓记事本\n'+''.join(f'[CHAPTER]\nTIMEBASE=1/30\nSTART={a}\nEND={b}\ntitle={title}\n' for a,b,title in chapters))
    sources=d/'sources.txt';sources.write_text(f"file '{song}'\nfile '{second}'\n")
    subprocess.run(['ffmpeg','-y','-v','error','-f','concat','-safe','0','-i',sources,'-i',metadata,'-map','0:v:0','-map_chapters','1','-map_metadata','1','-c:v','copy','-movflags','+faststart',target],check=True)
out=probe(target)
assert len(out['chapters'])==5
assert int(out['streams'][0]['nb_frames'])==n_start+len(sequence)
print(target,out['streams'][0]['duration'],len(sequence),'free-input frames')
