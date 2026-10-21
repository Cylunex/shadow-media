# 小说原生阅读

## 引擎与来源

`ReaderActivity` 将本地 TXT 和 `novel:` 阅读包交给 `NativeTextReaderActivity`。普通 EPUB 保留 Readium 3.3.0，漫画/PDF 保留 `ComicActivity`。

TXT/Novel 使用 Android Canvas 原生绘页，移植 Legado 固定提交 `5002ba71f6babf70704f2a0a26cff6aeafd95e3b` 的以下代码：

- `ZhLayout` / `HangingLineWidth`：中文断行、标点避头尾；修正 UTF-16 偏移与字簇下标混用。
- `LineColumnLayout`：行内坐标和两端对齐，段末自然排列，段首两字缩进。
- `SimulationPageDelegate` 的贝塞尔卷页、镜像背面与阴影，提取为 `LegadoPageCurl`。

来源、修改范围及 GPL-3.0 全文见 [Legado NOTICE](../third_party/legado/NOTICE.md)，并随 APK 的开源声明分发。未引入书源执行器、脚本、Legado 数据库或账号系统。

## 文本与分页

TXT 直接解码原文件，支持自动 UTF-8/BOM/GB18030 与手动编码，不再转换成网页排版。按中文章节标题识别目录，跳过空白章节；超长单段按安全字符边界拆分为最多 48,000 UTF-16 单位的阅读段，保留旧 `cN.xhtml` 的坐标换算。原 TXT 不改写，整本上限仍为 32 MiB。

Novel 沿用服务端三节点不可变 EPUB 窗口作为下载/缓存容器，只提取已校验 XHTML 的段落文本，正文不进入 WebView。章首重复标题只显示一次。单个 Novel 章节超过 50 万 UTF-16 单位时显示拆分提示，避免整章排版耗尽内存。

正文使用设备字体、中文标点断行、两端对齐、段首缩进和可配置字号/行距/段距/字距/页边距；配有章名、章序和本章页码。五种纸色共用现有偏好存储。打开菜单覆盖正文，系统栏变化不改变版心。

字号或尺寸变化按字符锚点重排，不把旧页码当作位置。分页只保留当前章的模型和最多三张页面位图，关闭时取消任务并释放。断行采用 ICU 字簇，保留扩展汉字、代理对与组合字符。

## 翻页与跨章

- 中央点击呼出菜单，左右点击/拖动翻页；短拖回弹不保存下一页位置。
- 滑动、覆盖、Legado 仿真、无动画和纵向滚动；拖动时显示真实相邻页正文。
- 到达章末继续翻页自动获取下一可读章，反向回到上一章末页，无需重启 Activity。缺失窗口按需下载，失败保留当前正文；离线可继续读本机相邻章节。
- 完整目录分页加载，离线回退已下载目录；支持章内进度条、书签、复制/摘录和搜索。TXT 搜索全文，Novel 搜索本机已下载章节，最多显示 200 个匹配。
- 本机书签跨同版本的 Novel 窗口汇总；点击书签打开其原不可变阅读包。
- 左侧窄边调亮度、双指字号、可选音量键翻页；无障碍节点提供阅读菜单及前后页操作。
- 朗读复用 `ReadAloud` 的离线语音、音频焦点和进程级播放协调；退出、切章或其他播放会话抢占时停止。

## 位置与同步

`native_text` 保存 schema、chapter_id、version_id、文本投影 SHA-256 和 UTF-16 offset。只有身份及 hash 都匹配时恢复精确字符；旧 Readium/其他设备位置没有此锚点时使用章内 ratio。排版变化、初次打开和目录预览不产生同步写入。

实际翻页、搜索跳转、切章和书签跳转通过原 `ProgressWriter` 写入。同一事务验证资源修订/会话序号并保存本机位置和 Novel outbox，继续沿用冻结请求、幂等重试、revision 冲突和账号作用域。`NovelPublicationMap` 在窗口变化时保留有效字符锚点，移除窗口相关的 totalProgression/position。冲突菜单提供保留本机或采用云端位置。

## 验证入口

```sh
./gradlew :experience:reading:testDebugUnitTest :core:library:testDebugUnitTest :app:testDebugUnitTest
./gradlew :experience:reading:connectedDebugAndroidTest
```

主机用例覆盖中文标点、扩展字/emoji、分页文本完整性、字符锚点重排、旧 TXT 位置与编码、超长单段、真实相邻页与卷页绘制；Room + MockWebServer 验证原生位置经事务/outbox 发往同步接口并保留锚点。

可选 `NOVEL_VALIDATION_PACKAGES` 为逗号分隔的本机真实阅读包路径，仅读取、排版并核对内容与锚点；真实正文不进入仓库。`NATIVE_READER_SCREENSHOTS` 指定原生绘制检查图的输出目录。可选 `NOVEL_VALIDATION_URL` / `NOVEL_VALIDATION_TARGETS` 对指定真实书目执行只读端到端打开回归，比较服务端进度前后不变。

2026-10-21 在 Android 35 arm64 模拟器执行了 `NativeReaderDeviceTest`：真实触屏横滑、前后跨章、字号重排不改进度、退出后按字符恢复，并检查实际截图。模拟器结果不能替代手机字体/刷新率、真实断网调度和两台设备同时阅读的最终验收。
