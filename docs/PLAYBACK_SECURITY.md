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

客户端不会无条件写入 `Range: bytes=0-`。Media3 会依据 seek/read 的 DataSpec 生成正确 Range；
覆盖它会破坏续播、拖动和分段读取。

## 本地存储

- 用户密码仅用于登录请求，不写磁盘；登录成功后立即从 UI state 清空。
- Access Token 连同服务器/用户绑定信息序列化后，由 Android Keystore 中的 AES-256 GCM key 加密。
- SharedPreferences 只保存随机 IV + ciphertext + authentication tag。
- 日志和播放诊断不显示 Token、Authorization、Cookie、完整播放 URL 或响应头。

## HTTP

应用默认拒绝 HTTP。用户必须在登录页明确确认“受信任局域网 HTTP”，应用才会建立连接。
Manifest 保留 cleartext 能力，是因为 Android Network Security Config 不能在运行时按用户输入动态
放行任意局域网主机；业务层策略负责拒绝未经确认的 HTTP。

这只降低误用风险，不能让 HTTP 变安全。公网和非可信网络必须使用 HTTPS。
