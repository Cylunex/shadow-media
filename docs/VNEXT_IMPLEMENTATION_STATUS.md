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

## 第六批：Feed 与阅读增强

- Feed 使用同一 DefaultPreloadManager.Builder 创建预加载管理器及播放器，默认关闭；停稳后仅下一条约 3 秒，保留上一项 tracks，其他移除。跳过慢解析、续播条目、ISO、自适应流和转码，移动网络/设备约束与后台状态降级。
- 预载只持有当前账号的 canonical PlaybackInfo 与内存 MediaSource，沿用凭据隔离和 302 拦截；失效预载回到正常解析，实验关闭可直接回滚原播放路径。
- 功能控制台记录预载/丢弃字节、命中/失败、异步采样峰值 PSS；首帧与缓冲沿用现有播放质量记录，尚无真机性能结论。
- 资源预算移入 network 共享层，接入可见封面、Feed 和漫画当前/相邻页；相邻缓存只拥有未被画面领取的一页，取消或销毁不会删除已领取文件。
- Android 15+ PDF 文字重排作为显式实验入口，保留原页和文本块位置，支持选文、字号和回原页；无文字页明确降级，末页文字读到末尾才完成。
- Emby 元数据请求增加取消关闭连接、取消异常归一及 API 跨 origin 拒绝；Feed 目录限制两千条，避免整库无界展开。
- 新增确定性 EPUB2/3、字体/CJK、RTL、FXL、TXT、CBZ/PDF、旧 Locator 和静音样本，以及 Readium/转换/原页映射设备测试源。测试源已编译，设备执行和实际布局检查仍待进行。

## 第七批：性能工程、发布门禁与最终走查

- 独立 benchmark 测试工程与 release 派生目标，固定一万首音乐、分页 Emby/302 视频、两个账号和阅读样本；测试入口与样本仅进入 benchmark 变体。
- Macrobenchmark 包含冷/暖启动、万曲滚动、视频点播、阅读目录、音频队列和账号切换；BaselineProfileRule 采集工程、真实输出导入工具与正式 profileinstaller 接入。
- 200% 字体、48dp 操作和阅读旋转的设备用例；PDF 重排工具栏支持换行。书架、歌曲与海报卡显示来源、离线状态和已知继续位置，只有可见卡片观察自己的状态。
- 正式 runtime/desugaring 共 168 个实际 artifact 的版本、哈希与 POM 继承许可证已锁定；完整 NOTICE 随包分发并可在功能控制台离线查看。源码及既有 APK 的只读检查工具分别校验依赖、原生哈希、测试内容排除、profile、应用 ID、debuggable 和可信签名身份。
- OPDS 路径、entry 标识和目录 Locator 不再作为明文持久定位；账号绑定的不透明引用指向 Keystore 加密记录。迁移保留资产 ID、原进度和书签，旧不安全临时快照作废；迁移标记避免每次启动重新扫描。
- 首次保存远端副本建立内容哈希时保留同版本原始进度与章节；真正替换版本仍重置进度。歌曲首次导入带入服务端收藏，本机明确选择优先，导入事务避免覆盖并发个人状态。
- 收藏后立即加入首页收藏；缓存首页补齐本机新收藏，刷新优先保留更丰富的服务端元数据。相同 serverId/userId 的不同 origin 不再在 Provider 注册时被合并。
- Emby 长剧集完整分页并检查重复、缺页和上限；内存条目缓存限制 512 项。原生 API 取消关闭 socket 并保留 CancellationException。
- 网络存储和稳定 ID 的声明式 HTTP 目录/详情/搜索增加元数据快照；网络存储目录按 60 项生成可见详情并提供分页。旧目录在失败/异常空响应/仅离线下保留，同键刷新串行避免迟到覆盖。
- 音乐队列使用 Media3 playlist preload，P0/P1 区分当前与下一首，下一首不覆盖当前诊断；取消嵌套资源配额。Feed 监听网络切换并轮询设备约束，拒绝未知时长、自适应容器和慢解析。
- 已完成 [来源与音质评估](SOURCE_AND_AUDIO_EVALUATION.md) 与 [性能/发布执行说明](PERFORMANCE_AND_RELEASE.md)。Kavita 原生、RSS 订阅和可选 Runtime 按正式设计属于评估项，未标作已开放功能。

## 第八批：逻辑复查与推送门禁

- 详情刷新与分页互斥；分页按请求代次结束加载状态，并合并当前详情，避免同步完成或旧请求回调留下错误状态。“加载更多”移至目录末尾。
- Emby、网络存储和原生目录的可选快照读写失败不再掩盖成功的网络结果；取消仍传播。个人状态、凭据和离线副本继续按强一致写入处理。
- 音频同一队列实例串行解析；关闭后拒绝在途/排队解析写回，过期选择回调不再上报。音乐恢复时正确设置 MUSIC 音频属性。
- Feed 实验关闭时不注册网络回调或轮询设备；后台停止轮询，断网不按非计费网络处理，下一项变化会取消旧准备，并清理相同位置的旧预载。
- 网络存储解密失败显示错误且保留其他来源；Emby 解密失败时拒绝单账号移除，避免误清全部密文；真实 AES-GCM 主机回归验证解锁后两账号仍可恢复。
- CI 自动事件只验证源码；Debug APK 改为手动显式选项，并接入 v3→v9 迁移与依赖/NOTICE/原生哈希/样本检查。完整记录见 [2026-09-11 走查](LOGIC_AUDIT_2026_09_11.md)。

## 剩余验收与实现边界

- 代码和主机验证已覆盖本轮 R1–R7 接入范围；R0 设备基线以及 R1/R4/R5/R6 的设备、真实协议服务端验收尚未执行。
- R7 的真实 Baseline Profile 采集、Release APK 构建、签名检查、历史包升级安装和设备性能对比尚未执行。没有生成 profile 文件，也没有编造设备通过或性能提升结论。
- 多个本地 profile 的 UI 属于后续扩展；当前是默认本地 profile + 远端账号隔离。渐进式音视频与出版物支持明确离线副本，ISO/直播/HLS/DASH 不冒充支持离线。
- Kavita 通过现有 OPDS 浏览/获取，未提供原生进度写回；RSS/音效/可选执行运行时的采用条件见评估文档，不在 UI 中开放未实现操作。
- 工作区要求 APK 构建与部署由用户明确提出；代码提交推送仅包含源码验证，不自动构建或安装 APK。

## 已有验证证据

- v6→v7 主机 SQLite 迁移通过同等字段、索引、外键及历史样本校验。
- v5→v6 主机 SQLite 实际执行迁移 SQL，与 Room 导出 schema 的字段、索引、外键一致，保留全部旧表样本数据。
- `test lintDebug` 通过：178 个独立测试（Debug 151 + 纯 JVM 27），包括 Release 与 benchmark 单测变体共 343 次执行，0 失败/错误/跳过；Lint 0 错误，93 条模块报告警告（含重复）。
- 未构建/安装 APK，未运行设备或真实服务器验收。2026-09-11 只读查询 ADB：已连接设备为 0。

## 一手接口依据

- [Emby 条目信息](https://dev.emby.media/doc/restapi/Item-Information.html)：音频专辑及 disc/track 元数据。
- [MediaLibraryService 回调](https://developer.android.com/reference/androidx/media3/session/MediaLibraryService.MediaLibrarySession.Callback)：本地媒体树与搜索协议。
- [Readium 3.3.0](https://github.com/readium/kotlin-toolkit/releases/tag/3.3.0) 及 [迁移指南](https://github.com/readium/kotlin-toolkit/blob/3.3.0/docs/migration-guide.md)：引擎升级边界，文件 URL 显式提供 isDirectory。
- [Audiobookshelf API](https://api.audiobookshelf.org/)：全书章节与轨道 startOffset/duration 的坐标关系。

- [Jellyfin 12](https://jellyfin.org/posts/jellyfin-release-12.0/) 与 [原生认证要求](https://github.com/jellyfin/jellyfin/pull/13306)：Authorization/MediaBrowser 与原生路径。
- [OpenSubsonic API](https://opensubsonic.netlify.app/docs/api-reference/) 与 [歌曲歌词](https://opensubsonic.netlify.app/docs/endpoints/getlyricsbysongid/)：独立认证参数与扩展探测。
- [ID3v2.4 帧格式](https://id3.org/id3v2.4.0-frames)：USLT、SYLT 描述符边界和毫秒时间戳。
