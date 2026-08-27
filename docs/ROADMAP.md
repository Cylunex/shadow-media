# 路线图

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
- [ ] ConnectivityManager 网络恢复与长暂停主动刷新
- [x] ISO 音轨与内嵌字幕循环选择
- [ ] 普通视频音轨、内嵌字幕与外挂字幕选择

## M1：刷片 Feed

- [x] VerticalPager 与单活跃播放器页面驻留策略
- [x] 按服务器/用户/媒体库隔离的稳定 FeedSession 与上次位置恢复
- [ ] PlayerPool(3) + rememberPooledPlayer
- [ ] DefaultPreloadManager 共享 Builder
- [ ] 仅解析当前与后续 1–2 项播放地址
- [ ] 随机、最新、未看、收藏、继续观看
- [ ] Room 刷过记录、失败记录与 Feed 排重
- [ ] Paging 3 分页

## M2：播放器完整性

- [x] 循环播放、音频焦点、耳机拔出暂停和前后台切换
- [x] 失败诊断与重新解析播放地址
- [x] 独立进度条与直链/转码双模式 Seek
- [x] 二次确认后的 Emby 条目及文件删除
- [ ] 网络恢复和错误分类
- [ ] 诊断详情导出（自动脱敏）
- [ ] 画中画、MediaSession、锁屏控制
- [ ] 横竖屏和全屏
- [ ] 长按倍速、双击收藏与拖动手势

## M3：评估扩展

- [ ] 与 Shadow App 的 Deep Link / library 边界
- [x] libmpv 作为 ISO 专用后端
- [ ] 是否让特殊 ASS 或 Media3 解码失败样本手动切换到 libmpv
- [ ] Jellyfin、TV 和其他平台需求重新评估
