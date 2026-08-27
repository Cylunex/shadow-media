# 参考实现与取舍

本项目优先使用 AndroidX Media3 官方 API 和示例。以下项目只用于比较交互与生命周期方案，
本轮没有复制其源码：

- [NextPlayer](https://github.com/anilbeesetti/NextPlayer)：成熟的 Media3 播放器交互、字幕和
  MediaSession 参考；其许可证为 GPL-3.0，不直接并入当前工程。
- [SnapReel](https://github.com/shahriar-ahmed-seam/SnapReel)：垂直本地媒体浏览交互参考，
  不采用它的本地文件数据边界。
- [clown6613/ComposeReels](https://github.com/clown6613/ComposeReels)：单播放器和单向数据流
  的小型示例，只参考状态协调思路。
- [manjees/compose-reels](https://github.com/manjees/compose-reels)：PlayerPool 机制参考；当前
  不照搬较大的默认播放器池，避免 NAS/CDN 场景并发和内存开销失控。
- [oguzhanaslann/ComposeReels](https://github.com/oguzhanaslann/ComposeReels)：仓库规模较小且
  未声明许可证，不复制代码。

生产实现的依据是 Media3 官方
[PreloadManager 概念文档](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/concepts)
以及 Emby 的
[Playback Check-ins](https://dev.emby.media/doc/restapi/Playback-Check-ins.html)。

当前选择单活跃播放器、稳定 FeedSession、持久化进度 Outbox 和播放地址单次自动刷新。待真实
Emby/MediaWarp/115 链路完成兼容性与资源占用采样后，再决定是否启用容量受控的 PlayerPool 与
方向感知预加载。
