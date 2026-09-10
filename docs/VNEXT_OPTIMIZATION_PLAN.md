# Shadow Media vNext 优化改造方案

状态：目标设计；首批队列与进度改动已接入，范围和限制见 [2026-09-10 补充研究与实现](VNEXT_RESEARCH_2026_09_10.md)。
后续三轮全功能复查已接入文件内章节导航及消费链路修复，见 [本轮走查与验证状态](LOGIC_AUDIT_2026_09_10.md)。下文仍包含未交付的目标设计。

核查日期：2026-09-10。原实现基线：`ef5d17b`，方案基线：`22e96ff`；应用版本仍为 `1.1.1 (20)`。

输入包括用户提供的三份评估、当前代码与路线图，以及本文末尾的一手资料。实际能力以
[全媒介实施说明](MULTIMEDIA_IMPLEMENTATION.md) 和 [逻辑走查记录](LOGIC_AUDIT_2026_09_04.md)
为准。

## 1. 结论

下一轮不应继续横向增加孤立页面，也不应推倒现有五入口和播放链路。建议锁定为：

> 用本地目录快照和统一用户状态把五类体验收口，用可恢复的连续音频会话加入音乐，随后再做
> 可度量的 Feed 预加载、离线任务和更多来源。

具体决策：

1. 保留 Emby PlaybackInfo、302、ISO/libmpv、网络存储和直播现有路径，不让新功能增加视频
   起播前的 HEAD、完整下载或作品匹配。
2. 现有 `AudiobookService` 是后台音频基础，不再新建第二个音乐服务；将它逐步演化为支持
   `AUDIOBOOK` 与 `MUSIC` 两种会话模式的连续音频服务。
3. 先持久化队列和用户状态，再做专辑、艺人、歌单等页面。否则进程重建、系统媒体恢复、切账号
   和同曲目重复入队会持续制造补丁。
4. `Work / Rendition / ResourceRevision` 已有模型雏形，但不一次性改写所有旧表。先让新链路
   使用，再通过只读聚合视图兼容旧影视历史。
5. 目录、播放缓存、明确离线副本和临时文件必须成为四种不同状态；只有明确离线副本受用户管理，
   不被普通缓存淘汰。
6. 视频 Feed 使用 `DefaultPreloadManager` 做小窗口实验；音乐队列使用 ExoPlayer 播放列表预加载。
   两者共享资源预算，但不共享 Player、临时 URL 或凭据。
7. 来源扩展采用能力矩阵和原生协议。任意脚本运行时仍不进入主进程，不能为了“源更多”削弱凭据边界。

## 2. 对附件的校正

| 附件判断 | 当前核查 | 新决策 |
| --- | --- | --- |
| 项目没有后台音频 Service | 已有 `AudiobookService : MediaSessionService`、队列、倍速、睡眠和迷你栏 | 扩展现有服务，不另建 MusicService |
| 音频 Lint 是发布阻塞 | 历史报告记录通过；本次完整验证实际发现旧 SessionResult 错误码触发 WrongConstant，已改用 SessionError | 以当前完整测试/Lint 结果为准，避免把历史记录当门禁 |
| 音乐应等视频全部 Service 化 | 视频与听书生命周期不同，ISO 也不适合强塞进同一服务 | 音乐可在现有音频服务内先落地；视频 Service 化独立评估 |
| Media3 1.11 可直接带来 M4B 章节 | 后续复查已接入文件内 Chapter 列表与 Seek；ABS 章节映射和章节睡眠尚未完成 | 继续真实样本验收，按子能力交付 |
| 直接升级 Readium | 项目固定 3.1.2；当前稳定版 3.3.0，且次版本允许小型破坏性变化 | 建兼容层和样本矩阵后单独升级 |
| GPL-3.0 项目可随意参考所有代码 | Enve 是非商业 source-available；komga-reader 为 AGPL-3.0-or-later | Enve 仅借鉴行为；AGPL 代码不直接进入 GPL 客户端，除非单独完成许可决策 |
| 所有媒体先统一成一个数据图 | 当前最急的是队列恢复、状态一致和大库分页 | 采用可演进投影，禁止播放等待 Work 匹配 |

## 3. 目标架构

```text
Compose / TV / System controls
          │
          ├── Browse facade ───── Room catalog snapshots ── source refreshers
          ├── User state facade ─ Room authoritative state ─ protocol outboxes
          └── Open coordinator ── stable ResourceKey ─────── ephemeral ResourceLease
                                      │
                    ┌─────────────────┼──────────────────┐
                    ▼                 ▼                  ▼
             Video / Live       Continuous Audio    Reading / Comic
             Media3 + mpv       Media3 Service      Readium + render adapters
                    └─────────────────┬──────────────────┘
                                      ▼
                         shared resource scheduler
                 foreground > adjacent > visible art > background
```

统一的是身份、浏览快照、用户动作、资源预算和打开协议；不统一播放器、定位语义或远端同步协议。

### 3.1 三条数据平面

**目录平面**保存可以重新获取的投影：来源条目、父子结构、封面键、类型和刷新时间。UI 只观察 Room，
刷新失败继续显示旧数据，并明确标记“离线/可能过期”。

**用户状态平面**保存不可随意丢弃的动作：收藏、精确进度、书签、笔记、队列、离线意图和来源写回状态。
这部分不能随着目录清缓存删除。

**资源平面**区分稳定引用与临时访问：

```text
ResourceKey(provider/account/item/revision)
        └── resolve just in time
             └── ResourceLease(uri, headers, origin, expiry?, range/seek capability)
```

`ResourceLease` 只驻留内存，不进入 Room、日志、深链、队列或下载任务。302/115 地址失效后重新解析
稳定键，而不是续用或永久缓存最终 CDN URL。

### 3.2 身份和类型

扩展领域语义，但保留旧字符串适配：

```text
ContentKind: MOVIE / SERIES / EPISODE / LIVE_CHANNEL /
             BOOK / COMIC / AUDIOBOOK / MUSIC / PODCAST / FOLDER / UNKNOWN

NodeKind: SEASON / EPISODE / VOLUME / CHAPTER / TRACK / PAGE / DISC

AudioMode: MUSIC / AUDIOBOOK / PODCAST
```

- 文件后缀只说明格式，不能单独判定 `M4A/MP3` 是歌曲、听书还是播客。
- Emby 的 `Audio`、`MusicAlbum`、`MusicArtist`、`Playlist` 使用服务端类型和父子关系判断。
- 文件目录优先读取内嵌标签、NFO、CUE 和目录上下文；无法判断时显示“音频”，让用户选择归类。
- 同一作品的不同剪辑、译本、演播版保持各自 `Rendition` 和进度；作品合并只影响展示。
- 所有本地键至少带 Profile、Provider、Account、Item 与 Rendition/Revision，禁止只按远端 itemId 更新 UI。

### 3.3 来源能力矩阵

用结构化能力替代一个笼统的 `supportsX`：

```text
Capability(kind, scope, operation, precision, constraints)
operation = BROWSE / SEARCH / OPEN / FAVORITE_WRITE / PROGRESS_WRITE /
            PLAYLIST_WRITE / DOWNLOAD / RANGE / SEEK
```

能力按连接、账号、库和内容类型计算。按钮只在当前对象确实具备能力时出现；运行时发现能力变化时，
降级并给出原因，不留下永远报错的操作。

优先顺序：

1. Emby、本地文件、OpenList/WebDAV/SMB、OPDS、Komga、Audiobookshelf。
2. Jellyfin 与 OpenSubsonic：复用既有领域和 UI，以独立 Provider 接入，不假设协议完全相同。
3. Kavita 原生、RSS 播客、Stremio/受控 Runtime：在前两层稳定后按真实需求推进。
4. 不预置盗版源，不在主进程执行 CatVod JAR、QuickJS、Python 或 WebView 嗅探代码。

## 4. 状态与数据库演进

### 4.1 实际 v5 与后续数据库演进

本轮 v5 只新增三张直接服务当前路径的表，保留所有旧表：

| 表 | 关键字段 | 用途 |
| --- | --- | --- |
| `audio_queues` | id/currentEntryId/positionMs/speed/repeatMode/shuffleEnabled | 当前按模式命名的队列头，仅启用 AUDIOBOOK |
| `audio_queue_entries` | entryId/queueId/assetId/resourceRevision/ordinal/shuffleOrdinal | 独立实例、普通顺序与实际随机顺序 |
| `progress_sessions` | assetId/resourceRevision/sessionId/sequence | 事务核对当前消费会话 |

`user_states`、`catalog_entries`、`resource_tasks` 留给对应功能批次的后续增量版本。
个人收录也要独立于目录和资源引用；禁止一次迁移创建所有尚未使用的领域表。

现有 `library_assets`、`progress_records`、视频历史和 Outbox 继续存在。主机 SQLite 脚本已覆盖
v3→v4、v4→v5 的结构与旧表数据保留；Android MigrationTestHelper 和 v1 起的真实升级链仍需补齐。
不使用 destructive migration；不能因迁移失败清空旧记录。

### 4.2 写入语义

- 进度快照可以按同一作用域和 Rendition 合并，但用户回退、重看和重置必须保留，不能取历史最大值。
- Started/Pause/Stopped 等会话事件有顺序含义，不与最新进度快照使用同一种压缩规则。
- 每个写入带单调 `sequence` 和本地 sessionId；协议不支持幂等时承诺 at-least-once，不宣称 exactly-once。
- 文件 revision 改变时，旧进度不得写回新版本。可生成“尝试恢复”提示，但没有 LocatorMapping 不自动套用。
- 删除来源默认保留本地用户数据和未同步状态；删除本地作品只清理该作品作用域，不影响同一远端账号其它队列。

## 5. 音乐与连续音频

### 5.1 一个服务，多种业务语义

继续扩展现有 `AudiobookService`，当前不重命名服务组件；未来确需重命名时单独处理 manifest 和系统恢复入口兼容。

共享：ExoPlayer、MediaSession、音频焦点、耳机拔出、候选解析、账号归属、通知、错误恢复。

分开：

| 规则 | 音乐 | 听书 | 播客 |
| --- | --- | --- | --- |
| 主要导航 | 艺人/专辑/歌曲/歌单 | 书/章节/轨道 | 节目/单集 |
| 默认速度 | 1.0，隐藏倍速 | 记忆每本或 Profile 倍速 | 记忆节目倍速 |
| 完成 | 播放计数阈值/曲末 | 整书与章节完成 | 单集完成 |
| 连续性 | gapless 优先、shuffle/repeat | 精确长时续播、睡眠 | 队列、跳过片头尾 |
| 元数据 | artist/album/disc/track/lyrics | author/narrator/chapter | show/episode/date |

服务持有 Player，UI 只用 `MediaController`。音视频互斥由一个 `PlaybackCoordinator` 管理：开始视频时暂停音频但
保留队列；开始音频时关闭视频/PiP 的发声权；TTS 作为临时音频焦点参与者，不伪装成音乐队列。

### 5.2 队列与系统恢复

队列的身份是 `instanceId`，不是 MediaKey；同一首歌加入两次可以分别移动或删除。每次队列变更和当前位置
变化事务性保存，播放器成功切换后再提交 currentInstance，避免 UI 与 Service 各自维护一份真相。

第一阶段继续使用 `MediaSessionService`。队列可从 Room 恢复后，实现新版三参数
`onPlaybackResumption(session, controller, isForPlayback)`：

- `isForPlayback=false` 时只返回一个带本地 title/artwork 的最近条目，不触网。
- 真正播放时先迅速返回稳定 MediaItem，再按需解析 Lease；恢复 repeat、实际随机顺序、速度与位置。
- 未知、已删除、账号失效的条目跳过并保留可诊断原因；空队列正常结束，不抛 `first()` 异常。

目录快照能够在无网络时快速返回后，再升级为 `MediaLibraryService`，提供最近播放、专辑、艺人、歌单和
收藏浏览树，随后接 Android Auto。车机不是第一首歌可播放的前置条件。

### 5.3 播放候选与音质

- 音频复用现有 Emby PlaybackInfo、OpenList/网络存储解析和跨 origin 凭据策略。
- `LibraryAudioDataSource` 的一次刷新升级为候选链：401/403/404/410 重新解析当前稳定键一次；格式不支持或
  持续失败时换下一个候选，不无限重试。
- 用 `ResolvingDataSource` 或等价的项目适配层做 just-in-time 解析；不得让 UI 持有请求头。
- 音质条显示实际 candidate、codec、sample rate、bit depth、Direct/Transcode；不把源文件标签冒充当前输出。
- gapless、音频卸载、ReplayGain、均衡器和交叉淡化分别做能力检测与 A/B 验收，不用一个“无损”开关包办。

### 5.4 音乐首版页面

当前五入口先不改变。音乐 MVP 将“听书”入口改为“音频”，内部提供“听书 / 音乐”二级切换；来源仍保留为
一级入口，避免一次重排全部导航。经过真机与使用反馈后，再决定是否加入统一首页或把来源移到管理页。

音乐首版必须包含：

- 最近播放、专辑、艺人、歌曲、歌单和收藏；所有大列表从 Room 分页。
- 专辑详情按 disc/track 排序；播放、下一首播放、加入队列、替换队列。
- 迷你播放器、Now Playing、可重排队列、实际随机顺序、repeat mode。
- 内嵌/同目录/服务端静态与同步歌词的来源优先级；没有歌词正常降级。
- 播放诊断和账号/来源标签；本地歌单可以跨来源，写回服务器时只提交该服务器可识别的条目。

## 6. 全媒体共同优化

### 6.1 本地优先浏览

根 Compose 不再订阅全量资产和全量进度后在内存筛选。Repository 以 Room 为单一 UI 真相源：

```text
打开页面 → 立即展示本地快照 → 后台按库刷新 → 事务性 upsert/tombstone → UI 自动更新
```

- 使用 Paging 3 的数据库 PagingSource；远端页通过 mediator/refresh use case 写入快照。
- 搜索维持逐来源增量和独立游标，同时把已索引内容立即返回。
- 资产、用户状态和同步队列分表；来源离线或刷新中断不能推断“远端全部删除”。
- Lazy 列表提供稳定 key 和 contentType；播放位置只在可见组件按帧/定时派生，不让根 Shell 500ms 重组。

### 6.2 缓存与离线

| 状态 | 用户语义 | 清理规则 |
| --- | --- | --- |
| `REMOTE_ONLY` | 需要联网 | 无本地正文 |
| `METADATA_CACHED` | 目录可看，内容未保存 | 可淘汰重建 |
| `PLAYBACK_CACHED` | 临时片段/相邻页 | 按预算 LRU 淘汰 |
| `OFFLINE_PENDING/AVAILABLE/STALE/FAILED` | 用户明确要求离线 | 仅由用户或明确策略删除 |
| `TEMPORARY` | 导入、校验、渲染中间文件 | 任务结束/崩溃恢复时清理 |

视频/音频离线使用 Media3 `DownloadService + DownloadManager`；EPUB/CBZ/图片目录继续使用受控资源任务。
任务只保存稳定资源键和期望 revision，执行或续传时重新解析。续传前验证 ETag/Last-Modified/长度或内容 revision；
不接受 Range 或 revision 改变时丢弃本次临时分片，绝不拼接两个版本。

### 6.3 资源调度

建立进程级 `ResourceScheduler`，只共享预算，不共享客户端和凭据：

```text
P0 当前播放 / 当前阅读页
P1 即将播放的一个条目 / 相邻一页
P2 可见封面、详情和歌词
P3 用户离线任务
P4 后台目录刷新、标签和完整性检查
```

切换页面或账号取消无主任务。省流量、Data Saver、漫游、低电量和低存储分别降级；当前播放永远不能被封面或
整库扫描饿死。

### 6.4 视频 Feed

Media3 官方 `DefaultPreloadManager` 适合一维 Feed，但要用 Builder 同时创建 PreloadManager 和 ExoPlayer，
确保组件共享。建议实验策略：

- 当前项正常播放；下一项仅在用户停稳且资源预算允许时预载约 3 秒；上一项保留 tracks/source；其余返回 null。
- 移动网络/Data Saver 默认只准备 source 或关闭预加载；ISO、直播、转码候选和已知慢冷存储不预载。
- 预加载始终从 canonical Emby/Provider 请求获取，最终 302 Lease 不落盘；403 后回到稳定键刷新。
- 首先在功能开关下与现有单 Player 比较首帧、缓冲、流量、峰值内存和快速滑动取消，再决定默认开启。

视频网络栈暂保留共享 OkHttp，以免破坏已经验证的拦截器和 302 语义。HttpEngine/Cronet 只做独立实验；协议更快
不等于真实 115/OpenList 链路更快。

### 6.5 阅读与漫画

- 先抽出 `ReadingEngineAdapter`，再将 Readium 3.1.2 升级到 3.3.0；固定 EPUB2/3、中文字体、RTL、FXL、
  搜索、选文、标注、横竖屏、TXT 转换和旧 Locator 样本。
- 3.3.0 修复出版物资源字体等 CORS 加载，但发布方明确提醒次版本可能包含小型破坏变更，因此不能直接改版本号。
- 漫画借鉴 source/render 两条稳定 seam：现有 CBZ/Komga 获取与 SSIV/PdfRenderer 呈现分开。长条、双页、RTL
  仍使用原始 page + intra-page offset 定位。
- CBR/MuPDF/面板检测可做可选 RenderAdapter；引入 AGPL/native 组件前单独确认许可证、ABI、体积和崩溃隔离。

### 6.6 M4B 章节

Media3 1.11 已把 Nero/QuickTime 章节作为 track `Metadata` 中的 `Chapter` 暴露。增加统一 `ChapterRef`：

```text
ChapterRef(resourceRevision, trackId, chapterId, title, startMs, endMs?)
```

本地 M4B 直接消费 metadata；ABS 的全书章节转换成 track + track offset。提供章节列表、上一章/下一章、章节剩余
时间和“本章结束后暂停”。读取章节不能阻塞首帧，也不要求下载完整远端文件。

## 7. UI 与交互

- 延续中性深色、低饱和 Emby 蓝、语义化浅色方案；禁止荧光黄绿和黑字叠深色背景。
- 使用项目现有 Material Icons，不混用表情符号或另一套图标库。
- 动画服务于导航层级、播放器展开和焦点反馈；尊重系统减少动画，阅读正文不做持续背景动画。
- 卡片必须显示来源、离线状态和继续位置；未知状态写“未知”，不伪造百分比。
- 手机触控目标至少 48dp、正文对比度目标 4.5:1、200% 字体仍可操作；TV 每个入口有稳定 D-pad 焦点和返回路径。
- 先在现有五入口内完成状态一致性。统一首页和导航重排属于可用性实验，不与数据迁移一起上线。

## 8. 质量、性能与可观测性

### 8.1 自动化层次

1. 纯 Kotlin：类型路由、身份作用域、队列 reducer、实际随机顺序、revision、同步合并和候选策略。
2. Contract kit：每个 Provider 用相同 browse/search/detail/open/cancel/paging/credential 用例；网络 DataSource 做
   Range、重定向、取消和跨 origin 测试。
3. Room：MigrationTestHelper 覆盖每个历史 schema；进程中断、重复迁移、删除与延迟写入竞态。
4. Compose/Service instrumentation：旋转、后台、系统杀进程、通知、蓝牙、耳机、PiP、TV 遥控和空列表。
5. 真实协议矩阵：脱敏样本服务器覆盖 Emby 302、OpenList、SMB、WebDAV、Komga、ABS、OPDS 和直播 EPG。
6. Macrobenchmark + Baseline Profile：启动、首页、封面墙滚动、打开视频/书籍、恢复音频和切换账号。

### 8.2 指标

不为不可控公网设虚假的绝对 SLA。先记录相同设备、网络和样本下的基线，再以发布门禁比较：

- app cold/warm start、首个本地内容、远端目录首屏。
- tap-to-first-frame、PlaybackInfo、首个 302、CDN 响应头、buffer-to-ready 分段耗时。
- Feed 切换首帧、rebuffer 次数、预加载字节、取消浪费字节和峰值 PSS。
- 音频服务恢复、换曲、gap、通知就绪；阅读首章/首图和大图峰值内存。
- 同步队列长度、最老操作年龄、失败分类；不得记录 Token、完整 URL 查询或私有路径。

每个优化需在中位与慢样本都不回退，或有明确功能开关和回滚路径。Baseline Profile 覆盖真实关键旅程，不把
Debug 滚动感受当性能结论。

## 9. 实施批次

每批独立提交、独立开关、独立验收；不把后续表中的项目预先标成完成。

| 批次 | 交付 | 验收门 |
| --- | --- | --- |
| R0 设备基线 | 手机/平板/TV 冒烟矩阵；Emby 302、ISO、直播、阅读、漫画、音频样本；记录性能基线 | 现有能力先有可重复基准，不修改播放策略 |
| R1 状态内核 | v5 最小表、队列 reducer、账号/Revision 键、旧进度只读聚合、真实迁移测试 | 升级不丢 v1-v4 数据；重复曲目、回退进度、换账号不串写 |
| R2 连续音频 MVP | Service 持久队列与系统恢复、MUSIC 类型、Emby 单专辑、本地目录播放、音视频协调 | 后台连续播放、进程重建、通知/耳机可用；不污染听书进度 |
| R3 音乐日用 | 艺人/专辑/歌曲/歌单/收藏、Room 分页、Now Playing、队列编辑、歌词与音质条 | 万级曲库滚动、实际随机顺序、同曲重复入队、弱网候选回退 |
| R4 本地优先与离线 | 目录快照、可用性状态、资源任务、空间/网络约束、离线管理页 | 断网不清库；仅离线不偷跑网络；临时 Lease 不持久化 |
| R5 现有体验增强 | M4B 章节、Readium 3.3 兼容升级、漫画 Render seam、统一继续视图、Feed 预加载实验 | 每项可单独回滚；302/ISO 首帧、Seek、凭据隔离不回退 |
| R6 系统与来源 | MediaLibraryService/Auto、Jellyfin、OpenSubsonic、Kavita/RSS/可选 Runtime 评估 | 复用能力矩阵和状态内核，不复制页面与凭据体系 |
| R7 发布质量 | Baseline Profile、Macrobenchmark、可访问性、依赖/NOTICE、签名发布检查 | Release 构建、升级安装、设备矩阵和许可证清单通过 |

### R1/R2 建议代码落点

```text
core:model       AudioMode / typed kinds / capabilities / queue actions
core:database    v5 entities, migrations, paging queries, user-state projection
core:provider    contract kit, typed facets, account-scoped keys
core:library     resource state/task coordinator, revision-safe progress
core:playback    lease resolver, candidate failure policy, playback coordinator
experience:audio service-owned queue, music/audiobook reducers, chapters
app              thin navigation, music browse UI, unified continue projection
benchmark        critical user journeys and baseline profile
```

避免继续把新业务放入 `MainViewModel`、`MultimediaShell` 或单个大 Composable；它们只保留导航/兼容适配，新增状态
进入对应 experience ViewModel 和 use case。

## 10. 首批端到端验收场景

| 场景 | 必须结果 |
| --- | --- |
| 同一首歌在队列出现两次 | 两个 instance 可分别移动/删除，播放事件不混淆 |
| 第二首播放中杀掉服务/重启设备 | 恢复同一实例、顺序、位置、repeat、实际随机顺序，不先触网绘制通知 |
| 慢解析时暂停、换歌或换账号 | 旧 Lease 不起播，不覆盖当前状态，凭据不跨账号 |
| 音乐 → 视频/PiP → 听书/TTS | 发声权唯一；被暂停会话保留队列、位置和模式设置 |
| 文件 revision 改变且旧写入延迟到达 | 新版本不接受旧 Locator，提示可选恢复 |
| 远端刷新失败或返回空页 | 保留本地目录，不把全库标删除；可以重试 |
| 仅离线模式 | 只使用完整、revision 匹配的副本；不可用时明确提示，不回源 |
| 302 Lease 过期 | 从 canonical 项目重新解析一次并从原位置恢复，不把 Token 发给 CDN |
| Feed 快速连滑 | 只保留小窗口，取消旧预载，ISO/直播不误预载，内存受控 |
| 阅读/漫画切布局或升级引擎 | 恢复相同原始页/Locator；旧书签保留且不被静默重写 |

## 11. 参考项目与采用边界

| 参考 | 借鉴 | 不采用 |
| --- | --- | --- |
| Android Media3 官方文档/1.11 | PreloadManager Builder、播放恢复、MediaLibraryService、Chapter、错误策略 | 不假设默认策略适合 302/ISO |
| Readium Kotlin 3.3 | 阅读资源修复、迁移指南和统一出版物方向 | 不跳过兼容样本直接升级 |
| Next Player | 手势、字幕/音轨、TV 与网络文件交互；GPL-3.0 与本项目兼容 | 不替换 Shadow 的 Resolver/账号/进度 |
| Finamp | 可恢复队列、自动离线、下载状态、缓冲限额、Android Auto 的产品行为 | Flutter 代码不直接搬入原生 Compose 架构 |
| komga-reader | Source 与 Render 两条 seam、离线占位、E-Ink/设备能力隔离 | AGPL 代码不直接并入，早期项目不作为稳定依赖 |
| Enve Book Player | 跨源书库、Rendition、读听关联和明确状态边界 | 非商业 source-available，只研究行为，不复制代码或资产 |
| Streamyfin/Findroid | 下载生命周期、横竖屏竞态、空播放列表等真实失败案例 | 不引入 Expo/Jellyfin 专属架构 |

### 一手资料

- [Media3：DefaultPreloadManager](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager)
- [Media3：后台播放与 playback resumption](https://developer.android.com/media/media3/session/background-playback)
- [Media3：Android Auto / MediaLibraryService](https://developer.android.com/media/implement/surfaces/cars)
- [Media3：网络栈选择](https://developer.android.com/media/media3/exoplayer/network-stacks)
- [Media3：错误策略与 ResolvingDataSource](https://developer.android.com/media/media3/exoplayer/customization)
- [Media3 1.11.0 发布说明](https://github.com/androidx/media/releases/tag/1.11.0)
- [Readium Kotlin 3.3.0 发布说明](https://github.com/readium/kotlin-toolkit/releases/tag/3.3.0)
- [Android：离线优先数据层](https://developer.android.com/topic/architecture/data-layer/offline-first)
- [Android：Compose 列表性能](https://developer.android.com/develop/ui/compose/lists)
- [Android：Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)
- [Next Player](https://github.com/anilbeesetti/nextplayer)
- [Finamp](https://github.com/finamp-app/finamp)
- [komga-reader](https://github.com/Gabriel-Graf/komga-reader)
- [Enve Book Player](https://github.com/opisaac9001/Enve-Book-Player)

## 12. 最终优先级

R1/R2 的听书持久队列、版本化位置和事件驱动 UI 已接入。下一步先完成设备验收并打通音乐单专辑批次；
详细拆分见 [B1–F 验收表](VNEXT_RESEARCH_2026_09_10.md#5-下一批交付与验收)：

```text
设备与性能基线
→ 账号/Revision 安全的状态与持久队列
→ 一张 Emby 专辑 + 一个本地目录的连续播放
→ 完整音乐体验
→ 本地优先与离线
→ M4B / 阅读 / 漫画 / Feed 增强
→ 更多来源与系统入口
```

后续音乐、播客、Jellyfin、OpenSubsonic、离线和车机复用已验证的状态与资源访问能力。
