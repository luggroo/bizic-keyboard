"""Build the first complete song on pp. 190–191, using the school's own display converter."""
import csv
import json
import re
import subprocess
import unicodedata
from pathlib import Path

SOURCE_ID = 'xu-tujia-annotated-2-13'
SOURCE_URL = 'https://tujia.biz/?id=' + SOURCE_ID + '#corpus?corpusCollection=xu-tujia-annotated-texts'
SENSES = {'1pl':'我们', 'INJ':'语气词', 'EMP':'强调', 'STRP':'状态助词', 'IND':'陈述语气'}
# -xi is used in a status complement in line 4. Keep the reviewed unstressed grammar glyph.
GRAMMAR = {('ɕi⁵⁵', 'STRP'): '兮'}

def plain(text):
    stripped = ''.join(c for c in unicodedata.normalize('NFD', text) if c not in '\u0304\u0301\u030c\u0300')
    return re.sub(r"[ ’'-]", '', unicodedata.normalize('NFC', stripped))

def make_song(dictionary, existing, orthography, project, references):
    source = next(x for x in json.loads((dictionary/'site/annotated-books.json').read_text()) if x['id']==SOURCE_ID)
    blocks = source['blocks'][:4]  # First song, ending at the period on line 4.
    tokens = [g for b in blocks for g in b['glosses']]
    ipa_parts = {g['ipa']: re.findall(r'[^⁰¹²³⁴⁵⁶⁷⁸⁹]+[⁰¹²³⁴⁵⁶⁷⁸⁹]+',g['ipa']) for g in tokens}
    assert all(''.join(s)==ipa for ipa,s in ipa_parts.items())
    ipa = sorted(set(ipa_parts)|{p for parts in ipa_parts.values() for p in parts})
    converter = ['node', str(project/'tools/site_spellings.cjs'), str(dictionary/'site')]
    run = lambda req: json.loads(subprocess.check_output(converter, input=json.dumps(req).encode()))
    written = run({'ipa':ipa, 'words':[]})['spellings']
    accents = run({'ipa':[], 'words':list(written.values())})['accented']
    records, sections, extras, by_source = [], [], [], {}
    for line, block in enumerate(blocks,1):
        assert block['ipa'].split()==[g['ipa'] for g in block['glosses']]
        rows = []
        for column, g in enumerate(block['glosses'],1):
            form = written[g['ipa']]
            parts = [written[p] for p in ipa_parts[g['ipa']]]
            assert form==''.join(parts) and all(re.fullmatch('[a-z]+[xfrv]',p) for p in parts)
            meaning = SENSES.get(g['zh'],g['zh'])
            key=(g['ipa'],g['zh'])
            if key not in by_source:
                entry=next((w for w in existing if w['word']==form and meaning in w['meaning'].split('、')), None)
                if not entry:
                    units=orthography.units(parts,[references.get(p,{}).get('first',{}).get('h','') for p in ipa_parts[g['ipa']]])
                    if key in GRAMMAR: units=[GRAMMAR[key]]
                    if any(not unit for unit in units):raise ValueError(('No current orthography for',parts))
                    entry=dict(id=f'xu213-{line}-{column}',word=form,meaning=meaning,shortGloss=meaning,
                               storyMeaning=meaning,recommended=''.join(units),hanUnits=units,syllables=ipa_parts[g['ipa']],
                               aliases=[],variants=[u if len(u)==1 else '' for u in units],inBase=False,
                               diacritic=accents[form],plain=plain(accents[form]),key=plain(accents[form]),
                               sourceGloss=g['zh'],sourceIpa=g['ipa'])
                    extras.append(entry)
                by_source[key]=entry
            entry=by_source[key]
            suffix=(block['zh'][-1]+('\n' if line<len(blocks) else '')) if column==len(block['glosses']) else ''
            rows.append(dict(id=entry['id'],end=suffix))
            records.append((entry,form,accents[form],suffix))
        sections.append(dict(translation=block['zh'],rows=rows))
    with (project/'tools/story-demo/shange.tsv').open('w',newline='') as out, (project/'tools/story-demo/shange-spelling.tsv').open('w',newline='') as full:
        t=csv.writer(out,delimiter='\t',lineterminator='\n'); s=csv.writer(full,delimiter='\t',lineterminator='\n')
        t.writerow(['keys','text','meaning','after','hanUnits'])
        s.writerow(['id','plain','ye','diacritic','han','gloss','after'])
        for w,ye,accent,after in records:
            end=after.replace('\n','\\n'); keys=plain(accent)
            t.writerow([keys,w['recommended'],w['meaning'],end,'|'.join(w['hanUnits'])])
            s.writerow([w['id'],keys,ye,accent,w['recommended'],w['meaning'],end])
    return extras, dict(title='山歌两首（第一首）',url=SOURCE_URL,source=SOURCE_URL,sections=sections)
