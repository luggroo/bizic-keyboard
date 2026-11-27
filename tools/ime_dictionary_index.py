"""Index the exact packaged dictionary bytes for on-demand offline IME lookup."""
import csv
import gzip
import json
import re
from collections import defaultdict
from zlib import crc32

# Both sides use the UTF-8 CRC32 low six bits. No word or editor-text lookup.
BUCKETS = 64
# IDs are opaque, including IDs from new sources. They never become paths.
ID = re.compile(r"[^\s\x00-\x1f\x7f]{1,160}")
BATCH = re.compile(r"prepared/words-([0-9]+)\.json\.gz")


def entry_ranges(compressed):
    """Yield the UTF-8 byte range of each original JSON object, independent of spacing."""
    text = gzip.decompress(compressed).decode("utf-8")
    decoder = json.JSONDecoder()
    raw, byte_at = 0, 0
    delimiter = re.compile(r"\s*\[\s*")
    end = re.compile(r"\s*]\s*\Z")
    while True:
        match = delimiter.match(text, raw)
        if not match:
            raise ValueError("Malformed dictionary entry batch")
        at = match.end()
        byte_at += len(text[raw:at].encode("utf-8"))
        if end.match(text, at):
            return
        entry, raw = decoder.raw_decode(text, at)
        size = len(text[at:raw].encode("utf-8"))
        yield entry, byte_at, size
        byte_at += size
        if end.match(text, raw):
            return
        delimiter = re.compile(r"\s*,\s*")


def keyboard_rows(words, northern):
    with words.open(encoding="utf-8", newline="") as core, northern.open(encoding="utf-8", newline="") as north:
        reader = csv.DictReader(core, delimiter="\t")
        required = {"id", "kind", "word", "meaning", "plain", "diacritic", "recommended"}
        if not reader.fieldnames or not required.issubset(reader.fieldnames):
            raise ValueError("Invalid keyboard word-table header")
        for row in reader:
            yield row, False
        for row in csv.DictReader(north, delimiter="\t", fieldnames=reader.fieldnames):
            yield row, True


def reading_profiles(files):
    # The reader ships its profile catalogue as a JSON literal followed by JS.
    # Read only the data literal. An alternative frontend can omit this catalogue.
    profiles = {"brassett-to-ye": "Brassett 拼音转叶氏拼音", "brassett-phonetic": "Brassett 拼音对应 IPA"}
    source = files.get("notation-profiles.js", b"").decode("utf-8")
    assignment = re.search(r"\b(?:const|let|var)\s+NOTATION_CONFIG\s*=\s*(?=\{)", source)
    if assignment:
        try:
            config, _ = json.JSONDecoder().raw_decode(source, assignment.end())
        except ValueError:
            return profiles
        for row in config.get("profiles", []):
            if row.get("id") and row.get("title"):
                profiles[row["id"]] = row["title"]
    return profiles


def index_assets(files, words, northern):
    catalog = json.loads(gzip.decompress(files["prepared/catalogue.json.gz"]))
    search = json.loads(gzip.decompress(files["prepared/word-search.json.gz"]))
    canonical = {row["id"] for row in search["words"]}
    aliases = search["wordAliases"]
    locations = {}
    witnesses = {}
    for name, payload in files.items():
        batch = BATCH.fullmatch(name)
        if batch:
            for entry, offset, size in entry_ranges(payload):
                word_id = entry["id"]
                if word_id in locations or not ID.fullmatch(word_id):
                    raise ValueError("Invalid or repeated dictionary entry ID")
                locations[word_id] = (int(batch[1]), offset, size)
                for number, witness in enumerate(entry.get("attestations", []), 1):
                    identity = (word_id, witness["id"])
                    witnesses[identity] = None if identity in witnesses else (
                        number, witness.get("source", ""), witness.get("locator", ""))
    if locations.keys() != canonical or any(target not in canonical for target in aliases.values()):
        raise ValueError("Dictionary index and entry batches disagree")

    def resolve(word_id):
        if word_id in canonical:
            return word_id if aliases.get(word_id, word_id) == word_id else None
        return aliases.get(word_id)

    def native_group(key, signature):
        _, word, meaning, plain, _, han = signature
        return plain or word, meaning, han if key.startswith("ethnonym-alt-") else ""

    lexical = {}
    group_members = defaultdict(set)
    conflicts = set()
    imported_forms = set()
    for row, is_northern in keyboard_rows(words, northern):
        if row["kind"] not in {"tujia", "phonetic"}:
            continue
        key = row["id"]
        if not ID.fullmatch(key):
            continue
        signature = tuple(row[field] for field in ("kind", "word", "meaning", "plain", "diacritic", "recommended"))
        group_members[native_group(key, signature)].add(key)
        if key in lexical and lexical[key] != signature:
            conflicts.add(key)
        lexical[key] = signature
        if is_northern:
            imported_forms.add(key)

    # Only these variant IDs have a documented parent. @N is a keyboard form
    # number, not an attestation. Keep only the link to the whole article.
    def target_for(key):
        direct = resolve(key)
        if direct:
            return direct
        numbered = re.fullmatch(r"(.+)@[1-9][0-9]*", key)
        if numbered and key in imported_forms and numbered[1] not in conflicts:
            return resolve(numbered[1])
        # See tools/keyboard_gloss.py::ethnonym_variants. Verify the named parent
        # and the vocabulary of this generator before linking an alternate Han form.
        ethnic = re.fullmatch(r"ethnonym-(?:primary|alt)-(.+)-(bizika|bijika|mijika)", key)
        if ethnic and ethnic[1] in lexical and ethnic[1] not in conflicts:
            parent = lexical[ethnic[1]]
            if parent[2] == lexical[key][2] == "土家族" and lexical[key][3] == ethnic[2]:
                return resolve(ethnic[1])
        return None

    targets = {key: target_for(key) for key in lexical if key not in conflicts}
    # Native Han grouping can collapse equal toneless readings and meanings.
    # A surviving representative ID need not describe all entries in that group.
    groups = {group: {targets.get(key) or key for key in keys} for group, keys in group_members.items()}
    buckets = [[] for _ in range(BUCKETS)]
    for key, canonical_id in sorted(targets.items()):
        if not canonical_id:
            continue
        group = native_group(key, lexical[key])
        if not conflicts.isdisjoint(group_members[group]):
            continue
        bucket = crc32(key.encode("utf-8")) % BUCKETS
        batch, offset, length = locations[canonical_id]
        number, source, locator = witnesses.get((canonical_id, key)) or (0, "", "")
        locator = " ".join(locator.split())
        # A representative can only appear if the other identities in this
        # exact group, not merely an unrelated candidate, survived selection.
        members = group_members[group]
        native_ids = " ".join(sorted(members))
        required = native_ids if len(groups[group]) > 1 else ""
        # Even a single article can contain distinct documentary witnesses to the
        # same form and gloss. Only the raw IDs from this group can distinguish them.
        witness_required = ""
        if number and len(members) > 1:
            same_article = {member for member in members if targets.get(member) == canonical_id}
            if len(same_article) > 1:
                if all(witnesses.get((canonical_id, member)) for member in same_article):
                    witness_required = " ".join(sorted(same_article))
                else:
                    # A keyboard form alias may identify the parent without identifying a witness.
                    # Even its presence cannot certify the documentary basis of the merged reading.
                    number, source, locator = 0, "", ""
        buckets[bucket].append(f"{key}\t{canonical_id}\t{batch}\t{offset}\t{length}\t{number}\t{source}\t{locator}\t{required}\t{witness_required}\n")

    assets = {f"ime-dictionary/ids-{number}.tsv": "".join(lines).encode("utf-8")
              for number, lines in enumerate(buckets)}
    titles = {}
    for source in [*catalog["sources"], *catalog.get("sourceVariants", [])]:
        titles[source["id"]] = " ".join(source["title"].split())
    catalogue = {"sources": titles, "profiles": reading_profiles(files)}
    assets["ime-dictionary/sources.json"] = json.dumps(catalogue, ensure_ascii=False, sort_keys=True,
                                                       separators=(",", ":")).encode("utf-8")
    return assets
