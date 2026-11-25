#!/usr/bin/env python3
"""Tag the keyboard's ranked IPA shortlist using the dictionary's attestations."""

import argparse
from collections import Counter, defaultdict
import gzip
import json
from pathlib import Path
import re
import unicodedata


VARIETIES = {"北部": 1, "南部": 2}


def ipa_key(text):
    return unicodedata.normalize("NFC", "".join(text.split()).replace("g", "ɡ"))


def glosses(text):
    return {text.strip(), *(part.strip() for part in re.split(r"[/／、；;]", text))} - {""}


def tagged_readings(words):
    matches = defaultdict(int)
    for word in words:
        attestations = {item["id"]: item for item in word["attestations"]}
        dialects = set(word["dialects"])
        for notation in word["notations"]["ipa"]:
            form = ipa_key(notation["text"])
            for attestation_id in notation["attestationIds"]:
                item = attestations[attestation_id]
                dialect = item.get("dialect")
                if dialect not in VARIETIES:
                    continue
                readings = glosses(item.get("zh", ""))
                if dialects == {dialect}:
                    for gloss in [word.get("zh", ""), *word.get("zhVariants", [])]:
                        readings.update(glosses(gloss))
                for gloss in readings:
                    matches[form, gloss] |= VARIETIES[dialect]
    return matches


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dictionary", type=Path, help="The site's dictionary.json.gz")
    parser.add_argument("source_commit", help="Git commit of the dictionary snapshot")
    parser.add_argument("shortlist", type=Path, help="Existing, ranked ipa-completions.tsv")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    with gzip.open(args.dictionary, "rt", encoding="utf-8") as source:
        matches = tagged_readings(json.load(source)["words"])
    rows = ["# Dialect mask (1 north, 2 south, 3 both), IPA, Chinese gloss",
            "# https://github.com/luggroo/bizic-dictionary/blob/" + args.source_commit + "/site/dictionary.json.gz"]
    counts = Counter()
    for row in args.shortlist.read_text(encoding="utf-8").splitlines():
        if row.startswith("#") or not row:
            continue
        form, gloss = row.split("\t")[-2:]
        mask = matches[ipa_key(form), gloss]
        if not mask:
            parts = glosses(gloss) - {gloss}
            if parts:
                mask = 3
                for part in parts:
                    mask &= matches[ipa_key(form), part]
        counts[mask] += 1
        if mask:
            rows.append(f"{mask}\t{form}\t{gloss}")
    args.output.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print("north only:", counts[1], "south only:", counts[2], "both:", counts[3], "excluded:", counts[0])


if __name__ == "__main__":
    main()
