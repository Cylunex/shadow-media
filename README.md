# Shadow Media

一个以 Emby 为后端、以原生播放为基础的 Android 刷片客户端。项目不登录网盘、不刮削媒体、
不维护第二套媒体库；它只负责内容发现、播放编排、原生播放与 Emby 状态同步。

当前是第一个可运行骨架，目标是先验证这条最小闭环：

```text
Emby 登录
  → 选择媒体库
  → 读取最近 20 个视频
  → 请求 PlaybackInfo
  → DirectStream / 302，失败后回退 HLS 转码
  → Media3 播放
  → Playing / Progress / Stopped 回写
```

## 已包含

- Kotlin 2.2、Jetpack Compose、Media3 1.11 和 OkHttp 的多模块 Android 工程；
- Emby 用户登录、媒体库、视频列表、`PlaybackInfo` 和播放状态上报接口；
- Media3 Material 3 原生播放器与续播位置恢复；
- 直连优先、转码候选回退，以及不包含 Token 的播放诊断信息；
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

## 下一阶段

端到端兼容性矩阵验证通过后，再实现 Compose `VerticalPager` Feed，并按 Media3 官方短视频
示例引入容量为 3 的 `PlayerPool`、`rememberPooledPlayer` 与共享
`DefaultPreloadManager.Builder`。最终 CDN URL 只解析当前项和后续 1–2 项，不落盘。

详见 [架构说明](docs/ARCHITECTURE.md) 与 [路线图](docs/ROADMAP.md)。
