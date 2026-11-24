#!/usr/bin/env python3
"""Package the interactive demonstration as one offline HTML file."""
import argparse
import re
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('site', type=Path)
parser.add_argument('artifacts', type=Path)
parser.add_argument('--version', required=True)
parser.add_argument('--reference-zip', type=Path, help=argparse.SUPPRESS)
args = parser.parse_args()

def inline(match):
    source = (args.site / match.group(1)).read_text(encoding='utf-8')
    return '<script>' + source.replace('</script', '<\\/script') + '</script>'

html = re.sub(r'<script src="([^"]+)"></script>', inline,
              (args.site / 'index.html').read_text(encoding='utf-8'))
licenses = list((root / 'shared/bundle/Assets/licenses').glob('*'))
licenses += list((root / 'tools/web-demo/licenses').glob('*.txt'))
licenses += [root / 'shared/bundle/Assets/handwriting' / f for f in ('ARPHICPL.TXT', 'NOTICE.txt')]
licenses += [root / 'app/src/main/assets/ismantic' / f for f in ('LICENSE.txt', 'NOTICE.txt')]
licenses += [root / 'tools/web-demo/licenses/onnxruntime.txt']
license_text = '\n\n'.join(f'{p.name}\n{p.read_text(encoding="utf-8")}' for p in licenses if p.is_file())
html += '\n<!-- Open-source licenses and data notices\n' + license_text.replace('--', '- -') + '\n-->\n'
args.artifacts.mkdir(parents=True, exist_ok=True)
(args.artifacts / 'index.html').write_text(html, encoding='utf-8')
archive = args.artifacts / 'shange-demo.zip'
with ZipFile(archive, 'w', ZIP_DEFLATED, compresslevel=7) as out:
    out.writestr('index.html', html)
with ZipFile(archive) as verify:
    assert verify.namelist() == ['index.html'] and verify.testzip() is None
    assert not re.search(r'<script src=', verify.read('index.html').decode())
print(archive, archive.stat().st_size)
