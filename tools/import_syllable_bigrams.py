#!/usr/bin/env python3
"""Compile the ranked syllable pairs using the spelling display functions on tujia.biz."""
import argparse
import csv
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools/data/syllable-bigrams-ranked.txt"
ASSETS = ROOT / "shared/bundle/Assets"


def compile_table(site, output):
    pairs = [line.split() for line in SOURCE.read_text(encoding="utf-8").splitlines()]
    if len(pairs) != 1000 or any(len(row) != 2 for row in pairs):
        raise ValueError("Expected the 1,000 ranked two-syllable records")
    if len({tuple(row) for row in pairs}) != len(pairs):
        raise ValueError("Duplicate source pair")
    # TLP wunr (wũ²¹) corresponds to the site's northern regional form wongr.
    # Keep it distinct from ongr, which has its own separately ranked pair.
    with (ASSETS / "words.tsv").open(encoding="utf-8") as fp:
        sit = next(row for row in csv.DictReader(fp, delimiter="\t") if row["id"] == "w1658")
    if sit["meaning"] != "坐" or not {"wunr", "wongr"} <= set(sit["romanAliases"].split()):
        raise ValueError("Review the dictionary entry for the source spelling wunr")
    normalize = {"wunr": "wongr"}
    compiled = [(normalize.get(a, a), normalize.get(b, b)) for a, b in pairs]
    if any((a != "<s>" and not re.fullmatch("[a-z]+", a)) or not re.fullmatch("[a-z]+", b)
           for a, b in compiled):
        raise ValueError("Invalid source syllable or misplaced sentence boundary")
    words = sorted({w for row in compiled for w in row} - {"<s>"})
    result = subprocess.run(["node", str(ROOT / "tools/site_spellings.cjs"), str(site)],
                            input=json.dumps({"words": words, "ipa": []}), text=True,
                            capture_output=True, check=True)
    accents = json.loads(result.stdout)["accented"]
    with output.open("w", newline="", encoding="utf-8") as fp:
        writer = csv.writer(fp, delimiter="\t", lineterminator="\n")
        writer.writerow(["context", "next", "count", "rank", "contextAccent", "nextAccent"])
        for rank, (left, right) in enumerate(compiled, 1):
            writer.writerow([left, right, "", rank, "" if left == "<s>" else accents[left], accents[right]])
    print(f"{len(compiled)} ranked pairs, {sum(a == '<s>' for a, _ in compiled)} sentence starts")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("site", type=Path, help="site directory in bizic-dictionary")
    parser.add_argument("--output", type=Path, default=ASSETS / "syllable_bigrams.tsv")
    args = parser.parse_args()
    compile_table(args.site, args.output)
