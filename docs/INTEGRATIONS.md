# 服务连接

服务连接页负责把“发现、想看、入库、虚拟频道”连接起来，但不在 Android 客户端承担下载、整理、
频道排期或 IPTV 源管理。连接地址、API Key 和 Token 使用 Android Keystore + AES-GCM 加密保存，
日志、状态对象和界面不会返回凭据正文。

## 已支持

- MoviePilot：连接探测和管理界面入口。不同主版本的订阅写接口差异较大，客户端不猜测写入协议；
  具体订阅可在服务管理界面完成。
- Seerr：使用官方 `X-Api-Key` 认证探测 `/api/v1/status`；统一详情含 TMDB ID 时，可向
  `/api/v1/request` 提交电影或剧集想看请求。
- Tunarr：探测系统健康，并从服务根地址推导 `/api/channels.m3u` 与 `/api/xmltv.xml`，导入后
  直接复用 Shadow Media 的直播、EPG、多线路和回看链路。
- Dispatcharr：探测实例与 Swagger；M3U/XMLTV 输出地址由用户从 Dispatcharr 的 Channels 页面
  复制，客户端不会猜测用户、Profile 或访问控制参数。

所有 HTTP 地址都必须显式勾选“允许受信任局域网 HTTP”；HTTPS 降级重定向会被拒绝。接入 M3U 后，
播放仍使用不携带 Emby Token/Cookie 的外部网络客户端。

接口依据：

- MoviePilot API：<https://api.movie-pilot.org>
- Seerr OpenAPI：<https://github.com/seerr-team/seerr/blob/develop/seerr-api.yml>
- Tunarr：<https://github.com/chrisbenincasa/tunarr>
- Dispatcharr API：<https://dispatcharr.github.io/Dispatcharr-Docs/api/>
- Dispatcharr 频道输出：<https://dispatcharr.github.io/Dispatcharr-Docs/channels/>
