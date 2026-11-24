#!/usr/bin/env python3
"""Select reviewed whole-word loans whose attested donor readings agree tone for tone."""
import argparse
import csv
import gzip
import itertools
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("site", type=Path, help="bizic-dictionary/site")
    cli.add_argument("readings", type=Path, help="bizic-translator/data/loan-readings-north.tsv")
    cli.add_argument("web", type=Path, help="directory containing words.js")
    args = cli.parse_args()
    base = json.loads((args.web / "words.js").read_text().split("=", 1)[1].split(";\nconst soundVariants=")[0])
    spellings = {row["id"]: row["word"] for row in base}
    readings = defaultdict(list)
    with args.readings.open(encoding="utf-8") as fp:
        for row in csv.DictReader(fp, delimiter="\t"):
            readings[row["汉字"]].append(row["IPA"])
    with gzip.open(args.site / "dictionary.json.gz", "rt", encoding="utf-8") as fp:
        words = {word["id"]: word for word in json.load(fp)["words"]}
    selected = {}
    for key, spelling in spellings.items():
        for note in words[key].get("editorialNotes", []):
            if note.get("kind") != "借词来源" or note.get("scope") != "whole" or note.get("donorVariety") != "汉语":
                continue
            han = note["donor"]
            attested = {v["ipa"].replace(" ", "") for v in note.get("reviewedForms", [])}
            reading = next(("".join(v) for v in itertools.product(*(readings[c] for c in han))
                            if "".join(v) in attested), None)
            if reading:
                selected[key] = [key, spelling, han, key, reading, reading, "词典整词借源及北部字音表"]
    # Additional whole-word forms explicitly established for this orthography.
    for key in ["w714", "w715", "w716"]:
        han = words[key]["zh"]
        selected[key] = [key, spellings[key], han, key, words[key]["term"], "/".join(readings[han]), "约定借词本字"]
    song = words["stedt-rn-288885"]
    assert song["zh"] == "山歌" and song["term"] == "sã⁵⁵ko⁵⁵"
    selected["xu213-2-1"] = ["xu213-2-1", "sangxgox", song["zh"], song["id"], song["term"],
                             " / ".join(",".join(readings[c]) for c in song["zh"]), "词典及龙山歌例，约定本字"]
    with (ROOT/"tools/data/plain-han-loans.tsv").open("w", encoding="utf-8", newline="") as fp:
        writer = csv.writer(fp, delimiter="\t", lineterminator="\n")
        writer.writerow(["id", "ye", "han", "dictionaryId", "attested", "localReading", "basis"])
        writer.writerows(selected[k] for k in sorted(selected))
    print(f"{len(selected)} plain-Han lexical entries")


if __name__ == "__main__":
    main()
