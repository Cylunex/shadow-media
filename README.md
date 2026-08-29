# Shadow Media

一个以 Emby 私有媒体库为第一方核心、以原生播放为基础的 Android 媒体客户端。它提供媒体中心、
封面墙和刷片体验，并为用户自带的外部配置与直播订阅建立受控入口。项目不登录网盘、不刮削媒体，
也不会预置或分发公开内容源。

当前 `1.0.1` 已经打通主要播放闭环，并建立可持续扩展底座：

```text
选择或添加多个 Emby 登录
  → 媒体中心：继续观看 / 最近新增 / 我的收藏
  → 媒体库：服务端分页封面墙 / 搜索 / 筛选 / 排序 / 剧集详情
  → 从封面墙进入播放，或读取媒体库全部视频进入 VerticalPager Feed
  → 请求 PlaybackInfo
  → Direct Play / DirectStream / 302，失败后回退 HLS 转码
  → 普通媒体由 Media3 播放，ISO 由内置 libmpv 光盘引擎播放
  → Playing / Progress / Stopped 回写

用户自带 TVBox JSON / M3U / TXT
  → HTTPS/局域网 HTTP 策略与 2 MiB 上限检查
  → 支持 URL 或系统文件选择器导入
  → 兼容 JSONC 注释、伪装 Content-Type 与多仓 urls 目录
  → 自动限时展开 lives 二级 M3U/TXT，失效子源不阻塞整体导入
  → 配置正文使用 Android Keystore + AES-GCM 加密保存
  → M3U/TXT 解析为视频列表并由独立 Media3 播放
  → TVBox JSON 读取站点、直播和运行时需求等元数据
  → 未知 JAR / QuickJS / Python / WebView 代码绝不在主进程执行

统一发现
  → Emby、直播频道和安全 HTTP CMS 站点注册为 MediaProvider
  → 聚合搜索允许单个 Provider 失败，结果按标题相关性和评分排序
  → 统一详情、选集、线路与播放解析
```

## 已包含

- Kotlin 2.2、Jetpack Compose、Media3 1.11 和 OkHttp 的多模块 Android 工程；
- Emby 用户登录、媒体库、视频列表、`PlaybackInfo` 和播放状态上报接口；
- 多 Emby 服务器/用户管理：加密保存、快速切换和单独移除登录；
- 媒体中心首页：继续观看、最近新增、我的收藏和媒体库/影视仓入口；
- 统一发现页：跨 Emby、直播和已导入 HTTP CMS Provider 搜索，展示源级故障而不中断其余结果；
- Android TV 启动入口与 D-pad 焦点反馈；遥控器支持 Feed 切换、10 秒 Seek 和播放/暂停；
- 离开播放页自动进入画中画，详情页可分享 `shadowmedia://` 接力链接到已配置相同 Provider 的设备；
- 加密服务连接中心：MoviePilot/Seerr 健康检查与管理入口、Seerr 想看请求、Tunarr/Dispatcharr
  M3U/XMLTV 导入直播中心；
- Emby 封面墙：每页 60 条的服务端分页、库内搜索、已看/未看/续播/收藏筛选、名称/日期/评分/
  随机排序；
- 电视剧与合集详情：读取子项、展示剧集进度并从选定剧集开始连续播放；
- 收藏状态直接写回 Emby，海报缓存键随服务器、媒体 ID、图片 Tag 和尺寸自然失效；
- 影视仓视频源：从 URL 或本地文件导入 TVBox JSON/JSONC、多仓目录、M3U 和 TXT，并安全展开
  `lives` 中最多 8 个二级列表；M3U/TXT 支持分组、相对地址、
  `tvg-logo` 以及受限的 User-Agent/Referer/Origin 播放头；
- 外部视频列表与 Media3 播放页使用无 Cookie、无 Emby 拦截器的独立 OkHttp 客户端，不向外部地址
  发送 Emby Token，也不把外部播放进度回写 Emby；
- 每个服务器、用户和媒体库独立保存稳定 Feed 顺序及上次刷片位置；
- 远端分页加载全部视频，不限制为前 20 条；
- 全屏垂直刷片 Feed：从任意条目进入，上下滑动时按需解析并切换播放器；
- Media3 Material 3 原生播放器与续播位置恢复；
- Emby 只返回媒体源能力、不返回 `DirectStreamUrl` 时构造标准静态流地址，直连失败再回退转码；
- 循环播放、系统音频焦点、拔出耳机暂停、后台暂停与前台恢复；
- 播放地址重新解析，以及不包含 Token 的播放诊断信息；
- 播放候选全部失败时自动刷新一次 `PlaybackInfo`，并从失败位置继续；
- OpenList/115 冷缓存 302 采用更长解析窗口、每 host 受控并发和临时 CDN 失败后回源获取新签名；
- 播放诊断展示最终跳转 host、HTTP 状态、跳转次数、尝试次数和响应头耗时，不显示完整 URL；
- 持久化播放进度 Outbox：网络失败后保留并在该服务器下次连接时补报；
- ISO/DVD/Blu-ray 镜像由内置 libmpv + libbluray/libdvdnav 光盘引擎直接读取，支持应用内续播、
  拖动、章节、音轨/字幕切换和 Emby 进度同步；
- ISO 远程读取采用严格 HTTP Range、容量受控的内存分页缓存和 302 后逐跳凭据隔离；VLC 仅保留
  为显式兜底；
- 独立可拖动进度条；转码链路通过 `StartTimeTicks` 实现服务端 Seek；
- 列表和 Feed 均支持经二次确认后从 Emby 媒体库及服务器文件系统永久删除条目；
- 精确 origin 鉴权隔离：Emby Token 不会跟随 302 请求发送到第三方 CDN；
- Android Keystore + AES-GCM 加密保存多账号会话 Token，并兼容旧版单账号数据迁移；
- HTTPS 默认策略，以及用户明确确认后的局域网 HTTP；
- 核心 URL、时间单位和请求头安全策略单元测试。
- 统一 `MediaProvider`、跨源 `MediaKey`、统一详情/分页/播放请求模型与运行时能力声明；
- Room 2.8 本地状态库：历史、收藏、搜索、播放指标、线路健康、媒体时刻、用户档案与功能开关；
- 普通视频音轨与内嵌字幕循环选择、MediaSession 元数据和应用内画中画入口；
- 播放首帧、缓冲、候选链和失败原因自动写入本地遥测，并聚合线路健康度；
- 首页生成基于续播、收藏、已看状态和评分的可解释推荐，不上传个人观看数据；
- 普通视频与 ISO 都可保存媒体时刻、标记片头/片尾并在命中片段时一键跳过；
- 媒体记忆页统一展示可续播时刻、当前片段地图、首帧/缓冲/失败统计和线路健康度，并可导出不含
  地址、片名、Token 或完整线路标识的脱敏诊断；
- 可关闭的功能控制台，以及电影感动态背景、玻璃层次和统一 Material Icons 视觉系统；

## 工程结构

```text
app/             应用入口、手动依赖注入、端到端验证 UI
core/model/      与 Android 无关的领域模型
core/provider/   统一媒体 Provider 协议与注册表
core/database/   Room 本地状态、Paging 数据源和播放质量数据
core/network/    Emby API、DTO、仓库、Keystore 会话存储
core/playback/   Media3、libmpv ISO 引擎、302 请求头隔离、回退与播放上报
docs/            架构决策、播放安全与迭代路线
```

## 本地运行

要求 JDK 17、Android SDK 36 和 Build Tools 35.0.0：

```bash
./gradlew test
./gradlew assembleDebug
```

打开应用后输入自己的 Emby 地址和用户凭据。仓库不包含真实服务地址、Token 或签名材料。
HTTP 只应在受信任局域网内临时启用。

ISO 是光盘镜像而不是普通视频容器。Emby Server 不支持 ISO 转码，Media3 也不能解析光盘结构，
所以 ISO 页面使用内置的 GPL-3.0 libmpv 光盘后端，通过 libbluray/libdvdnav 对 Emby/MediaWarp/
115 返回的远程镜像执行随机 Range 读取。当前自动选择最长标题，支持 DVD 与无 DRM 的 Blu-ray；
加密商业光盘、BD-J 菜单、完整菜单导航和 x86/x86_64 设备不在支持范围。详见
[ISO 播放说明](docs/ISO_PLAYBACK.md)。

## 安装包

本地执行 `./gradlew assembleDebug` 会生成一个使用 Android Debug Key 签名、可直接安装测试的
`app/build/outputs/apk/debug/app-debug.apk`。正式发布前需要配置项目专用签名，仓库不会保存私钥。

删除功能调用 Emby 的文件删除接口，不是仅从 Feed 隐藏。执行前务必确认服务端备份和 Emby
用户的删除权限。

## 外部源边界

当前影视仓页面已能导入并播放用户自带 M3U/TXT，也能从 TVBox JSON/多仓目录自动提取直播列表，
直播中心会合并同名频道线路，提供搜索、分组、收藏和线路切换，并在订阅提供 XMLTV 时展示当前/
下一节目、直播进度及标准 catch-up 回看入口。EPG 会缓存到 Room，网络更新失败时仍可使用有效缓存。
但还不是完整 CatVod 执行器。TVBox JSON 中的
远程 HTTP API、统一搜索和换源会逐步接入统一 Provider；需要 JAR、QuickJS 或 Python 的站点只会标记为“需要隔离
运行时”，不会在包含 Emby Token 的主应用进程内执行。完整边界见
[媒体中心与外部源](docs/MEDIA_HUB.md)。

电视遥控与无凭据接力协议见 [Android TV 与播放接力](docs/TV_AND_HANDOFF.md)。
外部服务能力与凭据边界见 [服务连接](docs/INTEGRATIONS.md)。
媒体时刻、用户片段与脱敏统计见 [媒体记忆与洞察](docs/MEMORY_AND_INSIGHTS.md)。

## 下一阶段

真实链路兼容性矩阵验证通过后，按 Media3 官方短视频示例引入容量为 3 的 `PlayerPool`、
`rememberPooledPlayer` 与共享
`DefaultPreloadManager.Builder`。最终 CDN URL 只解析当前项和后续 1–2 项，不落盘。

本项目整体以 GPL-3.0 发布。详见 [架构说明](docs/ARCHITECTURE.md)、
[ISO 播放说明](docs/ISO_PLAYBACK.md)、[第三方来源](third_party/webhtv-mpv/NOTICE.md)、
[参考实现与取舍](docs/REFERENCES.md) 与 [路线图](docs/ROADMAP.md)。
