# Codex 开发工作流

本文是给 Codex 的操作规范。执行用户任务时优先遵守本文件和仓库根目录 `CODEx.md`。

## 模块职责

- 根目录 [CODEx.md](/Volumes/雷电/project/MusicBleController/CODEx.md)：iOS 改动后的强制 smoke 工作流。
- `docs/`：固定架构上下文，减少每次任务重复摸索。
- `tools/ios-smoke-tests/`：iOS-only 验证工具。

## 标准流程

1. 先判定任务类型，并阅读对应架构文档。
2. 用 `rg` 查调用链和日志关键词。
3. 只修改任务要求范围内的文件。
4. 改业务代码前后注意工作区是否有用户未提交改动。
5. iOS 改动按 `CODEx.md` 跑 quick 或 full smoke。
6. Android/Sony 改动至少跑对应 Gradle build；涉及真机链路时输出未验证项。
7. 提交前跑 `git diff --check`。

## 任务类型到必读文档

Codex 修改代码前必须先声明：

- 本次任务类型。
- 已阅读的文档。
- 不允许修改的边界。
- 修改后需要跑的 smoke test / build / 真机验证。

| 任务类型 | 必读文档 |
|---|---|
| iOS BLE 连接 / 自动重连 / Health Check | [IOS_ARCHITECTURE.md](/Volumes/雷电/project/MusicBleController/docs/IOS_ARCHITECTURE.md), [RECONNECT_HEALTH_ARCHITECTURE.md](/Volumes/雷电/project/MusicBleController/docs/RECONNECT_HEALTH_ARCHITECTURE.md), [BLE_PROTOCOL.md](/Volumes/雷电/project/MusicBleController/docs/BLE_PROTOCOL.md), [SMOKE_TEST_GUIDE.md](/Volumes/雷电/project/MusicBleController/docs/SMOKE_TEST_GUIDE.md) |
| AlbumArt / 封面 / HQ / enhanced / cache / transfer timeout | [ALBUM_ART_ARCHITECTURE.md](/Volumes/雷电/project/MusicBleController/docs/ALBUM_ART_ARCHITECTURE.md), [BLE_PROTOCOL.md](/Volumes/雷电/project/MusicBleController/docs/BLE_PROTOCOL.md), [IOS_ARCHITECTURE.md](/Volumes/雷电/project/MusicBleController/docs/IOS_ARCHITECTURE.md), [SMOKE_TEST_GUIDE.md](/Volumes/雷电/project/MusicBleController/docs/SMOKE_TEST_GUIDE.md) |
| 歌词 / FullLyrics / LyricSecondary / 翻译 / 罗马音 / QRC / Recovery | [LYRICS_ARCHITECTURE.md](/Volumes/雷电/project/MusicBleController/docs/LYRICS_ARCHITECTURE.md), [BLE_PROTOCOL.md](/Volumes/雷电/project/MusicBleController/docs/BLE_PROTOCOL.md), [PROJECT_OVERVIEW.md](/Volumes/雷电/project/MusicBleController/docs/PROJECT_OVERVIEW.md) |
| 设置页 / Preferences / UserDefaults | [IOS_ARCHITECTURE.md](/Volumes/雷电/project/MusicBleController/docs/IOS_ARCHITECTURE.md), [SMOKE_TEST_GUIDE.md](/Volumes/雷电/project/MusicBleController/docs/SMOKE_TEST_GUIDE.md) |
| Smoke Test / 自动测试 | [SMOKE_TEST_GUIDE.md](/Volumes/雷电/project/MusicBleController/docs/SMOKE_TEST_GUIDE.md), [CODEX_WORKFLOW.md](/Volumes/雷电/project/MusicBleController/docs/CODEX_WORKFLOW.md) |
| Sony Android / GATT / advertising / BLE recovery | [PROJECT_OVERVIEW.md](/Volumes/雷电/project/MusicBleController/docs/PROJECT_OVERVIEW.md), [BLE_PROTOCOL.md](/Volumes/雷电/project/MusicBleController/docs/BLE_PROTOCOL.md), [RECONNECT_HEALTH_ARCHITECTURE.md](/Volumes/雷电/project/MusicBleController/docs/RECONNECT_HEALTH_ARCHITECTURE.md) |
| 跨端协议 | [BLE_PROTOCOL.md](/Volumes/雷电/project/MusicBleController/docs/BLE_PROTOCOL.md), [PROJECT_OVERVIEW.md](/Volumes/雷电/project/MusicBleController/docs/PROJECT_OVERVIEW.md) |

如果任务横跨多类，读取所有相关文档的并集。不要只读其中一个局部文档。

## 修改边界声明

修改代码前，Codex 应输出类似：

```text
任务类型：AlbumArt / HQ transfer timeout
已阅读：ALBUM_ART_ARCHITECTURE.md, BLE_PROTOCOL.md, IOS_ARCHITECTURE.md, SMOKE_TEST_GUIDE.md
不修改：BLE UUID、AlbumArt binary 协议字段、Sony Android
验证：codex_check.sh；如触及 project.pbxproj 再跑 full smoke
```

修改完成后，Codex 必须输出：

- 修改文件。
- 是否涉及协议。
- 是否需要 iOS quick smoke，以及是否已运行。
- 是否需要 iOS full smoke，以及是否已运行。
- 是否需要 Android build，以及是否已运行。
- 是否需要真机手测，以及未测原因。

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

## Android/Sony smoke 决策

- Quick：
  `./tools/android-smoke-tests/run_android_smoke_tests.sh --quick --json`
- Full：
  `./tools/android-smoke-tests/run_android_smoke_tests.sh --json`

需要 Android quick 的改动：
- Sony Kotlin/Java 普通逻辑。
- BLE recovery / advertising / GATT 逻辑。
- QRC / Lyrics / AlbumArt / PlaybackHistory 的 Sony 端逻辑。
- Sony Debug Tools UI。

需要 Android full 的改动：
- `PlayerAgentApp/build.gradle`
- `AndroidManifest.xml`
- App 启动流程。
- foreground service 启动/权限。
- APK install 相关配置。

iOS 和 Sony 都改动时，两个 smoke 都要跑。跨端 BLE 协议改动时，smoke 只能覆盖基础健康检查，仍必须安排 iPhone + Sony 真机链路验证。

## 协议兼容性规则

默认禁止改协议，除非用户明确要求。

如果涉及以下任一项，必须明确说明是否兼容旧版本：

- BLE UUID。
- command/status characteristic JSON 字段。
- FullLyrics / LyricSecondary payload。
- AlbumArt binary start/chunk/end payload。
- 播放、音量、Live Activity 控制命令名称。

协议改动必须优先保持向后兼容。不能仅修改 iOS 或仅修改 Sony 后假设另一端会同步更新。

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
