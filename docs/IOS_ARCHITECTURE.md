# iOS 架构

本文描述 iPhone App 侧结构。目标是避免继续把所有职责塞回 `BLETestManager.swift`。

## 模块职责

- `BLETestManager`：兼容页面动作门面，继续暴露现有播放、扫描、重连、音量、Seek、歌词和历史方法；协议接收后只把受影响的数据同步到对应 Store/Coordinator。
- `BLEInboundPipeline`：单消费者串行接收 notify，在后台维持接收顺序并完成 JSON、TrackInfo 和历史数据解码，主线程只应用最终结果。
- `ConnectionStore` / `PlaybackStore` / `LyricsStore` / `ArtworkStore` / `DiagnosticsStore`：基于 Observation 的聚焦状态域；播放器子视图只读取自己需要的属性。
- `BLEProtocolV3Parser`：V1/V2/V3 能力 ACK、`sid/es` 元信息、`commandError` 和 `mediaLoadState` 的 typed 解析及输入尺寸保护。
- `PreferencesStore`：本地设置和 UserDefaults。包括日常/调试模式、自动重连、歌词偏移、歌词显示模式、封面增强、封面尺寸和自动/流畅/省电性能模式。
- `AlbumArtReceiver`：封面 offer、preview/HQ 请求、legacy/base64 和 binary 接收、超时、缓存、displayQuality、enhanced、诊断 snapshot。
- `ArtworkImageCache`：40 项/32MB 解码图片缓存，以及 utility 串行队列上的 ImageIO 降采样。
- `BLEObservableStateModels`：聚焦 Store、协议 typed payload、事件序号诊断、布局/刷新策略和 Live Activity revision fence；旧 `ObservableStateSlice` 仅保留给兼容代码和渐进迁移测试。
- `LastNowPlayingSnapshotStore`：24 小时版本化启动占位，只显示为“上次播放 · 等待同步”。
- `FullLyricsCacheStore`：完整歌词 80 项/8MB/30 天版本化磁盘 LRU；重复歌曲先显示缓存，再由 Sony 后台复验。
- `MediaLoadingState`：歌词窗口/完整歌词和 preview/HQ 的加载阶段及明确失败原因。
- `ContentView`：安全区内的响应式播放器 UI；使用 compact/regular 两档布局、`ViewThatFits` 和垂直滚动兜底，并按 `AppExperienceMode` 控制日常/调试入口。
- `FullLyricsView`：全屏歌词、翻译/罗马音显示模式、逐字高亮和点击 seek。
- `NowPlayingDiagnosticView` / `SystemHealthOverviewView`：当前歌曲诊断、快捷修复、系统健康总览。
- `LiveActivityManager`：ActivityKit 状态合并、去重、队列、启动/恢复/更新；封面由后台编码队列生成 80×80 JPEG，再交给 actor 原子写入共享容器。
- `AppPerformanceLog` / `MetricDiagnosticsSubscriber`：按连接、协议、歌词、封面、Live Activity、UI 分类记录 OSLog/signpost，并接收 MetricKit 指标和诊断。

## 核心文件

- [BLETestManager.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/BLETestManager.swift)
- [PreferencesStore.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/PreferencesStore.swift)
- [AppExperienceMode.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/AppExperienceMode.swift)
- [AlbumArtReceiver.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/AlbumArtReceiver.swift)
- [ArtworkImageCache.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/ArtworkImageCache.swift)
- [BLEObservableStateModels.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/BLEObservableStateModels.swift)
- [LastNowPlayingSnapshotStore.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/LastNowPlayingSnapshotStore.swift)
- [FullLyricsCacheStore.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/FullLyricsCacheStore.swift)
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
4. JSON status、TrackInfo 拼包和历史拼包在 `BLEInboundPipeline` 的串行后台队列解码；以 transfer token、`trackId + generation + transferId` 校验时效后，回到 MainActor 应用 typed 结果。
5. `BLETestManager` 保留兼容 `@Published` API，但主界面不观察它的全量变化；`BLETestManagerOwner` 作为不发布变化的 `@StateObject` 稳定持有门面，避免 View 重建时重复创建 CoreBluetooth 栈。连接、播放、歌词、封面和诊断子视图分别读取对应 Store，相同值和无关事件不会重绘整个播放器。
6. 诊断页通过 `makeNowPlayingDiagnosticSnapshot()` 从当前状态派生。

## 协议协商与兼容

- 正常模式发送 `CLIENT_CAPABILITIES protocolVersion=3`，保留全部 V2 boolean，并发送 `f3=7`。
- `f2` bit 0...5 依次表示 `albumArtBinary`、`fullLyricsZlib`、`lyricWindow`、`ping`、`clockSyncV1`、`transferRetry`。
- `f3` bit 0...2 依次表示 `statusMetaV1`、`structuredErrorV1`、`mediaLoadStateV1`。
- 没有 ACK 时保持 legacy/V1；V2 ACK 读取原 boolean；V3 ACK 读取紧凑的 `f2`、`f3`、`sid`。调试模式可强制发送 V2 以便 A/B 和紧急回退。
- `sid/es` 只用于记录服务会话内的重复、缺口和乱序。不同优先级 notify 可以交错，所以 `es` 不能作为全局丢弃依据。
- `commandError` 通过原命令 `seq` 关联，保留 domain、code、retryable、retryAfterMs、trackId、generation；面向用户显示可理解原因，完整字段进入调试日志。
- `mediaLoadState` 按 `trackId + generation + resource + stage + reason` 去重并驱动歌词/封面加载状态；未协商该能力时继续使用 iOS 本地推断。
- 小 MTU 下 Sony 可以关闭 `statusMetaV1`；iOS 必须接受没有 `sid/es` 的 V3 status，并继续按消息类型处理。

CoreBluetooth 使用 `com.musicblecontroller.sony.central.v1` 做状态恢复，并在 iOS 17+ 连接选项中启用系统自动重连。系统报告 `isReconnecting=true` 时，自建 scan/reconnect 暂停；回到前台且系统重连超过连接超时后才由现有主动扫描接管。恢复到已连接且特征有效的 Sony 时直接复用 characteristic/notify；恢复确认前，前台生命周期检查不得把 `disconnected` 初值误判为真实断链。只有恢复状态无效时才回到 scan/reconnect。

命令写回调连续超时仍会触发真实传输自愈。若 CoreBluetooth transport 尚连接且最近 notify 证明链路活跃，自愈期间最多 8 秒保持已连接展示；正式同步完成后结束保护，连接失败或保护窗到期则恢复真实重连状态。该策略只稳定产品展示，不跳过重连，也不把超时命令补发。

用户控制与播放状态 fallback 共用串行 command write 队列，但普通 `GET_PLAYBACK_STATE` 会被更新的 fallback 或新的用户控制合并；前台恢复验证与 Health probe 的请求序列受保护。NEXT/PREVIOUS fallback 等待 1 秒，优先让 Sony 的正式 TrackInfo/PlaybackState 主动推送完成；新 trackId 一旦被接受即取消剩余 fallback。该策略不新增 Timer、不重发控制命令，也不改变 BLE 协议。

## 关键状态

- 连接：`connectionStatus`、`connectionDisplayState`、`connectionHealthState`、`autoReconnectState`。
- 播放：`title`、`artist`、`album`、`isPlaying`、`positionMs`、`durationMs`。
- 歌词：`lyric`、`fullLyrics`、`fullLyricsTrackId`、`lyricDiagnostic`、`lyricSecondaryTransfer`。
- 封面：`albumArtImage`、`artworkDisplayQuality`、`artworkEnhancementStatus`，由 `AlbumArtReceiver` 镜像到 `BLETestManager`。
- 设置：`PreferencesStore.shared` 是真实 owner；`BLETestManager` 只做兼容读取/同步。
- 播放时钟只在前台且播放中运行；暂停、断开、后台保持静态位置。进度刷新为自动 4Hz、流畅 10Hz、省电 2Hz；伪频谱自动 15fps、流畅 20fps、省电或 Reduce Motion 静态化。
- 稳定歌曲会异步写入 `LastNowPlayingSnapshot-v1.json`；首个真实 playbackState 不匹配或没有活动歌曲时立即清除占位。

## UI 状态与无障碍

- 可用高度不超过 700pt 或 Dynamic Type 为无障碍字号时使用 compact：封面约 140pt、歌词区至少 120pt、控制间距约 32pt；regular 封面约 204pt，并沿用原暗色播放器的分段弹性留白，把剩余高度分配在封面、歌词、进度和控制之间，音量条贴近底部安全区。
- 蓝牙关闭、未授权、不支持、未连接、扫描、连接、恢复连接、连接失败和健康连接均有明确页面状态。没有缓存时只显示连接引导；有缓存时明确标记“上次播放 · 等待同步”。
- 背景单独忽略安全区，内容始终在安全区内。`ViewThatFits` 放不下时改用垂直滚动，音量和控制按钮仍可访问。
- 逐字歌词以连续字符进度驱动 iOS 18 `TextRenderer`，在同一份文本排版上按行裁切高亮；按性能模式在 10Hz/4Hz/2Hz 状态采样之间线性补间并前视一个采样周期。切行、Seek/倒退、大跨度校正和 Reduce Motion 必须直接对齐真实位置，不能播放反向扫动。
- 歌词交互使用 `Button`，Slider 保留 VoiceOver 可调整操作；Reduce Motion 下不播放脉冲和频谱动画。
- 用户文案维护在 String Catalog，简体中文和英文必须同步；升级后的默认界面语言是简体中文，用户可在设置中显式切换英文，避免系统语言变化造成中英混排。日志、协议字段和内部诊断值不本地化。

## 不允许随便修改的点

- 不要在 Widget Extension 中创建 `CBCentralManager`。
- 不要把设置重新塞回 `BLETestManager`。
- 不要把封面接收链路从 `AlbumArtReceiver` 拆回 `BLETestManager`。
- 不要让控制命令在非 healthy 状态下排队补发。
- 不要绕过五个聚焦 Store 让主界面重新直接观察 `BLETestManager` 的所有 BLE 诊断字段，也不要恢复全局 `objectWillChange` 镜像链。
- 不要把“上次播放”快照当作真实在线播放状态。
- 不要让 UI 模式影响 BLE、歌词、封面、Live Activity 的业务逻辑。

## 常见问题排查入口

- 自动连接慢：查 `[BLE-Reconnect] foreground strategy=scanFirst`、retrieve fast timeout、didDiscover、didConnect。
- 假连接：查 `[BLE-Health] suspect/probe/hard reconnect` 和 `connectionHealthState`。
- 频繁切歌时误显示重连：查 `[CTRL-iOS] write path recovery keeps connected presentation` 与 `[BLE-UIState] silent transport recovery started/finished`；保护窗内不应出现 `display reconnecting`。
- 恢复后立即重连：查 `[BLE-Restore]`、`foreground restore skipped`，恢复完成前不应出现 `foreground unhealthy ... hard reconnect`。
- 设置未持久化：查 `[Preferences] loaded`、`[Preferences] changed`、smoke `[SmokeTest] preferences persisted`。
- 诊断页数据不对：查 `makeNowPlayingDiagnosticSnapshot()` 和 `SystemHealthSnapshot(nowPlaying:)`。

## 修改后必须跑哪些 smoke test

- 改 `BLETestManager.swift`、`ContentView.swift`、`FullLyricsView.swift`、诊断页、封面、重连、设置：跑 quick。
- 改 `PreferencesStore.swift`、`PreferencesView.swift`、UserDefaults key、`project.pbxproj`：跑 full。
- quick 命令：`./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick --json`
- full 命令：`./tools/ios-smoke-tests/run_ios_smoke_tests.sh --json`
- XCTest：`xcodebuild -project IOSBleFeasibility/IOSBleFeasibility.xcodeproj -scheme sonyMusic -destination 'platform=iOS Simulator,name=iPhone 16 Pro' CODE_SIGNING_ALLOWED=NO test`
- 覆盖率验收必须对 `sonyMusic.app` 生产 Target 单独计算，不能把测试 Target 混入总数。命令在 XCTest 后追加 `-enableCodeCoverage YES -resultBundlePath /tmp/IOSCoverage.xcresult`，再用 `xcrun xccov view --report /tmp/IOSCoverage.xcresult` 读取。2026-08-13 基线为 50.19%（14571/29029），36 个 XCTest 全部通过。
- 覆盖率测试包含主播放器 regular/compact、设置、调试、诊断、完整歌词四种显示模式、历史统计等真实 SwiftUI 渲染，以及诊断 reducer、自愈状态机、歌词/封面/历史/日志/快照持久化。不得通过排除生产源码或把无断言执行计入测试来维持门槛。

工程暂时使用 Swift 5 + targeted strict concurrency；升级 Swift 6 前必须先保持 clean build 无源代码 warning。Debug/诊断构建可启用主线程 heartbeat，生产性能以 signpost 和 MetricKit 为准。

V4 Debug/Smoke 构建通过 `RealtimeTraceStore` 记录命令、协议接收、解码、Track/歌词/CurrentWord/封面 publish 和主播放器消费事件。事件只进入 2048 条 Ring Buffer 与现有异步 `AppLogStore`，不成为业务状态源；完整口径和真机命令见 [REALTIME_SLO_V4.md](/Volumes/雷电/project/MusicBleController/docs/REALTIME_SLO_V4.md)。
