#!/usr/bin/env python3
"""Measure an APK's ZIP payload, Android signing block/alignment, and gzip data."""
import argparse
import gzip
import hashlib
import json
import re
from pathlib import Path
from zipfile import ZipFile

LEARNING = "assets/learning/"
CATEGORIES = {
    "dex": "DEX", "native": "Native libraries", "ime": "IME lexicon",
    "frontend": "Web frontend", "dictionary": "Dictionary data", "grammar": "Grammar data",
    "corpus": "Corpus data", "bibliography": "Bibliography", "notation": "Notation",
    "localization": "Localization", "fonts": "Fonts", "handwriting": "Handwriting assets",
    "other": "Other assets", "android": "Android resources and metadata",
}


def group(name, categories):
    if name.startswith(LEARNING):
        return categories.get(name[len(LEARNING):], "other")
    if re.fullmatch(r"classes(?:[0-9]+)?\.dex", name):
        return "dex"
    if name.startswith("lib/"):
        return "native"
    if name.startswith(("assets/handwriting/", "assets/ismantic/")):
        return "handwriting"
    if name.startswith("assets/"):
        return "ime" if name.endswith(".tsv") else "other"
    return "android"


def logical_size(archive, entry):
    if entry.filename.startswith(LEARNING) and entry.filename.endswith(".json.gz"):
        with archive.open(entry) as packed, gzip.GzipFile(fileobj=packed) as stream:
            return sum(len(b) for b in iter(lambda: stream.read(1024 * 1024), b""))
    return entry.file_size


def measure(path):
    columns = ("files", "storedBytes", "uncompressedBytes", "logicalBytes")
    rows = {k: dict.fromkeys(columns, 0) for k in CATEGORIES}
    by_abi = {}
    with ZipFile(path) as archive:
        manifest = LEARNING + "offline-manifest.json"
        categories = {}
        if manifest in archive.namelist():
            inventory = json.loads(archive.read(manifest))["files"]
            categories = {entry["path"]: entry["category"] for entry in inventory}
        for entry in archive.infolist():
            values = (1, entry.compress_size, entry.file_size, logical_size(archive, entry))
            category = group(entry.filename, categories)
            for col, value in zip(columns, values):
                rows[category][col] += value
            if category == "native":
                abi = entry.filename.split("/")[1]
                sizes = by_abi.setdefault(abi, dict.fromkeys(columns, 0))
                for col, value in zip(columns, values):
                    sizes[col] += value
    totals = {col: sum(row[col] for row in rows.values()) for col in columns}
    file_size = path.stat().st_size
    return {"apk": path.name, "apkBytes": file_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "categories": rows, "payload": totals, "nativeByAbi": by_abi,
            "zipSigningAndAlignmentBytes": file_size - totals["storedBytes"]}


def report(result):
    print(f"{result['apk']} ({result['apkBytes']:,} bytes)")
    print("\n| Contents | Files | Stored in APK (B) | ZIP uncompressed (B) | After gzip (B) |")
    print("|---|---:|---:|---:|---:|")
    for code, row in result["categories"].items():
        print(f"| {CATEGORIES[code]} | {row['files']} | {row['storedBytes']:,} | "
              f"{row['uncompressedBytes']:,} | {row['logicalBytes']:,} |")
    print(f"| ZIP headers, signing and alignment | — | {result['zipSigningAndAlignmentBytes']:,} | — | — |")
    print("\nStored bytes include each compressed or stored ZIP entry. "
          "The last column also expands bundled JSON gzip streams.")
    print("\nSHA-256", result["sha256"])


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("apk", type=Path)
    cli.add_argument("--json", action="store_true")
    args = cli.parse_args()
    result = measure(args.apk)
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        report(result)
