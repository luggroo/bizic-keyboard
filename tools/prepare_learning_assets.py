#!/usr/bin/env python3
"""Verify the pinned learning snapshot and stage its files for Android asset merging."""
import argparse
import gzip
import hashlib
import json
import re
import shutil
import stat
import tempfile
from pathlib import Path
from zipfile import ZipFile
from ime_dictionary_index import index_assets
from prepare_keyboard_assets import WORD_FILES, project

CONTENT = Path(__file__).resolve().parents[1] / "learning-content"
ARCHIVE = CONTENT / "learning.zip"
PIN = CONTENT / "learning.sha256"
BRANDING = CONTENT.parent / "artwork" / "learning-brand"
BRAND_ASSETS = ("yangque-logo.svg", "icon.svg")
MANIFEST = "offline-manifest.json"
CATALOGUE = "prepared/catalogue.json.gz"
PUBLICATION_TEXT = re.compile(r"prepared/source-[0-9]+\.json\.gz")
CATEGORIES = {"frontend", "dictionary", "grammar", "corpus", "bibliography",
              "notation", "localization", "fonts", "other"}
MAX_BYTES = 128 * 1024 * 1024
MAX_FILES = 4096

ANDROID_TITLES = {
    "keyboard": ("cixcix har-nierxix", "cīcī hǎ-niěxī"),
    "knowledge": ("sar afhxix-mor-hxex", "sǎ áhxī-mǒ-hxē"),
    "settings": ("sifsif", "sísí"),
    "searchDictionary": ("cixcix niv", "cīcī nì"),
    "searchAll": ("jierxif niv", "jiěxí nì"),
}


def safe_name(name):
    # Match the asset names served by LearningOrigin. /_app/ is native navigation.
    return (isinstance(name, str) and not name.startswith("_app/")
            and all(re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]*", part)
                    for part in name.split("/")))


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_snapshot(archive=ARCHIVE, pin=PIN):
    """Return verified bytes keyed by origin-relative path, including the manifest."""
    expected = pin.read_text(encoding="ascii").strip()
    if archive.stat().st_size > MAX_BYTES or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("Invalid learning snapshot or SHA-256 pin")
    with archive.open("rb") as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
        if digest.hexdigest() != expected:
            raise ValueError("Learning snapshot SHA-256 does not match the checked-in pin")
        stream.seek(0)
        with ZipFile(stream) as bundle:
            entries = bundle.infolist()
            if len(entries) > MAX_FILES or sum(x.file_size for x in entries) > MAX_BYTES:
                raise ValueError("Learning snapshot exceeds the schema's build limits")
            paths = set()
            for entry in entries:
                name = entry.orig_filename
                mode = stat.S_IFMT(entry.external_attr >> 16)
                if (not safe_name(name) or name != entry.filename
                        or mode not in (0, stat.S_IFREG) or name.casefold() in paths):
                    raise ValueError(f"Invalid or repeated learning snapshot path: {name!r}")
                paths.add(name.casefold())
            data = {e.filename: bundle.read(e) for e in entries}

    manifest = json.loads(data[MANIFEST])
    if (type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1
            or not re.fullmatch(r"[0-9a-f]{40}", manifest.get("sourceRevision", ""))
            or not re.fullmatch(r"[A-Za-z0-9._-]+", manifest.get("contentVersion", ""))):
        raise ValueError("Unsupported learning snapshot version")
    records = manifest["files"]
    names = {item["path"] for item in records}
    if (len(records) != len(names) or names != data.keys() - {MANIFEST}
            or not {"index.html", "app.js", "prepared/word-search.json.gz"} <= names):
        raise ValueError("Learning snapshot inventory is incomplete or inconsistent")
    for item in records:
        path = item["path"]
        if any(parent.as_posix().casefold() in paths for parent in Path(path).parents):
            raise ValueError(f"File-directory conflict in learning snapshot: {path!r}")
        if (type(item["size"]) is not int or len(data[path]) != item["size"]
                or sha256(data[path]) != item["sha256"] or item["category"] not in CATEGORIES):
            raise ValueError(f"Invalid learning snapshot record: {path!r}")
    return data


def add_android_titles(files):
    """Add Android navigation titles to the reader's existing spelling selector."""
    if "academy.js" not in files:
        return

    def title(key, secondary):
        return (f'<span class="academy-title-stack"><span class="tujia-ui" '
                f'data-ui-tujia="{key}" lang="tji"></span>{secondary}</span>')

    def label(zh, en, value):
        return f'<span data-ui-source="{zh}" data-ui-english="{en}">{value}</span>'

    def replace_once(text, marker, replacement):
        if text.count(marker) != 1:
            raise ValueError(f"Expected one Android reader title marker: {marker}")
        return text.replace(marker, replacement, 1)

    # The drawer, home tiles, and knowledge directory share localized caption markup.
    for name in ("index.html", "en/index.html"):
        text = files[name].decode()
        sections = (
            (r'<aside id="academy-drawer"[^>]*>.*?</aside>', {
                "首页": "home", "词典查询": "words", "例句语料": "corpus",
                "语言知识": "knowledge", "土家语输入法": "keyboard", "设置": "settings",
            }),
            (r'<div class="academy-destinations">.*?</div>', {
                "词典查询": "words", "例句语料": "corpus", "语言知识": "knowledge",
            }),
            (r'<template id="academy-knowledge-template">.*?</template>', {
                "语法": "grammar", "记音": "notation", "历史比较": "history",
            }),
            (r'<fieldset class="academy-search-scope">.*?</fieldset>', {
                "搜词典": "searchDictionary", "搜全站": "searchAll",
            }),
        )
        for pattern, keys in sections:
            section = re.search(pattern, text, re.S)
            if section is None:
                raise ValueError(f"Missing Android reader navigation in {name}: {pattern}")
            markup = section[0]
            for zh, key in keys.items():
                def stacked(match):
                    en, current = match.groups()
                    renamed = {"搜词典": "逻词典", "搜全站": "逻全站"}.get(zh, zh)
                    return title(key, label(renamed, en, renamed if current == zh else current))

                markup, count = re.subn(
                    rf'<span data-ui-source="{zh}" data-ui-english="([^"]+)">([^<]*)</span>',
                    stacked, markup,
                )
                if count != 1:
                    raise ValueError(f"Expected one Android reader caption for {zh} in {name}")
            text = text[:section.start()] + markup + text[section.end():]
        text = replace_once(text, '<span id="academy-title"></span>',
                            '<span id="academy-title">' + title("home", "<span></span>") + "</span>")
        files[name] = text.encode()

    language = files["ui-language.js"].decode()
    language = replace_once(language, "const labels=Object.freeze({", "const labels=Object.freeze({"
                            + "".join(f"{key}:'{ye}'," for key, (ye, _) in ANDROID_TITLES.items()))
    language = replace_once(language, "const homeAccents={", "const homeAccents={"
                            + "".join(f'"{key}":"{accent}",' for key, (_, accent) in ANDROID_TITLES.items()))
    files["ui-language.js"] = language.encode()

    academy = files["academy.js"].decode()
    academy = replace_once(
        academy,
        "caption(title,home?'':label[0],home?'':label[1]);",
        "const spelling=title.querySelector('[data-ui-tujia]');"
        "const key=titles[view]?view:'home';"
        "if(spelling.dataset.uiTujia!==key){"
        "spelling.dataset.uiTujia=key;spelling.textContent=window.TujiaUI.spelling(key);}"
        "caption(spelling.nextElementSibling,label[0],label[1]);",
    )
    files["academy.js"] = academy.encode()
    files["academy.css"] += b"""
.academy-title-stack { display:flex; flex-direction:column; align-items:inherit; gap:2px; }
.academy-title-stack > .tujia-ui { margin:0; line-height:1.4; }
html[data-edition] #academy-drawer > nav > * { font-family:inherit; font-weight:600; }
html[data-edition] #academy-drawer > nav > * > span { min-width:0; overflow-wrap:break-word; }
html[data-edition] #academy-drawer > nav .academy-title-stack { align-items:flex-start; }
html[data-edition] #academy-drawer > nav .academy-title-stack > [data-ui-source] { font-size:15px; }
.academy-search-scope label > span { justify-content:center; padding-block:4px; }
.academy-search-scope .academy-title-stack > .tujia-ui { color:inherit; }
.academy-knowledge-list strong .tujia-ui { font-size:14px; }
#academy-title .academy-title-stack { align-items:center; }
"""


def read_android_snapshot(archive=ARCHIVE, pin=PIN, branding=None):
    files = read_snapshot(archive, pin)
    catalogue = json.loads(gzip.decompress(files[CATALOGUE]))
    for section in ("sources", "sourceVariants"):
        for record in catalogue.get(section, []):
            chunk = record.pop("_textChunk", None)
            if chunk or record.get("text"):
                record["text"] = ""
                record.pop("textNotice", None)
    files[CATALOGUE] = gzip.compress(
        json.dumps(catalogue, ensure_ascii=False, separators=(",", ":")).encode(), mtime=0
    )
    for name in list(files):
        if re.fullmatch(r"translation(?:-en)?\.html|translation-(?:guide|gloss)\.(?:js|css)", name):
            del files[name]
        elif name.endswith((".html", ".js", ".css")):
            text = files[name].decode().replace("毕兹学堂", "毕兹辞谱").replace("Bizic Academy", "Bizic Cipu")
            if name.endswith(".html"):
                text = re.sub(r'<a\b[^>]*data-academy-translation[^>]*>.*?</a>', "", text, flags=re.S)
            if name == "app.js":
                text = re.sub(r'<section class="translation-callout"[^>]*>.*?</section>', "", text, flags=re.S)
            if name == "academy.js":
                text = re.sub(r"const guide=english\(\)\?'/translation-en\.html'.*?;\}\);", "", text)
            files[name] = text.encode()
    if any(re.search(rb"href=[\"'](?:/)?translation(?:-en)?\.html|(?:https?://)(?:www\.)?(?:doubao\.com|kimi\.com|claude\.ai|chatgpt\.com|dola\.com)",
                     data, re.IGNORECASE) for name, data in files.items() if name.endswith((".html", ".js"))):
        raise ValueError("External AI guide or link in Android reading assets")
    # The shared frontend also runs on the website, where publication search is available.
    for name in ("app.js", "ui-core-translations.js", "ui-translations.js"):
        if name not in files:
            continue
        text = files[name].decode().replace(
            '<div class="detail-actions"><button data-recognition-batch="true">扫描文献全文</button></div>', ""
        ).replace(
            "搜索题名、原目录与已提取正文…", "搜索题名与原目录…"
        ).replace(
            "Search titles, original folders or extracted text…", "Search titles and original folders…"
        ).replace(
            "词形、释义、文献正文与语料…", "词形、释义、书目与语料…"
        ).replace(
            "Word forms, meanings, source documents or texts…", "Word forms, meanings, bibliography or corpus…"
        )
        files[name] = text.encode()
    for name in list(files):
        if PUBLICATION_TEXT.fullmatch(name):
            del files[name]
    add_android_titles(files)
    # App branding follows the launcher, independent of the pinned dictionary content.
    # Only the two known, inventoried SVGs may be overridden, after snapshot verification.
    if branding is not None:
        for name in BRAND_ASSETS:
            if name not in files:
                raise ValueError(f"Missing learning branding asset: {name}")
            files[name] = (branding / name).read_bytes()
        for name in files:
            if name.endswith((".html", ".webmanifest")):
                files[name] = files[name].replace(b"?v=yangque1", b"?v=yangque-k01")
    manifest = json.loads(files[MANIFEST])
    manifest["files"] = [
        {**record, "size": len(files[record["path"]]), "sha256": sha256(files[record["path"]])}
        for record in manifest["files"] if record["path"] in files
    ]
    files[MANIFEST] = json.dumps(manifest, separators=(",", ":")).encode()
    return files


def android_ime_index(files, vocabulary):
    with tempfile.TemporaryDirectory() as temp:
        paths = [Path(temp) / name for name in WORD_FILES]
        for path in paths:
            path.write_bytes(vocabulary[path.name])
        return index_assets(files, *paths)


def prepare(output, archive=ARCHIVE, pin=PIN, vocabulary=None, branding=None):
    files = read_android_snapshot(archive, pin, branding)
    output.mkdir(parents=True, exist_ok=True)
    # Gradle owns this output. Fully verify and stage the new tree before replacing it.
    with tempfile.TemporaryDirectory(dir=output) as temp:
        staged = Path(temp) / "learning"
        if vocabulary:
            filtered, _, _ = project({name: path.read_text() for name, path in zip(WORD_FILES, vocabulary)})
            for path, data in android_ime_index(files, filtered).items():
                target = Path(temp) / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
        for name, data in files.items():
            # AGP's asset merger inflates one .gz layer and strips the suffix.
            # A temporary store-only wrapper preserves the website's path and bytes in the APK.
            if name.endswith(".gz"):
                name += ".gz"
                data = gzip.compress(data, compresslevel=0, mtime=0)
            target = staged / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        for directory in Path(temp).iterdir():
            dest = output / directory.name
            if dest.exists():
                shutil.rmtree(dest)
            directory.rename(dest)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Generated Android asset root")
    parser.add_argument("--snapshot", type=Path, default=ARCHIVE)
    parser.add_argument("--pin", type=Path, default=PIN)
    parser.add_argument("--branding", type=Path, default=BRANDING)
    assets = CONTENT.parent / "shared/bundle/Assets"
    parser.add_argument("--words", type=Path, default=assets / "words.tsv")
    parser.add_argument("--northern", type=Path, default=assets / "northern-words.tsv")
    args = parser.parse_args()
    prepare(args.output, args.snapshot, args.pin, (args.words, args.northern), args.branding)
