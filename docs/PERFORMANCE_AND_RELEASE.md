# 性能工程与发布门禁

对应 R7。已接入可运行的测试源和检查工具；本文件不是实际设备性能报告。尚无生成的 Baseline Profile、APK 签名结果或升级安装结果。

## 工程

- `benchmark` 是独立 `com.android.test` 工程；目标为 `top.cylunex.shadowmedia.benchmark`，采用 release 派生配置、混淆和测试签名，应用数据与正式包隔离。
- 只有 `app/src/benchmark` 声明测试 Application/准备 Activity 和回环 HTTP 服务器。种入一万首本地曲目、两个测试账号、分页视频与固定阅读样本；准备数据发生在计时区间外。
- Macrobenchmark 覆盖冷/暖启动、万曲滚动、视频目录/播放、EPUB/目录、音频队列和账号切换。UI 步骤缺失会失败，不能以空启动代替业务路径。
- `BaselineProfileGenerator` 分别采集 startup 和业务路径；`profileinstaller` 已接入正式应用。只有设备实际产生的文本才可导入，不手写“假” profile。
- AccessibilityJourneyTest 覆盖 200% 字体的关键操作、48dp 触控区域与阅读旋转；完整 TalkBack/TV/实际 Locator 检查仍执行 DEVICE_ACCEPTANCE.md。

API 35+ 设备优先用于 profile 采集；低版本设备按 Android 官方对 root/采集支持的要求配置。真实硬件性能与模拟器功能测试分别归档。[生成 Baseline Profile](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)、[Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)

## 仅源码验证，不生成 APK

```sh
./gradlew test lintDebug :app:compileBenchmarkKotlin :benchmark:compileBenchmarkKotlin :core:database:compileDebugAndroidTestKotlin :experience:reading:compileDebugAndroidTestKotlin :app:writeRuntimeInventory --offline
python3 scripts/dependency-inventory.py
python3 scripts/verify-release.py
```

`writeRuntimeInventory` 只解析正式 runtime 与 desugaring 的实际依赖，不触发打包；记录准确 Maven 坐标、artifact SHA-256、POM 继承许可证与包内 NOTICE。新增、升级依赖必须核对差异后执行 `dependency-inventory.py --update`；未知许可证或来源缺失直接失败。

完整 NOTICE 随应用资产分发，功能控制台提供离线查看。原生 MPV/FFmpeg 的固定源码、重建来源和二进制哈希仍由 `third_party/webhtv-mpv` 管理。补充许可正文来源见 `third_party/licenses/README.md`。本清单不将仅测试使用的固定字体算入正式 runtime。

## 获准构建/安装后才执行

下列 connected 任务会构建和安装 APK；遵守工作区约定，不能随普通代码提交自动执行。

```sh
./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=top.cylunex.shadowmedia.perftest.BaselineProfileGenerator
# 将上一步真实输出复制到本机工作目录后再导入；参数必须是实际生成文件。
python3 scripts/import-baseline-profile.py PATH_TO_GENERATED_STARTUP_PROFILE --startup
python3 scripts/import-baseline-profile.py PATH_TO_GENERATED_STARTUP_PROFILE PATH_TO_GENERATED_CRITICAL_PROFILE
./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=top.cylunex.shadowmedia.perftest.CriticalJourneyBenchmark
./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=top.cylunex.shadowmedia.perftest.AccessibilityJourneyTest
```

导入工具排除测试 Application/服务器/准备 Activity 的规则。生成后核对混淆映射，并分别以 `CompilationMode.None` 和安装 profile 的模式做同设备对比；当前测试工程的业务用例使用 Partial + warmup，不把暖机收益冒充 profile 收益。

输出归档记录提交、设备/API/ABI、样本清单哈希、网络、字体比例、编译模式、迭代数和原始 JSON/Perfetto。首帧、302、缓冲、预载字节和 PSS 同时从应用诊断与设备 trace 对照；帧时间用例不能代替解码首帧测量。

## 已有正式包的只读检查

```sh
python3 scripts/verify-release.py --apk PATH_TO_RELEASE_APK --expected-certificate-sha256 TRUSTED_CERTIFICATE_SHA256
```

工具不访问私钥、不签名、不安装、不上传。必须提供独立可信的证书摘要，检查应用 ID、debuggable、v2/v3 签名、真实 profile 存在、NOTICE 和测试资产/入口排除；不能根据包自带证书自我认定可信。正式包仍需历史版本升级安装、全部设备矩阵和协议验收通过后发布。
