# iOS 架构

本文描述 iPhone App 侧结构。目标是避免继续把所有职责塞回 `BLETestManager.swift`。

## 模块职责

- `BLETestManager`：CoreBluetooth Central、扫描/连接/重连、命令写入、status notify 分发、歌词/历史/诊断/Live Activity 更新协调。
- `PreferencesStore`：本地设置和 UserDefaults。包括日常/调试模式、自动重连、歌词偏移、歌词显示模式、封面增强开关、封面显示尺寸。
- `AlbumArtReceiver`：封面 offer、preview/HQ 请求、legacy/base64 和 binary 接收、超时、缓存、displayQuality、enhanced、诊断 snapshot。
- `ContentView`：主播放器 UI，按 `AppExperienceMode` 控制日常/调试入口。
- `FullLyricsView`：全屏歌词、翻译/罗马音显示模式、逐字高亮和点击 seek。
- `NowPlayingDiagnosticView` / `SystemHealthOverviewView`：当前歌曲诊断、快捷修复、系统健康总览。
- `LiveActivityManager`：ActivityKit 状态合并、去重、队列、启动/恢复/更新。

## 核心文件

- [BLETestManager.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/BLETestManager.swift)
- [PreferencesStore.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/PreferencesStore.swift)
- [AppExperienceMode.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/AppExperienceMode.swift)
- [AlbumArtReceiver.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/AlbumArtReceiver.swift)
- [ContentView.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/ContentView.swift)
- [PreferencesView.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/PreferencesView.swift)
- [FullLyricsView.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/FullLyricsView.swift)
- [NowPlayingDiagnosticSnapshot.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/NowPlayingDiagnosticSnapshot.swift)
- [NowPlayingDiagnosticView.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/NowPlayingDiagnosticView.swift)

## 数据流

1. `BLETestManager` 扫描并连接 Sony。
2. `sendCommand(cmd:extra:)` 写 JSON 到 command characteristic。
3. `parseStatus` 根据 `type` 分发：
   - `playbackState` / `trackInfo` 更新播放器和 Live Activity。
   - `fullLyrics*` 更新 `fullLyrics`。
   - `lyricSecondary*` 组装翻译/罗马音。
   - `albumArt*` 转交 `AlbumArtReceiver`。
   - `playHistory*` 合并到 `PlaybackHistoryStore`。
4. UI 通过 `@Published` 状态刷新。
5. 诊断页通过 `makeNowPlayingDiagnosticSnapshot()` 从当前状态派生。

## 关键状态

- 连接：`connectionStatus`、`connectionDisplayState`、`connectionHealthState`、`autoReconnectState`。
- 播放：`title`、`artist`、`album`、`isPlaying`、`positionMs`、`durationMs`。
- 歌词：`lyric`、`fullLyrics`、`fullLyricsTrackId`、`lyricDiagnostic`、`lyricSecondaryTransfer`。
- 封面：`albumArtImage`、`artworkDisplayQuality`、`artworkEnhancementStatus`，由 `AlbumArtReceiver` 镜像到 `BLETestManager`。
- 设置：`PreferencesStore.shared` 是真实 owner；`BLETestManager` 只做兼容读取/同步。

## 不允许随便修改的点

- 不要在 Widget Extension 中创建 `CBCentralManager`。
- 不要把设置重新塞回 `BLETestManager`。
- 不要把封面接收链路从 `AlbumArtReceiver` 拆回 `BLETestManager`。
- 不要让控制命令在非 healthy 状态下排队补发。
- 不要让 UI 模式影响 BLE、歌词、封面、Live Activity 的业务逻辑。

## 常见问题排查入口

- 自动连接慢：查 `[BLE-Reconnect] foreground strategy=scanFirst`、retrieve fast timeout、didDiscover、didConnect。
- 假连接：查 `[BLE-Health] suspect/probe/hard reconnect` 和 `connectionHealthState`。
- 设置未持久化：查 `[Preferences] loaded`、`[Preferences] changed`、smoke `[SmokeTest] preferences persisted`。
- 诊断页数据不对：查 `makeNowPlayingDiagnosticSnapshot()` 和 `SystemHealthSnapshot(nowPlaying:)`。

## 修改后必须跑哪些 smoke test

- 改 `BLETestManager.swift`、`ContentView.swift`、`FullLyricsView.swift`、诊断页、封面、重连、设置：跑 quick。
- 改 `PreferencesStore.swift`、`PreferencesView.swift`、UserDefaults key、`project.pbxproj`：跑 full。
- quick 命令：`./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick --json`
- full 命令：`./tools/ios-smoke-tests/run_ios_smoke_tests.sh --json`
