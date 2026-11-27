#!/usr/bin/env python3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from prepare_keyboard_assets import asset_files


class KeyboardAssetsTest(unittest.TestCase):
    def test_release_variants_filter_candidates_and_retain_tima(self):
        typing, audit = asset_files(True)
        handwriting, _ = asset_files(False)
        for name in ("words.tsv", "northern-words.tsv", "ipa-completions.tsv", "ngrams.tsv", "syllable_senses.tsv"):
            self.assertEqual(typing[name], handwriting[name])
        self.assertNotIn("zh-中国共产党".encode(), typing["words.tsv"])
        self.assertNotIn("男生殖器".encode(), typing["northern-words.tsv"])
        self.assertNotIn("阴茎".encode(), typing["ipa-completions.tsv"])
        self.assertIn("stedt-rn-354871".encode(), typing["northern-words.tsv"])
        self.assertIn("w684\tgaf".encode(), typing["words.tsv"])
        self.assertIn("土家族".encode(), typing["northern-words.tsv"])
        self.assertIn("苗族".encode(), typing["northern-words.tsv"])
        self.assertTrue(any(row[2] == "保留梯玛" for row in audit))
        for name in ("onnxruntime-LICENSE.txt", "onnxruntime-ThirdPartyNotices.txt"):
            path = f"licenses/{name}"
            self.assertIn(path, handwriting)
            self.assertNotIn(path, typing)


if __name__ == "__main__":
    unittest.main()
