#!/usr/bin/env python3
"""Read-only source/release package checks. Never builds, signs, installs or uploads."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--expected-certificate-sha256')
    args = parser.parse_args()
    subprocess.run(['python3', str(ROOT / 'scripts/dependency-inventory.py')], check=True)
    for name, digest in json.loads((ROOT / 'fixtures/reading/manifest.json').read_text()).items():
        assert hashlib.sha256((ROOT / 'fixtures/reading' / name).read_bytes()).hexdigest() == digest, name
    for line in (ROOT / 'third_party/webhtv-mpv/SHA256SUMS').read_text().splitlines():
        digest, name = line.split(maxsplit=1)
        assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest, name
    for tree in ['app/src/main', 'app/src/release']:
        for file in (ROOT / tree).rglob('*'):
            if file.is_file() and file.suffix in {'.kt', '.java', '.xml'}:
                assert 'BenchmarkSetupActivity' not in file.read_text(), f'Test entry in {tree}'
    assert (ROOT / 'app/src/benchmark/AndroidManifest.xml').exists()
    if not args.apk:
        print('Source gates passed. APK, installed upgrade, Baseline Profile and device results were NOT checked.')
        return
    expected = (args.expected_certificate_sha256 or '').replace(':', '').lower()
    if not re.fullmatch('[0-9a-f]{64}', expected): raise SystemExit('--apk requires the independently trusted signing certificate SHA-256.')
    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        assert 'assets/dexopt/baseline.prof' in names, 'Real generated Baseline Profile is missing'
        assert 'assets/open-source-notices.txt' in names, 'Notices missing'
        forbidden = ['fixture-video.mp4', 'silence.wav', 'physical-pages.cbz', 'epub3-cjk-font.epub']
        assert not any(any(name.endswith('/' + item) for item in forbidden) for name in names), 'Fixtures shipped in release'
        for name in names:
            if name.endswith('.dex'): assert b'BenchmarkSetupActivity' not in archive.read(name), 'Benchmark component shipped'
    analyzer = shutil.which('apkanalyzer')
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/nonexistent')
    if not analyzer:
        candidates = sorted((sdk / 'cmdline-tools').glob('*/bin/apkanalyzer'))
        analyzer = str(candidates[-1]) if candidates else None
    if not analyzer: raise SystemExit('Set Android SDK/PATH to locate apkanalyzer; manifest verification is incomplete.')
    def manifest(field):
        return subprocess.run([analyzer, 'manifest', field, str(args.apk)], capture_output=True, text=True, check=True).stdout.strip()
    assert manifest('application-id') == 'top.cylunex.shadowmedia', 'Unexpected release application id'
    assert manifest('debuggable') == 'false', 'Debuggable package is not a release'
    signer = shutil.which('apksigner')
    if not signer:
        sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/nonexistent')
        candidates = sorted((sdk / 'build-tools').glob('*/apksigner'))
        signer = str(candidates[-1]) if candidates else None
    if not signer: raise SystemExit('Set Android SDK/PATH to locate apksigner; package verification is incomplete.')
    result = subprocess.run([signer, 'verify', '--verbose', '--print-certs', str(args.apk)], capture_output=True, text=True, check=True)
    certs = re.findall(r'certificate SHA-256 digest:\s*([0-9a-fA-F]+)', result.stdout)
    assert expected in [cert.lower() for cert in certs], 'Signing identity mismatch'
    assert re.search(r'Verified using v[23][^\n]*true', result.stdout), 'APK v2/v3 signature required'
    print('APK hashes, fixtures, notices, profile presence and trusted signing identity passed. Device upgrade remains a separate gate.')

if __name__ == '__main__': main()
