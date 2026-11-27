"""Regression tests for local dictionary provenance recorded in frequency exports."""
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
from import_words import DICTIONARY_INPUTS, ROOT, dictionary_provenance


class DictionarySource(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo"
        self.site = self.root / "site"
        self.site.mkdir(parents=True)
        for name in DICTIONARY_INPUTS:
            (self.site / name).write_bytes((name + "\n").encode())
        self.git("init", "-q")
        self.git("add", "site")
        self.git("-c", "user.name=Test", "-c", "user.email=test@local.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "Witnesses")
        self.rev = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", self.root, *args], text=True).strip()

    def test_actual_commit_and_all_loaded_spelling_modules(self):
        receipt = dictionary_provenance(self.site)
        self.assertEqual(self.rev, receipt["sourceRevision"])
        adapter = (ROOT / "tools/site_spellings.cjs").read_text()
        modules = re.findall(r"'([\w.-]+\.js)'", adapter)
        self.assertTrue(modules)
        self.assertTrue(set(modules) <= receipt["sourcesSHA256"].keys())
        self.assertEqual(receipt, dictionary_provenance(self.site, ref=self.rev))
        (self.site / "southern-notation.js").write_bytes(b"dirty")
        with self.assertRaisesRegex(ValueError, "differs from Git"):
            dictionary_provenance(self.site)

    def test_exported_unversioned_inputs_and_explicit_local_origin(self):
        exported = Path(self.temp.name) / "export"
        shutil.copytree(self.site, exported)
        receipt = dictionary_provenance(exported)
        self.assertIsNone(receipt["sourceRevision"])
        self.assertEqual(receipt["sourcesSHA256"], dictionary_provenance(self.site)["sourcesSHA256"])
        self.assertEqual(self.rev, dictionary_provenance(exported, self.root, self.rev)["sourceRevision"])
        with self.assertRaisesRegex(ValueError, "requires a local Git"):
            dictionary_provenance(exported, ref=self.rev)
        (exported / "data.json.gz").write_bytes(b"mismatched")
        with self.assertRaisesRegex(ValueError, "differs from Git"):
            dictionary_provenance(exported, self.root, self.rev)


if __name__ == "__main__":
    unittest.main()
