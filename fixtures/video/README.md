# 原创视频测试样本

所有图像/音频来自 ffmpeg 的 `testsrc2` 和 `sine`，字幕由项目生成；不含外部影片、账号或服务地址。
样本均约 2 秒、256×144，用于快速重复验收，不代表高码率性能。

| 文件 | 内容 |
| --- | --- |
| avc-aac.mp4 | H.264 Baseline + AAC 双声道 |
| multi-track.mkv | H.264 + 两条 AAC（eng/Main、jpn/Commentary）+ zho SRT |
| vp9-opus.webm | VP9 + Opus |
| av1.mp4 | AV1，无音频；无解码器的设备用于验证明确失败与模式切回 |
| hls/index.m3u8 | H.264/AAC 两个 TS 分片，VOD |
| sample.zh.srt | 中文外挂字幕 |
| manifest.json | 每项容器的流信息、字节数、SHA-256 |

安装具有 libx264、libvpx-vp9、libopus、libsvtav1 的 ffmpeg 及 ffprobe 后，运行：

```sh
python3 scripts/generate-video-fixtures.py
```

脚本重新生成并逐一用 ffmpeg 解码，任何失败都会中止。不同 ffmpeg 版本可能产生不同字节，需连同清单一起更新。
设备验收可以在临时目录将 MP4 循环拼接成长样本，或用本机 HTTP 测试服务提供这些文件；不要把设备实际地址、私有 URL 或 Token 写入仓库。
