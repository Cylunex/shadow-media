# 媒体中心与外部源

## 当前实现

`1.1.0` 在原有“媒体库列表 → Feed”基础上提供相互隔离的媒体入口：

```text
媒体中心
├── Emby 首页：继续观看 / 最近新增 / 我的收藏
├── 媒体库：分页封面墙 / 搜索 / 筛选 / 排序 / 剧集详情
├── 刷片：仍读取完整可播放视频集合并维护 FeedSession
├── 网络媒体库：OpenList / WebDAV / SMB、NFO、海报与 STRM
└── 影视仓：URL/本地文件导入、视频列表与隔离播放
```

封面墙每次向 Emby 请求 60 条，继续滚动时使用 `StartIndex` 加载下一页。搜索、收藏、已看、未看、
续播和排序全部委托 Emby `/Users/{userId}/Items`，客户端不复制一套媒体数据库。电视媒体库首先展示
Series，进入详情后再读取 Episode；电影和家庭视频可直接进入播放器。

## 影视仓安全子集

当前接受：

- TVBox/FongMi 风格 JSON；
- 带 `//` 或 `/* */` 注释的 JSONC，以及顶层 `urls` 多仓目录；
- M3U；
- 包含 `#genre#` 的 TVBox TXT 直播列表。
- XMLTV 节目单、节目进度、当前/下一节目与标准 catch-up 回看。
- 同名频道多线路合并、线路切换、频道分组、搜索与本地收藏。

导入器只读取最多 2 MiB 的配置正文，远程地址要求 HTTPS；局域网 HTTP 必须由用户明确允许。
HTTPS 重定向不得降级为 HTTP。URL 与配置正文均使用 Android Keystore + AES-GCM 加密保存，本地
文件通过系统文件选择器读取后不依赖持续文件权限。旧版只保存摘要的订阅会提示重新导入。

网络正文采用真正的限长流式读取，短响应不会因未达到 2 MiB 上限而触发 EOF。TVBox 配置中的
`lives` 与多仓目录引用会并发限时展开，单个失效、超时或格式不兼容的二级源不会导致整个导入失败；
最多读取 8 个二级入口，拒绝 loopback 地址、HTTPS 降级重定向、图片、HTML 落地页和专用加密正文。

M3U 与 TXT 会转换为最多 5000 个标准视频条目，支持 `#EXTINF`、`group-title`、`tvg-logo`、
TVBox `#genre#` 分组、相对播放地址，以及播放地址 `|` 后的 User-Agent/Referer/Origin。点击条目后
使用独立 Media3 + OkHttp 播放链路；该客户端没有 Emby 拦截器、Cookie Jar、会话存储或进度上报，
因此不会向视频源或跳转 CDN 泄露 Emby 凭据。

`api` 为 HTTP(S) 的站点会计入远程 API；其他站点标记为“需要隔离运行时”。当前不会执行：

- 动态 JAR / DexClassLoader；
- QuickJS；
- Python / Chaquopy；
- WebView 嗅探；
- 配置中的解析器、代理、Hosts 或任意脚本。

## 后续 Provider 边界

下一阶段在现有直播 Provider 上继续实现不执行代码的声明式 Provider：

```text
ExternalSourceSubscription
        │
        ├── DeclarativeHttpProvider ── 分类 / 搜索 / 详情 / resolve
        └── LivePlaylistProvider ───── M3U / TXT（已实现）/ XMLTV
```

需要完整 CatVod 兼容时，运行时必须位于独立 APK 或 NAS 服务中。主应用只接收标准化的 home、search、
detail 和 resolve 结果；运行时永远拿不到 Emby Token、用户密码、会话存储或播放进度数据库。
