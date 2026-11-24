#!/usr/bin/env python3
"""Verify the four native libraries and compare every packaged dictionary asset to source."""
import argparse
import hashlib
from pathlib import Path
from zipfile import ZipFile


def verify(apk):
    assets = Path(__file__).resolve().parents[1] / "shared/bundle/Assets"
    expected = {path.relative_to(assets).as_posix(): path for path in assets.rglob("*") if path.is_file()}
    packages = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
    with ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive member")
        found = {name.removeprefix("assets/") for name in names if name.startswith("assets/") and not name.endswith("/")}
        if found != set(expected):
            raise ValueError(f"Unexpected asset inventory: {sorted(found ^ set(expected))}")
        for name, source in expected.items():
            if archive.read("assets/" + name) != source.read_bytes():
                raise ValueError(f"Packaged asset differs from source: {name}")
        native = {name for name in names if name.startswith("lib/") and name.endswith(".so")}
        if native != {f"lib/{abi}/libtujia_core.so" for abi in packages}:
            raise ValueError(f"Unexpected set of native libraries: {sorted(native)}")
        for lib in native:
            if archive.read(lib)[:4] != b"\x7fELF":
                raise ValueError(f"Invalid native library: {lib}")
    print(f"{apk.name}: {len(expected)} matching assets, {len(packages)} native libraries")
    print("SHA-256", hashlib.sha256(apk.read_bytes()).hexdigest())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    verify(parser.parse_args().apk)
