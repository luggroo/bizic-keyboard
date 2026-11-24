"""Build the Android supplementary lexicon from the site's northern dictionary."""
import csv
import gzip
import json
import re
import subprocess
import sys
import unicodedata
from functools import lru_cache
from pathlib import Path

from han_orthography import Orthography

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "shared/bundle/Assets"
# Chinese gloss lookup keys for source transcriptions without a complete site spelling.
GLOSS_KEYS = {
    "224883": "song", "431565": "kao", "288459": "bing", "386736": "shisi",
    "355437": "xiazi", "372742": "huoba", "289347": "yingmu", "431232": "yangji",
    "288513": "sanyue", "228249": "diba", "346488": "dong", "288554": "lang",
    "373783": "gang", "197496": "zhizi", "288762": "bangtui", "127243": "jiaozhijia",
    "414569": "hui", "352839": "niao",
}


def han_units(word, orthography):
    # A tone letter may also start the next syllable (e.g. x-). Require a unique parse.
    @lru_cache(None)
    def parse(start):
        if start == len(word):
            return [()]
        ways = []
        for end in range(start + 2, min(len(word), start + 9) + 1):
            part = word[start:end]
            if orthography.base(part):
                ways.extend((part,) + rest for rest in parse(end))
                if len(ways) > 1:
                    return ways[:2]
        return ways
    parts = parse(0)
    return orthography.units(parts[0], [""] * len(parts[0])) if len(parts) == 1 else []


def build(site):
    with gzip.open(site / "dictionary.json.gz", "rt") as stream:
        words = [w for w in json.load(stream)["words"] if "北部" in w.get("dialects", [w.get("dialect")])]
    # Restrict mixed-dialect entries to attested northern readings.
    for w in words:
        if len(w.get("dialects", [])) > 1:
            w["attestations"] = [a for a in w["attestations"] if a.get("dialect") == "北部"]
            ids = {a["id"] for a in w["attestations"]}
            w["notations"] = {k: [v for v in vs if ids.intersection(v.get("attestationIds", []))]
                              for k, vs in w["notations"].items()}
            w["dialect"], w["dialects"] = "北部", ["北部"]
    run = subprocess.run(["node", ROOT / "tools/site_spellings.cjs", site], check=True,
                         input=json.dumps(dict(entries=words, includeAlternatives=True)), capture_output=True, text=True)
    displays = json.loads(run.stdout)["display"]
    with (ASSETS / "words.tsv").open() as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        fields = reader.fieldnames
        existing = {r["id"]: r for r in reader}
    orthography = Orthography.from_file()
    with (ASSETS / "northern-words.tsv").open("w") as stream:
        writer = csv.DictWriter(stream, fields, delimiter="\t", lineterminator="\n", quoting=csv.QUOTE_NONE, quotechar=None)
        for w in words:
            ident = w["id"]
            forms = displays[ident]
            ye, accent = forms["ye"], forms["accent"]
            if len(ye) != len(accent):
                raise ValueError(f"Unpaired spellings for {ident}")
            fallback = not ye
            if fallback:
                ipa = w["notations"]["ipa"][0]["text"]
                ye = accent = [ipa]
            base = existing.get(ident)
            for i, (word, marked) in enumerate(zip(ye, accent)):
                compact = re.sub(r"[\s’'-]", "", word.lower())
                if base and compact == re.sub(r"[\s’'-]", "", base["word"].lower()):
                    continue
                units = [] if fallback else han_units(compact, orthography)
                gloss = " ".join((w.get("zh") or w.get("en") or "").split())
                plain = unicodedata.normalize("NFC", "".join(c for c in unicodedata.normalize("NFD", marked.lower())
                                                            if c not in "\u0304\u0301\u030c\u0300"))
                alias = GLOSS_KEYS[ident.split("-")[-1]] if fallback else ""
                writer.writerow(dict(id=ident if i == 0 and not base else f"{ident}@{i+1}", word=word.lower(),
                                     plain=plain, diacritic=marked.lower(), meaning=gloss, shortGloss=gloss,
                                     kind="tujia" if units else "phonetic", frequency=0, romanAliases=alias,
                                     recommended="".join(units), hanUnits="|".join(units)))


if __name__ == "__main__":
    build(Path(sys.argv[1]))
