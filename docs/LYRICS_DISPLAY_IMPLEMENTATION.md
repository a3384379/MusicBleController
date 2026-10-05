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
