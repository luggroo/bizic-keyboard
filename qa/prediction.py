#!/usr/bin/env python3
"""Cross-validate the shipping next-word engine against whole held-out sources."""
import argparse
import csv
import hashlib
import io
import json
from collections import Counter, defaultdict
from itertools import chain
from dataclasses import dataclass
from pathlib import Path
from tempfile import TemporaryDirectory

from lexicon import ASSETS, ROOT, Model, library, rank, tsv, utf16
from import_ngrams import aligned_units, letters, lexemes_by_sense
from import_words import gloss_parts, load, native_forms, reviewed_glosses, spellings


@dataclass(frozen=True)
class Segment:
    doc: str
    group: str
    source: str
    block: str
    # A canonical site reading is retained for every token, including unknowns.
    forms: tuple
    ids: tuple
    aligned: bool
    initial: bool


def corpus(site, words_path):
    annotated = load(site / 'annotated-books.json')
    phonetics = spellings(site, [], annotated, {'words': []})['spellings']
    index = lexemes_by_sense(words_path)
    records, stats = [], Counter()
    for fname, texts, orth in [('annotated-books.json', annotated, 'ipa'),
                               ('longshan-texts.json', load(site / 'longshan-texts.json'), 'ye')]:
        for doc in texts:
            if doc['dialect'] != '北部':
                continue
            # Each complete numbered book chapter or original recording page is a material.
            # The snapshot does not identify individual speakers consistently.
            group = doc.get('externalUrl', '').partition('#')[0] or doc['id']
            previous_ending = True
            for i, block in enumerate(doc['blocks'], 1):
                if doc['id'] == 'tlp-longshan-stories-02-01':
                    block = dict(block, glosses=reviewed_glosses(block))
                initial = previous_ending
                previous_ending = block.get('zh', '').rstrip('”’)） ').endswith(('。', '？', '！', '.', '!', '?'))
                glosses = block.get('glosses', [])
                if not glosses:
                    stats['blocks_without_reference_words'] += 1
                    continue
                stats['reference_tokens'] += len(glosses)
                ref = str(block.get('id', i))
                def record(glosses, aligned, at_start=False):
                    forms, ids = [], []
                    for gloss in glosses:
                        form = native_forms(phonetics.get(gloss.get('ipa', ''), '') if orth == 'ipa'
                                            else gloss.get('ye', ''))
                        # Use the original reading for duplicate detection when no Ye form is available.
                        forms.append(form or 'IPA:' + gloss.get('ipa', ''))
                        matches = set().union(*(index.get((form, sense), ()) for sense in gloss_parts(gloss['zh'])))
                        if not aligned:
                            stats['unaligned_tokens'] += 1
                        elif len(matches) == 1:
                            stats['uniquely_linked_tokens'] += 1
                        elif matches:
                            stats['ambiguous_tokens'] += 1
                        else:
                            stats['unlinked_tokens'] += 1
                        ids.append(next(iter(matches)) if aligned and len(matches) == 1 else '')
                    if forms:
                        records.append(Segment(doc['id'], group, fname, ref, tuple(forms), tuple(ids), aligned, at_start))
                # Extraction may mask a gloss containing punctuation. Count it independently as
                # an unaligned miss; preserve every original token exactly once.
                units = aligned_units(block, orth)
                if not units:
                    stats['unaligned_blocks'] += 1
                    record(glosses, False, initial)
                    continue
                spans, indices = [], {id(g): j for j, g in enumerate(glosses)}
                seen = set()
                offset = 0
                ended = set()
                for ch in block.get(orth, ''):
                    if ch in '。！？.!?\n':
                        ended.add(offset)
                    offset += len(letters(ch))
                offsets, offset = {}, 0
                for gloss in glosses:
                    offsets[id(gloss)] = offset
                    offset += len(letters(gloss[orth]))
                for unit in [*units, None]:
                    if unit is not None:
                        seen.add(indices[id(unit)])
                        spans.append(unit)
                    elif spans:
                        start = offsets[id(spans[0])]
                        record(spans, True, (start == 0 and initial) or start in ended)
                        spans = []
                for j, gloss in enumerate(glosses):
                    if j not in seen:
                        record([gloss], False)
    from prediction_model import GRAMMAR_SOURCE, grammar_sentences
    for i, sentence in enumerate(grammar_sentences(site), 1):
        forms, ids = [], []
        for raw, gloss in sentence:
            form = native_forms(raw.strip('[]'))
            matches = set().union(*(index.get((form, sense), ()) for sense in gloss_parts(gloss)))
            forms.append(form)
            ids.append(next(iter(matches)) if len(matches) == 1 else '')
            stats['reference_tokens'] += 1
            key = 'uniquely_linked_tokens' if len(matches) == 1 else 'ambiguous_tokens' if matches else 'unlinked_tokens'
            stats[key] += 1
        records.append(Segment('northern-reference-grammar', 'northern-reference-grammar',
                               GRAMMAR_SOURCE, str(i), tuple(forms), tuple(ids), True, True))
    assert sum(len(s.ids) for s in records) == stats['reference_tokens']
    return records, stats


def blocked_fragments(records):
    """Forbid whole utterances and four-token windows shared with a held source."""
    whole = {s.forms for s in records}
    spans = {f[i:i+4] for f in whole for i in range(len(f)-3)}
    short = {f for f in whole if 2 <= len(f) < 4}
    return whole, spans | short


def overlaps(forms, blocked):
    whole, fragments = blocked
    return forms in whole or any(forms[i:i+n] in fragments
                                 for n in (2, 3, 4) for i in range(len(forms)-n+1))


def split(records, held):
    def held_out(r):
        return r.group == held or r.source == held
    test = [r for r in records if held_out(r)]
    forbidden = blocked_fragments(test)
    training, repeats = [], set()
    purged = 0
    for r in records:
        if held_out(r) or not r.aligned:
            continue
        if r.forms in repeats or overlaps(r.forms, forbidden):
            purged += 1
            continue
        training.append(r)
        repeats.add(r.forms)
    assert not any(overlaps(r.forms, forbidden) for r in training)
    return training, test, purged


def fold_words(rows, training_counts):
    out = io.StringIO()
    writer = csv.DictWriter(out, fieldnames=rows[0].keys(), delimiter='\t', lineterminator='\n')
    writer.writeheader()
    for r in rows:
        if r['kind'] in ('tujia', 'grammar', 'phrase'):
            r = dict(r, frequency=training_counts[r['id']])
        writer.writerow(r)
    return out.getvalue().encode()


def strict_bigrams(training):
    counts = Counter()
    for seg in training:
        history = []
        for word in seg.ids:
            if not word:
                history = []
                continue
            for n in range(1, len(history) + 1):
                counts[' '.join(history[-n:]), word] += 1
            history = (history + [word])[-2:]
    return ('context\tnext\tcount\texample\n' + ''.join(
        f'{ctx}\t{word}\t{count}\tfold\n' for (ctx,word),count in sorted(counts.items())
    )).encode()


def lexical_cards(items):
    seen = set()
    for item in items:
        key = item.text, item.gloss
        if key not in seen:
            yield item
            seen.add(key)


def han_cards(items):
    """One cell per written form; a tap cannot confirm one of several lexical IDs."""
    forms = {}
    recommendations = {}
    for item in items:
        if item.han_sense:
            surface = recommendations.setdefault((item.consumed, item.han_sense), item.text)
            if surface != item.text:
                continue
        forms.setdefault(item.text, {}).setdefault(item.consumed, []).append(item)
    cards = []
    for boundaries in forms.values():
        action = boundaries[max(boundaries)]
        senses = {c.ids for c in action if not c.syllable_only or (c.ids and c.short)}
        sounds = {c.last_syllable for c in action}
        cards.append(action[0]._replace(ids=next(iter(senses)) if len(senses) == 1 else '',
                                        last_syllable=next(iter(sounds)) if len(sounds) == 1 else ''))
    return cards


class Results:
    def __init__(self):
        self.counts = Counter()

    def event(self, position, manual, cold, unknown, prefix=None):
        c = self.counts
        c['tokens'] += 1
        c['cold'] += cold
        c['unknown'] += unknown
        c['top1'] += position == 1
        c['top4'] += 0 < position <= 4
        if manual is not None:
            c['comparable'] += 1
            c['manual_keys'] += manual
            c['with_prediction'] += 1 if 0 < position <= 4 else manual
            if prefix is not None:
                c['prefix_only_keys'] += prefix
                c['with_prediction_and_prefix'] += 1 if 0 < position <= 4 else prefix


def totals():
    return defaultdict(Results)


DEMO_DOCS = {'xu-tujia-annotated-2-13', 'tlp-longshan-stories-02-01'}


def score_fold(engine, label, test, by_id, all_scores, freq=None, slices=None, seen_pairs=frozenset(), traces=None, warm=False):
    """One successful submission of the intended dictionary ID advances lexical history."""
    # Fixed lexical output in this study is recommended Han, with strict ID/sense.
    recent, last_doc = [], ''
    for seg in test:
        if not warm or seg.doc != last_doc or not seg.initial:
            recent = []
        if seg.initial:
            recent = (recent + ['<s>'])[-32:]
        last_doc = seg.doc
        history = []
        last_sound = '<s>' if seg.initial else ''
        for i, target in enumerate(seg.ids):
            start = i == 0 and seg.initial
            key = ' '.join(recent) if warm else ' '.join(history) or ('<s>' if start else '')
            raw_words = engine.choices(key, op='next_words') if key and freq is None else []
            word_cards = (freq if freq is not None else han_cards(raw_words)) if key else []
            word_ids = [c if isinstance(c, str) else c.ids for c in word_cards][:4]
            for c in word_cards[:4]:
                if not isinstance(c, str) and c.ids and (c.text != by_id[c.ids]['recommended']
                                               or c.gloss != by_id[c.ids]['meaning']):
                    raise AssertionError(f'Predicted Han output does not identify its dictionary word: {c}')
            direct_ids = list(word_ids)
            if freq is None and last_sound:
                screen = han_cards(chain(raw_words, engine.choices(last_sound, op='next_syllables')))
                direct_ids = [c.ids if c.ids and c.text == by_id[c.ids]['recommended'] else '' for c in screen[:4]]
            elif len(direct_ids) < 4 and last_sound:
                visible = {by_id[c]['recommended'] if isinstance(c, str) else c.text for c in word_cards[:4]}
                for candidate in engine.choices(last_sound, op='next_syllables'):
                    if candidate.text in visible:
                        continue
                    visible.add(candidate.text)
                    exact_id = candidate.ids if candidate.ids and candidate.text == by_id[candidate.ids]['recommended'] else ''
                    direct_ids.append(exact_id)
                    if len(direct_ids) == 4:
                        break
            if label == 'statistical_warm' and target:
                screen_text = [c.text for c in (screen if last_sound else word_cards)[:4]]
                score = all_scores[label, 'screen'].counts
                score['predicted_surface_top4'] += by_id[target]['recommended'] in screen_text
            manual = prefix_cost = None
            typed_surface = None
            chosen_sound = ''
            if target:
                row = by_id[target]
                query = row['plain']
                exact = [c for c in engine.choices(query) if c.kind == 0 and not c.syllable_only
                         and c.consumed == utf16(query)]
                typed = han_cards(exact)
                pos = rank(typed, lambda c: c.ids == target and c.consumed == utf16(query)
                           and c.text == row['recommended'] and c.gloss == row['meaning'])
                typed_surface = next((c for c in typed if c.text == row['recommended']), None)
                if label == 'statistical_warm' and query.isascii() and query.isalpha():
                    score = all_scores[label, 'screen'].counts
                    output = typed_surface
                    eligible = any(c.ids == target and c.text == row['recommended'] for c in exact)
                    score['manual_exact_raw_id'] += eligible
                    score['manual_exact_surface'] += bool(output)
                    if eligible and not pos:
                        if output is None or output.ids:
                            raise AssertionError(f'Grouped card lost an unambiguous exact target: {row["id"]}')
                        score['manual_raw_to_ambiguous'] += 1
                if pos and query.isascii() and query.isalpha():
                    # Each "next page" advances four cards. The final tap commits the word.
                    manual = len(query) + 1 + (pos - 1) // 4
                    chosen_sound = typed[pos - 1].last_syllable
                    if label.startswith('statistical'):
                        prefix_cost = manual
                        for length in range(1, len(query)):
                            prefix = query[:length]
                            exact = [c for c in engine.choices(prefix) if c.kind == 0 and not c.syllable_only
                                     and c.consumed == utf16(prefix)]
                            completes = engine.choices(prefix, op='spelling_completions', history=key)
                            first = han_cards(chain(exact, completes))[:4]
                            if any(c.ids == target for c in first):
                                prefix_cost = length + 1
                                break
            if traces is not None:
                traces.setdefault(seg.block, []).append({
                    'context': key, 'target': target, 'manual_taps': manual,
                    'word_ids': word_ids, 'screen_ids': direct_ids,
                    'word_rank': word_ids.index(target) + 1 if target and target in word_ids else 0,
                    'screen_rank': direct_ids.index(target) + 1 if target and target in direct_ids else 0,
                })
            for view, ids in [('words', word_ids), ('screen', direct_ids)]:
                pos = ids.index(target) + 1 if target and target in ids else 0
                all_scores[label, view].event(pos, manual, not history, not target, prefix_cost)
                if slices is not None:
                    unseen_pair = bool(history and target and (history[-1], target) not in seen_pairs)
                    for part, enabled in [('known',bool(target)), ('unseen_pair',unseen_pair), ('oov_or_uncertain',not target),
                                          ('cold',not history), ('start',start), ('history',bool(history))]:
                        if enabled:
                            slices[label, view, part].event(pos, manual, not history, not target, prefix_cost)
            if target and (manual is not None or target in direct_ids):
                # A card link is required to carry an ID. Unavailable targets reset
                # history rather than supplying the reference answer to the model.
                history = (history + [target])[-2:]
                recent = (recent + [target])[-32:]
                last_sound = chosen_sound or next((c.last_syllable for c in word_cards if not isinstance(c,str) and c.ids == target), '')
            else:
                recent = []
                history = []
                # A merged card can supply one agreed pronunciation even with no sense ID.
                last_sound = typed_surface.last_syllable if typed_surface and not typed_surface.ids else ''


def spelling_screen(engine, test, by_id):
    """Replay in Ye output mode. An equal spelling can be useful without a known sense."""
    result = Counter()
    recent, last_doc = [], ''
    for segment in test:
        if segment.doc != last_doc or not segment.initial:
            recent = []
        if segment.initial:
            recent = (recent + ['<s>'])[-32:]
        sound = '<s>' if segment.initial else ''
        last_doc = segment.doc
        for form, target in zip(segment.forms, segment.ids):
            result['tokens'] += 1
            if not segment.aligned or not form or form.startswith('IPA:'):
                recent, sound = [], ''
                continue
            candidates = list(lexical_cards(engine.choices(' '.join(recent), mode=0, op='next_words'))) if recent else []
            displayed = {c.text for c in candidates}
            if len(candidates) < 4 and sound:
                for item in engine.choices(sound, mode=0, op='next_syllables'):
                    if item.text not in displayed:
                        displayed.add(item.text)
                        candidates.append(item)
                    if len(candidates) == 4:
                        break
            page = candidates[:4]
            position = rank(page, lambda c: native_forms(c.text) == form)
            selected = None
            if position:
                selected = page[position-1]
                result['top1'] += position == 1
                result['top4'] += 1
                result['syllable_card'] += selected.syllable_only
                result['unlinked_reference'] += not target
                # Surface twins can be distinct lexical items; check ID independently.
                result['confirmed_target_sense'] += bool(target and selected.ids == target)
                if selected.syllable_only and not selected.short:
                    result['bare_syllable'] += 1
            else:
                # Confirm a matching lexical ID through ordinary input when available.
                # Otherwise accept an orthographic syllable or submit the original spelling.
                query = by_id[target]['plain'] if target else form
                choices = engine.choices(query, mode=0)
                valid = [c for c in choices if c.consumed == utf16(query) and native_forms(c.text) == form]
                selected = next((c for c in valid if target and c.ids == target), None)
                if selected is None:
                    selected = next((c for c in valid if c.syllable_only), None)
            recent = (recent + [selected.ids])[-32:] if selected and selected.ids else []
            sound = selected.last_syllable if selected else ''
    return result


def document_ids(training):
    docs = defaultdict(list)
    for r in training:
        docs[r.doc].append([w or None for w in r.ids])
    return dict(docs)


def sample_cases(by_id):
    cases = []
    for i, r in enumerate(tsv(Path(__file__).with_name('prediction-holdouts.tsv'))):
        ids = tuple(r['ids'].split())
        forms = tuple(native_forms(by_id[t]['word']) for t in ids)
        cases.append(Segment('QA-' + str(i+1), 'QA', '人工重组', str(i + 1), forms, ids, True, True))
    return cases


def summary(scores):
    return {':'.join(k): dict(v.counts) for k, v in sorted(scores.items())}


def run(site, output):
    from prediction_model import read_material, train, training_documents, word_classes
    words_path = ASSETS / 'words.tsv'
    rows = tsv(words_path)
    by_id = {r['id']: r for r in rows if r['kind'] in ('tujia', 'grammar', 'phrase')}
    records, stats = corpus(site, words_path)
    cases = sample_cases(by_id)
    demo_fragments = blocked_fragments(s for s in records if s.doc in DEMO_DOCS)
    classes = word_classes(site, words_path)
    shipped = ASSETS / 'ngrams.tsv'
    full_training = training_documents(read_material(site, words_path))
    if train(full_training, classes).encode() != shipped.read_bytes():
        raise ValueError('The installed prediction asset differs from the model built by this training program')
    sounds = (ASSETS / 'syllable_bigrams.tsv').read_bytes()
    senses = (ASSETS / 'syllable_senses.tsv').read_bytes()
    totals_by_mode, by_slice, demos, cross_collection = totals(), totals(), totals(), totals()
    details, traces = {}, {}
    spelling_totals = defaultdict(Counter)
    with TemporaryDirectory(prefix='tujia-prediction-qa-') as temp:
        dll = library(Path(temp) / 'prediction.so')
        sources = {s.source for s in records}
        groups = sorted({s.group for s in records}) + sorted(sources) + ['QA']
        for group in groups:
            samples = records + cases if group == 'QA' else records
            train_segments, test, excluded = split(samples, group)
            train_segments = [s for s in train_segments if s.doc not in DEMO_DOCS and
                              not overlaps(s.forms, demo_fragments)]
            counts = Counter(w for s in train_segments for w in s.ids if w)
            words = fold_words(rows, counts)
            docs = document_ids(train_segments)
            grams = train(docs, classes).encode()
            folded = totals()
            fold_slices = totals()
            pairs = {(a, b) for s in train_segments for a, b in zip(s.ids, s.ids[1:]) if a and b}
            with Model(dll, words, strict_bigrams(train_segments), sounds, senses) as legacy:
                score_fold(legacy, 'attested', test, by_id, folded, slices=fold_slices, seen_pairs=pairs)
                frequent = sorted(counts, key=lambda w: (-counts[w], w))
                seen = set()
                common = []
                for w in frequent:
                    visible = by_id[w]['recommended']
                    if visible not in seen:
                        common.append(w)
                        seen.add(visible)
                    if len(common) == 4:
                        break
                score_fold(legacy, 'frequency', test, by_id, folded, freq=common, slices=fold_slices, seen_pairs=pairs)
            with Model(dll, words, grams, sounds, senses) as trained:
                score_fold(trained, 'statistical', test, by_id, folded, slices=fold_slices, seen_pairs=pairs,
                           traces=traces if group == 'QA' else None)
                score_fold(trained, 'statistical_warm', test, by_id, folded, slices=fold_slices, seen_pairs=pairs, warm=True)
                spelling = spelling_screen(trained, test, by_id)
            study = 'everyday' if group == 'QA' else 'collections' if group in sources else 'documents'
            spelling_totals[study].update(spelling)
            total = demos if group == 'QA' else cross_collection if group in sources else totals_by_mode
            for k,v in folded.items():
                total[k].counts.update(v.counts)
            if group not in sources and group != 'QA':
                for k,v in fold_slices.items():
                    by_slice[k].counts.update(v.counts)
            details[group] = {'spelling_screen':dict(spelling), 'purged_repeated_segments': excluded, 'training_segments': len(train_segments),
                              'training_tokens': sum(counts.values()), 'test_segments': len(test),
                              'test_tokens': sum(len(s.ids) for s in test), 'scores': summary(folded)}
    assert all(r.counts['tokens'] == stats['reference_tokens'] for r in totals_by_mode.values())
    inputs = [words_path, shipped, ASSETS/'syllable_bigrams.tsv', ASSETS/'syllable_senses.tsv',
              ROOT/'shared/src/lexicon.cpp', ROOT/'tools/prediction_model.py',
              Path(__file__).resolve(), Path(__file__).with_name('prediction-holdouts.tsv')]
    result = {'build_inputs': {str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in inputs},
              'sources': {f:hashlib.sha256((site / f).read_bytes()).hexdigest() for f in
                         ('annotated-books.json','longshan-texts.json','grammar-reviewed-data.js','dictionary.json.gz')}, 'coverage': dict(stats),
              'spelling_screen': dict(spelling_totals), 'metrics': summary(totals_by_mode), 'slices': summary(by_slice),
              'everyday_recombinations': summary(demos), 'everyday_cases': traces, 'collection_holdouts': summary(cross_collection), 'folds': details}
    output.write_text(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2) + '\n')
    return result



def write_report(path, result):
    measures = result['metrics']
    baseline = measures['attested:screen']
    warm = measures['statistical_warm:screen']
    labels = [('attested', '仅回退到已见邻接'), ('frequency', '训练词频前四'),
              ('statistical', '统计模型，最多两个前词'), ('statistical_warm', '统计模型，最多 32 个已确认词')]
    def pct(n, d):
        return f'{n:,}（{n/d:.2%}）'
    def row(label, r):
        return '| ' + ' | '.join([label, pct(r['top1'],r['tokens']), pct(r['top4'],r['tokens']),
                                   f"{r['with_prediction']:,}", f"{r['manual_keys'] - r['with_prediction']:,}"]) + ' |'
    n = result['coverage']
    report = ['# 下一词能猜中多少，能省几次点按',
              f"在 {warm['tokens']:,} 个留出词位上，以最多 32 个已确认词作上下文，四张可见候选卡共命中 {warm['top4']} 个（{warm['top4']/warm['tokens']:.2%}）。旧邻接模型命中 {baseline['top4']} 个。按整套来源留出时，单纯按词频排序的前四命中数更高。",
              '## 整篇留出',
              f"每次留出一份完整材料，共 19 折。无调全拼能按目标义项选到原词的有 {warm['comparable']:,} 个词位，共需 {warm['manual_keys']:,} 键。下表的命中率分母包含全部 {warm['tokens']:,} 个词位。按键数只在可以实际查到目标词的共同子集上比较。",
              f"无调全拼能在完整词候选中写出目标记音字的有 {warm['manual_exact_surface']:,} 个词位。合并前，后台返回的词义记录中有 {warm['manual_exact_raw_id']:,} 个词位可以落到目标 ID；其中 {warm['manual_raw_to_ambiguous']:,} 个在合并后能写对字，但一次点选不能判定具体词义。首屏下一词只比字形可写中 {warm['predicted_surface_top4']} 次。字形比较限于已对齐且有推荐记音字的参考词，覆盖率分母仍保留其他词位。",
              '| 候选排序 | 首位命中 | 首屏四位命中 | 使用下一词所需键数 | 比全拼省键 |\n|---|---:|---:|---:|---:|\n'
              + '\n'.join(row(label, measures[key+':screen']) for key,label in labels),
              f"对同一批可输入的词位，逐字输入拼写前缀，再选择首屏中的目标，需要 {warm['prefix_only_keys']:,} 键。先看下一词、没有命中再输入前缀，需要 {warm['with_prediction_and_prefix']:,} 键。和全拼相比合计省 {warm['manual_keys'] - warm['with_prediction_and_prefix']:,} 键（{1-warm['with_prediction_and_prefix']/warm['manual_keys']:.1%}）。这是按给定选词策略计算的界面动作数，未计量阅读、判断、打错和纠错的耗时。",
              '## 跨来源和新搭配',
              '整套留出将同一套书、同一批故事录音或整份参考语法全部移出训练。表中只列首屏命中数。',
              '| 测试来源 | 全部词位 | 旧邻接 | 训练词频 | 统计模型 | 含短期历史 |\n|---|---:|---:|---:|---:|---:|\n'
              + '\n'.join('| ' + ' | '.join([name, str(result['folds'][src]['test_tokens'])] + [
                str(result['folds'][src]['scores'][key+':screen']['top4']) for key,_ in labels]) + ' |' for src,name in
                           [('annotated-books.json','徐氏文献整套'),('longshan-texts.json','龙山故事录音整批'),
                            ('grammar-reviewed-data.js','参考语法整份')])]
    novel = {k:result['slices'][k+':screen:unseen_pair'] for k,_ in labels}
    art = result['everyday_recombinations']
    cold = result['slices']['statistical_warm:screen:cold']
    extra_sound = warm['top4'] - measures['statistical_warm:words']['top4']
    spelling = result['spelling_screen']['documents']
    report += [f"训练集中从未见过、但前后词均已识别的相邻搭配共 {novel['attested']['tokens']} 次。旧邻接首屏命中 {novel['attested']['top4']} 次，训练词频 {novel['frequency']['top4']} 次，统计模型 {novel['statistical']['top4']} 次，含短期历史 {novel['statistical_warm']['top4']} 次。共有 {cold['tokens']:,} 个位置缺少可沿用的前词，含短期历史在这些位置首屏命中 {cold['top4']} 次。",
               f"另外预先固定 8 个日用词序组合，单独阻断整段相同的训练材料。24 个词位中，旧邻接、训练词频、统计模型和含短期历史的首屏命中分别为 " + '、'.join(str(art[k+':screen']['top4']) for k,_ in labels) + '。例如「我·茶·喝」中，句首能选到「我」，「茶」和「喝」仍需输入。八组均为人工组合，尚无母语者对自然度的评定。[测试词序](prediction-holdouts.tsv)',
                '## 拼写模式的裸音节',
               f"单独以叶氏拼写重放同一批 {spelling['tokens']:,} 个词位。首位有 {spelling['top1']} 次音串吻合，首屏有 {spelling['top4']} 次。首屏命中中有 {spelling['syllable_card']} 次点的是音节卡，其中 {spelling['bare_syllable']} 次屏上只需显示读音。命中首屏拼写的词位里有 {spelling['confirmed_target_sense']} 次同时能确认目标词义 ID。这个专项允许用户直接点出同读音的输出，按站点叶氏读音忽略词内分隔符比对。各折都使用同一张 1,000 对音节的参考表。原表未标注每对来自哪篇材料，因此这 {spelling['top4']} 次首屏命中可能包含音节表与留出篇目的重叠。",
               '## 数据、隔离与计分',
               f"14 章徐氏文献、4 份龙山故事和 1 份北部参考语法构成 19 组。{n['uniquely_linked_tokens']:,} 个词位与当前词表的发音和义项唯一对应，{n['unlinked_tokens']:,} 个未对应，{n.get('ambiguous_tokens', 0)} 个仍有多个可能词义，{n['unaligned_tokens']} 个音文未对齐。后面三类都按未命中计数，并清除可续用词史。另有 {n['blocks_without_reference_words']} 个块没有逐词参考，只记录块数。原始材料缺少可验证的说话人标识，因此无法据此判断对新说话人的表现。",
               '每折重新计算词频、邻接和词类统计。整篇测试文献、两个演示文献及其重复材料全部隔离。训练侧删除相同整段、包含测试短句的段落及与测试共享连续四个读音的段落。同折训练段再次去重。词典读音、对义、词类和推荐汉字作为产品固定词表使用。普通话后备频率沿用原表。人工重组另算，不参与主结果。每一折的训练数、删除数和输入文件摘要在机器可读结果中。',
               f'通过与安卓键盘相同的 C ABI 请求候选。空白句首从句界标记开始，断开的未知词史使用空上下文。短期排序按原文从左到右，只读已确认的词，在编辑材料或未知词切换时清除。统计模型的另一列最多读取两个前词。同写法归为一格。计分要求点击该格能写出目标字形并确认词义 ID；同一格含多个不同词义时，仍能写字，但不计为命中某个特定义项，也不把其中任一义项当成已确认词史。若同格各义的末音一致，仍可用这个音节接下一个音。补入音节卡后，首屏多命中 {extra_sound} 个可确认的词义。单音节表音还可帮助书写词库外的字。',
               '全拼路径逐个点击无调字母、每四项计一次翻页，再点目标词。首屏预测命中需点一次。前缀补全逐键读取实际候选，完整匹配优先于补全，首屏出现正确词才允许停。每个未知、无法消歧或无法用标准无调拼写选到的词保留在命中分母，但缺少可比较的录入成本。',
               '## 复现',
               '在仓库根目录使用 Python 3、Node.js、C++17 编译器和毕兹学堂网站快照运行。',
               '```sh\npython3 qa/prediction.py /workspace/bizic-dictionary/site\n```',
               '[分折结果及文件校验值](results/prediction.json) · [模型训练器](../tools/prediction_model.py)']
    path.write_text('\n\n'.join(report) + '\n')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('site', type=Path, help='local checkout of bizic-dictionary/site')
    p.add_argument('--output', type=Path, default=ROOT / 'qa/results/prediction.json')
    p.add_argument('--report', type=Path, default=ROOT / 'qa/prediction.md')
    args = p.parse_args()
    report = run(args.site, args.output)
    write_report(args.report, report)
    print(args.report)
