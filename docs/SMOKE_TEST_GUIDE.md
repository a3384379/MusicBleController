# Smoke Test 指南

本文记录当前 smoke 工具能力。仓库现在有两套 smoke：

- iOS-only：只验证 iPhone 端，不控制 Sony，也不使用 adb。
- Android/Sony-only：只验证 Sony `PlayerAgentApp`，使用 adb，不操作 iPhone。
- Cross-device entry：编排 iOS-only 和 Android/Sony-only，并生成总报告。

## iOS-only 模块职责

- `run_ios_smoke_tests.sh`：总入口，设备发现、build/install/launch/log/settings/file checks、optional BLE、optional AlbumArt Flow、生成报告。
- `codex_check.sh`：Codex 快捷入口，运行 quick 并读取 `report.json`。
- `generate_ios_report.py`：生成 `report.md` 和 `report.json`，包含 Git 信息、device、required/optional tests、failure excerpt、AlbumArt Flow。
- `ios_album_art_flow_test.sh`：只读 iOS 日志，分析最近封面链路。

## Android/Sony-only 模块职责

- `tools/android-smoke-tests/run_android_smoke_tests.sh`：总入口，设备发现、build/install/launch/log/file checks、optional BLE、生成报告。
- `android_device_check.sh`：选择 adb device，处理多设备和 unauthorized。
- `android_build_install.sh`：构建 `:PlayerAgentApp:assembleDebug` 并安装 APK。
- `android_collect_logs.sh`：采集 Sony logcat 和过滤日志。
- `android_file_checks.sh`：检查 app external files、QRC cache、QQMusic public 目录。
- `android_ble_optional_test.sh`：只基于 Sony logcat 判断 GATT/advertising 健康。
- `generate_android_report.py`：生成 Android `report.md` 和 `report.json`。
- Debug build 会通过 `PlayerAgentDebugControlReceiver` 尝试启动 BLE foreground service，减少人工点击 Sony UI。

## Cross-device 模块职责

- `tools/smoke/run_all_smoke_tests.sh`：检测 iPhone/Sony，调用 iOS 和 Android 子 suite，支持自动降级。
- `tools/smoke/generate_all_report.py`：读取子报告并生成总 `report.md` / `report.json`。

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
- [generate_android_report.py](/Volumes/雷电/project/MusicBleController/tools/android-smoke-tests/generate_android_report.py)

## Cross-device 核心文件

- [run_all_smoke_tests.sh](/Volumes/雷电/project/MusicBleController/tools/smoke/run_all_smoke_tests.sh)
- [generate_all_report.py](/Volumes/雷电/project/MusicBleController/tools/smoke/generate_all_report.py)
- [README.md](/Volumes/雷电/project/MusicBleController/tools/smoke/README.md)

## iOS-only 数据流

1. 选择 iPhone：`--device` > `IOS_DEVICE_ID` > 自动发现 available iPhone。
2. 可选 build/install。
3. `devicectl launch` 启动 App。
4. 从 App container 复制 `Documents/Logs/ios_ble.log`。
5. DEBUG smoke preferences 写入并重启验证。
6. 文件检查 App container 中日志、AlbumArtCache、Preferences。
7. Optional BLE 从 iOS 日志判断 scan/discover/connect/notify/playbackState/healthy。
8. Optional AlbumArt Flow 从 iOS 日志判断 `albumArtOffer`、preview/HQ、binary transfer、cache、enhanced、timeout。
9. 输出 `report.md` 和 `report.json`。

## Android/Sony-only 数据流

1. 选择 adb device：`--device` > `ANDROID_DEVICE_ID` > 自动发现单台 online device。
2. full 模式构建并安装 `PlayerAgentApp-debug.apk`；quick 模式跳过 build/install。
3. 用 `monkey -p com.example.playeragent 1` 启动主 App。
4. 检查进程和 launch 后 FATAL/ANR。
5. 采集 `logcat -d`，生成 `sony_logcat.log` 和 `sony_filtered.log`。
6. 检查 `/sdcard/Android/data/com.example.playeragent/files`、QRC cache、ArtworkDiscovery、Logs、QQMusic public 目录。
7. Optional BLE 只从 logcat 判断 GATT server / service add / advertising。
8. 输出 `/tmp/music_ble_android_smoke/<timestamp>/report.md` 和 `report.json`。

## Cross-device 数据流

1. 检测 iPhone：`devicectl list devices`。
2. 检测 Sony：`adb devices`。
3. 两台都存在时分别运行 iOS 和 Android smoke。
4. 只存在一台时自动只跑对应 suite，另一个 suite 标记 `SKIPPED`。
5. 子报告输出到 `<output>/ios/` 和 `<output>/android/`。
6. 总报告输出到 `/tmp/music_ble_smoke/<timestamp>/report.md` 和 `report.json`。

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
  - QRC Cache
  - QQMusic Dir
- Optional WARN 常见于用户没有启动 PlayerAgent BLE service，不代表 Required 失败。
- Optional FAIL 用于 FATAL/ANR 或 GATT/advertising 失败且没有 recovery success。
- `--no-debug-control` 可关闭 Debug-only service control，回到只读 logcat 的旧行为。
- release build 不包含 debug control receiver，`PlayerAgentForegroundService` 仍保持 `exported=false`。

## Cross-device 关键状态

- `PASS`：所有已运行 suite Required 通过，且没有 Optional 严重 FAIL。
- `WARN`：Required 通过，但缺一台设备导致 suite `SKIPPED`，或 Optional 有 WARN。
- `FAIL`：任一已运行 suite Required FAIL，显式指定的 suite 缺设备，或子脚本异常无报告。
- 退出码：`0` 表示 PASS/WARN，`1` 表示 FAIL。

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
- Android Device Check FAIL：确认 `adb devices -l` 只有一台 `device`，且 Sony 已授权 USB 调试。
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
- 改 cross-device 总入口：跑 `./tools/smoke/run_all_smoke_tests.sh --quick --json`。
- 跨端相关改动且两台设备都连接：优先跑 `./tools/smoke/run_all_smoke_tests.sh --quick --json`。
