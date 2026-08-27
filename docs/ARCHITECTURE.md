# 架构说明

## 产品边界

Shadow Media 是 Emby 客户端，不是媒体服务器或解码器。MoviePilot、STRM 助手、Emby 和
MediaWarp 继续负责资源入库、元数据、播放地址和 302；客户端只负责 Feed、播放编排、Media3
以及用户状态回写。

首版有意不包含 115 登录、下载、离线缓存、刮削、NFO 编辑、Jellyfin/Plex、TV 和 libmpv。

## 分层

```text
Compose UI
   │  immutable UiState / user actions
   ▼
MainViewModel
   │
   ├── EmbyRepository ── OkHttp ── Emby REST API
   │
   └── PlaybackRuntime ── Media3 ── Emby / MediaWarp / CDN
                              │
                              └── EmbyRepository playback reports
```

- `core:model` 不依赖 Android，保存跨层稳定模型。
- `core:network` 是远端数据源和会话数据源的唯一入口。
- `core:playback` 拥有播放器生命周期、播放候选回退和状态上报。
- `app` 是 composition root，当前使用手动构造器注入；规模增加后再评估 Hilt。
- UI 使用单向数据流，Composable 不直接访问网络数据源。

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

1. 优先使用 `DirectStreamUrl`；若媒体源声明 `SupportsDirectPlay`，上报为 DirectPlay；
2. 将 `TranscodingUrl` 作为下一候选，通常为 Emby HLS；
3. 保存 `MediaSourceId` 与 `PlaySessionId`，用相同播放方法上报状态；
4. 不猜测 `/Videos/{id}/stream.mp4?Static=true`，也不持久化 302 后的 CDN URL。

接口依据：

- <https://dev.emby.media/reference/RestAPI/MediaInfoService/postItemsByIdPlaybackinfo.html>
- <https://dev.emby.media/doc/restapi/Playback-Check-ins.html>

当前回退覆盖同一次 PlaybackInfo 返回的直连 → 转码候选。401/403、网络切换和长时间暂停后重新
请求 PlaybackInfo 会在兼容性验证阶段加入，此时需要把播放器错误、ConnectivityManager 和应用
前后台恢复统一接入一个恢复状态机。

## Feed 播放策略

端到端验证完成后，Feed 采用 Media3 1.11 官方短视频示例的组合：

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

首版不引入 Paging 3 或 Room：20 条验证列表没有离线数据一致性问题。Feed 开始分页、排重和保存
失败记录时，再同时引入 Room、PagingSource 和本地单一事实源，避免现在留下未使用的基础设施。
