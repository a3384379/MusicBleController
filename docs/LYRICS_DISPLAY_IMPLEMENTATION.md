# iOS 歌词展示实施与验收

实施依据：[分享对话中的最新方案](https://chatgpt.com/share/6ac318e1-0e14-83e9-baba-a60b2fe442f1)。
代码基线：`origin/master`，`7a0bc2a12bcd4c25b37439162b4c8052944dd8f6`。
开发分支：`codex/ios-lyrics-display`。这是代码实施记录，不是 PiP 真机或发布验收结论。

## 用户行为

- 设置 → 歌词 → **灵动岛显示歌词**：新安装和缺失配置的升级默认关闭。
  开启后紧凑态用小状态标识和单行当前歌词；长句尾部截断。关闭后恢复原来的封面、进度和状态图标。
  原有锁屏、展开态、播放控制继续工作。原来的「灵动岛样式」仍独立控制已有布局。
- 设置 → 歌词 → **悬浮歌词**：独立持久化，默认关闭。
  主播放器显示实际的画中画源预览。开启时请求一次系统 PiP；系统不允许时显示原因，并提供「再次显示」。
  再次启动 App 时保留偏好，但需要用户主动显示窗口。
- 用户或系统关闭 PiP 后保留偏好，状态变成「已停止」，不会自动重弹，也不会向 Sony 发送暂停。
  关闭设置释放新增资源，不断开蓝牙。

紧凑态是否加宽、是否进入最小态，以及 PiP 窗口位置、尺寸和控件，均由系统决定。

## 实际接入点

| 文件 | 职责 |
|---|---|
| `PreferencesStore.swift` / `PreferencesView.swift` | 两项独立默认关闭的偏好、开关、运行状态和重试入口 |
| `LyricsPresentationSnapshot.swift` | 小型展示投影、歌曲代际/行/时间轴身份、PiP 状态机、播放目标去重、最新帧队列 |
| `BLETestManager.swift` | 从已接受的播放状态、CurrentWord 和既有完整歌词派生快照；切歌清理旧行；复用既有控制通道 |
| `SonyMusicActivityAttributes.swift` | 可变紧凑歌词模式；旧 JSON 缺失字段按 false 解码；显示文本字节上限 |
| `LiveActivityManager.swift` | 复用原有单次在途、最新待提交状态；开关变化参与语义去重；编码内容小于 4KB |
| `SonyMusicLiveActivityWidget.swift` | 开关控制紧凑态；最小态继续展示标识；过期紧凑态降级为等待同步 |
| `LyricsSampleBufferRenderer.swift` | utility 串行队列上的 640×360 文本画面、最多三个像素缓冲、单调 host-clock 帧时间 |
| `LyricsPictureInPictureController.swift` | AVKit 启停、真实成功回调、超时/中断清理、最多一帧在途与一帧待绘制 |
| `LyricsPictureInPictureSourceView.swift` / `ContentView.swift` | 挂载真实且可见的 sample-buffer layer；仅开启悬浮歌词时创建展示入口 |
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
  该心跳只维护画中画画面和有界时钟投影，不发送 BLE 轮询。
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
| 3 | 重启和重载 | 两项用户选择持久化，XCTest |
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
| 17 | 不支持/启动失败 | 偏好保留，状态和原因可见，状态机 XCTest；设备限制待真机验证 |
| 18 | supported 与 possible | 分别检查设备能力与当前条件；当前窗口行为待真机验证 |
| 19 | 启动中关闭/再开启 | 旧 generation 的成功回调被拒绝，XCTest |
| 20 | 用户/系统关窗 | 保留偏好、停止资源、不自动重弹，状态机 XCTest；真机待测 |
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
正在结束的活动 ID 不参与恢复，也不恢复 ended/dismissed 活动，旧重复活动清理任务不能结束新会话选中的活动。
已经提交给 ActivityKit 的系统调用无法强制撤回，此修复保证旧任务不会继续产生下一项副作用或污染新会话。
关闭紧凑歌词仍仅恢复原布局，不调用 `end`；关闭 PiP 不发送 Sony 暂停。

### 回归证据与验收边界

修复前先加入两项生产接入回归，并执行完整工程中的这两项 XCTest，均按预期失败：
连续 `update → end` 后发布次数为 1（期望 0）；`.sent → 匹配明确拒绝 → 新状态 → 显式重试` 的命令数为 1（期望 2）。
证据为 `/private/tmp/musicble-n1-n2-before.xcresult` 与 `/private/tmp/musicble-n1-n2-before.log`。

| 检查 | 最终结果与证据 |
|---|---|
| 完整 iPhone 16 Pro / iOS 18.3 模拟器 XCTest | **首轮 PASS 117/117**：50 项歌词展示、67 项原稳定性测试。本轮新增 13 项，覆盖明确拒绝恢复、20 次同状态回报/请求、无回报期限、未知错误保护、旧 seq/代际/会话/连接隔离、停止重开与重连恢复、意图过期、实际提示像素，以及未启动/重开/在途完成生命周期。`/private/tmp/musicble-n1-n2-all.xcresult`、`/private/tmp/musicble-n1-n2-all.log` |
| R3 与意图期限共同回归 | 陈旧采样仍不续期或确认 toggle，合法旧协议仍能恢复。46 秒后到达的合法确认只释放原命令；旧后续意图已过期，新的显式播放请求才发送第二条命令。这是增加过期语义，不放宽 R3 断言。 |
| generic iOS App / Widget 构建 | **BUILD SUCCEEDED**，无 Swift 源码编译警告；`/private/tmp/musicble-n1-n2-device-build.log` |
| 真实渲染器附件 | 新 unknown 提示确实进入 640×360 像素缓冲，已导出并目视检查；文字、背景和原歌词区域正常。附件名 `floating-lyrics-unknown-control-feedback`，不是系统 PiP 窗口验收。 |
| quick smoke | **overall FAIL，Required 2/6**；两个 PASS 是 quick 跳过构建/安装。`Tm iPhone` 仍 unavailable，CoreDevice 无法定位设备，启动、日志、偏好和容器未验证；三个可选链路 SKIPPED。`/private/tmp/musicble-n1-n2-quick-smoke/report.json`、`ios_launch_stderr.log` |
| full smoke / 本地 Android build | 本轮未触及启动、安装、UserDefaults、日志系统或工程设置，**不要求 full smoke**；没有 Android/Sony 改动，**不要求本地 Android build**。GitHub iOS/Android CI 应按实际提交另外核对，不能以旧 master CI 代替。 |
| 图谱与差异 | 修改前核对索引、精确符号、调用路径并读取真实源码。修改后完整索引成功（8231 nodes / 43869 edges），调用图显示错误入口接入策略；当前接口未暴露 `list_projects/index_status/detect_changes`，使用完整索引、实际差异与调用位置复核。`git diff --check`、String Catalog JSON 检查通过；未生成仓库内图谱文件。 |
| 首轮/重试口径 | 本次最终本地 117 项首轮通过，没有为通过而重复执行完整测试。原快照存储 `load == nil` 的历史 CI 间歇失败未在本次最终本地轮次出现，也未在本轮修复；后续 CI 首轮与自动重试须分别报告。 |

两项设置仍独立默认关闭；默认关闭不会启动 PiP/音频/渲染心跳，也不会创建控制结果期限任务或状态查询。
新增有界任务只在用户提出 PiP 播放意图且命令入口接受后创建。BLE UUID、JSON/A1/A2/FullLyrics/secondary、
Sony/Android、偏好格式、Widget 布局和工程配置不变。
跨应用 PiP、灵动岛、多活动选择、Sony BLE、后台/锁屏、音频中断和长期运行仍未完成真机验收。
用户在本会话已明确授权修改后提交并合并 GitHub；按该授权交付，保留 smoke 失败与真机待验边界。
