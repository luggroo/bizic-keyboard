#!/usr/bin/env python3
"""Transfer a verified, previously typed note checkpoint and its completed recordings.

Both adb arguments are wrappers selecting actual, distinct devices. The destination
must already have the debug notes app. Existing unrelated notes are preserved.
"""
import argparse
import csv
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import time
from xml.etree import ElementTree as ET

p=argparse.ArgumentParser()
p.add_argument('source_adb')
p.add_argument('destination_adb')
p.add_argument('script',type=Path)
p.add_argument('source_video',type=Path)
p.add_argument('destination_video',type=Path)
p.add_argument('--chunk',type=int,required=True)
a=p.parse_args()
package='biz.tujia.storynotes'

def adb(device,*args,**kwargs):
    return subprocess.run([device,*args],**kwargs)

def get(device,name,required=True):
    r=adb(device,'exec-out','run-as',package,'cat',name,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=required)
    return r.stdout if r.returncode==0 and not r.stdout.startswith(b'cat:') else None

def put(device,name,data):
    path=shlex.quote(name)
    cmd='run-as '+package+' sh -c '+shlex.quote('cat > '+path+'.transfer && mv '+path+'.transfer '+path)
    adb(device,'shell',cmd,input=data,check=True)

serials=[adb(d,'get-serialno',check=True,stdout=subprocess.PIPE).stdout.strip() for d in [a.source_adb,a.destination_adb]]
if serials[0]==serials[1]: raise RuntimeError('Choose two distinct Android devices')
with a.script.open(encoding='utf-8') as f:
    rows=list(csv.DictReader(f,delimiter='\t'))

for attempt in range(5):
    state=get(a.source_adb,'files/story-checkpoint.txt')
    num_s,id_s=state.decode().splitlines()
    n=int(num_s)
    if not 0<n<len(rows) or n%a.chunk: raise RuntimeError('Invalid source checkpoint')
    words=ET.fromstring(get(a.source_adb,'shared_prefs/notes.xml'))
    matched=[w for w in words if w.attrib.get('name')==id_s]
    actual=matched[0].text if len(matched)==1 else None
    expected=''.join(r['text']+r['after'].replace(r'\n','\n') for r in rows[:n])
    if state==get(a.source_adb,'files/story-checkpoint.txt') and expected==actual: break
    time.sleep(.3)
else: raise RuntimeError('Could not obtain a matching stable recording checkpoint')

# Check completed media before importing the actual saved note from the source device.
finish=time.monotonic()+30
required=[a.source_video/f'part-{i:03d}.mp4' for i in range(n//a.chunk)]
while not all(path.exists() and path.stat().st_size>256 for path in required):
    if time.monotonic()>finish: raise RuntimeError('Missing completed video segment')
    time.sleep(.25)
for path in required:
    subprocess.run(['ffprobe','-v','error','-select_streams','v:0','-show_entries','stream=codec_name','-of','csv=p=0',str(path)],check=True,stdout=subprocess.DEVNULL)

adb(a.destination_adb,'shell','am','force-stop',package,check=True)
adb(a.destination_adb,'shell','run-as',package,'mkdir','-p','shared_prefs','files',check=True)
old=get(a.destination_adb,'shared_prefs/notes.xml',False)
doc=ET.fromstring(old) if old else ET.Element('map')
prior=[w for w in doc if w.attrib.get('name')==id_s]
if prior and prior[0].text!=actual:
    raise RuntimeError('A different note already uses this ID on the destination')
if not prior: ET.SubElement(doc,'string',name=id_s).text=actual
for style in words:
    if style.attrib.get('name')=='style:'+id_s:
        for stale in list(doc):
            if stale.attrib.get('name')=='style:'+id_s: doc.remove(stale)
        ET.SubElement(doc,'string',name='style:'+id_s).text=style.text
put(a.destination_adb,'shared_prefs/notes.xml',ET.tostring(doc,encoding='utf-8',xml_declaration=True))
put(a.destination_adb,'files/story-checkpoint.txt',state)
a.destination_video.mkdir(exist_ok=True,parents=True)
for path in [*required,a.source_video/'opening-timing.tsv']:
    if not path.is_file(): continue
    dest=a.destination_video/path.name
    if dest.exists():
        if path.read_bytes()!=dest.read_bytes(): raise RuntimeError('Different recording already exists at '+str(dest))
    else:
        try: os.link(path,dest)
        except OSError: shutil.copy2(path,dest)
print('Transferred independently verified saved note:',n,'actual selections,',n//a.chunk,'completed recordings.',flush=True)
