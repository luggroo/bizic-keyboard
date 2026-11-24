#!/usr/bin/env python3
"""Record the first four lines of 山歌两首 in a real Android notes editor and render at half demonstration pace."""
import argparse
import csv
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
SOURCE='https://tujia.biz/?id=xu-tujia-annotated-2-13'
TRANSLATIONS=['我们都是土家族，','唱山歌都用土家语，','唱一句土家山歌，','唱得不好别怪我。']
FPS=30

def load(name):
    with (HERE/name).open(encoding='utf-8') as stream:
        return list(csv.DictReader(stream,delimiter='\t'))

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('output', type=Path)
parser.add_argument('--adb', default=os.environ.get('ADB','adb'))
parser.add_argument('--render-only',action='store_true',help='Render a previously verified capture in this directory')
a=parser.parse_args(); out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
rows=load('shange-spelling.tsv'); aligned=load('shange.tsv'); assert len(rows)==len(aligned)
dictionary=HERE.parents[1]/'shared/bundle/Assets/words.tsv'
with dictionary.open(encoding='utf-8') as stream:
    loan_ids={entry['id'] for entry in csv.DictReader(stream,delimiter='\t')
              if entry.get('presentation')=='plainHanLoan'}
script=out/'song-input.tsv'; syl=[]; line=0; source_lines=[]; words=[]; modes=[]
with script.open('w',encoding='utf-8',newline='') as stream:
    w=csv.writer(stream,delimiter='\t',lineterminator='\n'); w.writerow(['keys','text','meaning','after'])
    for i,(entry,units) in enumerate(zip(rows,aligned)):
        mode='spell' if line==0 else 'hand' if i>=len(rows)-2 else 'han'
        modes.append(mode)
        chosen=entry['diacritic'] if mode=='spell' else entry['han']
        after=entry['after'] or (' ' if mode=='spell' else '')
        assert entry['han']==units['text'] and entry['gloss']==units['meaning']
        syl.append(len(units['hanUnits'].split('|')));source_lines.append(line);words.append(chosen+after.replace(r'\n','\n'))
        input_key=chosen if mode=='hand' else entry['plain']
        meaning=('@written:'+entry['gloss']) if mode=='han' and entry['id'] in loan_ids else entry['gloss']
        if mode=='hand': meaning=''
        w.writerow([mode+':'+input_key,chosen,meaning,after]);
        if r'\n' in after:line+=1
assert line==3 and words[-1].endswith('。')
if not a.render_only:
    subprocess.run([sys.executable,str(HERE/'record.py'),str(script),str(out),'--reading-pace','--input','touch','--keyframes','--dry','--chunk','4','--adb',a.adb],check=True)
assert (out/'saved-note.txt').read_text()==''.join(words)
frames=sorted((out/'frames').glob('*.png')); tags=[p.stem.split('-',1)[1] for p in frames]
items=[]; chapter_starts=[]
def play(start,end,count):
    if start>=end:return
    if count<end-start: raise ValueError('Insufficient playback frames for all actual keyboard states')
    for j in range(count):items.append(start+(end-start)*j//count)

def sample(start,end,minimum=0):
    if start<end:
        offset=len(items)
        play(start,end,max(minimum,3*(end-start)))
        for j in range(len(items)-1,offset-1,-1):
            if tags[items[j]]=='mode-options' and (j==offset or items[j-1]!=items[j]):
                items[j:j]=[items[j]]*21

sample(0,tags.index('begin0'),24)
for i, syllables in enumerate(syl):
    begin=tags.index('begin'+str(i)); commit=tags.index('commit'+str(i)); after=tags.index('after'+str(i))
    if i==0 or source_lines[i-1]!=source_lines[i]: chapter_starts.append(0 if i==0 else len(items))
    if modes[i]=='hand':
        first=next(j for j in range(begin,commit) if tags[j].startswith('ink'))
        received=tags.index('auto'+str(i));sample(begin,first,8);play(first,received,60*syllables);sample(received,commit,24)
    else:
        choice=tags.index('choices'+str(i));first=tags.index('press',begin,choice)
        sample(begin,first,3)
        # Three syllables per four seconds, plus independent time to read the gloss.
        play(first,choice,40*syllables)
        items.extend([choice]*47)
        play(choice+1,commit,max(8,commit-choice-1))
    sample(commit,after,8);next_begin=tags.index('begin'+str(i+1)) if i+1<len(syl) else tags.index('saved')
    line_end=i+1==len(syl) or source_lines[i+1]!=source_lines[i]
    sample(after,next_begin,23 if line_end else 5)
items.extend([tags.index('saved')]*90)
fontpath='/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
font=ImageFont.truetype(fontpath,27,index=2);small=ImageFont.truetype(fontpath,17,index=2)
with tempfile.TemporaryDirectory(dir=out) as tmp:
    work=Path(tmp); cache={};chapter=0
    for idx,n in enumerate(items):
        if chapter+1<len(chapter_starts) and idx>=chapter_starts[chapter+1]:chapter+=1
        key=(chapter,n)
        if key not in cache:
            screen=Image.open(frames[n]).convert('RGB'); image=Image.new('RGB',(screen.width,screen.height+108),'#fbf8ef');image.paste(screen,(0,108)); pen=ImageDraw.Draw(image)
            pen.text((22,11),'毕兹学堂 · 山歌两首　'+str(chapter+1)+'/4',font=small,fill='#8c442d');pen.text((22,45),TRANSLATIONS[chapter],font=font,fill='#292a24');pen.line((0,107,screen.width,107),fill='#c6bcaf')
            cache[key]=work/f'view-{chapter}-{n}.png';image.save(cache[key])
        os.symlink(cache[key],work/f'{idx:05}.png')
    chapter_ends=chapter_starts[1:]+[len(items)]; meta=work/'metadata.txt'
    meta.write_text(';FFMETADATA1\ntitle=山歌两首 · 第一首\ncomment='+SOURCE+'\n'+''.join(f'[CHAPTER]\nTIMEBASE=1/{FPS}\nSTART={s}\nEND={e}\ntitle={title}\n' for s,e,title in zip(chapter_starts,chapter_ends,TRANSLATIONS)))
    target=out/'shange-half-speed.mp4';subprocess.run(['ffmpeg','-y','-hide_banner','-loglevel','error','-framerate',str(FPS),'-i',str(work/'%05d.png'),'-i',str(meta),'-map','0:v:0','-map_chapters','1','-map_metadata','1','-c:v','libx264','-crf','16','-threads','3','-preset','veryfast','-pix_fmt','yuv420p','-movflags','+faststart',str(target)],check=True)
check=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-count_frames','-show_streams','-show_chapters','-of','json',target]));assert int(check['streams'][0]['nb_read_frames'])==len(items) and len(check['chapters'])==4
print(target,len(items)/FPS,'seconds,',len(syl),'commits,',sum(syl),'syllables,',len(frames),'native UI states',flush=True)
