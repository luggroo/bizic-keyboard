"""Build offline glyph templates for Tujia spellings/variants and Mandarin singletons.

Usage: python3 tools/import_handwriting.py shared/bundle/Assets/words.tsv /path/to/hanzi-writer-data-2.0.1.tgz
Upstream: https://registry.npmjs.org/hanzi-writer-data/-/hanzi-writer-data-2.0.1.tgz
"""
import base64
import csv
import hashlib
import json
from pathlib import Path
import sys
import tarfile
import unicodedata

OUT = Path(__file__).resolve().parents[1] / 'shared/bundle/Assets/handwriting'
INTEGRITY = 'nbQwM+MaryGoq7pBMIZLCd3lFq03nXuJuwku1+6UbjL58uU+9OULVcMkoNvNuJSoIV7f1bbPRfD4D/LQa5S7qg=='

def main(dictionary, archive_path):
    if base64.b64encode(hashlib.sha512(archive_path.read_bytes()).digest()).decode() != INTEGRITY:
        raise ValueError('Unexpected hanzi-writer-data archive (required version 2.0.1)')
    native, references, grammar, mandarin = set(), set(), set(), set()
    classes = {'tujia': native, 'phrase': native, 'syllable': references, 'grammar': grammar}
    with dictionary.open(encoding='utf-8', newline='') as src:
        for row in csv.DictReader(src, delimiter='\t'):
            if row['kind'] == 'zh':
                if len(row['recommended']) == 1:
                    if is_han(row['recommended']):
                        mandarin.add(row['recommended'])
            else:
                target = classes[row['kind']]
                for key in ('recommended', 'aliases', 'variants', 'hanUnits'):
                    target.update(ch for ch in row.get(key, '') if is_han(ch))
    chars = native | references | grammar | mandarin
    OUT.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive_path, mode='r|gz') as archive:
        templates = {}
        license_text = None
        for member in archive:
            if member.name == 'package/ARPHICPL.TXT':
                license_text = archive.extractfile(member).read()
                continue
            if not member.name.startswith('package/') or not member.name.endswith('.json'):
                continue
            char = member.name[8:-5]
            if char in chars:
                strokes = json.load(archive.extractfile(member))['medians']
                templates[char] = ';'.join(' '.join(f'{x},{900-y}' for x, y in stroke) for stroke in strokes)
        if license_text is None:
            raise ValueError('Missing handwriting license')
        missing = chars - templates.keys()
        lines = [
            '# Tujia IME offline stroke templates, 2026-11-24.',
            '# Modified from Hanzi Writer Data 2.0.1 / Make Me a Hanzi / Arphic Technology.',
            '# Changes: selected IME characters, extracted stroke medians, inverted Y axis; text serialization.',
            '# Copyright (C) 1999 Arphic Technology Co., Ltd. Redistributable under ARPHICPL.TXT.',
        ]
        lines.extend(char + '\t' + templates[char] for char in sorted(templates))
        # 尅, an IME-specific character absent upstream. Independently drawn stroke-center template.
        if '尅' in missing:
            lines.append('# Original supplementary template for 尅, 2026-11-23. Distributed under the same license.')
            lines.append('尅\t50,220 530,210;280,88 275,360;132,360 142,581;135,360 460,356 460,578;138,580 459,573;210,585 188,714 145,813 69,903;373,585 369,860 387,902 858,903 910,879 931,803;535,303 935,298;840,85 835,727 810,759 733,748;568,464 623,551 657,601')
            missing.remove('尅')
        (OUT / 'medians.tsv').write_text('\n'.join(lines)+'\n')
        (OUT / 'ARPHICPL.TXT').write_bytes(license_text)
        n = len(chars - missing)
        sections = (('土家语词与变体', native), ('参考音节字表', references), ('语法成分', grammar),
                    ('普通汉语独立成词的汉字', mandarin))
        coverage = '\n'.join(f'{label}　已收 {len(glyphs - missing)} / {len(glyphs)} 字。'
                             + ('缺少' + ''.join(sorted(glyphs & missing)) + '。' if glyphs & missing else '')
                             for label, glyphs in sections)
        notice = f"""离线手写字形数据

可手写 {n} 个不同的汉字。词表、音节表和语法表中的同一个字共用一份笔画模板。

{coverage}

{len(templates)} 个字的笔画中线选自 Hanzi Writer Data 2.0.1，原始数据来自 Make Me a Hanzi 和文鼎字型。「尅」的模板为本项目另绘。2026-11-24 对数据作了选字、坐标方向调整和文本转换。转换程序见源码包中的 tools/import_handwriting.py。

Hanzi Writer Data: https://github.com/chanind/hanzi-writer-data
Make Me a Hanzi: https://github.com/skishore/makemeahanzi
Copyright (C) 1999 Arphic Technology Co., Ltd.
字形数据可依 Arphic Public License 自由复制、修改和再分发。授权原文见本目录 ARPHICPL.TXT。字形数据按现状提供。
"""
        (OUT / 'NOTICE.txt').write_text(notice, encoding='utf-8')
        print(f'{n} character templates\n{coverage}')


def is_han(char):
    name = unicodedata.name(char, '')
    return name.startswith(('CJK UNIFIED IDEOGRAPH-', 'CJK COMPATIBILITY IDEOGRAPH-')) or char == '〇'

if __name__ == '__main__':
    main(Path(sys.argv[1]), Path(sys.argv[2]))
