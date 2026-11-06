# Smoke Test 指南

本文记录当前 smoke 工具能力。仓库现在有两套 smoke：

- iOS-only：只验证 iPhone 端，不控制 Sony，也不使用 adb。
- Android/Sony-only：只验证 Sony `PlayerAgentApp`，使用 adb，不操作 iPhone。
- Cross-device entry：编排 iOS-only 和 Android/Sony-only，并生成总报告。
- CI：`.github/workflows/build.yml` 固定 JDK 17 跑 Android 单测/assembleDebug，并在无签名 iOS Simulator 上跑 XCTest。

## iOS-only 模块职责

- `run_ios_smoke_tests.sh`：总入口，设备发现、build/install/launch/log/settings/file checks、optional BLE、optional AlbumArt Flow、optional CurrentWord Flow、生成报告。
- `codex_check.sh`：Codex 快捷入口，运行 quick 并读取 `report.json`。
- `generate_ios_report.py`：生成 `report.md` 和 `report.json`，包含 Git 信息、device、required/optional tests、failure excerpt、AlbumArt Flow、CurrentWord Flow。
- `ios_album_art_flow_test.sh`：只读 iOS 日志，分析最近封面链路。
- `ios_current_word_flow_test.sh`：只读 iOS 日志，统计 `currentWord` 接收、丢弃和延迟。

## Android/Sony-only 模块职责

- `tools/android-smoke-tests/run_android_smoke_tests.sh`：总入口，设备发现、build/install/launch/log/file checks、optional BLE、optional PlaybackDiff Flow、optional CurrentWord Flow、生成报告。
- `android_device_check.sh`：选择 adb device，处理多设备和 unauthorized。
- `android_build_install.sh`：构建 `:PlayerAgentApp:assembleDebug` 并安装 APK。
- `android_collect_logs.sh`：采集 Sony logcat 和过滤日志。
- `android_file_checks.sh`：检查 app external files、QRC cache、QQMusic public 目录。
- `android_ble_optional_test.sh`：默认通过 Debug Receiver 请求 START，再从 Sony logcat 判断 GATT/advertising 健康；禁用调试控制时只分析已有日志。
- `ble_health_watchdog_test.sh`：默认请求 RECOVER，再分析健康状态；与 BLE optional 检查共用 `DEBUG_CONTROL_ENABLED` 开关。
- `android_playback_diff_flow_test.sh`：只基于 Sony logcat 统计 PlaybackDiff snapshot/diff/push/skip 指标。
- `android_current_word_flow_test.sh`：只基于 Sony logcat 统计轻量 `currentWord` push/skip/间隔指标。
- `generate_android_report.py`：生成 Android `report.md` 和 `report.json`。
- Debug build 会通过 `PlayerAgentDebugControlReceiver` 尝试启动 BLE foreground service，减少人工点击 Sony UI。

## Cross-device 模块职责

- `tools/smoke/run_all_smoke_tests.sh`：检测 iPhone/Sony，调用 iOS 和 Android 子 suite，支持自动降级。
- `tools/smoke/generate_all_report.py`：读取子报告并生成总 `report.md` / `report.json`。
- `tools/smoke/current_word_long_play_test.sh`：手动长播放窗口测试，采集 iOS + Sony 日志，验证 V2.3 `currentWord` 是否持续推送、iOS 是否持续 accepted，以及 playbackState 是否明显低于 currentWord。
- `tools/smoke/control_e2e_v29_test.sh`：真实交互 E2E 测试，启动 iOS Debug App 的测试参数，实际发送播放控制、音量、Seek、FullLyrics、AlbumArt 请求，再从 iOS/Sony 双端日志闭环判定。
- `tools/smoke/source_capability_v30_test.sh`：源数据可用性诊断测试，采集 3-5 首歌的歌词/封面来源、ready 延迟和 unavailable reason，区分 BLE/缓存问题与 Sony/QQ音乐源头未提供。
- `tools/smoke/lyrics_timeline_v34_test.sh`：自动切换 10 首 QQ 音乐，关联 TrackInfo、QRC、window、A2/legacy full lyrics、currentWord 与维护任务时间线。
- `tools/smoke/album_art_v27_long_play_test.sh`：封面长播放与 currentWord 联合验证，缓存立即显示也计为有效成功。

## iOS-only 核心文件

- [run_ios_smoke_tests.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/run_ios_smoke_tests.sh)
- [codex_check.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/codex_check.sh)
- [generate_ios_report.py](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/generate_ios_report.py)
- [ios_ble_optional_test.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/ios_ble_optional_test.sh)
- [ios_album_art_flow_test.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/ios_album_art_flow_test.sh)
- [ios_collect_logs.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/ios_collect_logs.sh)
- [ios_file_checks.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/ios_file_checks.sh)

## Android/Sony-only 核心文件

- [run_android_smoke_tests.sh](/Volumes/雷电/project/MusicBleController/tools/android-smoke-tests/run_android_smoke_tests.sh)
- [android_device_check.sh](/Volumes/雷电/project/MusicBleController/tools/android-smoke-tests/android_device_check.sh)
- [android_build_install.sh](/Volumes/雷电/project/MusicBleController/tools/android-smoke-tests/android_build_install.sh)
- [android_launch.sh](/Volumes/雷电/project/MusicBleController/tools/android-smoke-tests/android_launch.sh)
- [android_collect_logs.sh](/Volumes/雷电/project/MusicBleController/tools/android-smoke-tests/android_collect_logs.sh)
- [android_file_checks.sh](/Volumes/雷电/project/MusicBleController/tools/android-smoke-tests/android_file_checks.sh)
- [android_ble_optional_test.sh](/Volumes/雷电/project/MusicBleController/tools/android-smoke-tests/android_ble_optional_test.sh)
- [ble_health_watchdog_test.sh](/Volumes/雷电/project/MusicBleController/tools/android-smoke-tests/ble_health_watchdog_test.sh)
- [android_playback_diff_flow_test.sh](/Volumes/雷电/project/MusicBleController/tools/android-smoke-tests/android_playback_diff_flow_test.sh)
- [generate_android_report.py](/Volumes/雷电/project/MusicBleController/tools/android-smoke-tests/generate_android_report.py)

## Cross-device 核心文件

- [run_all_smoke_tests.sh](/Volumes/雷电/project/MusicBleController/tools/smoke/run_all_smoke_tests.sh)
- [generate_all_report.py](/Volumes/雷电/project/MusicBleController/tools/smoke/generate_all_report.py)
- [current_word_long_play_test.sh](/Volumes/雷电/project/MusicBleController/tools/smoke/current_word_long_play_test.sh)
- [control_e2e_v29_test.sh](/Volumes/雷电/project/MusicBleController/tools/smoke/control_e2e_v29_test.sh)
- [source_capability_v30_test.sh](/Volumes/雷电/project/MusicBleController/tools/smoke/source_capability_v30_test.sh)
- [lyrics_timeline_v34_test.sh](/Volumes/雷电/project/MusicBleController/tools/smoke/lyrics_timeline_v34_test.sh)
- [album_art_v27_long_play_test.sh](/Volumes/雷电/project/MusicBleController/tools/smoke/album_art_v27_long_play_test.sh)
- [README.md](/Volumes/雷电/project/MusicBleController/tools/smoke/README.md)

## iOS-only 数据流

1. 选择 iPhone：`--device` > `IOS_DEVICE_ID` > 自动发现 available iPhone。
2. 可选 build/install。
3. `devicectl launch` 启动 App。
   - 有 `--smoke-*` 参数时，`ios_ble_precheck.sh` 会先写入一次性 `Documents/SmokeLaunchArguments.txt`。即使 CoreBluetooth 状态恢复先在后台唤醒 App，测试参数也不会丢失；App 读取后立即删除。
4. 从 App container 复制 `Documents/Logs/ios_ble.log`。
5. DEBUG smoke preferences 写入并重启验证。
6. 文件检查 App container 中日志、AlbumArtCache、Preferences。
7. Optional BLE 从 iOS 日志判断 scan/discover/connect/notify/playbackState/healthy。
8. Optional AlbumArt Flow 从 iOS 日志判断 `albumArtOffer`、preview/HQ、binary transfer、cache、enhanced、timeout。
9. Optional CurrentWord Flow 从 iOS 日志统计 `currentWord` 接收、丢弃、最近 line/word 和 latency。
10. 输出 `report.md` 和 `report.json`。

## Android/Sony-only 数据流

1. 选择 adb device：`--device` > `ANDROID_DEVICE_ID` > 自动发现单台 online device。
2. full 模式构建并安装 `PlayerAgentApp-debug.apk`；quick 模式跳过 build/install。
3. 用 `monkey -p com.example.playeragent 1` 启动主 App。
4. 检查进程和 launch 后 FATAL/ANR。
5. 采集 `logcat -d`，生成 `sony_logcat.log` 和 `sony_filtered.log`。
6. 检查 `/sdcard/Android/data/com.example.playeragent/files`、QRC cache、ArtworkDiscovery、Logs、QQMusic public 目录。
7. 默认通过 Debug Receiver 请求 START/RECOVER，再从 logcat 判断 GATT server、service add、advertising 与健康状态；`--no-debug-control` 禁止这两处广播。
8. Optional PlaybackDiff Flow 只从 logcat 统计 snapshotBuildCount、diffCount、pushCount、skipCount、skipRatio、trackChanged、wordChanged、positionJump。
9. Optional CurrentWord Flow 只从 logcat 统计 `CurrentWordPush` push/skip/averageInterval/lastPushCost。
10. 输出 `/tmp/music_ble_android_smoke/<timestamp>/report.md` 和 `report.json`。

## Cross-device 数据流

1. 检测 iPhone：`devicectl list devices`。
2. 检测 Sony：`adb devices`。
3. 两台都存在时分别运行 iOS 和 Android smoke。
4. 只存在一台时自动只跑对应 suite，另一个 suite 标记 `SKIPPED`。
5. 子报告输出到 `<output>/ios/` 和 `<output>/android/`。
6. 总报告输出到 `/tmp/music_ble_smoke/<timestamp>/report.md` 和 `report.json`。

## CurrentWord 长播放测试

命令：

```bash
./tools/smoke/current_word_long_play_test.sh --duration 90 --json
```

用途：

- 验证 Latency Optimization V2.3 在真实播放窗口中是否持续发送 `currentWord`。
- 比较 Sony `CurrentWordPush` 和 iOS accepted `currentWord`。
- 统计 `playbackState` 与 `currentWord` 比例、stale discard、main stall、execution gap、receive interval 和 latency。

前提：

- iPhone USB 连接、解锁，iOS App 已连接 Sony。
- Sony USB 连接，PlayerAgent BLE service 已启动。
- QQ音乐正在播放有逐字时间的歌曲，最好是快歌。
- 测试窗口建议 60-120 秒。

报告：

- 默认输出 `/tmp/current_word_long_play/<timestamp>/report.md` 和 `report.json`。
- 同时保存 `ios_ble.log`、`sony_logcat.log`、`ios_current_word_filtered.log`、`sony_current_word_filtered.log`。

该测试不接入普通 Required smoke，因为它依赖人工保证当前歌曲、播放状态和测试时长。

## Control E2E V2.9 真实交互测试

命令：

```bash
./tools/smoke/control_e2e_v29_test.sh --duration 75 --json
```

用途：

- 验证 iOS 真实发送命令、Sony 真实收到命令、iOS 再收到状态变化的完整闭环。
- 覆盖 `PLAY_PAUSE`、`NEXT`、`PREVIOUS`、`VOLUME_UP`、`VOLUME_DOWN`、`SEEK_TO`、`GET_FULL_LYRICS`、AlbumArt 请求。
- 统计 command sent/received/success、playbackState、trackChanged、currentWord、fullLyrics、albumArt binary、stale discard、payload too large、main stall、命令延迟和命令后状态延迟。

前提：

- iPhone USB 连接、解锁，安装 DEBUG iOS App。
- Sony USB 连接，PlayerAgent 控制服务已启动并 advertising。
- QQ音乐正在播放，并且 MediaSession 可用。

结果规则：

- 核心控制 `PLAY_PAUSE`、`NEXT`、`VOLUME_UP`、`VOLUME_DOWN`、`SEEK_TO`、`GET_FULL_LYRICS` 失败时整体 FAIL。
- `PREVIOUS` 允许 WARN，因为播放器播放历史可能不足。
- AlbumArt 允许 WARN，因为当前歌曲可能没有封面或 HQ 不可用，但必须输出明确 reason。
- `stale discard`、`payload too large`、`main stall` 任一出现时整体 FAIL。

报告：

- 默认输出 `/tmp/control_e2e_smoke/<timestamp>/report.md` 和 `report.json`。
- 同时保存 `ios_ble.log`、`sony_logcat.log`、`ios_control_e2e_filtered.log`、`sony_control_e2e_filtered.log`。

该测试与普通 full smoke 不同：full smoke 证明构建、安装、启动和基础日志正常；Control E2E 证明真实 BLE 交互链路能完成用户操作。

## V2 实时性能矩阵

```bash
./tools/smoke/lyrics_timeline_v34_test.sh --duration 145 --json
./tools/smoke/current_word_long_play_test.sh --duration 90 --json
./tools/smoke/album_art_v27_long_play_test.sh --duration 90 --json
```

- 10 首矩阵必须看到实际 10 个不同 trackId、无 duplicate/skipped/trackNotChanged、无旧歌词或旧封面覆盖。
- `fullLyricsSendStart/End` 同时覆盖 A2 与 legacy，不能因协议升级造成 trace 缺失。
- 冷测使用调试流程自然换歌，不删除用户真实 QRC/封面缓存。
- 自动切歌必须通过 iOS BLE 控制或 Android `cmd media_session dispatch next`；不要向无焦点 PlayerAgent Activity 注入 `input keyevent`，否则系统可能产生与业务线程无关的 input-dispatch ANR。
- Sony-only 模式的当前歌词刷新必须通过 Debug Receiver 的 `com.example.playeragent.debug.REFRESH_CURRENT_LYRIC` 转发；正式前台服务保持 `exported=false`，shell 不能直接启动。
- iPhone 锁屏导致 `ios_app_launch_failed` 时测试不得执行动作，解锁后整项重跑。

## iOS BLE 硬前置校验

所有会依赖 iOS BLE 的真实链路测试，都必须先通过 [ios_ble_precheck.sh](/Volumes/雷电/project/MusicBleController/tools/smoke/ios_ble_precheck.sh)。

硬性条件：

- iOS App launched。
- BLE connected。新的 `didConnect` 或 `[BLE-Restore] restored ... state=connected` 都是有效连接证据。
- notify subscribed。
- 5 秒内收到至少 1 条 `playbackState`。

如果前置校验失败，脚本必须立即 FAIL，`precheckFailReason=ios_ble_not_connected`，并且不得继续执行 `GET_FULL_LYRICS`、AlbumArt 请求、`NEXT`、`VOLUME`、`SEEK` 等动作。报告必须输出 `iosAppLaunched`、`iosBleConnected`、`notifySubscribed`、`firstPlaybackStateReceived`、`firstPlaybackStateLatencyMs`、`precheckResult`、`precheckFailReason`。

## iOS XCTest

```bash
xcodebuild \
  -project IOSBleFeasibility/IOSBleFeasibility.xcodeproj \
  -scheme sonyMusic \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  CODE_SIGNING_ALLOWED=NO \
  test
```

`PerformanceStabilityTests` 覆盖 A1/A2 分发、乱序/重复/缺包、CRC/zlib 固定向量、封面降采样和损坏数据、快照过期/损坏、状态切片去重及播放时钟暂停。新增协议或缓存行为时，应先增加确定性 XCTest，再跑真机 smoke。

## Source Capability V3.0 源可用性诊断

命令：

```bash
./tools/smoke/source_capability_v30_test.sh --duration 150 --json
```

用途：

- 聚合 Sony `[TrackCapability]` 结构化日志和 iOS 日志，判断每首歌歌词/封面是 READY_FAST、READY_SLOW、UNAVAILABLE、PARSE_FAILED、LOAD_FAILED 还是 SOURCE_NOT_PROVIDED。
- 统计 qrc lookup、parse、albumArt load 的 avg/p95，以及 FullLyrics/AlbumArt 请求后发送延迟。
- 统计 `CurrentWordFence` 拦截、iOS stale discard、payload too large、main stall。

前提：

- iPhone USB 连接、解锁，安装 DEBUG iOS App。
- Sony USB 连接，PlayerAgent 控制服务已启动。
- QQ音乐正在播放。测试窗口内建议手动切 3-5 首歌，每首至少停留 30 秒。

结果：

- 报告输出 `/tmp/music_ble_capability/<timestamp>/report.md` 和 `report.json`。
- 如果 source fields 均缺失且歌词/封面不可用，报告会标记 `SOURCE_NOT_PROVIDED`，用于说明瓶颈在 Sony/QQ音乐/MediaSession 源头，而不是 BLE 传输。

## iOS-only 关键状态

- Required tests：
  - iOS Build
  - iPhone Install
  - App Launch
  - Log File
  - Preferences
  - File Checks
- Optional tests 不影响退出码；WARN/SKIPPED 允许，因为 Sony 可能不在线。
- `report.json.summary.overall_result` 只看 Required。
- `albumArtFlow` 顶层字段包含 AlbumArt 详细诊断。

## Android/Sony-only 关键状态

- Required tests：
  - Android Build
  - Device Check
  - APK Install
  - App Launch
  - Crash Check
  - Logcat
  - App Data
  - Cache Dirs
- Optional tests：
  - BLE Service
  - PlaybackDiff Flow
  - CurrentWord Flow
  - QRC Cache
  - QQMusic Dir
- Optional WARN 常见于用户没有启动 PlayerAgent BLE service，不代表 Required 失败。
- PlaybackDiff Flow 在没有 iPhone subscriber 时返回 SKIPPED；有 subscriber 但样本不足时返回 WARN。真实连接并播放 2-3 分钟后，期望 `skipCount > pushCount`。
- CurrentWord Flow 在没有 iPhone subscriber、旧 Sony build 或当前歌曲无逐字时间时返回 SKIPPED/WARN；真实连接并播放有逐字歌词的歌曲时，期望 `pushCount > 0`。
- Optional FAIL 用于 FATAL/ANR 或 GATT/advertising 失败且没有 recovery success。
- `--no-debug-control` 同时关闭 BLE optional 与 health watchdog 的 START/RECOVER 广播、调试等待和日志刷新；这两项检查只分析已有日志。suite 的正常启动、日志采集和 full 模式构建安装仍会执行，App 自身的服务自动启动不受该参数关闭。
- watchdog 报告记录 `debugControlEnabled/recoverCommandAttempted/recoverCommandRc/recoverCommandSkipReason`；未尝试时 rc 为 null，不能用广播 rc=0 冒充恢复成功。START 报告也区分收到、转发、权限拒绝，只有 GATT/advertising 证据才能证明 BLE 已运行。
- 缺 BLE 权限时 Debug Receiver 拒绝冷启动动作；STOP 直接停止服务。服务自身捕获前台提升的权限异常，安全退出并拒绝继续执行命令。日志刷新不得抹掉已捕获的 FATAL/ANR。
- release build 不包含 debug control receiver，`PlayerAgentForegroundService` 仍保持 `exported=false`。

## Cross-device 关键状态

- `PASS`：所有已运行 suite Required 通过，且没有 Optional 严重 FAIL。
- `WARN`：Required 通过，但缺一台设备导致 suite `SKIPPED`，或 Optional 有 WARN。
- `FAIL`：任一已运行 suite Required FAIL，显式指定的 suite 缺设备，或子脚本异常无报告。
- 退出码：`0` 表示 PASS/WARN，`1` 表示 FAIL。

## 缺权限调试恢复修复验证（2026-11-06 至 2026-11-07）

基线 `1ae52b5`，分支 `codex/ble-recovery-permissions`。本次修复 Sony 服务启动权限保护和 Android smoke 参数遗漏，不改 BLE UUID、消息字段、数据库或 iOS 代码。

| 检查 | 结果与边界 |
|---|---|
| Sony JVM | 148 项通过，无失败或跳过，包含 API 23/30/31/35 的权限组合 |
| 无设备脚本测试 | 14 项通过，覆盖 START/RECOVER 开关透传、权限拒绝、未尝试状态和 FATAL 保留 |
| Android 生命周期 | 2 项缺权限、1 项授权且无通知权限的实际测试全部通过，无 assumption skip；直接 FGS 拒绝后等待超过设备配置的 30000 ms 超时，无延迟崩溃或重启 |
| 缺权限完整冒烟 | Required 8/8，Optional 1 PASS / 4 WARN / 2 SKIPPED / 0 FAIL；START 和 RECOVER 被拒绝，不再崩溃 |
| 最终缺权限 quick | Required 8/8，watchdog 明确记录 `manual recover skipped: permission_missing` |
| 禁用调试控制 quick | Required 8/8；无 START/RECOVER 调试广播，watchdog attempted=false、rc=null |
| 权限完整的完整冒烟 | Required 8/8，Optional 4 PASS / 1 WARN / 2 SKIPPED / 0 FAIL；GATT、advertising 与手动恢复均有日志。WARN 为无 QQMusic 目录，SKIPPED 为无 iPhone 订阅者 |
| 构建、lint、release 边界 | Debug 与 instrumentation APK 构建成功；lint 0 error / 30 个既有 warning；生成的 release manifest 不含 Debug Receiver，正式服务仍 exported=false |

环境为专用 API 37 模拟器 `AuditMusicBle`、JDK 17；不代表 Sony 真机、旧版 Android 系统或 iPhone 端到端验收。未修改 Controller，未将旧的 Controller XML 记作本批重跑。iOS quick/full 不适用，未运行；已有 iOS 文案改动保持原样。

中途一轮 quick 的启动检查为 FAIL（7/8）：权限切换后残留的系统 `GrantPermissionsActivity` 任务被 monkey 复用，目标进程未拉起，未观察到 FATAL/ANR。对专用模拟器的残留权限弹窗和目标 App 执行 force-stop 清理后，重新运行通过；原失败报告未删除，不计为通过轮次。

完整清单位于本次工作机 `/tmp/musicble-ble-guard-20261106/verification.json`，包含原始失败、最终分组测试和各轮报告。该路径为临时证据，非长期归档。新权限 helper 仅对注入 SDK 判断中的内联权限名称局部说明 `InlinedApi` 误报；未屏蔽历史 lint 告警。

## 本轮自用范围与多设备修复（2026-11-07）

Android `--device` 现在向子脚本导出所选 serial，多台设备在线时仍只检查指定设备；设备检查失败时，`--json` 也输出报告位置与失败摘要，进程保持失败退出。相关无设备回归共 16 项通过，多设备环境下 Sony quick 为整体 PASS、Required 8/8，使用 `--no-debug-control`，未触发调试 START/RECOVER。

本轮最终 iPhone full 与同构建 quick 均整体 PASS、Required 6/6。完整回归及证据边界见[优化进度](OPTIMIZATION_PROGRESS.md)和[CI 验证](CI_VALIDATION.md)。用户已取消真实两端性能采样、120 分钟长稳、双控制器和能耗对照；这些不属于当前 smoke 必选项，也没有记为通过。既有性能工具仅供以后主动手动使用。

## 不允许随便修改的点

- iOS smoke 工具不得调用 `adb`、`logcat` 或 Android Gradle 构建。
- Optional WARN/SKIPPED 不得让 suite 失败。
- `--quick` 必须跳过 build/install。
- `--json` stdout 应保持机器可读。
- Android smoke 工具不得调用 `devicectl`，不得操作 iPhone，不能删除 QRC/QQMusic/用户数据。
- Cross-device 总入口不得直接操作业务数据，只能编排子 smoke。

## 常见问题排查入口

- iPhone 锁屏导致 App Launch FAIL：`ios_launch_stderr.log` 会出现 device locked，可解锁后复跑。
- Preferences FAIL：确认安装的是 DEBUG 包且支持 `--smoke-test-preferences`。
- Log File FAIL：确认 App 启动过，且 `ios_ble.log` 有预期关键词。
- AlbumArt Flow FAIL：查看 `album_art_flow.json` 的 `events` 和 `reason`。
- Android Device Check FAIL：确认 Sony 已授权 USB 调试；多台设备在线时使用 `--device <serial>` 或 `ANDROID_DEVICE_ID` 明确选择目标。
- Android Build FAIL：看 `android_build_stderr.log`。
- Android Launch FAIL：看 `launch_logcat.log`。
- Android BLE Service WARN：可能只是未手动启动 PlayerAgent 前台服务。
- Debug control unavailable：确认安装的是 debug build；release 不包含该测试 receiver。
- Cross-device WARN：通常是只连接了一台设备或某个 Optional 检查 WARN。
- Cross-device FAIL：先看总 `report.json` 的 `ios.report_json` / `android.report_json` 指向的子报告。

## 修改后必须跑哪些 smoke test

- 改 smoke 脚本：至少跑 `./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick --json`。
- 改 report JSON schema：检查 `report.json` 是否仍可被 `codex_check.sh` 解析。
- 改 iOS project/Preferences/App container 相关：跑 full。
- 改 Android smoke 脚本：至少跑 `./tools/android-smoke-tests/run_android_smoke_tests.sh --quick --json`。
- 改 Sony Android 普通逻辑：跑 Android quick。
- 改 Android build/manifest/service/permission/install：跑 Android full。
- 改 Debug-only service control receiver：跑 Android full，并验证 release manifest 不包含 receiver。
- 缺权限与恢复回归：运行 `BleServicePermissionTest` 的 denied/granted 分组；权限在 instrumentation 外设置，直接 FGS 失败测试需等待超过系统配置的前台提升超时。设备外脚本测试见 `tools/android-smoke-tests/tests/test_debug_control.py`。
- 改 cross-device 总入口：跑 `./tools/smoke/run_all_smoke_tests.sh --quick --json`。
- 跨端相关改动且两台设备都连接：优先跑 `./tools/smoke/run_all_smoke_tests.sh --quick --json`。
- 验证 CurrentWord V2.3 实时效果：手动跑 `./tools/smoke/current_word_long_play_test.sh --duration 90 --json`，不要把它当作每次 quick smoke 的 Required。
- 验证真实控制链路：手动跑 `./tools/smoke/control_e2e_v29_test.sh --duration 75 --json`，不要把它当作每次 quick smoke 的 Required。
- 诊断歌词/封面源头可用性：手动跑 `./tools/smoke/source_capability_v30_test.sh --duration 150 --json`，测试期间切 3-5 首歌。
