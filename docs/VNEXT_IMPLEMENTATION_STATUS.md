# vNext 实现与验收账本

对应 `VNEXT_OPTIMIZATION_PLAN.md` 的 R0–R7。状态按接入代码、自动化和真实设备验收分别记录；本文件不把目标设计或测试骨架记为完整交付。

## 已接入并通过本轮主机验证的实现

- R2/R3：MUSIC/PODCAST 类型，显式本地歌曲/目录导入，Emby 音乐库索引与专辑 disc/track 排序；Room 分页歌曲、艺人、专辑、目录、收藏、最近收听；本地跨来源歌单与重复实例编辑。
- R2：同一个 AudiobookService 管理按模式持久化的独立队列；歌曲显式点播从头开始、默认 1x，听书保持原续播语义。系统恢复只读本地元数据。
- R2：进程发声协调接入 Emby 视频、外部视频、ISO、音频和 TTS；旧会话退出不能撤销新会话的发声权。
- R3：有界播放候选链与过期 Lease 单次重解析；换编码后重建媒体源并按时间恢复，避免复用不同表示的字节偏移；播放上报跟随实际候选。
- R3：静态/LRC 歌词、同目录歌词导入，当前解码输入与播放方式诊断；实际收听时长/播放计数与图书完成进度分离。
- R4：阅读/听书书架与本地搜索已改为数据库分页；根 Shell 不再订阅全量资产和进度，位置 ticker 下沉到可见播放组件。
- R5：文件内章节与 ABS 书级章节映射，当前章、前后章节、剩余时间、按媒体位置停止的章节睡眠；新增章节与音乐相关表，数据库 v5→v6 增量迁移。
- R5：Readium 引擎适配层与 3.3.0 升级；漫画 source/render 适配层，保留物理页/页内偏移定位。
- R6：MediaLibraryService 本地浏览树、搜索、系统队列恢复与 Android Auto 入口；只对自身或可信控制器开放。

## 第二批：持久离线与目录刷新

- Room v7 保存稳定资源键、任务代次、预期 revision、进度和网络/充电约束；只解析执行中的临时 Lease，不保存 URL、Cookie 或请求头。
- 音视频显式副本使用 Media3 DownloadService/DownloadManager 和独立 NoOpCacheEvictor 缓存；目前开放可完整保存的渐进式文件，ISO/直播/HLS/DASH 不显示为已支持。
- 图书下载使用 WorkManager 前台任务，完整校验后安装；操作串行、代次检查阻止旧完成回调覆盖暂停、重试或删除。移除副本保留收藏和继续位置。
- 音频及普通视频入口优先使用经过完整性检查的离线副本；独立管理页提供暂停、重试、移除、仅离线、任务网络/充电偏好和过期临时文件清理。
- 仅离线覆盖 OkHttp 新请求与已打开响应、SMB 后续读取、解析、封面及同步。后台任务服从漫游、Data Saver、低电量和低存储约束。
- 音乐目录记录刷新代次，失败、空页和过期响应保留旧目录；只有完整分页校验通过才隐藏本代未出现的条目，收藏和歌单仍保留。
- 进程资源预算接入离线媒体、图书、音乐索引、音频解析和漫画当前页，保留前台请求名额。
- 增加实际 Android MigrationTestHelper，从 v1–v6 每个历史 schema 种入各表数据并验证迁移、外键与重复打开；另有目录快照和任务代次的数据库竞态测试。测试源已编译，设备执行尚未进行。

## 第三批：原生来源、歌词与系统导航

- 新增 Jellyfin/OpenSubsonic 原生 Provider，复用已加密的连接存储、来源管理、音乐索引、队列、离线资源和聚合搜索；账号变更使用新的连接作用域。
- Jellyfin 使用原生路径、MediaBrowser Authorization、用户认证和 PlaybackInfo，提供直放/转码候选。OpenSubsonic 使用 API Key 或每次请求独立 salt/token，支持专辑/歌曲分页、搜索和原始/转码候选。
- 原生 API 请求拒绝跨 origin 重定向；测试验证反向代理子路径、认证头、分页尾页与无需媒体预探测的候选链。
- 歌词优先级接入为内嵌、同目录/本机、服务端。支持 ID3 USLT、毫秒时间戳的 SYLT、Vorbis 标签、静态/LRC、Jellyfin 和 OpenSubsonic songLyrics 扩展；不支持的歌词正常降级。
- 章节控制跨越物理轨道，上一轨在准备好后定位到最后章节；系统媒体库补齐目录 getItem、播客节点、搜索计数与结果一致性、旧 Service action 兼容。
- 修复视频离线入口漏传 OfflineRepository、原生视频历史标识加错前缀，以及一个 Emby 账号失败阻止其他音乐来源显示的问题。

## 第四批：个人书库、继续视图与目录副本

- Room v8 将个人收录/收藏和目录索引分开；刷新整库不再将所有歌曲加入个人书库，默认 profile 兼容旧收藏。移除书架先取消资源任务。
- 统一继续视图只读聚合阅读、音频、旧视频历史和歌曲最近收听；未知总时长不伪造百分比，歌曲不转换为听书完成进度。
- 手工作品/版本关联支持关联、解除及切换版本，各版本继续使用自己的原始 Locator，不自动换算读听位置。
- Jellyfin/OpenSubsonic 歌单显式导出为服务器副本，保留重复曲目和顺序。持久化创建/回读检查点；未知结果禁止自动重复创建，删除后迟到写入不能恢复任务。
- 原生来源页面先显示 Room 元数据快照，失败或异常空刷新保留旧目录，支持仅离线浏览。快照有容量限制，不保存 acquisition、封面临时链接及带凭据的导航 URL。
- v7→v8 主机 SQLite 迁移通过字段、索引、外键及旧数据校验；新增关联独立进度、个人收录和继续视图设备测试源。

## 第五批：账号隔离与 Emby 本地优先

- Emby 身份纳入规范化 origin、反向代理路径、服务器、用户和默认 profile；数据库按唯一账号迁移，保持资产 ID、队列和原始 Locator。历史归属有歧义时保留记录且不自动绑定网络账号。
- Emby 首页、海报墙、详情与 Feed 目录支持持久快照及先显示本地内容。原生与 Emby 快照共用 32 MiB/256 页上限，目录缓存不保存 PlaybackInfo 或临时凭据。
- Room v9 保存按账号隔离的本地收藏动作；跨页面观察同一状态，目录旧值不能覆盖本地选择，后台以操作 ID 确认同步，离线保留待发动作。
- 音乐全库索引拒绝把缓存回退当作成功刷新。账号密文读取失败不再删除原数据或用空账号覆盖。
- Provider 增加目录、播放和用户状态三类结构化能力，聚合搜索及歌单导出根据实际操作能力选入口。
- v8→v9 主机 SQLite 迁移通过；补充缓存空响应/离线/超容量、账号作用域、迟到收藏确认与历史资产迁移用例。

## 仍需继续实现

- R1：历史迁移测试的设备执行；多本地 profile 的 UI 与完整数据隔离仍属后续扩展；当前提供默认 profile 与远端账号隔离。
- R3：音质能力的独立评估。
- R4：将快照覆盖扩展到其他来源页面、可见封面与相邻内容的预算接入；真实设备中断续传/仅离线验证。
- R5：固定阅读样本、PDF 文本重排映射、带默认关闭开关的 Feed 预加载实验。
- R6：原生来源的真实服务器验证；Kavita/RSS/可选运行时按设计完成评估。
- R7：设备 instrumentation、Macrobenchmark/Baseline Profile、可访问性与依赖许可门禁。

## 已有验证证据

- v6→v7 主机 SQLite 迁移通过同等字段、索引、外键及历史样本校验。
- v5→v6 主机 SQLite 实际执行迁移 SQL，与 Room 导出 schema 的字段、索引、外键一致，保留全部旧表样本数据。
- `test lintDebug` 通过：155 个独立测试（Debug 130 + 纯 JVM 25），包括 Release 变体共 285 次执行，0 失败/错误/跳过；Lint 0 错误，94 条模块报告警告（含重复）。
- 未构建/安装 APK，未运行设备或真实服务器验收，未推送。

## 一手接口依据

- [Emby 条目信息](https://dev.emby.media/doc/restapi/Item-Information.html)：音频专辑及 disc/track 元数据。
- [MediaLibraryService 回调](https://developer.android.com/reference/androidx/media3/session/MediaLibraryService.MediaLibrarySession.Callback)：本地媒体树与搜索协议。
- [Readium 3.3.0](https://github.com/readium/kotlin-toolkit/releases/tag/3.3.0) 及 [迁移指南](https://github.com/readium/kotlin-toolkit/blob/3.3.0/docs/migration-guide.md)：引擎升级边界，文件 URL 显式提供 isDirectory。
- [Audiobookshelf API](https://api.audiobookshelf.org/)：全书章节与轨道 startOffset/duration 的坐标关系。

- [Jellyfin 12](https://jellyfin.org/posts/jellyfin-release-12.0/) 与 [原生认证要求](https://github.com/jellyfin/jellyfin/pull/13306)：Authorization/MediaBrowser 与原生路径。
- [OpenSubsonic API](https://opensubsonic.netlify.app/docs/api-reference/) 与 [歌曲歌词](https://opensubsonic.netlify.app/docs/endpoints/getlyricsbysongid/)：独立认证参数与扩展探测。
- [ID3v2.4 帧格式](https://id3.org/id3v2.4.0-frames)：USLT、SYLT 描述符边界和毫秒时间戳。
