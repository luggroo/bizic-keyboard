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
HAN_GRAPH = re.compile(r"[\u3400-\u9fff]")


def build(source, vocabulary, out):
    from opencc import OpenCC
    from pypinyin import lazy_pinyin
    simplified = OpenCC("t2s")
    sys.path.insert(0, str(source / "src"))
    from tujia_lex.loan_lookup import local_readings, local_table

    with vocabulary.open(encoding="utf-8") as stream:
        words = {han: key for key, han, _ in csv.reader(stream, delimiter="\t")}
    local = defaultdict(set)
    by_city = defaultdict(set)
    by_register = defaultdict(set)
    examples = defaultdict(set)
    supplementary = {}
    for han in local_table()["characters"]:
        registers = defaultdict(lambda: defaultdict(set))
        for row in local_readings(han)["characters"][0]["readings"]:
            reading = row["input_key"]
            if not reading:
                continue
            local[han].add(reading)
            if row["north_spelling"]:
                local[han].add(row["north_spelling"])
            # Identical IPA may have sources in different cities, or register labels
            # on only one source. Keep the note, city and register together.
            for witness in row.get("witnesses") or [row]:
                city, register = witness["city"], witness["register"]
                by_city[city, han].add(reading)
                if register in ("文", "白"):
                    registers[city][register].add(row["source_reading"])
                    by_register[city, han, register].add(reading)
                note = re.sub(r"[（(][^）)]*[）)]", "", witness.get("note") or "").replace("～", "~")
                for part in re.split(r"[,，;；:：、|]", note):
                    template = part.strip()
                    explicit_head = witness.get("page") and han in template
                    if not EXAMPLE.fullmatch(template) or not ("~" in template or explicit_head):
                        continue
                    phrase = template.replace("~", han)
                    if phrase not in words or len(phrase) < 2:
                        continue
                    marker = "~" if "~" in template else han
                    positions = tuple(i for i, c in enumerate(template) if c == marker)
                    examples[phrase].add((positions, reading, city, register))
        if han not in words and HAN_GRAPH.fullmatch(han) and simplified.convert(han) == han and any(
                any(white != literary for white in pair["白"] for literary in pair["文"])
                for pair in registers.values()):
            # Local sources supply the extra character; pypinyin only supplies its Mandarin key.
            standard = lazy_pinyin(han)[0]
            if standard and re.fullmatch(r"[a-züê]+", standard):
                supplementary[han] = standard

    aliases = defaultdict(set)
    vocabulary_keys = {**words, **supplementary}
    for han, key in vocabulary_keys.items():
        if len(han) == 1:
            aliases[han].update(local.get(han, ()))
            continue
        syllables = lazy_pinyin(han)
        if "".join(syllables) != key or len(syllables) != len(han):
            raise ValueError(f"Phrase pronunciation changed for {han}")
        if any(s.endswith(("an", "ang")) for s in syllables):
            aliases[han].add("'".join(syllables))
        attested = examples.get(han, ())
        context = defaultdict(set)
        for positions, reading, city, register in attested:
            for i in positions:
                context[city, i].add(reading)
        for positions, reading, city, register in attested:
            # The mixed key preserves the surrounding Mandarin spelling as a typing convenience.
            mixed = [reading if i in positions else base for i, base in enumerate(syllables)]
            if mixed != syllables:
                aliases[han].add("'".join(mixed))
            # Compose a wholly local key only when each other character has a unique
            # attested reading in this city, this word or the same explicit register.
            local_phrase = []
            for i, graph in enumerate(han):
                if i in positions:
                    local_phrase.append(reading)
                    continue
                options = context.get((city, i)) or by_register.get((city, graph, register)) or by_city.get((city, graph), set())
                if len(options) != 1:
                    break
                local_phrase.append(next(iter(options)))
            if len(local_phrase) == len(han) and local_phrase != syllables:
                aliases[han].add("'".join(local_phrase))

    with out.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(["han", "romanAliases", "supplementalMandarinKey"])
        for han in sorted(set(aliases) | supplementary.keys()):
            readings = aliases[han] - {vocabulary_keys[han]}
            if readings or han in supplementary:
                fields = [han, " ".join(sorted(readings))]
                if han in supplementary:
                    fields.append(supplementary[han])
                writer.writerow(fields)


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
