"""Regression checks on the shipped supplementary survey and pronoun choices."""
import csv
import tempfile
import unittest
from pathlib import Path
from lexicon import ASSETS, Model, library, utf16

class BaojingWords(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        dll = library(Path(cls.temp.name) / 'lib.so')
        words = (ASSETS/'words.tsv').read_bytes() + (ASSETS/'northern-words.tsv').read_bytes()
        data = [(ASSETS/(name+'.tsv')).read_bytes() for name in ('ngrams', 'syllable_bigrams', 'syllable_senses')]
        cls.model = Model(dll, words, *data)
        cls.addClassCleanup(dll.tji_words_close, cls.model.handle)
        with (ASSETS/'words.tsv').open() as src:
            cls.fields = next(csv.reader(src, delimiter='\t'))
        with (ASSETS/'northern-words.tsv').open() as src:
            cls.rows = list(csv.DictReader(src, delimiter='\t', fieldnames=cls.fields))

    def full(self, query):
        return [c for c in self.model.choices(query) if c.ids and c.consumed == utf16(query)]

    def test_survey_points_and_unchanged_tones(self):
        by_id = {r['id']: r for r in self.rows}
        for ident, ye, marked in [('yubao-60756-v-2328', 'mirjirkar', 'mǐjǐkǎ'),
                                  ('yubao-60823-v-2328', 'mifjixkax', 'míjīkā')]:
            r = by_id[ident]
            self.assertEqual((r['word'], r['diacritic'], r['meaning']), (ye, marked, '土家族'))
            self.assertTrue(any(c.gloss == '土家族' for c in self.full(ye)))
        self.assertTrue(any(c.gloss == '土家族' for c in self.full('mijika')))
        for point in ('60756', '60823'):
            self.assertGreater(sum(r['id'].startswith('yubao-'+point+'-v-') for r in self.rows), 2700)
        self.assertTrue(any(r['id'].startswith('yubao-60823') and r['word'].endswith('r') and
                            r['diacritic'] == r['plain'] for r in self.rows))

    def test_ethnonym_variants(self):
        for query, expected in [('bizika', ['秘兹佧', '币子佧']),
                                ('bijika', ['秘基佧', '币基佧']),
                                ('mijika', ['密及佧', '秘基佧']),
                                ('mirjirkar', ['密及佧', '秘基佧']),
                                ('mifjixkax', ['密及佧', '秘基佧'])]:
            self.assertEqual([c.text for c in self.full(query) if c.gloss == '土家族'], expected, query)

    def test_pinyin_deduplicates_readings_without_losing_senses(self):
        for query, style, readings in [
            ('bizika', 0, ['bifzixkar']), ('bijika', 0, ['bifjixkar']),
            ('mijika', 0, ['mirjirkar', 'mifjixkax']),
            ('bizika', 1, ['bízīkǎ']), ('bijika', 1, ['bíjīkǎ']),
            ('mijika', 1, ['mǐjǐkǎ', 'míjīkā'])]:
            choices = [c for c in self.model.choices(query, 0, style)
                       if c.consumed == utf16(query) and c.text in readings]
            for reading in readings:
                self.assertEqual(sum(c.text == reading for c in choices), 1, (query, reading))
        same = [c for c in self.model.choices('ngaf', 0, 0)
                if c.consumed == 4 and c.text == 'ngaf']
        self.assertEqual(len(same), 1)
        self.assertIn('我', same[0].gloss)
        self.assertIn('压', same[0].gloss)
        self.assertEqual(same[0].ids, '')

    def test_ma_tone_defaults(self):
        for query, character in [('max', '马'), ('mav', '马'), ('mar', '麻'), ('maf', '祃')]:
            candidates = [c for c in self.model.choices(query) if c.consumed == utf16(query) and c.kind == 0]
            self.assertTrue(any(c.text == character for c in candidates), query)
            self.assertFalse(any(c.text == '妈' for c in candidates), query)
        for r in self.rows:
            if r['kind'] == 'tujia' and r['presentation'] != 'plainHanLoan' and 'ma' in r['word']:
                self.assertNotIn('妈', r['recommended'], r['id'])

    def test_song_sentence_final_particle(self):
        self.assertTrue(any(c.text == '咧' and c.gloss == '陈述语气'
                            for c in self.full('lex')))

    def test_reviewed_near_synonyms(self):
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
        from keyboard_gloss import normalize_meaning
        self.assertEqual(normalize_meaning('石；石头'), normalize_meaning('石头'))
        self.assertEqual(normalize_meaning('兵；士兵'), normalize_meaning('士兵'))
        self.assertEqual(normalize_meaning('他；她'), '他')
        self.assertEqual(normalize_meaning('燕子；吞咽'), '燕子；吞咽')
        self.assertEqual(normalize_meaning('雪'), '雪')
        self.assertEqual(normalize_meaning('月亮'), '月亮')
        stones = [c for c in self.full('aba') if c.gloss == '石头']
        self.assertEqual(len(stones), 1)

    def test_pronouns_and_distinct_homophones(self):
        self.assertEqual([(c.text,c.short) for c in self.full('ngaf') if c.gloss == '我'], [('牙','我')])
        self.assertEqual([(c.text,c.short) for c in self.full('nif') if c.gloss == '你'], [('伲','你')])
        go = self.full('gof')
        self.assertEqual([c.text for c in go if c.gloss == '他'], ['个'])
        self.assertTrue({('过','讨'),('过','过错')}.issubset({(c.text,c.gloss) for c in go}))

if __name__ == '__main__':
    unittest.main()
