# Shadow Media

一个以 Emby 为后端、以原生播放为基础的 Android 刷片客户端。项目不登录网盘、不刮削媒体、
不维护第二套媒体库；它只负责内容发现、播放编排、原生播放与 Emby 状态同步。

当前 `0.5.0` 是可安装的刷片 MVP，已经打通这条闭环：

```text
选择或添加多个 Emby 登录
  → 选择媒体库
  → 分页读取媒体库全部视频并进入 VerticalPager Feed
  → 请求 PlaybackInfo
  → Direct Play / DirectStream / 302，失败后回退 HLS 转码
  → 普通媒体由 Media3 播放，ISO 由内置 libmpv 光盘引擎播放
  → Playing / Progress / Stopped 回写
```

## 已包含

- Kotlin 2.2、Jetpack Compose、Media3 1.11 和 OkHttp 的多模块 Android 工程；
- Emby 用户登录、媒体库、视频列表、`PlaybackInfo` 和播放状态上报接口；
- 多 Emby 服务器/用户管理：加密保存、快速切换和单独移除登录；
- 每个服务器、用户和媒体库独立保存稳定 Feed 顺序及上次刷片位置；
- 远端分页加载全部视频，不限制为前 20 条；
- 全屏垂直刷片 Feed：从任意条目进入，上下滑动时按需解析并切换播放器；
- Media3 Material 3 原生播放器与续播位置恢复；
- Emby 只返回媒体源能力、不返回 `DirectStreamUrl` 时构造标准静态流地址，直连失败再回退转码；
- 循环播放、系统音频焦点、拔出耳机暂停、后台暂停与前台恢复；
- 播放地址重新解析，以及不包含 Token 的播放诊断信息；
- 播放候选全部失败时自动刷新一次 `PlaybackInfo`，并从失败位置继续；
- 持久化播放进度 Outbox：网络失败后保留并在该服务器下次连接时补报；
- ISO/DVD/Blu-ray 镜像由内置 libmpv + libbluray/libdvdnav 光盘引擎直接读取，支持应用内续播、
  拖动、章节、音轨/字幕切换和 Emby 进度同步；
- ISO 远程读取采用严格 HTTP Range、容量受控的内存分页缓存和 302 后逐跳凭据隔离；VLC 仅保留
  为显式兜底；
- 独立可拖动进度条；转码链路通过 `StartTimeTicks` 实现服务端 Seek；
- 列表和 Feed 均支持经二次确认后从 Emby 媒体库及服务器文件系统永久删除条目；
- 精确 origin 鉴权隔离：Emby Token 不会跟随 302 请求发送到第三方 CDN；
- Android Keystore + AES-GCM 加密保存多账号会话 Token，并兼容旧版单账号数据迁移；
- HTTPS 默认策略，以及用户明确确认后的局域网 HTTP；
- 核心 URL、时间单位和请求头安全策略单元测试。

## 工程结构

```text
app/             应用入口、手动依赖注入、端到端验证 UI
core/model/      与 Android 无关的领域模型
core/network/    Emby API、DTO、仓库、Keystore 会话存储
core/playback/   Media3、libmpv ISO 引擎、302 请求头隔离、回退与播放上报
docs/            架构决策、播放安全与迭代路线
```

## 本地运行

要求 JDK 17、Android SDK 36 和 Build Tools 35.0.0：

```bash
./gradlew test
./gradlew assembleDebug
```

打开应用后输入自己的 Emby 地址和用户凭据。仓库不包含真实服务地址、Token 或签名材料。
HTTP 只应在受信任局域网内临时启用。

ISO 是光盘镜像而不是普通视频容器。Emby Server 不支持 ISO 转码，Media3 也不能解析光盘结构，
所以 ISO 页面使用内置的 GPL-3.0 libmpv 光盘后端，通过 libbluray/libdvdnav 对 Emby/MediaWarp/
115 返回的远程镜像执行随机 Range 读取。当前自动选择最长标题，支持 DVD 与无 DRM 的 Blu-ray；
加密商业光盘、BD-J 菜单、完整菜单导航和 x86/x86_64 设备不在支持范围。详见
[ISO 播放说明](docs/ISO_PLAYBACK.md)。

## 安装包

本地执行 `./gradlew assembleDebug` 会生成一个使用 Android Debug Key 签名、可直接安装测试的
`app/build/outputs/apk/debug/app-debug.apk`。正式发布前需要配置项目专用签名，仓库不会保存私钥。

删除功能调用 Emby 的文件删除接口，不是仅从 Feed 隐藏。执行前务必确认服务端备份和 Emby
用户的删除权限。

## 下一阶段

真实链路兼容性矩阵验证通过后，按 Media3 官方短视频示例引入容量为 3 的 `PlayerPool`、
`rememberPooledPlayer` 与共享
`DefaultPreloadManager.Builder`。最终 CDN URL 只解析当前项和后续 1–2 项，不落盘。

本项目整体以 GPL-3.0 发布。详见 [架构说明](docs/ARCHITECTURE.md)、
[ISO 播放说明](docs/ISO_PLAYBACK.md)、[第三方来源](third_party/webhtv-mpv/NOTICE.md)、
[参考实现与取舍](docs/REFERENCES.md) 与 [路线图](docs/ROADMAP.md)。
