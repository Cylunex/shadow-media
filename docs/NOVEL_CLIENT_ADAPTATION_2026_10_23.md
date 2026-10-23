# Novel 最新能力的 Android 客户端适配

本次核对 Novel `ddb0c284bcd709cba1319ae5fa040f4c6302f4f3`，包含独立书源分组、精确搜索、详情与目录预览及非阅读书库工具。以当前 `app/api/source_browser.py`、`source_groups.py`、`library_tools.py`、`v1.py` 和执行器返回结构为准。只修改 Media，没有修改或部署 Novel。

## 原生入口与行为

| 入口 | 实现与边界 |
| --- | --- |
| 书城 → 搜索在线书源 | 书名/作者/分类精确开关，多组 OR、标签 AND，书源名称/站点、分类、可用性、启用、能力与分组状态筛选；每批最多 24 源，继续其他源、单源翻页、失败/未返回源重试和主动取消 |
| 书城 → 书源发现 | 复用选源入口，独立 `explore_enabled`，来源与分类/排行榜等栏目、页码及刷新；失败显示上次成功内容与错误，没有发现能力时明确提示 |
| 结果 → 原生详情 | 默认 `include_toc:false`，兼容 `job_id:null` 纯资料来源；使用 name/author/intro/kind/category/word_count/latest_chapter/source_updated_at/status，保留搜索摘要并以详情非空字段补充 |
| 详情 → 目录预览 | 点击才请求 `include_toc:true`；标题过滤、正倒序、重试，标明部分目录及服务端 3 页/200 章/20 秒上限；目录失败保留已有简介/目录，不取正文或写进度 |
| 详情 → 收录 / 阅读 | 统一 `source-results/collect` 幂等收录，保留 work_id 与目录任务失败状态；打开作品采用详情返回的 canonical ID；仅资料不声称可读。为已有作品找源时仍可显式关联当前作品 |
| 搜索设置与历史 | 服务端最近 50 条去重关键词、点击复搜、清空；书名/作者排除词及已收录/书架/读过标记开关 |
| 顶栏工具 → 书源管理 | 分页选择、跨页保留、全选筛选结果，多标签/多组增减；名称/站点/启用/耗时/修订排序，启停、发现开关、置顶/置底、最多 200 源搜索检查和逐源回执 |
| 工具 → 独立书源分组 | 新建、重命名、成员统计、移除；与作品书架分组区分，一个源可属于多个组 |
| 管理 → 规则文件 | Novel 原生 JSON 导入/选中导出；单源编辑携带 expected_revision，冲突保留输入，展示最新规则，明确合并后再使用新修订号；不在客户端执行规则 |
| 工具 → 批量整理作品 | 按书名/作者查找，书架/全库范围，跨页最多 500 本，书架增移、标签、主分组、阅读状态、更新目录；作品与原文件保留，多书单复用既有页面 |
| 工具 → 订阅与 RSS | sources/rss 新增、分组、启停、编辑、移除、手动及 0–720 小时间隔、任务状态；RSS/Atom 文章未读/收藏、原文入口；订阅 JSON 与 RSS OPML 导入导出 |
| 工具 → 文件与清单 | 系统文件选择器上传 TXT/EPUB 原始字节，客户端最多 64 MiB；公开文件 URL 后台导入；shadow.library-list/v1 书目与书架/书单增量迁移，明确不含正文及完整进度 |
| Android 分享 / 打开方式 | content URI 的 TXT/EPUB 提供“导入本机并阅读”或选择现有 Novel 连接上传，外部 Intent 不自动上传；沿用既有本地阅读导入器 |
| 工具 → 偏好与备份 | 按连接记住默认首页，真实作品/书架/记录/追更/笔记统计，清理服务器查询缓存，生成任务并通过系统文件保存备份；不提供线上覆盖恢复 |

搜索/发现结果、所选来源与 URL 只保存在当前连接的内存会话；从作品返回保留结果、页码、筛选与滚动。普通作品分页结果也随返回栈恢复，避免返回后滚动到尚未重新加载的页。切换搜索取消本机旧 HTTP/轮询，不能将旧响应写入新关键词；重试/单源翻页使用提交时的范围和精确模式。下一页依据 `raw_count`，本页匹配数为零不等于没有下一页。

所有 API 仍从用户连接构造，支持服务根及 `/novel/` 前缀，沿用 Basic/Bearer/OIDC 续期和重定向同源检查。上传按 64 KiB 缓冲读取 SAF 流，OIDC 重试重新打开输入；导出直接流式写入系统选择的目标文件，支持数组 JSON 和备份二进制。规则、订阅和搜索详情不进入普通页面缓存。新增偏好沿用 SharedPreferences，没有新增业务环境变量或真实服务配置。

新接口返回 404/405 时显示服务版本不支持提示，认证/校验错误保持可见；没有把失败当空结果。旧服务仍可使用原书城、书架与阅读路径。服务器登录源、目录循环或部分目录等限制保留，不以“启用”“有规则”或候选数量代替全链路验证。

## 开源参考与阅读边界

本次读取 Legado 固定版本 `531f9fbf07bdf491cb0aafaf02c35058f3bd0692` 的 [SearchModel](https://github.com/LegadoTeam/legado/blob/531f9fbf07bdf491cb0aafaf02c35058f3bd0692/app/src/main/java/io/legado/app/model/webBook/SearchModel.kt)、[搜索菜单](https://github.com/LegadoTeam/legado/blob/531f9fbf07bdf491cb0aafaf02c35058f3bd0692/app/src/main/res/menu/book_search.xml)、[书源菜单](https://github.com/LegadoTeam/legado/blob/531f9fbf07bdf491cb0aafaf02c35058f3bd0692/app/src/main/res/menu/book_source.xml)、[TocViewModel](https://github.com/LegadoTeam/legado/blob/531f9fbf07bdf491cb0aafaf02c35058f3bd0692/app/src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt)，参考精确搜索、选源、独立发现、目录过滤和显示倒序的交互；同时参考 [Readest 官方仓库](https://github.com/readest/readest) 的文件关联与书库管理入口。使用独立实现，没有复制这些参考中的代码。

已核查 Media 自身 TXT/Novel Canvas 排版、中文断行、五种翻页、纸张/字号/段距/边距、章节跳转返回、书签摘录搜索、音量键、朗读、离线窗口和进度 outbox，本次沿用并回归。源切换仍先关联来源并取目录，再由版本/目录入口选择，不将其他版本的字符位置直接套用。原 Legado 排版移植固定版本与 [NOTICE](../third_party/legado/NOTICE.md) 保持，音视频、漫画与 Readium EPUB 核心未改动。

## 验证

执行以下任务，不执行 assemble、安装、打包 APK 或部署：

```sh
./gradlew :core:library:testDebugUnitTest :experience:reading:testDebugUnitTest \
  :app:testDebugUnitTest :app:lintDebug :experience:reading:lintDebug \
  :experience:reading:compileDebugAndroidTestKotlin
```

最终合计 193 项测试：189 项通过、4 项既有外部条件测试跳过；新增 21 项测试全部通过。App 与阅读模块 Lint 无错误，阅读 Android 验收源码编译通过。Lint 仍有既有依赖/播放/TV 等警告。

新增契约测试覆盖多组选源/AND 标签/发现开关、精确空页继续、分页上下文、null job 预览、最新字段与部分目录、预览失败保留、统一收录回执、主动取消、原始字节流/认证重试、跨源导出拒绝、JSON 数组导出、OPML 转义、清单导入与批量边界。新增 Robolectric/Compose 测试覆盖搜索→详情→作品→返回结果、延迟请求替换、跨页选源、部分目录工具、规则冲突输入保留及 320dp/横屏受限高度。截图检查浅色搜索页和深色横屏布局。

这些是 MockWebServer、JVM/Robolectric 和编译/Lint 证据，不是真机或线上书源全链路验收。外部条件测试按既有条件跳过，不计作通过。实际系统文件提供器授权、首次 OIDC、后台/进程重建及真实触摸体验仍需设备验收；服务端既有测试和 NAS 回执没有计入客户端结果。

本次不实现服务器 CLI 恢复、MCP 管理、自动登录脚本或客户端书源执行器。RSS 是标准 Feed，原生规则导出不是 Legado 格式，书目清单不是完整备份，下载章节窗口不是离线全本。
