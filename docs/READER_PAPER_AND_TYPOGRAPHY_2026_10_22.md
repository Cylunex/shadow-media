# 阅读器排版与仿真羊皮纸

本轮只修改 Shadow Media，重点是小说 / TXT 的原生阅读页，并同步改善普通 EPUB 的纸张和排版设置。

## 参考与取舍

2026-10-22 查阅下列一手资料；产品观察与本项目的具体实现分别列出。没有新增第三方阅读引擎或运行时依赖。

| 参考资料 | 关注的阅读体验 | 本轮实现 |
| --- | --- | --- |
| [Apple Books 阅读与外观设置](https://support.apple.com/en-euro/guide/iphone/iphc1af7c57/27/ios/27) | 常用字号按钮、主题、细分排版与对齐选项 | 四组排版预设、字号加减、正文预览；精细选项按需展开 |
| [Kindle 官方无障碍阅读介绍](https://www.aboutamazon.com/news/books-and-authors/kindle-accessibility-features-for-all-readers) | 字体、间距、对齐方式和背景色可按阅读习惯调整 | 大字预设采用自然左对齐，保留独立对齐开关；设置入口保持明确的触控语义 |
| [Kobo 字体与页面设置](https://help.kobo.com/hc/en-us/articles/360017639913-Adjust-font-size-and-change-the-font-style-on-your-Kobo-eReader) | 阅读菜单中直接调整字体、行距、边距与对齐，记住用户偏好 | 新选项继续写入原有偏好，重开阅读器可恢复；预设只调整字体布局 |
| [Readest 自定义设置](https://www.readest.com/docs/customization) | 背景图片、纹理浓度、版面宽度与中西文排版 | 内置轻量羊皮纸纹理、0–100% 深浅设置；原生宽屏居中限制行长 |
| [KOReader 状态栏与章节进度](https://koreader.rocks/user_guide/#statusbar) | 可配置状态栏和本章进度 | 原生页脚显示本章比例、页码和细进度线；可隐藏页眉页脚并增加正文空间 |
| [Legado](https://github.com/LegadoTeam/legado) 与 [现有移植说明](../third_party/legado/NOTICE.md) | 原生中文断行、行内排布和贝塞尔卷页 | 沿用已移植内核；新纹理进入真实页面快照，翻页正反面继续使用实际页面内容 |

## 已实现

- **羊皮纸**：替换原生纯色与 EPUB 程序噪点。使用同一张 1024 × 1536 仿真纤维纹理，默认强度 65%，以中心裁切保持纹理比例；正文用较深的棕墨色。纹理关闭后为纯色纸张。
- **外观切换**：原生修改纸色或纹理只清理页面绘制缓存，不重新分页、不改变字符锚点、不写阅读进度。菜单提供日夜按钮，返回日间时恢复上次的纸张，记忆可跨启动保留。
- **预设和预览**：经典、舒展、大字、紧凑四组；保留亮度、纸张、翻页和手势偏好。预览显示所选纸张、字体、字号、行距、字距和对齐方式。字号按钮以 5% 为一步，保留 80–220% 范围。
- **精细排版**：保留行距、段距、字距、边距、字体、加粗、缩进；增加两端对齐开关。大字预设默认自然左对齐，避免窄屏将短行过度拉开。
- **原生版心**：宽屏默认最多 640 dp / 28 个当前字号字宽，正文与页眉页脚一同居中；可关闭。修复大字号和大页边距同时使用时，分页宽度可能大于实际视口的问题。
- **原生阅读进度**：页脚以本章页数计算比例，不再将包含卷标题的目录序号标成章节号。可隐藏页眉和页脚；隐藏后的滚动定位同步使用新的正文顶部留白。
- **性能**：纹理在工作线程解码，进程内共享不可变位图；原生继续只缓存最多三张页面，EPUB 使用缓存的内置图片 data URI，不联网读取纹理。

宽屏限制与页眉页脚开关只用于原生小说 / TXT。普通 EPUB 的流式布局仍由 Readium 管理；固定版式书籍遵循原书布局。排版预览是样例，实际分页继续由各自阅读引擎计算。

## 素材来源

- 生成方式：内置 `image_gen.imagegen`，全新生成，无参考图。
- 最终资源：`experience/reading/src/main/assets/reader/parchment.webp`。
- 尺寸：1024 × 1536；WebP 质量 88，208,192 字节。只做格式压缩，显示深浅由阅读器控制。
- 原始生成提示词：

```text
Use case: photorealistic-natural. Asset type: full-bleed parchment background texture for a mobile novel reader, portrait 1024 x 1536. Primary request: an extremely realistic close-up scanned sheet of fine, softly aged ivory-gold vellum parchment. Flat orthographic view, surface fills every pixel, no perspective. Real delicate organic collagen/fiber grain, tiny irregular pores, extremely subtle cloudy warm tonal variation, faint age patina mostly near the outermost 3 percent of the frame. The central 90 percent must remain pale warm cream, very even in brightness and calm enough to read dense dark text over for hours. Authentic physical material, tactile and natural, not a flat computer noise pattern. Soft diffuse even archival scanner lighting. Colors: pale warm ivory and muted honey; avoid orange/brown cast or high contrast. No objects, no letters, no symbols, no print, no writing, no borders, no torn edges, no heavy stains, no creases crossing the reading area, no shadows, no vignette, no decorative scrolls, no watermark. A practical elegant reading-paper texture, not a fantasy treasure map.
```

## 验证

```sh
./gradlew :experience:reading:testDebugUnitTest :core:library:testDebugUnitTest :app:testDebugUnitTest \
  :experience:reading:lintDebug :app:lintDebug :experience:reading:compileDebugAndroidTestKotlin
```

结果：172 项测试，168 项通过、4 项外部环境用例按条件跳过；阅读模块与应用 Lint、设备测试源码编译通过。

新增回归覆盖偏好持久化与日间纸张恢复、预设不改阅读行为、纹理颜色对比度、原生纸张刷新不分页/不写进度、EPUB 样式与 data URI、宽屏和窄屏字号边界、隐藏页眉页脚后的字符恢复，以及预设/精细排版/320 dp 日夜按钮的实际 Compose 操作。

Robolectric 原生绘制已检查羊皮纸正文、真实相邻页、卷曲背面、夜间、大字窄屏、横屏版心及设置菜单。设置 `NATIVE_READER_SCREENSHOTS` 可重新导出原创测试文本截图，产物留在仓库外。

本轮未构建 APK，未执行 Android 设备测试；真实 WebView 的 EPUB 切章背景、不同设备字体和 GPU 翻页流畅度仍需要设备验收。
