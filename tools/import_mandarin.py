#!/usr/bin/env python3
"""Extract common Mandarin words from two pinned wheels. Requires Python msgpack."""
import argparse
import gzip
import hashlib
import re
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile

import msgpack

ROOT = Path(__file__).resolve().parents[1]
WORDFREQ = "4b1c6ecffc6198be3396d5cf871c4423ca71c907c231348d352dd54d62b97473"
PYPINYIN = "d53b1e8ad2cdb815fb2cb604ed3123372f5a28c6f447571244aca36fc62a286f"
HAN = re.compile(r"[\u3400-\u9fff]{1,8}")
PINYIN = re.compile("[a-z]+")


def checked(path, sha):
    if hashlib.sha256(path.read_bytes()).hexdigest() != sha:
        raise ValueError(f"Unexpected source file: {path}")
    return ZipFile(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wordfreq", type=Path, help="wordfreq-3.1.1-py3-none-any.whl")
    parser.add_argument("pypinyin", type=Path, help="pypinyin-0.55.0-py2.py3-none-any.whl")
    parser.add_argument("--out", type=Path, default=ROOT / "tools/data/mandarin.tsv")
    parser.add_argument("--licenses", type=Path, default=ROOT / "shared/bundle/Assets/licenses")
    args = parser.parse_args()
    args.licenses.mkdir(parents=True, exist_ok=True)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with checked(args.wordfreq, WORDFREQ) as w:
        freq = msgpack.unpackb(gzip.decompress(w.read("wordfreq/data/small_zh.msgpack.gz")), raw=False)
        if freq[0] != {"format": "cB", "version": 1}:
            raise ValueError("Unexpected frequency format")
        upstream_notice = w.read("wordfreq-3.1.1.dist-info/METADATA").decode().split("## License\n",1)[1]
    with tempfile.TemporaryDirectory() as temp, checked(args.pypinyin, PYPINYIN) as p:
        p.extractall(temp)
        (args.licenses / "pypinyin-MIT.txt").write_bytes(p.read("pypinyin-0.55.0.dist-info/licenses/LICENSE.txt"))
        sys.path.insert(0, temp)
        from pypinyin import lazy_pinyin
        rows = []
        for band, words in enumerate(freq[1:]):
            for han in words:
                if not HAN.fullmatch(han):
                    continue
                reading = "".join(lazy_pinyin(han))
                if PINYIN.fullmatch(reading):
                    rows.append((reading, han, 900 - band))
    rows.sort(key=lambda e: (e[0], -e[2], e[1]))
    args.out.write_text("".join(f"{key}\t{word}\t{zipf100}\n" for key,word,zipf100 in rows), encoding="utf-8")
    notice = """Mandarin pinyin fallback dataset

The Mandarin frequency data in the rows marked zh and their browser export are
adapted from Robyn Speer's wordfreq 3.1.1 small_zh, CC BY-SA 4.0. This adaptation
is available under the same license. https://creativecommons.org/licenses/by-sa/4.0/
Project and data: https://github.com/rspeer/wordfreq
Release: https://pypi.org/project/wordfreq/3.1.1/

Changes: keep common Han-only tokens up to eight characters; add untoned pinyin
using pypinyin 0.55.0. A frequency value equals 100 times the original Zipf score
(900 minus the original frequency-band index), not a token or reading count.
The pronunciation uses pypinyin's phrase lookup and default character reading.
pypinyin: https://github.com/mozillazg/python-pinyin (see pypinyin-MIT.txt).
This data license applies to the adapted Mandarin frequency data.

Original wordfreq acknowledgements and references follow.
"""
    (args.licenses / "mandarin-frequency.txt").write_text(notice + upstream_notice, encoding="utf-8")
    print(f"Imported {len(rows)} Mandarin entries ({sum(len(r[1]) == 1 for r in rows)} single characters).")


if __name__ == "__main__":
    main()
