# iOS 架构

本文描述 iPhone App 侧结构。目标是避免继续把所有职责塞回 `BLETestManager.swift`。

## 模块职责

- `BLETestManager`：CoreBluetooth Central、扫描/连接/重连、命令写入、status notify 分发、歌词/历史/诊断/Live Activity 更新协调。
- `PreferencesStore`：本地设置和 UserDefaults。包括日常/调试模式、自动重连、歌词偏移、歌词显示模式、封面增强、封面尺寸和自动/流畅/省电性能模式。
- `AlbumArtReceiver`：封面 offer、preview/HQ 请求、legacy/base64 和 binary 接收、超时、缓存、displayQuality、enhanced、诊断 snapshot。
- `ArtworkImageCache`：40 项/32MB 解码图片缓存，以及 utility 串行队列上的 ImageIO 降采样。
- `BLEObservableStateModels`：连接、播放、歌词、封面、诊断五个可观察切片；相同值不重复发布。
- `LastNowPlayingSnapshotStore`：24 小时版本化启动占位，只显示为“上次播放 · 等待同步”。
- `MediaLoadingState`：歌词窗口/完整歌词和 preview/HQ 的加载阶段及明确失败原因。
- `ContentView`：主播放器 UI，按 `AppExperienceMode` 控制日常/调试入口。
- `FullLyricsView`：全屏歌词、翻译/罗马音显示模式、逐字高亮和点击 seek。
- `NowPlayingDiagnosticView` / `SystemHealthOverviewView`：当前歌曲诊断、快捷修复、系统健康总览。
- `LiveActivityManager`：ActivityKit 状态合并、去重、队列、启动/恢复/更新。

## 核心文件

- [BLETestManager.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/BLETestManager.swift)
- [PreferencesStore.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/PreferencesStore.swift)
- [AppExperienceMode.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/AppExperienceMode.swift)
- [AlbumArtReceiver.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/AlbumArtReceiver.swift)
- [ArtworkImageCache.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/ArtworkImageCache.swift)
- [BLEObservableStateModels.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/BLEObservableStateModels.swift)
- [LastNowPlayingSnapshotStore.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/LastNowPlayingSnapshotStore.swift)
- [MediaLoadingState.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/MediaLoadingState.swift)
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
4. `BLETestManager` 保留兼容 `@Published` API，但主界面分别观察五个 `ObservableStateSlice`；同值与无关状态不再让整个播放器重绘。
5. 诊断页通过 `makeNowPlayingDiagnosticSnapshot()` 从当前状态派生。

CoreBluetooth 使用 `com.musicblecontroller.sony.central.v1` 做状态恢复。恢复到已连接且特征有效的 Sony 时直接复用 characteristic/notify；恢复确认前，前台生命周期检查不得把 `disconnected` 初值误判为真实断链。只有恢复状态无效时才回到 scan/reconnect。

## 关键状态

- 连接：`connectionStatus`、`connectionDisplayState`、`connectionHealthState`、`autoReconnectState`。
- 播放：`title`、`artist`、`album`、`isPlaying`、`positionMs`、`durationMs`。
- 歌词：`lyric`、`fullLyrics`、`fullLyricsTrackId`、`lyricDiagnostic`、`lyricSecondaryTransfer`。
- 封面：`albumArtImage`、`artworkDisplayQuality`、`artworkEnhancementStatus`，由 `AlbumArtReceiver` 镜像到 `BLETestManager`。
- 设置：`PreferencesStore.shared` 是真实 owner；`BLETestManager` 只做兼容读取/同步。
- 播放时钟只在前台且播放中运行；暂停、断开、后台保持静态位置。频谱为播放 20fps、加载 10fps，Reduce Motion/低电量/省电模式静态化。
- 稳定歌曲会异步写入 `LastNowPlayingSnapshot-v1.json`；首个真实 playbackState 不匹配或没有活动歌曲时立即清除占位。

## 不允许随便修改的点

- 不要在 Widget Extension 中创建 `CBCentralManager`。
- 不要把设置重新塞回 `BLETestManager`。
- 不要把封面接收链路从 `AlbumArtReceiver` 拆回 `BLETestManager`。
- 不要让控制命令在非 healthy 状态下排队补发。
- 不要绕过五个状态切片让主界面重新直接观察所有 BLE 诊断字段。
- 不要把“上次播放”快照当作真实在线播放状态。
- 不要让 UI 模式影响 BLE、歌词、封面、Live Activity 的业务逻辑。

## 常见问题排查入口

- 自动连接慢：查 `[BLE-Reconnect] foreground strategy=scanFirst`、retrieve fast timeout、didDiscover、didConnect。
- 假连接：查 `[BLE-Health] suspect/probe/hard reconnect` 和 `connectionHealthState`。
- 恢复后立即重连：查 `[BLE-Restore]`、`foreground restore skipped`，恢复完成前不应出现 `foreground unhealthy ... hard reconnect`。
- 设置未持久化：查 `[Preferences] loaded`、`[Preferences] changed`、smoke `[SmokeTest] preferences persisted`。
- 诊断页数据不对：查 `makeNowPlayingDiagnosticSnapshot()` 和 `SystemHealthSnapshot(nowPlaying:)`。

## 修改后必须跑哪些 smoke test

- 改 `BLETestManager.swift`、`ContentView.swift`、`FullLyricsView.swift`、诊断页、封面、重连、设置：跑 quick。
- 改 `PreferencesStore.swift`、`PreferencesView.swift`、UserDefaults key、`project.pbxproj`：跑 full。
- quick 命令：`./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick --json`
- full 命令：`./tools/ios-smoke-tests/run_ios_smoke_tests.sh --json`
- XCTest：`xcodebuild -project IOSBleFeasibility/IOSBleFeasibility.xcodeproj -scheme sonyMusic -destination 'platform=iOS Simulator,name=iPhone 16 Pro' CODE_SIGNING_ALLOWED=NO test`
