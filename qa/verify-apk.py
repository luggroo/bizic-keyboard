#!/usr/bin/env python3
"""Verify native library coverage and compare each packaged dictionary asset to source."""
import argparse
import hashlib
import re
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from prepare_learning_assets import BRANDING, PUBLICATION_TEXT, android_ime_index, read_android_snapshot
from prepare_keyboard_assets import asset_files


NETWORK_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
}


def verify_offline_manifest(apk, aapt):
    manifest = subprocess.run([str(aapt), "dump", "permissions", str(apk)],
                              check=True, capture_output=True, text=True).stdout
    if not manifest.startswith("package: "):
        raise ValueError("Unable to read the packaged Android permissions")
    for line in manifest.splitlines():
        if line.startswith("uses-permission"):
            permission = re.search(r"\bname='([^']+)'", line)
            if permission and permission[1] in NETWORK_PERMISSIONS:
                raise ValueError(f"{apk.name} requests {permission[1].split('.')[-1]} in its merged manifest")
    tree = subprocess.run([str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"],
                          check=True, capture_output=True, text=True).stdout
    if ".LearningResourceProvider" in tree or "biz.tujia.ime.learning.resources" in tree:
        raise ValueError(f"{apk.name} still registers the removed publication provider")
    print(f"{apk.name}: offline manifest check passed")
    return tree


def verify_component_icons(apk, aapt, manifest, keyboard_only=False):
    resources = subprocess.run([str(aapt), "dump", "resources", str(apk)],
                               check=True, capture_output=True, text=True).stdout
    resource_names = dict(re.findall(r"spec resource (0x[0-9a-f]+) biz\.tujia\.ime:([^:]+):", resources))
    expected = {"application": "drawable/ic_tujia" if keyboard_only else "mipmap/ic_tujia_launcher", "service": "drawable/ic_tujia"}
    seen = set()
    blocks = re.finditer(r"(?m)^\s*E: (application|service) [^\n]*\n((?:[ \t]+A: [^\n]*\n)+)", manifest)
    for match in blocks:
        kind, attributes = match.groups()
        if kind == "service" and '"biz.tujia.ime.TujiaIme"' not in attributes:
            continue
        icon = re.search(r"android:icon\(0x[0-9a-f]+\)=@(0x[0-9a-f]+)", attributes)
        if not icon or resource_names.get(icon[1]) != expected[kind]:
            raise ValueError(f"{apk.name}: {kind} should use @{expected[kind]}")
        seen.add(kind)
    if seen != expected.keys():
        raise ValueError(f"{apk.name}: could not locate the packaged app and IME service")
    print(f"{apk.name}: launcher and IME use their individual artwork")


def verify(apk, typing_only, keyboard_only=False):
    expected, _ = asset_files(typing_only)
    learning = {}
    if not keyboard_only:
        snapshot = read_android_snapshot(branding=BRANDING)
        learning = {"learning/" + name: data for name, data in snapshot.items()}
        learning.update(android_ime_index(snapshot, expected))
    libraries = ["libtujia_core.so"]
    if not typing_only:
        libraries.extend(["libonnxruntime.so", "libonnxruntime4j_jni.so"])
    packages = {"arm64-v8a", "armeabi-v7a", "x86_64"}
    with ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive member")
        found = {name.removeprefix("assets/") for name in names if name.startswith("assets/") and not name.endswith("/")}
        if any(PUBLICATION_TEXT.fullmatch(name.removeprefix("learning/")) for name in found):
            raise ValueError("Document full-text shards must not ship in Android APKs")
        required = set(expected) | set(learning)
        if found != required:
            raise ValueError(f"Unexpected asset inventory: {sorted(found ^ required)}")
        for name, data in expected.items():
            if archive.read("assets/" + name) != data:
                raise ValueError(f"Packaged asset differs from source: {name}")
        for name, data in learning.items():
            if archive.read("assets/" + name) != data:
                raise ValueError(f"Packaged dictionary asset differs from verified build inputs: {name}")
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
    parser.add_argument("--keyboard-only", action="store_true")
    parser.add_argument("--aapt", type=Path, help="Verify the offline manifest with Android Build Tools aapt")
    args = parser.parse_args()
    if args.aapt:
        manifest = verify_offline_manifest(args.apk, args.aapt)
        verify_component_icons(args.apk, args.aapt, manifest, args.keyboard_only)
        if args.keyboard_only and 'biz.tujia.ime.LearningActivity' in manifest:
            raise ValueError("Standalone keyboard unexpectedly registers the learning activity")
    verify(args.apk, args.typing_only, args.keyboard_only)
