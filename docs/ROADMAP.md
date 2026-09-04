# 路线图

## 全媒介开发分支（2026-09）

实际入口、已执行检查与尚未完成的长期项见 [全媒介实施说明](MULTIMEDIA_IMPLEMENTATION.md)。
下方 M0～M6 是原视频功能清单，不将旧待办自动改成已完成。

- [x] 五入口壳、深浅色外观、无 Emby 首启
- [x] 类型化打开与进度契约、Room v4 增量结构、原 Outbox 导入
- [x] EPUB/TXT 小说、PDF 基础翻页、目录/搜索/书签/笔记/高亮与离线朗读
- [x] CBZ/ZIP、图片目录、Komga 按页与基础离线副本
- [x] 后台音频、倍速/睡眠/轨道/书签、ABS 与 Emby Audio
- [x] 多 Emby/已导入书库聚合、增量搜索、来源级重试和继续翻页
- [x] OPDS、Komga/ABS 首次远端进度与本机待发送队列
- [ ] 手机/平板/TV 真机与真实服务完整验收
- [ ] Work/Rendition 整理、统一继续与更新关注
- [ ] 全格式内嵌音频章节、完整离线任务中心、连续 Webtoon
- [ ] Suwayomi/Stremio 等可选 Runtime 与精确读听对齐等长期扩展

## M0：可播放 MVP（当前）

- [x] Emby 地址、用户名和密码登录
- [x] Keystore 加密的多服务器/多用户会话、切换与旧数据迁移
- [x] 远端分页获取媒体库全部视频
- [x] PlaybackInfo → Direct Play / DirectStream → Transcode 候选链
- [x] DirectStreamUrl 缺失时的标准静态流兜底
- [x] 应用内 libmpv DVD/Blu-ray ISO 直读、续播、Seek、章节、音轨/字幕与进度同步
- [x] ISO 严格 Range、内存页缓存、302 凭据隔离和 VLC 显式兜底
- [x] Media3 Compose Material 3 Player
- [x] Playing / 每 10 秒 Progress / Pause / Unpause / Stopped
- [x] 持久化播放进度 Outbox 与按服务器补报
- [x] 302 跨 origin Token 隔离
- [x] 基础播放诊断
- [ ] 真实设备兼容性矩阵与抓包验证
- [x] 候选耗尽后刷新一次 PlaybackInfo 并从原位置恢复
- [x] OpenList/115 冷缓存 302 延迟、临时 CDN 403/429/5xx 与受控并发兼容
- [ ] ConnectivityManager 网络恢复与长暂停主动刷新
- [x] ISO 音轨与内嵌字幕循环选择
- [x] 普通视频音轨与内嵌字幕循环选择
- [ ] 外挂字幕导入、样式与延迟调节

## M1：刷片 Feed

- [x] VerticalPager 与单活跃播放器页面驻留策略
- [x] 按服务器/用户/媒体库隔离的稳定 FeedSession 与上次位置恢复
- [ ] PlayerPool(3) + rememberPooledPlayer
- [ ] DefaultPreloadManager 共享 Builder
- [ ] 仅解析当前与后续 1–2 项播放地址
- [x] 媒体中心的最近新增、收藏、继续观看投影
- [ ] Feed 内的随机、最新、未看、收藏模式切换
- [x] Room 历史、收藏、播放指标、线路健康和功能开关底座
- [ ] Room 刷过记录、失败记录与 Feed 排重
- [ ] Paging 3 分页

## M2：播放器完整性

- [x] 循环播放、音频焦点、耳机拔出暂停和前后台切换
- [x] 失败诊断与重新解析播放地址
- [x] 独立进度条与直链/转码双模式 Seek
- [x] 二次确认后的 Emby 条目及文件删除
- [ ] 网络恢复和错误分类
- [x] 诊断详情导出（自动脱敏）
- [x] 应用内画中画入口与活动 MediaSession
- [ ] MediaSessionService、通知与锁屏控制
- [ ] 横竖屏和全屏
- [ ] 长按倍速、双击收藏与拖动手势
- [x] 普通视频与 ISO 的媒体时刻保存
- [x] 用户片头/片尾标记与命中片段一键跳过

## M3：媒体中心与封面墙

- [x] Emby 首页内容行
- [x] 服务端分页封面墙
- [x] 库内搜索、状态筛选、排序与随机换一批
- [x] 电视剧/合集子项详情与连续播放
- [x] 收藏写回 Emby
- [ ] 详情背景图、媒体版本、演员与相关推荐
- [ ] 普通视频音轨/字幕预选与详情展示
- [ ] Paging 3 与 Room 页面快照

## M4：外部源与直播

- [x] TVBox JSON、M3U、TXT 的安全检查与订阅元数据存储
- [x] 运行时站点识别；主进程禁止执行 JAR/QuickJS/Python/WebView 嗅探
- [x] 声明式 HTTP Provider 的搜索、详情、选集与播放
- [x] M3U/TXT 频道浏览与 Media3 直播播放
- [x] XMLTV EPG 与基础回看
- [x] 同名频道多线路、搜索、分组与本地收藏
- [ ] 多线路手动/自动换源与健康度
- [ ] 独立 CatVod Source Runtime 协议
- [x] OpenList 原生 API 媒体库与稳定 302 下载入口
- [x] 通用 WebDAV 目录、Range 播放和 Basic Auth 隔离
- [x] SMB2/SMB3 目录浏览与可 Seek 随机读取播放
- [x] NFO 元数据、同目录海报与 STRM 解析

## M5：评估扩展

- [ ] 与 Shadow App 的 Deep Link / library 边界
- [x] libmpv 作为 ISO 专用后端
- [ ] 是否让特殊 ASS 或 Media3 解码失败样本手动切换到 libmpv
- [x] Android TV 启动入口、自适应 UI 与 D-pad 焦点反馈
- [x] 播放器遥控切片、Seek 和播放/暂停快捷键
- [ ] Jellyfin、Plex 和其他平台 Provider 需求重新评估

## M6：扩展平台

- [x] 统一 MediaProvider / MediaKey / UnifiedMediaItem / PlaybackRequest
- [x] ProviderRegistry 与能力声明
- [x] Room 2.8 数据库和 PagingSource
- [x] 功能开关控制台
- [x] 播放遥测与线路健康数据模型
- [x] EmbyProvider 迁移到统一协议
- [x] 直播与声明式 HTTP Provider
- [ ] Stremio 与 CatVod Bridge Provider
- [x] 跨 Provider 聚合搜索、部分失败隔离与统一详情
- [x] 跨设备 Deep Link 接力与自动画中画
- [x] MoviePilot、Seerr、Tunarr、Dispatcharr 加密连接与健康探测
- [x] Seerr 标准想看请求与 TMDB ID 映射
- [x] Tunarr/Dispatcharr M3U、XMLTV 导入直播中心
- [x] 基于续播、收藏、已看状态与评分的本地可解释推荐
- [x] 媒体记忆页、播放聚合统计与线路健康面板
