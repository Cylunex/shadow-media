# Android TV 与播放接力

Shadow Media 同一 APK 同时声明移动端和 Android TV 启动入口，不要求触摸屏。电视模式下，可交互卡片
获得明确的焦点描边与轻微放大反馈；Feed 使用上下方向键切换，播放器使用左右方向键前后跳转 10 秒，
确认键或媒体播放键切换播放状态。ISO、Media3 和外部直播播放器遵循同一组按键语义。

移动端离开活动播放页时自动进入 16:9 画中画；应用内 MediaSession 继续承接系统媒体键。后续若需要
在活动完全退出后长期后台播放，再将播放器所有权迁移到 `MediaSessionService`，当前版本不会创建一个
与前台播放器相互竞争的第二实例。

详情页可以分享形如下面的接力链接：

```text
shadowmedia://play?provider=PROVIDER_ID&item=ITEM_ID&position=POSITION_MS
```

链接不包含 Token、Cookie 或真实播放 URL。接收设备必须已经配置相同 Provider；应用只打开统一详情，
不会因外部链接直接自动播放。这样既能完成手机到电视的轻量接力，也避免把服务器凭据写进剪贴板或
第三方消息应用。
