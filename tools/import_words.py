#!/usr/bin/env python3
"""Build the shared lexicon and optional browser ranking data from a local tujia.biz snapshot."""
import argparse
import csv
import gzip
import hashlib
import json
import re
import subprocess
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from shutil import copyfile
from han_orthography import DATA, TONES, Orthography, PLAIN_HAN_LOANS
from keyboard_gloss import update_row

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/"tools/story-demo"))
from longshan_spelling import reviewed_glosses
FIELDS = "id word key meaning recommended aliases variants frequency kind shortGloss plain diacritic hanUnits romanAliases presentation".split()
DIGITS = str.maketrans("⁰¹²³⁴⁵⁶⁷⁸⁹", "0123456789")
# Use the concise reading that helps tell homophones apart; keep the original meaning intact.
SHORT = {
    "w684": "吃", "w687": "干", "w1827": "次", "w611": "慢", "w510": "穿衣",
    "w1467": "姑母", "w217": "泥土", "w2740": "起", "w1779": "煎", "w1044": "头巾",
    "w754": "热", "w865": "脱", "w309": "小孩", "w838": "含", "w2272": "田姓",
    "w1596": "忍受", "w958": "脚步", "w512": "跌倒", "w624": "穿透", "w441": "尺",
    "w955": "几", "w2652": "嘴", "w514": "往下走", "w515": "轻抚", "w1456": "码放",
    "w1457": "人家", "w1718": "石墙",
    "ls60-68": "早早吃饭", "ls60-79": "有人喊",
}
# Exact equivalences in the source annotation, for counting only.
SENSES = {
    "xu213-1-1":{"1pl"}, "xu213-1-4":{"INJ"}, "xu213-2-3":{"EMP"},
    "xu213-4-2":{"STRP"}, "xu213-4-8":{"IND"},
    "w309": {"孩童", "孩子"}, "w1585": {"1sg", "1SG", "第一人称单数"},
    "w1469": {"1pl", "1PL"},
    "w1421": {"人"}, "w514": {"下来"}, "w958": {"步子"},
}
NORTH_LOCATIONS = {"靛房", "永顺", "他砂", "大坝", "坡脚"}


def load(path):
    if path.suffix == ".gz":
        with gzip.open(path, "rt", encoding="utf-8") as fp:
            return json.load(fp)
    return json.loads(path.read_text(encoding="utf-8"))


def demo_rows(demo):
    left, right = (demo / "words.js").read_text(encoding="utf-8").split(";\nconst soundVariants=", 1)
    entries = json.loads(left.split("=", 1)[1])
    variants = json.loads(right.rstrip().rstrip(";"))
    rows = []
    for e in entries:
        alternatives = [variants[s] for s in e["syllables"]]
        if not all(alternatives) or len(alternatives) != len(e["hanUnits"]):
            raise ValueError(f"Missing syllable data in {e['id']}")
        row = {k:e[k] for k in FIELDS[:5]}
        row["hanUnits"] = "|".join(e["hanUnits"])
        row.update(aliases=" ".join(e["aliases"]), variants=" ".join(alternatives), kind="tujia")
        rows.append(row)
    return rows


def orthography_rows():
    rows = []
    for base, row in Orthography.from_file().rows.items():
        for tone, suffix in enumerate(TONES, 1):
            word = base + suffix
            han = row['t'+str(tone)]
            rows.append(dict(id='ortho-'+word, word=word, key=base, meaning='音节', recommended=han,
                             kind='syllable', frequency=0, shortGloss='音节', plain=base, hanUnits=han))
    glosses = {'nie':'领属','niexi':'名物化','mo':'情态','-bo':'处所','-lie':'施事','-xi':'名物化',
               '-xi ge':'状态补语','ge':'状态','la':'进行','-ɪ':'将来','-u':'完成'}
    with (DATA/'orthography-grammar.tsv').open() as stream:
        for row in csv.DictReader(stream, delimiter='\t'):
            part = row['morpheme']
            is_suffix = part in ('-ɪ', '-u')
            key = part.replace('ɪ','i') if is_suffix else part.lstrip('-').replace(' ','')
            han = row['form']
            rows.append(dict(id='grammar-'+key, word=key, key=key, plain=key, diacritic=key,
                             meaning=row['role'], shortGloss=glosses[part], frequency=0, kind='grammar',
                             recommended=han, hanUnits=han if is_suffix else '|'.join(han)))
    return rows


def context_words(path):
    orthography = Orthography.from_file()
    with path.open(encoding="utf-8") as src:
        for record in csv.DictReader(src, delimiter="\t"):
            parts = record["syllables"].split()
            units = orthography.units(parts, [""] * len(parts))
            if not all(units):
                raise ValueError(f"No reference character for {record['id']}: {parts}")
            word = "".join(parts)
            yield dict(id=record["id"], word=word, key="".join(p[:-1] for p in parts),
                       meaning=record["meaning"], shortGloss=record["shortGloss"], kind="tujia",
                       recommended="".join(units), hanUnits="|".join(units),
                       variants=" ".join(units))


def native_forms(value):
    return re.sub(r"[\s'’\-]+", "", value.casefold())


def clean_gloss(gloss):
    return gloss.casefold().strip(" 。？！、")


def gloss_parts(gloss):
    values = {gloss}
    values.update(re.split(r"[、/／,，;；]", gloss))
    return {clean_gloss(part) for part in values if part}


def spellings(site, rows, annotated, dictionary):
    ipa = {g["ipa"] for text in annotated for block in text["blocks"] for g in block.get("glosses", [])}
    ids = {w["id"] for w in rows}
    selected = [w for w in dictionary["words"] if w["id"] in ids]
    result = subprocess.run(["node", str(ROOT / "tools/site_spellings.cjs"), str(site)],
        input=json.dumps({"words": [row["word"] for row in rows], "ipa": sorted(ipa), "entries": selected}),
        text=True, capture_output=True, check=True)
    return json.loads(result.stdout)


def corpus_counts(rows, dictionary, data, annotated, longshan, ipa):
    # Never assign an unglossed homophone to all its senses. Consult the *whole*
    # northern dictionary, including homophones outside this smaller keyboard.
    entries = {r["id"]:r for r in rows}
    candidates = defaultdict(set)
    senses = defaultdict(set)
    brassett = defaultdict(set)
    for entry in dictionary["words"]:
        if "北部" not in entry.get("dialects", [entry.get("dialect")]):
            continue
        index = entry["id"]
        for notation in entry.get("notations", {}).get("ye", []):
            candidates[native_forms(notation["text"])].add(index)
        for notation in entry.get("notations", {}).get("brassett", []):
            form = native_forms(notation["text"].translate(DIGITS))
            brassett[form].add(index)
        for term in [entry.get("zh",""), *entry.get("zhVariants", [])]:
            senses[index].update(gloss_parts(term))
    for row in rows:
        i = row["id"]
        for form in [row["word"], *row.get("romanAliases","").split()]:
            candidates[native_forms(form)].add(i)
        senses[i].update(gloss_parts(row["meaning"]))
        senses[i].add(clean_gloss(row["shortGloss"]))
        senses[i].update(clean_gloss(g) for g in SENSES.get(i, ()))
    counts = Counter()
    stats = Counter()

    def aligned(source, ye, zh):
        stats[source + "Units"] += 1
        ids = candidates.get(native_forms(ye), set())
        # Different dictionary editions can have IDs with the same form *and*
        # labelled sense. One observed token counts once for the matching IME sense.
        matched = [i for i in ids if i in entries and senses[i] & gloss_parts(zh)]
        if len(matched) == 1:
            counts[matched[0]] += 1
            stats[source + "Matches"] += 1

    for text in annotated:
        if text["dialect"] != "北部":
            continue
        stats["annotatedTexts"] += 1
        for block in text["blocks"]:
            for gloss in block.get("glosses", []):
                aligned("annotated", ipa[gloss["ipa"]], gloss["zh"])
    for text in longshan:
        if text["dialect"] != "北部" or not any(b.get("glosses") for b in text["blocks"]):
            continue
        stats["longshanTexts"] += 1
        for block in text["blocks"]:
            glosses = (reviewed_glosses(block) if text["id"]=="tlp-longshan-stories-02-01" else block.get("glosses", []))
            for gloss in glosses:
                aligned("longshan", gloss["ye"], gloss["zh"])
    native_token = re.compile(r"(?<![a-z0-9])(?:[a-z]+[1-4])+(?![a-z0-9])", re.I)
    for text in data["corpus"]:
        locations = set(text.get("locations", []))
        if not locations or not locations <= NORTH_LOCATIONS or not text.get("text"):
            continue
        stats["brassettTexts"] += 1
        for token in native_token.findall(text["text"]):
            stats["brassettUnits"] += 1
            ids = brassett.get(token.lower(), set())
            if len(ids) != 1:
                continue
            i = next(iter(ids))
            if i in entries:
                counts[i] += 1
                stats["brassettMatches"] += 1
    return counts, stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("demo", type=Path, help="Demo dist directory containing words.js")
    parser.add_argument("--dictionary-site", type=Path, required=True, help="luggroo/bizic-dictionary/site")
    parser.add_argument("--out", type=Path, default=ROOT/"shared/bundle/Assets")
    parser.add_argument("--story", type=Path, default=ROOT/"tools/data/story-words.tsv")
    parser.add_argument("--mandarin", type=Path, default=ROOT/"tools/data/mandarin.tsv")
    parser.add_argument("--mandarin-readings", type=Path, default=DATA/"mandarin-reading-aliases.tsv",
                        help="Additional Chinese reading keys and attested syllable boundaries")
    parser.add_argument("--tujia-frequencies", type=Path, help="Optional tab-separated id/frequency counts replacing estimates for listed Tujia IDs")
    parser.add_argument("--web", type=Path, help="Generate the browser's ranking.js")
    args = parser.parse_args()
    site = args.dictionary_site
    rows = demo_rows(args.demo)
    if args.story.exists():
        with args.story.open(encoding="utf-8", newline="") as stream:
            rows.extend(csv.DictReader(stream, delimiter="\t"))
    context_source = DATA / "context-words.tsv"
    rows.extend(context_words(context_source))
    ids = [r["id"] for r in rows]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate keyboard word ID")
    annotated = load(site/"annotated-books.json")
    longshan = load(site/"longshan-texts.json")
    reference = orthography_rows()
    dictionary = load(site/"dictionary.json.gz")
    forms = spellings(site, rows + reference, annotated, dictionary)
    with (DATA/"story-spelling-aliases.tsv").open() as src:
        historical = {r["id"]:r["romanAliases"].split() for r in csv.DictReader(src,delimiter="\t")}
    for row in rows:
        raw = row["word"]
        aliases = historical.get(row["id"],[])[:]
        if row["id"] in forms["display"]:
            # Pick the same witnessed reading, retaining the site's vocabulary boundaries.
            display = forms["display"][row["id"]]
            accent = forms["accented"][raw]
            ye = next((v for v in display["ye"] if native_forms(v)==native_forms(raw)), None)
            marked = next((v for v in display["accent"] if native_forms(v)==native_forms(accent)), None)
            if ye is None or marked is None:
                raise ValueError(f"Unmatched school spelling for {row['id']}: {raw} vs {display}")
            if raw != ye: aliases.extend([raw, accent])
            row.update(word=ye, diacritic=marked)
        row["romanAliases"] = " ".join(dict.fromkeys(aliases))

    for r in rows:
        if r["id"] not in PLAIN_HAN_LOANS:
            continue
        loan = PLAIN_HAN_LOANS[r["id"]]
        if native_forms(r["word"]) != native_forms(loan["ye"]):
            raise ValueError(f"Loan spelling mismatch for {r['id']}")
        if r["recommended"] != loan["han"]:
            r["aliases"] = " ".join(dict.fromkeys([*r["aliases"].split(), r["recommended"]]))
        r.update(recommended=loan["han"], hanUnits="|".join(loan["han"]), presentation="plainHanLoan")

    for r in rows + reference:
        r["shortGloss"] = SHORT.get(r["id"], r.get("shortGloss") or r["meaning"])
        if not r.get("diacritic"):
            r["diacritic"] = forms["accented"][r["word"]]
        if not r.get("plain"):
            # Preserve ü and ê; remove only the four tone accents and optional syllable separators.
            base = unicodedata.normalize("NFD", r["diacritic"])
            base = "".join(c for c in base if c not in "\u0304\u0301\u030c\u0300")
            r["plain"] = native_forms(unicodedata.normalize("NFC", base))
    for row in rows:
        update_row(row)
    counts, stats = corpus_counts(rows, dictionary, load(site/"data.json.gz"),
                                  annotated, longshan, forms["spellings"])
    observed_rows = sum(counts[row["id"]] > 0 for row in rows)
    overrides = {}
    if args.tujia_frequencies:
        with args.tujia_frequencies.open(encoding="utf-8-sig", newline="") as stream:
            records = csv.DictReader(stream, delimiter="\t")
            if records.fieldnames != ["id", "frequency"]:
                parser.error("--tujia-frequencies columns must be id and frequency")
            valid_ids = set(ids)
            for record in records:
                word_id, value = record["id"], record["frequency"]
                if word_id not in valid_ids or word_id in overrides:
                    parser.error(f"Unknown or repeated Tujia word ID: {word_id}")
                if not value or not value.isascii() or not value.isdecimal() or int(value) >= 2 ** 64:
                    parser.error(f"Invalid nonnegative frequency for {word_id}")
                overrides[word_id] = int(value)
    for row in rows:
        row["frequency"] = overrides.get(row["id"], counts[row["id"]])
    mandarin = defaultdict(list)
    with args.mandarin_readings.open(encoding="utf-8") as stream:
        reading_rows = list(csv.DictReader(stream, delimiter="\t"))
    chinese_readings = {r["han"]: r["romanAliases"] for r in reading_rows}
    if len(chinese_readings) != len(reading_rows):
        raise ValueError("Duplicate form in Chinese reading aliases")
    supplemental = [(r["supplementalMandarinKey"], r["han"], 0) for r in reading_rows if r.get("supplementalMandarinKey")]
    with args.mandarin.open(encoding="utf-8") as stream:
        measured = list(csv.reader(stream, delimiter="\t"))
    measured_han = {han for _, han, _ in measured}
    if any(han in measured_han for _, han, _ in supplemental):
        raise ValueError("A supplemental Han character already has a measured frequency")
    args.out.mkdir(parents=True, exist_ok=True)
    with (args.out/"words.tsv").open("w",encoding="utf-8",newline="") as out:
        writer = csv.DictWriter(out, FIELDS, delimiter="\t", lineterminator="\n", quoting=csv.QUOTE_NONE)
        writer.writeheader()
        writer.writerows(rows)
        writer.writerows(reference)
        for key, han, zipf100 in [*measured, *supplemental]:
            writer.writerow(dict(id="zh-"+han, word=key, key=key, recommended=han, kind="zh",
                                 frequency=zipf100, romanAliases=chinese_readings.get(han, "")))
            mandarin[key].append([han, int(zipf100)])
    copyfile(args.demo/"dialect-data-LICENSE.txt",args.out/"dialect-data-LICENSE.txt")
    if args.web:
        meta = {r["id"]:[r["frequency"],r["shortGloss"],r["plain"],r["diacritic"],r["word"],r["romanAliases"].split()] for r in rows}
        refs = [[r["id"], r["word"], r["plain"], r["diacritic"], r["recommended"], r["shortGloss"], r["meaning"], r["hanUnits"]] for r in reference]
        args.web.write_text("window.tujiaRanking="+json.dumps({"words":meta,"mandarin":mandarin,"reference":refs,"plainHanLoans":{r["id"]:r["recommended"] for r in rows if r.get("presentation")=="plainHanLoan"}},
            ensure_ascii=False,separators=(",",":"))+";\n",encoding="utf-8")
    files = ["annotated-books.json", "longshan-texts.json", "data.json.gz", "dictionary.json.gz",
             "longshan-texts.js", "notation-ui.js", "notation-profiles.js", "lexical-reviews.js", "spelling-boundaries.js"]
    provenance = {
        "source":"https://github.com/luggroo/bizic-dictionary/tree/b882110a9e86b52536f7b8b4bf8179ae5f70f2f0/site",
        "method":"Count northern aligned word or word-group annotations with matching tone-marked spelling and Chinese sense; omit unresolved homophones. In the geographically identified northern Brassett transcripts, count exact numeric-tone word spans only when there is one dictionary ID for the spelling. Undetermined sense and unresolved token boundaries are omitted. Counts estimate collection usage; zero means no qualifying observation.",
        "sourcesSHA256":{f:hashlib.sha256((site/f).read_bytes()).hexdigest() for f in files},
        "inputSHA256":{p.name:hashlib.sha256(p.read_bytes()).hexdigest()
                       for p in (args.demo/"words.js", args.story, context_source, DATA/"story-spelling-aliases.tsv",
                                 DATA/"plain-han-loans.tsv", args.mandarin, args.mandarin_readings) if p.exists()},
        "mandarinReadingSources": {
            "local": ["https://github.com/osfans/MCPDict/blob/da0bd03ec9417f0edfdf7aeba6c21cf693df43cf/tools/tables/output/吉首.tsv",
                      "https://github.com/osfans/MCPDict/blob/da0bd03ec9417f0edfdf7aeba6c21cf693df43cf/tools/tables/output/重慶.tsv"],
            "book": "李启群《吉首方言研究》，民族出版社，2002，第37–40页。ISBN 9787105050574。",
            "method": "Use modern Jishou and Chongqing local readings, including Li (2002) register examples. Phrase alternatives are based on explicit examples. Mixed keys retain surrounding Mandarin syllables; local-only keys use attested character readings in the same city. A missing single character with distinct colloquial and literary readings in one city receives a Mandarin key from pypinyin 0.55.0 and an unobserved frequency of zero.",
        },
        "stats":dict(stats), "tujiaRows":len(rows), "referenceRows":len(reference), "observedRows":observed_rows,
        "mandarinRows":sum(map(len, mandarin.values())),
        "measuredMandarinRows":len(measured), "unrankedRegisterCharacters":len(supplemental),
    }
    if args.tujia_frequencies:
        provenance["frequencyOverrides"] = {
            "file": args.tujia_frequencies.name,
            "sha256": hashlib.sha256(args.tujia_frequencies.read_bytes()).hexdigest(),
            "rows": len(overrides),
        }
    licenses = args.out/"licenses"
    licenses.mkdir(exist_ok=True)
    copyfile(DATA/"orthography-revisions.json",licenses/"orthography-revisions.json")
    (licenses/"tujia-frequencies.json").write_text(json.dumps(provenance,ensure_ascii=False,indent=2)+"\n")
    if args.web:
        web_licenses = args.web.parent/"licenses"
        web_licenses.mkdir(exist_ok=True)
        for source in licenses.iterdir():
            if source.is_file():
                copyfile(source, web_licenses/source.name)
    print(json.dumps({k:v for k,v in provenance.items() if k in ("stats","tujiaRows","observedRows","mandarinRows")},ensure_ascii=False))


if __name__ == "__main__":
    main()
