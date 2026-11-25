#!/usr/bin/env python3
"""Targeted source-data regressions for unlabeled sounds, attested meanings and writing-only aliases."""
import csv
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def indexed(path):
    with path.open(encoding='utf-8') as stream:
        data = list(csv.DictReader(stream, delimiter='\t'))
    result = {row['syllable']: row for row in data}
    assert len(result) == len(data), 'A reading must have only one runtime/audit row'
    return result


def main():
    runtime = indexed(ROOT / 'shared/bundle/Assets/syllable_senses.tsv')
    audit = indexed(ROOT / 'qa/results/syllable-sense-audit.tsv')
    assert set(runtime['jinv']) == {'syllable', 'shortGloss', 'lexemeId', 'han'}
    assert runtime['jinv']['shortGloss'] == '紧' and not runtime['jinv']['han']
    assert {'w1051', 'w3991'} <= set(audit['jinv']['independentEntries'].split())
    assert audit['jinv']['compoundWords'] == audit['jinv']['boundWords'] == '0'
    assert runtime['mangf'] == dict(syllable='mangf', shortGloss='慢', lexemeId='w1473', han='慢')
    assert 'mongr' not in runtime
    assert audit['mongr']['independentMeanings'] == '午'
    assert 'yubao-60757-v-2498' in audit['mongr']['independentEntries'].split()
    assert int(audit['mongr']['compoundWords']) > 0
    assert runtime['munr'] == dict(syllable='munr', shortGloss='', lexemeId='', han='萌')
    assert audit['munr']['wholeWords'] == '0'
    assert set(audit['munr']['componentExamples'].split()) >= {
        'tlp-longshan-proverbs-28', 'tlp-longshan-proverbs-33'}
    assert runtime['hanf']['han'] == '汉' and not runtime['hanf']['shortGloss']
    assert not ({'tunx', 'tunr', 'gaf', 'yer'} & runtime.keys())
    for key, row in runtime.items():
        proof = audit[key]
        if row['shortGloss']:
            assert proof['status'] == 'verified' and proof['shortGloss'] == row['shortGloss']
        else:
            assert not row['lexemeId'] and row['han'] == proof['referenceHan']
            assert proof['equivalentReferenceKey']
    print(f"PASS: {len(audit)} audited readings, {sum(bool(r['shortGloss']) for r in runtime.values())} independent meanings, "
          f"{sum(not r['shortGloss'] for r in runtime.values())} transcription-only aliases")


if __name__ == '__main__':
    main()
