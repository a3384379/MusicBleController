# MusicBleController 项目总览

本文给 Codex 使用，用于快速建立项目边界。修改代码前先读本文件，再按具体模块阅读对应架构文档。

## 模块职责

- `PlayerAgentApp/`：运行在 Sony Android 设备上的权威数据源。负责读取 MediaSession/通知、记录播放历史、解析 QQMusic QRC 歌词、监听 QRC 文件变化、提供 BLE GATT Server、执行播放/音量控制。
- `IOSBleFeasibility/IOSBleFeasibility/`：iPhone 主 App。负责 CoreBluetooth Central 连接 Sony、发送命令、接收状态、缓存封面/歌词/历史、展示播放器、诊断页和设置页。
- `IOSBleFeasibility/SonyMusicLiveActivityExtension/`：Live Activity / Dynamic Island / 锁屏 Widget Extension。只展示 `ActivityKit` 状态和 App Group 中的小封面缩略图，不直接连接 Sony。
- `ControllerApp/`：旧 Android Controller 侧代码，当前主链路不依赖它。除非任务明确要求，不要修改。
- `tools/ios-smoke-tests/`：iOS-only smoke test 工具。只依赖 iPhone、`devicectl` 和 iOS App 日志，不使用 adb。

## 核心文件

- iOS BLE 主入口：[BLETestManager.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/BLETestManager.swift)
- iOS 设置：[PreferencesStore.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/PreferencesStore.swift)
- iOS 封面接收：[AlbumArtReceiver.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/AlbumArtReceiver.swift)
- iOS 诊断：[NowPlayingDiagnosticSnapshot.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/NowPlayingDiagnosticSnapshot.swift)、[NowPlayingDiagnosticView.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/NowPlayingDiagnosticView.swift)
- Sony 前台服务：[PlayerAgentForegroundService.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/service/PlayerAgentForegroundService.kt)
- Sony BLE GATT：[BleGattServerManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/ble/BleGattServerManager.kt)
- Sony BLE 队列：[BleNotifyQueue.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/ble/BleNotifyQueue.kt)
- Sony 播放状态：[PlaybackStateReader.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/PlaybackStateReader.kt)
- Sony 歌词：[LyricManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/LyricManager.kt)、[QrcLyricManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcLyricManager.kt)

## 总体数据流

1. Sony `PlayerAgentForegroundService` 启动 GATT Server、advertising、QRC watcher、播放历史监控。
2. iPhone `BLETestManager` 作为 Central 扫描 `SonyPlayerAgent`，连接服务 `0000A001...`。
3. iPhone 向 command characteristic 写 JSON 命令。
4. Sony 从 MediaSession / Notification / QRC 缓存读取状态，通过 status characteristic notify JSON 或二进制封面 chunk。
5. iPhone 更新主播放器、全屏歌词、诊断页、Live Activity。
6. Live Activity Extension 只读取 `ContentState` 和 App Group 缩略图，不访问 BLE。

## 关键状态

- Sony 是播放、历史、歌词和封面来源的权威端。
- iPhone 是同步、缓存和展示端。
- iOS `BLETestManager` 仍是连接/协议分发中心，但设置已拆到 `PreferencesStore`，封面接收已拆到 `AlbumArtReceiver`。
- Live Activity 状态必须保持轻量，`ContentState` 不允许放图片、完整歌词、大数组或 Base64。
- iOS 日志落盘在 App 容器 `Documents/Logs/ios_ble.log`。

## 不允许随便修改的点

- BLE UUID：`0000A001` service、`0000A002` command、`0000A003` status。
- command/status characteristic 语义。
- AlbumArt binary header / chunk / end 协议。
- FullLyrics / LyricSecondary 协议。
- QRC Triple DES 解密核心算法。
- Sony 播放控制和音量控制命令名称。
- Live Activity `ContentState` 轻量化原则。

## 常见问题排查入口

- iOS 连接：`ios_ble.log` 中 `[BLE-Reconnect]`、`[BLE-Health]`、`didConnect`、`notify subscribed`。
- 歌词：`[Lyrics-iOS]`、`[LyricsDiag-iOS]`、Sony `[Lyric]`、`[LyricRecovery]`、`[QrcCache]`。
- 封面：iOS `[AlbumArt]`、`[AlbumArtBinary]`、`[AlbumArt-iOS]`、`[ArtworkDisplay]`；Sony `[AlbumArt-Sony]`。
- Live Activity：`[LiveActivity]`、`[LiveActivityState]`、`[LiveActivityPerf]`。
- Smoke 报告：`/tmp/music_ble_ios_smoke/<timestamp>/report.json`。

## 修改后必须跑哪些 smoke test

- iOS 普通 UI/BLE/歌词/封面/诊断/设置/Live Activity 改动：
  `./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick --json`
- iOS 启动、安装、UserDefaults、日志、App 容器、`project.pbxproj` 改动：
  `./tools/ios-smoke-tests/run_ios_smoke_tests.sh --json`
- docs-only 改动：只需 `git diff --check`。
