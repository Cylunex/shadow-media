# WebHTV MPV/ISO 来源声明

Shadow Media 的应用内 ISO 后端包含和改编了 WebHTV 的播放组件。

- 上游项目：https://github.com/fish2018/WebHTV
- 固定提交：4b50754d3a2902eb4f94361669aa52079f3a2917
- 上游许可证：GNU General Public License v3.0
- 获取日期：2026-08-27

采用内容：

- app/src/main/java/is/xyz/mpv/MPVLib.java：MPV JNI Java 桥和应用资产加载器；
- app/src/*/assets/mpv-libs/{arm64-v8a,armeabi-v7a}：成套 MPV、FFmpeg 和 JNI 原生库；
- app/src/main/java/com/fongmi/android/tv/player/iso 的会话接口约定；
- third_party/mpv-player-jni 的 stream callback、libbluray/libdvdnav 光盘控制设计。

Shadow Media 重新实现了与自身 Emby/OkHttp 模型适配的 IsoSessionManager、严格 Range 验证、
容量受控缓存、逐跳凭据隔离、Compose 控件、播放诊断和 Emby 状态上报。原生二进制未修改。

对应源码可从上述固定提交取得；native 精确依赖版本保存在 mpv-native-lock.json。JNI 源码位于
该提交的 third_party/mpv-player-jni，完整 native 重建说明位于 third_party/mpv-native-build.md
和 scripts/build_mpv_native.sh。上游 MPV JNI 桥单独保留其 MIT 声明，见 MPVLIB-LICENSE。

这些文件以及本项目对它们的改编按 GPL-3.0 条款分发。本目录的 SHA256SUMS 可用于确认本仓库
二进制与固定上游提交中资产一致。各底层库仍保留各自许可证；固定依赖来源见 lock 文件。
