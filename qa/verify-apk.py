#!/usr/bin/env python3
"""Verify native library coverage and compare each packaged dictionary asset to source."""
import argparse
import hashlib
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from prepare_learning_assets import read_snapshot


def verify_offline_manifest(apk, aapt):
    manifest = subprocess.run([str(aapt), "dump", "permissions", str(apk)],
                              check=True, capture_output=True, text=True).stdout
    if not manifest.startswith("package: "):
        raise ValueError("Unable to read the packaged Android permissions")
    for line in manifest.splitlines():
        if line.startswith("uses-permission") and "name='android.permission.INTERNET'" in line:
            raise ValueError(f"{apk.name} requests INTERNET in its merged manifest")
    tree = subprocess.run([str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"],
                          check=True, capture_output=True, text=True).stdout
    if ".LearningResourceProvider" in tree or "biz.tujia.ime.learning.resources" in tree:
        raise ValueError(f"{apk.name} still registers the removed publication provider")
    print(f"{apk.name}: offline manifest check passed")


def verify(apk, typing_only):
    assets = ROOT / "shared/bundle/Assets"
    expected = {path.relative_to(assets).as_posix(): path for path in assets.rglob("*") if path.is_file()}
    android_assets = ROOT / "app/src/main/assets"
    expected.update({p.relative_to(android_assets).as_posix(): p for p in android_assets.rglob("*") if p.is_file()})
    if typing_only:
        expected = {name: path for name, path in expected.items()
                    if not name.startswith(("handwriting/", "ismantic/"))
                    and name not in ("handwriting-readings.tsv", "handwriting-tones.tsv")}
    learning = {"learning/" + name: data for name, data in read_snapshot().items()}
    libraries = ["libtujia_core.so"]
    if not typing_only:
        libraries.extend(["libonnxruntime.so", "libonnxruntime4j_jni.so"])
    packages = {"arm64-v8a", "armeabi-v7a", "x86_64"}
    with ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive member")
        found = {name.removeprefix("assets/") for name in names if name.startswith("assets/") and not name.endswith("/")}
        required = set(expected) | set(learning)
        if found != required:
            raise ValueError(f"Unexpected asset inventory: {sorted(found ^ required)}")
        for name, source in expected.items():
            if archive.read("assets/" + name) != source.read_bytes():
                raise ValueError(f"Packaged asset differs from source: {name}")
        for name, data in learning.items():
            if archive.read("assets/" + name) != data:
                raise ValueError(f"Packaged asset differs from pinned learning snapshot: {name}")
        native = {name for name in names if name.startswith("lib/") and name.endswith(".so")}
        if native != {f"lib/{abi}/{name}" for abi in packages for name in libraries}:
            raise ValueError(f"Unexpected set of native libraries: {sorted(native)}")
        for lib in native:
            if archive.read(lib)[:4] != b"\x7fELF":
                raise ValueError(f"Invalid native library: {lib}")
    print(f"{apk.name}: {len(required)} matching assets, {len(native)} native libraries across {len(packages)} ABIs")
    print("SHA-256", hashlib.sha256(apk.read_bytes()).hexdigest())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--typing-only", action="store_true")
    parser.add_argument("--aapt", type=Path, help="Verify the offline manifest with Android Build Tools aapt")
    args = parser.parse_args()
    if args.aapt:
        verify_offline_manifest(args.apk, args.aapt)
    verify(args.apk, args.typing_only)
