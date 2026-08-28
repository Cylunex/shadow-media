# 架构说明

## 产品边界

Shadow Media 是以 Emby 为第一方来源的私人媒体中心，不是媒体服务器。MoviePilot、STRM 助手、
Emby 和 MediaWarp 继续负责资源入库、元数据、播放地址和 302；客户端负责媒体中心投影、封面墙、
Feed、播放编排和状态回写。用户自带外部配置属于独立 Provider 边界，不会获得 Emby 凭据。

当前有意不包含 115 登录、下载、离线缓存、刮削和 NFO 编辑。libmpv 只作为 ISO/DVD/Blu-ray
专用后端，普通视频仍由 Media3 负责。未知 CatVod JAR、QuickJS、Python 和 WebView 嗅探不进入
主进程；完整兼容需要未来的独立 APK 或 NAS Source Runtime。

## 分层

```text
Compose UI
   │  immutable UiState / user actions
   ▼
MainViewModel
   │
   ├── EmbyRepository ── Catalog / 收藏 / PlaybackInfo ── Emby REST API
   ├── ExternalSourceRepository ── JSONC / 多仓 / lives 展开 / M3U、TXT 解析（不执行代码）
   ├── ExternalSourceStore ── 加密订阅正文与摘要
   ├── FeedSessionStore ── 每服/用户/媒体库的稳定顺序与当前位置
   │
   └── PlaybackRuntime ─┬─ Media3 ───────────── 普通视频 / HLS
                       ├─ 隔离 Media3 ───────── 外部视频源（无 Emby 凭据）
                       └─ libmpv 光盘后端 ─── ISO / DVD / Blu-ray
                              │
                              └── PlaybackOutbox ── EmbyRepository playback reports
```

- `core:model` 不依赖 Android，保存跨层稳定模型。
- `core:provider` 定义统一的首页、浏览、搜索、详情和播放解析协议；具体 Provider 不向 UI 暴露网络 DTO。
- `core:database` 使用 Room 保存跨 Provider 历史、收藏、搜索、媒体时刻、播放指标和线路健康度，
  并提供 Paging 数据源。服务端仍是 Emby 收藏和进度的权威，本地库用于聚合与离线状态。
- `core:network` 是远端数据源和会话数据源的唯一入口。
- `core:playback` 拥有播放器生命周期、播放候选回退和状态上报。
- `app` 是 composition root，当前使用手动构造器注入；规模增加后再评估 Hilt。
- UI 使用单向数据流，Composable 不直接访问网络数据源。

`MediaProvider`、`MediaKey`、`UnifiedMediaItem`、`MediaDetail` 和 `UnifiedPlaybackRequest` 构成跨源协议。
Emby、直播列表和声明式 HTTP CMS 均通过 `ProviderRegistry` 注册；聚合搜索并发查询声明了 SEARCH
能力的 Provider，单源故障只形成可见诊断，不会清空其他结果。需要 JAR、QuickJS 或 Python 的站点
仍不进入主进程，后续只通过隔离的 CatVod Bridge 接入。

这一结构遵循 Android 官方的 UI/data 分层、repository、单向数据流和 screen-level ViewModel
建议：<https://developer.android.com/topic/architecture/recommendations>。

## 版本选择

- AGP 8.13.2 + Gradle 8.13 + JDK 17；`compileSdk 36`，`targetSdk 36`；
- Kotlin 2.2.21 + Compose Compiler Gradle Plugin；
- Compose BOM 2026.01.01，而非 2026.08.00：后者的 Compose 1.12 需要 API 37 和 AGP 9.1.2；
- Media3 1.11.0：正式包含 Material 3 `Player`、`PlayerPool`、`rememberPooledPlayer`；
- OkHttp 作为 Emby API 与 Media3 DataSource 的统一 HTTP 栈。

参考：

- <https://developer.android.com/blog/posts/media3-1-11-whats-new>
- <https://developer.android.com/media/media3/ui/compose>
- <https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager>
- <https://github.com/androidx/media/tree/release/demos/compose>

## PlaybackInfo 决策

客户端使用 `POST /Items/{Id}/PlaybackInfo`，提交用户、码率上限与首版设备能力描述。返回后：

1. 优先使用服务端返回的 `DirectStreamUrl`；
2. 若媒体源声明 `SupportsDirectPlay` 但没有返回直连 URL，则构造带 `MediaSourceId`、
   `PlaySessionId` 和 `Static=true` 的标准 `/Videos/{id}/stream.{container}` 地址；
3. 将 `TranscodingUrl` 作为下一候选，通常为 Emby HLS；
4. 保存 `MediaSourceId` 与 `PlaySessionId`，用实际播放方法上报状态；
5. Token 只放在同源请求头，不放进播放 URL，也不持久化 302 后的 CDN URL。

接口依据：

- <https://dev.emby.media/reference/RestAPI/MediaInfoService/postItemsByIdPlaybackinfo.html>
- <https://dev.emby.media/doc/restapi/Playback-Check-ins.html>

当前先遍历同一次 PlaybackInfo 返回的直连 → 转码候选；候选全部失败后自动重新请求一次
PlaybackInfo，并从失败位置恢复。一次刷新仍失败就停止自动循环并显示诊断，避免错误链路无限重试。

Playing/Progress/Stopped 先写入持久化 Outbox，再尝试发送。记录只包含服务器/用户 ID、媒体 ID、
播放会话 ID、位置和事件，不包含 Access Token 或播放 URL；切回对应服务器时会顺序补报。

## Feed 播放策略

当前 MVP 已使用 `VerticalPager`，只让停稳的当前页持有一个 Media3 播放器；切页立即停止并释放
旧播放器，PlaybackInfo 按页惰性解析。真实链路验证完成后，Feed 采用 Media3 1.11 官方短视频
示例的完整组合：

```text
VerticalPager
  ├── PlayerPool(capacity = 3)
  ├── rememberPooledPlayer
  └── DefaultPreloadManager.Builder
        ├── 当前项：PlayerPool 播放
        ├── 下一项：预载 3 秒
        ├── 上一项：预载 1 秒
        └── 更远项：只准备 source / tracks 或不加载
```

`PlayerPool` 和 `DefaultPreloadManager` 共享同一个 Builder 创建的 ExoPlayer。媒体元数据可以多取，
但签名播放地址只解析当前和后续 1–2 条，避免 115/CDN URL 在真正播放前过期。

当前数据层通过 Emby `StartIndex + Limit` 远端分页，循环读取到 `TotalRecordCount`，因此媒体库内容
不设条数上限。它暂时仍一次性保存于内存；需要增量渲染、排重和失败记录时，再引入 Room、
PagingSource 和本地单一事实源。

FeedSession 按 `serverId + userId + libraryId` 隔离。重新进入媒体库时保留仍存在条目的原有顺序，
把新条目追加到尾部，并恢复上次停留位置；删除条目时同步从 FeedSession 移除。

ISO/DVD 镜像不属于 Media3 可直接播放的媒体容器，Emby Server 也不支持 ISO 转码。解析器识别
`VideoType=Iso` 或 `container=iso` 后构造当前 Emby origin 的静态 ISO 流，并交给独立的
`MpvIsoPlaybackRuntime`：

```text
libmpv + libplayer.so
  → webhtv-dvdiso:// 会话
  → JNI stream_cb
  → IsoSessionManager
  → 4 MiB × 8 页 LRU 内存缓存
  → OkHttp 严格 Range / 302 / 精确 origin 鉴权
  → Emby / MediaWarp / CDN
```

原生层由 libbluray/libdvdnav 解释光盘结构，因此时间轴、章节和跨 M2TS 片段拖动不依赖 Media3
Extractor。应用自动选择最长标题，暴露播放/暂停、精确 Seek、章节、音轨与字幕循环，并通过同一
PlaybackOutbox 上报 Emby 状态。上游必须支持语义正确的 `206 + Content-Range`；返回 200、
长度变化或 ETag/Last-Modified 变化会立即终止，触发一次 PlaybackInfo 刷新。VLC 作为用户可选
的外部兜底，不是默认 ISO 引擎。完整能力边界见 `docs/ISO_PLAYBACK.md`。
