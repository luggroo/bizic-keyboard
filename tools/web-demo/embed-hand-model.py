#!/usr/bin/env python3
"""Embed the offline handwriting runtime and the Android Ismantic model."""
import argparse
import base64
import json
from pathlib import Path
from shutil import copyfile

root = Path(__file__).resolve().parents[2]
ap = argparse.ArgumentParser()
ap.add_argument('ort_dist', type=Path, help='onnxruntime-web 1.20.0 dist directory')
ap.add_argument('output', type=Path, help='directory containing the web demo')
args = ap.parse_args()
assets = root / 'app/src/main/assets/ismantic'
encode = lambda b: base64.b64encode(b).decode('ascii')
model = b''.join((assets / f'mobilenet-v2.onnx.{i}').read_bytes() for i in range(2))
data = {'labels': (assets / 'characters.txt').read_text(encoding='utf-8'),
        'model': encode(model), 'mjs': encode((args.ort_dist / 'ort-wasm-simd-threaded.mjs').read_bytes()),
        'wasm': encode((args.ort_dist / 'ort-wasm-simd-threaded.wasm').read_bytes())}
args.output.mkdir(parents=True, exist_ok=True)
(args.output / 'hand-data.js').write_text('globalThis.tujiaHandData='+json.dumps(data, ensure_ascii=False, separators=(',',':'))+';\n', encoding='utf-8')
copyfile(args.ort_dist / 'ort.min.js', args.output / 'ort.min.js')
