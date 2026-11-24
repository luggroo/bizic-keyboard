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
print("guest ms; nearest rank: metric n p50 p95 p99 max samples<=30ms")
for metric, values in samples.items():
    values.sort()
    ranks = [values[math.ceil(len(values) * p) - 1] for p in (.5, .95, .99)]
    print(metric, len(values), *ranks, values[-1], sum(v <= 30 for v in values))
for pattern in (r'^Total frames rendered:.*$', r'^Janky frames:.*$',
                r'^\d+th percentile:.*$', r'^\s*TOTAL\s+[\d\s]+$'):
    for match in re.findall(pattern, data, re.M):
        print(match.strip())
