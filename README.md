# Shadow Media

一个以 Emby 为后端、以原生播放为基础的 Android 刷片客户端。项目不登录网盘、不刮削媒体、
不维护第二套媒体库；它只负责内容发现、播放编排、原生播放与 Emby 状态同步。

当前 `0.2.0` 是可安装的刷片 MVP，已经打通这条闭环：

```text
Emby 登录
  → 选择媒体库
  → 读取最近 20 个视频并进入 VerticalPager Feed
  → 请求 PlaybackInfo
  → Direct Play / DirectStream / 302，失败后回退 HLS 转码
  → Media3 播放
  → Playing / Progress / Stopped 回写
```

## 已包含

- Kotlin 2.2、Jetpack Compose、Media3 1.11 和 OkHttp 的多模块 Android 工程；
- Emby 用户登录、媒体库、视频列表、`PlaybackInfo` 和播放状态上报接口；
- 全屏垂直刷片 Feed：从任意条目进入，上下滑动时按需解析并切换播放器；
- Media3 Material 3 原生播放器与续播位置恢复；
- Emby 未返回 `DirectStreamUrl` 时构造标准静态流地址，直连失败再回退转码；
- 循环播放、系统音频焦点、拔出耳机暂停、后台暂停与前台恢复；
- 播放地址重新解析，以及不包含 Token 的播放诊断信息；
- 精确 origin 鉴权隔离：Emby Token 不会跟随 302 请求发送到第三方 CDN；
- Android Keystore + AES-GCM 加密保存会话 Token；
- HTTPS 默认策略，以及用户明确确认后的局域网 HTTP；
- 核心 URL、时间单位和请求头安全策略单元测试。

## 工程结构

```text
app/             应用入口、手动依赖注入、端到端验证 UI
core/model/      与 Android 无关的领域模型
core/network/    Emby API、DTO、仓库、Keystore 会话存储
core/playback/   Media3、302 请求头隔离、回退与播放上报
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

## 安装包

本地执行 `./gradlew assembleDebug` 会生成一个使用 Android Debug Key 签名、可直接安装测试的
`app/build/outputs/apk/debug/app-debug.apk`。正式发布前需要配置项目专用签名，仓库不会保存私钥。

## 下一阶段

真实链路兼容性矩阵验证通过后，按 Media3 官方短视频示例引入容量为 3 的 `PlayerPool`、
`rememberPooledPlayer` 与共享
`DefaultPreloadManager.Builder`。最终 CDN URL 只解析当前项和后续 1–2 项，不落盘。

详见 [架构说明](docs/ARCHITECTURE.md) 与 [路线图](docs/ROADMAP.md)。
