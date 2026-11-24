"""Build the six-paragraph note from the Longshan text, aligned gloss by gloss.

Usage: python3 tools/story-demo/prepare_story.py DICTIONARY_REPO TRANSLATOR_REPO WEB_DIST
"""
import csv
import json
import re
import subprocess
import sys
import unicodedata
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from han_orthography import REVISIONS, Orthography
from xu_song import make_song
from longshan_spelling import align, reviewed_glosses

dictionary, translator, web = map(Path, sys.argv[1:4])
orthography = Orthography.from_file()
story_path = ROOT / "tools/data/story-words.tsv"
# The release's handwritten variants are stable import inputs. Rebuilding in a
# clean tree must not learn new aliases from the last generated output.
with (ROOT/'tools/data/legacy-story-input-forms.tsv').open(encoding="utf-8") as archive:
    previous = {(r["word"],r["meaning"]):r for r in csv.DictReader(archive,delimiter="\t")}
source_id = "tlp-longshan-stories-02-01"
source = next(s for s in json.loads((dictionary / "site/longshan-texts.json").read_text()) if s["id"] == source_id)
assert source["title"] == "陈二郎戏弄哥哥" and len(source["blocks"]) == 6
refs = {v["ipa"]: v for v in json.loads((web / "reference-data.json").read_text())}
base = json.loads((web / "words.js").read_text().split("=", 1)[1].split(";\nconst soundVariants=")[0])
# Keep attested personal names, recognizable borrowed words, and explicitly agreed
# note characters. The rest use the project's Southwest-Mandarin reference table.
# Overrides apply to these attested senses of a word, never to an unrelated homophone.
names = {
    ("cerjiarkar", "陈姓"): "陈家卡", ("cereflar", "陈二郎"): "陈二郎", ("eflar", "二郎"): "二郎",
    ("goxgox", "哥哥"): "哥哥", ("goxgov", "哥哥"): "哥哥", ("goxgor", "哥哥"): "哥哥",
    ("jiuf", "就"): "就", ("jiur", "就"): "就", ("suxliaor", "输了"): "输了",
    ("ngefngouf", "硬忤"): "硬忤", ("beifbeixdix", "白白地"): "白白地",
    ("dif", "第"): "第", ("zuif", "最"): "最", ("lox", "人"): "倮",
    ("yianxtanrlair", "蔫蔫地"): "蔫坦来",
}
per_syllable = {"gi": "给", "ki": "尅", "hi": "黑", "hxi": "诶"}
# The story source uses ɨ next to a sibilant; the reference table transcribes
# these two c syllables using its apical vowel. Keep the source IPA separately.
ref_variants = {"tsʰɨ⁵⁵": "tsʰɿ⁵⁵", "tsʰɨ²¹": "tsʰɿ²¹"}
occurrences = []
source_glosses = {}
keyed = {}
# Keep existing entry IDs even after repairing an erroneous gloss boundary.
source_keys = dict.fromkeys((''.join(g['ye'].lower().split()),g['zh']) for b in source['blocks'] for g in b['glosses'])
old_ids={key:'ls60-'+str(i) for i,key in enumerate(source_keys,1)}
for block_no, block in enumerate(source["blocks"], 1):
    letters = re.findall(r"([a-z]+)([^a-z]*)", block["ye"].lower())
    sounds = re.findall(r"[^\s,.;:!?，。；、：“”！？]+", block["ipa"])
    assert len(letters) == len(sounds)
    cursor = 0
    for gloss in reviewed_glosses(block):
        parts = gloss["ye"].lower().split()
        stops = letters[cursor:cursor+len(parts)]
        assert parts == [v[0] for v in stops] and all(not v[1].strip() for v in stops[:-1])
        ipa = sounds[cursor:cursor+len(parts)]
        cursor += len(parts)
        ye = "".join(parts)
        meaning = gloss["zh"]
        lookup = (ye, meaning)
        legacy_units = []
        for syl, sound in zip(parts, ipa):
            old_ref = refs[ref_variants.get(sound, sound)]["first"]["h"]
            legacy_units.append(per_syllable.get(syl[:-1], old_ref))

        # Start from the lexical exception when it exists, including its exact word boundaries.
        exact = next((w for w in base if w["word"] == ye and
                      meaning in re.split("[、，；]", w["meaning"])), None)
        if exact:
            units = list(exact["hanUnits"])
        elif lookup in names:
            units = orthography.units(parts, legacy_units, override=list(names[lookup]))
        else:
            units = orthography.units(parts, legacy_units)
            if parts[0] in {"lox", "lov"} and meaning.startswith("人"):
                units[0] = "倮"
            if "吃" in meaning and "gaf" in parts:
                eat = next(w for w in base if w["id"] == "w684")
                for i, syllable in enumerate(parts):
                    if syllable == "gaf": units[i] = eat["hanUnits"][0]
            if meaning.startswith("小孩") and ye == "bovlir":
                units = ["波", "俐"]
        assert len(parts) == len(units) and all(1 <= len(unit) <= 2 for unit in units), (lookup, units, parts)
        han = "".join(units)
        prior = previous.get(lookup, {})
        word_id = keyed[lookup]['id'] if lookup in keyed else exact['id'] if exact else old_ids.get(lookup, 'tlp-'+ye)
        aliases = []
        if not exact:
            old = names.get(lookup, "".join(legacy_units))
            released = REVISIONS['previousWordForms'].get(word_id)
            for alias in [old, *prior.get("aliases","").split(), prior.get("recommended", ""), released]:
                if alias and alias != han and alias not in aliases:
                    aliases.append(alias)
        groups = []
        prior_sets = prior.get("variants", "").split()
        for i, unit in enumerate(units):
            old_choices = prior_sets[i] if len(prior_sets) == len(units) else ""
            # Digraphs are matched as units and full aliases. Variant sets only contain single glyphs.
            glyphs = [unit] if len(unit) == 1 else []
            if len(legacy_units[i]) == 1: glyphs.append(legacy_units[i])
            group = "".join(dict.fromkeys([*glyphs, *old_choices]))
            assert group, (lookup, i)
            groups.append(group)
        if lookup not in keyed:
            keyed[lookup] = dict(id=word_id,
                                 word=ye, sourceYe=" ".join(parts), meaning=exact["meaning"] if exact else meaning, storyMeaning=meaning,
                                 recommended=han, hanUnits=units, syllables=ipa, aliases=aliases,
                                 variants=groups, inBase=bool(exact))
            non_table = [(syl, unit) for syl, unit in zip(parts, units) if orthography.base(syl) is None]
            if non_table:
                keyed[lookup]["sourceFallback"] = [{"spelling": syl, "unit": unit} for syl, unit in non_table]
        assert keyed[lookup]["hanUnits"] == units
        after = stops[-1][1].strip().translate(str.maketrans(".,;:?!", "。，；：？！"))
        if cursor == len(letters) and block_no != 6:
            after += "\n"
        if "sourceGloss" in gloss:
            source_glosses[len(occurrences)] = gloss["sourceGloss"]
        occurrences.append((block_no,lookup,after))
    assert cursor == len(letters)

merged_aliases=align(keyed,base,dictionary/'site',ROOT,orthography,refs)
with (ROOT/'tools/data/story-spelling-aliases.tsv').open('w',newline='') as f:
    table=csv.writer(f,delimiter='\t',lineterminator='\n');table.writerow(['id','romanAliases'])
    for i,alts in sorted(merged_aliases.items()):
        if alts:table.writerow([i,' '.join(alts)])
counts = Counter(key for _,key,_ in occurrences)
extras = list({w['id']:w for w in keyed.values() if not w['inBase']}.values())
header = ["id","word","key","meaning","recommended","aliases","variants","frequency","kind","shortGloss","plain","diacritic","hanUnits"]
with story_path.open("w", encoding="utf-8", newline="") as out:
    table = csv.writer(out, delimiter='\t', lineterminator='\n')
    table.writerow(header)
    for w in extras:
        table.writerow([w["id"],w["word"],w["key"],w["storyMeaning"],w["recommended"]," ".join(w["aliases"]),
                        " ".join(w["variants"]),counts[w["sourceWord"],w["storyMeaning"]],
                        "tujia",w["storyMeaning"],w["plain"],w["diacritic"],"|".join(w["hanUnits"])])
record = ROOT/"tools/story-demo/chen-erlang.tsv"
with record.open("w", encoding="utf-8", newline="") as out:
    table=csv.writer(out,delimiter="\t",lineterminator="\n")
    table.writerow(["keys","text","meaning","after","hanUnits"])
    for n, key, after in occurrences:
        w=keyed[key]
        table.writerow([w["plain"],w["recommended"],w["meaning"],after.replace("\n","\\n"),"|".join(w["hanUnits"])])
# The old source is retained for the archive recording. The active demo uses Xu's first song.
song, song_note = make_song(dictionary, base + extras, orthography, ROOT, refs)
with story_path.open("a", encoding="utf-8", newline="") as out:
    table=csv.writer(out, delimiter='\t', lineterminator='\n')
    for w in song:
        table.writerow([w["id"],w["word"],w["key"],w["meaning"],w["recommended"]," ".join(w["aliases"]), " ".join(w["variants"]),
                        0, "tujia",w["shortGloss"],w["plain"],w["diacritic"],"|".join(w["hanUnits"])])
sections = [dict(translation=b['zh'], rows=[]) for b in source['blocks']]
for i, (num, key, after) in enumerate(occurrences):
    row = dict(id=keyed[key]['id'], end=after)
    if i in source_glosses:
        row['sourceGloss'] = source_glosses[i]
    sections[num - 1]['rows'].append(row)
archived = dict(title=source['title'],url='https://tujia.biz/?id='+source_id,source=source['externalUrl'],sections=sections)
dump=lambda v:json.dumps(v,ensure_ascii=False,separators=(',',':'))
(web/"story-words.js").write_text("const storyEntries="+dump(extras+song)+";\nconst noteStory="+dump(song_note)+";\nconst archivedStory="+dump(archived)+";\n")
print(source["title"],len(sections),"paragraphs,",len(occurrences),"selections,",
      sum(len(keyed[k]["hanUnits"]) for _,k,_ in occurrences),"syllables,",
      sum(len(keyed[k]["recommended"]) for _,k,_ in occurrences),"Han glyphs,",len(extras),"additional entries")
fallbacks = sorted({(s,u) for w in keyed.values() for s,u in zip(w["siteSyllables"],w["hanUnits"]) if orthography.base(s) is None})
if fallbacks:
    print("Source spellings outside the default table:", ", ".join(f"{s} → {u}" for s,u in fallbacks))
# The reference page also lists original story spellings outside the supplied table.
reference_path = web / 'orthography-data.js'
if reference_path.is_file():
    reference = json.loads(reference_path.read_text().split('=', 1)[1].strip().rstrip(';'))
    retained = {(r['syllable'], r['form']) for r in reference.get('fallback', []) if r.get('source') != '龙山故事'}
    reference['fallback'] = [dict(syllable=k, form=v) for k,v in sorted(retained)] + [
        dict(syllable=k, form=v, source='龙山故事') for k,v in fallbacks if (k,v) not in retained]
    reference_path.write_text('window.tujiaOrthography='+dump(reference)+';\n')
