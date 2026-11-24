#!/usr/bin/env python3
"""Build the keyboard's reviewed original-character forms from school and project records."""
import argparse
import csv
import gzip
import json
from collections import Counter, defaultdict
from pathlib import Path

from import_words import DATA, context_words, demo_rows, native_forms

ROOT = Path(__file__).resolve().parents[1]

# LS-60 uses these forms in different paragraphs. The repeated term has a project
# writing convention; the school's screening file lists its origin as unresolved.
BROTHERS = {"ls60-18": "goxgox", "ls60-119": "goxgov", "ls60-166": "goxgor"}
PARTS = {
    "ls60-67": ("早", "w2641", "开头 zaov 对词典“早”；全词是早饭。"),
    "ls60-68": ("早早", "w2641", "开头两个 zaov 对“早”；其后另有吃饭的成分。"),
    "ls60-167": ("输", "w2156", "sux 对“输”；liaor 单独的来源未核定。"),
    "w233": ("半日", "", "项目约定的汉语成分“半日”接土家语 caix。"),
}
PENDING = {
    "ls60-72": ("就", "w1068", "词典 jiux 已标整词借源，故事读 jiur。"),
    "ls60-150": ("就", "w1068", "词典 jiux 已标整词借源，故事读 jiuf。"),
    "ls60-20": ("", "w2956", "arkox 的词典义是哥哥；与重叠式分记。"),
    "ls60-128": ("", "w124", "arkov 的既有审校排除近代汉借。"),
    "ls60-21": ("", "", "arkoxar 含独立呼语音节 ar；原句译为哥哥啊。"),
    "ls60-70": ("陈二郎", "", "故事人名按原文写；专名拼写本身不能判定借源。"),
    "ls60-120": ("二郎", "", "故事人名简称按原文写。"),
}


def table(path):
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def write(path, header, rows):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("site", type=Path, help="bizic-dictionary/site")
    cli.add_argument("readings", type=Path, help="bizic-translator/data/loan-readings-north.tsv")
    cli.add_argument("web", type=Path, help="directory containing words.js")
    args = cli.parse_args()
    rows = demo_rows(args.web) + table(DATA / "story-words.tsv") + list(context_words(DATA / "context-words.tsv"))
    vocabulary = {r["id"]: r for r in rows}
    assert len(rows) == len(vocabulary)
    readings = defaultdict(list)
    for r in table(args.readings):
        readings[r["汉字"]].append(r["IPA"])
    with gzip.open(args.site / "dictionary.json.gz", "rt", encoding="utf-8") as stream:
        words = {r["id"]: r for r in json.load(stream)["words"]}
    selected, findings = {}, {}

    def accept(key, han, source_id, sound, reason, status):
        local = " / ".join(",".join(readings[char]) or "未收" for char in han)
        selected[key] = [key, vocabulary[key]["word"], han, source_id, sound, local, reason]
        findings[key] = (status, han, source_id, reason)

    by_form = defaultdict(list)
    for d in words.values():
        if d.get("dialect") != "北部":
            continue
        notes = [n for n in d.get("editorialNotes", []) if n.get("kind") == "借词来源" and
                 n.get("scope") == "whole" and n.get("donorVariety") == "汉语" and
                 n.get("text", "").startswith("借自汉语")]
        for note in notes:
            if d["id"] in vocabulary:
                accept(d["id"], note["donor"], d["id"], "、".join(v["ipa"] for v in note["reviewedForms"]),
                       "词典明确标注整词汉借；保留土家语条目原调", "词典整词借源")
            for ye in d.get("notations", {}).get("ye", []):
                by_form[native_forms(ye["text"]), d["zh"]].append((d, note))
    for key, row in vocabulary.items():
        if key in selected:
            continue
        matches = by_form[native_forms(row["word"]), row["meaning"]]
        if len(matches) == 1:
            d, note = matches[0]
            accept(key, note["donor"], d["id"], "、".join(v["ipa"] for v in note["reviewedForms"]),
                   "原文的叶氏拼写、词义均与有整词借源标注的北部词条相同", "同音同义对齐借源")
    for key in ("w714", "w715", "w716"):
        d = words[key]
        accept(key, d["zh"], key, d["term"], "项目确认借词本字", "项目确认借词")
    song = words["stedt-rn-288885"]
    assert song["term"] == "sã⁵⁵ko⁵⁵" and song["zh"] == "山歌"
    accept("xu213-2-1", "山歌", song["id"], song["term"], "词典读音及龙山歌例，项目约定本字", "项目约定本字")
    texts = json.loads((args.site / "annotated-books.json").read_text(encoding="utf-8"))
    verse = next(doc for doc in texts if doc["id"] == "xu-tujia-annotated-2-13")
    assert any(g["zh"] == "过错" and g["ipa"] == "ko³⁵" for g in verse["blocks"][3]["glosses"])
    accept("xu213-4-5", "过", "", "ko³⁵", "龙山山歌第四行“过错”，项目确认借词本字", "项目确认借词")
    for key, spelling in BROTHERS.items():
        assert vocabulary[key]["word"] == spelling and vocabulary[key]["meaning"] == "哥哥"
        accept(key, "哥哥", "w809", spelling, "项目约定用本字；学堂 w809 的借源仍待审", "项目约定本字")
    for k, (han, source, reason) in PARTS.items():
        if k not in vocabulary:
            raise ValueError(f"Unknown partial loan {k}")
        findings[k] = ("部分成分", han, source, reason)
    for k, (han, source, reason) in PENDING.items():
        assert k in vocabulary and k not in selected
        findings[k] = ("待考或保留原注", han, source, reason)
    write(DATA / "plain-han-loans.tsv", ["id", "ye", "han", "dictionaryId", "attested", "localReading", "basis"],
          (selected[k] for k in sorted(selected)))
    audit = []
    for k, r in vocabulary.items():
        status, han, source, reason = findings.get(k, ("未获确证", "", "", ""))
        audit.append((k, r["word"], r["meaning"], status, han, source, reason))
    write(ROOT / "qa/results/han-source-review.tsv", ["id", "ye", "meaning", "status", "han", "dictionaryId", "basis"], audit)
    print(f"{len(selected)} original-character forms; {len(audit)} entries screened", dict(Counter(r[3] for r in audit)))


if __name__ == "__main__":
    main()
