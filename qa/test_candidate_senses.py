"""Check same-sense Han recommendations against the native core and shipped word list."""
import tempfile
import unittest
from collections import defaultdict
from pathlib import Path

from lexicon import ASSETS, Model, library, tsv, utf16


class HanSenseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        dll = library(Path(cls.temp.name) / 'libtujia.so')
        names = ('words', 'ngrams', 'syllable_bigrams', 'syllable_senses')
        cls.model = Model(dll, *((ASSETS / (name + '.tsv')).read_bytes() for name in names))
        cls.addClassCleanup(dll.tji_words_close, cls.model.handle)
        cls.words = [w for w in tsv(ASSETS / 'words.tsv') if w['kind'] == 'tujia']

    def test_untoned_spelling_recommends_one_form_per_sense(self):
        groups = defaultdict(set)
        for word in self.words:
            groups[word['plain'], word['meaning']].add(word['recommended'])
        for (query, meaning), alternatives in groups.items():
            if len(alternatives) < 2:
                continue
            with self.subTest(query=query, meaning=meaning):
                forms = [item.text for item in self.model.choices(query)
                         if item.kind == 0 and item.ids and item.consumed == utf16(query)
                         and item.gloss == meaning and item.text in alternatives]
                self.assertEqual(len(forms), 1, forms)

    def test_different_senses_of_the_same_han_and_chinese_fallback(self):
        choices = self.model.choices('ga')
        lexical = {(item.text, item.short) for item in choices if item.ids}
        self.assertTrue({('夹', '干'), ('夹', '上'), ('架', '吃')}.issubset(lexical))
        self.assertTrue(any(item.kind == 1 and item.text == '嘎' and not item.short for item in choices))
        tai = self.model.choices('tai')
        self.assertTrue(any(item.ids and item.text == '太' and item.gloss == '没有' for item in tai))
        self.assertTrue(any(item.kind == 1 and item.text == '台' for item in tai))
        go = [item for item in self.model.choices('go') if item.ids and item.consumed == 2]
        self.assertEqual([item.text for item in go if item.gloss == '他'], ['过'])
        self.assertIn(('过', '过错'), {(item.text, item.gloss) for item in go})
        tanie = self.model.choices('tanie')
        self.assertIn(('他捏', '后面'), {(item.text, item.gloss) for item in tanie})
        self.assertIn(('他业', '后头'), {(item.text, item.gloss) for item in tanie})

    def test_completing_a_word_does_not_duplicate_its_sense(self):
        choices = self.model.choices('ma', op='spelling_completions')
        forms = [item.text for item in choices if item.gloss == '吧' and item.text in ('满', '忙')]
        self.assertEqual(len(forms), 1, forms)
        # duyo and duwa are two different full readings after the same typed prefix.
        choices = self.model.choices('du', op='spelling_completions')
        self.assertTrue({'堵药', '堵娃'}.issubset({item.text for item in choices if item.gloss == '赌啊'}))

    def test_marked_pinyin_and_handwritten_variants_still_reach_their_words(self):
        for word in ('gǒ', 'gor'):
            choices = self.model.choices(word)
            self.assertTrue(any(item.gloss == '他' and item.text == '郭' and item.consumed == utf16(word)
                                for item in choices), word)
        for glyph in ('尬', '嘎'):
            choices = self.model.choices(glyph, mode=2, style=1)
            self.assertTrue({('吃', 'gá'), ('干', 'gǎ')}.issubset(
                {(item.short, item.text) for item in choices}), glyph)

    def test_equal_senses_at_two_valid_word_boundaries(self):
        choices = self.model.choices('gore')
        boundaries = {(item.text, self.model.suffix('gore', item.consumed))
                      for item in choices if item.gloss == '他'}
        self.assertEqual(boundaries, {('过', 're'), ('郭', 'e')})


if __name__ == '__main__':
    unittest.main()
