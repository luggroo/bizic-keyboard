import csv
import io
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from prediction_model import train, training_documents


class PredictionTrainingTest(unittest.TestCase):
    def test_demonstration_and_duplicate_sentences_are_excluded(self):
        demo = [('a', '甲', 'first'), ('b', '乙', 'second')]
        other = [('a', '甲', 'first'), ('c', '丙', None)]
        material = {'xu-tujia-annotated-2-13': [demo], 'one': [demo, other], 'two': [other]}
        self.assertEqual(training_documents(material), {'one': [['first', None]], 'two': []})

    def test_class_transfers_to_a_new_word_without_copying_one_source(self):
        classes = {'rice': 'noun', 'bread': 'noun', 'fruit': 'noun', 'eat': 'verb', 'rare': 'verb'}
        model = train({'one': [['rice', 'eat']] * 15 + [['rice', 'rare']],
                       'two': [['bread', 'eat']]}, classes)
        scores = {}
        for row in csv.DictReader(io.StringIO(model), delimiter='\t'):
            scores.setdefault(row['context'], {})[row['next']] = int(row['score'])
        self.assertEqual(scores['@prior']['eat'], 4)  # Three from one document, one from the other.
        self.assertEqual(scores['fruit'], scores['rice'])
        self.assertGreater(scores['fruit']['eat'], scores['rare']['eat'])

    def test_unknown_word_breaks_context(self):
        classes = dict.fromkeys(('first', 'middle', 'last', 'unseen'), '')
        training = [['first', None, 'middle', 'last']]
        model = train({'one': training, 'two': training}, classes)
        rows = list(csv.DictReader(io.StringIO(model), delimiter='\t'))
        def ranking(context):
            return [(row['next'], row['score']) for row in rows if row['context'] == context]
        self.assertEqual(ranking('first'), ranking('unseen'))
        self.assertEqual(ranking('middle')[0][0], 'last')
        self.assertFalse(ranking('first middle'))


if __name__ == '__main__':
    unittest.main()
