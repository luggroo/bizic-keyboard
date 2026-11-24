#!/usr/bin/env python3
"""Build input-only readings for the frequency-ranked Chinese fallback vocabulary."""
import argparse
import csv
import re
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

from import_mandarin import PYPINYIN, checked

DATA = Path(__file__).resolve().parent / "data"
EXAMPLE = re.compile(r"[\u3400-\u9fff~]+")


def build(source, vocabulary, out):
    from pypinyin import lazy_pinyin
    sys.path.insert(0, str(source / "src"))
    from tujia_lex.loan_lookup import local_readings, local_table

    with vocabulary.open(encoding="utf-8") as stream:
        words = {han: key for key, han, _ in csv.reader(stream, delimiter="\t")}
    local = defaultdict(set)
    examples = defaultdict(set)
    for han in local_table()["characters"]:
        for row in local_readings(han)["characters"][0]["readings"]:
            reading = row["input_key"]
            if not reading:
                continue
            local[han].add(reading)
            if row["north_spelling"]:
                local[han].add(row["north_spelling"])
            for part in re.split(r"[,，;；:：、]", row["note"]):
                if "~" in part and EXAMPLE.fullmatch(part):
                    phrase = part.replace("~", han)
                    if phrase in words and len(phrase) > 1:
                        examples[phrase].add((part, reading))

    aliases = defaultdict(set)
    for han, key in words.items():
        if len(han) == 1:
            aliases[han].update(local.get(han, ()))
            continue
        syllables = lazy_pinyin(han)
        if "".join(syllables) != key or len(syllables) != len(han):
            raise ValueError(f"Phrase pronunciation changed for {han}")
        if any(s.endswith(("an", "ang")) for s in syllables):
            aliases[han].add("'".join(syllables))
        for template, reading in examples.get(han, ()):
            variant = [reading if c == "~" else s for c, s in zip(template, syllables)]
            if variant != syllables:
                aliases[han].add("'".join(variant))

    with out.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(["han", "romanAliases"])
        for han in sorted(aliases):
            readings = aliases[han] - {words[han]}
            if readings:
                writer.writerow([han, " ".join(sorted(readings))])


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("translator", type=Path, help="bizic-translator checkout with the compiled Jishou/Chongqing table")
    cli.add_argument("pypinyin", type=Path, help="pypinyin-0.55.0 wheel")
    cli.add_argument("--vocabulary", type=Path, default=DATA / "mandarin.tsv")
    cli.add_argument("--out", type=Path, default=DATA / "mandarin-reading-aliases.tsv")
    args = cli.parse_args()
    with tempfile.TemporaryDirectory() as temp, checked(args.pypinyin, PYPINYIN) as wheel:
        wheel.extractall(temp)
        sys.path.insert(0, temp)
        build(args.translator, args.vocabulary, args.out)
