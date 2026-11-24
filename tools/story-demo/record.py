#!/usr/bin/env python3
"""Film the device's real screen. The driver pauses input during each screenrecord rollover."""
import argparse
import csv
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('script', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
parser.add_argument('--mode', default='汉字记音')
parser.add_argument('--chunk', type=int, default=18)
parser.add_argument('--input', choices=['hardware','touch'], default='hardware')
parser.add_argument('--fast-cards',action='store_true',help='Resolve ranks with the shared C++ core, then verify live candidate labels and committed text')
parser.add_argument('--reading-pace',action='store_true',help='Touch every letter slowly and hold every candidate and sentence for reading; encode at real time')
parser.add_argument('--dry',action='store_true',help='Run all input and saved-note checks without starting a screen recorder')
parser.add_argument('--style-demo', action='store_true')
parser.add_argument('--resume', action='store_true', help='Reopen the note saved at the last complete capture segment')
parser.add_argument('--capture-from', type=int, default=0, help='Replay prior input before capturing this segment')
args = parser.parse_args()
if args.reading_pace and (args.fast_cards or args.style_demo): raise ValueError('Reading pace uses the visual story flow')
package = 'biz.tujia.storynotes'
script_name = 'story.tsv'
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
stamp = 'notes-' + str(int(time.time()))
remote_dir = '/data/local/tmp/' + stamp

def adb(*op, **opts):
    return subprocess.run([args.adb, *map(str,op)], check=True, **opts)

def shell(*cmd, **opts):
    return adb('shell',shlex.join(map(str,cmd)),**opts)

def app(*cmd, **opts):
    return shell('run-as',package,*cmd,**opts)

def upload(data,name):
    app('sh','-c','cat > '+shlex.quote('files/'+name),input=data)

app('mkdir','-p','files')
app('sh','-c','rm -f files/record-go-* files/record-finalized files/verified-note.txt')
if not args.resume: app('rm','-f','files/story-checkpoint.txt')
if args.reading_pace: (out/'chapters.tsv').unlink(missing_ok=True)
if not args.resume and args.capture_from==0 and (out/'opening-timing.tsv').exists():
    (out/'opening-timing.tsv').unlink()
upload(args.script.read_bytes(),script_name)
if args.reading_pace:
    glyphs=[r.split('\t')[0][5:] for r in args.script.read_text().splitlines() if r.startswith('hand:')]
    if len(glyphs)!=1: raise ValueError('Choose a single drawn character for the two-sentence demonstration')
    medians=Path(__file__).resolve().parents[2]/'shared/bundle/Assets/handwriting/medians.tsv'
    drawing=next(r for r in medians.read_text().splitlines() if r.startswith(glyphs[0]+'\t'))
    upload((drawing+'\n').encode(),'stroke-demo.tsv')

if args.fast_cards:
    if args.mode!='汉字记音': raise ValueError('--fast-cards requires Chinese phonetic-character output')
    root=Path(__file__).resolve().parents[2]
    with tempfile.TemporaryDirectory() as temp:
        executable=Path(temp)/'story-ranks'
        subprocess.run([os.environ.get('CXX','c++'),'-std=c++17','-O2','-I'+str(root/'shared/src'),
                        str(Path(__file__).with_name('ranks.cpp')),str(root/'shared/src/lexicon.cpp'),
                        '-o',str(executable)],check=True)
        ranks=subprocess.check_output([str(executable),str(root/'shared/bundle/Assets/words.tsv'),str(args.script)])
    upload(ranks,'story-ranks.txt')
shell('mkdir','-p',remote_dir)

instrument = [args.adb,'shell','am','instrument','-w','-r','-e','script',script_name,
              '-e','mode',args.mode,'-e','chunk',str(args.chunk),'-e','record','0' if args.dry else '1','-e','input',args.input,
              '-e','resume',str(int(args.resume)),'-e','capture_from',str(args.capture_from)]
if args.fast_cards: instrument.extend(['-e','fast_cards','1'])
if args.reading_pace: instrument.extend(['-e','reading_pace','1'])
if args.style_demo:
    instrument.extend(['-e','style_demo','1'])
instrument.append(package+'/.Playback')
player = subprocess.Popen(instrument,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
pieces=[]
capture=None
num=None
segment_start=None

def start(n):
    name=f'part-{n:03d}.mp4'
    target=remote_dir+'/'+name
    command = 'echo $$ > '+remote_dir+'/pid; exec screenrecord --bit-rate 2000000 --time-limit 178 '+target
    proc=subprocess.Popen([args.adb,'shell',command],stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    time.sleep(.7)
    if proc.poll() is not None: raise RuntimeError('screenrecord could not start: '+proc.stderr.read().decode())
    app('touch','files/record-go-'+str(n))
    return proc,n

def stop(proc,n):
    if proc.poll() is not None:
        raise RuntimeError('Encoder ended before the note driver finished segment '+str(n)+': '+proc.stderr.read().decode())
    shell('sh','-c','kill -INT "$(cat '+remote_dir+'/pid)"')
    try: proc.wait(20)
    except subprocess.TimeoutExpired:
        raise RuntimeError('screenrecord failed to finalize its video')
    name=f'part-{n:03d}.mp4'
    adb('pull',remote_dir+'/'+name,out/name,stdout=subprocess.DEVNULL)
    pieces.append(out/name)

ok=False
try:
    with (out/'run.log').open('w',encoding='utf-8') as log:
        for line in player.stdout:
            line=line.rstrip('\r\n')
            print(line,flush=True); print(line,file=log,flush=True)
            if line.startswith('INSTRUMENTATION_STATUS: record_segment='):
                if capture: stop(capture,num); capture=None
                segment_start=time.monotonic()
                capture,num=start(int(line.rsplit('=',1)[1]))
            if args.reading_pace and segment_start is not None and line.startswith(('INSTRUMENTATION_STATUS: chapter=', 'INSTRUMENTATION_STATUS: selected=')):
                with (out/'chapters.tsv').open('a',encoding='utf-8') as t:
                    print(round(time.monotonic()-segment_start,2),line.split('=',1)[1],sep='\t',file=t)
            if line=='INSTRUMENTATION_STATUS: record_done=1':
                if capture:
                    stop(capture,num)
                    capture=None
                app('touch','files/record-finalized')
            if num==0 and (line.startswith('INSTRUMENTATION_STATUS: first_keys=') or line.startswith('INSTRUMENTATION_STATUS: words=1/')):
                kind='typing' if 'first_keys=' in line else 'committed'
                with (out/'opening-timing.tsv').open('a',encoding='utf-8') as times:
                    print(kind,round(time.monotonic()-segment_start,3),sep='\t',file=times)
            if line=='INSTRUMENTATION_RESULT: outcome=PASS':
                ok=True
    if capture: stop(capture,num); capture=None
    if player.wait()!=0 or not ok: raise RuntimeError('Playback verification failed; see run.log')
finally:
    if capture and capture.poll() is None:
        shell('sh','-c','kill -INT "$(cat '+remote_dir+'/pid)"')
        capture.wait(20)
    if player.poll() is None: player.terminate()

saved=adb('exec-out','run-as',package,'cat','files/verified-note.txt',stdout=subprocess.PIPE).stdout
(out/'saved-note.txt').write_bytes(saved)
if args.reading_pace:
    events=adb('exec-out','run-as',package,'cat','files/film-events.tsv',stdout=subprocess.PIPE).stdout
    (out/'film-events.tsv').write_bytes(events)
rows=(line for line in args.script.read_text(encoding='utf-8').splitlines() if line and not line.startswith('#') and not line.startswith('keys\ttext\t'))
expected=''.join(row[1]+row[3].replace(r'\n','\n') for row in csv.reader(rows,delimiter='\t',quoting=csv.QUOTE_NONE))
if saved.decode('utf-8') != expected: raise RuntimeError('Saved note bytes differ from the requested story')
shell('rm','-r',remote_dir)
if not args.dry:
    command=[sys.executable,str(Path(__file__).with_name('encode.py')),str(out)]
    if args.reading_pace: command.append('--real-time')
    subprocess.run(command,check=True)
