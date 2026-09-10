# 固定阅读与媒体样本

`scripts/generate-reading-fixtures.py` 使用原创内容确定性生成，不包含用户书籍、音视频或账号。测试资产只进入 androidTest/benchmark，普通发行包不打入本目录。

| 样本 | 验证 |
| --- | --- |
| epub2-cjk.epub | EPUB2、中文段落、NCX、全文搜索 |
| epub3-cjk-font.epub | EPUB3、嵌入 Noto Sans、中文系统字体回退、字体资源加载 |
| epub3-rtl.epub | RTL spine/正文、目录、选文 |
| epub3-fixed.epub | 固定版式、横竖屏与页面恢复 |
| utf8.txt / gb18030.txt | 两种编码、章节转换、原始文件保留 |
| physical-pages.cbz | 三个原始页，长图、横图、RTL/双页坐标 |
| physical-pages.pdf | 两页文本提取、回到原页、重排末尾完成状态 |
| legacy-locator.json | 升级后保留旧 Locator，不静默换算 |
| silence.wav | 六秒静音音频，后台/恢复测试 |

EPUB2/3 每本含两个章节，搜索标记为 `SHADOW_SEARCH_NEEDLE_1`。在手机、平板和 TV 上按 `docs/DEVICE_ACCEPTANCE.md` 操作，记录实际截图、Locator 和基线数据；本目录存在不代表设备验收已执行。

字体为未修改的 Noto Sans Regular，Copyright 2018 The Noto Project Authors，SIL OFL 1.1；完整许可在 `fonts/OFL.txt`，并随嵌入字体包含于 EPUB 中。一手来源：[字体](https://github.com/notofonts/noto-fonts/tree/main/hinted/ttf/NotoSans)、[许可](https://github.com/notofonts/noto-fonts/blob/main/LICENSE)。`manifest.json` 锁定所有样本 SHA-256。
