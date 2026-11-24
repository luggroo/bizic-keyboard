#!/usr/bin/env python3
"""Measure native candidates, real segmented lyrics and whole-document held-out predictions."""
import argparse
import csv
import ctypes as C
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict, namedtuple
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'shared/bundle/Assets'
sys.path.insert(0, str(ROOT / 'tools'))
from import_ngrams import aligned_units, lexemes_by_sense
from import_words import gloss_parts, load, native_forms, spellings

Item = namedtuple('Item', 'ids text gloss short consumed kind last_syllable syllable_only plain_han_loan')


def tsv(path):
    with path.open(encoding='utf-8') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def utf16(s):
    return len(s.encode('utf-16le')) // 2


def library(target):
    files = [ROOT / 'shared/src' / f'{name}.cpp' for name in ('lexicon', 'strokes', 'tujia_core', 'edit')]
    subprocess.run([os.environ.get('CXX', 'c++'), '-std=c++17', '-O2', '-shared', '-fPIC',
                    '-I' + str(ROOT / 'shared/include'), *map(str, files), '-o', str(target)], check=True)
    dll = C.CDLL(str(target))
    ptr, string, size, num = C.c_void_p, C.c_char_p, C.c_size_t, C.c_int32
    def declare(name, args, result):
        func = getattr(dll, 'tji_' + name)
        func.argtypes, func.restype = args, result
    declare('words_open_with_ngrams', [string, size, string, size], ptr)
    declare('words_open_full', [string, size, string, size, string, size], ptr)
    declare('words_open_with_senses', [string, size] * 4, ptr)
    declare('words_close', [ptr], None)
    declare('spelling_completions', [ptr, string, string, num, num], ptr)
    declare('next_syllables', [ptr, string, num], ptr)
    for name in ('lookup_with_orthography', 'handwriting_suggestions', 'next_words', 'next_syllables_for_mode'):
        declare(name, [ptr, string, num, num], ptr)
    declare('choices_count', [ptr], size)
    for field in ('text', 'meaning', 'short_meaning', 'lexeme_ids', 'last_syllable'):
        declare('choice_' + field, [ptr, size], string)
    for field in ('consumed_utf16', 'kind', 'is_syllable', 'plain_han_loan'):
        declare('choice_' + field, [ptr, size], num)
    declare('choices_close', [ptr], None)
    declare('suffix_after_choice', [string, num], ptr)
    declare('text_free', [ptr], None)
    return dll


class Model:
    def __init__(self, dll, words, grams, sounds=b'', senses=b''):
        self.dll = dll
        self.handle = dll.tji_words_open_with_senses(words, len(words), grams, len(grams), sounds, len(sounds), senses, len(senses))
        if not self.handle:
            raise RuntimeError('Words and ngrams did not load together')
        self.cache = {}

    def choices(self, query, mode=1, style=0, op='lookup_with_orthography', cache=True, history=''):
        key = (query, mode, style, op, history)
        if key in self.cache:
            return self.cache[key]
        d = self.dll
        if op == 'spelling_completions':
            choices = d.tji_spelling_completions(self.handle, query.encode(), history.encode(), mode, style)
        elif op == 'next_syllables':
            choices = d.tji_next_syllables_for_mode(self.handle, query.encode(), mode, style)
        else:
            choices = getattr(d, 'tji_' + op)(self.handle, query.encode(), mode, style)
        try:
            items = [Item(d.tji_choice_lexeme_ids(choices, i).decode(), d.tji_choice_text(choices, i).decode(),
                          d.tji_choice_meaning(choices, i).decode(), d.tji_choice_short_meaning(choices, i).decode(),
                          d.tji_choice_consumed_utf16(choices, i), d.tji_choice_kind(choices, i),
                          d.tji_choice_last_syllable(choices, i).decode(), bool(d.tji_choice_is_syllable(choices, i)),
                          bool(d.tji_choice_plain_han_loan(choices, i)))
                     for i in range(d.tji_choices_count(choices))]
        finally:
            d.tji_choices_close(choices)
        if cache:
            self.cache[key] = items
        return items

    def suffix(self, draft, count):
        value = self.dll.tji_suffix_after_choice(draft.encode(), count)
        try:
            return C.string_at(value).decode()
        finally:
            self.dll.tji_text_free(value)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.dll.tji_words_close(self.handle)


class Scores:
    def __init__(self):
        self.rows = defaultdict(Counter)
        self.examples = {}

    def add(self, label, rank, detail=None):
        c = self.rows[label]
        c['n'] += 1
        for k in (1, 3, float('inf')):
            if rank and rank <= k:
                c[k] += 1
        if rank != 1 and detail and label not in self.examples:
            self.examples[label] = detail

    def table(self, labels):
        result = ['| 输入任务 | 样本数 | 首位 | 前三位 | 全部候选 |', '|---|---:|---:|---:|---:|']
        for key in labels:
            c = self.rows[key]
            cells = [f"{c[k]}/{c['n']}（{c[k]/c['n']:.1%}）" for k in (1, 3, float('inf'))]
            result.append('| ' + ' | '.join([key, str(c['n']), *cells]) + ' |')
        return '\n'.join(result)


def rank(items, predicate):
    return next((i for i, item in enumerate(items, start=1) if predicate(item)), 0)


def visible(item, row, text, consumed, han=True):
    loan = han and row.get('presentation') == 'plainHanLoan'
    short = '' if loan else row['shortGloss'] or row['meaning']
    return (item.text == text and item.gloss == row['meaning'] and item.short == short and item.plain_han_loan == loan
            and item.kind == 0 and item.consumed == consumed)


def expect(item, row, text, consumed, han=True):
    return item.ids == row['id'] and visible(item, row, text, consumed, han)


def source_forms_by_sense(rows):
    forms = defaultdict(list)
    for row in rows:
        if row['kind'] == 'tujia':
            forms[row['plain'], row['meaning']].append(row)
    return forms


def visible_untoned(item, row, source_forms, consumed):
    return any(visible(item, equivalent, equivalent['recommended'], consumed)
               for equivalent in source_forms[row['plain'], row['meaning']])


def lexical(model, rows, scores):
    plain = Counter(r['plain'] for r in rows)
    han = Counter(r['recommended'] for r in rows)
    forms = source_forms_by_sense(rows)
    labels = ['无调→推荐汉字', '调号→推荐汉字', '叶氏→推荐汉字', '无调→调号拼写', '无调→叶氏拼写',
              '推荐汉字→调号拼写', '推荐汉字→叶氏拼写', '同无调拼写的不同词义', '同推荐汉字的不同词义',
              '原文罗马字别名→推荐汉字', '整词汉字旧写法→推荐汉字', '规范字形的真前缀→整词']
    for r in rows:
        def measure(label, query, output='recommended', mode=1, style=0, op='lookup_with_orthography'):
            items = model.choices(query, mode, style, op)
            found = rank(items, lambda x: expect(x, r, r[output], utf16(query), han=mode == 1))
            scores.add(label, found, (query, r[output], r['shortGloss'], items[0].text if items else '', found))
        for form, label in zip(('plain','diacritic','word'), labels):
            measure(label, r[form])
        measure(labels[3], r['plain'], 'diacritic', 0, 1)
        measure(labels[4], r['plain'], 'word', 0)
        measure(labels[5], r['recommended'], 'diacritic', 2, 1)
        measure(labels[6], r['recommended'], 'word', 2)
        if plain[r['plain']] > 1:
            measure(labels[7], r['plain'])
        if han[r['recommended']] > 1:
            measure(labels[8], r['recommended'])
        for alias in set(r['romanAliases'].split()):
            measure(labels[9], alias)
        for alias in set(r['aliases'].split()) - {r['recommended']}:
            measure(labels[10], alias)
        for end in range(1,len(r['recommended'])):
            query = r['recommended'][:end]
            if not any(x.ids == r['id'] for x in model.choices(query, 2)):
                measure(labels[11], query, op='handwriting_suggestions')
        for label, query in [('无调→推荐汉字（可见结果）', r['plain']), ('同形汉字→推荐写法（可见结果）', r['recommended'])]:
            if label.startswith('同形') and han[r['recommended']] <= 1:
                continue
            if label.startswith('无调'):
                pos = rank(model.choices(query), lambda x: visible_untoned(x, r, forms, utf16(query)))
            else:
                pos = rank(model.choices(query), lambda x: visible(x, r, r['recommended'], utf16(query)))
            scores.add(label,pos)
    return labels + ['无调→推荐汉字（可见结果）', '同形汉字→推荐写法（可见结果）']


def song(model, rows, scores):
    words = {r['id']:r for r in rows}
    lines, current = [], []
    for row in tsv(ROOT/'tools/story-demo/shange-spelling.tsv'):
        current.append(row)
        if row['after']:
            lines.append(current)
            current = []
    assert len(lines)==4 and not current
    for line in lines:
        for form in ('plain','diacritic','ye'):
            keys = ''.join(t[form] for t in line)
            target = ''.join(t['han'] for t in line)
            ids = ' '.join(t['id'] for t in line)
            full = model.choices(keys)
            scores.add('山歌整行一次选择·'+form, rank(full, lambda x: x.ids == ids and x.text == target and x.consumed == utf16(keys)))
            suffix = keys
            committed = ''
            correct = True
            for tok in line:
                oracle = words[tok['id']]
                assert oracle['meaning'] == tok['gloss'] and oracle['recommended'] == tok['han']
                single = model.choices(tok[form])
                remaining = model.choices(suffix)
                test = lambda x: expect(x, oracle, tok['han'], utf16(tok[form]))
                scores.add('山歌单词·'+form, rank(single,test))
                k = rank(remaining,test)
                scores.add('山歌整行预编辑后分词·'+form,k)
                if k:
                    choice = remaining[k-1]
                    actual = model.suffix(suffix,choice.consumed)
                    correct &= actual == suffix[len(tok[form]):]
                    committed += choice.text
                else:
                    correct = False
                suffix = suffix[len(tok[form]):]
            scores.add('逐词选择后整行正确·'+form,1 if correct and committed == target and not suffix else 0)
    return lines


def corpus_documents(site, words_path):
    lookup = lexemes_by_sense(words_path)
    annotated = load(site/'annotated-books.json')
    ipa = spellings(site, [], annotated, {'words':[]})['spellings']
    docs = defaultdict(list)
    mapped_tokens = aligned_tokens = all_pairs = covered_pairs = 0
    for source, form in ((annotated,'ipa'), (load(site/'longshan-texts.json'),'ye')):
        for doc in source:
            if doc['dialect'] != '北部':
                continue
            for block in doc['blocks']:
                history = []
                prior_unit = False
                for gloss in aligned_units(block,form):
                    if gloss is None:
                        history.clear()
                        prior_unit = False
                        continue
                    aligned_tokens += 1
                    all_pairs += prior_unit
                    prior_unit=True
                    reading = ipa.get(gloss['ipa'],'') if form=='ipa' else gloss['ye']
                    ids = set()
                    for sense in gloss_parts(gloss['zh']):
                        ids.update(lookup.get((native_forms(reading),sense),()))
                    if len(ids)!=1:
                        history.clear()
                        continue
                    mapped_tokens+=1
                    following=ids.pop()
                    if history:
                        docs[doc['id']].append((tuple(history), following))
                        covered_pairs+=1
                    history=(history+[following])[-2:]
    return docs, (mapped_tokens, aligned_tokens, covered_pairs, all_pairs)


def adjacent_input(model, rows, docs, scores):
    by_id = {r['id']: r for r in rows}
    forms = source_forms_by_sense(rows)
    for events in docs.values():
        for ctx, next_id in events:
            left, following = by_id[ctx[-1]], by_id[next_id]
            query = left['plain'] + following['plain']
            choices = model.choices(query)
            length = utf16(left['plain'])
            for label, match in [
                ('真实相邻两词连打·选择前词并保留后词', lambda x: expect(x, left, left['recommended'], length)),
                ('真实相邻两词连打（可见结果）', lambda x: visible_untoned(x, left, forms, length)),
            ]:
                pos = rank(choices, match)
                correct = pos and model.suffix(query, choices[pos-1].consumed) == following['plain']
                scores.add(label, pos if correct else 0)


def syllable_oracle(model, baseline, rows, table, senses_path, entries):
    groups = defaultdict(list)
    accents = {}
    senses = {r['syllable']:r for r in tsv(senses_path)}
    standard = {r['word']:r['recommended'] for r in entries if r['kind'] == 'syllable'}
    lexical = {r['id']:r for r in rows}
    for arc in tsv(table):
        groups[arc['context']].append(arc)
        if arc['context'] != '<s>':
            accents[arc['context']] = arc['contextAccent']
    total = han_total = glossed = 0
    for context, arcs in groups.items():
        target = sorted(arcs, key=lambda arc: int(arc['rank']))
        ye = model.choices(context, mode=0, style=0, op='next_syllables')
        marks = model.choices(context, mode=0, style=1, op='next_syllables')
        han = model.choices(context, mode=1, style=1, op='next_syllables')
        assert [c.text for c in ye] == [r['next'] for r in target], context
        assert [c.text for c in marks] == [r['nextAccent'] for r in target], context
        assert [c.last_syllable for c in marks] == [r['next'] for r in target], context
        assert all(c.consumed == 0 and c.syllable_only for c in ye + marks + han), context
        assert marks == model.choices(context, mode=2, style=1, op='next_syllables'), context
        expected_han = []
        for arc, sound in zip(target,marks):
            tone = arc['next']
            sense = senses.get(tone, {})
            word = lexical.get(sense.get('lexemeId'), {})
            gloss = sense.get('shortGloss', '')
            assert sound.ids == sense.get('lexemeId', '') and sound.gloss == sound.short == gloss, tone
            glossed += bool(gloss)
            glyph = sense.get('han') or word.get('recommended') or standard.get(tone)
            if glyph:
                short = '' if word.get('presentation') == 'plainHanLoan' else gloss
                expected_han.append((tone,glyph,gloss,short,sound.ids))
        assert [(c.last_syllable,c.text,c.gloss,c.short,c.ids) for c in han] == expected_han, context
        if context in accents:
            assert marks == model.choices(accents[context], mode=0, style=1, op='next_syllables'), context
        total += len(arcs)
        han_total += len(han)
    assert not model.choices('', op='next_syllables') and not model.choices('。', op='next_syllables')
    stable = 0
    for r in rows:
        for key in ('word','diacritic','plain','recommended'):
            assert model.choices(r[key]) == baseline.choices(r[key]), r['id']
        assert model.choices(r['id'],op='next_words') == baseline.choices(r['id'],op='next_words'), r['id']
        assert any(c.ids == r['id'] and c.last_syllable for c in model.choices(r['word'],0)), r['id']
        stable += 1
    after_ka = model.choices('kar', mode=1, op='next_syllables')
    for sound in ('mongr', 'munr'):
        assert any(c.last_syllable == sound and c.text == '萌' and not c.gloss and not c.ids for c in after_ka)
    branch = 'yubao-60757-v-2498'
    for sound, word_id, meaning in [('mǒng', branch, '地支午'), ('kǎmǒng', 'w1102', '树'),
                                    ('tōngmǒng', 'w2302', '呻吟'), ('tòngmǒng', 'w2303', '线')]:
        assert any(c.ids == word_id and c.short == meaning and c.text == sound
                   for c in model.choices(sound, mode=0, style=1))
    assert any(c.ids == branch and c.short == '地支午' and c.text == '萌' and c.consumed == 0
               for c in model.choices(f'w309 {branch} w309', mode=1, op='next_words'))
    return (f'图表共 {total} 对，含 {len(groups["<s>"])} 个句首选项、{len(groups)} 种前项（含句首）。'
            f'按来源顺序逐条核对，可直接上屏的叶氏拼写与带调拼写各 {total}/{total}。'
            f'其中 {han_total} 对有已审定词形或音节参考字，汉字模式 {han_total}/{han_total} 对的字形、汉义和排序均吻合。'
            f'逐音节释义使用独立审核表；{glossed}/{total} 对有可证实的单义，其余候选的释义留空。'
            f'加载音节资源前后，{stable}/{len(rows)} 个词的候选与词义联想保持相同排序。'
            '没有参考字的音节仅供拼写输入。\n\n'
            '在 kǎ 后，mǒng 和 mǔn 都可直接上屏，两种读音共用参考字“萌”，均留空释义。'
            '另选词典中的独立词 mǒng 时，键盘显示“地支午”。同一输入框再次用到已选过的搭配时，可以联想到这个词义。'
            '三个完整词 kǎmǒng、tōngmǒng、tòngmǒng 则分别显示“树”“呻吟”“线”。')


def other_candidates(model, entries, scores):
    for row in entries:
        kind = row['kind']
        if kind == 'grammar':
            choices = model.choices(row['word'])
            scores.add('语法形式→推荐汉字', rank(choices, lambda x: expect(x, row, row['recommended'], utf16(row['word']))))
        elif kind == 'syllable':
            choices = model.choices(row['word'])
            scores.add('明确声调→参考表用字', rank(choices, lambda x: x.kind == 0 and x.text == row['recommended'] and x.consumed == utf16(row['word'])))
        elif kind == 'zh':
            choices = model.choices(row['word'], cache=False)
            scores.add('普通话拼音→汉语后备词', rank(choices, lambda x: x.kind == 1 and x.text == row['recommended'] and not x.gloss and x.consumed == utf16(row['word'])))


def loan_oracle(model, entries, gram_path):
    loans = {row['id']:row for row in entries if row.get('presentation') == 'plainHanLoan'}
    assert loans and 'xu213-2-1' in loans
    han_checks = spelling_checks = prediction_checks = 0
    for record in loans.values():
        for key in {record['plain'], record['word'], record['diacritic'], record['recommended'], *record['aliases'].split()}:
            choices = model.choices(key)
            assert rank(choices, lambda c: visible(c, record, record['recommended'], utf16(key))), (record['id'], key)
            han_checks += 1
        for mode, key, target, style in ((0,record['word'],record['word'],0),
                                         (2,record['recommended'],record['diacritic'],1)):
            choices = model.choices(key,mode,style)
            assert rank(choices,lambda c:expect(c,record,target,utf16(key),han=False)), record['id']
            spelling_checks += 1
    for edge in tsv(gram_path):
        if edge['context'] == '@prior' or edge['next'] not in loans:
            continue
        row = loans[edge['next']]
        assert rank(model.choices(edge['context'],op='next_words'), lambda c:expect(c,row,row['recommended'],0))
        prediction_checks += 1
    predicted = (f'词义联想表中 {prediction_checks} 条边指向这些词义，均保留词条身份与完整释义。'
                 if prediction_checks else '当前词模没有生成指向这组借词的词级联想。')
    return (f'{len(loans)} 个经过借源和本字核对的词义采用原汉字。键入拼写、手写本字或保留的旧写法共 '
            f'{han_checks}/{han_checks} 次正确显示本字并省去字上短释义。土家语拼写与手写转拼写 {spelling_checks}/{spelling_checks} '
            f'次仍显示释义。{predicted}'
            '例如输入 `sanggo` 或手写“伤哥”可选到“山歌”；切换到土家语拼写，候选 `sānggō` 上方显示“山歌”。')


def source_oracle(site, rows, song_lines):
    dictionary=load(site/'dictionary.json.gz')
    words={r['id']:r for r in rows}
    entries=[e for e in dictionary['words'] if e['id'] in words]
    books=load(site/'annotated-books.json')
    song_doc=next(d for d in books if d['id']=='xu-tujia-annotated-2-13')
    source_tokens=[g for block in song_doc['blocks'][:4] for g in block['glosses']]
    converter=['node',str(ROOT/'tools/site_spellings.cjs'),str(site)]
    def convert(payload):
        return json.loads(subprocess.check_output(converter,input=json.dumps(payload).encode()))
    native=convert({'entries':entries,'ipa':[g['ipa'] for g in source_tokens]})
    accent=convert({'words':list(native['spellings'].values())})['accented']
    compared=Counter()
    for entry in entries:
        row=words[entry['id']]
        modes=native['display'][entry['id']]
        compared['ye']+=row['word'] in modes['ye']
        compared['accent']+=row['diacritic'] in modes['accent']
        compared['literalGloss']+=row['meaning']==entry['zh']
    labels={'1pl':'我们','INJ':'语气词','EMP':'强调','STRP':'状态助词','IND':'陈述语气'}
    fixture=[tok for line in song_lines for tok in line]
    assert len(source_tokens)==len(fixture)
    for source,tok in zip(source_tokens,fixture):
        ye=native['spellings'][source['ipa']]
        compared['song']+=(tok['ye']==ye and tok['diacritic']==accent[ye] and
                          tok['gloss']==labels.get(source['zh'],source['zh']))
    story=next(d for d in load(site/'longshan-texts.json') if d['id']=='tlp-longshan-stories-02-01')
    witnesses={(native_forms(g['ye']),g['zh']) for b in story['blocks'] for g in b['glosses']}
    story_only=[r for r in rows if r['id'] not in native['display'] and not r['id'].startswith('xu213-')]
    direct=sum(any((native_forms(a),r['meaning']) in witnesses for a in [r['word'],*r['romanAliases'].split()]) for r in story_only)
    return (f'学堂词典中可按 ID 直接对应 {len(entries)} 条。逐条调用学堂北部词头显示函数后，'
            f'叶氏拼写有 {compared["ye"]}/{len(entries)} 条与词条展示形式一致，调号有 {compared["accent"]}/{len(entries)} 条一致。'
            f'完整汉义与词典主释义逐字一致 {compared["literalGloss"]}/{len(entries)} 条；例如主释义“儿童”在键盘中扩为“小孩、儿童”。'
            f'其余条目中的 {len(story_only)} 条取自《陈二郎戏弄哥哥》，其中 {direct}/{len(story_only)} 条可以用原文拼写或保留的拼写变体加原释义直接回查。'
            '有一处原标注“一会儿坐”，导入时结合相邻的“坐”拆为两个词，故未通过逐字比对。'
            f'山歌原文前四行的 IPA 换算拼写、调号与逐词汉义另核 {compared["song"]}/{len(source_tokens)} 词次。'
            '推荐汉字另依项目参考字表和分义约定。')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--site',type=Path,required=True,help='Local tujia.biz site directory containing annotated-books.json and longshan-texts.json')
    parser.add_argument('--out',type=Path,default=ROOT/'qa/lexicon.md')
    args=parser.parse_args()
    words_path = ASSETS / 'words.tsv'
    grams_path = ASSETS / 'ngrams.tsv'
    sound_path = ASSETS / 'syllable_bigrams.tsv'
    senses_path = ASSETS / 'syllable_senses.tsv'
    entries = tsv(words_path)
    rows = [r for r in entries if r['kind'] in ('tujia', 'phrase')]
    words, model_data = words_path.read_bytes(), grams_path.read_bytes()
    scores = Scores()
    docs, coverage=corpus_documents(args.site,words_path)
    with tempfile.TemporaryDirectory() as temp:
        dll=library(Path(temp)/'shared.so')
        with Model(dll,words,model_data,sound_path.read_bytes(),senses_path.read_bytes()) as model, Model(dll,words,model_data) as baseline:
            sounds = syllable_oracle(model, baseline, rows, sound_path, senses_path, entries)
            loans = loan_oracle(model, rows, grams_path)
            labels=lexical(model,rows,scores)
            other_candidates(model, entries, scores)
            lines=song(model,rows,scores)
            adjacent_input(model, entries, docs, scores)
    mapped,total,covered,pairs=coverage
    n = len(rows)
    tokens = sum(map(len,lines))
    report=['# 词库、连续分词与下一词联想评测',
            f'在共用输入核心中测试 {n} 个土家语词条或词组。每个词按相同权重计一次。一个候选要同时给出预期词条 ID、字形或拼写、完整与简明汉义，并消耗正确数量的 UTF-16 单元才算命中。首位和前三位均计入全部候选。',
            '## 词条与同形词',scores.table(labels),
            '同形词按相同输入、不同来源词条 ID 取样。旧写法来自保留的输入别名，词内补全按每个能严格补成整词的前缀计分。无调键入和相邻词连打的“可见结果”要求候选消费长度正确，字形和短义来自相同无调拼写、相同完整汉义的词条。比如打 go，表示“他”的多种来源写法共占一格“过”；选择“过错”仍有独立的一格。逐条核对 ID 的行保留来源间的差异。带调 gǒ 或叶氏 gor 仍能选“郭”。',
            '## 音节、语法与汉语后备候选',
            scores.table(['明确声调→参考表用字','语法形式→推荐汉字','普通话拼音→汉语后备词']),
            '音节行按参考字、调号确定的输入长度和输出类别计分，实际词条与默认音节可共用该字。汉语后备项显示在土家语候选后面，按空释义、汉字、输入长度及汉语类别判断。',
            '## 下一音节',sounds,
            '## 汉语借词的本字',loans,
            '## 学堂来源核对',source_oracle(args.site,rows,lines), '## 真实连续输入',
            f'毕兹学堂《山歌两首》第一首有 4 行、{tokens} 次词语出现。一次选择要求只有一个候选就能按原顺序送入全行所有词。逐词选择从完整的连续输入开始，每次选择后用核心函数取回余串。诗行的逗号、句号和换行属于编辑操作，未计入打字数。各行分别测试无调、调号和叶氏键入。[学堂原文](https://tujia.biz/?id=xu-tujia-annotated-2-13#corpus?corpusCollection=xu-tujia-annotated-texts)',
            scores.table([f'{prefix}·{form}' for prefix in ('山歌单词','山歌整行一次选择','山歌整行预编辑后分词',) for form in ('plain','diacritic','ye')]),
            '表中 `plain` 为无调拼写，`diacritic` 为带调号拼写，`ye` 为叶氏拼写。按标注手动选择各词后，整行的正确数分别为 ' + '、'.join(str(scores.rows['逐词选择后整行正确·'+form][1])+'/4' for form in ('plain','diacritic','ye'))+'。',
            '## 语料分词与下一词',
            f'逐词标注来自学堂的北部土家语文献与龙山语料。可与音串完整对齐的标注中有 {total} 个词次，其中 {mapped} 个能对应当前词库中的一个词义。{pairs} 组相邻词次中有 {covered} 组可同时对到两个词条。下表只对这 {covered} 组作判断。标点、块界、词库外和无法消歧的词都会中断上下文。',
            scores.table(['真实相邻两词连打·选择前词并保留后词','真实相邻两词连打（可见结果）']),
            '下一词的首位、首屏四位和按键数按整篇留出重新训练，包含词表外与冷启动位置。[统计推荐对照](prediction.md)',
            '## 复现',
            '同一无调拼写与完整汉义的不同汉字写法可用仓库自带词库复查。专项还检查前缀补全、不同词界各自保留余串、带调选词、手写选义和普通汉语后备项。',
            '```sh\npython3 qa/test_candidate_senses.py\n```',
            '在仓库根目录运行下列命令。依赖 Python 3、Node.js、C++17 编译器，以及包含学堂逐词标注和转换脚本的本地快照。',
            '```sh\npython3 qa/lexicon.py --site /workspace/bizic-dictionary/site --out /tmp/lexicon.md\n```',
            '本评测直接调用实际 C ABI。词条覆盖反映当前表内的一致性与候选次序。自由口语和真实使用时发生的输入错误需要另行取样。安卓系统输入框的手写选义与候选提交见[设备实测](device.md)。',
            '| 输入文件 | SHA-256 |\n|---|---|\n'+'\n'.join(f'| `{p.name}` | `{hashlib.sha256(p.read_bytes()).hexdigest()}` |' for p in (words_path,grams_path,sound_path,senses_path,ROOT/'shared/src/lexicon.cpp',ROOT/'tools/story-demo/shange-spelling.tsv',args.site/'dictionary.json.gz',args.site/'annotated-books.json',args.site/'longshan-texts.json'))]
    args.out.write_text('\n\n'.join(report)+'\n',encoding='utf-8')
    print(args.out)
    print(json.dumps({k:[v['n'],v[1],v[3],v[float('inf')]] for k,v in scores.rows.items()},ensure_ascii=False))
    for label in ('无调→推荐汉字（可见结果）', '调号→推荐汉字', '叶氏→推荐汉字', '推荐汉字→叶氏拼写', '真实相邻两词连打（可见结果）', '普通话拼音→汉语后备词'):
        if scores.rows[label][float('inf')] != scores.rows[label]['n']:
            raise AssertionError('Missing expected choice: ' + label)


if __name__=='__main__':
    main()
