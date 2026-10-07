# iOS 歌词展示实施与验收

最初实施依据：[分享对话中的方案](https://chatgpt.com/share/6ac318e1-0e14-83e9-baba-a60b2fe442f1)。
初版代码基线：`origin/master`，`7a0bc2a12bcd4c25b37439162b4c8052944dd8f6`，分支 `codex/ios-lyrics-display`。
当前展示修订基线：`f360082c35c5b1100fa974e09196f0adc3f8a241`，分支 `codex/lyrics-display-ux-fixes`。
下方旧轮次验证记录按其原版本保留；当前行为见「用户行为」，本轮证据见文末。这是代码实施记录，真机结果需按实际覆盖范围判断。

## 用户行为

- 控制页右上角 **「词」→ 歌词设置 → 灵动岛显示歌词**：每次 App 冷启动默认关闭。
  同一页面同时管理灵动岛与悬浮歌词，通用设置 → 歌词 → 歌词显示设置也可进入。
  开启后紧凑态保留 26pt 封面和状态标识，当前歌词使用固定 120×26pt 区域；长句尾部截断。
  展开态在摄像头下方预留两行歌词空间。关闭后恢复原来的封面、进度和状态图标。
  原有锁屏、展开态、播放控制继续工作。原来的「灵动岛样式」仍独立控制已有布局。
- **「词」→ 歌词设置 → 悬浮歌词**：每次 App 冷启动默认关闭，开关反映当前显示会话。
  主控制页始终不显示底部预览、悬浮状态或显示按钮，包括开关关闭、开启和窗口活动期间。
  打开页面不会自动显示窗口或激活音频。在页面主动开启会立即请求系统 PiP；关窗后可重新开启或点击「再次显示」。
  若上一个窗口尚在清理，保留本次明确的重开请求，清理完成后再准备新窗口；关闭开关或退出准备会取消该请求。
  真实启动预览仅在设置页准备窗口时临时挂载，并滚动到可见位置；首帧和系统条件满足后启动，最长等待八秒。
  收到启动成功回调后自动关闭设置页，控制器保留显示图层与渲染器以继续展示；退出设置只取消尚未完成的启动。
  支持单行/双行，双行展示当前句和下一句，歌名默认隐藏；行数和歌名设置独立持久化。
  画面基准尺寸为 640×60 单行、640×104 双行。显示歌名才加 24px，播放控制有反馈才加 28px，不保留空白提示行。
  「背景配色」提供暖棕、薄荷、雾蓝、淡紫、玫瑰和深灰，默认暖棕；关窗后保留选择，活动窗口换色立即刷新。
  行数、歌名和配色在关闭状态也可预先调整。正常启动或读回偏好不自动重弹窗口。
  启动失败按预览未显示、首帧未就绪、系统不可用和启动未确认显示不同提示，开关同步关闭。
  单双行、歌名与配色继续持久化；两项开启标记仅存在于当前 App 会话，不恢复旧版保存的开启值。
- 用户或系统关闭 PiP 后开关同步关闭，状态变成「已停止」，不会自动重弹，也不会向 Sony 发送暂停。
  关闭开关释放新增资源，不断开蓝牙；退出页面只取消尚未完成的准备。

紧凑态是否加宽、是否进入最小态，以及 PiP 窗口位置、尺寸和控件，均由系统决定。

## 实际接入点

| 文件 | 职责 |
|---|---|
| `PreferencesStore.swift` / `LyricsDisplaySettingsView.swift` / `PreferencesView.swift` | 两项当前会话开关、独立歌词设置页、持久化样式、运行状态和重试入口 |
| `LyricsPresentationSnapshot.swift` | 小型展示投影、歌曲代际/行/时间轴身份、PiP 状态机、播放目标去重、最新帧队列 |
| `BLETestManager.swift` | 从已接受的播放状态、CurrentWord 和既有完整歌词派生快照；切歌清理旧行；复用既有控制通道 |
| `SonyMusicActivityAttributes.swift` | 可变紧凑歌词模式；旧 JSON 缺失字段按 false 解码；显示文本字节上限 |
| `LiveActivityManager.swift` | 复用原有单次在途、最新待提交状态；开关变化参与语义去重；编码内容小于 4KB |
| `SonyMusicLiveActivityWidget.swift` | 开关控制紧凑态；最小态继续展示标识；过期紧凑态降级为等待同步 |
| `LyricsSampleBufferRenderer.swift` | utility 串行队列上的 640×60 单行 / 640×104 双行画面，歌名与反馈行按需增高；每个缓冲池最多三个像素缓冲、单调 host-clock 帧时间 |
| `LyricsPictureInPictureController.swift` | AVKit 启停、真实成功回调、超时/中断清理、最多一帧在途与一帧待绘制 |
| `LyricsPictureInPictureSourceView.swift` / `LyricsDisplaySettingsView.swift` / `ContentView.swift` | 「词」入口；独立歌词设置页显式启动时挂载真实且可见的 sample-buffer layer，成功后返回播放器；主控制页不挂载悬浮预览 |
| `Localizable.xcstrings` | 简体中文和英文文案 |
| `LyricsPresentationTests.swift` | 偏好、旧模型、payload、身份、竞态、控制去重及像素/帧时间验证 |

不新增蓝牙管理器、歌词请求、解析器、完整歌词缓存或云端 ActivityKit 推送。
BLE UUID、JSON/A1/A2/FullLyrics/secondary 协议、Sony 和 Android 代码不变。

## 生命周期、后台与资源

- 展示身份包含 `trackID + generation + lineIndex + timelineRevision`，文本修正也改变 revision。
  切歌立即失效旧歌词；异步绘制完成后复核最新身份。帧时间使用 host clock，不使用会倒退的歌曲位置。
- 切歌后的旧缓存不会冒充已同步播放。连接清理使旧时钟失效；恢复后等待新的权威播放/CurrentWord 状态。
- 正在播放的时钟有效期复用 Live Activity 的 45 秒；暂停复用 5 分钟。使用单调 uptime 判定，不随系统校时跳变。
  明确断线、陈旧、同步中、前奏、间奏和无歌词分别降级，不无限推进缓存歌词。
- 同一行的逐字事件不额外刷新 Activity 或逐字绘制 PiP。PiP 真正 active 后才运行 1Hz 画面心跳，停止立即取消。
  该心跳只维护画中画画面和有界时钟投影，不发送 BLE 轮询。显式启动的准备阶段每 200ms
  复核一次可见性/首帧/系统条件，最多八秒，成功、失败、关闭和离开前台时取消，不是后台轮询。
- AVKit 的异步委托回到 MainActor；同步播放状态查询使用锁保护的小型镜像，不跨线程读取 BLE 门面。
- PiP 播放控件使用「确保播放/暂停」语义，只有权威状态与目标不同且没有待确认请求时才调用既有 toggle 通道。
  旧活动的迟到委托不能控制新活动。关窗的 willStop 回调同步封锁控制，排队中的 didStart 不能重新开放控制。
- 默认关闭时没有 PiP 控制器、缓冲池、渲染心跳或音频激活。仅显式显示时取得 `.playback/.moviePlayback/.mixWithOthers`
  音频会话，停止后在配置仍归本功能持有时恢复。工程增加 `audio` 后台能力用于 PiP，保留 `bluetooth-central`。
  不播放静音音轨、不伪造视频通话，不把蓝牙后台唤醒当作永久运行保证。

## 26 项验收矩阵

以下矩阵根据已读取的最新方案正文与当前实现整理。分享中的 sandbox 附件下载链接无法直接读取，未作为已阅读的验收证据。
自动化代码通过后，仍需按下面的真机项目逐项记录结果。未执行不得填写 PASS。

| # | 场景 | 判定/证据 |
|---:|---|---|
| 1 | 新配置 | 两项默认关闭，XCTest |
| 2 | 旧配置升级 | 旧灵动岛样式不启用新开关，XCTest |
| 3 | 重启和重载 | 冷启动两项关闭、旧开启标记清除；样式持久化，当前会话 load 不关闭活动窗口，XCTest |
| 4 | 四种组合 | 独立生效、重置后关闭，XCTest；组合真机待测 |
| 5 | 关闭紧凑歌词 | 原有紧凑/展开/锁屏保持，源码与 Widget 构建；真机待测 |
| 6 | 同一行修改开关 | ContentState 变化并立即请求更新，XCTest/接入审查；真机待测 |
| 7 | 旧活动恢复 | 无新字段的 JSON 正确解码，XCTest |
| 8 | 长中英/emoji/RTL | 单行尾部截断、13pt 可读；PiP 像素附件；灵动岛真机待测 |
| 9 | 多活动竞争 | 最小态标识降级；实际系统选择待真机验证 |
| 10 | payload 上限 | 组合 emoji 和转义字符的实际编码 <4096 字节，XCTest |
| 11 | 切到另一首歌 | 新身份立即清除旧行；继承已有协议 generation 栅栏 |
| 12 | 同一 ID 新 generation | 不保留前代歌词，XCTest |
| 13 | 两行相同文本 | 行号变化不误去重，XCTest |
| 14 | 同行/倒退 seek | 时间轴 revision 改变，帧 PTS 单调，XCTest |
| 15 | Sony 外部 seek | 已接受的 playbackState 倒退跳变更新展示时间轴，XCTest；Sony 实际链路待真机验证 |
| 16 | 暂停/继续 | 行和播放状态投影一致；PiP 与 Sony 联动待真机验证 |
| 17 | 不支持/启动失败 | 开关回到关闭，样式保留，状态和原因可见，状态机/设置 XCTest；设备限制待真机验证 |
| 18 | supported 与 possible | 分别检查设备能力与当前条件；当前窗口行为待真机验证 |
| 19 | 启动中关闭/再开启 | 旧 generation 的成功回调被拒绝，XCTest |
| 20 | 用户/系统关窗 | 开关关闭、样式保留、停止资源、不自动重弹；明确重开等待清理后执行，状态机/设置 XCTest；真机待测 |
| 21 | 关窗时 Sony 继续播放 | stop 路径不调用播放命令，源码审查；真机待测 |
| 22 | 两项关闭 | 音频会话不改变，XCTest；后台 CPU/内存待真机测量 |
| 23 | PiP 实际画面 | 像素格式/尺寸、可见文字、帧时间，XCTest 附件；跨应用窗口待真机验证 |
| 24 | 事件突发/迟到帧 | 1000 次突发只保留最新待绘制状态、同行不重绘、失效/过期身份拒绝迟到帧，XCTest；持续压力与 p95 待真机验证 |
| 25 | 断线/过期/恢复 | 冻结并降级，等待身份与时钟复核；蓝牙后台、锁屏、长时间运行待真机验证 |
| 26 | 本地音频/视频/电话中断 | 混音、会话所有权清理、停止后不重弹；真机及发布适用性待验收 |

## 验证记录

2026-10-05，所有开发和代码验证均针对独立工作树
`/Users/sqz/.codex/worktrees/ios-lyrics-display/MusicBleController`。
原工作目录中既有的本地化与部署脚本修改保留，未合入本任务。

| 检查 | 结果 | 证据 |
|---|---|---|
| iPhone 16 Pro / iOS 18.3 模拟器 XCTest | **PASS 87/87**，20 个新增歌词展示测试与原有 67 个测试 | `/private/tmp/musicble-lyrics-tests-close-guard.xcresult`、`/private/tmp/musicble-lyrics-xcode-close-guard.log` |
| 最新代码的 generic iOS 构建 | **BUILD SUCCEEDED**，无签名编译 App 和 Widget | `/private/tmp/musicble-lyrics-device-build-close-guard.log` |
| PiP 渲染附件 | 640×360 BGRA 像素与单调 PTS 通过测试；已目视检查中英与 emoji 文字 | `testSampleBufferPixelsAndTimestampsRemainValidAcrossBackwardSeek` 的 xcresult PNG 附件；这不是系统 PiP 窗口截图 |
| quick smoke | **overall FAIL，Required 2/6**；build/install 被 quick 模式跳过，不能算作验证通过 | `/private/tmp/musicble-lyrics-quick-smoke/report.json` |
| full smoke | **overall FAIL，Required 0/6**；原始签名构建成功，设备安装失败，运行相关项目未完成 | `/private/tmp/musicble-lyrics-full-smoke/report.json`、`ios_build.log`、`ios_build_install_stderr.log` |
| 差异与工程格式 | `git diff --check`、App Info.plist 与 project.pbxproj 格式检查通过 | 最终工作树检查 |

设备列表中的 `Tm iPhone` 状态为 `unavailable`。full smoke 把 build/install 合并记为失败，
但原始 `ios_build.log` 明确记录 **BUILD SUCCEEDED**；安装的 CoreDevice 错误为无法定位请求设备。
App launch、日志、偏好和容器检查因此无法完成，optional BLE/AlbumArt/CurrentWord 均跳过。
full smoke 之后的关窗保护修订已重新执行上表的模拟器测试和 generic iOS 构建。

修改前已确认主仓库图谱 `Volumes-e99bb7e794b5-project-MusicBleController` 可用，并完成架构、符号、调用关系与源文件核对。
修改后对独立工作树的完整索引与 iOS 范围索引均返回 worker crash。
`detect_changes` 仍定位主仓库，返回的是主工作目录原有修改，不能作为本工作树的结构影响结论。
因此本次编辑后的图谱影响验证未完成；实际改动使用源码搜索、差异审查、编译和 XCTest 复核。
未生成仓库内持久化图谱文件。

初次交付遵循 [CODEx.md 的 Pass Criteria](../CODEx.md)：smoke overall 未达到 PASS，因此代码当时留在独立工作树，未提交、推送或合并。
2026-10-05，在已告知 smoke 失败与真机验收缺口后，用户明确要求「提交合并到 github」。
本次按该明确授权继续提交与 PR 合并流程；以下真机项目仍保持未验收状态，CI 结果不会替代设备验收。
跨应用 PiP、iPhone 音频兼容、后台歌词、蓝牙设备链路和灵动岛多活动布局仍需按矩阵做真机验收，不能用模拟器结果代替。

## 2026-10-05 源码审计补修

基线为 PR #9 合并后的 `4c6eb6129666e0cc929da71b4795756ab18f39d3`，
分支为 `codex/ios-lyrics-audit-fixes`。再次读取分享页时，公开的最后一条仍是上述设计方案，
正文注明未完成源码审计，未提供针对 PR #9 的新问题清单。
以下三项来自按方案风险检查当前 master 的实际源码，不冒充分享页新增的审计结论。

| 问题 | 修复与边界 |
|---|---|
| 显示队列暂时不可写，或绘制期间变为不可写，最新帧会被去重或提前消耗 | 最新快照保留到实际可入队；一次 readiness 请求唤醒后立即配对停止请求。同行事件仍不创建额外帧，但可以推进已有待处理快照。停止会取消请求，旧会话回调不能影响新会话。 |
| 达到三个像素缓冲的分配上限，被当成永久绘制失败并关闭 PiP | 单独识别 `kCVReturnWouldExceedAllocationThreshold`，保留最新待处理快照。清理待显示帧但保留当前画面，等待 flush 完成后重试一次；再次耗尽时等待下一次有效内容变化或已有 active 心跳，不增加轮询定时器。显式再次显示也刷新快照。 |
| 系统关窗只改状态并取消心跳，未复用用户停止的超时清理 | `willStop` 同步封锁播放控制，再进入公共停止清理路径；缺失 `didStop` 时沿用两秒兜底，释放控制器、渲染资源与音频租约。不会再次请求系统停止，也不会向 Sony 发送暂停。 |

绘制的临时 UIKit/CoreVideo 对象在 utility 队列的 autorelease pool 内释放。
异步 flush 使用弱引用并复核会话 generation 和 renderer 身份；停止或重开后到达的完成回调无效。
队列仍限制为一帧在途、一个最新待处理快照，像素缓冲上限仍为三个。
永久的像素创建、上下文、格式或 sample-buffer 错误仍走原有失败提示与资源清理。

缓冲池回收通知在本次模拟器回归中未按预期到达，因此最终恢复路径使用显示队列的 flush 完成回调。
相关 API 依据：[分配阈值](https://developer.apple.com/documentation/corevideo/kcvreturnwouldexceedallocationthreshold)、
[显示队列清理](https://developer.apple.com/documentation/avfoundation/avsamplebuffervideorenderer/flush(removingdisplayedimage:completionhandler:))、
[readiness 请求](https://developer.apple.com/documentation/avfoundation/avsamplebuffervideorenderer/requestmediadatawhenready(on:using:))。

本次仅修改 PiP 控制器、最新帧队列、像素绘制器、对应测试及本记录。
BLE 管理、命令、Sony/Android、偏好格式、Widget 布局和项目配置均未修改。

| 检查 | 本次结果与证据 |
|---|---|
| 模拟器完整 XCTest | **PASS 91/91**，新增四项回归：不可写重试、较新心跳快照保留、旧身份/停止后不得重试、实际三个缓冲耗尽并释放后恢复；`/private/tmp/musicble-lyrics-audit-tests-close.xcresult`、`/private/tmp/musicble-lyrics-audit-tests-close.log` |
| generic iOS App / Widget 构建 | **BUILD SUCCEEDED**，无 Swift 编译警告；`/private/tmp/musicble-lyrics-audit-device-build-final.log` |
| quick smoke | **overall FAIL，Required 2/6**，两项 PASS 实际为跳过 build/install；设备仍为 unavailable，启动、日志、偏好与容器无法验证；`/private/tmp/musicble-lyrics-audit-quick-smoke/report.json` |
| 源码与结构影响复核 | 最终差异、调用位置及 `git diff --check` 通过；工作树完整索引仍 worker crash，`detect_changes` 仍返回主目录原有六项修改，不能作为本次影响验证证据 |
| 系统关窗、跨应用 PiP、后台/BLE/锁屏与音频兼容 | **未完成真机验收**；状态机与清理路径经测试/源码复核，但不替代实际 AVKit 系统回调及设备测试 |

用户本轮明确要求修改后提交并合并 GitHub，按该授权执行 PR 和 CI 流程。
本次 quick smoke 的设备限制与既有真机缺口继续保留；原主工作目录的六项未提交修改不纳入本次提交。

## 2026-10-05 用户提供的 R1–R4 审计整改

基线为 PR #10 合并后的 `12a61e2911e367893bf5fb8794b92b8c83ad833c`，
分支为 `codex/ios-lyrics-audit-round2`。问题清单来自用户在本轮提供的最新源码审计正文。
原分享页仍提供设计方案，本节不把该页面当作新审计或新提交的证据。

| 条目 | 本次整改 | 回归证据 |
|---|---|---|
| R1 / P1：快速回切开关丢失最后选择 | 先保留在途或防抖期间的最新待提交状态，再按已完成状态去重；提交完成后重新协调最新期望。结束活动会失效旧代际的提交、防抖和过渡回调。 | 使用真实 `LiveActivityManager.update` 队列与可暂停的发布接口，观测 OFF → ON 在途 → OFF 的实际 ContentState 提交顺序、防抖回切、突发合并及结束后的迟到完成；不只比较模型字段。 |
| R2 / P1：展示变化与发布去重不一致 | 去重使用同一 `LyricsPresentationSnapshot.key`，包括 intro、instrumental、syncing、stale 等展示语义；将这份快照传入发布。延迟合并时重新计算最新投影。原歌词解析与缓存回退保持独立。 | 从 BLE 管理器收到的完整歌词与播放回报驱动实际发布接口；验证前奏 → 第一行 → 间奏 → 同文第二行的 ContentState 文本和行号。前奏和第一行仍可解析到同一原始行，但必须发布不同展示内容。 |
| R3 / P2：未接受锚点仍续期 | 先验证播放采样，再更新播放字段；拒绝陈旧或缺失位置的回报。只在实际应用位置锚点后确认展示时钟。有效期由最后确认时的播放状态决定，拖动中收到暂停回报不会把未更新的 45 秒时钟延长到 5 分钟。CurrentWord 同样仅在应用位置时续期。 | 注入可信单调时钟映射，发送过期 `sampleMono`，确认内容、播放状态、有效期和控制确认均未改变；推进时钟后必须 stale。拖动跳过锚点及缺失位置也不得续期；随后合法的无时钟旧协议播放回报可以恢复同步并协调控制。 |
| R4 / P2：最后播放目标未兑现、超时盲目重发 | 分别维护最后期望、已确认状态、在途 toggle 与确认修订号。新的已接受播放回报确认在途目标后，自动发送尚未兑现的最后目标；不需要再次请求。超时及仍为原状态的新回报均不清除未决命令。未发送的后续命令遇到既有防抖或写入门控时，做一次有界延迟复核；永久拒绝不会成为在途命令。目标兑现后释放意图，允许之后的 Sony 控制。切歌、断线清理，PiP 停止取消未发送意图，但保留已发送命令的未决保护。 | BLE 管理器的实际控制接入测试验证暂停在途 → 请求播放 → 暂停回报 → 自动第二次发送；另测超过三秒不重发、相同状态回报、延迟门控、同 ID 新代际、断线、PiP 停止及发送拒绝。原去重测试改为由确认回报自动协调，不再依赖第二次主动请求。 |

发布接口、时钟和命令接口通过内部初始化依赖注入，使测试控制在途完成并观测生产队列的输出；
生产默认仍使用原 ActivityKit 发布器、系统单调时钟及 `sendLiveActivityCommand`。
这些测试不声称系统灵动岛或 Sony 真机已实际收到内容与命令。

R5 的 readiness 唤醒、有效快照重试、缓冲耗尽恢复和系统停止公共清理路径保持。
PiP 停止仅额外通知 BLE 管理器取消未发送的播放意图，不主动发送暂停，不改变渲染恢复逻辑。
BLE 协议、Sony/Android、偏好持久化格式、Widget 布局及工程配置未修改。

| 检查 | 本轮结果与证据 |
|---|---|
| 最终完整模拟器 XCTest | **PASS 104/104**，其中 37 个歌词展示测试和原有 67 个稳定性测试；在原 91 项基础上新增 13 项，并修正原播放目标测试的确认方式。`/private/tmp/musicble-lyrics-r1234-tests-final.xcresult`、`/private/tmp/musicble-lyrics-r1234-tests-final.log` |
| 最终 generic iOS App / Widget 构建 | **BUILD SUCCEEDED**，无 Swift 编译警告；`/private/tmp/musicble-lyrics-r1234-device-build-final.log` |
| quick smoke | **overall FAIL，Required 2/6**；两项 PASS 是 quick 模式跳过 build/install，不能视为设备验证。`Tm iPhone` 仍 unavailable，启动、日志、偏好和容器未验证，三个可选链路跳过。`/private/tmp/musicble-lyrics-r1234-quick-smoke/report.json` |
| 图谱与结构影响 | 修改前查索引、架构、精确符号与调用路径，并读取真实源码。工作树完整索引与 iOS 范围索引再次 worker crash；编辑后 `detect_changes` 仍返回主工作目录的六项原有修改（重复列出），无法验证本工作树影响。使用实际源码、差异、编译与测试复核；未生成仓库内图谱文件。 |
| 真机验收 | **仍未完成**。跨应用 PiP、灵动岛布局、Sony/BLE、锁屏后台、音频中断与长期运行不能由这些测试关闭。 |

用户明确要求按本轮审计修改后提交并合并 GitHub，继续按该授权执行。
本地自动化通过与设备验收未通过分别保留，合并不等于真机验收或发布通过。

## 2026-10-06 最新审计 N1/N2 收尾

审计来源为[新的分享对话](https://chatgpt.com/share/6ac43a6b-7e20-83e9-aea7-0d6506220344)，
实际远端与开发基线均为 `b57ba7f0893a8fa0cc7353f1ab639b4e39726745`。
分支为 `codex/ios-lyrics-audit-n1-n2`。本轮不重做已关闭的 R1～R3 或 R4 正常协调路径，保留 R5。
开始时主工作目录干净；PR #12 已交付的六项修改及其备份 stash 均不重复应用。

### N1：发送后的明确拒绝与未知结果

- `LyricsPlaybackTargetPolicy` 记录实际命令 `seq`、歌曲/代际、连接 epoch、协商的 Sony 会话以及单调发送时间。
  `.sent` 表示发送入口接受，不是媒体执行成功；`sendLiveActivityCommand` 也开始检查实际入队返回值。
- `handleStructuredCommandError` 接回同一策略。只有匹配请求的 `protocol/unknown_command` 被视为明确未执行：
  Sony 分发器在该错误分支返回，没有执行媒体命令。释放后取消旧意图，要求失败之后的新有效播放回报，再由用户显式重试。
  `retryable`、ATT 回调、未知业务错误、相同状态和超时均不能证明未执行，不据此释放 toggle 保护。
- 已发送命令八秒未确认，或收到匹配但语义不明确的错误，进入 `unknown`，取消未发送的最后意图，并查询一次既有
  `GET_PLAYBACK_STATE`。持续相同回报和重复请求不会再查询或再 toggle。若随后收到明确拒绝，可再查询一次以恢复可重试状态；没有轮询循环。
- 未发送意图同样有八秒期限。PiP 停止取消未发送意图、保留已发送命令的保护及有界结果期限；重开不盲重发。
  迟到的目标确认可以结束原命令，但不会执行已过期的后续意图，也不会对抗之后的 Sony 手动控制。
- `LyricsStore` 与展示快照携带待确认/未知/失败提示。PiP 使用已有 1Hz active 心跳更新底部提示，应用内展示相同状态，
  未知状态提供已有手动重新连接入口。重新连接取消旧意图，新的有效采样之后仍需用户重新操作。
  控制提示不进入歌词语义 key，不增加 ActivityKit 歌词发布频率。
- 异步接收入口及主线程应用检查连接 epoch；错误检查 seq、代际和会话。旧会话播放采样不能确认当前 PiP 命令，
  但不以 `es` 全局丢弃其他状态。无 `sid` 或时钟字段的合法旧协议继续使用既有回退。

当前协议没有媒体操作的逐命令成功 ACK，也没有为 Sony 找不到媒体控制器的直接返回路径发送业务失败。
因此这一路只能安全显示结果未知并提供恢复入口，不能声称状态查询或重新连接证明了原操作未执行。
本次没有新增协议字段、错误码或 Sony 执行行为。

### N2：发布副作用之前检查生命周期身份

`LiveActivityManager` 持有可取消的 publication Task，`end` 先失效 epoch 并取消任务和 observer。
Task 启动、`publish` 入口、查找/恢复/创建活动前，以及 await 返回后下一次系统 update 前均检查 epoch 与取消状态。
完成后仅当前 epoch 可以回写或推进队列；旧 observer 还必须匹配当前活动 ID。
系统 update 前后也复核当前活动 ID 与 active/stale 状态，系统已结束的目标不能被迟到 update 恢复为当前活动。
正在结束的活动 ID 不参与恢复，也不恢复 ended/dismissed 活动，旧重复活动清理任务不能结束新会话选中的活动。
PR 审查发现重复活动清理也使用 epoch 会使 `end` 之后尚未开始的清理被跳过，留下旧活动。
因此实际结束操作交给独立的 `LiveActivityCleanupQueue`：已安排的清理继续完成，执行前仍检查当前选中的 ID，
结束完成前始终排除恢复，同一 ID 只安排一次结束。三项新增测试驱动生产清理队列的实际异步回调，验证结束后继续清理、保护新选中目标和在途排除/去重。
已经提交给 ActivityKit 的系统调用无法强制撤回，此修复保证旧任务不会继续产生下一项副作用或污染新会话。
关闭紧凑歌词仍仅恢复原布局，不调用 `end`；关闭 PiP 不发送 Sony 暂停。

### 回归证据与验收边界

修复前先加入两项生产接入回归，并执行完整工程中的这两项 XCTest，均按预期失败：
连续 `update → end` 后发布次数为 1（期望 0）；`.sent → 匹配明确拒绝 → 新状态 → 显式重试` 的命令数为 1（期望 2）。
证据为 `/private/tmp/musicble-n1-n2-before.xcresult` 与 `/private/tmp/musicble-n1-n2-before.log`。

| 检查 | 最终结果与证据 |
|---|---|
| 完整 iPhone 16 Pro / iOS 18.3 模拟器 XCTest | **最终版本首轮 PASS 120/120**：53 项歌词展示、67 项原稳定性测试。本轮新增 16 项，覆盖明确拒绝恢复、20 次同状态回报/请求、无回报期限、未知错误保护、旧 seq/代际/会话/连接隔离、停止重开与重连恢复、意图过期、实际提示像素，以及未启动/重开/在途完成生命周期与重复活动清理。审查前两个代码版本均首轮 117/117；新增清理队列回归后验证当前版本为 120/120。`/private/tmp/musicble-n1-n2-reviewed.xcresult`、`/private/tmp/musicble-n1-n2-reviewed.log` |
| R3 与意图期限共同回归 | 陈旧采样仍不续期或确认 toggle，合法旧协议仍能恢复。46 秒后到达的合法确认只释放原命令；旧后续意图已过期，新的显式播放请求才发送第二条命令。这是增加过期语义，不放宽 R3 断言。 |
| generic iOS App / Widget 构建 | **BUILD SUCCEEDED**，无 Swift 源码编译警告；`/private/tmp/musicble-n1-n2-device-build-reviewed.log` |
| 真实渲染器附件 | 新 unknown 提示确实进入 640×360 像素缓冲，已导出并目视检查；文字、背景和原歌词区域正常。附件名 `floating-lyrics-unknown-control-feedback`，不是系统 PiP 窗口验收。 |
| quick smoke | **overall FAIL，Required 2/6**；两个 PASS 是 quick 跳过构建/安装。`Tm iPhone` 仍 unavailable，CoreDevice 无法定位设备，启动、日志、偏好和容器未验证；三个可选链路 SKIPPED。最终版本记录 `/private/tmp/musicble-n1-n2-reviewed-quick-smoke/report.json`、`ios_launch_stderr.log` |
| full smoke / 本地 Android build | 本轮未触及启动、安装、UserDefaults、日志系统或工程设置，**不要求 full smoke**；没有 Android/Sony 改动，**不要求本地 Android build**。GitHub iOS/Android CI 应按实际提交另外核对，不能以旧 master CI 代替。 |
| 图谱与差异 | 修改前核对索引、精确符号、调用路径并读取真实源码。修改后完整索引成功（8242 nodes / 43955 edges），调用图显示错误入口接入策略；Swift 闭包及同名方法存在误连，清理队列调用关系另用真实源码确认。当前接口未暴露 `list_projects/index_status/detect_changes`，使用完整索引、实际差异与调用位置复核。`git diff --check`、String Catalog JSON 检查通过；未生成仓库内图谱文件。 |
| 首轮/重试口径 | 最终本地 120 项首轮通过，完整测试仅在代码改变后重新验证，没有为通过而重复执行。原快照存储 `load == nil` 的历史 CI 间歇失败未在本次最终本地轮次出现，也未在本轮修复；后续 CI 首轮与自动重试须分别报告。 |

两项设置仍独立默认关闭；默认关闭不会启动 PiP/音频/渲染心跳，也不会创建控制结果期限任务或状态查询。
新增有界任务只在用户提出 PiP 播放意图且命令入口接受后创建。BLE UUID、JSON/A1/A2/FullLyrics/secondary、
Sony/Android、偏好格式、Widget 布局和工程配置不变。
跨应用 PiP、灵动岛、多活动选择、Sony BLE、后台/锁屏、音频中断和长期运行仍未完成真机验收。
用户在本会话已明确授权修改后提交并合并 GitHub；按该授权交付，保留 smoke 失败与真机待验边界。

## 2026-10-08 悬浮歌词与灵动岛展示修订

本轮依据用户提供的四张实拍截图及其确认的修改方案，基于主干
`f360082c35c5b1100fa974e09196f0adc3f8a241` 在 `codex/lyrics-display-ux-fixes` 开发。
截图中的悬浮歌词失败对应旧版统一的启动超时提示，单凭截图不能确定 AVKit 拒绝原因。
本轮增加分阶段条件检查和系统回调日志，并在已连接的 iPhone 与 Sony 上验证。

- 紧凑灵动岛保留原有 26pt 封面与进度，叠加小型状态标识，保留播放/重连入口。
  当前歌词占固定 120×26pt 区域，长句截断；展开态在进度上方固定预留两行歌词。
  摄像头两侧的紧凑布局、系统外形和多个活动时的最小态选择仍由 iOS 决定。
- 悬浮画面改为固定行高的长条：单行 960×128、双行 960×180，歌名默认隐藏，可独立开启。
  双行的下一句来自已接受且有效的当前歌曲完整歌词时间轴，断线、陈旧和切歌时不借用旧歌下一句。
  下一句内容进入帧身份，迟到的旧下一句不会覆盖当前快照。
- 设置中的「显示悬浮歌词」先关闭设置页，再等待主播放器的真实源预览可见。
  系统能力、前台状态、源几何、首帧入队、渲染状态和当前 PiP 可用性分别检查，最长等待八秒。
  重复点击不重开准备任务；关闭与离开前台取消准备。失败提示和日志分别记录阶段及系统错误。
- 保留 R1–R5 与 N1/N2 的发布协调、采样确认、播放目标和资源恢复机制。
  默认关闭或仅保存开启偏好时不激活音频会话；关闭 PiP 仍不主动暂停 Sony。
  画面采用系统 PiP，位置、缩放范围和系统控件仍由 AVKit 控制。

### 本轮验证与证据

| 检查 | 本轮结果与证据 |
|---|---|
| 最终源代码完整 XCTest | **PASS 127/127**，iPhone 16 Pro Max / iOS 18.3，60 项歌词展示与 67 项稳定性测试。新增七项覆盖下一句身份、实际长条像素、偏好恢复、可见性/遮挡与启动取消；`/private/tmp/musicble-lyrics-ux-validation/tests-final-ios18.xcresult`、`tests-final-ios18.log`。 |
| iOS 26.5 验证 | 文案收紧前相同功能代码 **PASS 127/127**，iPhone 17 Pro Max；`tests-delivery-fresh-device.xcresult`。同版本模拟器重启测试曾出现测试进程连接超时，并中止仍未连接的测试运行；没有把这些运行算作通过。最终文案与当前源代码在 iOS 18.3 完整验证，不修改工程测试配置绕过问题。 |
| 签名构建、安装与运行 | 最终 full smoke **PASS，Required 6/6**：实际构建、安装、启动、日志、偏好与文件检查全部执行；`/private/tmp/musicble-lyrics-ux-validation/ios-final-full/report.json`、`report.md`。三个可选 BLE/封面/CurrentWord 检查显式跳过，不纳入本轮通过数量。先前 full smoke 因锁屏而失败的记录保留在 `ios-full/report.json`，没有替换为成功记录。 |
| 最终安装包 PiP 启动 | iPhone 16 Pro Max / iOS 26.4：00:43:47.555 调用启动，00:43:48.129 收到 **didStart / state=active**；源 416×78，首帧已入队、渲染状态为 rendering、系统 possible=true，实际 render-size 417×78；`/private/tmp/musicble-lyrics-ux-validation/pip-final/ios-live.log`、`pip-launch-final.json`。 |
| 单行/双行切换与后台状态 | 前一轮真机日志记录单行约 417×55、双行约 417×78 的系统尺寸回调；Sony 的新播放状态在 background 持续被接受，新的歌词与封面 revision 进入 Live Activity 发布；`pip-probe/ios-live.log`、`pip-probe/ios_ble.old.log`。这是系统回调和数据链路证据，实际窗口可见性与观感待用户反馈。 |
| 用户设置保留 | 最终安装和运行检查前后的 UserDefaults 字典完全相同，保留用户本轮手动选择的两项开启与双行模式；`iphone-preferences-pip-probe.plist`、`iphone-preferences-final.plist`。`--smoke-floating-lyrics` 仅是 DEBUG 进程参数，一次显式启动，不保存开关、不在关闭后重弹。 |
| 像素附件 | 长句不会串入下一行，歌名隐藏/显示、下一句改变和单行忽略下一句均通过实际像素比较；单行 PNG 已导出目视检查，`/private/tmp/musicble-lyrics-ux-validation/attachments/26BB812B-AD1A-456E-AE28-ED5F5AEB5066.png`。附件是绘制缓冲，不是系统窗口截图。 |
| 图谱与差异 | 修改前通过索引定位并读取真实源码；最终完整索引成功（8277 nodes / 44309 edges，persistence=false），复核启动与快照调用路径。当前工具未暴露 `list_projects/index_status/detect_changes`，用真实差异、构建与测试补充检查。同名函数、闭包和系统回调仍以源码为准。`git diff --check` 与 String Catalog JSON 通过；最终测试/安装的 12 个源码文件哈希记录在 `source-manifest.json`，验证后没有源码变更。 |

本轮已完成代码、最终安装、实际 PiP 启动回调和后台数据链路检查。
灵动岛封面与长短歌词的屏幕效果、跨应用 PiP 的实际可见性、电话/视频中断、锁屏、
多活动竞争和长时间运行尚未逐项验收，不据此把原 26 项真机矩阵整体标为通过。
本轮修改目前保留在本地分支，尚未提交、推送或合并 GitHub。

## 2026-10-08 控制页清理与悬浮条收紧

本次依据用户随后提供的两张截图继续修订同一分支。上一轮播放器底部保留了启动源预览，
DEBUG 真机启动参数还会让预览在开关关闭后保留，造成截图中的黑框与状态栏。
本次删除整个主控制页底部区域，并将真实源视图移到设置中的临时准备流程。
成功后自动返回播放器；手动退出设置只取消准备，活动中的窗口继续持有图层与渲染器。
来源可见性检查接受最前方设置页中的真实源，仍拒绝被模态页面覆盖的源。

歌词条改为深色背景、薄荷绿加粗当前句和灰色下一句，固定行高与左右边距。
当前句使用 38px 字体，下一句使用 32px；单行 640×60、双行 640×104。
只在开启歌名时增加 24px，只在控制反馈非 idle 时增加 28px。
设置页启动预览宽度最多 320pt，窗口尺寸仍由 AVKit 决定，不能用预览尺寸保证系统外框同样大小。
保留真实播放/暂停状态和公开的 `requiresLinearPlayback`，不使用私有控件隐藏方式。
轻点窗口可收起系统控件，双指缩放可调整大小，见 [Apple 操作说明](https://support.apple.com/en-nz/guide/iphone/iphcc3587b5d/ios)。

| 检查 | 本次结果与证据 |
|---|---|
| 最终完整 XCTest | **PASS 130/130**，iPhone 16 Pro Max / iOS 18.3：63 项歌词展示、67 项稳定性测试。新增三项覆盖主控制页两种开关状态均不挂载预览、退出设置取消准备但保留偏好、歌名和控制提示按需增高；`/private/tmp/musicble-lyrics-ui-cleanup/tests-final.xcresult`、`tests-final.log`。首次编译中观察包装器与 `@Observable` 类型不匹配已修正；失败记录保留在 `tests-first.log`，未作为通过证据。 |
| 签名构建与真机安装 | full smoke **PASS，Required 6/6**，构建、安装、启动、日志、偏好与文件检查全部实际执行，无跳过的 Required；`/private/tmp/musicble-lyrics-ui-cleanup/ios-final-full/report.json`。三个可选 BLE/封面/CurrentWord 脚本显式跳过。 |
| 真机 PiP 启动 | iPhone 16 Pro Max / iOS 26.4：06:56:28.664 调用启动，06:56:29.264 收到 **started / state=active**；真实设置源 320×52、frame=true、renderer=rendering、possible=true、linear=true，系统尺寸回调约 418×68；`pip-probe/ios-live-initial.log`、`pip-launch.json`。 |
| 设置退出与停止路径 | 成功后的源从窗口移除，没有立即发生 stop。06:56:54.890 先记录悬浮开关关闭，06:56:54.929 收到 will-stop，06:56:55.753 完成清理。此时 visible=false、source=0×0；`pip-probe/ios-live-followup.log`。这覆盖约 26 秒的实际运行与关闭链路，不代表长期后台验收。 |
| 实际画面检查 | 双行 PNG 已导出目视检查，行距紧凑、两句没有互相覆盖，默认无提示空行；`pixel-attachments/F3A6EB59-7FD3-4CE1-86E5-CADB7887D0D8.png`。主控制页在关闭和开启状态的 UIHostingController 附件均不含底部预览。附件由 `tests-second.xcresult` 导出，其 UI/渲染源码与最终版本一致；后续仅收紧错误文案、增加 stop 日志。附件不是系统 PiP 截图。 |
| 用户偏好 | 测试前读取 `iphone-preferences-before.plist`，测试中读取 `iphone-preferences-final.plist`。设备上同时发生多次悬浮开关变更，因此没有把两份字典称为完全一致，也没有用旧备份覆盖最新选择；其他键比较一致。显式 DEBUG 启动不写入悬浮开关。 |
| 图谱与差异 | 最终完整索引成功，8292 nodes / 44384 edges，persistence=false；复核设置面板与取消启动的调用关系。当前工具未暴露 `list_projects/index_status/detect_changes`，用真实源码、差异、构建和测试补充验证。`git diff --check`、String Catalog JSON 检查通过；对应源码哈希见 `source-manifest.json`。 |

实际系统控件收起后的桌面观感、缩放下限、不同视频应用、中断、锁屏、多活动与长时间运行，
仍需按真实场景验收；不把启动回调和像素附件当作整个真机矩阵通过。
本次代码已安装到连接的 iPhone，目前仍保留在本地分支，未提交、推送或合并 GitHub。

### 同日用户复测：重开入口与可选配色

用户继续反馈「关闭后再也打不开」和「黑色背景难看」，并要求像 QQ 音乐一样选择背景配色。
此前设备日志记录了重新开启偏好，但没有对应的窗口启动请求：上一版把开关当作偏好保存，
还要求额外点击显示按钮。现在开关的用户操作与「再次显示」复用同一启动入口，准备成功后自动返回播放器。
清理期间的新请求只排队一次，旧窗口的停止回调或两秒兜底完成资源释放后才重开；取消、关闭开关与旧 generation
不会误触发重开。关窗本身仍不自动重弹，也不暂停 Sony。

背景加入六种可选渐变配色，当前句与下一句采用对应的浅色文字。
新偏好键 `floatingLyricsTheme` 独立保存，缺失或非法值回退到暖棕；重载和恢复默认均覆盖此键。
设置页色块与真正渲染帧使用同一份标量颜色数据。换色通过现有 appearance 刷新和帧失效机制生效，
不新增 BLE 命令、歌词请求、音频播放或后台定时器。Apple 的窗口外框、位置和播放控件继续由系统决定。

| 检查 | 最终配色版证据 |
|---|---|
| 完整 XCTest | **PASS 133/133**，iPhone 16 Pro Max / iOS 18.3，66 项歌词展示与 67 项稳定性测试；`/private/tmp/musicble-lyrics-ui-cleanup/tests-color-final.xcresult`、`tests-color-final.log`。新增关闭中显式重开/旧 generation 拒绝、取消后不重放和实际六种配色；扩展偏好重载、重置与非法值回退。此前暖色单配色版 132/132 记录保留在 `tests-reopen-warm.xcresult`。 |
| 最终签名构建、安装和检查 | full smoke **PASS，Required 6/6**，实际构建、安装、启动、日志、偏好和文件检查；`ios-color-final-full/report.json`。三个可选 BLE/封面/CurrentWord 脚本跳过，不作为通过项目。 |
| 正常开关启动 | 未额外运行 DEBUG PiP 探针，用户在设备上 07:10:51.475 开启悬浮开关，07:10:51.571 发起启动，07:10:52.168 收到 **started / active**，源 320×52、系统约 418×68；`pip-probe/ios-color-manual.log`。 |
| 设备配色与行数操作 | 07:11:02.168 选择薄荷，07:11:03.116 改回暖棕；07:11:06.247 选择单行，系统随即回调约 **418×39**。07:11:38.616 关闭开关后正常停止。这些是配置与系统回调证据，颜色实际观感及同一进程中关窗后再次显示仍等待用户复测确认。 |
| 六种实际像素 | 导出并目视检查六张 640×104 的颜色附件，当前句与下一句清晰、没有新增空白行；`theme-attachments/manifest.json`。这些是渲染缓冲附件，不是系统窗口截图。 |
| 索引、差异与版本对应 | 最终完整索引成功，8319 nodes / 44524 edges，persistence=false；复核 `finishStop` 的系统回调/兜底调用和 appearance 刷新入口。`git diff --check`、String Catalog JSON 通过；最终源码与测试/安装对应哈希记录在 `source-manifest.json`。 |

手机上的配色、行数和开关操作由用户实时进行，没有用之前的 UserDefaults 备份覆盖这些新选择。
代码和安装完成；跨应用观感、重复关窗重开、锁屏、中断和长期后台仍按实际复测结果验收。
最终配色版仍位于本地 `codex/lyrics-display-ux-fixes`，尚未提交、推送或合并 GitHub。

### 同日后续修订：「词」入口与会话开关

用户继续要求两项开关默认关闭、显示状态与开关一致，并把悬浮设置独立成页，随后确认灵动岛开关也放入其中。
控制页连接状态右侧、更多菜单左侧新增 44pt 的圆形「词」按钮，直接打开 `LyricsDisplaySettingsView`。
页面标题为「歌词设置」，包含灵动岛歌词、悬浮歌词与悬浮样式；原通用设置的歌词区保留进入该页的入口。
页面使用深色控件，单双行选项、文字和配色在深色背景上保持可读。主控制页仍不挂载底部预览。

- 两项开关仅表示当前 App 会话，每次冷启动均为关闭；清除旧版保存的两项开启标记。
  进入设置、前后台切换或同一进程重载偏好不会重置已开启的会话，行数、歌名与配色继续持久化。
- 进入页面本身不请求窗口、不挂载启动源、不激活音频。用户开启悬浮开关或点击显示入口，立即使用同一准备流程。
  成功后自动返回上一页；失败、用户或系统关窗、取消准备完成后开关关闭，避免开启标记与窗口不一致。
  窗口尚在清理而用户明确请求重开时，继续保留这一请求，清理结束后执行；旧回调不能重新开启取消的请求。
- 任一歌词显示会话开启时，「词」按钮使用强调色。灵动岛开关立即刷新 Activity 外观，初次挂载也同步冷启动的关闭值。
  灵动岛封面与固定歌词区域、R1–R5 / N1–N2 协调与恢复机制保持现有修订。

| 检查 | 最新版本证据 |
|---|---|
| 最终完整 XCTest | **PASS 135/135**，iPhone 16 Pro Max / iOS 18.3：68 项歌词展示、67 项稳定性测试。新增窗口完成/失败与显式重开时的实际开关协调、打开独立页不启动 PiP/不改变音频会话；扩展冷启动四种旧开启组合均关闭、样式保留和同进程 load 保留会话。`/private/tmp/musicble-lyrics-ui-cleanup/tests-settings-reviewed.xcresult`、`tests-settings-reviewed.log`。页面深色控件修订前为 `tests-settings-final.xcresult` 的 135/135，按源码版本分别保留。 |
| 签名构建、安装与 full smoke | **PASS，Required 6/6**，实际构建、安装、启动、日志、偏好与文件检查，Required 无跳过；`ios-settings-reviewed-full/report.json`。三个可选 BLE/封面/CurrentWord 脚本显式跳过。App 和 Widget 签名构建成功；源码编译无 warning，已签名 Widget 不执行 strip 的构建提示按原样保留。 |
| 新页面与控制页截图 | 最终独立页截图 `settings-reviewed-attachments/1E8B300B-4962-4DBA-867F-8C4A29E27675.png` 已目视检查：中文、两个关闭开关、单双行、歌名与可滚动配色均可见。控制页的开关关闭/开启两张附件均有「词」按钮且无底部预览；`player-settings-attachments/manifest.json`，其控制页源码与最终一致。截图来自 UIHostingController，不是系统 PiP 窗口截图。 |
| 真机默认状态与样式保留 | 最新安装的正常启动在 07:27:52.294、07:28:04.516 记录 `compactLyrics=false floatingLyrics=false floatingTheme=warm`，没有自动启动 PiP。安装前后 `iphone-preferences-before-settings.plist` / `iphone-preferences-after-settings.plist` 显示旧开关键已移除，用户的单行和暖棕保留；没有用旧备份覆盖用户选择。 |
| 正常开启的系统回调 | 新版安装后，07:28:56.753 用户开启悬浮会话，07:28:56.813 调用系统启动，07:28:57.401 收到 **started / active**。真实源为 320×30，首帧入队、renderer=rendering、possible=true、linear=true；系统回调约 **418×39**。证据为 `pip-probe/ios-settings-start.log`，这次成功发生在 07:29:29 的显式 DEBUG 启动探针之前，不归因于该探针。 |
| 探针与验收边界 | 07:29:29 的 DEBUG 进程启动调用成功，日志在 07:29:30.229 重新记录两项开关关闭；没有记录对应的新 PiP 启动，不把进程启动当作 PiP 成功。已请用户从新「词」入口复测关窗后再次开启；当前重复关窗重开、实际跨应用观感、锁屏、中断、多活动与长期后台仍待真机确认。 |
| 结构、工程与版本对应 | 最终完整索引成功，8332 nodes / 44587 edges，persistence=false；调用图与源码共同确认独立页复用 `setEnabled → requestStart`，两处页面入口和真实可见启动源。未暴露的 `list_projects/index_status/detect_changes` 用完整索引、真实差异、构建与测试补充，未生成仓库图谱文件。工程引用、String Catalog JSON 与 `git diff --check` 通过；13 个源码/工程/本地化文件哈希见 `source-manifest.json`，之前配色版哈希另存 `source-manifest-color-final.json`。 |

本轮只修改 iOS 展示与设置接入，没有 BLE 协议或 Sony/Android 改动，因此未重复 Android build。
已完成最新代码测试与真机安装。以上为提交前的验证记录；用户随后授权将全部待提交内容提交并合并到 GitHub，
实际交付状态以对应 PR 和主干提交为准。上面的回调证据不替代完整真机验收矩阵。
