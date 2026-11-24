#!/usr/bin/env python3
"""Create a file://-compatible bundle from the exact mobile engine and its input resources."""
import argparse
import base64
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
ap = argparse.ArgumentParser()
ap.add_argument('wasm', type=Path)
ap.add_argument('output', type=Path)
args = ap.parse_args()
assets = root/'shared/bundle/Assets'
files = {'words':'words.tsv', 'ngrams':'ngrams.tsv', 'sounds':'syllable_bigrams.tsv',
         'pen':'handwriting/medians.tsv'}
if (assets/'syllable_senses.tsv').exists():
    files['senses'] = 'syllable_senses.tsv'
if (assets/'handwriting-readings.tsv').exists():
    files['readings'] = 'handwriting-readings.tsv'
data = {name:(assets/file).read_text() for name,file in files.items()}
data['words'] += (assets/'northern-words.tsv').read_text()
data['wasm'] = base64.b64encode(args.wasm.read_bytes()).decode('ascii')
args.output.write_text('globalThis.tujiaCoreData=' + json.dumps(data, ensure_ascii=False, separators=(',',':')).replace('<','\\u003c') + ';\n')
