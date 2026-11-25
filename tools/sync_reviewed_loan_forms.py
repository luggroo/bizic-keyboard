#!/usr/bin/env python3
"""Sync unambiguous, form-specific whole-loan notes from a prepared site."""
import argparse
import csv
import gzip
import json
import re
import unicodedata
from pathlib import Path

from keyboard_gloss import normalize_meaning

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'shared/bundle/Assets'
DATA = ROOT / 'tools/data'
FIELDS = 'id word key meaning recommended aliases variants frequency kind shortGloss plain diacritic hanUnits romanAliases presentation'.split()
LOAN_FIELDS = ['id', 'ye', 'han', 'meaning', 'noteId', 'basis']


def canonical_ipa(value):
    value = unicodedata.normalize('NFC', value)
    def apical(match):
        following = value[match.end():match.end() + 1]
        if following and unicodedata.category(following).startswith(('L', 'M')):
            return match.group(0)
        return match.group(1) + ('ɿ' if match.group(1)[0] in 'szʦʣǳ' else 'ʅ')
    value = re.sub(r'([szʦʣǳ](?:[͜͡]?ʰ)?|[ʂʐ]ʰ?)ɨ', apical, value)
    return re.sub(r'[\s|｜◦]', '', value)


def recorded_donor(row, mini, notes, excluded):
    """Return one documented donor, without inferring it from a homophone."""
    ident = row['id'].split('@')[0]
    if ident in excluded or row['id'] in excluded or row['kind'] != 'tujia':
        return None
    if row['word'] == 'xiaf' or '未细分' in row['meaning']:
        return None  # Preserve reviewed spelling and unresolved broad senses.
    forms = [v for v in mini.get('_display', {}).get('ye', []) if v['text'].lower() == row['word'].lower()]
    if len(forms) != 1:
        return None
    ipas = forms[0].get('ipaForms', mini.get('_ipa', []) if len(mini.get('_ipa', [])) == 1 else [])
    if not ipas:
        return None
    tokens = set(re.split(r'[；;，,、/／（）()\s]+', row['meaning']))
    matches = []
    for note in notes:
        donor = note.get('donor', '')
        if not (note.get('id', '').startswith(('chinese-loan-', 'loan-etymology-')) and
                note.get('kind') == '借词来源' and note.get('scope') == 'whole' and
                donor != '毒' and re.fullmatch(r'[\u3400-\u9fff]+', donor) and
                donor in tokens and len(donor) == len(row['hanUnits'].split('|'))):
            continue
        if any(canonical_ipa(f.get('ipa', '')) in {canonical_ipa(x) for x in ipas}
               and f.get('span') == [1, len(donor)] for f in note.get('reviewedForms', [])):
            matches.append(note)
    return matches[0] if len({n['donor'] for n in matches}) == 1 else None


def exclusions(review_dir):
    ids = set()
    for name in ('loan-table-review-2026-11-17.json', 'loan-south-vo-review-2026-11-17.json'):
        for d in json.loads((review_dir/name).read_text())['decisions']:
            if d.get('decision') == 'not-loan':
                ids.update([d['id'], *d.get('aliases', [])])
    for r in json.loads((review_dir/'loan-screening-constraints.json').read_text())['rules']:
        if r['policy'] in ('exclude-entry', 'retain-review', 'exclude-component'):
            ids.add(r['id'])
    return ids


def read_rows(path, header):
    with path.open(encoding='utf-8', newline='') as stream:
        return list(csv.DictReader(stream, delimiter='\t', fieldnames=None if header else FIELDS))


def write_rows(path, rows, fields, header):
    with path.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, delimiter='\t', lineterminator='\n', quoting=csv.QUOTE_NONE)
        if header:
            writer.writeheader()
        writer.writerows(rows)


def run(site, review_dir):
    prepared = site/'prepared'
    def read(name):
        with gzip.open(prepared/(name+'.json.gz'), 'rt', encoding='utf-8') as stream:
            return json.load(stream)
    core = read('core')
    by_id = {w['id']: w for w in core['words']}
    excluded = exclusions(review_dir)
    core_words = read_rows(ASSETS/'words.tsv', True)
    north_words = read_rows(ASSETS/'northern-words.tsv', False)
    core_loans = read_rows(DATA/'core-han-loans.tsv', True)
    north_loans = read_rows(DATA/'northern-han-loans.tsv', True)
    known = {(r['id'], r['ye']) for r in core_loans + north_loans}
    added = {'core': [], 'north': []}
    for group, rows in [('core', core_words), ('north', north_words)]:
        for row in rows:
            if row['presentation'] == 'plainHanLoan' or row['id'].startswith(('yubao-', 'ethnonym-')):
                continue
            mini = by_id.get(row['id'].split('@')[0])
            if not mini or '南部' in mini.get('dialect', '') or (row['id'], row['word']) in known:
                continue
            entry = read('entries/'+mini['id'])
            note = recorded_donor(row, mini, entry.get('_entryNotes', []), excluded)
            if not note:
                continue
            donor = note['donor']
            added[group].append(dict(id=row['id'], ye=row['word'], han=donor,
                                     meaning=normalize_meaning(row['meaning']), noteId=note['id'], basis=note['text']))
            known.add((row['id'], row['word']))
            if group == 'core':
                if row['recommended'] != donor:
                    row['aliases'] = ' '.join(dict.fromkeys([*row['aliases'].split(), row['recommended']]))
                row.update(recommended=donor, hanUnits='|'.join(donor), presentation='plainHanLoan')
    write_rows(DATA/'core-han-loans.tsv', core_loans+added['core'], LOAN_FIELDS, True)
    write_rows(DATA/'northern-han-loans.tsv', north_loans+added['north'], LOAN_FIELDS, True)
    write_rows(ASSETS/'words.tsv', core_words, FIELDS, True)
    print(f"{len(added['core'])} primary and {len(added['north'])} supplementary reviewed forms added")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('prepared_site', type=Path)
    parser.add_argument('review_dir', type=Path)
    args = parser.parse_args()
    run(args.prepared_site, args.review_dir)
