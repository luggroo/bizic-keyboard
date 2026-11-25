"""Audit every reference tone and observed single-syllable reading.

Checks the actual shared engine, including its merged candidates, rather than
only the source strings. Optional --output writes a TSV for human review.
"""
import argparse
import csv
import re
import sys
import tempfile
from pathlib import Path

from lexicon import ASSETS, Model, library, utf16
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from han_orthography import Orthography, TONES


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    with (ASSETS / 'words.tsv').open() as f:
        reader = csv.DictReader(f, delimiter='\t'); fields = reader.fieldnames; rows = list(reader)
    with (ASSETS / 'northern-words.tsv').open() as f:
        rows += list(csv.DictReader(f, delimiter='\t', fieldnames=fields))
    table = Orthography.from_file()
    readings = {base + tone for base in table.rows for tone in TONES}
    readings.update(r['word'] for r in rows if r['kind'] in ('tujia', 'phonetic') and
                    re.fullmatch('[a-z]{1,8}[xfrv]', r['word']) and
                    (table.base(r['word']) or (r['hanUnits'] and '|' not in r['hanUnits'])))
    with tempfile.TemporaryDirectory() as tmp:
        dll = library(Path(tmp) / 'libtujia.so')
        blob = (ASSETS / 'words.tsv').read_bytes() + (ASSETS / 'northern-words.tsv').read_bytes()
        data = [(ASSETS / (n + '.tsv')).read_bytes() for n in ('ngrams', 'syllable_bigrams', 'syllable_senses')]
        model = Model(dll, blob, *data)
        report = []
        try:
            for query in sorted(readings):
                choices = model.choices(query, 0, 0, cache=False)
                for c in choices:
                    assert len(c.short) <= 6, (query, c.text, c.short)
                exact = [c for c in choices if c.kind == 0 and c.text == query and c.consumed == utf16(query)]
                report.append((query, '；'.join(c.short for c in exact if c.short),
                               '；'.join(c.gloss for c in exact if c.gloss)))
            r = [c for c in model.choices('rǐ', 0, 1) if c.text == 'rǐ' and c.consumed == 2]
            assert len(r) == 1 and r[0].short == '多；男生殖器', r
            assert r[0].gloss == '多；男生殖器', r
            han = [c for c in model.choices('rir', 1, 0) if c.text == '日' and c.consumed == 3]
            assert {'多', '男生殖器'} <= {c.gloss for c in han}, han
        finally:
            dll.tji_words_close(model.handle)
    if args.output:
        with args.output.open('w', encoding='utf-8', newline='') as f:
            writer = csv.writer(f, delimiter='\t', lineterminator='\n')
            writer.writerow(['ye', 'shortGloss', 'fullMeaning'])
            writer.writerows(report)
    print(f'PASS: audited {len(readings)} toned syllables; each pinyin hint fits six characters')

if __name__ == '__main__':
    main()
