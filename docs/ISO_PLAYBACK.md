# ISO 原生播放

## 实现

Shadow Media 0.5.0 为 ISO 使用独立的应用内光盘后端：

```text
PlaybackInfo 静态 ISO 流
  → MpvIsoPlaybackRuntime
  → libmpv / libplayer.so
  → webhtv-dvdiso stream callback
  → IsoSessionManager
  → OkHttp Range + 32 MiB LRU
  → Emby / MediaWarp / 115 CDN
```

libbluray 和 libdvdnav 负责解释 DVD/Blu-ray 文件系统、标题、章节与片段映射。播放器不会把 ISO
伪装成 MPEG 再交给 Media3，也不会下载整个镜像。默认打开最长标题，续播位置通过 mpv 的光盘
时间轴恢复，播放中每 10 秒与暂停、恢复、退出事件一起写入 Emby PlaybackOutbox。

## 已支持

- ARM64-v8a 与 armeabi-v7a Android 设备；
- Emby 静态 ISO 流及 MediaWarp/对象存储 302；
- DVD ISO 和未加密 Blu-ray ISO 的最长标题；
- 播放、暂停、精确时间 Seek、上一/下一章节；
- 内嵌音轨和字幕的循环选择；
- MediaCodec 硬解优先、软件解码回退；
- 续播、Playing/Progress/Pause/Unpause/Stopped 上报；
- 不含 URL 或 Token 的应用内播放诊断；
- 原生引擎失败时重新获取一次 PlaybackInfo，仍失败可显式交给 VLC。

## Range 与缓存约束

原生光盘库会在镜像的不同区域随机读取。数据源为每次读取发出精确 bytes=start-end 请求，并要求
响应满足以下条件：

- 状态码为 206；
- Content-Range 起点与请求一致，总长度有效；
- 同一会话内文件长度保持不变；
- 上游提供 ETag 或 Last-Modified 时，验证器保持不变。

返回 200 代表上游忽略 Range，会直接显示诊断而不是把完整镜像读入内存。缓存使用 4 MiB 页、
最多 8 页的 LRU，总上限约 32 MiB；关闭页面时立即释放并取消在途请求。401/403、签名过期或
链路错误会交给现有的一次性 PlaybackInfo 自愈流程。

## 安全

会话 URI 形式为 webhtv-dvdiso://进程内ID/longest，不携带真实地址或凭据。OkHttp network
interceptor 会在每一次请求及每一跳 302 上重新判断 origin：

- 精确 Emby origin 添加 Emby 鉴权；
- 进入第三方 CDN 前移除 X-Emby-Token、X-Emby-Authorization、Authorization 和 Cookie；
- Range、User-Agent 与明确允许的非敏感播放头继续保留。

诊断页只显示最终上游 host、HTTP 状态、Range、缓存计数、大小、ABI 和编解码器，不显示完整 URL、
Token、Cookie 或响应头。

## 能力边界

- 不支持 x86/x86_64；这些设备会显示 ABI 诊断并可使用外部播放器兜底。
- 不提供 BD-J、完整 DVD/Blu-ray 菜单导航；当前选择最长标题。
- 不绕过 AACS、BD+、CSS 或其他 DRM/光盘加密，只支持用户有权访问的无 DRM 镜像。
- 3D MVC、Dolby Vision、TrueHD 等最终能力取决于镜像、设备解码器、Android 音频链路与显示器。
- 上游必须支持可靠随机 Range；仅能顺序下载的网关无法原生播放远程 ISO。
- 当前内存缓存不做离线保存，也不预取完整镜像。

## 第三方来源

原生基线和 Java MPV 桥来自 GPL-3.0 项目 WebHTV，固定版本、对应源码与二进制校验信息见
third_party/webhtv-mpv/NOTICE.md。Shadow Media 整体按 GPL-3.0 发布。
