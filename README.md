# MusicBleController

<p align="center">
  <strong>Turn a Sony Android Walkman into a Bluetooth music bridge for iPhone.</strong><br>
  <strong>让 iPhone 实时获取 Sony Android 播放器上的 QQ 音乐歌词、封面和播放状态。</strong>
</p>

<p align="center">
  <img alt="iOS 18+" src="https://img.shields.io/badge/iOS-18%2B-000000?logo=apple">
  <img alt="Android 6+" src="https://img.shields.io/badge/Android-6%2B-3DDC84?logo=android&logoColor=white">
  <img alt="SwiftUI" src="https://img.shields.io/badge/SwiftUI-ActivityKit-F05138?logo=swift&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-BLE-7F52FF?logo=kotlin&logoColor=white">
</p>

MusicBleController is an open-source iPhone companion for Sony Walkman devices
running Android. A lightweight Android agent reads QQ Music playback metadata,
QRC lyrics and album art, then streams them to a native SwiftUI app over
Bluetooth Low Energy (BLE). The iPhone app provides playback controls, synced
lyrics, artwork caching, Dynamic Island and Live Activities.

> [!IMPORTANT]
> The current implementation supports **QQ Music on Android only**. It is an
> unofficial community project and is not affiliated with Sony, Apple, Tencent
> or QQ Music.

## Preview

<p align="center">
  <img src="docs/assets/ios_main_player_ui_from_code.svg" width="720" alt="MusicBleController iPhone player UI">
</p>

<details>
  <summary>More UI and diagnostic previews</summary>
  <p align="center">
    <img src="docs/assets/ios_preferences_diagnostics_ui_from_code.svg" width="720" alt="iOS preferences and diagnostics UI">
    <img src="docs/assets/sony_playeragent_debug_ui_from_code.svg" width="720" alt="Sony PlayerAgent debug UI">
  </p>
</details>

## Why this project?

Sony's Android-based Walkman players can run QQ Music, but their playback state
does not naturally integrate with an iPhone. This project connects the two
devices without a cloud service:

- **Synced QQ Music lyrics** — parses encrypted QRC lyrics, including word-level
  timing, translation and romanization where available.
- **Fast album art** — preview-first BLE delivery, HQ background upgrades,
  CRC validation and stale-while-revalidate caching.
- **Remote playback controls** — play/pause, next, previous, seek and volume.
- **Native iPhone experience** — SwiftUI player, full-screen lyrics, lock-screen
  Live Activity and Dynamic Island support.
- **Connection recovery** — capability negotiation, health checks, reconnect
  protection and old-client protocol fallback.
- **Built for slow hardware** — priority-based BLE scheduling, compressed lyric
  transfers and indexed QRC caches reduce work on the Walkman.
- **Observable and testable** — on-device diagnostics plus iOS, Android and
  cross-device smoke-test suites.

## How it works

```mermaid
flowchart LR
    QQ["QQ Music on Sony Walkman"] --> Agent["PlayerAgent · Kotlin"]
    Agent -->|"BLE · lyrics / artwork / state"| iOS["iPhone app · SwiftUI"]
    iOS -->|"BLE · controls / seek / volume"| Agent
    iOS --> Live["Live Activity / Dynamic Island"]
```

The Sony device is the authoritative media source. `PlayerAgentApp` reads
MediaSession, notifications and QQ Music's local QRC cache, while the iOS app
acts as the BLE controller, cache and presentation layer. No external server is
required.

## Compatibility

| Component | Requirement |
|---|---|
| Music source | QQ Music for Android |
| Sony side | Android 6.0+; tested on Sony NW-WM1AM2 (Android 11) |
| iPhone side | iOS 18.0+ with Bluetooth enabled |
| Development | Android Studio/JDK and Xcode with an Apple Development team |

Other Android-based Sony Walkman models may work, but hardware and firmware
differences have not all been validated. Real-device testing is required; the
iOS Simulator cannot validate the BLE media path.

## Getting started

### 1. Build the Sony PlayerAgent

```bash
git clone https://github.com/a3384379/MusicBleController.git
cd MusicBleController
bash gradlew :PlayerAgentApp:assembleDebug
```

Install `PlayerAgentApp/build/outputs/apk/debug/PlayerAgentApp-debug.apk` on the
Sony player. Grant notification access, enable the required accessibility
service, then start the PlayerAgent foreground service.

### 2. Build the iPhone app

1. Open `IOSBleFeasibility/IOSBleFeasibility.xcodeproj` in Xcode.
2. Select your Apple Development team for the app and Live Activity extension.
3. Replace the sample bundle identifiers and App Group with identifiers owned
   by your team.
4. Build and install the app on a physical iPhone running iOS 18 or later.
5. Keep both devices nearby, open the iPhone app and connect to
   `SonyPlayerAgent`.

For deeper implementation and troubleshooting details, see:

- [Project overview](docs/PROJECT_OVERVIEW.md)
- [BLE protocol](docs/BLE_PROTOCOL.md)
- [Lyrics architecture](docs/LYRICS_ARCHITECTURE.md)
- [Album-art architecture](docs/ALBUM_ART_ARCHITECTURE.md)
- [Smoke-test guide](docs/SMOKE_TEST_GUIDE.md)

## Repository layout

| Path | Purpose |
|---|---|
| `PlayerAgentApp/` | Sony/Android media agent and BLE GATT server |
| `IOSBleFeasibility/` | Native iPhone app and Live Activity extension |
| `ControllerApp/` | Legacy Android controller, retained for compatibility |
| `docs/` | Protocol and architecture documentation |
| `tools/` | iOS, Android and cross-device smoke tests |

## Validation

```bash
# Android unit tests and debug build
bash gradlew :PlayerAgentApp:testDebugUnitTest :PlayerAgentApp:assembleDebug

# Installed-device quick smoke tests
./tools/smoke/run_all_smoke_tests.sh --quick --json
```

The repository also includes long-play tests for album art, lyrics, controls,
reconnect behavior and current-word timing. See the
[smoke-test documentation](tools/smoke/README.md) before running tests that
operate real devices.

## Project status

This is an actively developed, device-specific project rather than a polished
App Store product. The BLE protocol preserves compatibility between older and
newer builds, but setup currently expects familiarity with Android sideloading
and Xcode signing.

Issues and pull requests are welcome, especially for:

- validation on additional Android-based Walkman models;
- reproducible BLE performance traces;
- QQ Music metadata, QRC lyric or album-art edge cases;
- setup documentation and translations.

If MusicBleController is useful to you, consider starring the repository. It
helps other Sony Walkman and iPhone users discover the project.

## Acknowledgements

The QRC decryption implementation includes work derived from open-source
projects credited in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## 中文简介

MusicBleController 用 BLE 将 Sony Android Walkman 上的 QQ 音乐与 iPhone
连接起来，提供歌词同步、逐字高亮、封面传输、播放控制、灵动岛和
实时活动。Sony 端负责读取 QQ 音乐 MediaSession、通知封面与本地 QRC
缓存，iPhone 端负责展示、缓存和控制，全程不需要云服务。

当前仅支持 Android 版 QQ 音乐，需要自行编译并签名 iOS App。欢迎通过
Issues 反馈其他 Walkman 机型的兼容情况、歌词或封面样本以及 BLE 性能数据。
