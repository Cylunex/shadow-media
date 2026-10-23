# Novel 最新能力的 Android 客户端适配

本次核对 Novel `3cf3f9ba88663d73426809c3b42be3338bcde50c`，在独立书源分组、精确搜索、详情与目录预览及非阅读书库工具基础上，补齐并发搜索模式、预览章节打开、指定作品换源和收录复用。以当前 `app/api/source_browser.py`、`source_groups.py`、`library_tools.py`、`v1.py` 和执行器返回结构为准。只修改 Media，没有修改或部署 Novel。

## 原生入口与行为

| 入口 | 实现与边界 |
| --- | --- |
| 书城 → 搜索在线书源 | 书名/作者/分类精确开关，多组 OR、标签 AND，书源名称/站点、分类、可用性、启用、能力与分组状态筛选；新版默认自动续查，可选单批 / 全部；每批 12 源，最多传入 5000 个已筛选来源，继续其他源、单源翻页、失败/未返回源重试和主动取消 |
| 书城 → 书源发现 | 复用选源入口，独立 `explore_enabled`，来源与分类/排行榜等栏目、页码及刷新；失败显示上次成功内容与错误，没有发现能力时明确提示 |
| 结果 → 原生详情 | 默认 `include_toc:false`，兼容 `job_id:null` 纯资料来源；使用 name/author/intro/kind/category/word_count/latest_chapter/source_updated_at/status，保留搜索摘要并以详情非空字段补充 |
| 详情 → 目录预览 | 点击才请求 `include_toc:true`；标题过滤、正倒序、重试，标明部分目录及服务端 3 页/200 章/20 秒上限；目录失败保留已有简介/目录；有正文能力且服务支持时可点章，先收录 / 关联并等待固定版本，再按 chapter_url 精确解析章节；纯预览不取正文或写进度 |
| 详情 → 收录 / 阅读 | 统一 `source-results/collect` 幂等收录，保留 work_id 与目录任务失败状态；打开作品采用详情返回的 canonical ID；仅资料不声称可读。为已有作品找源时所有收录 / 阅读动作携带当前 work_id；同名歧义原位选已有作品，仅明确单独收录才传 create_new:true |
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

服务能力从 `/integration` 的 `source_search_modes`、`source_chapter_open`、`source_intake_reuse` 显式读取。缺少能力的服务保持每批 12 源且不发送新 mode 字段；为已有作品换源仍用旧 `source-bindings`，不会将未知 work_id 字段发给旧收录接口后误建作品。旧服务目录预览保持只读，纯资料来源不能冒充可读来源或强行关联。

新版自动模式把完整选定范围交给服务端：无匹配的批次自动继续，首个有匹配批次结束后暂停；全部模式遍历当前页的全部候选，实际并发由服务端控制。展示 matched_count、remaining、concurrency 和结果截断提示，截断不等于任务结束。搜索轮询不再设 60 秒总等待上限；保留原 job ID、提交范围和已返回结果，离开页面会停止本机轮询，返回同一连接的搜索或重新打开该作品的找源页后恢复原任务，不重新提交。此状态保留在连接会话内存中，不承诺进程重建恢复。取消后保留结果，继续 / 重试沿用提交时的精确模式与范围，单源下一页仍按 raw_count 判断。

点击目录章节使用目录任务返回的固定 version_id 和该项原始 chapter_url；`open-chapter` 的作品、版本身份必须与收录回执一致，不能按标题、序号猜测，也不跟随响应网页 URL。解析后交给现有原生阅读器按身份载入。缺失章节或已关联其他作品的冲突保持错误，不更换身份重试。目录任务失败保留收录回执，可继续查看原任务或明确重新获取目录。

“使用此来源继续阅读”只打开新目录版本，不改原版本、书架或已有进度。跨版本无法精确定位时转入该版本目录选择章节，继续沿用阅读进度冲突规则。同名同明确作者的唯一作品由服务端复用；409 `choose_existing_work` 在原详情页展示候选简介，选择后恢复此前的收录 / 阅读 / 指定章节动作；不在客户端自动合并、删除历史重复作品。

所有 API 仍从用户连接构造，支持服务根及 `/novel/` 前缀，沿用 Basic/Bearer/OIDC 续期和重定向同源检查。上传按 64 KiB 缓冲读取 SAF 流，OIDC 重试重新打开输入；导出直接流式写入系统选择的目标文件，支持数组 JSON 和备份二进制。规则、订阅和搜索详情不进入普通页面缓存。新增偏好沿用 SharedPreferences，没有新增业务环境变量或真实服务配置。

新接口返回 404/405 时显示服务版本不支持提示，认证/校验错误保持可见；没有把失败当空结果。旧服务仍可使用原书城、书架与阅读路径。服务器登录源、目录循环或部分目录等限制保留，不以“启用”“有规则”或候选数量代替全链路验证。

## 开源参考与阅读边界

本次读取 Legado 固定版本 `531f9fbf07bdf491cb0aafaf02c35058f3bd0692` 的 [SearchModel](https://github.com/LegadoTeam/legado/blob/531f9fbf07bdf491cb0aafaf02c35058f3bd0692/app/src/main/java/io/legado/app/model/webBook/SearchModel.kt)、[搜索菜单](https://github.com/LegadoTeam/legado/blob/531f9fbf07bdf491cb0aafaf02c35058f3bd0692/app/src/main/res/menu/book_search.xml)、[书源菜单](https://github.com/LegadoTeam/legado/blob/531f9fbf07bdf491cb0aafaf02c35058f3bd0692/app/src/main/res/menu/book_source.xml)、[TocViewModel](https://github.com/LegadoTeam/legado/blob/531f9fbf07bdf491cb0aafaf02c35058f3bd0692/app/src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt)，参考精确搜索、选源、独立发现、目录过滤和显示倒序的交互；同时参考 [Readest 官方仓库](https://github.com/readest/readest) 的文件关联与书库管理入口。使用独立实现，没有复制这些参考中的代码。

已核查 Media 自身 TXT/Novel Canvas 排版、中文断行、五种翻页、纸张/字号/段距/边距、章节跳转返回、书签摘录搜索、音量键、朗读、离线窗口和进度 outbox，本次沿用并回归。源切换先关联指定作品并取目录，可直接选择该来源继续阅读；定位不精确时进入固定版本目录，不将其他版本的字符位置直接套用。原 Legado 排版移植固定版本与 [NOTICE](../third_party/legado/NOTICE.md) 保持，音视频、漫画与 Readium EPUB 核心未改动。

## 验证

执行以下任务，不执行 assemble、安装、打包 APK 或部署：

```sh
./gradlew :core:library:testDebugUnitTest :experience:reading:testDebugUnitTest \
  :app:testDebugUnitTest :app:lintDebug :experience:reading:lintDebug \
  :experience:reading:compileDebugAndroidTestKotlin
```

追加适配后合计 208 项测试：204 项通过、4 项既有外部条件测试跳过。本次追加 15 项测试全部通过，覆盖完整选源范围、能力降级、任务恢复、作品歧义选择、明确新建、来源身份冲突、固定版本与精确章节地址，以及纯目录来源的阅读动作限制。此前 21 项新增测试继续通过。本次 App Lint 无错误；此前阅读模块 Lint 和 Android 验收源码编译已通过，本次重新执行阅读单元测试。Lint 仍有既有依赖/播放/TV 等警告。

新增契约测试覆盖多组选源/AND 标签/发现开关、精确空页继续、分页上下文、null job 预览、最新字段与部分目录、预览失败保留、统一收录回执、主动取消、原始字节流/认证重试、跨源导出拒绝、JSON 数组导出、OPML 转义、清单导入与批量边界。新增 Robolectric/Compose 测试覆盖搜索→详情→作品→返回结果、延迟请求替换、跨页选源、部分目录工具、规则冲突输入保留及 320dp/横屏受限高度。截图检查窄屏搜索截断提示及既有浅色搜索页和深色横屏布局；同名候选选择同时检查窄屏弹层截图与 Compose 交互断言。

这些是 MockWebServer、JVM/Robolectric 和编译/Lint 证据，不是真机或线上书源全链路验收。外部条件测试按既有条件跳过，不计作通过。实际系统文件提供器授权、首次 OIDC、后台/进程重建及真实触摸体验仍需设备验收；服务端既有测试和 NAS 回执没有计入客户端结果。

本次不实现服务器 CLI 恢复、MCP 管理、自动登录脚本或客户端书源执行器。RSS 是标准 Feed，原生规则导出不是 Legado 格式，书目清单不是完整备份，下载章节窗口不是离线全本。

追加适配完成后，已按用户要求于 2026-10-24 构建 1.1.8（27）Debug 测试包，包含上述新增契约。包名与上一版一致，签名、ZIP 完整性、16 KiB ZIP 对齐及新功能代码检查通过；未执行设备安装或服务部署。1.1.7 测试包早于这批追加改动。
