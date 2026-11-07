# Sheverying

Sheverying is an Android service manager based on Shizuku and Shevery. It can start the service through wireless debugging, try to restore it after startup, and expose a control interface for automation tools.

[简体中文](README.md)

## Features

- Start the Android system service through wireless debugging, with the root startup path provided by the project also available.
- Try to restore the service after device boot or an app update.
- Manage app authorization and retain Shizuku-compatible API, broadcast, and Provider interfaces.
- Expose an automation control interface that Tasker-like tools can use to request service startup.

Startup and background recovery depend on the Android version, device vendor, and system settings.

## Compatibility

- Application ID: `cn.ying.sherverying`.
- Retains the Shizuku-compatible authority `moe.shizuku.privileged.api` and related service interfaces.
- Minimum Android version: Android 11 (API 30).
- Current version: `14.6.4`, `versionCode` `70`. App, service, and API versions are read from the root `version.properties` file.

An update can be installed over an existing app only when it is signed with the same key. An APK signed with a different key requires uninstalling the existing app first, which may remove app data. Keep the release keystore secure and reuse it for upgrades to existing installations.

## Automation token

Connectors are enabled by default. The build embeds the token from the untracked `.automation-token` file and reuses it on subsequent builds from that checkout. Legacy random-token preferences are ignored. An explicitly disabled connector stays disabled.

CI uses the `RELEASE_AUTOMATION_TOKEN` secret. Internal phone deployments use a local build whose `.automation-token` must match Ding's embedded token. Both builds use the same release key but can have different tokens. Other integrations can copy their APK's token from Settings. Version `14.6.4` fixes the disabled default and supports upgrades over `14.6.3` signed with the current key, preserving pairing and permissions. Matching versions and signatures alone do not make a CI APK compatible with the internal Ding deployment.

## Build and test

Requirements: JDK 21, Android SDK, Android NDK `30.0.16248370`, and CMake `3.22.1`. The project includes the Gradle Wrapper.

Run unit tests and build the Debug version:

```shell
./gradlew :manager:testDebugUnitTest :manager:assembleDebug
```

Build the Release version:

```shell
./gradlew :manager:assembleRelease -PrequireReleaseSigning=true
```

Append `-PtargetAbi=arm64-v8a` to build an arm64-only APK. On Windows, use `gradlew.bat`.

Release builds require a valid keystore in local `signing.properties` and fail instead of falling back to Debug signing. Debug builds may still use Android's development signing configuration. Keep signing material out of the repository and reuse the same release key for upgrades.

The GitHub Actions tag workflow publishes releases using `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, and `RELEASE_AUTOMATION_TOKEN`. A `v*` tag matching `version.properties` runs tests, signs an arm64 APK with the release key, verifies its certificate, and publishes it. Internal Ding phones use a local build with a matching token. Download public releases from [Releases](https://github.com/ying-zhang/sheverying/releases).

## License and upstream projects

The main project is licensed under Apache-2.0. Shizuku, Nightzuku, icons, and third-party dependencies retain their respective copyrights and licenses; see the license files in the corresponding directories.

Upstream projects: [Shevery](https://github.com/HmnDev-Tech/shevery) · [Shizuku](https://github.com/RikkaApps/Shizuku)
