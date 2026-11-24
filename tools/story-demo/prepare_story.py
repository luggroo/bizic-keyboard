"""Build the six-paragraph note from the Longshan text, aligned gloss by gloss.

Usage: python3 tools/story-demo/prepare_story.py DICTIONARY_REPO TRANSLATOR_REPO WEB_DIST
"""
import csv
import json
import html
import re
import subprocess
import sys
import unicodedata
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from han_orthography import REVISIONS, Orthography

dictionary, translator, web = map(Path, sys.argv[1:4])
orthography = Orthography.from_file()
story_path = ROOT / "tools/data/story-words.tsv"
previous = {}
if story_path.exists():
    with story_path.open(encoding="utf-8") as archive:
        previous = {(row["word"], row["meaning"]): row for row in csv.DictReader(archive, delimiter="\t")}
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
    ("goxgox", "哥哥"): "哥哥", ("goxgox", "哥哥呢"): "哥哥", ("goxgov", "哥哥"): "哥哥",
    ("goxgov", "哥哥啊"): "哥哥", ("goxgor", "哥哥"): "哥哥",
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
keyed = {}
for block_no, block in enumerate(source["blocks"], 1):
    letters = re.findall(r"([a-z]+)([^a-z]*)", block["ye"].lower())
    sounds = re.findall(r"[^\s,.;:!?，。；、：“”！？]+", block["ipa"])
    assert len(letters) == len(sounds)
    cursor = 0
    for gloss in block["glosses"]:
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
        word_id = keyed[lookup]['id'] if lookup in keyed else exact['id'] if exact else 'ls60-'+str(len(keyed)+1)
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
                                 word=ye, meaning=exact["meaning"] if exact else meaning, storyMeaning=meaning,
                                 recommended=han, hanUnits=units, syllables=ipa, aliases=aliases,
                                 variants=groups, inBase=bool(exact))
            non_table = [(syl, unit) for syl, unit in zip(parts, units) if orthography.base(syl) is None]
            if non_table:
                keyed[lookup]["sourceFallback"] = [{"spelling": syl, "unit": unit} for syl, unit in non_table]
        assert keyed[lookup]["hanUnits"] == units
        after = stops[-1][1].strip().translate(str.maketrans(".,;:?!", "。，；：？！"))
        if cursor == len(letters) and block_no != 6:
            after += "\n"
        occurrences.append((block_no,lookup,after))
    assert cursor == len(letters)

converted = json.loads(subprocess.check_output(
    ["node", str(translator/"scripts/tujia-lex-spelling.cjs"), str(dictionary)],
    input=json.dumps([dict(mode="ye", text=v["word"]) for v in keyed.values()]).encode()))
counts = Counter(key for _,key,_ in occurrences)
for (k, w), mark in zip(keyed.items(), converted):
    w["diacritic"] = mark["accent"]
    naked = ''.join(c for c in unicodedata.normalize('NFD',mark["accent"]) if c not in "\u0300\u0301\u0304\u030c")
    w["plain"] = unicodedata.normalize('NFC',naked).replace("'","")
    w["key"] = w["plain"]
    assert re.fullmatch("[a-z]+",w["plain"]), w
extras = [w for w in keyed.values() if not w["inBase"]]
header = ["id","word","key","meaning","recommended","aliases","variants","frequency","kind","shortGloss","plain","diacritic","hanUnits"]
with story_path.open("w", encoding="utf-8", newline="") as out:
    table = csv.writer(out, delimiter='\t', lineterminator='\n')
    table.writerow(header)
    for w in extras:
        table.writerow([w["id"],w["word"],w["key"],w["storyMeaning"],w["recommended"]," ".join(w["aliases"]),
                        " ".join(w["variants"]),counts[w["word"],w["storyMeaning"]],
                        "tujia",w["storyMeaning"],w["plain"],w["diacritic"],"|".join(w["hanUnits"])])
record = ROOT/"tools/story-demo/chen-erlang.tsv"
with record.open("w", encoding="utf-8", newline="") as out:
    table=csv.writer(out,delimiter="\t",lineterminator="\n")
    table.writerow(["keys","text","meaning","after","hanUnits"])
    for n, key, after in occurrences:
        w=keyed[key]
        table.writerow([w["plain"],w["recommended"],w["meaning"],after.replace("\n","\\n"),"|".join(w["hanUnits"])])
sections=[]
paragraphs=[]
styled_paragraphs=[]
for n,b in enumerate(source["blocks"],1):
    rows=[{"id":keyed[key]["id"], "end":after} for num,key,after in occurrences if num==n]
    sections.append({"translation":b["zh"],"rows":rows})
    paragraphs.append("".join(keyed[key]["recommended"]+end for num,key,end in occurrences if num==n).rstrip("\n"))
    pieces = []
    for num, key, after in occurrences:
        if num != n: continue
        for unit in keyed[key]["hanUnits"]:
            rendered = html.escape(unit)
            if len(unit) == 2:
                rendered = '<span class="joined-han"><span>'+rendered+'</span></span>'
            pieces.append(rendered)
        pieces.append(html.escape(after.rstrip("\n")))
    styled_paragraphs.append("<p>"+"".join(pieces)+"</p>")
dump=lambda v:json.dumps(v,ensure_ascii=False,separators=(',',':'))
(web/"story-words.js").write_text("const storyEntries="+dump(extras)+";\nconst noteStory="+dump({"title":source["title"],"url":"https://tujia.biz/?id="+source["id"],"source":source["externalUrl"],"sections":sections})+";\n")
landing = web / "android.html"
if landing.is_file():
    markup = landing.read_text(encoding="utf-8")
    # Keep the existing summary, source attribution, media players and all other page content.
    pattern = r'(<section class="manuscript"><details><summary>[^<]*</summary>)(.*?)(?=<p><a\s)'
    matches = list(re.finditer(pattern, markup, flags=re.DOTALL))
    if len(matches) != 1 or len(re.findall(r"<p>.*?</p>", matches[0].group(2), flags=re.DOTALL)) != 6:
        raise ValueError("Could not identify six manuscript paragraphs in android.html")
    a, b = matches[0].span(2)
    markup = markup[:a] + "".join(styled_paragraphs) + markup[b:]
    style = """<style id="manuscript-han-units">
.manuscript .joined-han { display: inline-block; width: 1.68em; line-height: 1.08; white-space: nowrap; border-bottom: .07em solid currentColor; }
.manuscript .joined-han > span { display: inline-block; transform-origin: left center; transform: scaleX(.84); }
</style>"""
    existing = re.search(r'<style id="manuscript-han-units">.*?</style>', markup, flags=re.DOTALL)
    if existing:
        markup = markup[:existing.start()] + style + markup[existing.end():]
    else:
        markup = markup.replace("</head>",style+"\n</head>",1)
    landing.write_text(markup, encoding="utf-8")
print(source["title"],len(sections),"paragraphs,",len(occurrences),"selections,",
      sum(len(keyed[k]["hanUnits"]) for _,k,_ in occurrences),"syllables,",
      sum(len(keyed[k]["recommended"]) for _,k,_ in occurrences),"Han glyphs,",len(extras),"additional entries")
fallbacks = sorted({(f["spelling"],f["unit"]) for w in keyed.values() for f in w.get("sourceFallback",[])})
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
