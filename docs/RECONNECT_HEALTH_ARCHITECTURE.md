# iOS 自动连接与 Health Check 架构

本文记录 iOS 自动连接、假连接检测和 UI 状态分层。不要把 Health 状态和 UI 胶囊文案混为一谈。

## 模块职责

- `BLETestManager`：CoreBluetooth Central、scan/retrieve/connect/service discovery/notify subscribe、health timer、probe、hard reconnect。
- `PreferencesStore`：`autoReconnectEnabled` 开关。
- `ContentView`：日常模式只显示简洁连接胶囊；调试详情在 Debug/诊断页。
- `NowPlayingDiagnosticSnapshot` / `SystemHealthSnapshot`：连接状态诊断和系统健康总览。

## 核心文件

- [BLETestManager.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/BLETestManager.swift)
- [PreferencesStore.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/PreferencesStore.swift)
- [ContentView.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/ContentView.swift)
- [NowPlayingDiagnosticSnapshot.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/NowPlayingDiagnosticSnapshot.swift)

## 数据流

1. App 前台或手动扫描时，优先 scan Sony advertising。
2. 旧 peripheral retrieve 只作为辅助，fast timeout 为 1800ms。
3. 连接成功后 discovery service/characteristic，订阅 status notify。
4. 收到任意有效 status notify 后进入 healthy。
5. Health timer 周期检查 last notify age；suspect 后发送 `GET_PLAYBACK_STATE` probe。
6. probe 超时或 stale no notify 后 hard reconnect。
7. `connectionDisplayState` 给 UI，`connectionHealthState` 给诊断和控制保护。

## 关键状态

- 常量当前在 `BLETestManager.swift` 顶部：
  - `FAST_RETRIEVE_CONNECT_TIMEOUT_MS = 1800`
  - `DEFAULT_CONNECT_TIMEOUT_MS = 8000`
  - `CONNECTION_HEALTH_TICK_MS = 3000`
  - `CONNECTION_HEALTH_SUSPECT_MS = 6000`
  - `CONNECTION_HEALTH_STALE_MS = 12000`
  - `CONNECTION_HEALTH_PROBE_TIMEOUT_MS = 3000`
  - `CONNECTION_HEALTH_HARD_RECONNECT_MIN_INTERVAL_MS = 5000`
- `connectionDisplayState`：给 UI 使用，如 `connected`、`reconnecting`、`disconnected`。
- `connectionHealthState`：内部健康，如 `healthy`、`suspect`、`stale`、`disconnected`。
- `autoReconnectState`：`idle`、`reconnectScheduled`、`scanning`、`connecting` 等。
- `connectionAttemptId`：防止旧 scan/connect 回调污染当前状态。

## 不允许随便修改的点

- 不要因为 UI 胶囊闪烁而关闭 Health Check。
- 不要在已 ready 连接回前台时主动断开重连。
- 不要让 retrieve connect 阻塞 scan 太久。
- 不要在非 healthy/suspect 时发送播放控制命令。
- 不要重连后补发旧控制命令。

## 常见问题排查入口

- 打开 App 连接慢：看 `[BLE-Reconnect] foreground strategy=scanFirst`、`retrieve connect fast timeout`、`didDiscover`、`didConnect`。
- 一直显示重连：看 `autoReconnectState`、`connectionAttemptId`、`ignore stale callback`、`scan timeout`。
- 假连接：看 `[BLE-Health] suspect`、`probe sent`、`probe timeout`、`hard reconnect reason=...`。
- 胶囊闪烁：区分 `connectionDisplayState` 和 `connectionHealthState`，日常模式不应展示技术细节。

## 修改后必须跑哪些 smoke test

- 改连接、重连、Health、UI 胶囊：quick smoke。
- 改 `autoReconnectEnabled` 默认值/UserDefaults/Preferences：full smoke。
- 真机建议测试：Sony 服务停止、恢复、App 前后台、Force Reconnect。
