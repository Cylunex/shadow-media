# 来源扩展与音质方案评估

核查日期：2026-09-11。对应正式方案 R3 音质评估、R6 Kavita/RSS/可选 Runtime 评估。这些评估项不等于本轮承诺接入全部运行时；下面区分已经采用的实现与尚未开放的能力。

## 音频

| 能力 | 本轮决定与实现 | 后续开放条件 |
| --- | --- | --- |
| 连续队列 / 下一首准备 | 一个 ExoPlayer 持有完整队列，使用 `PreloadConfiguration(3_000_000)`；当前项 P0、相邻项 P1，音乐模式、非计费网络和设备允许时启用 | 真机统计切歌耗时、额外字节和功耗；有声书继续保持章节/睡眠语义 |
| Gapless | 使用 Media3 解码链和编码文件自身的 delay/padding 元数据，不在应用层插入静音或伪造“绝对无缝”标识 | AAC/MP3/FLAC/Opus 的专辑连续样本，包含错误或缺失 gapless 标签；耳机/蓝牙分别记录实际间隔 |
| Audio offload | 不强制启用，也不把 offload 当作音质等级；保留当前兼容解码路径 | 设备格式支持、gapless 和倍速条件均满足才可独立实验；故障必须能回到普通输出 |
| ReplayGain | 暂不改写音量；标签采集与解码格式信息不能冒充已应用增益 | 增加 track/album 模式、峰值限制和无标签回退；实际 PCM 与响度样本验证 |
| EQ / crossfade | 不新增第二发声服务或混音播放器；首版保留系统音频焦点和统一输出 | EQ 需处理会话 ID 重建与 offload 冲突；crossfade 需混音及重复实例/随机顺序/计数边界的独立设计与功耗证据 |
| HTTP 引擎 | 保留 OkHttp、范围验证和逐跳凭据隔离；不为“新”而替换传输层 | 在同一冷存储、302、弱网与 CDN 样本上比较首响应、首帧、取消时延和包体开销，再考虑 Cronet/平台引擎 |

Media3 官方说明：播放列表预载只在当前播放没有加载媒体时启动，适合线性队列；动态 Feed 则使用共享 Builder 的 DefaultPreloadManager。这两条实现分开配置。[预加载介绍](https://developer.android.com/blog/posts/elevating-media-playback-introducing-preloading-with-media3-part-1?hl=en)

Media3 发布说明持续修正 offload 与 gapless 的组合行为，因此版本升级和支持声明必须有设备证据。当前继续采用已接入的 1.11.0，不增加音效依赖。[Media3 发布说明](https://developer.android.com/jetpack/androidx/releases/media3)

## Kavita

采用现有 OPDS 接入用于浏览和显式获取出版物。Kavita 的 OPDS 地址可能直接携带用户 Auth Key；因此不能只过滤查询参数。本轮将 OPDS entry、目录和翻页地址转换为按账号绑定的不透明引用，原始定位存入 Android Keystore 加密的连接级存储；下载地址仍在执行时重新解析。旧书架只改 itemId，保留 assetId、书签、队列和原进度；含原始网络定位的旧临时快照作废后可刷新。

原生 Kavita API 的优势在阅读列表、丰富筛选和进度写回；若后续引入，应增加一个 Provider adapter，使用独立 `x-api-key`，探测到期时间，并按版本保存 page/章节 Locator。当前不声称 OPDS 已提供 Kavita 原生进度同步，也不将 OPDS-PS 直接标为 Komga。[Kavita OPDS](https://wiki.kavitareader.com/guides/features/opds/)、[Kavita API](https://wiki.kavitareader.com/guides/api/)

## RSS / 播客

设计落点是 `ContentKind.PODCAST`、现有连续音频服务和本地订阅目录；RSS 本身不提供统一的收藏/进度写回。订阅实现必须使用订阅作用域 + GUID，不把 enclosure URL 当稳定内容 ID。没有 GUID 时需明确的确定性回退；重复 GUID、enclosure 替换、重定向、缺少长度、服务器忽略条件请求均进入测试矩阵。

只接受可消费的音频 enclosure；解析器禁用外部实体并限制响应体/条目数；条件 GET 的 ETag/Last-Modified 与音频资源自己的 revision 分开。订阅刷新归入 P4，离线下载仍须显式操作，空订阅刷新不能删除历史。首轮不加入自动订阅导入/自动下载，也不宣称已提供 RSS 订阅页面。[RSS 2.0 当前规范](https://www.rssboard.org/rss-specification)、[RSS 互操作建议](https://www.rssboard.org/rss-profile)

## 可选 Runtime

继续保留声明式 HTTP 来源与外部集成；没有引入任意 JS/JAR/CatVod 执行器。若需要运行时，应单独定义进程隔离、能力授予、网络凭据范围、超时/取消、崩溃恢复、ABI 和许可清单；不能让来源脚本直接访问主应用 Room、Keystore 或播放器。当前 Provider 能力矩阵只开放实际实现的浏览、解析与状态操作。

这是基于现有架构与维护成本的工程取舍。不会因为参考项目有某个功能就复制其运行时、许可证受限代码或独立用户状态体系。
