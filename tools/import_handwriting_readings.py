"""Build same-tone Han neighbours from translate-tujia's regional witnesses.

Usage: python tools/import_handwriting_readings.py ../bizic-translator/data/loan-sources
Source revision: luggroo/bizic-translator cd7a69207eaf11471a3670e5c949a53f2b78268f.
Includes the attested literary and colloquial readings in Li Qiqun (2002), pp. 37–40.
The MCPDict source is covered by shared/bundle/Assets/dialect-data-LICENSE.txt.
"""
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

from opencc import OpenCC


def build(source):
    simplified = OpenCC("t2s")
    cities = [defaultdict(set), defaultdict(set)]

    def add(table, graph, ipa):
        if len(graph) != 1 or graph == "□" or not re.fullmatch(r"[^\s\d|·]+[1-9]", ipa):
            return
        for form in {graph, simplified.convert(graph)}:
            if len(form) == 1:
                table[form].add(ipa.replace("ɑ", "a"))

    for table, city in zip(cities, ["jishou", "chongqing"]):
        with (source / (city + ".tsv")).open(encoding="utf-8") as stream:
            for graph, ipa, *_ in csv.reader(stream, delimiter="\t", quoting=csv.QUOTE_NONE):
                add(table, graph, ipa)
    with (source / "jishou-li-2002.tsv").open(encoding="utf-8") as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            for register in ["colloquial", "literary"]:
                for ipa in row[register].split("|"):
                    add(cities[0], row["graph"], ipa)

    def nasal(ipa):
        return re.sub(r"aŋ(?=\d$)", "an", ipa)

    exact, nearby = [], []
    for city in cities:
        same, close = defaultdict(set), defaultdict(set)
        for graph, sounds in city.items():
            for ipa in sounds:
                same[ipa].add(graph)
                close[nasal(ipa)].add(graph)
        exact.append(same)
        nearby.append(close)
    yield "han\tjishou\tchongqing\tnasal"
    for graph in sorted(cities[0].keys() | cities[1].keys()):
        groups = [set(), set(), set()]
        seen = {graph}
        for i, city in enumerate(cities):
            excluded = cities[0] if i == 1 and graph in cities[0] else ()
            for ipa in city.get(graph, ()):
                groups[i].update(peer for peer in exact[i][ipa] if peer not in excluded)
                groups[2].update(peer for peer in nearby[i][nasal(ipa)] if peer not in excluded)
        fields = [graph]
        for group in groups:
            fields.append("".join(sorted(group - seen)))
            seen.update(group)
        if any(fields[1:]):
            yield "\t".join(field or "-" for field in fields)


if __name__ == "__main__":
    out = Path(__file__).resolve().parents[1] / "shared/bundle/Assets/handwriting-readings.tsv"
    out.write_text("\n".join(build(Path(sys.argv[1]))) + "\n", encoding="utf-8")
