"""Build the Android supplementary lexicon from the site's northern dictionary."""
import csv
import gzip
import json
import re
import subprocess
import sys
import unicodedata
from collections import defaultdict
from functools import lru_cache
from pathlib import Path

from han_orthography import Orthography
from keyboard_gloss import update_row, ethnonym_variants, normalize_meaning

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "shared/bundle/Assets"
LOANS = ROOT / "tools/data/northern-han-loans.tsv"
LEGACY = ROOT / "tools/data/northern-legacy-forms.tsv"
SURVEY_LOANS = ROOT / "tools/data/survey-loan-forms.tsv"
SURVEY_COMPONENTS = ROOT / "tools/data/survey-loan-components.tsv"


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
    with LOANS.open(encoding="utf-8") as stream:
        loans = {(r['id'], r['ye']): r for r in csv.DictReader(stream, delimiter='\t')}
    with SURVEY_LOANS.open(encoding="utf-8") as stream:
        for row in csv.DictReader(stream, delimiter='\t'):
            key = (row['id'], row['ye'])
            if key in loans and loans[key]['han'] != row['han']:
                raise ValueError(f'Conflicting survey loan forms: {key}')
            loans[key] = row
    components = defaultdict(list)
    with SURVEY_COMPONENTS.open(encoding="utf-8") as stream:
        for row in csv.DictReader(stream, delimiter='\t'):
            components[(row['id'], row['ye'])].append(row)
    with LEGACY.open(encoding="utf-8") as stream:
        legacy = {(r['id'], r['ye']): r['aliases'].split() for r in csv.DictReader(stream, delimiter='\t')}
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
    survey = subprocess.run(["node", ROOT / "tools/site_survey_words.cjs", site],
                            check=True, capture_output=True, text=True)
    survey_words = json.loads(survey.stdout)
    added = []
    with (ASSETS / "northern-words.tsv").open("w") as stream:
        writer = csv.DictWriter(stream, fields, delimiter="\t", lineterminator="\n", quoting=csv.QUOTE_NONE, quotechar=None)
        for w in words:
            ident = w["id"]
            forms = displays[ident]
            ye, accent = forms["ye"], forms["accent"]
            if len(ye) != len(accent):
                raise ValueError(f"Unpaired spellings for {ident}")
            base = existing.get(ident)
            for i, (word, marked) in enumerate(zip(ye, accent)):
                compact = re.sub(r"[\s’'-]", "", word.lower())
                if base and compact == re.sub(r"[\s’'-]", "", base["word"].lower()):
                    continue
                units = han_units(compact, orthography)
                gloss = " ".join((w.get("zh") or w.get("en") or "").split())
                plain = unicodedata.normalize("NFC", "".join(c for c in unicodedata.normalize("NFD", marked.lower())
                                                            if c not in "\u0304\u0301\u030c\u0300"))
                row_id = ident if i == 0 and not base else f"{ident}@{i+1}"
                loan = loans.get((row_id, word.lower()))
                if loan and loan['meaning'] != normalize_meaning(gloss):
                    raise ValueError(f"Loan sense no longer aligns: {row_id}")
                old = ''.join(units)
                if loan:
                    units = list(loan['han'])
                aliases = legacy.get((row_id, word.lower()), [])[:]
                if loan and old != ''.join(units):
                    aliases.append(old)
                row = update_row(dict(id=row_id, word=word.lower(),
                                     plain=plain, diacritic=marked.lower(), meaning=gloss, shortGloss=gloss,
                                     kind="tujia" if units else "phonetic", frequency=0,
                                     recommended="".join(units), hanUnits="|".join(units),
                                     aliases=' '.join(dict.fromkeys(aliases)),
                                     presentation='plainHanLoan' if loan else ''))
                writer.writerow(row)
                added.extend(ethnonym_variants(row))
        for w in survey_words:
            for i, form in enumerate(w['forms']):
                word, marked = form['ye'].lower(), form['accent'].lower()
                compact = re.sub(r"[\s’'()-]", '', word)
                parts = form['parts']
                units = orthography.units(parts, [''] * len(parts)) if parts and all(parts) and ''.join(parts) == compact else []
                if units and not all(units):
                    # The source segmentation is authoritative. A reviewed loan
                    # component may supply an otherwise unknown syllable's glyph.
                    partial = units[:]
                    for component in components.get((w['id'] if i == 0 else f"{w['id']}@{i+1}", word), []):
                        start, end = int(component['start']), int(component['end'])
                        if (component['meaning'] == normalize_meaning(' '.join(w['zh'].split()))
                                and 1 <= start <= end <= len(partial)
                                and end - start + 1 == len(component['han'])):
                            partial[start - 1:end] = list(component['han'])
                    units = partial if all(partial) else han_units(compact, orthography)
                plain = unicodedata.normalize('NFC', ''.join(c for c in unicodedata.normalize('NFD', marked)
                                                            if c not in '\u0304\u0301\u030c\u0300'))
                row_id = w['id'] if i == 0 else f"{w['id']}@{i+1}"
                gloss = ' '.join(w['zh'].split())
                loan = loans.get((row_id, word))
                if loan and loan['meaning'] != normalize_meaning(gloss):
                    raise ValueError(f'Survey loan sense no longer aligns: {row_id}')
                old = ''.join(units)
                if loan:
                    units = list(loan['han'])
                row = update_row(dict(id=row_id, word=word, plain=plain, diacritic=marked,
                    meaning=gloss, shortGloss=gloss, kind='tujia' if units else 'phonetic', frequency=0,
                    recommended=''.join(units), hanUnits='|'.join(units),
                    aliases=old if loan and old != ''.join(units) else '',
                    presentation='plainHanLoan' if loan else ''))
                # A mixed word keeps its gloss and non-loan characters; only the
                # reviewed source syllables receive their Chinese donor spelling.
                if not loan and (parts_loan := components.get((row_id, word))):
                    if any(c['meaning'] != normalize_meaning(gloss) for c in parts_loan):
                        raise ValueError(f'Survey component sense no longer aligns: {row_id}')
                    current = row['hanUnits'].split('|') if row['hanUnits'] else []
                    if len(current) == len(parts):
                        original = row['recommended']
                        claims = {}
                        for c in parts_loan:
                            start, end = int(c['start']), int(c['end'])
                            if not (1 <= start <= end <= len(current) and end - start + 1 == len(c['han'])):
                                raise ValueError(f'Invalid survey component span: {row_id}')
                            for position, char in enumerate(c['han'], start - 1):
                                if position in claims and claims[position] != char:
                                    raise ValueError(f'Conflicting survey component spellings: {row_id}')
                                claims[position] = char
                        for position, char in claims.items():
                            current[position] = char
                        row['recommended'], row['hanUnits'] = ''.join(current), '|'.join(current)
                        if row['recommended'] != original:
                            row['aliases'] = ' '.join(dict.fromkeys([*row['aliases'].split(), original]))
                writer.writerow(row)
                added.extend(ethnonym_variants(row))
        for row in existing.values():
            added.extend(ethnonym_variants(row))
        writer.writerows(added)


if __name__ == "__main__":
    build(Path(sys.argv[1]))
