# 网络媒体库

Shadow Media 可以在没有 Emby 登录的情况下直接使用 OpenList、WebDAV 和 SMB 媒体库。每个连接都是
独立 `MediaProvider`，账号密码通过 Android Keystore + AES-GCM 加密保存，不写入日志、播放 URL 或
应用备份正文。

## 连接方式

### OpenList

- 地址填写站点根地址，例如 `https://openlist.example.com/`；部署在子路径时保留完整子路径。
- 填写 OpenList 用户名和密码；允许 Guest 浏览的站点可以留空。
- 根目录填写 OpenList 挂载路径，例如 `/115/影视`。
- 客户端使用 `/api/fs/list` 和 `/api/fs/get` 获取目录与文件能力，实际播放优先转换为稳定 `/d/`
  地址。这样播放器发起新的 Range 请求时会重新经过 OpenList，而不是持续使用已经过期的 115 CDN
  临时地址。必须代理的存储仍保留 OpenList 返回的 `/p/` 地址。

### WebDAV

- 地址填写完整 DAV 根地址；OpenList 默认是 `https://host/dav/`。
- 支持 Basic Auth、`PROPFIND Depth: 1`、Range 播放和目录分页。
- 播放凭据只在相同 scheme、host、port 下发送；302 跳到网盘 CDN 后自动移除。

### SMB

- 主机填写 IP、主机名或 `主机:端口`，共享名只填写 share 名，不包含路径。
- 支持 SMB2/SMB3、用户名/密码、可选域和子目录根路径。
- 视频通过内置随机读取 DataSource 播放，不需要在 URL 中暴露 SMB 密码，并支持播放器按偏移重新
  打开文件实现拖动和续播。
- ISO 通过同一套随机读接口桥接到内置 libmpv 光盘引擎，保留章节、音轨和可拖动时间线。

## 刮削目录

支持 Kodi、Jellyfin、Emby 常用 NFO 字段：标题、原始标题、简介、年份、评分、片长、季集编号、
类型、演员以及 TMDB/IMDb `uniqueid`。XML 解析禁用 DTD、外部实体和外部 Schema。

海报按以下顺序匹配：

1. `视频名-poster.jpg`、`视频名.jpg`；
2. `poster.jpg`、`folder.jpg`；
3. `视频名-thumb.jpg`、`thumb.jpg`；
4. OpenList 自身返回的缩略图。

影片按文件夹刮削时，会以最多 6 个并发请求读取子目录的 `movie.nfo`、`tvshow.nfo` 和海报，避免
同时压满 NAS 或网盘接口。图片写入应用缓存，凭据不会交给通用图片加载器。

## STRM

- 支持 UTF-8 文本，忽略空行和以 `#` 开头的注释，读取第一条有效地址。
- 支持 HTTP(S) 地址，以及相对当前媒体目录的文件路径。
- 指向 HTTP(S) ISO 或同一网络库内 ISO 的 STRM 会自动切换到光盘引擎。
- HTTP STRM 必须是 HTTPS；只有用户为该连接明确开启“允许局域网 HTTP”后才接受 HTTP。
- STRM 只保存于你的存储；Shadow Media 不复制或改写源文件。

网络媒体库的播放历史和续播位置写入本地 Room，以 `Provider + 文件路径` 隔离，不回写 Emby。
