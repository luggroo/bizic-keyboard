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

Item = namedtuple('Item', 'ids text gloss short consumed kind last_syllable syllable_only')


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
    declare('words_close', [ptr], None)
    declare('next_syllables', [ptr, string, num], ptr)
    for name in ('lookup_with_orthography', 'handwriting_suggestions', 'next_words'):
        declare(name, [ptr, string, num, num], ptr)
    declare('choices_count', [ptr], size)
    for field in ('text', 'meaning', 'short_meaning', 'lexeme_ids', 'last_syllable'):
        declare('choice_' + field, [ptr, size], string)
    for field in ('consumed_utf16', 'kind', 'is_syllable'):
        declare('choice_' + field, [ptr, size], num)
    declare('choices_close', [ptr], None)
    declare('suffix_after_choice', [string, num], ptr)
    declare('text_free', [ptr], None)
    return dll


class Model:
    def __init__(self, dll, words, grams, sounds=b''):
        self.dll = dll
        self.handle = dll.tji_words_open_full(words, len(words), grams, len(grams), sounds, len(sounds))
        if not self.handle:
            raise RuntimeError('Words and ngrams did not load together')
        self.cache = {}

    def choices(self, query, mode=1, style=0, op='lookup_with_orthography', cache=True):
        key = (query, mode, style, op)
        if key in self.cache:
            return self.cache[key]
        d = self.dll
        if op == 'next_syllables':
            choices = d.tji_next_syllables(self.handle, query.encode(), style)
        else:
            choices = getattr(d, 'tji_' + op)(self.handle, query.encode(), mode, style)
        try:
            items = [Item(d.tji_choice_lexeme_ids(choices, i).decode(), d.tji_choice_text(choices, i).decode(),
                          d.tji_choice_meaning(choices, i).decode(), d.tji_choice_short_meaning(choices, i).decode(),
                          d.tji_choice_consumed_utf16(choices, i), d.tji_choice_kind(choices, i),
                          d.tji_choice_last_syllable(choices, i).decode(), bool(d.tji_choice_is_syllable(choices, i)))
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


def visible(item, row, text, consumed):
    return (item.text == text and item.gloss == row['meaning'] and item.short == row['shortGloss']
            and item.kind == 0 and item.consumed == consumed)


def expect(item, row, text, consumed):
    return item.ids == row['id'] and visible(item, row, text, consumed)


def lexical(model, rows, scores):
    plain = Counter(r['plain'] for r in rows)
    han = Counter(r['recommended'] for r in rows)
    labels = ['无调→推荐汉字', '调号→推荐汉字', '叶氏→推荐汉字', '无调→调号拼写', '无调→叶氏拼写',
              '推荐汉字→调号拼写', '推荐汉字→叶氏拼写', '同无调拼写的不同词义', '同推荐汉字的不同词义',
              '原文罗马字别名→推荐汉字', '整词汉字旧写法→推荐汉字', '规范字形的真前缀→整词']
    for r in rows:
        def measure(label, query, output='recommended', mode=1, style=0, op='lookup_with_orthography'):
            items = model.choices(query, mode, style, op)
            found = rank(items, lambda x: expect(x, r, r[output], utf16(query)))
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
            pos = rank(model.choices(query), lambda x: visible(x,r,r['recommended'],utf16(query)))
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


def grams(events):
    result = Counter()
    for context, following in events:
        for length in range(1,len(context)+1):
            result[(' '.join(context[-length:]),following)] += 1
    return result


def prediction(dll, words, grams_path, model, rows, docs, scores):
    by_id = {r['id']:r for r in rows}
    by_doc = {name:grams(events) for name,events in docs.items()}
    all_grams = sum(by_doc.values(),Counter())
    shipped = {(r['context'],r['next']):int(r['count']) for r in tsv(grams_path)}
    assert dict(all_grams) == shipped, 'Training example counts differ from shipped ngram data'
    for doc, events in docs.items():
        training = all_grams - by_doc[doc]
        data = 'context\tnext\tcount\texample\n' + ''.join(f'{c}\t{n}\t{f}\theld-out\n' for (c,n),f in sorted(training.items()))
        with Model(dll,words,data.encode()) as held_out:
            for ctx,next_id in events:
                history=' '.join(ctx)
                following = by_id[next_id]
                for label, dic in [('原表重放·下一词',model), ('整篇留出·下一词',held_out)]:
                    choices=dic.choices(history,op='next_words')
                    scores.add(label,rank(choices,lambda x:expect(x,following,following['recommended'],0)))
                # The two input items have no artificial delimiter, including homophones.
                left=by_id[ctx[-1]]
                query=left['plain']+following['plain']
                choices=model.choices(query)
                r=rank(choices,lambda x:expect(x,left,left['recommended'],utf16(left['plain'])))
                suffix_ok=r and model.suffix(query,choices[r-1].consumed)==following['plain']
                scores.add('真实相邻两词连打·选择前词并保留后词',r if suffix_ok else 0)
                pos=rank(choices,lambda x:visible(x,left,left['recommended'],utf16(left['plain'])))
                visible_suffix=pos and model.suffix(query, choices[pos-1].consumed) == following['plain']
                scores.add('真实相邻两词连打（可见结果）',pos if visible_suffix else 0)
    return len(all_grams)

def syllable_oracle(model, baseline, rows, table):
    groups = defaultdict(list)
    accents = {}
    for arc in tsv(table):
        groups[arc['context']].append(arc)
        if arc['context'] != '<s>':
            accents[arc['context']] = arc['contextAccent']
    total = 0
    for context, arcs in groups.items():
        target = sorted(arcs, key=lambda arc: int(arc['rank']))
        ye = model.choices(context, style=0, op='next_syllables')
        marks = model.choices(context, style=1, op='next_syllables')
        assert [c.text for c in ye] == [r['next'] for r in target], context
        assert [c.text for c in marks] == [r['nextAccent'] for r in target], context
        assert [c.last_syllable for c in marks] == [r['next'] for r in target], context
        assert all(c.consumed == 0 and not c.ids and c.syllable_only for c in ye + marks), context
        if context in accents:
            assert marks == model.choices(accents[context], style=1, op='next_syllables'), context
        total += len(arcs)
    assert not model.choices('', op='next_syllables') and not model.choices('。', op='next_syllables')
    stable = 0
    for r in rows:
        # Loading an independent hint table must never displace a lexical candidate.
        for key in ('word','diacritic','plain','recommended'):
            assert model.choices(r[key]) == baseline.choices(r[key]), r['id']
        assert model.choices(r['id'],op='next_words') == baseline.choices(r['id'],op='next_words'), r['id']
        assert any(c.ids == r['id'] and c.last_syllable for c in model.choices(r['word'],0)), r['id']
        stable += 1
    return (f'图表共 {total} 对，含 {len(groups["<s>"])} 个句首选项、{len(groups)} 种前项（含句首）。'
            f'按来源顺序逐行检查，叶氏形式 {total}/{total}、带调形式 {total}/{total} 一致。'
            f'对照装载与未装载音节表的同一引擎，{stable}/{len(rows)} 个词的候选内容、原有排序及词义联想完全一致。'
            '例如选“饭”后词义联想有“吃”。另外打开下一音节可见 rī、gá 等。音节提示消费长度为零，选中后仍可在词库中选词。'
            '图表给出名次；使用这些名次无法计算真实语流的命中概率。')


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
    entries = tsv(words_path)
    rows = [r for r in entries if r['kind'] in ('tujia', 'phrase')]
    words, model_data = words_path.read_bytes(), grams_path.read_bytes()
    scores = Scores()
    docs, coverage=corpus_documents(args.site,words_path)
    with tempfile.TemporaryDirectory() as temp:
        dll=library(Path(temp)/'shared.so')
        with Model(dll,words,model_data,sound_path.read_bytes()) as model, Model(dll,words,model_data) as baseline:
            sounds = syllable_oracle(model, baseline, rows, sound_path)
            labels=lexical(model,rows,scores)
            other_candidates(model, entries, scores)
            lines=song(model,rows,scores)
            grams_count=prediction(dll,words,grams_path,model,entries,docs,scores)
    mapped,total,covered,pairs=coverage
    n = len(rows)
    tokens = sum(map(len,lines))
    report=['# 词库、连续分词与下一词联想评测',
            f'在共用输入核心中测试 {n} 个土家语词条或词组。每个词按相同权重计一次。一个候选要同时给出预期词条 ID、字形或拼写、完整与简明汉义，并消耗正确数量的 UTF-16 单元才算命中。首位和前三位均计入全部候选。',
            '## 词条与同形词',scores.table(labels),
            '无调输入会合并原来靠声调区分的形式。表中同形词按“输入相同、词条 ID 不同”分组。词内补全按可严格补全的每个前缀计数，有些前缀由单字与双字合音共同构成。整词旧写法来自保留的输入别名。标注“可见结果”的行按相同字形、汉义、短释义和消费长度计分。相同的推荐字与释义会合并成一张候选，另一来源的 ID 在严格计分时失配，例如“倮／人”“哥哥／哥哥”。',
            '## 音节、语法与汉语后备候选',
            scores.table(['明确声调→参考表用字','语法形式→推荐汉字','普通话拼音→汉语后备词']),
            '音节行按参考字、调号确定的输入长度和输出类别计分，实际词条与默认音节可共用该字。汉语后备项显示在土家语候选后面，按空释义、汉字、输入长度及汉语类别判断。',
            '## 下一音节',sounds,
            '## 学堂来源核对',source_oracle(args.site,rows,lines), '## 真实连续输入',
            f'毕兹学堂《山歌两首》第一首有 4 行、{tokens} 次词语出现。一次选择要求只有一个候选就能按原顺序送入全行所有词。逐词选择从完整的连续输入开始，每次选择后用核心函数取回余串。诗行的逗号、句号和换行属于编辑操作，未计入打字数。各行分别测试无调、调号和叶氏键入。[学堂原文](https://tujia.biz/?id=xu-tujia-annotated-2-13#corpus?corpusCollection=xu-tujia-annotated-texts)',
            scores.table([f'{prefix}·{form}' for prefix in ('山歌单词','山歌整行一次选择','山歌整行预编辑后分词',) for form in ('plain','diacritic','ye')]),
            '表中 `plain` 为无调拼写，`diacritic` 为带调号拼写，`ye` 为叶氏拼写。按标注手动选择各词后，整行的正确数分别为 ' + '、'.join(str(scores.rows['逐词选择后整行正确·'+form][1])+'/4' for form in ('plain','diacritic','ye'))+'。',
            '## 语料分词与下一词',
            f'逐词标注来自学堂的北部土家语文献与龙山语料。可与音串完整对齐的标注中有 {total} 个词次，其中 {mapped} 个能对应当前词库中的一个词义。{pairs} 组相邻词次中有 {covered} 组可同时对到两个词条。下表只对这 {covered} 组作判断。标点、块界、词库外和无法消歧的词都会中断上下文。',
            scores.table(['真实相邻两词连打·选择前词并保留后词','真实相邻两词连打（可见结果）','原表重放·下一词','整篇留出·下一词']),
            f'原表重放使用已收录的 {grams_count} 条二元和三元记录。整篇留出分 {len(docs)} 折，每一折移除一篇文献中观测到的全部邻接次数，重新装入共用核心，再预测该文献中的词。同一词出现多次按出现次数计。留出时固定发行词库和词频，移除测试文献的邻接计数。排序首先取两个前词的语料邻接，再回退到一个前词。',
            '## 复现',
            '在仓库根目录运行下列命令。依赖 Python 3、Node.js、C++17 编译器，以及包含学堂逐词标注和转换脚本的本地快照。',
            '```sh\npython3 qa/lexicon.py --site /workspace/bizic-dictionary/site --out /tmp/lexicon.md\n```',
            '本评测直接调用实际 C ABI。词条覆盖反映当前表内的一致性与候选次序。语料留出反映在这批文献中的迁移表现。自由口语和真实使用时发生的输入错误需要另行取样。安卓系统输入框的手写选义与候选提交见[设备实测](device.md)。',
            '| 输入文件 | SHA-256 |\n|---|---|\n'+'\n'.join(f'| `{p.name}` | `{hashlib.sha256(p.read_bytes()).hexdigest()}` |' for p in (words_path,grams_path,sound_path,ROOT/'shared/src/lexicon.cpp',ROOT/'tools/story-demo/shange-spelling.tsv',args.site/'dictionary.json.gz',args.site/'annotated-books.json',args.site/'longshan-texts.json'))]
    args.out.write_text('\n\n'.join(report)+'\n',encoding='utf-8')
    print(args.out)
    print(json.dumps({k:[v['n'],v[1],v[3],v[float('inf')]] for k,v in scores.rows.items()},ensure_ascii=False))
    for label in ('无调→推荐汉字（可见结果）', '调号→推荐汉字', '叶氏→推荐汉字', '推荐汉字→叶氏拼写', '真实相邻两词连打（可见结果）', '普通话拼音→汉语后备词'):
        if scores.rows[label][float('inf')] != scores.rows[label]['n']:
            raise AssertionError('Missing expected choice: ' + label)


if __name__=='__main__':
    main()
