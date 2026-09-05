# MusicBleController V4 Cold-Path Handoff

本文记录第四阶段的 Track Handoff、current lyric 与 CurrentWord 冷路径优化。所有结果来自同一台 iPhone 与同一台 Sony 的单调时钟 Trace；日志只保存在本机临时目录，仓库不保存歌曲名、设备标识或原始媒体内容。

## 1. 第三阶段基线

第三阶段最终 Cold Path 固定基线为：command → Track publish p95 632.6ms、Track → current lyric p95 1210.1ms、Track → Preview/HQ p95 707.2ms、iOS decode p95 1ms、iOS publish p95 18.5ms。CurrentWord 只有一个可关联样本，不能作为统计基线。

第四阶段增强 Trace 后，在修改前另外完成 NEXT、PREVIOUS 和 Sony MediaSession dispatch-next 各 30 次。它证明了两个新的主要问题：lyrics ready 后仍可能等下一轮 PlaybackState 才发布 current line；CurrentWord 的 generation 切换固定等待和共享 scheduler 使首包常在 eligible 后再等 0.9～1.4 秒。

## 2. Track Handoff 状态机

Handoff 的观测状态为：

```text
COMMAND_PENDING / SONY_LOCAL_PENDING
→ METADATA_OBSERVED
→ IDENTITY_CANDIDATE
→ IDENTITY_ACCEPTED + GENERATION_CREATED
→ PLAYBACK_READY
→ P0_QUEUE
→ NOTIFY_CALLBACK
→ IOS_RECEIVED
→ IOS_DECODED
→ IOS_TRACK_ACCEPTED + PLAYBACK_PUBLISHED
→ NOW_PLAYING_CONSUMED
```

状态机只做诊断关联，不参与媒体选择。MediaSession 的正式 trackId、runtime generation 和 iOS generation fence 仍决定内容能否发布。新 Track 被接受后，旧 generation 的歌词、CurrentWord 和封面仍会被拒绝。

## 3. Handoff ID

iOS NEXT/PREVIOUS 使用现有 command sequence 生成 `command-<seq>`；无 iOS 命令的 Sony 本地转换使用进程内有界递增 ID。pending context 的 TTL 为 15 秒，Track Identity 接受后转成 active context；连接状态重置或服务关闭时清理。

Handoff ID 只写两端本地 Trace，没有加入 TrackInfo、PlaybackState、A1/A2 header 或任何 BLE payload。报告器优先按 Handoff ID 关联；自动场景无法共享本地 ID 时，才使用精确 trackId/generation 和可信 Clock Sync 对齐，不用墙上时间补齐。

## 4. 完整 Trace 事件

Track 链路覆盖 command intent/enqueue/write/callback、Sony receive/validate/dispatch、notification/MediaSession metadata、identity candidate/accepted、generation、playback read/ready/enqueue/dequeue/notify callback，以及 iOS receive/decode/accept/publish/UI consume。

歌词链路覆盖 lookup requested/start、runtime/parsed cache hit/miss、load、ready、current line selected/enqueued/sent/received/accepted/published。CurrentWord 覆盖 eligibility evaluated/not eligible/eligible、scheduler create/scheduled、first boundary、immediate/boundary enqueue、send、receive、accept/reject/publish。

新增关联字段为 `handoffId/triggerType/positionAnchorMs/lineIndex/wordTimingStatus/cacheSource/failureReason`。Trace 不记录标题、歌词正文、图片或文件路径。

报告器在一次连接中发现 Clock Sync sample count 回退时，将整轮跨设备时钟标记为不可信；不能用重连后重新收敛的最终 offset 解释重连前事件。App 启动和重连产生的同 Track `refreshed` 事件不再冒充新 Handoff。

## 5. Track Identity 来源

MediaSession metadata 是正式身份来源；QQ 音乐通知 metadata 仅作为辅助观测和本机 UI 封面刷新触发。通知中的身份只写隐私安全 digest，不直接成为 iOS 可见歌曲。

正式测试显示 MediaSession callback 存在，metadata accepted → Sony Track accepted p95 为 98.5ms（NEXT）、91.1ms（PREVIOUS）和 113.1ms（Sony dispatch-next）。远程控制的主要剩余时间在播放器收到 dispatch 后到暴露新 metadata：NEXT p95 773.3ms、PREVIOUS p95 781.4ms。这不是 iOS JSON decode、SwiftUI publish、Sony playback read 或 BLE queue 的固定等待。

## 6. Track Fast Lane

没有新增协议类型。TrackInfo、PlaybackState 和 CurrentWord 继续使用现有 JSON status，并进入既有 P0 实时队列。第四阶段先修正了 PlaybackState/CurrentWord 的队列边界；本轮真机 Trace 又发现 TrackInfo 在存在长任务时仍会被发送层放入 latest-only interleaved 槽。该槽只在长任务包边界被读取，长任务恰好结束时可能让歌曲身份滞留，而新的无身份 PlaybackState 已先到达 iOS。

`80222a5` 将 TrackInfo 恢复到普通 P0 队列，只保留可替换的 volumeState 使用 latest-only 槽。TrackInfo 因此可以在长任务下一包边界抢占，并先于随后排队的 PlaybackState 建立正式 trackId/generation。两轮 NEXT 真机 Trace 中，修改后的 TrackInfo 全部为 `queued`，不再出现 `queued_interleaved`；正常 TrackInfo queue wait 的 p95 为 5～18ms。此修复消除了偶发身份滞留边界，但没有把它误报为约 1 秒远程切歌长尾的主因。

第一份身份不等待 FullLyrics、translation、romanization、Preview、HQ、history 或 diagnostics。iOS 仍在正式 trackId/generation 被接受后发布 metadata，缓存快照不计作新歌发布。

后续真机 Trace 发现，`PlaybackStateReader` 在已经从权威 MediaSession 观察到新 metadata 后，仍会先执行 capability、预测历史、歌词状态和诊断工作，最后才建立 runtime generation 并发布 TrackInfo。新的 authority-acceptance fast lane 只在 BLE 主 Reader 中开启：当 `ReactiveMediaController` 确认 trackId 变化、trackId 和 title 均非空时，先原子创建正式 generation，发布不含旧歌词的轻量身份状态，再继续原有歌词和诊断路径。

Sony 本机 UI 与 iOS 在同一回调中接收新身份。Sony 先进入新曲的封面 LOADING，并复用 title/artist 精确校验和 generation/songKey fence 请求新图，不再继续显示上一首封面。其他用于历史、诊断和临时查询的 Reader 默认不开启 fast lane，避免重复 generation 或重复发布。

## 7. 有界 Handoff Probe

本阶段没有增加 Handoff Probe。真机 Trace 已观察到 MediaSession metadata callback，且 dispatch → metadata 长尾发生在 QQ 音乐/MediaSession 真正切换之前；增加 0/80/160/320ms 读取只会重复读旧身份，不能缩短播放器内部切歌时间，还会引入额外 Binder 和媒体读取竞争。

因此新增持续轮询为 0，现有 AutoPush 周期也没有调快。若未来设备出现“播放器已经切换但 callback 丢失”的独立证据，再单独打开固定次数、可取消的 Probe Gate。

本轮曾试验 120/200/280/360ms 的命令触发轻量 Probe。该轮 NEXT 30 次总 p95 为 734.3ms，且有 1 个 UI consume Trace 不完整；其 MTU 为 185，而保留实现的正常轮次为 515，不能把差异单独归因于 Probe 或 Handler 排队。由于没有证明稳定收益，该实现已在提交前完整删除，最终代码仍使用通知事件与原 220ms 冷路径 fallback。

## 8. Current Lyric 首包路径

`LyricManager` 在 runtime/parsed cache、load 和 ready 处输出精确 Trace。QRC ready 时先原子更新 `CurrentTrackRuntimeCache`，随后 `LyricsReadyGateSnapshot` 触发 `publishLyricsReadyPlaybackIfCurrent`：只有当前 trackId、generation、非空 current line 和订阅状态全部匹配，才立即发送既有 PlaybackState。

路径由“lyrics ready → 等下一轮 PlaybackState”收敛为“lyrics ready → current line select → P0 enqueue → iOS publish”。FullLyrics 校验、A2 正文、translation 和 romanization 仍在后台，不阻塞 current line；QRC Triple DES、parser、index schema、fuzzy/negative/alias/recovery 均未修改。

## 9. CurrentWord Eligibility

Eligibility 必须同时满足：正式 trackId/generation、当前歌词属于该 Track、播放中、可信的单调 position anchor、当前行有 word timing、当前 position 已进入有效词。分类结果包含 `ELIGIBLE`、`INTRO_WAIT`、`LINE_ONLY/NO_WORD_TIMING`、`LYRIC_NOT_READY`、`PAUSED`、`CLOCK_UNTRUSTED` 和 `NO_ACTIVE_LINE`。

`trackAcceptedToFirstWord` 只统计立即或接近立即 eligible 样本；音乐前奏、逐行歌词、无歌词、暂停或不可信 anchor 不计作系统失败。报告仍保留全部 rejected/missing 计数，不能挑最快样本构造 p95。

## 10. INTRO_WAIT 与 LINE_ONLY

播放位置在第一行/第一个词之前，或刚越过一个词而尚未进入下个边界时标记 `INTRO_WAIT`，并计算下一个真实词/行边界。只有逐行歌词时标记 `LINE_ONLY`，不发送伪造的 `line=0, word=0`。

第一行查找和 current line 选择使用有序时间轴的二分/边界策略；空行向前寻找最近有效行。position anchor 会按播放速度投影，暂停时不推进。Seek 的明显倒退会建立新本地时间线，小抖动仍由 position fence 拒绝。

## 11. CurrentWord Scheduler

CurrentWord 使用独立单线程 scheduled executor，不再与其他 scheduled maintenance 共享执行器。新 generation 到来时保留 sequence/generation/position fence、取消旧 CurrentWord notify，并用匹配的 `lyricsReady` 回调作为启动屏障；250ms 只作为 bounded re-evaluation fallback，不再固定等待 450ms 后才启动。

若当前 position 已位于有效词内部，立即发送 snapshot；否则按真实下一个词/行边界调度。暂停时 scheduler suspended，恢复和 seek 时重新计算。边界漂移校正仍最多 500ms，但不再作为切歌首包 holdoff。每个 generation 的 sequence 从新时间线单调增长，同词去重和 40ms 最小发送间隔保留。

## 12. Queue Priority

P0 仍包括控制状态、TrackInfo、PlaybackState 和 CurrentWord；P1 lyricWindow/正式 Preview；P2 FullLyrics/secondary；P3 HQ/history/diagnostics。P0 可以在大任务包边界抢占；CurrentWord 只保留最新有效状态，但在途 notify 不被替换。

正式样本的 `wordEligible → schedulerCreated` p95 为 0ms，`schedulerCreated → firstEnqueue` p95 为 0.5～1ms，`firstEnqueue → send` p95 为 0ms，证明 Sony scheduler 和 enqueue 已不是剩余长尾。PREVIOUS 与 Sony dispatch-next 的约 250ms 目标偏差发生在 send 后的 BLE/CoreBluetooth 接收或 iOS accept 段，不应继续盲调 scheduler。

## 13. Command Response 与控制后媒体发布

第三阶段保留的 25ms per-device quiet window 继续只限制 response 后的新 response-sensitive 媒体包。合法 JSON command 当前直接调用 `sendResponse()`，命令业务仍在原 executor 异步执行；没有延迟 response gate，也没有 pending response closure。

提交 `3596804` 曾把 ATT response 串行化到同设备 notify callback 边界。它在一轮压力 Trace 中消除了 write timeout，但安装后出现 iOS 和 Android Controller 同时拿不到部分歌词、进度和图片的产品回归，因此由 `1154397` 完整回退。旧轮次的 `commandResponseDeferred/Released/Rejected` 数据只能解释实验，不再描述当前协议路径。

当前跟进优化避免再改 ATT 生命周期：iOS 在串行写队列中合并过期的普通 `GET_PLAYBACK_STATE`，NEXT/PREVIOUS fallback 从 500ms 调整为 1 秒，并在正式新 trackId 到达时取消；前台验证和 Health probe 不参与丢弃。Sony 的 220ms 控制后 fallback 保留轻量 PlaybackState 兜底；新身份、TrackInfo 和精确封面由权威 MediaSession 确认后的 fast lane 独立发布。这样避免用旧歌曲媒体抢占新身份、歌词、CurrentWord 和封面。

新增 Trace 为 iOS `commandRefreshSuperseded` 与 Sony `postControlMediaDeferred/postControlMediaPublished`。该策略先通过纯策略单测、Sony unit/assemble 和 Swift 源码解析，本轮再由下述 iPhone + Sony 正常与压力场景完成真机复核。

本轮进一步让 iOS Clock Sync bootstrap/refresh PING 在 FullLyrics 或图片 receiving/decoding 期间事件化延后，媒体空闲后有界恢复；没有新增 Timer。NEXT 30 次中 `clockSyncProbeDeferred/Resumed` 成对出现，command timeout 与 hard reconnect 均为 0。随后的 NEXT、PREVIOUS、Sony dispatch-next 各 30 次以及 100 次快速压力均完成，严格报告均为 PASS（第一轮 NEXT 曾因一条已存在但未关联 handoff ID 的重复 Notification Trace 判为 29/30；`3f706b4` 修复纯观测关联后复跑为 30/30）。

## 14. AlbumArt 15ms Pacing

AlbumArt binary 15ms 最小 pacing 保持不变，没有为 Track 或 CurrentWord 调低。修改后正式 NEXT、PREVIOUS、Sony dispatch-next 的 Preview/HQ p95 分别为 68.3ms、79.4ms、66.5ms，正确性计数 wrong artwork=0；因此没有证据支持牺牲 command callback 可靠性继续压缩图片间隔。

100 次 650ms 压力用于 stale/队列正确性，不作为正常图片 SLO：其并发与播放器合并导致 Preview p95 1490.4ms，但仍低于 HQ 2500ms，且没有旧图被接受。

## 15. 双控制器行为

实现继续按设备隔离 capability、MTU、transfer、quiet window、notify failure 和 cleanup；当前代码没有 response gate。

按当前产品优先级，本轮先闭环 iPhone 控制端与 Sony PlayerAgent。Android Controller 与双控制器并发矩阵延期，标记 `SKIPPED（当前范围）`，不能用单元测试代替，也不作为这轮 iOS + Sony 优化的完成声明。

## 16. 修改前数据

第四阶段增强 Trace 的修改前 p95：

| metric | NEXT | PREVIOUS | Sony dispatch-next |
|---|---:|---:|---:|
| command/Track T0 → Track publish | 1153.8ms | 1084.3ms | 155.4ms |
| Track → current lyric | 1497.2ms | 1423.6ms | 692.9ms |
| word eligible → publish | 2023.5ms | 2098.5ms | 1053.6ms |
| immediately eligible Track → first word | 2052.5ms | 2180.2ms | 1225.5ms |
| Track → Preview/HQ | 1317.5ms | 1124.0ms | 203.0ms |
| scheduler created → first enqueue | 1340.5ms | 1370.2ms | 913.3ms |

Sony dispatch-next 是 `adb shell cmd media_session dispatch next` 的自动等价场景，不是真实歌曲自然播放结束。

## 17. 修改后数据

回退前历史真机轮次的正常 30 次结果：

| metric | NEXT p95 | PREVIOUS p95 | Sony dispatch-next p95 | target/result |
|---|---:|---:|---:|---|
| command/Track T0 → Track publish | 1025.9ms | 1005.2ms | 148.2ms | 远程 FAIL；本地等价 PASS |
| Track → current lyric | 115.2ms | 94.3ms | 107.8ms | PASS ≤500ms |
| word eligible → publish | 193.0ms | 282.4ms | 279.1ms | NEXT PASS；其余 MISS ≤250ms |
| immediately eligible Track → first word | 172.2ms | 146.8ms | 225.4ms | PASS ≤500ms |
| Track → Preview/HQ | 68.3ms | 79.4ms | 66.5ms | PASS |
| iOS decode | 1.0ms | 1.0ms | 1.0ms | PASS |
| iOS publish | 17.0ms | 18.0ms | 17.0ms | PASS |

相对增强 Trace 基线，current lyric p95 下降 84%～93%，立即 eligible 的第一词 p95 下降 82%～93%，word eligible → publish 下降 73%～90%。远程 command → Track 只下降约 7%～11%，仍不满足 350ms 目标；主要剩余段是 QQ 音乐/MediaSession 的 dispatch → metadata。

有效覆盖为 NEXT/PREVIOUS/Sony dispatch-next 各 30 个 eligible → publish 样本；立即 eligible Track → first word 分别为 25、25、23 个，低于提示词要求的每场景 30 个正式有效样本，必须如实保留为覆盖缺口。

100 次快速压力完成 100 个控制尝试、观察到 88 次转换；Clock Sync 因测试中跨重连 sample reset 被正确标记为不可信，所以跨设备耗时不生成。所有 8 个 CurrentWord reject 均为 `TRACK_MISMATCH`，9 个 Track Identity reject 为 generation conflict/stale generation，属于栅栏生效；stale accepted、duplicate control、hard reconnect、write timeout、L2CAP response failure 均为 0。

上述数字来自包含后来已回退 response gate 的构建，只用于保留 CurrentWord、歌词和队列优化的历史证据，不等价于 `1154397` 之后当前代码的最终产品结果。下面单独列出回退后当前提交的真机复测，不混用两组数据。

当前提交 `3f706b4` 的 iPhone + Sony 正常场景复测如下：

| metric | NEXT p95 | PREVIOUS p95 | Sony dispatch-next p95 | 结论 |
|---|---:|---:|---:|---|
| command/Track T0 → Track publish | 1026.0ms | 640.0ms | 145.0ms | 远程仍 FAIL ≤350ms；Sony 等价场景 PASS |
| metadata observed → Track accepted | 301.0ms | 91.1ms | 103.4ms | NEXT 有长尾，PREVIOUS/等价稳定 |
| playback ready → notify start | 129.0ms | 19.5ms | 72.4ms | P0 队列无系统性阻塞 |
| lyric ready → current line enqueue | 69.5ms | 21.8ms | 43.8ms | 三场景均 PASS ≤100ms |
| Track → current lyric（原始） | 3843.9ms | 3948.9ms | 112.4ms | NEXT/PREVIOUS 被 QQ 音乐 QRC 缺失/等待样本拉长 |
| word eligible → publish | 407.7ms（2） | 445.2ms（2） | 81.1ms（2） | 样本不足，不能形成 p95 验收 |
| Track → Preview | 1992.1ms | 171.8ms | 138.1ms | NEXT 冷传输 FAIL；其余 PASS |
| Track → HQ | 3543.7ms | 171.8ms | 138.1ms | NEXT 冷传输 FAIL；其余 PASS |

括号为 CurrentWord 有效样本数。当前播放列表绝大多数歌曲只有逐行歌词，本轮每场景都只有 2 个立即 eligible 样本，因此必须保留为覆盖缺口。报告器新增 `lyricMissingReasons` 与聚合计数，区分 `WAITING_QQMUSIC_CACHE`、`NO_QRC_FILE`、`NO_LINE_AT_POSITION`、精确缓存 miss 等原因；既有指标重新生成后数值不变。

100 次 650ms 快速压力完成 100 个控制意图和 100 个写回调，播放器实际产生 55 次 Track change；报告按压力口径 PASS。`stale accepted`、`duplicate control`、command timeout、hard reconnect、CurrentWord reject 均为 0。30 个被取消的媒体任务全部是切歌触发的 `track_changed/stale_generation` 主动取消，没有队列永久卡住；快速压力数据不计入正常 SLO。

加入 authority-acceptance fast lane 后的当前 iPhone + Sony 正常场景：

| metric | NEXT p95 | PREVIOUS p95 | 对比上一当前轮次 |
|---|---:|---:|---:|
| command → Track publish | 546.4ms | 391.8ms | NEXT -46.7%；PREVIOUS -38.8% |
| metadata observed → Track accepted | 30.5ms | 14.0ms | NEXT -89.9%；PREVIOUS -84.6% |
| Track → current lyric | 426.8ms（28） | 566.6ms（25） | QRC 可用性仍影响覆盖 |
| word eligible → publish | 189.0ms（4） | 149.6ms（13） | 样本 p95 满足 ≤250ms，覆盖仍不足 |
| Track → Preview | 1338.5ms | 92.2ms | NEXT 冷图仍 FAIL；PREVIOUS PASS |

NEXT/PREVIOUS 均完成 30/30 转换，`trackIdentityFastReady/trackFastLanePublished` 均为 30/30，没有 command timeout、hard reconnect、duplicate control 或 stale accepted。NEXT 主要剩余段是 dispatch 结束到 QQ 音乐/MediaSession 暴露新 metadata，fast lane 不提前猜测歌曲，因此不能消除这段外部等待。

最终 100 次 650ms 快速交替压力报告 PASS：100 个控制意图、53 次播放器实际身份切换、53/53 fast lane 发布；`STALE_CONTENT=0`、`duplicate_control=0`、`malformed=0`。一条旧身份被 generation fence 正确拒绝，2 个在途 Preview 被新曲抢占取消，都没有被 iOS 接受为当前内容。

专用逐字 QRC 90 秒窗口的旧版日志报告统计到 27 条 iOS CurrentWord accepted，stale discard=0、main stall=0、execution gap=0，日志派生 latency p95=114ms，CurrentWord 与 Live Activity 检查均 PASS。日志可能节流，该结果仅说明这一稳态窗口表现，不能替代统一 Trace 下切歌 → 首字的完整 SLO 验收。另一首只有逐行歌词的样本明确返回 `LINE_ONLY/NO_WORD_TIMING`，不伪造 CurrentWord。

## 18. 两小时 Soak

最终真机轮次实际观察 120 分钟。Sony Trace 有 33 条 track-change 事件；按精确 trackId 的相邻状态去除 2 条同曲刷新后，为 31 个顺序唯一状态、30 次真实自然转换。该轮没有发送 dispatch-next；测试工具仅依据明确的 `mode=natural` 测量场景在报告层标为 `NATURAL_AUTOPLAY`，设备原始 Trace 的 `triggerType` 仍为 `UNKNOWN`，不能表述成 QQ 音乐主动提供了自然播放来源标签。

CurrentWord 的 `eligible/sendEnd` 为 11520/11520，`currentWordRejected=0`；PlayerAgent PID 全程不变，前台服务缺失 0，ANR 0，PlayerAgent crash 0。120 个每分钟资源点中，PSS 为 55933KB → 78233KB，范围 54057～117314KB；前 20 分钟包含歌词/封面缓存预热，去掉前 20 分钟后的线性趋势为 -64.2KB/min，没有观察到持续无界增长。1 个非 playing 采样点发生在真实切歌边界，约 100ms 后 Track Trace 已从 paused 恢复 playing。

本轮在约第 97 分钟发生一次 Sony 系统 Bluetooth stack 故障：8.3 秒内出现 4 次 L2CAP write failure，随后 `hci_timeout_abort`，系统杀死并重启 `com.android.bluetooth`。PlayerAgent 正确清理 runtime、重建 GATT/广播，iPhone 从 Bluetooth service down 到重新连接并订阅约 4.4 秒，之后 CurrentWord 恢复且故障没有再次发生。它证明恢复路径有效，但违反 0 crash/0 hard reconnect/0 L2CAP failure 的 Soak 验收，因此本轮结果为 `FAIL`。

iOS App 日志只有 current + old 两段滚动文件。末尾原生报告虽然看到 33 次 Sony 转换，却只有 3 个可见 iOS Track change、0 个完整 Handoff 样本并返回 `trace_sample_incomplete`。补充的只读定时快照去重后恢复 52662 条 iOS Trace、19 次 iOS Track change、7369 次 CurrentWord publish，但只有 7 个包含 UI consume 的完整样本；同时 Bluetooth 重启使 Clock Sync 不可信。因此自然场景跨端 p95 为 `NOT APPLICABLE`，不得使用错误相关的长时延代替正式性能结果。

## 19. 资源开销

新增常驻状态仅为两端单个 pending/current Handoff context、固定 2048 条 Trace ring、一个可取消的 iOS playback fallback work item 和已有队列中的 trace context，目标远低于 2MB。新增大块歌词/图片 cache、持续轮询、主线程磁盘 I/O和网络访问均为 0。

Sony 新增一个 CurrentWord 单线程 scheduler，替代共享 executor 上的同类任务，不增加周期任务数量；暂停或无歌词时没有固定轮询。iOS 没有新增 Timer，界面只在 metadata Slice 真正变化时记录 consume，不引入整页随机 identity 重绘。

最终资源采样显示 PlayerAgent PID count=1、foreground service missing=0。PSS 全程斜率受冷启动缓存预热影响为 +137.6KB/min，但去掉前 20 分钟后为 -64.2KB/min，最后 60 分钟为 -83.8KB/min；因此没有进程内持续泄漏证据。系统 Bluetooth 进程的单次崩溃是独立的正确性/稳定性失败，不能由 PSS 趋势抵消。

## 20. 未完成问题

- 远程 NEXT/PREVIOUS command → Track publish p95 已下降到 546.4ms 和 391.8ms，但仍未达到 350ms；当前主要剩余段是播放器切换/metadata 暴露及其波动。
- 专用逐字 QRC 90 秒窗口的日志派生 CurrentWord latency p95=114ms 且未观察到 stale，但 NEXT/PREVIOUS 切歌场景仍只有 4/13 个 word eligible 样本，未达到每场景 30 个覆盖目标。
- NEXT 冷图 Track → Preview p95 仍为 1338.5ms，未达 800ms；PREVIOUS 的缓存/重用路径为 92.2ms。
- 120 分钟自然播放虽完成 30 次真实转换，但 Sony 系统 Bluetooth stack 在约第 97 分钟发生 1 次 HCI timeout/process death；4 次 L2CAP write failure，Soak 为 FAIL。
- iOS 滚动日志只恢复到 19 次 Track change、7 个完整 UI Handoff；Clock Sync 又因 Bluetooth 重启不可信，自然场景跨端 p95 为 `NOT APPLICABLE`。
- Android Controller 与双控制器矩阵按当前优先级延期，为 `SKIPPED（当前范围）`。
- authority-acceptance fast lane 的 iPhone + Sony NEXT/PREVIOUS 各 30 次、CurrentWord 90 秒和 100 次快速压力已执行；保留实现的这些轮次均 PASS。远程 Track、部分 QRC 冷路径和 NEXT 冷图片仍未达到目标。
- 历史快速压力曾发生 Clock Sync reset；本轮 100 次压力 Clock Sync 可信且无 hard reconnect，但仍只按正确性口径验收，不作为正常 SLO。

在上述项闭环前，第四阶段结论只能是“未完成，存在阻断项”。

## 21. Fast lane 提交前复核（2026-09-05）

- Sony `testDebugUnitTest` / `assembleDebug`：PASS；新增身份变化与不完整 metadata 的策略测试。最终 APK 已覆盖安装，未清除应用数据。
- Sony quick smoke：PASS，Required 8/8，Optional pass=5、warn=0、skipped=2；无订阅者窗口的 PlaybackDiff / CurrentWord 为 SKIPPED，不能算作链路通过。
- iOS quick smoke：PASS，Required 6/6，Optional pass=4、warn=4、skipped=1；Notify、PlaybackState、Healthy 与封面链路 PASS。没有新扫描/发现/连接事件及 CurrentWord 样本的项目保留 WARN。
- 最终安装版补测 NEXT 5 次：Trace 完整性 PASS（5/5，MTU 515、Clock Sync 可信、fast lane 5/5），不等于性能通过。command → Track p50/p95/max 为 938/3153.6/3591ms，其中 dispatch → metadata p95/max 为 2838.2/3425ms，metadata → accepted p95 为 45.4ms。冷图 Preview p95 为 2643.2ms；本轮无 word eligible 样本，一首缺少本地 QRC。该小样本不能替代前述 30 次对照，也不能忽略：它显示播放器 metadata 等待及冷图长尾仍不稳定，尚不能宣称端到端性能稳定改善。
- 两个 quick 脚本均跳过构建/安装；上述 Sony 构建与安装是单独执行的。iOS 本批无源码改动，本地 XCTest、全量 smoke 与重新安装为 SKIPPED；提交后的 XCTest 以对应 GitHub Actions 结果为准。
- Sony lint：FAIL（历史基线），修改前后均为 20 errors / 33 warnings，没有增加；未使用 suppress 或关闭 abortOnError。Android Controller 本地构建/lint 及双控制器真机测试为 SKIPPED（当前范围）。
- BLE UUID、既有 command/status 语义、A1/A2 固定 Header、ATT response 生命周期与 pacing 均未修改；没有新增 Timer 或持续轮询。可选身份回调异常被隔离，后续正常歌词/冷路径继续执行。
- `git diff --check`：PASS。图谱已完整重建且不落盘；当前工具未提供 `detect_changes`，影响范围由源代码 diff、调用路径和上述测试交叉核实。

原始报告仅保留在本机 `/tmp/musicble_phase4/`、`/tmp/music_ble_ios_smoke/` 与 `/tmp/music_ble_android_smoke/`，不提交真实歌曲名、设备标识或完整日志。
