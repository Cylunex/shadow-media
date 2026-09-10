#!/usr/bin/env python3
"""Import actual device-generated text profiles; reject fixtures and never fabricate a profile."""
import argparse
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument('profiles', type=Path, nargs='+', help='Actual BaselineProfileRule output text files')
parser.add_argument('--startup', action='store_true', help='Only pass outputs from the startup test')
args = parser.parse_args()
lines = set()
for file in args.profiles:
    if not file.is_file(): raise SystemExit(f'Missing actual profile: {file}')
    for line in file.read_text().splitlines():
        if not line or line.startswith('#'): continue
        if not re.match(r'^[HSP]*L[^;]+;', line): raise SystemExit(f'Invalid ART profile line in {file.name}')
        if any(item in line for item in ['BenchmarkApplication', 'BenchmarkFixtureServer', 'BenchmarkSetupActivity', '/perftest/']): continue
        lines.add(line)
if len(lines) < 10: raise SystemExit('Profile is empty or too small to establish a real journey.')
root = Path(__file__).resolve().parents[1]
target = root / 'app/src/main' / ('startup-prof.txt' if args.startup else 'baseline-prof.txt')
target.write_text('\n'.join(sorted(lines)) + '\n')
print(f'Imported {len(lines)} generated rules into {target.name}; rerun performance comparison before release.')
