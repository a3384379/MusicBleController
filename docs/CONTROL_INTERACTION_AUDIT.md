# iOS 滑杆、后台封面修复与全仓代码审计

审计日期：2026-10-01。审计与修复基线为 `acef77b`；提交和合并状态以关联 GitHub PR 为准。

iOS 音量无法拖动的原因已在模拟器中复现并修复；共用该控件的播放进度也一起修复。喇叭图标保持展示用途，直接拖动音量滑杆即可调节。随后按用户要求修复了后台封面更新及此前审计发现的四类问题，并处理 Sony lint 的全部 20 个错误。修复和自动化通过不能据此宣称整体代码没有缺陷，实机联动验收仍未执行。

## 滑杆根因与修复

旧版 [ContentView.swift](../IOSBleFeasibility/IOSBleFeasibility/ContentView.swift) 中的 `CompactPlayerSlider` 单独绘制轨道与滑块，再将实际接收触摸的 SwiftUI `Slider` 设置为 `.opacity(0.001)`。模拟器中的 `UIHostingController`、`UIWindow.hitTest` 测试确认实际控件有效透明度为 `0.001`，轨道中心没有命中该控件。修复前该回归测试有两项断言失败。

现在由同一个可见的原生 `UISlider` 绘制并处理交互，通过 `UIViewRepresentable` 接入原有绑定。保留细轨道和小滑块外观，将实际触摸高度扩大到 44pt。音量仍按整数档位调整，播放进度仍连续调整；拖动期间保留本地编辑值，沿用原有拖动更新与松手最终提交，禁用时拒绝修改。VoiceOver 调整也完成一次完整的开始与结束回调。

BLE UUID、命令名、歌词和封面 payload、A1/A2 header 均未修改；后台命令调度增加下文说明的当前 preview 例外。

## 后台封面空白的根因与修复

证据：[BLETestManager.swift](../IOSBleFeasibility/IOSBleFeasibility/BLETestManager.swift) 的 `flushCommandWriteQueue`、`albumArtSendCommand` 和 `publishLiveArtworkIfCurrent`。原先命令队列在非 active 状态冻结全部非控制请求，包含 `ALBUM_ART_REQUEST quality=preview`。后台切歌会清理旧歌曲的灵动岛封面，新歌曲即使收到 offer，也无法发出拉图请求；恢复前台才有机会取到新图。这是源码确认的调度缺口，没有用实机后台切歌进行复现。

现在后台可发送当前 artworkId 的 preview，请求从队列中寻找可执行项，避免被冻结的历史或 HQ 请求挡住；切歌后的旧 artworkId 请求会被移除。HQ、历史、诊断和周期同步仍等待前台。BLE offer 到缩略图编码、共享文件写入与 ActivityKit 更新之间使用有限后台任务，各任务最多 15 秒；成功发布、回到前台、断线及系统/本地期限到达均会结束相应任务。已有缓存命中同样经过这条发布链路。

共享 JPEG 使用原子写入和 `completeFileProtectionUntilFirstUserAuthentication`，支持设备首次解锁后在锁屏期间读取。保留 80×80 缩略图、20KB 文件上限、key/revision 和歌曲身份校验，迟到任务不能覆盖新歌曲；图片正文不进入 Live Activity ContentState。

自动化覆盖当前 preview 放行、旧图及 HQ/历史/诊断冻结、绕过冻结队首、共享 JPEG 可解码、文件预算、失败写入保留上一个版本及清理。真实后台唤醒、锁屏文件权限和灵动岛显示未实机验证。后台更新仍取决于 iOS 提供的 BLE 事件执行机会，有限任务不是常驻运行保证，见 [Apple Core Bluetooth 后台说明](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html) 和 [UIKit 有限后台任务说明](https://developer.apple.com/documentation/uikit/extending-your-app-s-background-execution-time)。

## 同批修复的交互问题

| 问题 | 修复 | 验证依据 |
|---|---|---|
| 音量和进度共用的透明滑杆无法命中 | 使用可见原生控件，触摸高度 44pt | 修复前失败、修复后通过的控件命中测试 |
| VoiceOver 等非触摸调节需要结束编辑并提交 | 非触摸修改完成一次编辑生命周期 | 原生无障碍调节回归测试 |
| 设备详情或全屏歌词退出时立即打开诊断，存在弹窗切换竞争 | 记录目标，在原弹窗 `onDismiss` 后打开诊断 | 源码检查及完整 iOS 编译；未执行弹窗端到端测试 |
| 更多菜单、歌词关闭、回到当前行、查看原因和歌词行触摸区域偏小 | 相关触摸区域至少 44pt，关闭按钮保留原图形尺寸 | 布局源码检查及完整 iOS 编译 |
| 全屏歌词上一首和下一首缺少明确无障碍名称 | 增加本地化无障碍标签 | 源码检查及完整 iOS 编译 |

新增回归测试位于 [PerformanceStabilityTests.swift](../IOSBleFeasibility/IOSBleFeasibilityTests/PerformanceStabilityTests.swift)：控件命中、连续拖动只结束一次编辑、无障碍调节提交，以及禁用时不更新值或开启编辑。音量档位和连续进度两种配置均有交互回调覆盖；这些测试没有连接 Sony 或验证远端实际音量。

## 审计覆盖范围

| 范围 | 本次检查 |
|---|---|
| iOS 播放界面 | 主界面两种布局、播放与音量绑定、歌词页、菜单、设置、历史与诊断入口；透明度、禁用状态、动作绑定和弹窗生命周期 |
| iOS 状态与传输 | Observation stores、连接健康与重连、控制队列、音量合并、进度编辑、历史分包与请求收尾、歌词诊断期限、后台 preview 和共享缩略图、Live Activity 控制边界 |
| Sony 端 | 页面导航和动作注册、媒体命令、音量诊断、QRC 缓存与 API 兼容、GATT 与权限 lint；全部现有 JVM 单测和 Debug 构建 |
| Android Controller | 进度与音量 Compose 状态、动作和禁用条件、诊断请求；全部现有 JVM 单测、Debug 构建和 lint |
| 工具与配置 | smoke 报告 Python 测试、42 个 shell 脚本语法、构建与 manifest 检查、改动和既有验收边界 |

结构发现使用重新建立并在修复后刷新的完整代码图谱，调用关系结合真实源文件和 `rg` 核对。本次没有逐项执行全部产品验收场景；系统回调、蓝牙时序和跨端并发也不能仅靠图谱或单测证明正确。

## 此前审计问题的修复结果

P1 表示功能可能持续不可用或在声明支持的平台上崩溃；P2 表示局部交互或诊断状态异常。以下四项均已修复。

### P1 历史同步在缺包或断线后无法重试

证据：[BLETestManager.swift](../IOSBleFeasibility/IOSBleFeasibility/BLETestManager.swift) 的 `syncPlaybackHistory`、`loadMorePlaybackHistory`、`handleHistoryPayloadEnd` 和 `clearConnectionTransports`；[PlaybackHistoryView.swift](../IOSBleFeasibility/IOSBleFeasibility/PlaybackHistoryView.swift) 的刷新禁用条件。

原先同步和分页设置忙碌状态，缺包、长度不符或 JSON 解码失败只记录日志并返回；断线没有重置历史传输和统计队列，也没有响应完成期限，刷新入口可能一直禁用。

现在发送结果、无效 start/chunk、缺包、长度/JSON/envelope 错误、响应类型不符和结构化命令错误统一进入失败收尾。每个历史及统计请求有 30 秒完成期限，成功或失败都会取消期限并清理请求；失败的统计项继续下一项。断线及清空本地缓存重置传输 token、队列和忙碌状态，旧连接的异步解码/合并回调不能改写新连接的界面。同步游标不推进时停止循环并允许重试。回归覆盖缺包、坏 JSON、发送失败、超时、断线、旧响应隔离和统计队列恢复，以及正常分页、游标持久化、统计完成、加载中去重和完成后再次加载；不包含实机丢包注入。

### P1 Sony 使用了高于声明最低版本的 Java API

证据：[PlayerAgentApp/build.gradle](../PlayerAgentApp/build.gradle) 声明 `minSdk 23`，未开启 core library desugaring；修复前 lint 列出 15 个 `NewApi` 错误。

- [CurrentLyricProbe.kt](../PlayerAgentApp/src/main/java/com/example/playeragent/media/CurrentLyricProbe.kt)：将 API 24 的 `Character.UnicodeScript` 改为兼容的 UnicodeBlock 汉字识别。
- [QrcLyricCacheManager.kt](../PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcLyricCacheManager.kt)：将 API 24 的 `ConcurrentHashMap.computeIfAbsent` 改为同步的共享实例初始化。
- [QrcParsedCacheIndexStore.kt](../PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcParsedCacheIndexStore.kt)：移除 API 26 的 java.nio 文件移动；写入先 sync，再在同一目录 rename，失败保留旧索引并清理临时文件。写入串行化，避免 debounce 与 flush 相互覆盖。

新增汉字识别及索引 rename 失败后恢复的 JVM 回归，既有索引持久化测试继续通过。另修复 3 个 `WrongConstant`（API 33 同步返回值使用 BluetoothStatusCodes.SUCCESS，异步 GATT 回调仍使用 GATT_SUCCESS）、1 个 `CoarseFineLocation`（旧系统 coarse/fine 权限配对）和 1 个 `UnspecifiedRegisterReceiverFlag`（ContextCompat 显式 NOT_EXPORTED）。保持 minSdk 23，没有新增 lint suppress 或降低门槛；没有在 Android 6/7 设备运行。

### P2 歌词诊断请求未发出也会一直等待

证据：[BLETestManager.swift](../IOSBleFeasibility/IOSBleFeasibility/BLETestManager.swift) 的 `requestLyricDiagnostic`、`sendCommand`、`handlePeripheralDisconnect`，以及 [LyricDiagnosticView.swift](../IOSBleFeasibility/IOSBleFeasibility/LyricDiagnosticView.swift) 的进入与刷新动作。

原先请求只检查缓存曲目 ID，设置 loading 后即使 `sendCommand` 没有发出，也无法收尾；没有响应期限，断线或无响应时可能一直等待。

现在请求先检查当前曲目、连接和前台状态，再检查发送入队结果；发送失败、Sony unavailable/结构化错误、10 秒响应超时和断线都会结束 loading 并显示本地化可重试原因。新请求取消旧期限，切歌取消旧等待，成功取消期限。回归覆盖发送失败、离线缓存曲目刷新、旧期限隔离、成功及超时；没有执行诊断页面实机端到端测试。

### P2 旧 Android Controller 的音量回包可能覆盖拖动值

证据：[ControllerAppUi.kt](../ControllerApp/src/main/java/com/example/controllerapp/ui/ControllerAppUi.kt) 的 `VolumeControl`。

原先 `remember(playback.volumeCurrent, playback.volumeMax)` 会在旧回包到达时重建拖动值，使滑杆跳回或松手提交旧档位。

按用户追加授权修复旧兼容模块。独立的 `VolumeSliderState` 在拖动时保留本地值，90ms 更新和松手提交都读取该值；松手后恢复远端状态，禁用/断线取消编辑，范围变化约束到合法档位。新增 JVM 回归覆盖旧回包、最终提交、范围缩小和取消编辑；没有执行 Compose UI 实机测试。

## 自动化验证结果

| 检查 | 结果 |
|---|---|
| 修复前的滑杆命中回归 | 预期失败：1 个测试，2 项断言失败 |
| iOS 全部 XCTest | PASS，67 项，0 失败；iPhone 16 Pro 模拟器，iOS 18.3 |
| Sony JVM 单测与 Debug 构建 | PASS，137 项测试，0 失败 |
| Android Controller JVM 单测与 Debug 构建 | PASS，34 项测试，0 失败 |
| Android Controller lint | PASS，0 errors / 30 warnings |
| Sony lint | PASS，0 errors / 34 warnings；修复前 20 errors / 33 warnings |
| smoke 报告 Python 单测 | PASS，49 项 |
| shell 语法 | PASS，42 个脚本通过 `bash -n` |
| 差异格式与既有改动保护 | `git diff --check` PASS；用户已有部署脚本和 InfoPlist 改动保持原内容，String Catalog 原条目保持原值，仅追加两个诊断提示 |

Sony lint 的 20 个错误均已消除。仍有 34 个 warning，包含原有 33 个建议及显式引入与 Controller 一致的 androidx.core 1.13.1 后新增的依赖版本建议；没有为消除 warning 进行全仓依赖升级。既有 V4 验收记录是历史基线，见 [V4_ACCEPTANCE_STATUS.md](V4_ACCEPTANCE_STATUS.md)，本报告不改写此前的实机或性能验收结论。

验证命令：

```bash
xcodebuild -project IOSBleFeasibility/IOSBleFeasibility.xcodeproj \
  -scheme sonyMusic -destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=18.3' \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO test

bash gradlew :PlayerAgentApp:testDebugUnitTest :PlayerAgentApp:assembleDebug \
  :PlayerAgentApp:lintDebug :ControllerApp:testDebugUnitTest \
  :ControllerApp:assembleDebug :ControllerApp:lintDebug
python3 -m unittest discover -s tools/smoke/tests -v
git diff --check
```

Android 验证使用本机缓存的 JDK 17。上述 Xcode 命令用模拟器名称表达，实际执行时以同一模拟器的 ID 定位并将 DerivedData 与结果包放在仓库外。

初次滑杆修复和工具验证保存在 `/tmp/musicble-control-audit-20261001/`，包括修复前的 `BeforeFix.xcresult`、修复后的 `InteractionAudit.xcresult` 和 `python-tests.log`。后台封面及四项审计修复的最终验证保存在 `/tmp/musicble-background-artwork-20261001/`，包括 `BackgroundAndAuditFinal2.xcresult`、`ios-tests-final2.log` 和 `android-checks.log`。最终 67 项通过结果替代了前两轮受测试蓝牙状态回调干扰的结果，以及新增成功路径测试误读私有字段导致的中间编译失败；该测试改为验证公开操作和状态，没有放宽生产代码访问权限。Python 和 shell 工具本轮没有继续修改，沿用同一工作区首轮通过结果。临时产物不提交到仓库。

GitHub 提交前又在独立修复 worktree 中验证了只含本次改动的代码：iOS 67、Sony 137、Android Controller 34 项测试均通过，两端 Android Debug 构建和 lint 通过；本地化只包含本次新增的两个诊断提示。结果保存在 `/tmp/musicble-background-artwork-20261001/publish/IsolatedCommit.xcresult`、`publish/ios-tests.log` 和 `publish/android-checks.log`。GitHub PR 和合并后的 CI 是独立的交付检查。

按用户要求没有实机演示、安装或跨设备 smoke；实机 quick/full smoke 均未执行，模拟器测试不替代 iPhone 与 Sony 的实际联动验收。GitHub 交付只包含本次修复、回归测试、文档及两个新增诊断提示，用户已有的部署脚本、InfoPlist 和其他本地化改动保留在原工作区。
