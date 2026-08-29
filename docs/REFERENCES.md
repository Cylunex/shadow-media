# 参考实现与取舍

本项目优先使用 AndroidX Media3 官方 API 和示例。以下项目只用于比较交互与生命周期方案，
除单独声明的 ISO 后端外，没有复制其源码：

- [NextPlayer](https://github.com/anilbeesetti/NextPlayer)：成熟的 Media3 播放器交互、字幕和
  MediaSession 参考；其许可证为 GPL-3.0，本项目只提炼交互原则，没有复制页面源码。
- [AFinity](https://github.com/MakD/AFinity)：活跃的 Compose + Material 3 + Jellyfin 客户端，
  用于比较首页层级、海报网格、媒体库筛选与低干扰播放器控件。本项目采用深色低对比表面、
  大标题和内容优先的信息层级，组件均为独立实现。
- [JellyPlay](https://github.com/raulshma/jellyplay)：GPL-3.0 的 Material 3 Expressive 媒体客户端，
  用于比较圆角卡片、继续播放入口、多服务状态表达和海报加载策略。
- [Sashimi Android](https://github.com/bitstorm-labs/sashimi-android)：用于比较可配置首页内容行、
  继续观看卡片、完整媒体库网格以及排序/筛选状态拆分；只参考公开行为与页面结构。
- [EmbyX](https://github.com/juneix/EmbyX)：MIT Web/PWA 项目，用于比较分页封面墙、随机换一批与
  Feed/墙之间的入口关系；没有把 Web 组件嵌入原生应用。
- [FongMi/TV](https://github.com/FongMi/TV)：GPL-3.0 的 CatVod 客户端，用于核对 `sites`、`lives`、
  直播订阅和解析/运行时边界。Shadow 只独立实现配置元数据检查，不复制其 JAR、QuickJS、Python、
  WebView 嗅探或播放器代码。
- [SnapReel](https://github.com/shahriar-ahmed-seam/SnapReel)：垂直本地媒体浏览交互参考，
  不采用它的本地文件数据边界。
- [clown6613/ComposeReels](https://github.com/clown6613/ComposeReels)：单播放器和单向数据流
  的 Apache-2.0 小型示例，参考底部渐变信息区、顶部悬浮操作和状态协调思路。
- [manjees/compose-reels](https://github.com/manjees/compose-reels)：PlayerPool 机制参考；当前
  不照搬较大的默认播放器池，避免 NAS/CDN 场景并发和内存开销失控。
- [oguzhanaslann/ComposeReels](https://github.com/oguzhanaslann/ComposeReels)：仓库规模较小且
  未声明许可证，不复制代码。
- [SMBJ](https://github.com/hierynomus/smbj)：Apache-2.0 的 SMB2/SMB3 Java 客户端；网络媒体库
  使用其公开 API 完成认证、目录枚举和随机读取，不包含 SMB1 降级实现。
- [OpenList](https://github.com/OpenListTeam/OpenList)：依据官方 `/api/fs/list`、`/api/fs/get`、
  `/d/`、`/p/` 和 WebDAV 协议行为独立实现客户端适配，没有复制服务端源码。

生产实现的依据是 Media3 官方
[PreloadManager 概念文档](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/concepts)
以及 Emby 的
[Playback Check-ins](https://dev.emby.media/doc/restapi/Playback-Check-ins.html)。

ISO 属于特殊边界：[Emby 团队说明](https://emby.media/community/topic/125851-zidoo-emby-cannot-open-3d-iso-files-only/)
服务器不支持 ISO 转码，只能把完整镜像交给能够直接读取它的播放器。应用内 ISO 后端采用
[WebHTV](https://github.com/fish2018/WebHTV) 的 GPL-3.0 MPV/JNI 基线及
`webhtv-dvdiso` stream callback 设计，使用 libbluray/libdvdnav 解析光盘；来源版本、二进制
哈希和对应源码见 `third_party/webhtv-mpv/`。VLC for Android 只保留为显式外部兜底。

当前选择单活跃播放器、稳定 FeedSession、持久化进度 Outbox 和播放地址单次自动刷新。待真实
Emby/MediaWarp/115 链路完成兼容性与资源占用采样后，再决定是否启用容量受控的 PlayerPool 与
方向感知预加载。

界面中的 Emby 海报使用 Coil 加载。认证头仅在与当前 Emby 服务严格同源时附加；发生跨域重定向
时会移除 Token、Authorization 和 Cookie，保持与播放链路一致的凭据隔离边界。
