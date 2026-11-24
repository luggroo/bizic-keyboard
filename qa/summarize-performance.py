#!/usr/bin/env python3
import collections
import math
import re
import sys
from pathlib import Path

data = Path(sys.argv[1]).read_text()
samples = collections.defaultdict(list)
for metric, index, value in re.findall(r'INSTRUMENTATION_STATUS: sample=(\w+),(\d+),([\d.]+)', data):
    samples[metric].append(float(value))
passed = "INSTRUMENTATION_RESULT: outcome=PASS" in data
missing = re.findall(r'INSTRUMENTATION_STATUS: missing=([^,]+),', data)
print("Test completed successfully:" if passed else "Test did not complete successfully:", passed)
if missing:
    print("Missing editor input/draw observations:", dict(collections.Counter(missing)))
print("Completed observations only; guest ms; nearest rank: metric n p50 p95 p99 max samples<=30ms")
for metric, values in samples.items():
    values.sort()
    ranks = [values[math.ceil(len(values) * p) - 1] for p in (.5, .95, .99)]
    print(metric, len(values), *ranks, values[-1], sum(v <= 30 for v in values))
frames = re.search(r'^Total frames rendered:\s*(\d+)', data, re.M)
patterns = [r'^Total frames rendered:.*$']
if frames and int(frames.group(1)) > 0:
    patterns.extend((r'^Janky frames:.*$', r'^\d+th percentile:.*$'))
patterns.append(r'^\s*TOTAL\s+[\d\s]+$')
for pattern in patterns:
    for match in re.findall(pattern, data, re.M):
        print(match.strip())
