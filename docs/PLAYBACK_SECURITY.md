# 播放与凭据安全

## Token 的边界

Emby Token 只允许发送到登录时记录的精确 origin（scheme、host、port 均一致）。播放使用
OkHttp network interceptor，因此每一次真实网络请求——包括 302 后的请求——都会重新执行检查。

```text
Emby origin
  X-Emby-Token + X-Emby-Authorization

第三方 CDN origin
  删除 X-Emby-Token / X-Emby-Authorization / Authorization / Cookie
  保留 Media3 生成的 Range、User-Agent 和非敏感播放头
```

客户端不会无条件写入 `Range: bytes=0-`。普通视频由 Media3 依据 DataSpec 生成正确 Range；
ISO 数据源则按 libbluray/libdvdnav 的随机访问请求生成精确闭区间。ISO 上游必须返回 206 和匹配
的 Content-Range；200、越界响应、文件长度或验证器变化都会被拒绝，避免把错误字节交给解析器。
ISO 使用 32 MiB 上限的内存 LRU 页缓存，不将镜像或临时 CDN URL 写入磁盘。

## 本地存储

- 用户密码仅用于登录请求，不写磁盘；登录成功后立即从 UI state 清空。
- 多个 Access Token 连同服务器/用户绑定信息序列化后，由 Android Keystore 中的 AES-256 GCM key 加密。
- SharedPreferences 只保存随机 IV + ciphertext + authentication tag。
- 播放进度 Outbox 不保存 Token、请求头或临时播放 URL，只保存补报所需的 ID、事件和位置。
- ISO 默认由应用内 libmpv 后端读取，使用与 Media3 相同的逐跳鉴权策略；会话 URI 只含进程内
  随机 ID，不包含 URL、Token 或请求头。
- 用户主动选择 VLC 兜底时，外部进程必须获得本次读取授权。应用只允许给当前 Emby 精确 origin
  的静态流临时添加 `api_key`，定向发送给官方 VLC 包；不会把 Token 添加到第三方 CDN URL，也
  不保存这个授权 URL。用户移除 VLC 历史记录的行为由 VLC 自身控制。
- 日志和播放诊断不显示 Token、Authorization、Cookie、完整播放 URL 或响应头。

## HTTP

应用默认拒绝 HTTP。用户必须在登录页明确确认“受信任局域网 HTTP”，应用才会建立连接。
Manifest 保留 cleartext 能力，是因为 Android Network Security Config 不能在运行时按用户输入动态
放行任意局域网主机；业务层策略负责拒绝未经确认的 HTTP。

这只降低误用风险，不能让 HTTP 变安全。公网和非可信网络必须使用 HTTPS。
