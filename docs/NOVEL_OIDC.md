# 小说统一账号登录

在「来源 → Novel → 添加」输入自己的 HTTPS 小说服务地址，选择「统一账号」，点击「登录统一账号」。应用会打开系统浏览器，完成统一身份服务登录后返回原生书城；输入服务根域名或 `/novel/` 根均可，应用读取服务端公布的规范 API 根。

登录使用 AppAuth Authorization Code + PKCE（S256），独立 public client，不内嵌域名、client secret 或账号密码。服务端需要部署[Novel 原生认证协议](https://github.com/Cylunex/shadow-novel/blob/main/docs/authentication.md)。回调 URI 为 `com.shadow.media.novel:/oauth2redirect`。

## 应用标识与服务配置

- 应用 ID、模块包名和源码目录统一以 `com.shadow.media` 为根，不随部署域名变化。
- 服务域名只来自用户在「来源」中输入的地址；issuer、client ID、resource 与规范 API 根从该服务的 `auth/native/config` 读取。实际部署域名与凭据不进入仓库，服务端通过仓库外环境文件配置。
- 上述回调是应用协议标识，不是服务地址。客户端常量、Manifest、Novel 服务端返回值和身份服务的客户端注册必须一致；部署时需把身份服务回调注册同步为新值。
- 应用 ID 变更后，Android 将其视为独立应用，不能直接覆盖旧包或读取旧包的数据库、离线文件与 Keystore 凭据。旧安装和数据保留，新包需要重新配置来源并登录。

仓库忽略 `.env`、`.env.*`、`local.properties` 与 `*.local.properties`；只允许提交不含真实地址或凭据的 `.env.example` 等示例。应用运行时仍使用上述来源配置，不自动读取开发机环境文件。

## 会话与数据

- 登录事务含 state、nonce 和 PKCE verifier，使用 Android Keystore AES-GCM 加密并保存十分钟；浏览器返回时校验并一次性消费。进程重建后仍能读取原事务，过期、取消和旧回调不会恢复账号。
- AuthState（含 Access/Refresh Token）保存在原有加密连接仓中，Activity 结果只传本地连接 ID。服务端 `/auth/me` 验证成功后才绑定 issuer、subject 与客户端 ID。
- 书城、阅读包下载和后台进度同步共用凭据刷新。同一账号续期保留连接 ID、下载与待同步进度；不同 issuer/subject/client 或服务地址建立新作用域，原离线数据保留，不自动归属给新账号。
- Token 被服务端拒绝时只强制刷新、重试一次。永久失效显示重新登录入口；不会改用 Basic，也不会把认证失败伪装为缓存成功。
- 连接列表点击「退出」立即清除当前会话，并尽力向身份服务撤销凭据。旧刷新结果不能恢复已退出会话；其他产品的统一身份登录态不受影响。

旧私网和外层认证入口可继续选择「手动凭据」。它们不会自动变成 OIDC 账号；切换到统一账号时按新身份建立作用域，避免串联旧连接的进度。

## 验证范围

单元测试覆盖同账号续期与异账号隔离、加密持久化与进程重建、事务过期/重放、退出后回写、服务配置跨源校验，以及 Token 重试与下载认证。编译和 Manifest 合并检查覆盖登录 Activity、AppAuth 回调与原生页面。测试使用签名 fixture 和模拟 HTTP，不代替用户在真实设备上的首次浏览器登录验收；未因本次代码修改自动构建 APK。
