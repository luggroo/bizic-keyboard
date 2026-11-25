#!/usr/bin/env python3
"""Verify the four native libraries and compare every packaged dictionary asset to source."""
import argparse
import hashlib
from pathlib import Path
from zipfile import ZipFile


def verify(apk, typing_only):
    assets = Path(__file__).resolve().parents[1] / "shared/bundle/Assets"
    expected = {path.relative_to(assets).as_posix(): path for path in assets.rglob("*") if path.is_file()}
    android_assets = Path(__file__).resolve().parents[1] / "app/src/main/assets"
    expected.update({p.relative_to(android_assets).as_posix(): p for p in android_assets.rglob("*") if p.is_file()})
    if typing_only:
        expected = {name: path for name, path in expected.items()
                    if not name.startswith(("handwriting/", "ismantic/", "handwriting-"))}
    libraries = ["libtujia_core.so"]
    if not typing_only:
        libraries.extend(["libonnxruntime.so", "libonnxruntime4j_jni.so"])
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
        if native != {f"lib/{abi}/{name}" for abi in packages for name in libraries}:
            raise ValueError(f"Unexpected set of native libraries: {sorted(native)}")
        for lib in native:
            if archive.read(lib)[:4] != b"\x7fELF":
                raise ValueError(f"Invalid native library: {lib}")
    print(f"{apk.name}: {len(expected)} matching assets, {len(native)} native libraries across {len(packages)} ABIs")
    print("SHA-256", hashlib.sha256(apk.read_bytes()).hexdigest())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--typing-only", action="store_true")
    args = parser.parse_args()
    verify(args.apk, args.typing_only)
