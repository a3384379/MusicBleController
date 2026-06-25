# iOS-only Smoke Test 指南

本文记录当前 smoke 工具能力。它只验证 iPhone 端，不控制 Sony，也不使用 adb。

## 模块职责

- `run_ios_smoke_tests.sh`：总入口，设备发现、build/install/launch/log/settings/file checks、optional BLE、optional AlbumArt Flow、生成报告。
- `codex_check.sh`：Codex 快捷入口，运行 quick 并读取 `report.json`。
- `generate_ios_report.py`：生成 `report.md` 和 `report.json`，包含 Git 信息、device、required/optional tests、failure excerpt、AlbumArt Flow。
- `ios_album_art_flow_test.sh`：只读 iOS 日志，分析最近封面链路。

## 核心文件

- [run_ios_smoke_tests.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/run_ios_smoke_tests.sh)
- [codex_check.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/codex_check.sh)
- [generate_ios_report.py](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/generate_ios_report.py)
- [ios_ble_optional_test.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/ios_ble_optional_test.sh)
- [ios_album_art_flow_test.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/ios_album_art_flow_test.sh)
- [ios_collect_logs.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/ios_collect_logs.sh)
- [ios_file_checks.sh](/Volumes/雷电/project/MusicBleController/tools/ios-smoke-tests/ios_file_checks.sh)

## 数据流

1. 选择 iPhone：`--device` > `IOS_DEVICE_ID` > 自动发现 available iPhone。
2. 可选 build/install。
3. `devicectl launch` 启动 App。
4. 从 App container 复制 `Documents/Logs/ios_ble.log`。
5. DEBUG smoke preferences 写入并重启验证。
6. 文件检查 App container 中日志、AlbumArtCache、Preferences。
7. Optional BLE 从 iOS 日志判断 scan/discover/connect/notify/playbackState/healthy。
8. Optional AlbumArt Flow 从 iOS 日志判断 `albumArtOffer`、preview/HQ、binary transfer、cache、enhanced、timeout。
9. 输出 `report.md` 和 `report.json`。

## 关键状态

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

## 不允许随便修改的点

- smoke 工具不得调用 `adb`、`logcat` 或 Android Gradle 构建。
- Optional WARN/SKIPPED 不得让 suite 失败。
- `--quick` 必须跳过 build/install。
- `--json` stdout 应保持机器可读。

## 常见问题排查入口

- iPhone 锁屏导致 App Launch FAIL：`ios_launch_stderr.log` 会出现 device locked，可解锁后复跑。
- Preferences FAIL：确认安装的是 DEBUG 包且支持 `--smoke-test-preferences`。
- Log File FAIL：确认 App 启动过，且 `ios_ble.log` 有预期关键词。
- AlbumArt Flow FAIL：查看 `album_art_flow.json` 的 `events` 和 `reason`。

## 修改后必须跑哪些 smoke test

- 改 smoke 脚本：至少跑 `./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick --json`。
- 改 report JSON schema：检查 `report.json` 是否仍可被 `codex_check.sh` 解析。
- 改 iOS project/Preferences/App container 相关：跑 full。
