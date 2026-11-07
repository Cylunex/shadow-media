# 阅读换源、净化与书城管理

## 本次变化

- 原生小说/TXT 阅读顶部直接提供换源（Novel）、搜正文、替换、书签，原有书签图标及日夜切换继续可用。换源移出更多菜单，保留既有章节匹配、手工选择及返回位置。
- 在线书源搜索将继续、停止、重试未完成与回顶置于滚动列表外；阅读器内搜源也提供固定的继续查询入口。续搜排除已经返回的书源，并合并原结果。
- 全局替换支持纯文字、通用正则、留空屏蔽、编辑、排序、启停、删除和预览。新增规则和保存按钮固定在面板上方，书库工具也能进入。
- Novel 规则按连接同步，缓存到本机；本机 TXT 使用本机规则。读取已下载正文不等待网络，进入阅读后独立刷新规则，版本变化时重新投影当前章。离线仍可使用缓存规则。
- 从原始正文生成同一份净化结果用于显示、搜索和听读，原始阅读包不改写。每条正则 50ms、整章 250ms、结果最多 512,000 字符；无效、复杂或膨胀规则整章回退原文。Python 与 Android 各用本机正则引擎，复杂语法不保证跨端兼容。
- 保存规则后重新显示本章；正文改变时，优先用仍然唯一匹配的原句恢复位置，再回退原有比例定位。重复原句不会擅自当作精确位置。
- 书城、作品列表及详情可管理和删除。已删除列表支持恢复；批量整理工具补齐删除、恢复与撤销。正文、书架资料、阅读进度、书签和笔记保留。

## 接口与兼容

Novel 能力发现新增 `reading_rules、catalog_trash`。规则接口为 `GET/PUT reading-rules`，保存携带 `expected_revision`，其他设备修改后返回 409；现有规则编辑区保留到用户重新加载。

`library/books/bulk` 新增 `delete、restore`；`works?view=trash` 获取已删除列表。旧服务不具备新接口时需要先更新 Novel。下载章节使用 `apply_rules=false`，EPUB 窗口继续传输原始正文，由本机应用一次规则，避免替换结果再次进入替换。

本次主要面向 Novel 原生正文及本机 TXT。普通 EPUB 的 Readium 排版、PDF 和漫画不新增文字替换。规则导入导出可从 Novel Web 进行；Android 面板提供直接管理。

## 参考

参照 LegadoTeam/legado `330e606c4d57e08adc056ff8ab7a4132c784da9a` 的[顶部操作](https://github.com/LegadoTeam/legado/blob/330e606c4d57e08adc056ff8ab7a4132c784da9a/app/src/main/res/menu/book_read.xml)、[独立换源列表与定位栏](https://github.com/LegadoTeam/legado/blob/330e606c4d57e08adc056ff8ab7a4132c784da9a/app/src/main/res/layout/dialog_book_change_source.xml)、[替换规则结构](https://github.com/LegadoTeam/legado/blob/330e606c4d57e08adc056ff8ab7a4132c784da9a/app/src/main/java/io/legado/app/data/entities/ReplaceRule.kt)。这是交互参考，没有整体移植其控制界面或规则管理系统。

## 验证范围

- 阅读模块 112 项、书库模块 148 项及 Novel App 界面 38 项，共 298 项；294 项通过，4 项可选外部样本跳过。覆盖顶部直达操作、固定续搜控制、保留已有来源、离线净化与原文恢复、广告删除后的原句定位、正则超时及膨胀回退。
- App Kotlin 编译和阅读模块 lint；Robolectric 在 320px 布局检查顶部操作，Web 同步实现用真实 Chrome 在 390px / 1280px 验证。
- 不构建 APK、不部署。尚未连接真机；实际触控、输入法、厂商 TTS 与实网书源仍属于设备验收。
