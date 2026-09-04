# 全媒介实施与验收说明

更新：2026-09-04。这是开发分支的实际状态，不是「所有长期规划已完成」或正式发布公告。

后续三轮逻辑走查、竞态修复和回归边界见 [逻辑走查记录](LOGIC_AUDIT_2026_09_04.md)。

## 产品与入口

主入口为影视、直播、阅读、听书、来源。沿用 Material Icons，不新增表情图标。
按照用户提供的原生界面参考采用深灰底、蓝色选择、白色标题、封面网格、持续音频迷你栏，TV 显示侧边导航。
来源页可选择深色、浅色、跟随系统；阅读器独立记忆纸色、夜间、字号、行距和滚动偏好。

影视保留 Emby/302、ISO/libmpv、网络存储、Feed 和原来的媒体进度协议，没有为新书库增加视频起播前的 HEAD、全文件下载或作品匹配等待。

## 当前可使用的路径

| 内容 | 来源与打开 | 状态与限制 |
| --- | --- | --- |
| 影视 | Emby、多服务器、OpenList/WebDAV/SMB、已有 HTTP 影视源 | 原播放器、封面墙、Feed、字幕音轨、ISO 与诊断路径保留；本次未重新做设备播放验收 |
| 直播 | 已有 M3U/TXT/TVBox lives 导入 → 频道/节目单 → 播放 | 复用现有 XMLTV、线路与回看能力；不假定任意订阅提供 EPG |
| 小说 | 本地 EPUB/TXT → Readium；网络文件或 OPDS → 保存副本 → Readium | 目录、全文搜索与续页、精确 Locator、书签、高亮选文、笔记、排版、离线系统 TTS |
| 漫画 | CBZ/ZIP、选定图片目录、Komga | 数字自然排序、ComicInfo、LTR/RTL/双页、长图宽度适配和拖动、按页分块、书签与页内位置 |
| PDF | 本地或下载副本 → Android PdfRenderer | 页码恢复、基本翻页；不承诺全文搜索、文本高亮或 TTS |
| 听书 | 本地 M4B/MP3/M4A/AAC/FLAC/OGG/Opus/WAV；网络存储；ABS；Emby Audio | 独立 MediaSessionService、锁屏控制、音频焦点、倍速、定时/本轨结束睡眠、轨道队列、书签和迷你播放器 |

### 添加内容

1. 阅读/听书页右上角 `+` 使用系统文件选择器，可多选；音频保留持久 URI 授权，图书保存应用内副本。
2. 阅读页目录图标导入一个图片章节目录，保存为内部 CBZ；不会递归扫描其它目录，也不会修改原图。
3. 来源 → 图书与有声书服务，添加 OPDS、Komga 或 Audiobookshelf。支持反代子路径；不预置公共源。
4. 网络存储沿用原连接入口，图书/音频详情会转到对应体验，不再一律交给视频播放器。
5. 作品菜单可收藏、离线保存远端副本或移出本机书架。移出操作有确认，不调用源站删除文件接口。

TXT 默认识别 BOM、严格 UTF-8，失败后尝试 GB18030。排版面板可改为 UTF-8、GB18030、Big5、UTF-16LE/BE。
切换编码会提示章节位置可能变化并重开；原 TXT 保留。

OPDS 支持 Atom 1.x 与 JSON 2.0 的常见目录、分页、相对链接、开放获取附件；不实现 DRM 购买/借阅。
Komga 在线按页取图；离线保存要求原文件为可读取的 ZIP/CBZ，不支持 CBR/RAR。
ABS 整本音轨加入时只请求一次展开元数据，不逐轨预解析临时音频地址。M4B 当前可播放与按时间定位；尚未实现所有内嵌章节标签格式的统一提取。

## 状态、搜索和账号

- `ContentKind`、`ExperienceKind`、`OpenPlan` 和 Time/Text/Page/Live Locator 契约独立于播放器。
- 当前视频以 legacy 兼容路径保留，三个新体验不引用巨型 MainViewModel；并非所有旧页面已完成架构拆分。
- Room v4 新增书架、定位、标注、同步队列与迁移标记，保留 v1/v2/v3 旧表和增量迁移。
- 原 Emby preferences Outbox 按一次性事务导入 Room，成功后才移除旧键并留备份；损坏记录保留，不静默清空。
- 搜索所有已保存 Emby 账号、网络存储、已支持影视/直播源与已导入书架。每源并发限额与超时、增量结果、失败重试、逐来源下一页；更改查询取消旧任务。
- OPDS/Komga/ABS 远端目录由专用来源页浏览；未接入它们的全远端聚合全文检索。书架搜索包括已经加入的这些内容。
- Emby 聚合结果播放绑定结果所属账号，而不是当前首页账号；冷详情真实回源，不再生成虚构 Video 条目。
- 来源地址、用户名、密码或 Token 改变时保守建立新的账号作用域；旧记录保留但不自动同步给新身份。仅修改显示名称保持作用域。
- Komga/ABS 首次加入时只为没有本机记录的内容导入远端位置。既有本地进度不被后台拉取覆盖；不宣称已实现通用跨设备冲突解决。
- 本地进度和 Komga/ABS 待发送记录事务性写入；应用存活期间重试，同步失败不阻塞阅读。删除连接后保留旧作用域队列。
- Emby 流式听书使用本次 PlaybackInfo 的真实 MediaSource/PlaySession，开始、更新、结束有序上报。离线副本通过 UserData 补报位置，不伪造在线播放会话。
- 网络文件、本地文件的进度仅保存在本机；OPDS 本身不提供通用进度同步。

## 资源与安全边界

临时 CDN 地址与请求头只在解析及网络层内存中使用，不进入书架、队列、收藏、深链或本文。
下载和音频数据源按 scheme/host/port 隔离凭据，跨 origin 不转发账号 Token、Authorization、Cookie 或 API Key。
来源凭据以 Android Keystore/AES-GCM 保存；HTTP 需明确勾选。

资源限制为明确的保护边界，不是媒体库条目上限：单本下载/图片目录副本 1 GiB，TXT 32 MiB，归档单条目 64 MiB，归档最多 30,000 条目和总展开预算，图片分块 1,024 像素，PDF 基础页渲染最长边 2,048 像素。空间不足或取消保留原文件、清理本次临时下载。

EPUB 验证 ZIP 路径、重复条目、膨胀预算，拒绝 XML 外部实体；清理正文中的脚本、事件处理器、嵌入框架、自动跳转和 SVG 动态修改。按扩展名及 OPF 声明识别正文，保留 Readium 后注入的引擎脚本。交互式出版物因此可能降级为静态阅读。

## 实际验证

本轮已执行并通过：

- `:app:compileDebugKotlin`、`:app:processDebugManifest`。
- `:core:model:test`、`:core:provider:test`。
- `:core:network:testDebugUnitTest`、`:core:library:testDebugUnitTest`、`:app:testDebugUnitTest`。
- `:core:playback:testDebugUnitTest`；数据库单元测试任务当前为 NO-SOURCE，没有将空任务算成测试覆盖。
- `:app:lintDebug`：0 错误；保留依赖更新、既有 PiP、Gradle 版本目录等提示，不为消除提示盲升整套工具链。
- `ruby scripts/verify-library-migration.rb`：SQLite 内存库中 v3 + 实际 v4 迁移 SQL 与新建 v4 列/索引一致，旧实体完整保留。

测试覆盖类型路由、搜索增量与取消、Emby 音频路径/账号、音频会话顺序、OPDS 解析、跨 origin 凭据、下载大小上限、归档/XML 防护、TXT 转 EPUB 和脚本净化。
SQLite 主机检查不替代 Android MigrationTestHelper，也不证明真实旧用户数据库均已升级成功。

没有生成 APK、签名、安装、部署、连接用户真实图书服务或执行手机/平板/TV 截图验收。下列必须在打包与设备授权后验收：

- Emby 302 与 ISO 原有起播/Seek 不回退。
- Readium WebView 正文、字体、横竖屏恢复、EPUB2/3/fixed-layout 样本。
- 大图分块、长图页内定位、PDF 内存，TV 遥控焦点与字体放大。
- 前台媒体服务通知、锁屏/蓝牙/耳机拔出、应用进程重建与真实音频格式。
- 真实 Komga/ABS 权限、反代地址、不同服务版本和断网补报。

## 不应标为已经完成的规划

五类主要体验已接入代码，但完整提案还有未完成项，不能将本轮结果称为 M7～M14 全部交付：

- M11 的跨来源 Work/Rendition 手动合并/拆分、统一全媒介继续页、关注更新与冲突解决；旧历史尚未整体迁移到新定位表。
- 完整 M4B 内嵌章节、通用断点下载任务中心、连续 Webtoon 跨页长条、后台 TTS、完整 TV 阅读交互。
- M12 的 Kavita/Suwayomi/Stremio 原生与 Runtime Adapter（Kavita 可尝试已有 OPDS，不等于原生 API 完整支持）。
- M13 精确读听对齐/Media Overlays；M14 OCR、翻译、联网 TTS、音乐/播客和追踪平台。
- 新阅读体验的生产级端到端回归、所有旧数据升级样本和正式发布验收。

## 上游与许可

新适配层和界面为本项目实现，没有直接 fork 其它 App 的整套代码。

| 依赖/协议 | 固定版本或依据 | 用途 |
| --- | --- | --- |
| [Readium Kotlin](https://github.com/readium/kotlin-toolkit/tree/3.1.2) | 3.1.2，[BSD-3-Clause](https://github.com/readium/kotlin-toolkit/blob/3.1.2/LICENSE) | EPUB 解析、导航、搜索与定位 |
| [Subsampling Scale Image View](https://github.com/davemorrissey/subsampling-scale-image-view) | AndroidX 3.10.0，[Apache-2.0](https://github.com/davemorrissey/subsampling-scale-image-view/blob/master/LICENSE) | 漫画分块与缩放 |
| [jsoup](https://jsoup.org/license) | 1.21.2，MIT | 出版物标记清理 |
| [OPDS 1.2](https://specs.opds.io/opds-1.2) / [2.0](https://specs.opds.io/opds-2.0) | 规范 | 目录与开放获取链接 |
| [Komga API](https://komga.org/docs/openapi/komga-api/) | 原生书籍/页面/read-progress | 漫画流式访问与页码同步 |
| [Audiobookshelf](https://github.com/advplyr/audiobookshelf/tree/master/server) | libraries/items/tracks 与 me/progress | 多文件有声书和全书位置换算 |
| [Emby UserData](https://dev.emby.media/reference/RestAPI/PlaystateService/postUsersByUseridItemsByItemidUserdata.html) | 用户状态接口 | 离线音频位置补报 |

正式二进制分发前仍需检查打包后的依赖许可/NOTICE、源码提供方式和新增依赖的传递组件清单；GPL 项目身份不免除这些要求。
