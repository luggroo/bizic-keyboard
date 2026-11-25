#!/usr/bin/env python3
"""Sync exact survey forms with reviewed whole-word loan notes from the site."""
import csv
import json
import re
import subprocess
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

from keyboard_gloss import normalize_meaning

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'tools/data'
FIELDS = ['id', 'ye', 'han', 'meaning', 'basis', 'noteId', 'ipa']


def canonical_ipa(value):
    return re.sub(r'[\s|｜◦]', '', unicodedata.normalize('NFC', value))


def select_rows(words, notes, overrides):
    by_id = defaultdict(list)
    for note in notes:
        donor = note.get('donor', '')
        if not (note.get('id', '').startswith(('chinese-loan-', 'loan-etymology-'))
                and note.get('kind') == '借词来源' and note.get('scope') == 'whole'
                and note.get('text', '').startswith(('借自', '可能借自', '汉语借词'))
                and re.fullmatch(r'[\u3400-\u9fff]+', donor)):
            continue
        for ident in note.get('wordIds', []):
            by_id[ident].append(note)
    rows = []
    applied = set()
    for word in words:
        for index, form in enumerate(word['forms']):
            ident = word['id'] if index == 0 else f"{word['id']}@{index+1}"
            ye = form['ye'].lower()
            key = (ident, ye)
            parts = form['parts']
            ipa = canonical_ipa(form['ipa'])
            # Match the website's reviewed form by its complete source IPA.
            # The donor's own tone is not a condition on this association.
            matched = [note for note in by_id[word['id']]
                       if len(note['donor']) == len(parts) and parts and all(parts)
                       and any(canonical_ipa(f.get('ipa', '')) == ipa
                               and f.get('span') == [1, len(parts)]
                               for f in note.get('reviewedForms', []))]
            donors = {n['donor'] for n in matched}
            override = overrides.get(key)
            if override:
                if override['meaning'] != normalize_meaning(word['zh']) or len(override['han']) != len(parts):
                    raise ValueError(f'Survey override does not match current sense and syllables: {key}')
                applied.add(key)
                source = next((n for n in matched if n['donor'] == override['han']), None)
                rows.append([ident, ye, override['han'], override['meaning'], override['basis'],
                             source['id'] if source else '', ipa])
            elif len(donors) == 1:
                note = matched[0]
                rows.append([ident, ye, note['donor'], normalize_meaning(word['zh']),
                             note['text'], note['id'], ipa])
    missing = set(overrides) - applied
    if missing:
        raise ValueError(f'Survey overrides absent from source: {sorted(missing)}')
    return rows


def build(site):
    loader = """const fs=require('fs'),vm=require('vm');
const source=fs.readFileSync(process.argv[1],'utf8');
process.stdout.write(vm.runInNewContext(source+'\\nJSON.stringify(EDITORIAL_NOTES)',{}, {timeout:5000}));"""
    notes = json.loads(subprocess.check_output(['node', '-e', loader, str(site/'editorial-notes.js')]))
    words = json.loads(subprocess.check_output(['node', str(ROOT/'tools/site_survey_words.cjs'), str(site)]))
    overrides = {}
    with (DATA/'survey-loan-overrides.tsv').open(encoding='utf-8', newline='') as stream:
        for row in csv.DictReader(stream, delimiter='\t'):
            key = (row['id'], row['ye'])
            if key in overrides:
                raise ValueError(f'Duplicate survey override: {key}')
            overrides[key] = row
    rows = select_rows(words, notes, overrides)
    path = DATA/'survey-loan-forms.tsv'
    with path.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t', lineterminator='\n')
        writer.writerow(FIELDS)
        writer.writerows(rows)
    print(f'{len(rows)} survey forms with explicit whole-word loan evidence')


if __name__ == '__main__':
    build(Path(sys.argv[1]))
