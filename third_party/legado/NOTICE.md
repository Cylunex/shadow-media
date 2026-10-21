# Legado 原生阅读代码

来源：[LegadoTeam/legado](https://github.com/LegadoTeam/legado)，固定版本 `5002ba71f6babf70704f2a0a26cff6aeafd95e3b`，GPL-3.0（许可全文见 LICENSE）。版权归 Legado 原作者及贡献者。

移植文件位于 `experience/reading/src/main/kotlin/top/cylunex/shadowmedia/reading/legado/`：

- `ZhLayout.kt`、`HangingLineWidth.kt`：中文断行、标点避头尾。修复最终行与回查时 UTF-16 偏移和字簇下标混用，字宽缓存增加并发保护。
- `LineColumnLayout.kt`：原生行内字坐标与两端对齐。
- `LegadoPageCurl.kt`：由 `SimulationPageDelegate.kt` 提取贝塞尔卷页、背面与阴影绘制，改为接收 Media 绘制的相邻页位图。

Media 自行实现 TXT/Novel 文本适配、分页模型、交互与同步。未引入 Legado 书源、脚本、账号或数据库。
