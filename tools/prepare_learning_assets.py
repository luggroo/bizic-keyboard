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

CONTENT = Path(__file__).resolve().parents[1] / "learning-content"
ARCHIVE = CONTENT / "learning.zip"
PIN = CONTENT / "learning.sha256"
MANIFEST = "offline-manifest.json"
CATEGORIES = {"frontend", "dictionary", "grammar", "corpus", "bibliography",
              "notation", "localization", "fonts", "other"}
MAX_BYTES = 128 * 1024 * 1024
MAX_FILES = 4096


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


def prepare(output, archive=ARCHIVE, pin=PIN):
    files = read_snapshot(archive, pin)
    output.mkdir(parents=True, exist_ok=True)
    # Gradle owns this output. Fully verify and stage the new tree before replacing it.
    with tempfile.TemporaryDirectory(dir=output) as temp:
        staged = Path(temp) / "learning"
        for name, data in files.items():
            # AGP's asset merger inflates one .gz layer and strips the suffix.
            # A temporary store-only wrapper preserves the website's path and bytes in the APK.
            if name.endswith(".gz"):
                name += ".gz"
                data = gzip.compress(data, compresslevel=0, mtime=0)
            target = staged / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        dest = output / "learning"
        if dest.exists():
            shutil.rmtree(dest)
        staged.rename(dest)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Generated Android asset root")
    parser.add_argument("--snapshot", type=Path, default=ARCHIVE)
    parser.add_argument("--pin", type=Path, default=PIN)
    args = parser.parse_args()
    prepare(args.output, args.snapshot, args.pin)
