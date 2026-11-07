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
- 当前版本为 `14.6.3`，`versionCode` 为 `69`。应用、服务和 API 版本从根目录 `version.properties` 读取。

覆盖安装需要 APK 使用与设备上已安装版本相同的签名密钥。使用不同密钥构建的 APK 需要先卸载旧版，卸载可能清除应用数据。若要继续给现有安装升级，请安全保管并使用同一 release keystore。

## 自动化令牌

首次构建时，项目会生成 128 位随机令牌并保存到未跟踪的 `.automation-token` 文件；同一工作副本的后续构建会继续使用该令牌。每个独立工作副本都会生成自己的令牌。分发同一个 APK 时，使用者应配置该 APK 设置页显示的令牌。

请勿公开提交个人令牌。若自动化集成无法连接，请先检查使用的令牌与已安装 APK 设置页中的值是否一致。

## 构建与测试

需要 JDK 21、Android SDK、Android NDK `30.0.16248370` 和 CMake `3.22.1`。项目使用 Gradle Wrapper。

运行单元测试并构建 Debug 版本：

```shell
./gradlew :manager:testDebugUnitTest :manager:assembleDebug
```

构建 Release 版本：

```shell
./gradlew :manager:assembleRelease
```

只构建 arm64 APK 时追加 `-PtargetAbi=arm64-v8a`。Windows 使用 `gradlew.bat`。

Release 构建会读取本机 `signing.properties` 中的签名配置；该文件和 keystore 不应提交到仓库。首次构建者可以创建自己的签名密钥。其他构建者生成的 APK 会使用各自的签名，不能用于覆盖安装由本项目 release key 签名的版本。

正式发布只通过 GitHub Actions 的版本标签流程完成。仓库管理员需要在 GitHub Actions Secrets 中配置 `RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 和 `RELEASE_AUTOMATION_TOKEN`。推送与 `version.properties` 一致的 `v*` 标签后，工作流会运行单元测试、使用 release key 构建 arm64 APK、校验证书指纹，并创建或更新对应 Release。签名不匹配或缺少密钥时，发布会失败，不会退回 debug 签名。正式 APK 请从 [Releases](https://github.com/ying-zhang/sheverying/releases) 下载。

## 许可证与上游

项目主体采用 Apache-2.0。Shizuku、Nightzuku、图标和第三方依赖保留各自版权及许可证；请查看对应目录中的许可文件。

上游项目：[Shevery](https://github.com/HmnDev-Tech/shevery) · [Shizuku](https://github.com/RikkaApps/Shizuku)
