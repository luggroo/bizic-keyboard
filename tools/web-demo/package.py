#!/usr/bin/env python3
"""Produce the offline HTML and ZIP directly from the current web UI and the shared engine."""
import argparse
from pathlib import Path
import re
from zipfile import ZipFile, ZIP_DEFLATED

root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('site',type=Path,help='Web project dist directory')
parser.add_argument('artifacts',type=Path)
parser.add_argument('--version',required=True)
parser.add_argument('--reference-zip',type=Path,help='Prior offline release supplying the unchanged reference appendix')
args=parser.parse_args()
site=args.site;art=args.artifacts;version=args.version
source=f'https://github.com/luggroo/bizic-keyboard/archive/refs/tags/v{version}.zip'
release=f'https://github.com/luggroo/bizic-keyboard/releases/download/v{version}/'
def inline(page):
    def script(m):
        js=(site/m[1]).read_text()
        if m[1]=='syllables.js':
            fetch="fetch('reference-data.json').then(r=>{if(!r.ok)throw Error();return r.json()})"
            assert fetch in js
            js=js.replace(fetch,'Promise.resolve('+ (site/'reference-data.json').read_text() +')')
        return '<script>'+js.replace('</script','<\\/script')+'</script>'
    return re.sub(r'<script src="([^"]+)"></script>',script,page)

def appendix(name):
    path=site/name
    if path.is_file():return path.read_bytes()
    if args.reference_zip:
        with ZipFile(args.reference_zip) as archive:return archive.read(name)
    raise FileNotFoundError(f'{name}: supply the full site directory or --reference-zip')

def page(name):
    value=appendix(name).decode().replace('href="./"', 'href="index.html"').replace('href="./?', 'href="index.html?')
    if name=='android.html':
        value=re.sub(r'安卓试用版 [0-9.]+','安卓试用版 '+version,value)
        value=re.sub(r'https://github.com/luggroo/bizic-keyboard/archive/refs/tags/v[0-9.]+\.zip',source,value)
        value=value.replace('"android-keyboard.mp4"','"'+release+'shange-demo.mp4"').replace('"continuous-handwriting.mp4"','"'+release+'continuous-handwriting.mp4"')
    return inline(value)

main=page('index.html')
art.mkdir(parents=True,exist_ok=True)
(art/'tujia-notes-demo.html').write_text(main)
name='shange-demo.zip'; tmp=art/(name+'.tmp')
with ZipFile(tmp,'w',ZIP_DEFLATED,compresslevel=7) as out:
    out.writestr('index.html',main)
    for f in ('android.html','orthography.html','homophones.html','shizi-tiaozao.html','syllables.html'):out.writestr(f,page(f))
    for f in ('shizi-tiaozao.docx','north-syllables.xlsx','orthography-defaults.tsv','orthography.xlsx','four-candidates.png','large-type.png','keyboard-font-100.png','keyboard-font-160.png','keyboard-sense-go.png','joined-han.png','noon-corrected.png'):out.writestr(f,appendix(f))
    for f in sorted((root/'shared/bundle/Assets/licenses').iterdir()):out.write(f,'licenses/'+f.name)
    for f in sorted((root/'tools/web-demo/licenses').glob('*.txt')):out.write(f,'licenses/wasm/'+f.name)
    for f in ('ARPHICPL.TXT','NOTICE.txt'):out.write(root/'shared/bundle/Assets/handwriting'/f,'licenses/handwriting/'+f)
with ZipFile(tmp) as z:
    assert z.testzip() is None
    assert not re.search(r'<script src=',z.read('index.html').decode())
tmp.replace(art/name)
print(art/name,(art/name).stat().st_size)
