# 设备与协议验收

本表是执行步骤，不是已完成的设备报告。主机单测、Lint、测试源编译与真机结果分别记录。使用专用测试账号和 `fixtures/reading`，不要在报告中附 Token、完整带查询 URL 或私有路径。

## 固定环境

记录提交、APK SHA-256、系统/API、设备/ABI、屏幕/字体比例、网络类型及服务端版本。同一对比使用同一设备、样本和网络；至少各运行五次，报告中位与最慢样本。手机 API 26/35+、平板、Android TV 分别执行，不能用模拟器结果代替真机峰值内存/首帧结论。

| 体验 | 操作与必须结果 |
| --- | --- |
| 升级 | 每个历史 Room schema 执行 MigrationTestHelper；收藏、Locator、离线意图、重复队列实例保留，重复打开和外键检查通过 |
| 账号 | 同 serverId/userId 的两个不同 origin、反向代理路径各写进度/收藏；切换/删除期间延迟回包不能串写 |
| 视频 | Emby PlaybackInfo→302、冷存储、过期 403、拖动 Seek、暂停/后台/PiP；ISO/libmpv 独立验证，不因新目录/作品匹配增加预探测 |
| Feed | 关闭/开启实验对照；停稳预载下一条，快速连续滑动、移动网络、Data Saver、后台及断网时取消；ISO/直播/转码不预载 |
| 音乐 | 同曲重复入队、移动/删除实例、实际随机顺序、repeat、进程终止恢复；歌词切换、内嵌/同目录/服务端降级；音乐从头/1x，不污染听书进度 |
| 听书 | M4B 与 ABS 跨轨章节、毫秒时间 Seek、上一章跨轨、本章睡眠、倍速改变和手动 Seek 后仍按媒体位置停止 |
| 发声 | 音乐→视频→ISO→听书→TTS，通知/耳机/蓝牙/Android Auto 操作只让一个消费会话发声 |
| 离线 | Wi-Fi/充电约束、暂停/重试/重启/删除、旧回调、强 ETag/Last-Modified 改变、Range 不支持；只接受完整同 revision 副本，离线模式下新请求和已打开网络读取均停止 |
| 阅读 | 四个 EPUB 样本均打开、目录/搜索/选文/书签；200% 字体、字体 CORS、RTL 与 FXL；横竖屏/后台/进程重建后核对原 Locator |
| TXT | UTF-8/GB18030 转换后内容正确，原文件字节不变；手动编码改变重新生成阅读副本 |
| 漫画/PDF | 三页 CBZ 的长图/横图/RTL/双页切换，原 page+offset 保留；预取页取消不删除正在显示的文件；PDF 重排回原页、旋转、末页未读完不得完成 |
| 歌单 | Jellyfin/OpenSubsonic 混源与重复实例导出，检查实际顺序；创建超时保持未知，回读失败重试不再次创建，删除后迟到回调不恢复本地任务 |
| 无障碍 | 48dp 目标、200% 字体无覆盖/截断关键操作、TalkBack 有名称/状态；TV D-pad 可到达各入口并返回，空列表和加载失败无焦点死角 |

## 执行入口

先编译测试源，再在获准构建/安装测试包后执行：

```sh
./gradlew test lintDebug :core:database:compileDebugAndroidTestKotlin :experience:reading:compileDebugAndroidTestKotlin
./gradlew :core:database:connectedDebugAndroidTest :experience:reading:connectedDebugAndroidTest
```

第二条会生成并安装测试 APK，须遵守工作区对 APK 构建/设备执行的授权约定。未连接设备或未执行时，记录为“未执行”，不要填写通过。

## 性能与发布记录

保存冷/暖启动、首个本地内容、目录首屏、点播首帧、PlaybackInfo/302/响应头、缓冲次数和时长、切歌/音频恢复、阅读首章与首图、峰值 PSS。Feed 的预载/丢弃字节、命中/失败与 PSS 可在功能控制台查看；普通首帧和缓冲使用播放质量记录。样本很小时预取可能读取整个样本，不能据此推断长视频流量。

发布需另附签名检查、正式包不含 benchmark 测试入口、依赖/NOTICE 清单、升级安装、设备矩阵和 Baseline Profile 的真实输出。没有这些输出时不把代码接入标为发布验收完成。
