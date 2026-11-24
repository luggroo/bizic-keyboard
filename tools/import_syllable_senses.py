#!/usr/bin/env python3
"""Audit complete northern dictionary forms before glossing an isolated predicted sound."""
import argparse
import csv
from collections import defaultdict
import json
from pathlib import Path
import re
import subprocess
import unicodedata
from han_orthography import Orthography

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'shared/bundle/Assets'
DATA = ROOT / 'tools/data'
AUDIT = ROOT / 'qa/results/syllable-sense-audit.tsv'
# Same tone-letter syllable boundaries as the dictionary's yeToAccent().
UNIT = re.compile(r'[a-züê]*?[aeiouüê][a-züê]*?[xfrv]')
HAN = re.compile(r'[\u3400-\u9fff]{1,8}')
COLUMNS = ('syllable', 'shortGloss', 'lexemeId', 'han')


def read_table(path):
    with path.open(encoding='utf-8') as inp:
        return list(csv.DictReader(inp, delimiter='\t'))


def export(path, header, rows):
    with path.open('w', encoding='utf-8', newline='') as out:
        writer = csv.writer(out, delimiter='\t', lineterminator='\n')
        writer.writerow(header)
        writer.writerows(rows)


def classify(witness):
    """A bound or partial source form can veto a gloss, but cannot supply an independent word."""
    raw = unicodedata.normalize('NFC', witness['spelling']).lower().strip()
    for form in re.split(r'[~～/／|｜]', raw):
        form = re.sub(r'⟦[^⟧]*⟧', '□', form).strip()
        units = UNIT.findall(form)
        if not units:
            continue
        bound = form.startswith('-') or form.endswith('-') or any(
            tag in witness['pos'].lower() for tag in ('suff', 'pref', 'infix'))
        whole = len(units) == 1 and units[0] == form and not bound
        if witness['method'] == 'partial-component' or '□' in form:
            whole = False
        for unit in set(units):
            yield unit, 'whole' if whole else ('bound' if bound and len(units) == 1 else 'component')


def lexical_sense(witnesses, nonfree, local):
    senses = {w['sense'].strip() for w in witnesses}
    if len(senses) != 1 or not any(not w['uncertain'] for w in witnesses) or nonfree:
        return None
    sense = next(iter(senses))
    # A survey prompt can ask for a particular use (e.g. 午, note 属马). Its printed
    # Chinese heading is not a general-purpose translation of the sound.
    if not HAN.fullmatch(sense) or all(w['note'].strip() for w in witnesses):
        return None
    local_senses = {row['meaning'] for row in local}
    if local_senses and local_senses != {sense}:
        return None
    return sense


def context_evidence(site, by_key):
    # These two public texts have sentence translations but no word-level gloss.
    # Only the complete phrase is evidence; neither isolated syllable inherits '树'.
    texts = json.loads((site / 'longshan-texts.json').read_text())
    for item in texts:
        if item['code'] not in ('LS-43', 'LS-48'):
            continue
        block = next(b for b in item['blocks'] if re.search(r'\bkar\s+munr\b', b['ye'], re.I))
        if block.get('glosses') or '树' not in block['zh']:
            raise ValueError('Recheck the original alignment for ' + item['id'])
        for key in ('kar', 'munr'):
            by_key[key]['component'].append(dict(word=item['id'], id=item['code'], source='tlp-longshan',
                locality='龙山', citation='Tujia Language Project', ipaOrTerm='kʰa²¹ mũ²¹', spelling='kar munr',
                sense='整句有“树”，未逐词标注', note='', en='', pos='', method='corpus-phrase', uncertain=True))


def generate(site, asset, audit, evidence=None):
    command = ['node', str(ROOT / 'tools/syllable_witnesses.cjs'), str(site)]
    source = json.loads(subprocess.check_output(command))
    by_key = defaultdict(lambda: defaultdict(list))
    for witness in source['witnesses']:
        for key, role in classify(witness):
            by_key[key][role].append(witness)
    context_evidence(site, by_key)
    orthography = Orthography.from_file()
    defaults = {row['base'] + tone: row['t' + str(i)] for row in orthography.rows.values()
                for i, tone in enumerate('xfrv', 1)}
    prediction = read_table(ASSETS / 'syllable_bigrams.tsv')
    targets = sorted(set(defaults) | {r['next'] for r in prediction})
    local = defaultdict(list)
    for row in read_table(ASSETS / 'words.tsv'):
        if row['kind'] == 'tujia' and UNIT.fullmatch(row['word']):
            local[row['word']].append(row)
    runtime, summary, details = [], [], []
    for key in targets:
        entries = by_key[key]
        whole = entries['whole']
        parts = entries['component'] + entries['bound']
        meaning = lexical_sense(whole, parts, local[key])
        base = orthography.base(key)
        equivalent = base + key[-1] if base and key not in defaults else ''
        alias_han = defaults.get(equivalent, '')
        if meaning:
            ids = {w['word'] for w in whole}
            local_match = [r for r in local[key] if r['id'] in ids and r['meaning'] == meaning]
            local_id = local_match[0]['id'] if len(local_match) == 1 else ''
            han = local_match[0]['recommended'] if local_id else ''
            if not han and defaults.get(key) == meaning:
                han = meaning
            runtime.append((key, meaning, local_id, han))
        elif alias_han:
            # The existing transcription system supplies a writing, not a lexeme or meaning.
            runtime.append((key, '', '', alias_han))
        senses = sorted({w['sense'] for w in whole if w['sense']})
        role_counts = [len({w['word'] for w in entries[role]}) for role in ('whole', 'component', 'bound')]
        if meaning:
            disposition = 'verified'
        elif len(senses) > 1:
            disposition = 'multiple-senses'
        elif whole and parts:
            disposition = 'also-bound-or-compound'
        elif whole:
            disposition = 'unreviewed-or-contextual'
        elif parts:
            disposition = 'bound-or-compound-only'
        else:
            disposition = 'no-complete-match'
        sources = sorted({w['source'] for w in whole})
        summary.append((key, disposition, meaning or '', *role_counts, ' / '.join(senses),
                        ' '.join(sources), ' '.join(sorted({w['word'] for w in whole})),
                        ' '.join(sorted({w['word'] for w in parts})[:5]), equivalent, alias_han))
        if evidence:
            for role, forms in sorted(entries.items()):
                for w in forms:
                    details.append((key,role,w['word'],w['id'],w['source'],w['locality'],w['citation'],
                                    w['ipaOrTerm'],w['spelling'],w['sense'],w['en'],w['note'],w['method']))
    export(asset,COLUMNS,runtime)
    export(audit,('syllable','status','shortGloss','wholeWords','compoundWords','boundWords','independentMeanings',
                  'independentSources','independentEntries','componentExamples','equivalentReferenceKey','referenceHan'),summary)
    if evidence:
        export(evidence,('syllable','role','wordId','witnessId','source','locality','citation','sourceForm',
                         'ye','meaning','english','usageNote','mapping'),sorted(set(details)))
    print(f"{source['northernEntries']} northern dictionary entries / {source['northernAttestations']} witnesses; "
          f"{source['surveyRows']} northern survey prompts; {len(source['unmatched'])} unmapped dictionary entries or incomplete survey prompts; "
          f"{sum(bool(r[1]) for r in runtime)} meanings and {sum(not r[1] for r in runtime)} "
          f"writing-only equivalences for {len(targets)} syllables.")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('site', type=Path, help='site directory of a local bizic-dictionary checkout')
    parser.add_argument('--asset', type=Path, default=ASSETS/'syllable_senses.tsv')
    parser.add_argument('--audit', type=Path, default=AUDIT)
    parser.add_argument('--evidence', type=Path, help='optional TSV with every linked source form')
    args = parser.parse_args()
    generate(args.site, args.asset, args.audit, args.evidence)
