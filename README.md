# Sheverying

Sheverying 是面向 Android 的 Shizuku / Shevery 服务管理器，基于 Shizuku 与 Shevery 项目构建。它支持通过无线调试启动服务、在启动后恢复服务，并提供供自动化工具调用的控制接口。

[English](README.EN.md)

## 主要功能

- 使用无线调试启动 Android 系统服务，也支持项目提供的 root 启动路径。
- 在设备重启或应用更新后尝试恢复服务。
- 管理应用授权，并提供 Shizuku 兼容的 API、广播和 Provider 接口。
- 提供自动化控制接口，可由 Tasker 类工具请求启动服务。

启动方式和后台恢复能力会受到 Android 版本、厂商系统及设备设置影响。

## 兼容性

- 应用 ID 为 `cn.ying.sherverying`。
- 保留 Shizuku 兼容 authority `moe.shizuku.privileged.api` 和相关服务接口。
- 最低支持 Android 11（API 30）。
- 当前版本为 `14.6.4`，`versionCode` 为 `70`。应用、服务和 API 版本从根目录 `version.properties` 读取。

覆盖安装需要 APK 使用与设备上已安装版本相同的签名密钥。使用不同密钥构建的 APK 需要先卸载旧版，卸载可能清除应用数据。若要继续给现有安装升级，请安全保管并使用同一 release keystore。

## 自动化令牌

连接器默认开启。自动化令牌在构建时从未跟踪的 `.automation-token` 文件嵌入 APK，同一工作副本重复构建会复用该值，旧的随机令牌偏好不参与认证。显式关闭过连接器的用户设置仍会保留。

CI 发布使用 `RELEASE_AUTOMATION_TOKEN` Secret；内部手机部署使用本地构建，其 `.automation-token` 必须与 Ding 的内置令牌一致。两种 APK 的令牌可以不同，但沿用同一 release key。第三方自动化工具可从设置页复制对应 APK 的令牌。

`14.6.4` 修复连接器默认关闭的问题。内部本地构建还会核对与 Ding 的令牌兼容性；使用当前 release key 签名的 `14.6.3` 可直接覆盖更新，保留配对和权限。不能仅因签名和版本一致就把 CI APK 当作内部部署包。

## 构建与测试

需要 JDK 21、Android SDK、Android NDK `30.0.16248370` 和 CMake `3.22.1`。项目使用 Gradle Wrapper。

运行单元测试并构建 Debug 版本：

```shell
./gradlew :manager:testDebugUnitTest :manager:assembleDebug
```

构建 Release 版本：

```shell
./gradlew :manager:assembleRelease -PrequireReleaseSigning=true
```

只构建 arm64 APK 时追加 `-PtargetAbi=arm64-v8a`。Windows 使用 `gradlew.bat`。

Release 构建读取本机 `signing.properties` 中的签名配置，缺少有效 keystore 时直接失败，不回退到 debug 签名；Debug 构建仍可使用 Android 的开发签名。该配置和 keystore 不应提交到仓库。覆盖现有安装必须沿用同一 release key。

正式发布通过 GitHub Actions 的版本标签流程完成。仓库管理员需要配置 `RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 和 `RELEASE_AUTOMATION_TOKEN` 五项 Secrets。推送与 `version.properties` 一致的 `v*` 标签后，工作流运行单元测试、使用 release key 构建 arm64 APK、校验证书指纹，并创建或更新对应 Release。正式 APK 请从 [Releases](https://github.com/ying-zhang/sheverying/releases) 下载；内部 Ding 手机使用令牌匹配的本地构建。

## 许可证与上游

项目主体采用 Apache-2.0。Shizuku、Nightzuku、图标和第三方依赖保留各自版权及许可证；请查看对应目录中的许可文件。

上游项目：[Shevery](https://github.com/HmnDev-Tech/shevery) · [Shizuku](https://github.com/RikkaApps/Shizuku)
