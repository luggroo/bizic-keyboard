#!/usr/bin/env python3
"""Count adjacent, unambiguously glossed northern Tujia lexemes from tujia.biz."""
import argparse
import csv
import hashlib
import json
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

from import_words import ROOT, SENSES, clean_gloss, gloss_parts, load, native_forms, spellings


def letters(value):
    return ''.join(c for c in value.casefold() if c.isalnum() or unicodedata.category(c).startswith('M'))


def aligned_units(block, form):
    glosses = block.get('glosses', [])
    surface = block.get(form, '')
    if not glosses or not surface:
        return []
    # Source punctuation can only be aligned if the transcript accounts for the whole surface.
    if letters(surface) != ''.join(letters(gloss[form]) for gloss in glosses):
        return []
    boundaries = set()
    offset = 0
    for char in surface:
        if char in ',.;:!?，。；：！？、…/\n':
            boundaries.add(offset)
        offset += len(letters(char))
    units = []
    offset = 0
    for gloss in glosses:
        end = offset + len(letters(gloss[form]))
        if offset in boundaries:
            units.append(None)
        spans_punctuation = any(offset < mark < end for mark in boundaries)
        units.append(None if spans_punctuation else gloss)
        offset = end
    return units


def lexemes_by_sense(words):
    lookup = defaultdict(set)
    with words.open(encoding='utf-8') as stream:
        for row in csv.DictReader(stream, delimiter='\t'):
            if row['kind'] not in ('tujia', 'phrase', 'grammar'):
                continue
            senses = gloss_parts(row['meaning']) | {clean_gloss(row['shortGloss'])}
            senses.update(clean_gloss(s) for s in SENSES.get(row['id'], ()))
            forms = [row['word'], *row.get('romanAliases', '').split()]
            for form in forms:
                for sense in senses:
                    lookup[native_forms(form), sense].add(row['id'])
    return lookup


def generate(site, words, output, provenance):
    lookup = lexemes_by_sense(words)
    annotated = load(site / 'annotated-books.json')
    spell = spellings(site, [], annotated, {'words': []})['spellings']
    files = [('annotated-books.json', annotated, 'ipa'),
             ('longshan-texts.json', load(site / 'longshan-texts.json'), 'ye')]
    counts, evidence, stats = Counter(), {}, Counter()
    for name, texts, form in files:
        for text in texts:
            if text['dialect'] != '北部':
                continue
            for index, block in enumerate(text['blocks'], start=1):
                context = []
                units = aligned_units(block, form)
                if units:
                    stats[name + ' aligned blocks'] += 1
                for gloss in units:
                    matches = set()
                    if gloss:
                        native = spell.get(gloss['ipa'], '') if form == 'ipa' else gloss['ye']
                        for sense in gloss_parts(gloss['zh']):
                            matches.update(lookup.get((native_forms(native), sense), ()))
                    if len(matches) != 1:
                        context.clear()
                        continue
                    following = matches.pop()
                    stats[name + ' matched tokens'] += 1
                    for length in range(1, len(context) + 1):
                        gram = tuple(context[-length:] + [following])
                        counts[gram] += 1
                        evidence.setdefault(gram, f"{text['id']}#{block.get('id', index)}")
                    context = (context + [following])[-2:]
    with output.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t', lineterminator='\n')
        writer.writerow(['context', 'next', 'count', 'example'])
        for gram, count in sorted(counts.items()):
            writer.writerow([' '.join(gram[:-1]), gram[-1], count, evidence[gram]])
    stats['distinct bigrams'] = sum(len(g) == 2 for g in counts)
    stats['distinct trigrams'] = sum(len(g) == 3 for g in counts)
    report = {
        'sources': {name: hashlib.sha256((site / name).read_bytes()).hexdigest() for name, _, _ in files},
        'sourceUrl': 'https://tujia.biz',
        'counts': stats,
        'method': ('Adjacent glossed tokens within a single northern source block. '
                   'Source punctuation, missing or ambiguous lexeme IDs, and block boundaries reset context. '
                   'Example references identify the original text and block.'),
    }
    provenance.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(dict(stats))


if __name__ == '__main__':
    assets = ROOT / 'shared/bundle/Assets'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dictionary_site', type=Path)
    parser.add_argument('--words', type=Path, default=assets / 'words.tsv')
    parser.add_argument('--output', type=Path, default=assets / 'ngrams.tsv')
    parser.add_argument('--provenance', type=Path, default=assets / 'licenses/ngrams-source.json')
    args = parser.parse_args()
    generate(args.dictionary_site, args.words, args.output, args.provenance)
