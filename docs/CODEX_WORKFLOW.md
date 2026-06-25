# Codex 开发工作流

本文是给 Codex 的操作规范。执行用户任务时优先遵守本文件和仓库根目录 `CODEx.md`。

## 模块职责

- 根目录 [CODEx.md](/Volumes/雷电/project/MusicBleController/CODEx.md)：iOS 改动后的强制 smoke 工作流。
- `docs/`：固定架构上下文，减少每次任务重复摸索。
- `tools/ios-smoke-tests/`：iOS-only 验证工具。

## 标准流程

1. 先读相关架构文档。
2. 用 `rg` 查调用链和日志关键词。
3. 只修改任务要求范围内的文件。
4. 改业务代码前后注意工作区是否有用户未提交改动。
5. iOS 改动按 `CODEx.md` 跑 quick 或 full smoke。
6. Android/Sony 改动至少跑对应 Gradle build；涉及真机链路时输出未验证项。
7. 提交前跑 `git diff --check`。

## iOS smoke 决策

- Quick：
  `./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick --json`
- Full：
  `./tools/ios-smoke-tests/run_ios_smoke_tests.sh --json`
- Codex 快捷：
  `./tools/ios-smoke-tests/codex_check.sh`

需要 quick 的改动：
- `BLETestManager.swift`
- `ContentView.swift`
- `FullLyricsView.swift`
- `PreferencesView.swift`
- AlbumArt
- Diagnostics
- AutoReconnect / Health Check
- Settings
- Live Activity

需要 full 的改动：
- App 启动/安装
- UserDefaults / Preferences
- App container 文件
- 日志系统
- build setting
- `project.pbxproj`

docs-only 改动：
- 不需要 build。
- 需要 `git diff --check`。

## Git 工作流

- 工作区混杂时，不要 `git add -A`。
- 只 stage 本次任务相关文件。
- 提交信息要短，能说明范围。
- 用户要求“提交到 GitHub”时：
  1. `git status -sb`
  2. `git diff --stat`
  3. 必要检查
  4. 显式 stage
  5. commit
  6. push

## 不允许随便修改的点

- BLE UUID 和协议。
- QRC 解密核心算法。
- Sony 播放控制/音量控制命令名称。
- Live Activity `ContentState` 轻量化原则。
- iOS smoke 的 no-adb 保证。
- 非任务范围的 UI/协议/缓存重构。

## 常见问题排查入口

- iOS smoke：`/tmp/music_ble_ios_smoke/<timestamp>/report.json`
- iOS App log：App container `Documents/Logs/ios_ble.log`
- Sony logcat：只有用户明确要求或 Android/Sony 任务需要时使用。
- AlbumArt Flow：`album_art_flow.json`
- 当前歌曲诊断：iOS `NowPlayingDiagnosticView` 和 `SystemHealthOverviewView`

## 修改文档后的检查

- `git diff --check`
- 确认 docs 没有写不存在的文件或已废弃链路。
