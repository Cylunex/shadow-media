#!/usr/bin/env python3
"""Generate small original test media, with no external/copyrighted input. Requires ffmpeg/ffprobe."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1] / "fixtures" / "video"
ROOT.mkdir(parents=True, exist_ok=True)
ffmpeg = shutil.which("ffmpeg")
ffprobe = shutil.which("ffprobe")
if not ffmpeg or not ffprobe:
    raise SystemExit("Install ffmpeg and ffprobe before generating video fixtures")

def encode(*args):
    subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", *map(str, args)], check=True)

(ROOT / "sample.zh.srt").write_text("1\n00:00:00,250 --> 00:00:01,750\nShadow Media 字幕测试\n", encoding="utf-8")
encode("-f", "lavfi", "-i", "testsrc2=size=256x144:rate=12", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
       "-t", "2", "-c:v", "libx264", "-preset", "ultrafast", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "12",
       "-c:a", "aac", "-ac", "2", "-b:a", "64k", "-map_metadata", "-1", "-movflags", "+faststart", ROOT / "avc-aac.mp4")
encode("-i", ROOT / "avc-aac.mp4", "-i", ROOT / "sample.zh.srt", "-map", "0:v", "-map", "0:a", "-map", "0:a", "-map", "1:s",
       "-c", "copy", "-metadata:s:a:0", "language=eng", "-metadata:s:a:0", "title=Main", "-metadata:s:a:1", "language=jpn",
       "-metadata:s:a:1", "title=Commentary", "-metadata:s:s:0", "language=zho", ROOT / "multi-track.mkv")
encode("-i", ROOT / "avc-aac.mp4", "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-c:a", "libopus", ROOT / "vp9-opus.webm")
encode("-i", ROOT / "avc-aac.mp4", "-an", "-c:v", "libsvtav1", "-preset", "12", "-svtav1-params", "lp=2", ROOT / "av1.mp4")
(ROOT / "hls").mkdir(exist_ok=True)
encode("-i", ROOT / "avc-aac.mp4", "-c", "copy", "-hls_time", "1", "-hls_list_size", "0", "-hls_segment_filename", ROOT / "hls" / "part%02d.ts", ROOT / "hls" / "index.m3u8")
manifest = {}
for path in sorted(ROOT.rglob("*")):
    if path.suffix not in {".mp4", ".mkv", ".webm", ".m3u8"}:
        continue
    metadata = json.loads(subprocess.check_output([ffprobe, "-v", "error", "-show_entries", "stream=index,codec_name,codec_type:stream_tags=language,title", "-of", "json", str(path)]))
    subprocess.run([ffmpeg, "-nostdin", "-v", "error", "-i", str(path), "-map", "0:v?", "-map", "0:a?", "-f", "null", "-"], check=True)
    manifest[str(path.relative_to(ROOT))] = {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "streams": metadata["streams"]}
(ROOT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Generated and decoded {len(manifest)} fixtures in {ROOT}")
