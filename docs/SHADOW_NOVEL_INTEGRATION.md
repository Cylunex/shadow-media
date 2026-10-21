# Shadow Novel 原生接入

## 使用入口与实现范围

阅读页的「Shadow Novel · 书城与书单」或来源页的「Shadow Novel」进入原生页面。连接自己的 Novel 服务根地址（支持反向代理子路径），验证 `/api/v1/integration` 后保存；无预置地址。连接复用 Keystore AES-GCM 存储格式，使用独立 vault、密钥别名和 `novel:<connection-id>` 作用域。内部沿用通用 `CatalogConnection` 数据结构，不通过 OPDS 请求业务数据。

| 页面 | 实现 |
|---|---|
| 书城 | 服务端热门、最近、完结、分类精选和换一本分区，进入对应完整列表 |
| 搜索与热度 | 服务端分页；标题/作者/别名搜索；分类多选、题材包含/排除、any/all、连载、内容可用性、个人状态/分组/标签、最低热度和排序 |
| 作品 | 简介、别名、分类、题材、字数/章节统计、来源热度与观测时间、版本、目录、所属书单、开始/继续阅读 |
| 作家 | 索引搜索、简介、作品统计、分类统计、作品搜索/排序/连载过滤和分页 |
| 书单 | 搜索、简介、样本、推荐语和成员分页；新建、编辑、软删除/恢复；从作品加入/移出、推荐语编辑及上下移动 |
| 个人 | 服务端书架和历史，加入/移出书架、阅读状态、分组、标签、按来源追更/停止追更及目录检查 |
| 维护 | 作品资料和作家简介的编辑表单仅在点击编辑后出现；默认展示资料 |
| 阅读 | Legado 原生中文排版、真实相邻页与卷页、自动跨章；目录、搜索、书签、主题、字体和离线朗读 |
| 离线 | 已下载章节窗口、页面缓存时间提示、本机位置和待同步状态；移除连接后仍可在本地阅读书架访问下载 |

使用现有 `/api/v1` 产品接口。服务端契约对应 Novel 提交 `06c6246` 的 `docs/media-integration.md`。来源规则导入/测试、采集任务管理、元数据观察与关联管理继续在 Novel 维护；客户端没有实现完整的服务端运维控制台。阅读器搜索作用于本机已下载章节，朗读当前章；章末翻页自动获取下一可读章，完整目录可跳转其他章节。

## 数据与阅读包

- `NovelApi` 构造已知 API 路径与重复查询参数；每次重定向在发送前校验 scheme/host/port，跨源 GET/正文/凭据均拒绝。HTTP 必须显式启用。Basic/Bearer 凭据仅保存在加密连接库。
- `NovelRepository` 使用 `works`、`navigation`、`city/home`、`authors`、`booklists`、`me/books` 等原生接口。普通写操作直接调用服务端，失败保留表单并显示错误。缓存页面标明时间，认证与业务错误不会伪装成缓存成功。
- 浏览缓存使用现有 Room `catalog_pages`，键包含连接作用域；递归移除服务地址、文件路径与来源 URL。读取包使用 `reading-manifest` 与 `publication.epub`；标题来自 manifest，安装时同时解析 OPF 的真实书名和作者。
- 默认窗口为 **3 个目录节点**，未缓存时只按服务端 `missing_chapter_ids` 载入当前窗口正文并重试，不因首次阅读抓取整本网文；后续章节载入失败时退回当前单章，避免阻塞已经可读的当前位置。卷标题不进入 spine；后续窗口按 `next_offset` 接续。
- 资源身份为 `(connection scope, work_id, version_id, package SHA-256)`；安装资源 ID 使用现有书库要求的 64 位十六进制哈希。Room `novel_publications` 保存资源与 `shadow-novel.json` 的映射。导入前校验包身份及窗口章节集合与 manifest 一致。
- `LibraryRepository.importPrepared` 验证 EPUB，既有 `PublicationSanitizer` 去除脚本和外部加载；`NativeTextReaderActivity` 使用 Legado 断行和卷页代码绘制 Canvas，EPUB 仅用作不可变章节运输容器。不同窗口独立保留，书签汇总同版本下载包并打开原位置。

## 位置与同步

`NovelPublicationMap` 将阅读器 href（去容器前缀、片段及查询）精确匹配到唯一 `chapter_id`，仅用 `locations.progression` 表示章内 ratio；不从全书百分比猜章节。完整 Locator 留在本机；远端 anchor 超过 8192 字节时只发送安全的章节路径和章内比例。恢复会移除属于另一窗口的 totalProgression/position，保留身份和文本 hash 合法的 `native_text` 字符锚点；跨 `version_id` 不复用 locator。服务端 `quality` 无法精确映射时要求选择目录章节。

Room `novel_reading_states` 按连接/作品保存已知 revision、最新本地位置、本地序号、稳定 client_id、递增 client_seq、冻结请求、请求对应的序号、冲突和错误。既有资源修订/阅读会话检查通过后，本地进度与 Novel 待同步状态在同一事务写入。

- 打开/预取、初次排版和重排不产生远端进度写入；初始位置可仅保存在本机，供离线恢复。
- 同一本书至多一个在途写入。冻结 `expected_revision/client_id/client_seq/mutation_id` 和正文；重试逐字复用，请求期间新位置独立保存。成功回执只确认其对应序号，不清除更新的位置。
- 409 保留双方位置并暂停该作品；阅读器内提供「继续本机位置 / 采用另一设备位置」。保留本机只在明确选择后以服务器 revision 创建新请求；采用远端会先取得对应版本和章节，再切换阅读器。
- 联网约束的 WorkManager 串行重试，阅读器显示待同步、冲突和失败状态。离线读写保留；HTTP 认证错误等待手动重试/重新连接。旧账号进度不会自动发送到新账号。
- 端点或凭据变化生成新作用域，仅改名保持作用域。移除连接保留下载及待同步记录。
- 窗口末页不将本地资源标为整书已读。服务端基于真实末章、目录完整性、连载状态和章内位置判断作品是否读完。

## 验证（2026-10-20）

已完成：

- `:app:compileDebugKotlin` 和 `:core:database:compileDebugAndroidTestKotlin`，未构建 APK。
- `:core:library:testDebugUnitTest`、`:core:database:testDebugUnitTest`、`:experience:reading:testDebugUnitTest`、`:app:testDebugUnitTest`：84 项通过，包含 16 项新增 Novel 测试，实时契约测试启用时无跳过。
- 路径前缀/重复筛选参数、跨 origin 重定向拦截、缓存脱敏、href/fragment、章内比例与版本隔离、初始 locator 不写入、超大 anchor 回退。
- Robolectric + Room + MockWebServer 覆盖冻结请求重试、请求期间新位置、连接作用域变化和 409 停写。
- 对隔离 Novel 实例的 5 部真实作品逐一下载 3 节点窗口，经过 Media 安全处理后验证章节 ID/路径/manifest 一致，且未修改服务端进度；另执行真实阅读包安装，验证书名/作者、Room 绑定、初始位置无待同步写、窗口末页不标整书完成。真实书目和 EPUB 未加入仓库。
- `ruby scripts/verify-library-migration.rb 9` 验证生产 9→10 迁移与新建 schema 的列、索引、外键一致，历史数据保留。MigrationTestHelper 全版本迁移测试已更新并编译。

可选实时测试读取环境变量 `NOVEL_VALIDATION_URL`，仅应指向隔离测试实例；不设置时两项实时用例跳过。常规 MockWebServer 测试不依赖外部服务。

尚未进行设备验证：本机无连接 Android 设备。Readium 真实触屏翻页/字体排版、后台进程终止与重启、WorkManager 在真实网络切换下的调度、真实认证、两台设备的端到端冲突选择和数据库全版本设备迁移仍需真机验收。未部署、未打包、未签名或安装 APK。

## 2026-10-21 原生正文改造

TXT/Novel 改用 Legado 原生断行、行坐标和仿真卷页，详见 [阅读体验](READING_EXPERIENCE.md)。同步事务和协议保持不变，新增跨窗口字符锚点与原生 outbox 回归；Android 35 模拟器验证触屏翻页、双向跨章、字号重排及恢复。真实服务当次返回 HTTP 502，改用之前下载的两本真实阅读包逐章验证内容和位置，没有写入用户远端进度；真实网络与双设备同步仍需手机测试。

同日补充在线验收：服务恢复 HTTP 200 后，用本次 1.1.2 测试 APK 在 Android 35 模拟器实际验证连接、加载书城并打开真实作品的原生阅读页（本章 36 页）；打开前后服务端进度一致。另验证从旧 Debug APK 覆盖升级，以及从 App 书架导入 TXT 后触屏连续翻页进入下一章。以上仍不代替两台真机同时阅读的同步验收。
