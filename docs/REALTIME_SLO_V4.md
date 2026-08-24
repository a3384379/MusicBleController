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

Gate 1 只增加观测能力。Gate 2 是否产生 `perf: reduce first-frame media latency` 提交，必须由第 11 节基线证明；若所有主要指标达标，则明确记录“已满足目标，不做无意义优化”。

本节将在连接展示与 Sony artwork identity fence 修复后，用相同真机场景补充 before/after。基线已证明允许修改的范围仅限：写回调异常时的连接展示稳定、stale generation 拒绝、以及事件触发的精确封面刷新；不调整 BLE delay、全局 priority、Timer、chunk size 或图片编码质量。

## 13. 正确性与 stale fence

报告按 iOS 当前 `trackId + generation` 检查 CurrentWord、歌词和封面 publish。旧事件被明确 drop 是保护生效，不计作 stale accepted；只有旧 generation 或不匹配内容进入 accepted/published 阶段才计入 `STALE_CONTENT`。

Sony 同一 `commandSeq + commandType` 的多次 `mediaControlDispatchStart` 计作重复执行；同一控制命令多次 `commandReceived` 单列为可能的重连补发。既有 generation、transferId、CRC 和顺序栅栏保持不变。

## 14. CPU、内存、电量和日志

- 两端各 2048 条固定 Ring Buffer，目标常驻增量小于 2 MB。
- iOS 复用 `AppLogStore` 串行异步缓冲与 2 MB 文件上限；Sony 使用单线程 daemon 日志执行器。
- Debug/Smoke 才启用完整事件流；Release 不产生完整事件流。
- 普通模式 BLE notify 数量不增加，Timer 不增加。
- Trace 不做 JSON pretty print，不做主线程同步 I/O，不解码图片，不参与重连。
- 诊断 UI 只读已有 Snapshot 并手动刷新，不绘制实时图表。

## 15. BLE 协议审计

本阶段不修改 BLE UUID、characteristic 用途、命令名称、A1/A2 header、FullLyrics JSON、LyricSecondary schema 或 QRC Triple DES。Trace 只写本地日志；没有新增 notify、字段或兼容分支，旧控制端行为不变。

## 16. 已知边界与未完成瓶颈

- Clock Sync 样本不足时端到端跨设备指标不可用，这是可信度保护，不是 0 ms。
- 快速切歌可能被播放器或 MediaSession 合并；无法关联的样本记为 missing，不选择最好一次。
- QRC 解密/解析若重新成为 Top 3，将拆为 Phase 2.1，不在本阶段顺手重写。
- 真实设备基线与前后台恢复结果完成前，本节不声明产品性能达标。

## 17. 下一阶段建议

基线完成后只处理事件证据指向的最大区间：ready→pending、pending→queue、重复 PlaybackState、CurrentWord 启动、Preview/HQ 竞争或 iOS decode/publish。每项优化必须保留 stale fence，并用同一场景输出 before/after p95 与正确性计数。
