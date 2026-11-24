#!/usr/bin/env python3
"""Extract evidenced whole-word Chinese donor forms for the supplementary lexicon."""
import csv
import gzip
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
site = Path(sys.argv[1])
with gzip.open(site / 'dictionary.json.gz', 'rt', encoding='utf-8') as f:
    words = json.load(f)['words']
loader = """const fs=require('fs'),vm=require('vm');
const code=fs.readFileSync(process.argv[1],'utf8');
process.stdout.write(vm.runInNewContext(code+'\\nJSON.stringify(EDITORIAL_NOTES)',{}, {timeout:5000}));"""
notes = json.loads(subprocess.check_output(['node', '-e', loader, str(site / 'editorial-notes.js')]))
byid = {w['id']: w for w in words}
canonical = {a['id']: w['id'] for w in words for a in w.get('attestations', [])}
forms = defaultdict(list)
for note in notes:
    donor = note.get('donor', '')
    if (note.get('kind') != '借词来源' or note.get('scope') != 'whole' or
            not note.get('text', '').startswith('借自汉语') or not donor or not note.get('reviewedForms')):
        continue
    for source in note.get('wordIds', []):
        ident = canonical.get(source, source)
        word = byid.get(ident)
        if not word or word.get('dialect') != '北部' or word.get('zh') != donor:
            continue
        ipas = {x.get('text') for x in word.get('notations', {}).get('ipa', [])}
        if not any(x.get('ipa') in ipas for x in note['reviewedForms']):
            continue
        forms[ident].append((donor, note['id'], note['text']))
# The project explicitly identifies 国家 for all these documented variants.
for ident in ('w810', 'w3682', 'w3695', 'stedt-rn-288910', 'stedt-rn-206778'):
    assert byid[ident]['zh'] == '国家'
    forms[ident].append(('国家', '', '项目确认“国家”及其所记异读用本字'))

fields = 'id word key meaning recommended aliases variants frequency kind shortGloss plain diacritic hanUnits romanAliases presentation'.split()
rows = []
with (ROOT / 'shared/bundle/Assets/northern-words.tsv').open(encoding='utf-8') as f:
    for row in csv.DictReader(f, fieldnames=fields, delimiter='\t'):
        ident = row['id'].split('@')[0]
        choices = forms.get(ident, [])
        donors = {x[0] for x in choices}
        if row['kind'] != 'tujia' or len(donors) != 1:
            continue
        donor = next(iter(donors))
        # Preserve the project's explicitly selected xiaf character.
        if row['word'] == 'xiaf' and donor != '夏':
            continue
        # A whole-word form must align one written Han character per syllable.
        if len(donor) != len(row['hanUnits'].split('|')):
            continue
        choice = choices[0]
        rows.append([row['id'], row['word'], donor, row['meaning'], choice[1], choice[2]])
path = ROOT / 'tools/data/northern-han-loans.tsv'
with path.open('w', encoding='utf-8', newline='') as f:
    out = csv.writer(f, delimiter='\t', lineterminator='\n')
    out.writerow(['id','ye','han','meaning','noteId','basis'])
    out.writerows(rows)
print(len(rows), 'supplementary whole-word donor forms')
