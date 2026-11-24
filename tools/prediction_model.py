#!/usr/bin/env python3
"""Build a small backoff next-word model from independent northern Tujia examples."""
import argparse
import csv
import hashlib
import html
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

from import_ngrams import aligned_units, lexemes_by_sense
from import_words import ROOT, clean_gloss, gloss_parts, load, native_forms, spellings

START = '<s>'
# The replay and its earlier story are demonstrations, never prediction training material.
DEMO_TEXTS = {'xu-tujia-annotated-2-13', 'tlp-longshan-stories-02-01'}
GRAMMAR_SOURCE = 'grammar-reviewed-data.js'
MODEL_HEADER = 'context\tnext\tscore\n'


def lexical_rows(path):
    with path.open(encoding='utf-8') as stream:
        return [row for row in csv.DictReader(stream, delimiter='\t')
                if row['kind'] in ('tujia', 'phrase', 'grammar')]


def pos_class(tag):
    tag = tag.lower().rstrip('.')
    if tag in ('vt', 'vi', 'v', 'v.i', 'v.t', 'itr.v', 'n+vt', 'be v', 'be', 'vc'):
        return 'verb'
    if tag in ('va', 'adj'):
        return 'adjective'
    if tag in ('n', 'nbp', 'nveg', 'nanim', 'nrel'):
        return 'noun'
    if tag in ('pron', 'dem', 'intg'):
        return 'pronoun'
    if tag.startswith('adv'):
        return 'adverb'
    if tag in ('cl', 'clf'):
        return 'classifier'
    if tag in ('num', 'num+cl'):
        return 'number'
    if tag in ('part', 'nsuff', 'vsuff', 'suff', 'conj', 'neg'):
        return 'function'
    return ''


def word_classes(site, words):
    """Use dictionary POS. For a corpus-only entry, transfer only a shared, unanimous POS."""
    direct, by_sense = {}, defaultdict(set)
    for entry in load(site / 'dictionary.json.gz')['words']:
        if entry.get('dialect') != '北部':
            continue
        group = pos_class(entry.get('pos', ''))
        if group:
            direct[entry['id']] = group
            for sense in gloss_parts(entry.get('zh', '')):
                by_sense[sense].add(group)
    classes = {}
    for row in lexical_rows(words):
        if row['word'].startswith('-'):
            continue  # Bound endings are not free-standing next words.
        categories = by_sense[clean_gloss(row['shortGloss'])]
        fallback = next(iter(categories)) if len(categories) == 1 else ''
        classes[row['id']] = direct.get(row['id'], 'function' if row['kind'] == 'grammar' else fallback)
    return classes


def grammar_sentences(site):
    """Only interlinear tables with an explicit northern spelling and gloss in each column."""
    source = (site / GRAMMAR_SOURCE).read_text(encoding='utf-8')
    chapters, _ = json.JSONDecoder().raw_decode(source.split('const REVIEWED_GRAMMAR=', 1)[1])
    for chapter in chapters['north']:
        for table in re.findall(r'<table>(.*?)</table>', chapter['html'], re.S):
            fields = {}
            for tr in re.findall(r'<tr>(.*?)</tr>', table, re.S):
                cells = [html.unescape(re.sub(r'<[^>]+>', '', cell)).strip()
                         for cell in re.findall(r'<t[dh]>(.*?)</t[dh]>', tr, re.S)]
                if len(cells) > 1:
                    fields[cells[0]] = cells[1:]
            forms = fields.get('拼音', [])
            glosses = fields.get('逐词', fields.get('中文逐词', []))
            if forms and len(forms) == len(glosses):
                yield list(zip(forms, glosses))


def read_material(site, words):
    """Read sentences as (source spelling, source gloss, optional unique lexeme ID) triples."""
    lookup = lexemes_by_sense(words)
    docs = defaultdict(list)

    def token(reading, gloss):
        form = native_forms(reading.strip('[]'))
        hits = set()
        for sense in gloss_parts(gloss):
            hits.update(lookup.get((form, sense), ()))
        return form, gloss, next(iter(hits)) if len(hits) == 1 else None

    annotated = load(site / 'annotated-books.json')
    ipa = spellings(site, [], annotated, {'words': []})['spellings']
    for texts, form in [(annotated, 'ipa'), (load(site / 'longshan-texts.json'), 'ye')]:
        for doc in texts:
            if doc['dialect'] != '北部':
                continue
            for block in doc['blocks']:
                sentence = []
                for unit in aligned_units(block, form):
                    if unit is None:
                        if sentence:
                            docs[doc['id']].append(sentence)
                        sentence = []
                    else:
                        native = unit['ye'] if form == 'ye' else ipa.get(unit['ipa'], '')
                        sentence.append(token(native, unit['zh']))
                if sentence:
                    docs[doc['id']].append(sentence)
    # Treat all reference grammar chapters as one source when counting support.
    for sentence in grammar_sentences(site):
        docs['northern-reference-grammar'].append([token(reading, gloss) for reading, gloss in sentence])
    return dict(docs)


def sentence_key(sentence):
    # Include readings of out-of-vocabulary tokens. Different unknown words must stay distinct.
    return tuple((reading, clean_gloss(gloss)) for reading, gloss, _ in sentence)


def training_documents(material, excluded=()):
    """Remove the demo sources and duplicate utterances, preserving unknown token positions."""
    excluded = set(excluded) | DEMO_TEXTS
    blocked = {sentence_key(sentence) for doc in excluded for sentence in material.get(doc, ())}
    result = {}
    for doc, sentences in material.items():
        if doc in excluded:
            continue
        kept = []
        for sentence in sentences:
            key = sentence_key(sentence)
            if key not in blocked:
                kept.append([token[2] for token in sentence])
                blocked.add(key)
        result[doc] = kept
    return result


def train(documents, classes):
    """Compile ranked rows. No test or shipped word frequencies enter fold training."""
    unigram, after_word, after_class, after_two = Counter(), Counter(), Counter(), Counter()
    word_docs, class_docs, two_docs = Counter(), Counter(), Counter()
    class_types = defaultdict(set)
    for sentences in documents.values():
        local_counts = [Counter() for _ in range(4)]
        uni, words, groups, triples = local_counts
        for sentence in sentences:
            left, previous = None, START
            for word in sentence:
                if word not in classes:
                    left, previous = None, None
                    continue
                uni[word] += 1
                if previous is not None:
                    words[previous, word] += 1
                    group = classes.get(previous)
                    if group:
                        groups[group, word] += 1
                        class_types[group, word].add(previous)
                if left and previous and left != START:
                    triples[left + ' ' + previous, word] += 1
                left, previous = previous, word
        # Long chapters or repetitions in a song must not dominate short independent texts.
        for local, total in zip(local_counts, (unigram, after_word, after_class, after_two)):
            total.update({key: min(count, 3) for key, count in local.items()})
        for local, support in ((words, word_docs), (groups, class_docs), (triples, two_docs)):
            support.update(local.keys())

    if not unigram:
        return MODEL_HEADER
    # A concrete pair/triple requires attestation in at least two documents. Class backoff
    # additionally requires two different preceding words of this class.
    def distributions(counts, supports, shared_types=None):
        out = defaultdict(dict)
        for key, count in counts.items():
            if supports[key] < 2 or (shared_types is not None and len(shared_types[key]) < 2):
                continue
            context, word = key
            out[context][word] = count
        return out

    words = distributions(after_word, word_docs)
    groups = distributions(after_class, class_docs, class_types)
    triples = distributions(after_two, two_docs)
    # Four pseudo-observations from the broader distribution smooth a sparse context.
    # Keep two lexical slots for general guesses; additional supported pairs can use all four.
    total = sum(unigram.values())
    base = {word: count / total for word, count in unigram.items()}
    def interpolate(prior, counts):
        if not counts:
            return prior
        denom = 4 + sum(counts.values())
        return {word: (4 * probability + counts.get(word, 0)) / denom for word, probability in prior.items()}

    lines = [MODEL_HEADER]
    for word, count in sorted(unigram.items()):
        lines.append(f'@prior\t{word}\t{count}\n')
    single = {}
    for context in sorted([START, *classes]):
        background = interpolate(base, groups.get(classes.get(context)))
        pairs = words.get(context, {})
        single[context] = interpolate(background, pairs)
        append_rank(lines, context, single[context], min(4, 2 + len(pairs)) if context != START else 2)
    for context, counts in sorted(triples.items()):
        last = context.split()[-1]
        ranked = interpolate(single[last], counts)
        append_rank(lines, context, ranked, min(4, 2 + len(words.get(last, {})) + len(counts)))
    return ''.join(lines)


def append_rank(lines, context, probabilities, limit):
    for word, score in sorted(probabilities.items(), key=lambda x: (-x[1], x[0]))[:limit]:
        lines.append(f'{context}\t{word}\t{max(1, round(score * 1_000_000_000))}\n')


def main():
    assets = ROOT / 'shared/bundle/Assets'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dictionary_site', type=Path)
    parser.add_argument('--words', type=Path, default=assets / 'words.tsv')
    parser.add_argument('--output', type=Path, default=assets / 'ngrams.tsv')
    parser.add_argument('--provenance', type=Path, default=assets / 'licenses/ngrams-source.json')
    args = parser.parse_args()
    site = args.dictionary_site
    material = read_material(site, args.words)
    documents = training_documents(material)
    data = train(documents, word_classes(site, args.words))
    args.output.write_text(data, encoding='utf-8')
    report = {
        'sourceUrl': 'https://tujia.biz',
        'sources': {file: hashlib.sha256((site / file).read_bytes()).hexdigest()
                    for file in ['annotated-books.json', 'longshan-texts.json', 'dictionary.json.gz', GRAMMAR_SOURCE]},
        'excludedDemoTexts': sorted(DEMO_TEXTS),
        'documents': len(documents),
        'sentences': sum(map(len, documents.values())),
        'linkedTokens': sum(id is not None for sentences in documents.values() for sentence in sentences for id in sentence),
        'rankedRows': len(data.splitlines()) - 1,
        'method': 'Document-capped counts, duplicate utterance removal, dictionary POS and interpolated word/word-class/unigram backoff. Word pairs and triples require two distinct documents. The compiled table holds ranked choices only.',
    }
    args.provenance.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))

if __name__ == '__main__':
    main()
