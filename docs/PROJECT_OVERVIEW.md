# MusicBleController 项目总览

本文给 Codex 使用，用于快速建立项目边界。修改代码前先读本文件，再按具体模块阅读对应架构文档。

## 模块职责

- `PlayerAgentApp/`：运行在 Sony Android 设备上的权威数据源。负责读取 MediaSession/通知、记录播放历史、解析 QQMusic QRC 歌词、监听 QRC 文件变化、提供 BLE GATT Server、执行播放/音量控制。
- `IOSBleFeasibility/IOSBleFeasibility/`：iPhone 主 App。负责 CoreBluetooth Central 连接 Sony、发送命令、接收状态、缓存封面/歌词/历史、展示播放器、诊断页和设置页。
- `IOSBleFeasibility/SonyMusicLiveActivityExtension/`：Live Activity / Dynamic Island / 锁屏 Widget Extension。只展示 `ActivityKit` 状态和 App Group 中的小封面缩略图，不直接连接 Sony。
- `ControllerApp/`：Android BLE V2 控制端。使用 Jetpack Compose、ViewModel、StateFlow 和前台连接服务，负责连接 Sony、播放控制、逐字/完整歌词、封面、历史统计、设置与诊断；RFCOMM 仅作为调试兼容入口。
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
- Sony 预测热集：[PredictiveMediaCoordinator.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/PredictiveMediaCoordinator.kt)
- Sony 歌词：[LyricManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/LyricManager.kt)、[QrcLyricManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcLyricManager.kt)
- Android Controller 服务：[ControllerConnectionService.kt](/Volumes/雷电/project/MusicBleController/ControllerApp/src/main/java/com/example/controllerapp/service/ControllerConnectionService.kt)
- Android Controller 协议与状态：[ControllerRepository.kt](/Volumes/雷电/project/MusicBleController/ControllerApp/src/main/java/com/example/controllerapp/ControllerRepository.kt)、[ControllerModels.kt](/Volumes/雷电/project/MusicBleController/ControllerApp/src/main/java/com/example/controllerapp/model/ControllerModels.kt)
- Android Controller 界面：[ControllerAppUi.kt](/Volumes/雷电/project/MusicBleController/ControllerApp/src/main/java/com/example/controllerapp/ui/ControllerAppUi.kt)

## 总体数据流

1. Sony `PlayerAgentForegroundService` 启动 GATT Server、advertising、QRC watcher、播放历史监控。
2. iPhone `BLETestManager` 和 Android `ControllerConnectionService` 可同时作为 Central 扫描并连接 `SonyPlayerAgent`（最多两个控制端）。
3. 控制端向 command characteristic 写 JSON 命令；直接响应按来源设备路由，权威播放状态广播给全部订阅端。
4. Sony 从 MediaSession / Notification / QRC 缓存读取状态，通过 status characteristic notify JSON 或二进制封面 chunk。
5. iPhone 更新主播放器、全屏歌词、诊断页、Live Activity。
6. Live Activity Extension 只读取 `ContentState` 和 App Group 缩略图，不访问 BLE。
7. Android `ControllerConnectionService` 使用同一 BLE V2 协议更新 Compose 界面和
   MediaStyle 通知，可与 iPhone 同时在线并保持控制与媒体状态同步。
8. Sony PlayerAgent 本机播放器封面监听 QQ 音乐通知事件，并在 title/artist 精确匹配当前媒体后发布；切歌后的迟到图片由 generation+songKey 栅栏拒绝。
9. V4 第三阶段先审计 MediaSession queue；有稳定 queueItemId/mediaId 时 Sony 才允许 CONFIRMED/STRONG 候选晋升。当前真机 QQ 音乐不暴露队列，因此只保留 WEAK 历史候选的本地 QRC 索引预热，不向控制端发送候选。
10. 协商 `mediaCacheValidationV1` 后，iOS 对当前歌曲的精确 FullLyrics cache 发 fingerprint/schema/行数校验；命中时 Sony 跳过完整正文传输，CurrentLine/CurrentWord 仍正常增量发布。
11. iOS cache v3 分离远端 QRC fingerprint 与本地持久化正文 fingerprint；媒体 TrackInfo、PlaybackState、歌词、CurrentWord、Preview/HQ 统一使用经过 identity 复核的 wire generation。
12. 高频切歌与封面传输并发时，Sony 对合法 command 保持即时 ATT response，并在 response 后按设备保留 25ms quiet window；封面 binary 最小 pacing 固定为 15ms。健康链路不会仅因歌曲 generation 变化显示“正在连接”。
13. V4 第四阶段以本地 `handoffId` 关联 command、MediaSession、PlaybackState、歌词和 CurrentWord；该 ID 只用于 Trace，不进入 BLE payload。
14. 歌词 ready 后，Sony 通过精确 trackId/generation 屏障立即发布包含 current line 的 PlaybackState，并立即恢复独立 CurrentWord boundary scheduler，不再等待下一轮 AutoPush。
15. iOS 控制写会取消尚未执行的普通 `GET_PLAYBACK_STATE` fallback，并把 NEXT/PREVIOUS 的兜底读取合并到播放器身份切换窗之后；前台验证和 Health 探针不参与丢弃。Sony 的 220ms 控制后广播保留轻量 PlaybackState 兜底；新身份和精确封面由权威 MediaSession 确认后的 fast lane 发布，避免旧身份和旧图占用正式新歌曲通道。
16. Sony TrackInfo 始终进入既有 P0 实时队列并在长媒体任务包边界抢占；latest-only interleaved 槽只保留可替换的 volumeState。iOS Clock Sync 后台探针在 FullLyrics/图片接收期间有界延后，媒体空闲后事件化恢复，不新增 Timer。
17. Sony BLE 主 `PlaybackStateReader` 在权威 MediaSession 确认新身份后先原子建立 runtime generation，立即同步 Sony UI 和 iOS TrackInfo，再继续歌词、capability 和诊断路径；该 fast lane 不使用通知猜测、不改协议，也不向非 BLE Reader 重复发布。

## 关键状态

- Sony 是播放、历史、歌词和封面来源的权威端。
- iOS 与 Sony 可协商 `clockSyncV1`，使用单调时钟和播放位置采样时间动态补偿 BLE 歌词延迟；严重积压的旧锚点不得覆盖当前进度。
- iPhone 是同步、缓存和展示端。
- Android Controller 是与 iPhone 对等的同步、缓存和展示端，不包含 Live Activity。
- iOS `BLETestManager` 仍是连接/协议分发中心，但设置已拆到 `PreferencesStore`，封面接收已拆到 `AlbumArtReceiver`。
- iOS 写通道自愈不会把最近仍有 notify 的健康连接立即展示成“正在连接”；同步失败或保护窗到期时仍回到真实重连状态。
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
- V4 实时性 Trace、SLO 与真机基线：[REALTIME_SLO_V4.md](/Volumes/雷电/project/MusicBleController/docs/REALTIME_SLO_V4.md)；自动报告位于 `/tmp/musicble_realtime_v4/<timestamp>/`。
- V4 预测来源、Hot Set、缓存校验与 Gate 4 跳过依据：[PREDICTIVE_MEDIA_ENGINE_V4.md](/Volumes/雷电/project/MusicBleController/docs/PREDICTIVE_MEDIA_ENGINE_V4.md)。
- V4 Cold-Path Handoff、current lyric/CurrentWord 首包、command response 竞态与第四阶段实测：[COLD_PATH_HANDOFF_V4.md](/Volumes/雷电/project/MusicBleController/docs/COLD_PATH_HANDOFF_V4.md)。

## 修改后必须跑哪些 smoke test

- iOS 普通 UI/BLE/歌词/封面/诊断/设置/Live Activity 改动：
  `./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick --json`
- iOS 启动、安装、UserDefaults、日志、App 容器、`project.pbxproj` 改动：
  `./tools/ios-smoke-tests/run_ios_smoke_tests.sh --json`
- Android Controller 改动：
  `./gradlew :ControllerApp:testDebugUnitTest :ControllerApp:assembleDebug :ControllerApp:lintDebug`
- docs-only 改动：只需 `git diff --check`。
