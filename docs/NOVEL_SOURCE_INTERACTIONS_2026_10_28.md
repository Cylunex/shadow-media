# 搜源与换源交互优化

本轮围绕作品搜源、阅读器换源和弱网重试，延续现有原生阅读界面与黑白主题。客户端仅提交代码，不构建 APK；Novel 服务端独立验证和发布。

## 研究与取舍

检查时间：2026-10-28。以下为官方文档或源码观察，并非在本机运行这些产品后的体验结论。

| 一手资料 | 观察 | 本轮取舍 |
| --- | --- | --- |
| [Legado 换源模型](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt)、[操作状态](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/ui/book/changesource/ChangeSourceOperationState.kt)、[结果选项](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/ui/book/changesource/ChangeSourceResultOptions.kt) | 搜索与来源切换分别管理；逐步发布结果；操作代次隔离旧任务；支持来源排序、筛选和当前源置顶。 | 分离搜索和目录准备状态，已返回来源立即可选；用代次保护取消与重试；当前版本置顶、同名同作者结果优先。没有真实测速数据时不虚构“最快来源”。 |
| [Readest 阅读文档](https://readest.com/docs/reading) | 阅读工具按需出现，目录、书签和正文搜索有明确入口；区分章节与整书进度。 | 保持原面板与阅读位置，不为搜源另建导航页；搜索和关联不会自动打开正文，用户选择章节后才跳转。 |
| [Anx Reader 发布记录](https://github.com/Anxcye/anx-reader/releases) | 持续修正目录当前章定位、继续阅读入口的进度保存，以及阅读交互与同步一致性。 | 保留上一轮修复的作品级书架和最新窗口续读，将测试重点放在操作不误改进度、网络失败后可继续，而不是继续增加设置。 |

这是基于问题的独立实现，没有复制上述项目源码，也没有新增依赖。既有 Legado 排版移植继续遵循原有许可证和 NOTICE。

## 客户端变化

- 阅读器搜索与目录准备不再共用一个忙碌状态。其他源仍在搜索时，已有版本和新返回的来源可直接选择；来源目录只在明确选择后准备。
- 取消轮询与发起停止请求有独立操作代次，过期请求不能更新新搜索的状态或提前结束加载提示。停止过程禁用重复停止按钮。
- 停止成功显示结果保留提示，取消搜索作为正常结束处理。目录任务取消仍属于未完成，不能误当成可读目录。
- 轮询中断保留已返回结果，提供“继续获取结果”，恢复同一个任务；任务失败后提供重新搜索。关闭面板停止本机轮询，返回时继续未结束任务。
- 作品页与阅读器搜源均提供“只看同名同作者”，默认仍保留其他候选。同名同作者优先且同组保持原顺序；切换筛选不会重新请求或改变关联规则。
- 展示具体来源的自动关联失败原因，保留全局重试入口。缺失/未知作者不会因为开启筛选而放宽自动关联条件。
- 已有来源列表将当前阅读版本置顶，离线时只允许选择有本机缓存的版本。
- 新版服务声明 `source_version_prepare` 后，收录/准备来源请求才发送 `reuse_version:true`。服务返回 `job_id:null` 与可用 `version_id` 时立即展示目录；否则沿用任务轮询。点击“重试获取目录”仍显式重新获取，旧服务不接收新字段。

## 验证

Room、EPUB 与既有阅读器回归继续执行。新增或加强 MockWebServer / Compose 用例覆盖：

1. 搜索任务仍为 running 时，直接使用已返回结果准备目录，但不自动打开正文或写进度。
2. 停止保留结果；筛选可逆且不额外发起搜索或关联请求。
3. 中途网络失败后恢复原任务，不重复 POST 搜索任务。
4. 搜索取消正常结束，而目录任务取消不能通过阅读准备。
5. 同名同作者稳定排序、未知作者排除、作品页筛选与新旧服务兼容。
6. 新服务返回已有目录时不再轮询目录任务，旧服务仍等待任务成功后打开。

`:core:library:testDebugUnitTest`、`:experience:reading:testDebugUnitTest`、`:app:testDebugUnitTest` 共 258 项通过，4 项需要外部服务或阅读包的可选集成测试跳过；`:app:lintDebug` 通过，0 错误、39 条既有警告。

真实弱网、真机触控与升级后的端到端阅读仍需安装新版客户端验收；本轮按用户要求不构建或发布 APK。
