# 十一月阅读检索与书源面板改进

日期：2026-11-10。围绕 Novel 原生接入与本机阅读展开；本轮验证源码与主机回归，不构建/安装 APK，不部署 NAS。真机书源整块弹层抖动由另一个排查会话提供证据，本轮修复后的真机复验仍待独立打包任务。

## 新参考

- [FnReader v2.0.6](https://github.com/TZYLXS/FnReader/tree/441fb192cdae391f196d52db9161a1f42087ff53)，Apache-2.0：短目录补全和搜索短空缓存由 Novel 服务端承担，客户端不额外抓取网页。
- [yyx-library v10.0.3](https://github.com/firewelly/yyx-library/tree/6cbdecb3d334e2be795a1c35ae55feff51cf5979)，BSD-3-Clause：简繁一体检索。其源码中目录为数据库范围内 LIKE，Shadow 的 FTS5 标题索引是独立增量。
- [Registrum](https://github.com/registrum-dev/Registrum/tree/c32f4c5caea95ffa4350518d69a34d827e6013b6)，MIT：宽度归一、跨设备阅读提示；本轮采用全角 ASCII 的检索折叠原则。
- [Bookshelf](https://github.com/hthoene/Bookshelf/tree/f0be28858ede4b9e60a30a40c32146e4349fa322)，MIT：轻量 OPDS 与离线选书同步，后续外部阅读器拓展参考。
- [Lumi Books 的双页排版提交](https://github.com/huangder/Lumi_Books/commit/3e48dfea93740fdc19cc9faffe837f0fd121fd97)，GPL-3.0：双页可见位置和跨章进度的设计参考，本轮未复制代码或更换阅读内核。
- [LocalReader v2.1.0](https://github.com/ChengqianHuang/Reader/tree/212e893b4ed364ac6b731da306b044ec1d5f32e6)，MIT：离线 Compose/Room 与会话统计；最新核查发行仍为六月，不当作近期高活跃项目。
- [GoTome v0.14.0](https://github.com/praetorianer777/GoTome/releases/tag/v0.14.0)，Apache-2.0：内容检索、近重复比较和智能书架继续作为服务端拓展方向。

外部项目只作为公开行为与架构证据。本轮仅分发 OpenCC 官方字表，其他新增代码均为独立实现。

## 两端一致的检索

`core:library` 的 `SearchText` 使用 OpenCC ver.1.1.9 首选单字映射，并折叠全角 ASCII 和全角空格；原始字表和 Novel 一致，SHA-256 为 `6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a`，许可证与来源声明一并打入 Java resources，见 [OpenCC 声明](../third_party/opencc/NOTICE.md)。

- 本地 TXT 目录、Novel 离线/已存目录、下载选章过滤及原生已下载正文搜索支持简繁同搜。
- 正文只对搜索副本折叠，所有映射保持 UTF-16 长度；摘要与高亮展示原文，Locator 继续引用原始字符偏移。向前/向后搜索、取消、200 条上限和不读取后续章节的约定不变。
- 精确搜索的完全匹配优先排序也折叠关键词；不会因此把简繁相似作品自动认作同一本，自动关联仍要求现有身份条件。
- 不新增下载、进度写入或词组级显示转换。在线全书目录检索由新 Novel 服务端处理；旧服务的联网结果仍以旧接口行为为准，本机离线检索不依赖服务端升级。

## 书源弹层与列表

此前阅读面板在 ModalBottomSheet 内部按剩余高度的 .92/.58 分配内容，再叠导航栏/键盘 padding。Material 3 会在弹层内容中按 offset 消耗顶部 Insets；当内容反过来决定 Surface 高度时，存在高度与 Expanded 锚点反复影响的路径。

统一 `ReaderModalBottomSheet` 在 Surface 外层按窗口约束限定总高度，内部内容只填充分配空间；系统 Insets 交给 Material 3 处理。阅读器所有共壳面板，以及详情“找书源”和“选择排行榜”均接入。参考 [Material 3 Insets 官方说明](https://developer.android.com/develop/ui/compose/system/material-insets)。这解释了修复针对的测量路径，不把其他只读排查风险当成已真机复现。

另外修复三处独立列表稳定性问题：

- 刷新期间保留上次成功的远端版本，成功后整体合并本机与远端目录；失败不先撤掉现有远端项。离线模式不尝试远端刷新。
- 每个连接/作品保留目录数据与真实 LazyListState；关闭重开、来源目录返回时不先用空列表夹断位置。版本、结果和固定标题都有稳定且分区的 key。
- 阅读器来源反馈区保留固定尺寸，Snackbar 改为叠加；通用书源浏览器的进度条也预留固定高度，避免开始/结束请求时推动整列。搜索/错误控件仍在可滚动内容内，正常新增来源会按条目 key 保持可见位置。

没有禁止正常滚动，没有替用户切换书源或写入阅读进度，也没有添加审核页。

## 验证

验证入口使用 JDK 17：

```sh
./gradlew :core:library:testDebugUnitTest :experience:reading:testDebugUnitTest :app:testDebugUnitTest \
  :core:library:lintDebug :experience:reading:lintDebug :app:lintDebug
```

新增回归覆盖原文简繁与全角字符、非 BMP/emoji 后的前后向 Locator、离线标题分页、真实 ModalBottomSheet 外壳、连续多帧稳定性、长列表滚动、模拟导航栏/IME Insets、横屏与不同面板高度，以及延迟/失败刷新下关闭重开与目录返回的来源位置。测试核对没有新增进度写入。

本轮三个模块共 353 项测试：349 项通过，4 项需要外部输入的可选测试跳过；三个模块 Lint 均通过。新增四种面板尺寸/横屏用例后，阅读模块全量 123 项又单独复跑通过（含原有 2 项可选跳过）。

主机回归不能代替修复包在真机上的键盘动画、触屏拖动、旋转和原故障链路验收。EPUB 双页、正文服务端全文索引、智能书架和近重复管理仍属后续拓展。
