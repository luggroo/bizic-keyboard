#!/usr/bin/env python3
"""Exercise numeric shard coordinates and conservative keyboard/reader identity rules."""
import csv
import gzip
import json
import sys
import tempfile
import unittest
from pathlib import Path
from zlib import crc32

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from ime_dictionary_index import index_assets


class ImeDictionaryIndexTest(unittest.TestCase):
    def test_reading_identity_and_multibyte_coordinates(self):
        words = [{"id":"w1", "term":"土家话 } ]", "attestations":[{"id":"old-A","source":"book","locator":"词条 3"},
                 {"id":"old-C","source":"book","locator":"1"}, {"id":"old-C","source":"book","locator":"2"}]},
                 {"id":"w2", "term":"ŋa²⁴"}, {"id":"w7", "term":"z"}, {"id":"w9", "term":"word"}]
        batch = json.dumps(words, ensure_ascii=False, indent=2).encode()
        def compressed(data):
            return gzip.compress(json.dumps(data, ensure_ascii=False).encode(), mtime=0)
        files = {"prepared/words-12.json.gz":gzip.compress(batch, mtime=0),
                 "prepared/word-search.json.gz":compressed({"words":words,"wordAliases":{"old-A":"w1","old-B":"w2","old-C":"w1"}}),
                 "prepared/catalogue.json.gz":compressed({"sources":[{"id":"book","title":" A  paper "}]})}
        fields = "id kind word meaning plain diacritic recommended".split()
        def row(key, form, gloss):
            return [key, "tujia", form, gloss, form, form, "汉"]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            core, northern = root / "core.tsv", root / "northern.tsv"
            with core.open("w") as out:
                writer = csv.writer(out, delimiter="\t")
                writer.writerows([fields,row("w1","a","甲"),row("w9","b","同义"),row("w2","c","字"),
                                  row("conflict","z","同读"),row("w7","z","同读")])
            with northern.open("w") as out:
                writer = csv.writer(out, delimiter="\t")
                writer.writerows([row("old-A","a","甲"),row("old-A@2","aa","甲"),row("unknown","a","另义"),
                                  row("old-B","b","同义"),row("w2","c","另一义"),row("conflict","q","同读"),
                                  row("old-C","c3","甲")])
            result = index_assets(files, core, northern)
            parsed = {}
            for name, content in result.items():
                if not name.endswith(".tsv"):
                    continue
                for line in content.decode().splitlines():
                    local, canon, shard, start, length, witness, source, locator, representative = line.split("\t")
                    self.assertEqual(name, f"ime-dictionary/ids-{crc32(local.encode()) % 64}.tsv")
                    self.assertEqual(shard, "12")
                    self.assertEqual(json.loads(batch[int(start):int(start)+int(length)])["id"], canon)
                    self.assertEqual((witness, source, locator), ("1","book","词条 3") if local == "old-A" else ("0","",""))
                    self.assertEqual(representative, "w2 w9" if local in {"old-B","w9"} else "")
                    parsed[local] = canon
            self.assertEqual(parsed, {"w1":"w1","old-A":"w1","old-A@2":"w1","old-B":"w2","old-C":"w1","w9":"w9"})
            catalogue = json.loads(result["ime-dictionary/sources.json"])
            self.assertEqual(catalogue["sources"], {"book":"A paper"})
            self.assertEqual(catalogue["profiles"]["brassett-to-ye"], "Brassett 拼音转叶氏拼音")


if __name__ == "__main__":
    unittest.main()
