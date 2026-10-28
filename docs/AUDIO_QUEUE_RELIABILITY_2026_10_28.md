# 音频队列隔离与睡眠定时

检查日期：2026-10-28。基线 `54aea6a`。本轮只改 Media 客户端源码与文档，不安装参考项目、不构建 APK、不部署、不连接生产服务。

## 现状与选择

当前源码已经实现六模块导航、黑白主题、Novel 渐进搜源与阅读缓存，也具备独立后台音频服务、按音乐／听书保存的队列、重复曲目实例 ID、离线音频和系统媒体库入口。新增另一套导航、播放器或下载系统没有实际收益。

核对音频服务后发现：新打开听书时会继承音乐的随机／循环状态，听书界面又没有关闭这些音乐控制的入口；恢复另一队列时，旧睡眠截止时间仍可能生效；“当前轨道结束”原先等媒体切换后才暂停。前两项用真实 MediaController → MediaLibrarySession → AudiobookService 的 Robolectric 测试复现后修复。

本轮采用会话状态明确归属、有限媒体边界暂停和恢复行为回归测试，保留现有产品导航、样式与服务合同。

## 参考源码与许可

以下链接固定到本次检查的修订。观察来自源码和 LICENSE 文件，未运行上游应用，不代表对完整项目的安全审计或实际体验背书。本轮没有复制第三方源码、资源或增加生产依赖。

| 项目／修订 | 检查内容与许可 | 采用或未采用 |
| --- | --- | --- |
| [MeloX-Android `7c17c009`](https://github.com/lladlam/MeloX-Android/blob/7c17c009b829c2a0c75c4316dd52d00ebf218c0a/android/app/src/main/kotlin/com/lladlam/melox/playback/MeloXPlaybackQueueStore.kt) | 队列保存、位置局部写入、按媒体 ID 找回当前项；另读同修订 `MeloXPlaybackService.kt`。根 LICENSE 为 GPL-3.0。 | 借鉴恢复语义要与队列身份绑定的检查方法。Media 已有实例 ID 与 Room 队列，不改成 URL 持久化；重复曲目继续按实例恢复。不引入其在线音乐平台与同步播放设施。 |
| [fqapp `c6c1b16e`](https://github.com/ch6vip/fqapp/blob/c6c1b16ef7a2a2e0cb50301c8118943ced153938/lib/services/audio_playback_controller.dart) | 控制器持有播放器、操作代次、保存进度、关闭时取消睡眠任务；根 LICENSE 为 GPL-3.0。 | 采用会话切换时清理临时控制状态这一方向。继续使用现有 Media3 后台服务；不引入其内容接口、解密链路或 Flutter/Rust 音频栈。 |
| [legado-ios `36af1b1a`](https://github.com/Aminark/legado-ios/blob/36af1b1a2661c6cdeb6e88ca618f25c2ac9bb677/Sources/LegadoCore/Search/BookSearchService.swift) | `AsyncStream` 逐源结果、有界并发、优先源和近期成功／失败排序、取消不计失败；根 LICENSE 为 GPL-3.0。 | Media 已有可中断／恢复的增量结果。本轮不另建搜索引擎；来源执行与调度由独立 Novel 项目负责，客户端不伪造测速排序。 |
| [tvbox `99873574`](https://github.com/ZxxXinI/tvbox/blob/998735749179b0e87a31cfd7b8109fada1b006f0/app/src/main/java/com/tvbox/app/ui/ReturnFocus.kt) | 保存稳定项键和索引，先滚入网格再请求焦点；该修订递归文件树没有发现 LICENSE/COPYING/NOTICE，README 也未明确授予源码许可。 | 仅作交互观察，不复制代码。电视焦点优化应结合遥控器与设备验收，不混入这次音频修复。 |
| [dart_simple_live `ba828e67`](https://github.com/xiaoyaocz/dart_simple_live/blob/ba828e6783b176ea5709fcd09f0eb01dfaceeb51/simple_live_app/lib/modules/live_room/player/player_controller.dart) | 播放器、控制条、弹幕与系统行为拆成不同 mixin；释放时取消播放／画中画订阅；根 LICENSE 为 GPL-3.0。 | 生命周期清理作为对照。Media 定位为自带源与私有库，不新增平台账号、弹幕适配或另一套 media_kit 播放器。 |
| [Voice `8af37b6d`](https://github.com/PaulWoitaschek/Voice/blob/8af37b6d0624f8ade142d182128d247db390a2ad/core/sleeptimer/impl/src/main/kotlin/voice/core/sleeptimer/SleepTimerImpl.kt) | 额外检索后选定的听书参考；独立定时状态、重新启用前取消旧任务、停止后的状态恢复；根 LICENSE.md 为 GPL-3.0。 | 采用单一生效定时和明确清理的原则。Voice 的按收听时长倒数、渐弱与摇动重启没有照搬；Media 保持现有墙钟截止时间语义，不增加传感器行为与设置。 |

具体轨道结束行为使用现有依赖中的 [Media3 ExoPlayer `setPauseAtEndOfMediaItems`](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer#setPauseAtEndOfMediaItems(boolean))，依据 `PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM` 收尾。它在播放器媒体边界处暂停，比应用收到下一轨切换回调后再调用暂停更符合“本轨结束”。

## 实际变化

- 新选择的队列跨音乐／听书模式时，重置随机和循环；同模式点播保留音乐控制。恢复音乐队列继续使用自己的随机／循环设置和 1× 播放，听书恢复原位置与倍速，并纠正旧版本误存的音乐随机／循环状态。恢复队列不自动播放、不提前解析网络地址。
- 恢复队列、成功替换播放列表、清空列表会解除旧睡眠控制；追加曲目保留当前定时。界面切换模块本身不调用这些操作，后台音频仍可继续。
- 本轨结束定时绑定当前队列实例 ID，启用 ExoPlayer 媒体边界暂停；暂停后解除一次性标记，继续播放可以正常越过边界。重复曲目、单曲循环与列表循环都不会把目标误认为下一次播放。
- 手动换轨／移除当前项会取消原轨道或章节目标，不把“当前”目标移动到另一首，也不因手动换轨而强制暂停。自动跨轨时仍保留章节结束的兜底暂停。
- 成功设置／关闭后立即发布定时状态。无当前条目、非法分钟数或没有可用章节结束点时返回失败；不先清除已有有效定时。

没有数据库 schema 或服务端协议变更。没有新增业务配置、真实服务地址、凭据或运行时依赖；测试新增项目已有版本的 Robolectric。

## 验证

服务级用例通过真实 MediaController 调用，不以模拟服务替代行为。Room 数据来自测试沙箱，静音源只用于有限时间线验收，关闭主机音频渲染，不请求用户内容源。

覆盖范围：

1. 音乐随机／单曲循环后打开听书；恢复音乐仍保留自己的设置且不自动播放。
2. 恢复其他模式、同模式替换、追加／清空队列时的定时归属。
3. 重复歌曲的不同实例，手动下一轨后解除原目标。
4. 空队列、非法分钟数与不可用章节不留下错误定时或清除已有定时。
5. 旧听书队列纠正随机／循环，同时保存位置、倍速及暂停状态。
6. Media3 有限静音时间线分别在顺序、单曲循环、列表循环下触发真实媒体结束回调：保持在第一轨暂停，解除标记后可继续进入第二轨。

验证命令使用 JDK 17：

```sh
./gradlew :experience:audio:testDebugUnitTest :core:playback:testDebugUnitTest \
  :core:library:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug --offline
```

共发现 251 项测试，249 项通过、0 失败，2 项既有真实 Novel 服务合同测试按条件跳过（未连接真实服务）。其中音频 27 项全部通过，新增服务级用例 9 项；播放核心 45 项通过，书库 134 项通过／2 项跳过，应用层 43 项通过。Lint 0 错误、39 条既有警告；`git diff --check` 通过。

## 剩余边界

本轮没有界面布局变化，不以主机时间线测试冒充真机音质或后台可靠性验收。蓝牙／耳机音频输出、锁屏通知、系统杀进程后的实际恢复、Android Auto／电视遥控仍待新版客户端设备验收；没有构建或安装 APK。章节定时仍沿用现有约一秒检查间隔，未宣称采样级精确暂停。

Novel 源调度优先级属于独立后端后续工作；本轮不改其合同或源码，不新增 Relay 依赖。其余音乐／影视候选未逐个安装或移植，避免在既有私有库播放器之外堆叠平台抓取功能。
