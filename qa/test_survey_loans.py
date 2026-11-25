"""Keep survey loan spelling tied to reviewed forms and their actual senses."""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tools'))
from prepare_survey_han_loans import select_rows
from sync_reviewed_loan_forms import recorded_donor


def word(ident, zh, *forms):
    return {'id': ident, 'zh': zh, 'forms': [
        {'ye': ye, 'parts': parts, 'ipa': ipa} for ye, parts, ipa in forms]}


def note(ident, donor, ipa, span, scope='whole', prefix='chinese-loan-'):
    return {'id': prefix+ident, 'wordIds': [ident], 'kind': '借词来源',
            'scope': scope, 'donor': donor, 'text': f'可能借自汉语「{donor}」。',
            'reviewedForms': [{'ipa': ipa, 'span': span}]}


class SurveyLoans(unittest.TestCase):
    def test_site_evidence_sense_and_tone_preservation(self):
        words = [word('can', '可以', ('kovyir', ['kov', 'yir'], 'kʰɔ⁵³ʑi³³')),
                 word('school', '学校', ('xiaortanx', ['xiaor', 'tanx'], 'ɕiau³³tʰã⁵⁵')),
                 word('poison', '毒', ('duf', ['duf'], 'tu³⁵')),
                 word('measure', '尺', ('cix', ['cix'], 'tsʰɿ⁵⁵'))]
        notes = [note('can', '可以', 'kʰɔ⁵³ʑi³³', [1, 2]),
                 note('school', '校堂', 'ɕiau³³tʰã⁵⁵', [1, 2]),
                 note('measure', '尺', 'tsʰɿ⁵⁵', [1, 1], prefix='loan-etymology-')]
        found = {r[0]: r for r in select_rows(words, notes, {})}
        self.assertEqual(found['can'][1:4], ['kovyir', '可以', '可以'])
        self.assertEqual(found['can'][6], 'kʰɔ⁵³ʑi³³')
        self.assertEqual(found['school'][2:4], ['校堂', '学校'])
        self.assertEqual(found['measure'][2], '尺')
        self.assertNotIn('poison', found)

    def test_unreviewed_alternates_and_partial_loans_are_not_promoted(self):
        words = [word('mixed', '唱歌', ('canrgor', ['canr','gor'], 'tsʰaŋ³³kɔ³³'),
                      ('gofcanr', ['gof','canr'], 'kɔ³⁵tsʰaŋ³³')),
                 word('partial', '桃花', ('aixbixcix', ['aix','bix','cix'], 'a⁵⁵bi⁵⁵tsʰi⁵⁵'))]
        notes = [note('mixed', '唱歌', 'tsʰaŋ³³kɔ³³', [1, 2]),
                 note('partial', '桃子', 'a⁵⁵bi⁵⁵tsʰi⁵⁵', [1, 2], scope='component')]
        self.assertEqual([r[:3] for r in select_rows(words, notes, {})], [['mixed','canrgor','唱歌']])

    def test_conflicting_donors_need_a_reviewed_override(self):
        words = [word('bell', '钟', ('lenrzir', ['lenr','zir'], 'leŋ³³tsɿ³³'))]
        notes = [note('bell', '铃子', 'leŋ³³tsɿ³³', [1, 2]),
                 note('bell', '铃铛', 'leŋ³³tsɿ³³', [1, 2])]
        self.assertEqual(select_rows(words, notes, {}), [])
        overrides = {('bell','lenrzir'): {'han':'铃子', 'meaning':'钟', 'basis':'已核定'}}
        self.assertEqual(select_rows(words, notes, overrides)[0][2], '铃子')

    def test_other_sources_require_the_recorded_form_and_sense(self):
        row = {'id': 'brave', 'kind': 'tujia', 'word': 'yongxgangx',
               'meaning': '勇敢', 'hanUnits': '勇|甘'}
        mini = {'_display': {'ye': [{'text': 'yongxgangx'}]}, '_ipa': ['jɔŋ⁵⁵kaŋ⁵⁵']}
        n = note('brave', '勇敢', 'jɔŋ⁵⁵kaŋ⁵⁵', [1, 2])
        self.assertEqual(recorded_donor(row, mini, [n], set())['donor'], '勇敢')
        self.assertIsNone(recorded_donor(row, mini, [n], {'brave'}))
        self.assertIsNone(recorded_donor({**row, 'meaning': '胆量'}, mini, [n], set()))
        self.assertIsNone(recorded_donor({**row, 'meaning': '勇敢（未细分）'}, mini, [n], set()))
        self.assertIsNone(recorded_donor(row, {**mini, '_ipa': ['jɔŋ³⁵kaŋ⁵⁵']}, [n], set()))
        self.assertIsNone(recorded_donor({**row, 'word': 'xiaf'}, mini, [n], set()))
        self.assertIsNone(recorded_donor(row, mini, [note('brave', '勇敢', 'jɔŋ⁵⁵kaŋ⁵⁵', [1, 1])], set()))


if __name__ == '__main__':
    unittest.main()
