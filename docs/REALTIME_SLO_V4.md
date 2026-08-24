# MusicBleController V4 Real-time SLO

本文定义 V4 第二阶段的实时性 Trace、端到端 SLO、自动报告和真机基线方法。Trace 只观察现有业务链路，不参与播放、歌词、封面、连接或重连决策。

## 1. 范围与数据链路

测量链路为：iOS 用户意图 → CoreBluetooth 写队列 → Sony GATT 命令接收 → MediaSession 控制 → 新 Track Identity → 歌词/封面就绪 → Sony Notify 队列 → iOS 协议接收/解码 → Observable State 发布 → SwiftUI 主播放器消费。

本阶段没有新增第二套 Track Identity、generation、时钟、BLE 队列或媒体状态。iOS 继续以 `BLETestManager` 和聚焦 Store 为状态边界；Sony 继续以 `ReactiveMediaController`、`CurrentTrackRuntimeCache`、`PlaybackStateReader` 和 `BleNotifyQueue` 为权威链路。

## 2. Trace 数据模型

两端稳定输出单行结构化事件：

```text
[RealtimeTrace] side=sony stage=lyricReady monoMs=123 commandSeq=- commandType=- trackId=... generation=7 transferId=- payloadType=lyrics queueWaitMs=- processingMs=- chunkIndex=- chunkCount=- result=ready reason=-
```

固定字段为 `side`、`stage`、`monoMs`、`commandSeq`、`commandType`、`trackId`、`generation`、`transferId`、`payloadType`、`queueWaitMs`、`processingMs`、`chunkIndex`、`chunkCount`、`result`、`reason`。缺失值统一为 `-`。

Trace 不写歌曲标题、歌手、专辑、歌词正文、图片正文或 Base64。字符串限制为 96 个安全字符；Track 使用协议现有的稳定 `trackId`。

## 3. 单调时钟

- iOS：`ProcessInfo.processInfo.systemUptime * 1000`。
- Sony：`SystemClock.elapsedRealtime()`。
- 持续时间禁止使用墙上时间。
- 两端 Ring Buffer 都固定为 2048 条；时钟采样发生回退时只保证 Trace 序列不倒退，不改变业务时钟。

## 4. T0 定义

手动 NEXT/PREVIOUS 的 T0 是 `commandIntent`：用户命令进入 iOS BLE 写队列之前的单调时刻。报告器按顺序把该命令与下一次真实 `trackIdentityAccepted` 闭环关联；快速切歌中无法在下一条命令前确认身份的样本标记为缺失，不猜测归属。

Sony 本机或自动下一首的 T0 是 Sony `trackIdentityAccepted`。只有 Clock Sync 可信时，报告器才把该时刻映射到 iOS 单调时间；不可信时端到端指标保持空值。

缓存快照不是新 Track T0，也不计作新歌曲首屏完成。

## 5. Clock Sync 可信策略

报告器复用现有 `clockSyncV1`，只接受最新 `confident=true` 的 `[ClockSync] pong` 样本。日志中的 `offsetMs` 是 `iOS monotonic - Sony monotonic`，因此 Sony 事件映射为 `sonyMonoMs + offsetMs`。

Clock Sync 不可信时：

- `commandToSonyReceiveMs`、自动切歌端到端和 Sony→iOS FullLyrics 指标不生成数值。
- Sony 内部 `ready → pending → queue → send` 与 iOS 内部 `receive → decode → publish` 仍分别报告。
- `CLOCK_SYNC_UNTRUSTED` 和对应 `missingCount` 明确增加，禁止用墙上时间补齐。

## 6. SLO

| 指标 | p95 目标 |
|---|---:|
| iOS command enqueue → Sony command receive | ≤ 200 ms |
| T0 → iOS Track/Playback publish | ≤ 300 ms |
| T0 → 当前歌词 publish | ≤ 500 ms；stretch 300 ms |
| T0 → CurrentWord publish | ≤ 500 ms |
| T0 → cache/Preview 封面 publish | ≤ 800 ms；cache stretch 300 ms |
| T0 → HQ 封面 publish | ≤ 2500 ms |
| lyric ready → iOS FullLyrics publish | ≤ 2000 ms |
| lyric ready → pending flush | ≤ 100 ms |

旧 Track/Generation 覆盖、错误 CurrentWord、错误封面、stale transfer accepted、重复控制执行和控制命令重连补发必须为 0。

## 7. Trace 阶段

Sony 覆盖控制接收/校验/派发、MediaSession Track、generation、runtime cache、PlaybackState、歌词请求/就绪/pending/full transfer、CurrentWord 调度/coalesce/drop/send、封面 detect/cache/offer/preview/HQ，以及 Notify enqueue/dequeue/send/callback/timeout/preempt/cancel。

iOS 覆盖命令 intent/enqueue/write/callback/timeout、status notify、PlaybackState decode/publish、Track Identity、当前歌词、CurrentWord、LyricWindow、FullLyrics、Artwork offer/cache/preview/HQ，以及主播放器真正消费新状态的 `nowPlayingViewStateChanged`。

SwiftUI `body` 不写盘、不读取完整日志、不增加 Timer，也不通过随机 identity 触发重建。

## 8. 报告工具与产物

普通、反向和自动场景：

```bash
./tools/smoke/realtime_latency_v4_test.sh --runs 30 --json
./tools/smoke/realtime_latency_v4_test.sh --runs 30 --previous --json
./tools/smoke/realtime_latency_v4_test.sh --runs 30 --auto --json
```

快速切歌：

```bash
./tools/smoke/realtime_latency_v4_test.sh --runs 100 --fast-switch --json
```

每轮写入 `/tmp/musicble_realtime_v4/<timestamp>/`：`report.json`、`summary.md`、`sony_trace.log`、`ios_trace.log`、`raw_events.jsonl`，并保留两端完整原始日志供本地审计。真实设备日志不提交 Git。

## 9. 报告字段与分类

每个指标输出 `count/min/avg/p50/p95/p99/max/missingCount`。指标包括命令、Track、当前歌词、CurrentWord、Preview、HQ、lyric ready/pending、FullLyrics、Notify queue、iOS decode/publish；另外输出 `lyricReadyToFullLyricsPublishMs` 直接验收后台完整歌词 SLO。

分类固定包含 `COMMAND_DELAY`、`TRACK_IDENTITY_DELAY`、`LYRIC_READY_DELAY`、`PENDING_FLUSH_DELAY`、`SEND_QUEUE_DELAY`、`CURRENT_WORD_DELAY`、`ARTWORK_DELAY`、`IOS_DECODE_DELAY`、`IOS_PUBLISH_DELAY`、`CLOCK_SYNC_UNTRUSTED`、`TRACE_INCOMPLETE`、`STALE_CONTENT`。

正确性诊断另列 `duplicate_control`、`control_reconnect_resend`、`wrong_current_word`、`wrong_artwork`、`wrong_lyrics` 和 stale generation。Top 10 慢样本不包含媒体正文。

## 10. 测试场景

硬基线包含至少 30 次 NEXT、30 次 PREVIOUS、30 次 Sony 本机自动等价切歌、100 次 650 ms 快速交替切歌。报告同时记录 runtime/歌词/封面 cache 命中与未命中、Preview/HQ、playing/paused、iOS 前台/后台恢复和控制器数量。

双控制器只有在第二台 Controller 真机实际可用时才执行；未执行必须标记 SKIPPED，不能写成 PASS。

## 11. 修改前真机基线 — BASELINE ONLY

以下数据由本分支 Trace 代码、真实 iPhone 与 Sony 设备于 2026-08-24 生成。原始日志保留在本机 `/tmp/musicble_phase2_baseline/`，不提交仓库。常规 NEXT 场景 Clock Sync 不可信，因此跨设备 `commandToSonyReceiveMs` 明确标为不可用；其余单端与 iOS T0 指标仍有效。

| metric | count | p50 | p95 | p99 | max | missing |
|---|---:|---:|---:|---:|---:|---:|
| commandToSonyReceiveMs | 0 | — | — | — | — | 30 |
| commandToTrackPublishMs | 30 | 867 | 1344.6 | 1537.5 | 1592 | 0 |
| trackToCurrentLyricMs | 28 | 1296.5 | 3372.3 | 3620.1 | 3696 | 2 |
| trackToCurrentWordMs | 26 | 2011 | 3620.3 | 3920.3 | 4005 | 4 |
| trackToPreviewArtMs | 29 | 1940 | 2473.8 | 2632 | 2679 | 1 |
| trackToHqArtMs | 25 | 3507 | 4341.4 | 5007.7 | 5216 | 5 |
| lyricReadyToPendingFlushMs | 2 | 35.5 | 48.6 | 49.7 | 50 | 0 |
| pendingFlushToSendStartMs | 2 | 88 | 88.9 | 89 | 89 | 0 |
| fullLyricsSendDurationMs | 30 | 129 | 338.1 | 625.6 | 737 | 0 |
| iOSDecodeDurationMs | 864 | 0 | 1 | 1 | 2 | 0 |
| iOSPublishDurationMs | 127 | 0 | 7.7 | 14.4 | 22 | 0 |

补充场景：PREVIOUS 30 次、Sony 自动等价切歌 30 次和 650ms 快速交替 100 次均生成 PASS 报告。快速场景只有 39/100 次在下一条命令前形成可观察身份切换，其余 61 次按定义记为 missing，不用后续切换冒充当前样本。

Top 3 瓶颈：

1. Track Identity 发布：常规 NEXT p95 1344.6ms，明显高于 300ms SLO；iOS decode/publish p95 仅 1ms/7.7ms，瓶颈不在 SwiftUI 或 JSON 解码。
2. Current lyric / CurrentWord 首帧：相对 Track 的 p95 分别为 3372.3ms/3620.3ms；需要优先排查身份确认后的歌词就绪与 CurrentWord 启动，而不是盲目调整 QRC 解析。
3. Artwork 首帧：Preview/HQ p95 分别为 2473.8ms/4341.4ms；真实日志同时暴露了 Sony UI 对旧通知封面的错误 READY 晋升。

Notify queue p95 为 79.6ms，单次 iOS decode/publish 远低于 SLO，因此本轮不修改 chunk size、CurrentWord 周期或全局 notify 优先级。100 次压力场景还复现 3 次“Sony 已接收并成功响应，但 iOS 写回调缺失”触发的主动硬重连，这是连接展示抖动的直接证据。

## 12. 修改后数据与实际优化

Gate 1 只增加观测能力。Gate 2 使用同一台 iPhone、同一台 Sony 和相同的 100 次 650ms 快速交替切歌复测。两轮 Clock Sync 都不可信，因此跨设备命令接收继续标记为不可用，不用墙上时间填充。

修改后报告保存在本机 `/tmp/musicble_phase2_after_fix2_fast100/`，原始设备日志不提交仓库。

| metric | count | p50 | p95 | p99 | max | missing |
|---|---:|---:|---:|---:|---:|---:|
| commandToSonyReceiveMs | 0 | — | — | — | — | 100 |
| commandToTrackPublishMs | 46 | 359.5 | 621.8 | 628.1 | 629 | 54 |
| trackToCurrentLyricMs | 25 | 410 | 1230.8 | 5945.2 | 7414 | 21 |
| trackToCurrentWordMs | 1 | 1836 | 1836 | 1836 | 1836 | 45 |
| trackToPreviewArtMs | 46 | 387 | 650 | 655.7 | 657 | 0 |
| trackToHqArtMs | 30 | 394 | 652.2 | 656.1 | 657 | 16 |
| lyricReadyToPendingFlushMs | 0 | — | — | — | — | 0 |
| pendingFlushToSendStartMs | 0 | — | — | — | — | 0 |
| fullLyricsSendDurationMs | 37 | 177 | 319.2 | 381.9 | 392 | 6 |
| iOSDecodeDurationMs | 1119 | 0 | 1 | 1 | 2 | 0 |
| iOSPublishDurationMs | 144 | 1 | 20.9 | 23 | 26 | 0 |

同场景 p95 对比：

| metric | before p95 | after p95 | change | 结论 |
|---|---:|---:|---:|---|
| command → Track publish | 631.1ms | 621.8ms | -1.5% | 无明显回退，仍高于 300ms SLO |
| Track → current lyric | 635.5ms | 1230.8ms | +93.7% | 25 个样本含 1 个 7414ms 外部歌词就绪长尾；未宣称优化 |
| Track → CurrentWord | 1196.4ms | 1836ms | 样本 3→1 | 样本不足，不作性能结论 |
| Track → Preview | 663.1ms | 650ms | -2.0% | 保持在 800ms SLO 内 |
| Track → HQ | 631.3ms | 652.2ms | +3.3% | 保持在 2500ms SLO 内 |
| FullLyrics send | 337.4ms | 319.2ms | -5.4% | 无协议或队列参数改动 |
| Notify queue | 124ms | 143ms | +15.3% | 最大值由 887ms 降至 591ms；p95 仍是待优化项 |
| iOS decode | 1ms | 1ms | 0% | 已满足目标 |
| iOS publish | 18ms | 20.9ms | +15.8% | 绝对值仍远低于 100ms |

实际优化严格限于证据指向的正确性和产品状态：

1. CoreBluetooth 已连接且最近仍收到 notify 时，连续两次写回调超时仍执行必要的传输自愈，但在 8 秒保护窗内保持“已连接”展示；只有同步失败或保护窗到期才显示重连。修复前压力轮次出现 3 次可见假重连，修复后一次同类内部自愈期间可见假重连为 0，4.38 秒后恢复同步。
2. iOS 对同一 Sony session 的 Track Identity 增加 generation 回退和同 generation 冲突栅栏；新 session 与 legacy generation=0 保持兼容。复测拒绝 2 个迟到身份事件，`STALE_CONTENT=0`。
3. Sony PlayerAgent UI 改为监听既有 QQ 音乐通知事件，并按当前 title/artist 精确验证 MediaMetadata/Notification 后才晋升封面。轨道变化先进入 LOADING，迟到任务受 generation+songKey 栅栏约束；临时精确源缺失只保留同一当前歌曲已经 READY 的图。复测最后一次歌曲身份变化后约 85ms 显示匹配封面。

本轮没有修改 BLE delay、全局 priority、Timer、chunk size、图片编码质量或协议。验收采用“找到真实外部瓶颈并有可重复证据”：剩余 Track/歌词长尾位于 MediaSession/媒体源身份与歌词就绪，不在 iOS decode/publish；盲目调低 BLE delay 无法解决。

## 13. 正确性与 stale fence

报告按 iOS 当前 `trackId + generation` 检查 CurrentWord、歌词和封面 publish。旧事件被明确 drop 是保护生效，不计作 stale accepted；只有旧 generation 或不匹配内容进入 accepted/published 阶段才计入 `STALE_CONTENT`。

Sony 同一 `commandSeq + commandType` 的多次 `mediaControlDispatchStart` 计作重复执行；同一控制命令多次 `commandReceived` 单列为可能的重连补发。既有 generation、transferId、CRC 和顺序栅栏保持不变。

100 次复测结果：stale accepted 0、wrong currentWord 0、wrong artwork 0、wrong lyrics 0、duplicate control 0、control reconnect resend 0。快速切歌中被 iOS generation 栅栏拒绝的旧身份事件单独记为 `trackIdentityRejected=2`，不算 accepted。

## 14. CPU、内存、电量和日志

- 两端各 2048 条固定 Ring Buffer，目标常驻增量小于 2 MB。
- iOS 复用 `AppLogStore` 串行异步缓冲与 2 MB 文件上限；Sony 使用单线程 daemon 日志执行器。
- Debug/Smoke 才启用完整事件流；Release 不产生完整事件流。
- 普通模式 BLE notify 数量不增加，Timer 不增加。
- 连接展示保护只复用命令超时和既有连接健康事件；没有新增轮询或 Timer。
- Sony 封面刷新复用 `PlayerNotificationListenerService` 已存在的通知回调和单线程 UI media executor；没有新增周期扫描，精确源不匹配时不解码或发布图片。
- Trace 不做 JSON pretty print，不做主线程同步 I/O，不解码图片，不参与重连。
- 诊断 UI 只读已有 Snapshot 并手动刷新，不绘制实时图表。
- Android lint 在起始 commit 实测为 PlayerAgent 20 errors / 34 warnings，修改后为 20 errors / 33 warnings；Controller 修改前后均为 0 errors / 30 warnings。本阶段没有新增 lint，新增 `AlbumArtIdentityPolicy` 文件为 0 issue。PlayerAgent lint 仍因历史 20 errors 以非零退出，未用 baseline 或 suppress 隐藏。

## 15. BLE 协议审计

本阶段不修改 BLE UUID、characteristic 用途、命令名称、A1/A2 header、FullLyrics JSON、LyricSecondary schema 或 QRC Triple DES。Trace 只写本地日志；没有新增 notify、字段或兼容分支，旧控制端行为不变。

## 16. 已知边界与未完成瓶颈

- Clock Sync 样本不足时端到端跨设备指标不可用，这是可信度保护，不是 0 ms。
- 快速切歌可能被播放器或 MediaSession 合并；无法关联的样本记为 missing，不选择最好一次。
- QRC 解密/解析若重新成为 Top 3，将拆为 Phase 2.1，不在本阶段顺手重写。
- 常规 NEXT 的 Track、歌词、CurrentWord、Preview/HQ 仍未全部达到目标；第二阶段完成的是 CI、可重复 Trace/SLO、正确性栅栏和基于证据的首轮修复，不宣称所有端到端 SLO 已达标。
- 快速场景 CurrentWord 可关联样本只有 1 个，不能据此判断改善或回退。
- 双控制器真机未执行，必须保持 SKIPPED。

## 17. 下一阶段建议

下一阶段应优先使用预测命中、精确缓存和传输消除处理 MediaSession 身份确认、歌词就绪与封面首帧长尾。若继续做第二阶段微调，仍只处理事件证据指向的 ready→pending、pending→queue、CurrentWord 启动或 Preview/HQ 竞争；每项优化必须保留 stale fence，并用同一场景输出 before/after p95 与正确性计数。

## 18. 第三阶段结果索引

第三阶段实现和数据见 [PREDICTIVE_MEDIA_ENGINE_V4.md](/Volumes/雷电/project/MusicBleController/docs/PREDICTIVE_MEDIA_ENGINE_V4.md)。正式 Prediction Source Audit 采集 239 次转换，但当前 Sony/QQ 音乐的 MediaSession queue available=0、activeQueueItemId available=0，高置信候选覆盖率为 0，所以 Warm Path 全部标记 `NOT APPLICABLE`，跨端 prefetch Gate 跳过。

已落地的可测收益是 `mediaCacheValidationV1`：iOS 与 Sony 精确校验 FullLyrics fingerprint/schema/原文及 secondary 行数，命中时只发轻量 not-modified 并继续 CurrentLine/CurrentWord，不再传 A2/legacy 正文。第一轮 100 个快速控制观察到 31 次传输跳过、估算节省 317,060 bytes；stale accepted、wrong CurrentWord、wrong artwork、visible false positive、duplicate control 和 cold fallback failure 均为 0。该轮 command→Track p95 608.8ms，相对第二阶段同场景 621.8ms 无回退；图片冷路径仍有外部长尾，未宣称 Warm SLO 达标。
