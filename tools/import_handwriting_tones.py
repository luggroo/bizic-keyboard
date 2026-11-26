"""Build Android's Han-to-tone families from bizic-translator/data/loan-readings-north.tsv.

The source records the preferred regional pronunciation. The other three tones
are input alternatives. Their meanings come from the keyboard lexicon at lookup.
"""
import csv
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

MARKS = dict(zip("xfrv", "\u0304\u0301\u030c\u0300"))
PITCH = dict(zip("xfrv", ("⁵⁵", "²⁴", "²¹", "⁵¹")))
ORDER = {"x": "xvrf", "v": "vxrf", "r": "rxvf", "f": "fxvr"}


def rows(source):
    families = defaultdict(dict)
    for row in csv.DictReader(source, delimiter="\t"):
        graph, marked, ipa = row["汉字"], unicodedata.normalize("NFD", row["调号拼音"]), row["IPA"]
        tone, = (t for t, accent in MARKS.items() if accent in marked)
        if len(graph) != 1 or not ipa.endswith(PITCH[tone]):
            raise ValueError(f"Inconsistent reading: {row}")
        base = marked.replace(MARKS[tone], "")
        family = families[graph].setdefault(base, (marked.replace(MARKS[tone], "{}"), ipa[:-len(PITCH[tone])], []))
        if tone not in family[2]:
            family[2].append(tone)

    grouped = defaultdict(str)
    for graph, readings in families.items():
        ye, marked, ipa = [], [], []
        for base, (accented, stem, preferred) in readings.items():
            for tone in dict.fromkeys([*preferred, *ORDER[preferred[0]]]):
                ye.append(base + tone)
                marked.append(unicodedata.normalize("NFC", accented.format(MARKS[tone])))
                ipa.append(stem + PITCH[tone])
        grouped[tuple(" ".join(forms) for forms in (ye, marked, ipa))] += graph
    for forms, characters in sorted(grouped.items()):
        yield "\t".join((*forms, characters))


if __name__ == "__main__":
    dest = Path(__file__).resolve().parents[1] / "app/src/main/assets/handwriting-tones.tsv"
    with Path(sys.argv[1]).open(encoding="utf-8") as source, dest.open("w", encoding="utf-8") as output:
        print("# Northern Han loan readings, grouped by identical conversion choices.", file=output)
        print("# https://github.com/luggroo/bizic-translator/blob/cd7a69207eaf11471a3670e5c949a53f2b78268f/data/loan-readings-north.tsv", file=output)
        for row in rows(source):
            print(row, file=output)
