#!/usr/bin/env python3
"""Align source glosses for the offline next-word predictor."""
import csv
import unicodedata
from collections import defaultdict

from import_words import SENSES, clean_gloss, gloss_parts, native_forms, reviewed_glosses


def letters(value):
    return ''.join(c for c in value.casefold() if c.isalnum() or unicodedata.category(c).startswith('M'))


def aligned_units(block, form, document_id=None):
    glosses = (reviewed_glosses(block) if document_id == 'tlp-longshan-stories-02-01'
               else block.get('glosses', []))
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


if __name__ == '__main__':
    from prediction_model import main
    main()
