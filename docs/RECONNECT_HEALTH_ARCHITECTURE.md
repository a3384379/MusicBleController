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
5. Health timer 检查静默时间：播放中 15 秒、暂停中 30 秒才探测。
6. V2 发送轻量 `PING/PONG`；旧 Sony 回退 `GET_PLAYBACK_STATE`。连续两次探测失败才 hard reconnect，明确断开回调仍立即处理。
7. `connectionDisplayState` 给 UI，`connectionHealthState` 给诊断和控制保护。
8. CoreBluetooth 状态恢复优先复用已连接 Sony 和已恢复 characteristic；iOS 17+ 同时启用系统自动重连，系统重连期间禁止自建 reconnect work item 竞争，前台超过连接超时后再回退主动扫描。进入 inactive/background 后暂停 health、订阅/写超时和时钟同步；回到前台先用单次探针验证，收到有效 notify 后才恢复状态、音量和歌词同步。
9. 单次 `didWrite` 回调超时只标记 suspect 并延长等待，不移除 in-flight 请求、不推进写队列；连续两次写回调超时才 hard reconnect。
10. Sony 端业务静默只触发按地址的轻量 notify 探针；只有真实 notify 失败达到阈值才隔离该地址，禁止用静默时间直接重建共享 GATT。
11. Sony 对合法 command 的 ATT response 与同设备在途 notify 在 queue callback 边界串行化；这防止 `sendResponse()` 本地成功但 L2CAP 未实际发出的 write callback 缺失。业务命令仍异步执行，不靠延迟媒体 generation 伪造连接健康。

## 关键状态

- 常量当前在 `BLETestManager.swift` 顶部：
  - `FAST_RETRIEVE_CONNECT_TIMEOUT_MS = 1800`
  - `DEFAULT_CONNECT_TIMEOUT_MS = 8000`
  - `CONNECTION_HEALTH_TICK_MS = 3000`
  - 播放静默阈值：15000ms
  - 暂停静默阈值：30000ms
  - `CONNECTION_HEALTH_PROBE_TIMEOUT_MS = 3000`
  - `CONNECTION_HEALTH_HARD_RECONNECT_MIN_INTERVAL_MS = 5000`
- `connectionDisplayState`：给 UI 使用，如 `connected`、`reconnecting`、`disconnected`。
- `connectionHealthState`：内部健康，如 `healthy`、`suspect`、`stale`、`disconnected`。
- `autoReconnectState`：`idle`、`reconnectScheduled`、`scanning`、`connecting` 等。
- `connectionAttemptId`：防止旧 scan/connect 回调污染当前状态。
- `coreBluetoothRestoreInProgress`：隔离状态恢复和前台检查的竞态。

## 不允许随便修改的点

- 不要因为 UI 胶囊闪烁而关闭 Health Check。
- 不要在已 ready 连接回前台时主动断开重连。
- 不要让 retrieve connect 阻塞 scan 太久。
- 不要在非 healthy/suspect 时发送播放控制命令。
- 不要重连后补发旧控制命令。
- 不要因为恢复连接尚未完成 health 初始化就主动取消已连接 peripheral。

## 常见问题排查入口

- 打开 App 连接慢：看 `[BLE-Reconnect] foreground strategy=scanFirst`、`retrieve connect fast timeout`、`didDiscover`、`didConnect`。
- 一直显示重连：看 `autoReconnectState`、`connectionAttemptId`、`ignore stale callback`、`scan timeout`。
- 假连接：看 `[BLE-Health] suspect`、`probe sent`、`probe timeout`、`hard reconnect reason=...`。
- 恢复竞态：看 `[BLE-Restore] restored`、`foreground restore skipped`、`reuse restored notifying characteristic`。
- 写回调偶发丢失：看 `[CTRL-iOS] write timeout extended`；第一轮超时后 in-flight 序号应保持不变。
- 高频切歌 write callback 缺失：同时检查 Sony `commandResponseDeferred/Released`、notify callback 和 L2CAP failure；response release 应发生在同设备在途 notify callback 之后，且不应触发 iOS hard reconnect。
- 前后台恢复：看 `BLE watchdogs paused`、`foreground validation queued/success`，验证成功前不应出现状态/音量/歌词同步突发。
- 胶囊闪烁：区分 `connectionDisplayState` 和 `connectionHealthState`，日常模式不应展示技术细节。

## 修改后必须跑哪些 smoke test

- 改连接、重连、Health、UI 胶囊：quick smoke。
- 改 `autoReconnectEnabled` 默认值/UserDefaults/Preferences：full smoke。
- 真机建议测试：`reconnect_sync_v28_test.sh`、Sony 服务停止/恢复、App 前后台、Force Reconnect。

第四阶段正常 NEXT/PREVIOUS/Sony dispatch-next 共 90 次和 100 次快速压力中，合法 command response 未观察到 L2CAP failure、iOS write timeout 或 hard reconnect。此前复现的“实际连接仍在但 UI 长时间显示正在连接”包含 ATT response 与在途 notify 冲突这一根因；修复细节见 [COLD_PATH_HANDOFF_V4.md](/Volumes/雷电/project/MusicBleController/docs/COLD_PATH_HANDOFF_V4.md)。
