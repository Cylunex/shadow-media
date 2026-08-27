# 路线图

## M0：播放兼容性验证（当前）

- [x] Emby 地址、用户名和密码登录
- [x] Keystore 加密会话恢复
- [x] 选择媒体库并获取 20 个视频
- [x] PlaybackInfo → DirectStream → Transcode 候选链
- [x] Media3 Compose Material 3 Player
- [x] Playing / 每 10 秒 Progress / Pause / Unpause / Stopped
- [x] 302 跨 origin Token 隔离
- [x] 基础播放诊断
- [ ] 真实设备兼容性矩阵与抓包验证
- [ ] 401/403、网络切换、长暂停的 PlaybackInfo 刷新状态机
- [ ] 音轨、内嵌字幕与外挂字幕选择

## M1：刷片 Feed

- [ ] VerticalPager 与页面驻留策略
- [ ] PlayerPool(3) + rememberPooledPlayer
- [ ] DefaultPreloadManager 共享 Builder
- [ ] 仅解析当前与后续 1–2 项播放地址
- [ ] 随机、最新、未看、收藏、继续观看
- [ ] Room 刷过记录、失败记录与 Feed 排重
- [ ] Paging 3 分页

## M2：播放器完整性

- [ ] 网络恢复和错误分类
- [ ] 诊断详情导出（自动脱敏）
- [ ] 画中画、MediaSession、锁屏控制
- [ ] 横竖屏和全屏
- [ ] 长按倍速、双击收藏与拖动手势

## M3：评估扩展

- [ ] 与 Shadow App 的 Deep Link / library 边界
- [ ] 特殊 ASS 或解码失败样本是否值得增加 libmpv 后端
- [ ] Jellyfin、TV 和其他平台需求重新评估
