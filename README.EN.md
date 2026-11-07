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
- Current version: `14.6.3`, `versionCode` `69`. App, service, and API versions are read from the root `version.properties` file.

An update can be installed over an existing app only when it is signed with the same key. An APK signed with a different key requires uninstalling the existing app first, which may remove app data. Keep the release keystore secure and reuse it for upgrades to existing installations.

## Automation token

On the first build, the project generates a random 128-bit token and stores it in the untracked `.automation-token` file. Later builds from the same working copy reuse that token. Each independent working copy generates its own token. When distributing one APK, users should configure the token shown in that APK's settings.

Do not publish a personal token. If an automation integration cannot connect, check that its configured token matches the value shown by the installed APK.

## Build and test

Requirements: JDK 21, Android SDK, Android NDK `30.0.16248370`, and CMake `3.22.1`. The project includes the Gradle Wrapper.

Run unit tests and build the Debug version:

```shell
./gradlew :manager:testDebugUnitTest :manager:assembleDebug
```

Build the Release version:

```shell
./gradlew :manager:assembleRelease
```

Append `-PtargetAbi=arm64-v8a` to build an arm64-only APK. On Windows, use `gradlew.bat`.

Release builds read the signing configuration from the local `signing.properties` file. Do not commit that file or the keystore. First-time builders can create their own signing key. APKs built by other contributors use their own signing keys and cannot replace builds signed with this project's release key.

Official releases are built only by the GitHub Actions tag workflow. Repository administrators must configure these GitHub Actions secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, and `RELEASE_AUTOMATION_TOKEN`. Pushing a `v*` tag matching `version.properties` runs unit tests, signs an arm64 APK with the release key, verifies its certificate fingerprint, and creates or updates the corresponding Release. If the key is missing or the signature does not match, publication fails instead of falling back to a Debug signature. Download official APKs from [Releases](https://github.com/ying-zhang/sheverying/releases).

## License and upstream projects

The main project is licensed under Apache-2.0. Shizuku, Nightzuku, icons, and third-party dependencies retain their respective copyrights and licenses; see the license files in the corresponding directories.

Upstream projects: [Shevery](https://github.com/HmnDev-Tech/shevery) · [Shizuku](https://github.com/RikkaApps/Shizuku)
