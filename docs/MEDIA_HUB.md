# 媒体中心与外部源

## 当前实现

`0.6.0` 把原来的“媒体库列表 → Feed”扩展成两个互不混淆的入口：

```text
媒体中心
├── Emby 首页：继续观看 / 最近新增 / 我的收藏
├── 媒体库：分页封面墙 / 搜索 / 筛选 / 排序 / 剧集详情
├── 刷片：仍读取完整可播放视频集合并维护 FeedSession
└── 影视仓：用户自带配置的安全检查与订阅管理
```

封面墙每次向 Emby 请求 60 条，继续滚动时使用 `StartIndex` 加载下一页。搜索、收藏、已看、未看、
续播和排序全部委托 Emby `/Users/{userId}/Items`，客户端不复制一套媒体数据库。电视媒体库首先展示
Series，进入详情后再读取 Episode；电影和家庭视频可直接进入播放器。

## 影视仓安全子集

当前接受：

- TVBox/FongMi 风格 JSON；
- M3U；
- 包含 `#genre#` 的 TVBox TXT 直播列表。

检查器只下载最多 2 MiB 的配置正文，要求 HTTPS；局域网 HTTP 必须由用户逐次明确允许。HTTPS
重定向不得降级为 HTTP。检查结果只保存 URL、名称、站点/直播数量和运行时需求，不保存配置正文；
包含 URL 的订阅元数据使用 Android Keystore + AES-GCM 加密，也不会向请求附加任何 Emby Header、
Token 或 Cookie。

`api` 为 HTTP(S) 的站点会计入远程 API；其他站点标记为“需要隔离运行时”。当前不会执行：

- 动态 JAR / DexClassLoader；
- QuickJS；
- Python / Chaquopy；
- WebView 嗅探；
- 配置中的解析器、代理、Hosts 或任意脚本。

## 后续 Provider 边界

下一阶段先实现不执行代码的声明式 Provider 和直播 Provider：

```text
ExternalSourceSubscription
        │
        ├── DeclarativeHttpProvider ── 分类 / 搜索 / 详情 / resolve
        └── LivePlaylistProvider ───── M3U / TXT / XMLTV
```

需要完整 CatVod 兼容时，运行时必须位于独立 APK 或 NAS 服务中。主应用只接收标准化的 home、search、
detail 和 resolve 结果；运行时永远拿不到 Emby Token、用户密码、会话存储或播放进度数据库。
