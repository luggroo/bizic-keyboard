#!/usr/bin/env python3
"""Exercise the build-time learning snapshot contract without an Android SDK."""
import gzip
import json
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from prepare_learning_assets import CATALOGUE, MANIFEST, prepare, read_android_snapshot, read_snapshot, sha256


class LearningSnapshotTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.archive = self.root / "learning.zip"
        self.pin = self.root / "learning.sha256"
        self.output = self.root / "generated"
        self.files = {"index.html": b"home", "app.js": b"", "prepared/word-search.json.gz": b"index",
                      CATALOGUE: gzip.compress(b'{"sources":[],"sourceVariants":[]}')}
        self.records = [{"path": k, "sha256": sha256(v), "size": len(v), "category": "frontend"}
                        for k, v in self.files.items()]
        self.manifest = {"schemaVersion": 1, "contentVersion": "one", "sourceRevision": "a" * 40,
                         "files": self.records}

    def write_archive(self):
        with ZipFile(self.archive, "w") as bundle:
            for path, content in self.files.items():
                bundle.writestr(path, content)
            bundle.writestr(MANIFEST, json.dumps(self.manifest))
        self.pin.write_text(sha256(self.archive.read_bytes()) + "\n")

    def test_replacement_removes_stale_output(self):
        self.write_archive()
        prepare(self.output, self.archive, self.pin)
        self.assertEqual((self.output / "learning/index.html").read_bytes(), b"home")
        wrapped = self.output / "learning/prepared/word-search.json.gz.gz"
        self.assertEqual(gzip.decompress(wrapped.read_bytes()), self.files["prepared/word-search.json.gz"])
        stale = self.output / "learning/old-entry.json"
        stale.touch()
        prepare(self.output, self.archive, self.pin)
        self.assertFalse(stale.exists())

    def test_pin_and_schema(self):
        self.write_archive()
        self.pin.write_text("1" * 64)
        with self.assertRaisesRegex(ValueError, "pin"):
            read_snapshot(self.archive, self.pin)
        self.manifest["schemaVersion"] = 2
        self.write_archive()
        with self.assertRaisesRegex(ValueError, "version"):
            read_snapshot(self.archive, self.pin)

    def test_corrupt_member_keeps_existing_output(self):
        self.write_archive()
        prepare(self.output, self.archive, self.pin)
        self.files["index.html"] = b"drift"
        self.write_archive()
        with self.assertRaisesRegex(ValueError, "record"):
            prepare(self.output, self.archive, self.pin)
        self.assertEqual((self.output / "learning/index.html").read_bytes(), b"home")

    def test_invalid_and_duplicate_paths(self):
        for path in ("../outside", "/absolute", "prepared/../same", "prepared\\file", "_app/keyboard", "INDEX.HTML"):
            with self.subTest(path=path):
                self.files[path] = b"unused"
                self.write_archive()
                with self.assertRaisesRegex(ValueError, "path"):
                    prepare(self.output, self.archive, self.pin)
                del self.files[path]
        self.assertFalse(self.output.exists())

    def test_extra_member(self):
        self.files["unlisted.json"] = b"unused"
        self.write_archive()
        with self.assertRaisesRegex(ValueError, "inventory"):
            read_snapshot(self.archive, self.pin)

    def test_android_excludes_publication_text_but_keeps_citations_and_corpus(self):
        shard = "prepared/source-1.json.gz"
        corpus = "prepared/corpus.json.gz"
        citation = {"id": "book", "url": "https://example.org/book.pdf", "text": ""}
        catalogue = {"sources": [{**citation, "_textChunk": "source-1", "textNotice": "OCR"}]}
        changed = {CATALOGUE: gzip.compress(json.dumps(catalogue).encode()),
                   shard: gzip.compress(b'{"id":"book","text":"publication full text"}'),
                   corpus: gzip.compress(b'{"corpus":[{"text":"curated example"}]}')}
        self.files.update(changed)
        self.records[:] = [r for r in self.records if r["path"] not in changed]
        self.records.extend({"path": k, "sha256": sha256(v), "size": len(v), "category": "bibliography"}
                            for k, v in changed.items())
        self.write_archive()
        self.assertIn(shard, read_snapshot(self.archive, self.pin))
        files = read_android_snapshot(self.archive, self.pin)
        self.assertNotIn(shard, files)
        self.assertEqual(files[corpus], self.files[corpus])
        self.assertEqual(json.loads(gzip.decompress(files[CATALOGUE]))["sources"], [citation])
        inventory = {r["path"]: r for r in json.loads(files[MANIFEST])["files"]}
        self.assertNotIn(shard, inventory)
        self.assertEqual(inventory[CATALOGUE]["sha256"], sha256(files[CATALOGUE]))


if __name__ == "__main__":
    unittest.main()
