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

## 仍需继续实现

- R1：历史迁移测试的设备执行；旧视频/阅读/音频继续状态只读聚合；更完整的账号/profile 作用域演进。
- R3：内嵌与服务端歌词优先级、远程歌单写回与检查点，音质能力的独立评估。
- R4：将快照覆盖扩展到其他来源页面、用户状态与个人收录分离、可见封面与相邻内容的预算接入；真实设备中断续传/仅离线验证。
- R5：固定阅读样本、PDF 文本重排映射、统一继续视图/手工作品关联、带默认关闭开关的 Feed 预加载实验。
- R6：Jellyfin/OpenSubsonic 原生来源与协议契约测试；Kavita/RSS/可选运行时按设计完成评估。
- R7：设备 instrumentation、Macrobenchmark/Baseline Profile、可访问性与依赖许可门禁。

## 已有验证证据

- v6→v7 主机 SQLite 迁移通过同等字段、索引、外键及历史样本校验。
- v5→v6 主机 SQLite 实际执行迁移 SQL，与 Room 导出 schema 的字段、索引、外键一致，保留全部旧表样本数据。
- `test lintDebug` 通过：136 个独立测试（Debug 117 + 纯 JVM 19），包括 Release 变体共 253 次执行，0 失败/错误/跳过；Lint 0 错误，92 条模块报告警告（含重复）。
- 未构建/安装 APK，未运行设备或真实服务器验收，未推送。

## 一手接口依据

- [Emby 条目信息](https://dev.emby.media/doc/restapi/Item-Information.html)：音频专辑及 disc/track 元数据。
- [MediaLibraryService 回调](https://developer.android.com/reference/androidx/media3/session/MediaLibraryService.MediaLibrarySession.Callback)：本地媒体树与搜索协议。
- [Readium 3.3.0](https://github.com/readium/kotlin-toolkit/releases/tag/3.3.0) 及 [迁移指南](https://github.com/readium/kotlin-toolkit/blob/3.3.0/docs/migration-guide.md)：引擎升级边界，文件 URL 显式提供 isDirectory。
- [Audiobookshelf API](https://api.audiobookshelf.org/)：全书章节与轨道 startOffset/duration 的坐标关系。
