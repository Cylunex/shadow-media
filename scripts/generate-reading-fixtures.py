#!/usr/bin/env python3
"""Deterministic, authored regression publications. No user books or external services."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED, ZIP_STORED
from html import escape
import hashlib, json, struct, wave, zlib

ROOT = Path(__file__).resolve().parents[1] / 'fixtures' / 'reading'
ROOT.mkdir(parents=True, exist_ok=True)
FONT = ROOT / 'fonts' / 'NotoSans-Regular.ttf'
assert FONT.read_bytes()[:4] == b'\0\1\0\0', 'Fetch the licensed test font before regenerating.'

def archive(path, entries):
    with ZipFile(path, 'w') as book:
        for name, value in entries.items():
            info = ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_STORED if name == 'mimetype' else ZIP_DEFLATED
            book.writestr(info, value.encode() if isinstance(value, str) else value)

def epub(name, version='3.0', language='zh', rtl=False, fixed=False, font=False):
    entries = {'mimetype': 'application/epub+zip', 'META-INF/container.xml':
        '<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'}
    title = f'Shadow fixture {name}'
    body = '这是原创中文阅读测试。横竖屏切换后应保留位置，选文、书签和全文搜索应保持可用。'
    if rtl: body = 'اختبار القراءة من اليمين إلى اليسار. هذا نص تجريبي.'
    css = 'body { line-height:1.6; margin:1em; } p { margin-bottom:1em; }'
    if font:
        css += '@font-face { font-family:FixtureNoto; src:url(fonts/NotoSans-Regular.ttf); } body { font-family:FixtureNoto, sans-serif; }'
        entries['OPS/fonts/NotoSans-Regular.ttf'] = FONT.read_bytes()
        entries['OPS/fonts/OFL.txt'] = (ROOT / 'fonts' / 'OFL.txt').read_text()
    for chapter in (1, 2):
        content = f'<h1 id="heading">{escape(title)} / {chapter}</h1><p>SHADOW_SEARCH_NEEDLE_{chapter}</p>'
        content += ''.join(f'<p id="p{i}">{i + 1}. {body} Font sample: AV fi ffi 0123456789.</p>' for i in range(3 if fixed else 80))
        entries[f'OPS/chapter{chapter}.xhtml'] = f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="{language}" dir="{"rtl" if rtl else "ltr"}"><head><title>{escape(title)}</title><meta name="viewport" content="width=600,height=800"/><style>{css}</style></head><body>{content}</body></html>'
    ncx = f'<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="urn:shadow:{name}"/></head><docTitle><text>{title}</text></docTitle><navMap>'
    ncx += ''.join(f'<navPoint id="n{i}" playOrder="{i}"><navLabel><text>Chapter {i}</text></navLabel><content src="chapter{i}.xhtml"/></navPoint>' for i in (1, 2)) + '</navMap></ncx>'
    entries['OPS/toc.ncx'] = ncx
    nav = '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>'
    nav += ''.join(f'<li><a href="chapter{i}.xhtml">Chapter {i}</a></li>' for i in (1, 2)) + '</ol></nav></body></html>'
    if version == '3.0': entries['OPS/nav.xhtml'] = nav
    meta = '<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>' if version == '3.0' else ''
    if fixed: meta += '<meta property="rendition:layout">pre-paginated</meta><meta property="rendition:spread">none</meta>'
    manifest = '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'
    manifest += ''.join(f'<item id="c{i}" href="chapter{i}.xhtml" media-type="application/xhtml+xml"/>' for i in (1, 2))
    if version == '3.0': manifest += '<item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>'
    if font: manifest += '<item id="font" href="fonts/NotoSans-Regular.ttf" media-type="font/ttf"/><item id="license" href="fonts/OFL.txt" media-type="text/plain"/>'
    direction = ' page-progression-direction="rtl"' if rtl else ''
    entries['OPS/package.opf'] = f'<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="{version}" prefix="rendition: http://www.idpf.org/vocab/rendition/#"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:shadow:{name}</dc:identifier><dc:title>{title}</dc:title><dc:language>{language}</dc:language>{meta}</metadata><manifest>{manifest}</manifest><spine toc="ncx"{direction}><itemref idref="c1"/><itemref idref="c2"/></spine></package>'
    archive(ROOT / f'{name}.epub', entries)

epub('epub2-cjk', version='2.0')
epub('epub3-cjk-font', font=True)
epub('epub3-rtl', language='ar', rtl=True)
epub('epub3-fixed', fixed=True)
text = '\n'.join(f'第{i}章 测试\n这是原创 TXT 编码与段落转换测试 SHADOW_TXT_{i}。\n' for i in range(1, 5))
(ROOT / 'utf8.txt').write_text(text, encoding='utf-8')
(ROOT / 'gb18030.txt').write_bytes(text.encode('gb18030'))

def png(width, height, rgb):
    def chunk(kind, data): return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + bytes(rgb) * width for _ in range(height))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')
archive(ROOT / 'physical-pages.cbz', {'001.png': png(400, 600, (80, 130, 190)), '002.png': png(400, 1600, (140, 110, 70)), '003.png': png(600, 400, (130, 130, 140)), 'ComicInfo.xml': '<ComicInfo><Title>Physical page fixture</Title><Writer>Shadow test</Writer></ComicInfo>'})

objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>']
for number, word in ((1, 'ONE'), (2, 'TWO')):
    stream = f'BT /F1 18 Tf 25 350 Td (SHADOW_PDF_PAGE_{word}) Tj 0 -40 Td (Original physical page {number}) Tj ET'.encode()
    objects += [f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /Font << /F1 7 0 R >> >> /Contents {number * 2 + 2} 0 R >>'.encode(), b'<< /Length ' + str(len(stream)).encode() + b' >>\nstream\n' + stream + b'\nendstream']
objects += [b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
pdf = bytearray(b'%PDF-1.4\n'); offsets = [0]
for i, obj in enumerate(objects, 1): offsets.append(len(pdf)); pdf += f'{i} 0 obj\n'.encode() + obj + b'\nendobj\n'
xref = len(pdf); pdf += f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode()
pdf += b''.join(f'{offset:010d} 00000 n \n'.encode() for offset in offsets[1:])
pdf += f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
(ROOT / 'physical-pages.pdf').write_bytes(pdf)
with wave.open(str(ROOT / 'silence.wav'), 'wb') as audio:
    audio.setnchannels(1); audio.setsampwidth(2); audio.setframerate(16000); audio.writeframes(b'\0' * (6 * 16000 * 2))
(ROOT / 'legacy-locator.json').write_text(json.dumps({'href': 'OPS/chapter1.xhtml', 'type': 'application/xhtml+xml', 'locations': {'progression': .35, 'totalProgression': .175}, 'text': {'highlight': 'SHADOW_SEARCH_NEEDLE_1'}}, ensure_ascii=False, indent=2) + '\n')
files = [p for p in ROOT.rglob('*') if p.is_file() and p.name not in {'manifest.json', 'README.md'}]
(ROOT / 'manifest.json').write_text(json.dumps({str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}, indent=2) + '\n')
print(f'Generated {len(files)} fixed regression assets.')
