# Shadow Media 看、读、听一体化设计与重构提案

状态：待确认，不代表已经实现，也不授权部署或构建 APK。

核查日期：2026-09-02。代码基线：`ad8d6c5`，应用 `1.1.1 (20)`。

输入：用户提供的三份全媒介方案、当前源码与文档、本文链接的上游一手资料。

## 1. 结论与本轮决策

将 Shadow Media 升级为「私有库优先、用户自带源、看影视 / 看直播 / 读小说 / 看漫画 / 听书」的一体化客户端，采用增量重构，不替换现有播放底座。

统一的是连接、目录、身份、搜索、收藏、进度存储和管理入口；不统一成一个播放器，也不强求所有内容使用同一种封面、时间轴或续播规则。

建议锁定：

- 产品：一个内容中心，五种专用消费体验；无 Emby 账号也可正常进入首页。
- 影视：保留 Media3、libmpv、Emby 302、STRM、NFO 和当前恢复策略。
- 直播：复用媒体播放基础设施，独立频道、节目单、回看和线路状态。
- 小说：优先验证 Readium EPUB，TXT 转换为受控的内部 EPUB；不另写完整排版内核。
- 漫画：独立漫画阅读器，复用成熟图像加载/分块解码组件，支持 CBZ、图片目录和 Komga。
- 听书：Media3 后台音频会话，先做 M4B/MP3，再接 Audiobookshelf（下文简称 ABS）及 Emby 音频。
- 扩展：先接原生协议；书源、图源、影视仓脚本通过可选服务端适配，不以完整扩展兼容作为首版前提。
- 工程：先建立 `OpenPlan + ProgressLocator + Legacy Adapter` 的可运行闭环，再逐个迁移；不先建几十个空模块。

本文提出首个全覆盖版本暂称 **2.0**，不是本轮修改版本号。音乐、播客、OCR、翻译、读听对齐、第三方脚本兼容继续保留为后续扩展，不作为五类内容交付的阻塞项。

## 2. 代码核查：真实起点

本轮为静态代码/文档核查，没有重跑设备播放、部署服务或执行数据库迁移。已有代码不等于所有设备已验证。

| 位置 | 实际现状 | 对改造的影响 |
| --- | --- | --- |
| [settings.gradle.kts](../settings.gradle.kts) | app + model/provider/database/network/playback，共六个模块 | 先在原模块增加契约，按实际依赖再拆分 |
| [Models.kt](../core/model/src/main/kotlin/top/cylunex/shadowmedia/model/Models.kt) | `MediaKey` 是 provider/item 二元组；条目类型为字符串；进度为毫秒 | 新增类型化领域模型，旧序列化标识不能直接替换 |
| [MediaProvider.kt](../core/provider/src/main/kotlin/top/cylunex/shadowmedia/provider/MediaProvider.kt) | home/browse/search/detail/resolve；resolve 只返回播放候选 | 引入可返回五类计划的 OpenProvider |
| [UnifiedProviders.kt](../app/src/main/kotlin/top/cylunex/shadowmedia/UnifiedProviders.kt) | Emby/直播适配器仍在 app；Emby 默认查询视频类；部分详情依赖内存缓存 | 补冷启动详情回源，音频需新增查询/映射，不是直接放开 Tab |
| [MainViewModel.kt](../app/src/main/kotlin/top/cylunex/shadowmedia/MainViewModel.kt) | 1,626 行；`syncProviders()` 只注册当前 Emby 会话 | 多登录切换不等于多 Emby 同时聚合，需新增连接/账号级注册 |
| [ShadowMediaRoot.kt](../app/src/main/kotlin/top/cylunex/shadowmedia/ShadowMediaRoot.kt) | 1,744 行；顶层 Screen 与大 UiState 集中控制 | 拆屏幕状态与导航，不把阅读状态再放进 MainUiState |
| [AggregateSearchEngine.kt](../core/provider/src/main/kotlin/top/cylunex/shadowmedia/provider/AggregateSearchEngine.kt) | 并发且失败隔离，但等全部任务返回；没有独立超时和结果增量输出；丢弃各来源下一页游标 | 五类聚合前补渐进结果、取消、超时、来源游标 |
| [NetworkStorageRepository.kt](../core/network/src/main/kotlin/top/cylunex/shadowmedia/network/NetworkStorageRepository.kt) | 已有 SMB 随机读与 HTTP 存储；文件筛选/投影围绕 Video/Strm；目录搜索有扫描预算 | 复用传输，不把增加扩展名误当成阅读支持 |
| [ShadowMediaDatabase.kt](../core/database/src/main/kotlin/top/cylunex/shadowmedia/database/ShadowMediaDatabase.kt) | Room schema v3，有 v1→v2→v3 迁移；历史、收藏、时刻、片段等已持久化 | 增量迁移保留原记录，不能 destructive migration |
| [PersistentPlaybackOutbox.kt](../core/network/src/main/kotlin/top/cylunex/shadowmedia/network/PersistentPlaybackOutbox.kt) | Outbox 实为 SharedPreferences JSON，最多 500 条；按 server/user 匹配 | 通用 Outbox 需要跨存储导入及单写者切换，不能只改 Room 表 |
| [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) | 没有后台媒体 Service/前台媒体服务权限 | MediaSession 已有不代表听书后台链路已有 |
| [Handoff.kt](../app/src/main/kotlin/top/cylunex/shadowmedia/Handoff.kt) | `shadowmedia://play` 携带 provider/item/position | 这是接力链接，不是进度同步服务；本地连接 ID 也不天然跨设备一致 |
| [ShadowMediaTheme.kt](../app/src/main/kotlin/top/cylunex/shadowmedia/ui/theme/ShadowMediaTheme.kt) | 已有 Material Icons 和深绿主题；定义了浅色方案但实际固定使用深色方案 | 延续现有图标体系；真正实现主题切换，不重新引入荧光黄绿 |

### 2.1 原样保留的资产

Emby API/302 origin 隔离、当前 DirectStreamUrl 优先策略、光盘随机读、STRM/NFO、直播导入、FeedSession、进度补报意图、诊断脱敏、现有用户的收藏和删除确认，都保留为兼容性基线。

特别禁止为了「统一资源」在视频起播前新增 HEAD 探测、完整下载、作品匹配等待、全库扫描或 Runtime 中转。优先沿用当前已优化的直达播放路径。

## 3. 对附件方案的关键修正

1. **统一作品不等于统一进度。** 同一电影的不同剪辑、一本书的不同译本/删节版/演播版都可能拥有不同定位坐标。仅标题、TMDB、ISBN 或总时长相似不能证明可无损续接。
2. **不要让 Work 取代来源键。** Work 可先临时建立，跨源合并异步进行；播放/阅读不等待身份解析。不能为实现全媒介先建一套庞大的知识图谱。
3. **SourceProjection 与 Rendition 不是固定一对一。** 一个 Emby 条目有多个 MediaSource；一个 ABS 条目可能同时含书和音频。用绑定关系表达，不按固定树结构强塞。
4. **本地进度不等于跨设备同步。** WebDAV/SMB/OpenList 首版只保证本机续读；Komga/ABS 原生同步的精度按能力声明；通用跨设备同步单独做。
5. **Readium 不是全格式成品阅读器。** 官方仍标记 Web Publication/CBZ 部分实现、Media Overlays 规划中；PDF 还需引入适配器。TXT 转内部 EPUB 是本项目方案，不是上游已有的开箱功能。[Readium 能力表](https://github.com/readium/kotlin-toolkit)
6. **普通独立进程不等于安全沙箱。** Android `android:process` 只决定进程归属，`isolatedProcess` 是另一套权限受限机制；不能把 `:runtime` 当作任意脚本隔离的充分条件。[Android Service 属性](https://developer.android.com/guide/topics/manifest/service-element)
7. **频道名不能直接成为跨源合并键。** 同名台可能地区、时区、清晰度、节目编排不同；优先使用来源命名空间内的 tvg-id、节目单映射和用户确认。
8. **统一 Outbox 不表示统一所有远端协议。** 会话事件与最新进度快照应分别处理；不能给不支持幂等的服务端承诺 exactly-once。
9. **“已完成”不是锁死位置。** 重看、重读、向前回跳、重置都是合法动作；保留用户明确操作，不能采用最大百分比或相近时间取更后位置。
10. **默认不做刮削，不等于拒绝读取元数据。** 读取 NFO/ComicInfo/EPUB OPF/音频标签属于本地投影；不下载网络刮削信息、不改写用户文件。

## 4. 产品信息架构与视觉方向

### 4.1 导航

手机保持五个一级入口：**首页 / 影视 / 直播 / 书库 / 我的**。书库内为小说、漫画、听书三个持久子页；支持将常用子页设为默认入口，不增加第六个底栏按钮。

- 首页：继续看/读/听、正在直播、新集/新章、最近加入、收藏空间。
- 影视：电影/剧集、封面墙、筛选、Feed。Feed 不强行推广到正文阅读。
- 直播：频道列表、现在/下一节目、时间轴 EPG、收藏和回看。
- 书库：按媒介呈现阅读中、待读、连载更新、已保存离线、系列。
- 我的：来源与账号、收藏/追更、历史、书签笔记、下载缓存、设置、诊断。
- 全局搜索在顶栏；音频迷你播放器在底栏上方。来源是低频管理入口，但空页面必须提供明显的“添加来源”和“打开本地文件”。

首次启动不再要求先登录 Emby：可添加服务器、导入订阅、选择本地文件，或者直接查看空书架。

### 4.2 页面规则

| 页面 | 主要内容 | 必须能看见的状态 |
| --- | --- | --- |
| 首页继续行 | 紧凑卡片，显示媒介、来源及精确位置 | 本地可用、服务离线、同步待处理，不用假进度条 |
| 作品详情 | 公共标题/封面/简介 + 专用季集/章节/音轨区域 | “继续”对应哪个版本；切版本可能不能迁移位置 |
| 版本与来源 | 同内容的来源、文件/译本/演播版 | 来源授权、格式能力、同步精度、上次使用 |
| 相关作品 | 改编、续作、其他语言版等 | 与“线路切换”明确分区 |
| 搜索 | 按影视/小说/漫画/听书/频道分组；逐源到达 | 已返回来源数、仍在搜索、单源重试、继续加载 |
| 来源中心 | 一个连接可创建多个类型媒体库 | 连接账号、目录权限、浏览/打开/同步各能力的实际结果 |
| 阅读器 | 正文或图像占满画面，点击唤出控制 | 章节/页码/目录，退出后可恢复 |
| 听书 | 封面、演播者、章节、剩余时长、大触控按钮 | 当前文件/章节、睡眠定时、倍速和同步状态 |

没有可靠跨源身份的搜索结果先按来源显示；只折叠已确认匹配，不为了“干净”隐藏同名作品。

### 4.3 视觉与可访问性

- 沿用现有 Material Icons，不混入另一套 Lucide，也不用表情符号作为界面图标。
- 深色中性底、低饱和绿色强调；绿色只用于主操作、选中和进度，不把所有图标染绿。
- 标题/正文使用 `onSurface/onBackground` 等语义 token；海报文字加固定可读遮罩，不依赖封面取色。
- 真正提供跟随系统、浅色、深色；阅读器的纸色/夜间主题独立记忆。
- 影视用海报，书籍/漫画保持原封面比例，听书优先方形/原封面，直播用频道标识。占位图不拉伸。
- 动效用于选中、页面展开和播放器收拢；阅读正文无持续背景动画。遵循减少动画设置。
- 将常规正文 4.5:1、重要控件 48dp、字体放大到 200% 可用设为本项目设计验收目标，不以“获奖网站风格”替代可读性。
- 平板采用导航栏/双栏和漫画双页；TV 优先影视、直播、听书，D-pad 焦点不能丢失。小说/漫画至少保留书架、详情和手机接力，TV 全功能阅读另排。

## 5. 领域模型：统一身份、分别定位

### 5.1 必需概念

| 模型 | 定义与约束 |
| --- | --- |
| Connection / AccountBinding | 连接实例与远端用户分别标识；地址可改，内部 ID 不因此改变；本地 Profile 不等于服务器账号 |
| LibraryProfile | 同一连接下的根目录/服务端库、内容类型、索引策略、元数据策略 |
| ProjectionKey | 连接实例 + 远端账号作用域 + itemId；兼容当前 MediaKey，不能拼接后再随意按冒号拆分 |
| Work | 用于展示、收藏归组的稳定本地作品身份，可先一来源一 Work，之后合并/拆分 |
| Rendition | 某种内容版本：译本、剪辑、演播版等；包含独立的定位域标识 |
| ProjectionRendition | 来源条目与版本的绑定；一个来源条目可绑定多个版本/体验 |
| ContentNode | 季/集/卷/章/音轨的稳定结构；必须有父节点和来源映射，不能用易变的排序序号当 ID |
| ResourceKey / ResourceRevision | 稳定资源引用及修订指纹；与本次解析出的短期 URL 分离 |

`ContentKind` 用于目录/来源内容分类，`ExperienceKind` 用于打开路径，`Format` 用于文件解析，三者分开。未知远端类型保留原始类型并映射为 UNKNOWN，不能静默当视频。

书籍 Work 可关联文本和人声演播 Rendition，分别出现在小说/听书入口；漫画改编、影视改编通常为另外的 Work。TTS 是文本 Rendition 的朗读方式，不生成一部新的有声书。即使同属一个 Work，各版本仍保留独立进度。

### 5.2 身份匹配

- 外部 ID 必须带命名空间、实体类型、语言/版本上下文；相同 ID 至多证明该层实体匹配。
- 校验过的文件指纹可证明相同文件；相同 ISBN 也不能保证两个文件的章节/页数完全一致。
- 标题、作者、年份等模糊匹配只生成候选，允许确认、拒绝、合并后拆分。
- 合并只影响展示和收藏归组；默认不移动、删除或复制原始进度。
- 使用 `LocatorMapping` 明确记录版本间定位映射及来源；没有映射就提示从头开始或手动选章。
- 文件改名、来源重建时先生成重关联候选；网络路径相同但内容改变必须产生新 revision，不能误套旧坐标。

元数据按字段保留来源：用户本地修正优先，随后服务端/NFO/嵌入标签按库策略选择，文件名最后兜底。这里的修正只覆盖本机显示，不写 NFO/ComicInfo。

## 6. Provider 与打开流程

### 6.1 能力拆分

保留现有 MediaProvider 作为 legacy；新增类型化 Catalog/Search/Detail/Open 基础契约，进度、收藏、节目单、追更、离线作为独立 facet。

能力不能只是一组全局布尔值：需声明内容类型、账号/库权限，以及同步精度（仅完成状态、页码、章节、精确 Locator）。运行时检测与静态声明不一致时显示不可用，不能显示一个永远报错的按钮。

元数据发现服务（例如想看/追踪）与真实内容 Provider 分离；搜索得到作品不代表能播放。全局检索只发送给当前 Profile 明确启用并加入范围的来源。

### 6.2 OpenPlan 与资源生命周期

契约草图，非可直接复制的 SDK 代码：

```text
OpenRequest(projection, rendition?, node?, resumeLocator?, intent)
    → OpenCoordinator
    → OpenProvider.open()
    → OpenPlan.Video | Live | Text | Comic | Audio | External
    → ExperienceRouter
    → 专用 Engine / UI
```

- Video：继续包裹现有 PlaybackCandidate，不先改候选优先级。
- Live：带频道、LIVE_EDGE/CATCH_UP 意图、节目时间窗及线路；Legacy Adapter 必须按类型路由，不能一律返回 Video。
- Text：publication 资源/格式、版本与初始 Locator。
- Comic：章节/分页页清单，按窗口加载 PageRef，不能把几万页 URL 全塞进 OpenPlan。
- Audio：轨道目录、章节映射、当前轨道与位置；远端 Session 可选。
- External：只允许受控 app/scheme，显式提示无法保证外部进度返回。

持久化的是 ResourceKey、来源映射、revision 和位置。网络层临时生成 ResourceLease（URI、有效期、读取能力及私有凭据绑定），读取失败才按预算重新获取；Lease 不进入 Room、日志、深链或收藏。

Runtime 返回值不得指定客户端其它来源的 `credentialScopeId`。凭据作用域必须由客户端根据本次调用的 Provider 绑定，逐跳检查 scheme/host/port；资源客户端、图片缓存与阅读器子资源同样遵守。

导入页面做轻量格式/权限验证；实际打开时检查资源 revision。完整图书校验、身份关联、目录预取不能阻塞无关的视频首帧。

### 6.3 搜索和多服务器

先把“保存多个 Emby 登录”扩展成“本 Profile 启用的连接/账号集合”；增加可见库选择、独立生命周期及并发预算。同服务两个用户的目录和历史不得混合。

搜索输出 `Flow<SearchSnapshot>`：携带 query generation、各来源结果/错误/游标；新查询取消旧查询，协程取消不当普通错误吞掉。每源独立超时，按来源续页，去重后仍允许加载更多。

网络目录搜索无法保证任意规模即时全库完成：默认已索引内容 + 当前目录，递归扫描为可暂停任务，显示覆盖范围与游标。用户的“不限制条数”落实为可持续分页，不是一次无限加载进内存。

## 7. 进度、音频会话与同步

### 7.1 Locator

权威进度键至少绑定：本地 Profile、账号作用域、Projection、Rendition/定位域、Node、resource revision。`normalizedProgress` 只是可为空的展示摘要。

| Locator | 权威信息 |
| --- | --- |
| Time | 轨道/片段 ID + 轨道内毫秒；全书累计时间可派生；未知时长允许为空 |
| Text | resource href、局部定位、文本锚点及 Readium locations/text 扩展数据；保留版本化原始定位载荷 |
| Page | 章节、稳定页标识/索引、页内归一化偏移、资源版本；双页变化后仍按逻辑页恢复 |
| Live | 频道、LIVE_EDGE/CATCH_UP、节目 UTC 时间窗与播放位置；回看过期时提示返回直播 |

Readium Locator 包含资源位置、locations 和文本上下文；实现时应无损保存其定位载荷，而非只取一个百分比。[Readium Locator](https://readium.org/architecture/models/locators/)

PDF/固定版面出版物按文档页坐标恢复，不套用重排文本位置。TTS 按文本锚点续读；听书按真实轨道时间续听。文本与人声音频只有具备对齐映射后才显示“从这里切换读/听”。

### 7.2 会话所有权

统一 `ConsumptionSession` 只负责身份、打开/关闭、进度事件和诊断；不强迫所有引擎继承一个巨大 Player 接口。

- Audio/TTS 与 Video/Live/mpv 之间只有一个音频焦点所有者；用户启动视频时暂停听书/TTS并保存位置。
- 听书时允许看书/漫画，纯阅读不会抢音频焦点；自动播放 Feed 不得偷偷打断正在听书，需明确用户动作。
- 离开视频页沿用现有暂停/PiP 策略；离开听书页继续播放。
- Activity 重建只重连 Controller，不能新建第二个音频播放器；服务重建按稳定资源键重新解析，不重用过期 URL。
- 统一会话事件由一个写入协调者接收，Legacy 与新 UI 不能对同一播放事件重复上报。

后台听书采用 MediaSessionService 持有 Player/MediaSession，UI 使用 MediaController；补前台媒体服务声明、控制器授权和生命周期。Android Auto 内容浏览若后续需要，再升级为 MediaLibraryService 的内容树，不宣称仅加 Service 就已适配车机。[官方后台播放指南](https://developer.android.com/media/media3/session/background-playback)

### 7.3 SyncOutbox

本地写入进度与待同步记录在同一个 Room 事务完成；网络发送在事务外，不能持有全局锁等待某台服务器。

区分两类：

- 状态快照：最新位置、收藏设置；同一目标可以合并待发送项。
- 有序事件：会话开始/结束、明确重置、删除标注；保留依赖和事件 ID，不能被普通进度压缩吞掉。

按连接/账号分队列、限制并发、指数退避并加入抖动；认证失败暂停该来源，显示重新登录；删除连接后取消任务并保留用户可选择的本地历史。未知版本载荷保留为待处理，不清空整个队列。

服务端 revision/ETag 可用时做版本协调；不可用时保留上次拉取基线、当前本地会话序号及远端快照。无法判定的跨设备冲突保留两份并让用户选，不能靠手机时间或最大位置武断覆盖。显式重置具有独立事件语义。

网络采用 at-least-once 尝试，本地防重；仅在 Provider 支持时使用其幂等键。对于失效的旧远端会话，按 Provider 规则提交最终进度，不把很久以前的 start 重播成“当前正在播放”。

### 7.4 同步能力边界

- Emby：维持原生播放状态回写。
- ABS/Komga：接其原生接口；本地保留更精确 Locator，远端只同步它支持的字段。
- OPDS：默认只有目录与获取；不能假定有进度/高亮协议。
- 本地/OpenList/WebDAV/SMB：2.0 保证本机续读与用户状态导出；跨设备共享另增可选 WebDAV 状态同步或独立 Sync 服务，不能直接复制 Room 文件。
- 接力链接：仅稳定 ID/不含正文的最小 Locator；接收端解析自己的账号/库映射，缺失时要求选择连接。文本摘录、高亮、凭据和媒体直链不进 URL。

## 8. 五类内容的可交付范围

| 类别 | 2.0 范围 | 随后增强/限制 |
| --- | --- | --- |
| 影视 | 当前 Emby、网络库、NFO/STRM、普通视频与 ISO；原续播/Feed/删除/诊断无回退 | PlayerPool、外挂字幕等原路线图继续独立排期，不与领域迁移捆绑 |
| 直播 | 现有 M3U/TXT/XMLTV、频道收藏、EPG、手动/有上限自动换线、可用回看 | 预约通知、时移、Xtream/Stalker 取决于真实来源能力，不假造回看 |
| 小说 | 本地/网络 EPUB、TXT；目录、搜索、重排、字号行距、主题、书签/高亮、本地准确续读、基础 TTS | PDF 基础页阅读作为验证后可选；DRM、MOBI/AZW、完整网页书源不列入首版承诺 |
| 漫画 | CBZ/ZIP、图片目录、Komga；LTR/RTL、长条、双页、目录、书签、缓存与续读 | CBR、OCR、翻译、智能分镜后置；PDF 漫画复用 PDF 模块，不再另写 PDF 内核 |
| 听书 | M4B/MP3 合集、ABS、Emby 音频映射；后台/锁屏/蓝牙、轨道/章节、倍速、睡眠定时、书签 | 不承诺所有编码可解；章节标签不支持时显示文件级目录，不能伪造章节 |

### 8.1 文本实现策略

Readium 先做独立技术验证：锁定 Maven 发布版本、检查 Kotlin/Media3 依赖冲突、min/compile SDK、desugaring、包体增量与进程恢复。当前项目固定 Kotlin 2.2.21/Media3 1.11.0，不盲跟 develop，也不把整个工具链升级夹进模型重构。

现阶段优先成熟 Navigator 适配到 Compose 容器；新的 Compose Web Navigator 官方仍注明实验性，验证后再替换。不要为了“全 Compose”牺牲阅读稳定性。[Readium Web Navigators](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/web-navigators.md)

TXT 采用有界后台转换：BOM/编码检测（支持用户覆盖）→章节识别→稳定分块/锚点→内部 EPUB 缓存。无章节时按段落分块；缓存键包含文件 revision、编码和转换器版本；超大文本不在主线程一次读入。不回写用户原文件。

阅读器脚本边界：允许经过审查的引擎自身渲染逻辑；禁止不受信书籍脚本、任意网络外链和 Android JS bridge。不能简单地“禁用全部 WebView JS”后假设上游渲染器仍正常。出版物资源通过受控 fetcher 获取。

PDF 需要额外第三方渲染适配，先验证维护状态、ABI/页大小兼容、许可证与崩溃处理；未通过前不给“全文检索/高亮/TTS 全支持”承诺。[Readium PDF 指南](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/pdf.md)

TTS 默认选择明确不需联网的系统 Voice；Android TTS 服务本身不保证所有音色都离线。联网音色需用户开启并提示正文传输；Reader 负责文本定位，音频会话协调者负责焦点/后台控制。[Android Voice API](https://developer.android.com/reference/android/speech/tts/Voice)、[Readium TTS 指南](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/tts.md)

### 8.2 漫画与远端归档

- PageManifest 按章节/窗口加载；图片目录做自然排序并排除封面/缩略图，ComicInfo 可提供顺序和元数据。
- 解码按像素/字节预算，不只按“前后四页”；超长图采用分块，切章取消预取并释放位图。
- 远端 ZIP/EPUB 需要随机访问目录和条目；Range 存在不等于能廉价按页读取压缩数据。
- 第一阶段用受限本地缓存打开归档；下载前显示大小、网络类型、空间需求与可取消进度。Komga 页面 API 优先按页取，不下载整库。
- 后续才做通过一致性测试的 RemoteArchiveReader：精确 Content-Range、长度/revision 校验、签名刷新与重试预算，不能把两版文件的区块拼接。
- ZIP 路径穿越、符号链接、过多条目、总展开字节、压缩比、单图尺寸、XML 外部实体必须防护；坏包不能拖垮其它来源。

### 8.3 音频与直播细节

多 MP3 书籍先按用户目录/标签形成轨道清单，稳定 trackId 与“全书时间→轨道时间”映射分开；未知时长逐步探测，不能先扫完整部书才开始听。M4B 章节可能有多种标签布局，需真实样本验证并提供文件级降级。

睡眠定时支持真实经过时长和“本章结束”，不被倍速混淆；设置按书保存。播放媒体时间与收听统计时长分开记，避免跳进度算成已听。

频道与线路分开：用户固定线路失败后是否允许自动切换是明确设置；自动最多尝试有限条未尝试线路。直播回 live edge，回看按节目时间恢复；节目映射不一致时不静默切台。EPG 的 UTC 时间、显示时区与源偏移单独存，节目结束不代表直播频道“已看完”。

## 9. 来源矩阵与接入顺序

下表为目标，不是现状功能表。

| 来源 | 2.0 主要用途 | 同步/注意事项 |
| --- | --- | --- |
| Emby | 影视、听书 | 多连接/账号启用集合；Audio/AudioBook 映射须按服务版本与真实返回值验证 |
| Local SAF | EPUB/TXT/CBZ/图片/M4B/MP3 | 持久 URI 授权、移动/撤权恢复；不得获取全盘权限来代替选文件 |
| OpenList/WebDAV/SMB | 用户自刮削影视和阅读/音频目录 | 读取元数据，本地进度；一连接多库，不改写文件 |
| M3U/TXT + XMLTV | 直播、后续广播 | 节目单与列表解耦；回看按源能力 |
| OPDS 1.2/2.0 | 通用图书目录和获取 | 处理相对链接、分页、获取关系与认证；无标准进度保证 |
| Komga 原生 API | 漫画、系列、页面和进度 | 不要求所有用户安装；已有服务就直接连接 |
| Audiobookshelf 原生 API | 听书、章节、服务端会话与进度 | 锁定服务版本及录制响应契约，不能只照旧 API 示例写 |
| 声明式 HTTP CMS | 保留现有影视来源 | 静态规则限制不因新媒介放宽 |

推荐接入次序：现有来源兼容 → 本地/存储文件 → ABS → OPDS → Komga。Kavita 原生增强、Suwayomi、Stremio、Storyteller 后续扩展；已有服务选择优先，不要求部署整套服务器。

Komga 文档提供 OPDS v1/v2，但不同客户端的流式读取和进度同步能力不同，不能只做 OPDS 就宣称原生同步完成。[Komga OPDS](https://komga.org/docs/guides/opds/)

ABS 官方 API 页面明确声明文档已过时且不再维护。实现必须核对目标发行版源码/响应，测试会话失效、换文件和离线进度回传。[ABS API](https://api.audiobookshelf.org/)

## 10. 缓存、离线、更新与删除

- Cache：为打开内容产生的可清理缓存；活动资源暂时 pin，磁盘 LRU 和用户设置预算。
- Offline Copy：用户明确保存的副本，任务清单、配额、完整性校验、失败重试、Wi-Fi/计费网络策略独立管理；清缓存不删除离线副本。
- 2.0 支持用户自有 EPUB/TXT/CBZ/音频副本及 Komga 按章保存；影视整库下载和第三方解析源批量抓取不在范围内。
- 本地目录库允许显式扫描建立索引；远端媒体服务器只缓存用过、收藏、在读和近期分页，不默认复制整个服务端目录。
- 追更仅轮询用户关注内容；按游标/revision 去重，批量通知。后台周期任务不承诺准时；直播提醒另按平台允许的调度能力实现。
- “移出书架”“删除离线副本”“删除源文件”是三个不同操作。保留现有 Emby 删除确认；新增来源默认只读，不因为统一详情出现删除按钮就扩展远端删除权。
- 断开连接需选择保留/清除本地用户状态；待同步数据必须显示处理后果，不自动投递给后来加入的另一账号。
- 无来源/来源离线/权限失效/格式不支持/同步冲突均有独立空态，不统一提示“没有可播放媒体源”。

## 11. 安全与可选扩展 Runtime

原生 Provider 表示集成代码受审查，不表示返回的 EPUB、图片或服务器内容天然可信。每个入口仍做尺寸、类型、跳转、路径和权限校验。

脚本兼容建议分级：

1. 原生协议直接调用。
2. 声明式无脚本规则：限制域名、请求数、递归、响应大小；正则也需有复杂度/超时约束。
3. 已有远端服务：优先接 Suwayomi 等 HTTP API，不先自研一个兼容所有扩展的 VM。
4. 无现成服务的 CatVod/书源兼容，才评估独立 Source Runtime；每类兼容器单独验收版本和平台依赖。

Suwayomi 提供扩展在服务端运行、供客户端消费的路径；这里只借其外置执行思路，不据此承诺任意 Android 扩展在普通 NAS JVM 都可运行。[Suwayomi](https://github.com/Suwayomi/Suwayomi-Server)

自建 Runtime 需插件级身份、执行限额和网络出口控制，禁止共享客户端账号库、宿主秘密、Docker socket或高权限目录。容器本身不是任意代码安全的证明；默认最小权限，SSRF/DNS 重绑定/重定向逐跳约束，私有 NAS 地址仅通过明确授予的资源允许列表开放。

HTTP 协议版本化且能协商能力：manifest/catalog/search/detail/nodes/open；文本正文和漫画页也作为受控资源返回。可借 Stremio manifest/resource 的分层，不把内部 Kotlin OpenPlan 直接暴露为无版本 RPC。[Stremio API](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/README.md)

协议导入成功、站点可浏览、资源能打开、远端能同步必须分别显示。Runtime 不可用只影响该来源，不影响启动、本地阅读或其它服务器。

高亮/笔记默认本地；日志不包含正文、书名查询、签名 URL、认证信息。公共分享只带最小定位信息。未来状态 Sync 服务与 Source Runtime 是不同安全域；Shadow Platform 不成为本次改造前置依赖，也不默认保存阅读事实。

## 12. 工程重构与数据库迁移

### 12.1 第一轮实际模块

先保留六个已有模块，在 `core:model` 与 `core:provider` 增加契约及 legacy adapter。按实现阶段增加 `core:sync`、`core:resource`、`experience:audio`、`experience:text`、`experience:comic`；设计组件在第二个新页面需要时提取为 `core:designsystem`。

新体验先各自封装 engine/UI，再在需求确实分离时拆 engine 和 feature。不要同时引入 `core:audio`、`engine:audio`、`experience:audio` 三层空转发。

目标依赖：app 组装 → feature/experience → use case/core 契约；Provider 实现依赖网络/资源，不依赖 UI；引擎不认识具体 Emby/ABS DTO。Readium、Media3 平台类型不能泄漏进纯 Kotlin 领域契约。

`MainViewModel` 逐步拆为 Shell/Sources/Search/Library/Detail 状态持有者；视频先原样包裹，新增三个体验不再引用 MainViewModel。手动构造注入暂留，Hilt 不是本次必需变更。

### 12.2 数据迁移

最小新增：`progress_records`、`sync_operations`、`migration_imports`；随后按需求增加资源/离线与身份关系。不要首个版本一次建齐附件中的十几张表。

1. **盘点旧数据**：除 Room v3，还包含 PlaybackOutbox preferences、FeedSession、Keystore 会话、来源订阅和网络连接存储；保留旧 stableId 映射表。
2. **显式 Room 增量迁移**：v3→新版本新增表；历史转 TimeLocator，旧收藏不改含义；未知身份只保存本地待匹配，不伪造账号。
3. **桥接写入**：新写入协调器在同一事务更新视频兼容历史和通用进度。文本/漫画不写入只支持毫秒的旧表。旧读取路径暂留。
4. **Outbox 跨存储导入**：先停止 legacy writer/flusher；在 Room 事务内按导入批次+旧记录标识写入及标记；事务成功后才清理旧 preferences。崩溃在清理前再次启动不重复导入；解析失败保留原值并报告，不静默清空。
5. **切换单个发送者**：legacy 与新 Outbox 不能同时消费同一事件。离线记录不得因切换主 Emby 账号发错服务器。
6. **切换读取**：继续卡片读新仓库，先比对旧历史/收藏/时刻/片头尾和 FeedSession 结果；保留 old-key alias，旧深链仍可解析。
7. **延后删旧表**：完成升级样本和至少一个稳定发布周期后，另一个迁移再删旧结构。媒体时刻和片段标记不必第一轮合并为 Annotation，先用读取适配器。

功能开关只回退路由，不回滚数据库 schema。新数据格式发布后，安装旧 APK 不保证可读；生产恢复采用向前修复。测试必须使用备份副本，不能把“关闭开关”描述为完整数据库降级。

### 12.3 原路径保护

视频 legacy adapter 必须保留实际 MediaSourceId、播放方法、会话标识、ISO 后端选择、origin 鉴权及恢复位置。迁移初期同一个事件只由原 reporter 或新协调器之一发送。

暂停/切页/取消打开时释放读取句柄；不要为新阅读缓存改写现有视频播放器的 HTTP 重试和缓冲全局配置。Feed 增量加载单独验收稳定排序、删除后位置和恢复，不顺带重写播放器池。

## 13. 分批计划与验收门槛

沿用现有 M0～M6 历史，不把未完成项重新标为已实现；新增以下里程碑。这里是建议提交批次，不是本轮已执行任务。

| 批次 | 交付 | 验收 |
| --- | --- | --- |
| M7-0 基线/技术验证 | 数据样本清单、契约测试夹具；Readium EPUB、音频服务、CBZ大图三项 spike；依赖兼容记录 | 不动现有起播路径，列出测得结果和未验证设备 |
| M7-1 最小契约 | ContentKind/ExperienceKind、OpenRequest/OpenPlan、Locator、旧来源适配及一个 Fake Provider | Emby/网络视频/直播正确路由；UNKNOWN 不崩溃；不以新接口替换正在运行的整条链 |
| M7-2 数据/资源 | 版本化进度与 Room Outbox、preferences 幂等导入、ResourceKey/Lease、安全获取边界 | 老用户数据和积压事件不丢失、不串账号；过期资源可重新获取 |
| M8-1 听书闭环 | 本地与网络 M4B/MP3、后台服务、章节/轨道、倍速、睡眠、书签、迷你播放器 | 锁屏/切页/旋转不中断；杀进程后用户可准确恢复；视频不双重出声 |
| M8-2 听书服务 | ABS 与 Emby Audio、能力检测、服务端进度 | 本地断网消费后可补报；失效远端会话不伪造在线播放 |
| M9 小说 | EPUB、TXT转换、排版/目录/搜索/标注、OPDS、基础 TTS | 换字号/方向/重启后准确续读；乱码可改编码；离线文件可打开 |
| M10 漫画 | CBZ/图片目录、Komga、LTR/RTL/双页/长条、有界缓存和按章离线 | 大图长图不因整图解码 OOM；翻页/换模式不丢位置；远端能力不足明确降级 |
| M11 全覆盖收口 | 多账号聚合、五入口、渐进搜索、统一继续/详情、基础身份合并、关注更新、直播自动换线 | 所有五类均有真实来源→打开→退出→继续的闭环，不能只有空 Tab |
| M12 可选生态 | Kavita/Suwayomi/Stremio 及必要 Runtime Adapter | 不增加主进程任意代码；单个扩展失败不影响其它来源 |
| M13 读听融合 | Storyteller/Media Overlays、自有对齐适配或上游可用能力 | 在指定文本/音频版本上验证精确映射，不能用百分比代替 |
| M14 增强 | OCR/翻译、可选联网 TTS、追踪平台、音乐/播客等 | 可关闭、有清晰数据流与成本边界，不污染核心链路 |

每个新体验完成时就带最小书库入口和专用详情，不等 M11 才让用户用；M11 做整体导航收口与一致性。并非先做纯重构数周再出现第一本能打开的书。

首版 2.0 以 M7～M11 的五类闭环为门槛。完整第三方书源/图源/影视仓兼容在 M12，不从总目标删除，但不能用“导入成功”替代可用验收。暂不承诺 8～12 周固定工期；完成 spike 后依据依赖和设备测试重新估算。

## 14. 测试、性能与发布门槛

### 自动化

- Provider 契约：稳定 ID、跨账号隔离、分页游标、取消传播、每源超时、冷启动详情、能力真实性。
- 打开契约：五类路由、过期签名、资源 revision 改变、一次授权不越权到其它 Provider。
- 进度：Time/Text/Page/Live round-trip、未知 Locator 版本、重置/重读、多音轨、不同剪辑、并发冲突。
- 迁移：Room v1/v2/v3 各自升级；preferences 已有积压、导入中崩溃、重复启动、损坏记录、旧账号删除、旧深链。
- 安全：跨 origin 重定向、ZIP/XML 攻击、超大图片、HTML 伪装、受控出版物子资源、凭据和正文日志检查。

### 真实体验样本

- 当前 Emby 302、OpenList/SMB/WebDAV、普通 MKV/MP4、ISO Seek、进度/删除/Feed 恢复。
- EPUB2/3、中文长章节、固定布局、损坏文件、不同 TXT 编码、可选 PDF。
- CBZ 有/无 ComicInfo、RTL、双页、超长图、乱序文件、远端取消下载和空间不足。
- M4B 多种章节标签、VBR MP3、缺时长、多个文件、手机锁屏/蓝牙/耳机拔出、服务重建。
- EPG 无时区/显式偏移、跨日、同名不同地区、回看过期、每条线路均失败。

### 指标

先采集同设备同网络基线，再定预算，不声称零延迟。分别统计 Open→首帧/首音/首文字/首漫画页、缓冲、解码时间、内存峰值、源搜索延迟、Outbox 积压；全部默认本地。

拟定视频 P95 起播无超过 10% 的稳定性回退作为调查门槛，须重复同样本测量而非单次网络波动定论。资源 cache、并发、预取像素和包体增量都必须列入每阶段结果；不因五类全覆盖同时常驻五套引擎。

发布前确认手机/平板/TV 的支持范围；新增 PDF/图像 native 依赖需验证目标 ABI 和系统页大小，不把已有 109 MB 包体当作可以无限增加的预算。APK 构建、真机安装、服务部署各按用户后续明确请求执行。

## 15. 精选参考：借哪一层

本轮核查上游公开文档，不宣称已安装体验所有应用，也不使用附件中未经验证的星数、活跃度和“官方级”评价。

| 参考 | 已核实的相关方向 | 本项目用法 |
| --- | --- | --- |
| [Kototoro](https://github.com/Kototoro-app/Kototoro) | 多内容、稳定作品与来源投影、Media Spaces | 借书架/来源分离和整理思路，不照搬动态 classloader/UI 插件 |
| [Riffle](https://github.com/pkmetski/riffle) | 阅读/听书、多服务器、书签与离线管理 | 借详情/迷你播放器/读听入口的产品组织，参考不等于纳入其代码 |
| [Mihon](https://github.com/mihonapp/mihon) | 本地阅读、多个 Viewer/阅读方向、分类和章节更新 | 参考漫画交互与状态边界，不移植整个扩展生态 |
| [Readium](https://github.com/readium/kotlin-toolkit) | 出版物解析与导航工具包 | 选定稳定发布后按模块集成，Shadow 自己负责目录、数据和 UI |
| [Komga](https://komga.org/docs/guides/opds/) | 私有图书服务、OPDS 与专用客户端能力 | 原生 Provider + 通用 OPDS，不把手机变成服务端扫描器 |
| [Audiobookshelf](https://api.audiobookshelf.org/) | 有声书 API、轨道/会话/进度模型 | 针对目标服务版本做契约，不照抄过时示例 |
| [Suwayomi](https://github.com/Suwayomi/Suwayomi-Server) | 服务端运行扩展、客户端消费 | 后期图源优先对接已有 Runtime |
| [Storyteller](https://storyteller-platform.dev/) | 电子书/有声书对齐与带 Media Overlays 的 EPUB3 | 后期精确读听验证；服务有对齐不等于 Android Reader 已支持 |

影视 UI 沿用本仓已建立的封面墙和[参考目录](REFERENCES.md)，直播按 EPG/频道优先组织；本轮不为换外观再换播放器基础库。

代码复用时固定来源提交、保留许可证/版权/NOTICE并记录修改。项目是 GPL 不意味着所有上游/二进制自动同许可；涉及不同许可依赖时在引入批次单独审查。本轮只形成原创设计提案，没有复制上游实现。

## 16. 待确认项与默认选择

无需现在填写账号或部署任何服务。后续实施以以下默认值推进，技术验证失败则更新决策记录：

- 首版平台：Android 手机/平板五类全覆盖；TV 影视/直播/听书优先。
- 小说底座：稳定 Readium Navigator；TXT 内部 EPUB；PDF 非首版阻塞项。
- 同步：私有服务用原生同步；普通文件库先本机状态，跨设备通用同步不默认上传。
- 元数据：读已有服务端和随文件元数据，不改写、不自动联网刮削。
- Runtime：可选远端；不部署、不开放端口、不在主进程加载未知代码。
- 原版本行为：保留旧数据、旧深链、现有来源和起播快路径；新功能按批次接入。

**第一笔实现应是一条可测试的兼容打开链：新增模型 + Legacy Adapter + 路由契约测试。之后尽快交付一部能锁屏续听的本地有声书，再交付一本能准确续读的 EPUB 和一章能连续翻页的漫画。**
