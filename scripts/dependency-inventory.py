#!/usr/bin/env python3
"""Read resolved Maven artifacts/POMs; check or explicitly update the reviewed license inventory.
No network, build, signing, device changes or credential access.
"""
import argparse
import hashlib
import io
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'
LOCK = ROOT / 'third_party/runtime-dependencies.json'
NOTICE = ROOT / 'app/src/main/assets/open-source-notices.txt'

def pom_licenses(coordinate, visited=()):
    if coordinate in visited: raise ValueError('POM parent cycle')
    group, artifact, version = coordinate.split(':')
    paths = list((CACHE / group / artifact / version).glob('*/*.pom'))
    if not paths: raise ValueError(f'Missing cached POM: {coordinate}')
    xml = ET.parse(paths[0]).getroot()
    licenses = [{'name': item.findtext('{*}name', '').strip(), 'url': item.findtext('{*}url', '').strip()}
                for item in xml.findall('./{*}licenses/{*}license')]
    if licenses:
        if any(not item['name'] or not item['url'] for item in licenses): raise ValueError(f'Incomplete license: {coordinate}')
        return licenses
    parent = xml.find('./{*}parent')
    if parent is None: raise ValueError(f'Unidentified license: {coordinate}')
    return pom_licenses(':'.join(parent.findtext('{*}' + key, '') for key in ['groupId', 'artifactId', 'version']), visited + (coordinate,))

def embedded_notices(data, prefix=''):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in sorted(archive.namelist()):
            if name == 'classes.jar':
                yield from embedded_notices(archive.read(name), prefix + name + '/')
            elif re.search(r'(^|/)(LICENSE|NOTICE|COPYING)([._-][^/]*)?$', name, re.I) and not name.endswith('.class'):
                body = archive.read(name).decode('utf-8', errors='replace').replace('\r\n', '\n').replace('\r', '\n').strip()
                body = '\n'.join(line.rstrip() for line in body.splitlines())
                if body: yield prefix + name, body

def generate():
    rows = []
    texts = {}
    for line in (ROOT / 'app/build/reports/runtime-artifacts.tsv').read_text().splitlines():
        coordinate, filename = line.split('\t')
        data = Path(filename).read_bytes()
        notices = []
        for name, body in embedded_notices(data):
            digest = hashlib.sha256(body.encode()).hexdigest()
            texts[digest] = body
            notices.append({'path': name, 'sha256': digest})
        rows.append({'coordinate': coordinate, 'artifact': Path(filename).name,
                     'sha256': hashlib.sha256(data).hexdigest(), 'licenses': pom_licenses(coordinate), 'embeddedNotices': notices})
    result = {'schemaVersion': 1, 'scope': 'release runtime and core library desugaring; test dependencies excluded', 'artifacts': rows}
    lines = ['Shadow Media — Open source notices', '', 'Project license: GPL-3.0. Runtime inventory includes exact published artifact hashes.',
             'Original source and native rebuild provenance:', 'https://github.com/fish2018/WebHTV/tree/4b50754d3a2902eb4f94361669aa52079f3a2917', '']
    for row in rows:
        lines += [row['coordinate']] + [f"  {lic['name']}: {lic['url']}" for lic in row['licenses']]
        lines += [f"  Notice {notice['sha256']} ({notice['path']})" for notice in row['embeddedNotices']]
    for digest, body in sorted(texts.items()): lines += ['', 'Notice ' + digest, body]
    # Include the application GPL and inherited native attribution in the distributed notice.
    for file in [ROOT / 'LICENSE', ROOT / 'third_party/webhtv-mpv/NOTICE.md', ROOT / 'third_party/webhtv-mpv/MPVLIB-LICENSE']:
        lines += ['', file.name, file.read_text()]
    for file in sorted((ROOT / 'third_party/licenses').glob('*.txt')): lines += ['', file.name, file.read_text()]
    return json.dumps(result, ensure_ascii=False, indent=2) + '\n', '\n'.join(line.rstrip() for line in '\n'.join(lines).splitlines()).rstrip() + '\n'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--update', action='store_true', help='Write inventory and notices after reviewing dependency changes')
    args = parser.parse_args()
    lock, notice = generate()
    if args.update:
        LOCK.parent.mkdir(parents=True, exist_ok=True); NOTICE.parent.mkdir(parents=True, exist_ok=True)
        LOCK.write_text(lock); NOTICE.write_text(notice)
    else:
        if LOCK.read_text() != lock or NOTICE.read_text() != notice: raise SystemExit('Dependency/notice drift. Review before --update.')
    print(f'Runtime inventory: {len(json.loads(lock)["artifacts"])} artifacts; all licenses identified; notices match.')

if __name__ == '__main__': main()
