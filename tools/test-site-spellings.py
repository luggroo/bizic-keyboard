#!/usr/bin/env python3
"""Verify shipped word outputs against the school's published display code and corpus."""
import argparse
import csv
import gzip
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tools/story-demo'))
from longshan_spelling import reviewed_glosses
from xu_song import SOURCE_ID, SENSES, plain

def read_tsv(path):
    with path.open(encoding='utf-8', newline='') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))

def unpack_js(path):
    return json.loads(path.read_text().split('=',1)[1].strip().rstrip(';'))

def run(site, web):
    rows = {r['id']:r for r in read_tsv(ROOT/'shared/bundle/Assets/words.tsv') if r['kind']=='tujia'}
    listing = json.loads((web/'words.js').read_text().split('=',1)[1].split(';\nconst soundVariants=')[0])
    dictionary = json.load(gzip.open(site/'dictionary.json.gz','rt'))['words']
    entries = [w for w in dictionary if w['id'] in rows]
    source = next(t for t in json.loads((site/'annotated-books.json').read_text()) if t['id']==SOURCE_ID)
    blocks = source['blocks'][:4]
    recorded = read_tsv(ROOT/'tools/story-demo/shange-spelling.tsv')
    converter = ['node', str(ROOT/'tools/site_spellings.cjs'), str(site)]
    expected = json.loads(subprocess.check_output(converter, input=json.dumps({
        'words':[r['word'] for r in rows.values()], 'entries':entries,
        'ipa':[g['ipa'] for b in blocks for g in b['glosses']]}).encode()))
    display = expected['display']
    for entry in listing:
        assert entry['id'] in display, entry['id']
    for word_id, shown in display.items():
        r=rows[word_id]
        assert r['word'] in shown['ye'] and r['diacritic'] in shown['accent'], r
    ranking=unpack_js(web/'ranking.js')['words']
    for r in rows.values():
        assert r['plain']==plain(r['diacritic']), r
        assert r['diacritic']==expected['accented'][r['word']], r
        assert ranking[r['id']]==[int(r['frequency']),r['shortGloss'],r['plain'],r['diacritic'],r['word'],r['romanAliases'].split()],r
    raw=(web/'story-words.js').read_text().split(';\nconst noteStory=')[1]
    demo=json.loads(raw.split(';\nconst archivedStory=')[0]);archive=json.loads(raw.split(';\nconst archivedStory=')[1].strip().rstrip(';'))
    assert len(blocks)==len(demo['sections'])==4
    cursor=0
    for b,section in zip(blocks,demo['sections']):
        assert section['translation']==b['zh']
        assert len(section['rows'])==len(b['glosses'])
        for g, selection in zip(b['glosses'],section['rows']):
            r=rows[selection['id']];record=recorded[cursor];cursor+=1
            assert expected['spellings'][g['ipa']]==r['word']==record['ye']
            assert SENSES.get(g['zh'],g['zh'])==record['gloss']==r['meaning']
            assert record['after']==selection['end'].replace('\n','\\n')
            assert record['diacritic']==r['diacritic']
    assert cursor==len(recorded)==22
    tlp=next(t for t in json.loads((site/'longshan-texts.json').read_text()) if t['id']=='tlp-longshan-stories-02-01')
    archive_count=0
    for b,section in zip(tlp['blocks'],archive['sections']):
        fixed=reviewed_glosses(b)
        assert len(section['rows'])==len(fixed)
        for gloss,select in zip(fixed,section['rows']):
            r=rows[select['id']]; archive_count+=1
            original=''.join(gloss['ye'].lower().split())
            assert original in {re.sub("[ ’'-]","",r['word']),*r['romanAliases'].split()}, (original,r)
    tea=rows['stedt-rn-368307'];sit=rows['w1658']
    assert all(r['word']!='wunr' for r in rows.values())
    assert sit['word']=='ongr' and {'wun','wunr','wǔn'}<=set(sit['romanAliases'].split())
    assert tea['word']=='arciex' and 'wun' not in tea['romanAliases'].split()
    print(f"{len(rows)} native rows match web; {len(display)} dictionary displays; {cursor} school song selections; {archive_count} original archive inputs; source homophones verified.")

if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('dictionary_site',type=Path)
    cli.add_argument('web_dist',type=Path)
    args=cli.parse_args()
    run(args.dictionary_site,args.web_dist)
