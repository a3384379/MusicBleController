# MusicBleController V4 Predictive Media Engine

本文记录第三阶段的预测来源审计、有界 Sony 本地预热、精确歌词缓存校验和回退边界。阶段目标是减少可消除的歌词准备与 BLE 重传，不提前展示未经 Sony 当前 PlaybackState 确认的媒体。

## 1. Gate 0 与基线

第三阶段从第二阶段最终提交 `c89acc7a5815d49e7026cc78c0f988c532be3f45` 创建 `codex/v4-phase3-predictive-engine`。第二阶段的 iOS/Android GitHub Jobs、iOS XCTest、iPhone/Sony smoke 均为 PASS；基线和 Top 3 瓶颈见 [REALTIME_SLO_V4.md](/Volumes/雷电/project/MusicBleController/docs/REALTIME_SLO_V4.md)。

第二阶段同场景快速切歌基线：command→Track p95 621.8ms、Track→当前歌词 p95 1230.8ms、Track→Preview p95 650ms、Track→HQ p95 652.2ms。CurrentWord 只有 1 个可关联样本，不能作回退结论。主要时间不在 iOS 解码/发布，而在播放器身份确认、歌词就绪和媒体传输。

## 2. Prediction Source Audit

正式审计采集 239 次真实歌曲转换，产物保存在本机 `/tmp/musicble_prediction_audit_v4/phase3_gate1_100_final/`，真实设备日志不提交仓库。

| source | confidence | coverage | accuracy | average lead time | local prewarm | cross-device prefetch |
|---|---|---:|---:|---:|---|---|
| MediaSession queue + activeQueueItemId | CONFIRMED/STRONG | 0% | N/A | N/A | 可实现，当前设备无来源 | 否 |
| NEXT/PREVIOUS + visible queue | CONFIRMED/STRONG | 0% | N/A | N/A | 可实现，当前设备无来源 | 否 |
| learned history adjacency | WEAK | 仅低置信本地事件 | 不用于可见预测 | N/A | 是，仅 QRC 索引预热 | 否 |
| no candidate | NONE | 100% high-confidence miss | N/A | N/A | 冷路径 | 否 |

审计结果：213 次 eligible transition，218 次 queue observation 中 queue available=0、activeQueueItemId available=0；高置信候选数为 0，预测覆盖率 0%，准确率和提前量均为 N/A。当前 Sony/QQ 音乐版本不暴露可用播放队列，通知只暴露当前媒体。因此 Gate 4 跨端预取按停止条件跳过，没有猜歌、读取私有数据库、Accessibility 抓取或联网请求。

## 3. 信任等级与来源解析

- `CONFIRMED`：activeQueueItemId 精确定位当前项，下一/上一 QueueItem 同时有 queueItemId、mediaId、title、artist。
- `STRONG`：当前项能唯一精确定位，目标有稳定 queueItemId 或 mediaId/trackId 以及完整 title+artist。
- `WEAK`：只来自重复历史邻接或不完整队列身份；仅允许 Sony 低优先级本地索引预热。
- `NONE`：没有候选，直接保留冷路径。

`PredictionSourceResolver` 不用模糊 title 匹配定位当前项。晋升必须精确匹配 title、artist，并且至少有稳定 mediaId 或 trackId；可用时还校验 2 秒 duration bucket。WEAK 永远不晋升，也不发送到 iOS。

## 4. Candidate 状态机与 Hot Set

状态为 `DISCOVERED → PREWARM_QUEUED → PREWARMING → LYRICS_READY/ARTWORK_READY/READY`，结束状态为 `PROMOTED/REJECTED/INVALIDATED/EXPIRED`。

`PredictiveHotSet` 只保存最多两个非当前候选，使用 access-order LRU 和 2 分钟 TTL。候选只保存 identity digest、稳定 ID、fingerprint 和 readiness 等元数据，不复制完整歌词、压缩正文或 Bitmap。重复 callback 按 source-independent candidateKey 去重，不会重复提交预热；容量淘汰、TTL、fingerprint 变化和 coordinator 关闭都会显式失效。

候选没有正式 generation。真实 Track Identity 被 Sony 接受后才建立当前 generation，再尝试晋升。

## 5. Sony 本地预热与晋升

`PredictiveMediaCoordinator` 使用 maintenance executor 的最低线程优先级，任务可通过 candidateKey/TTL 栅栏取消；省电模式、当前歌词正在前台加载或候选已过期时降级。它不增加 Timer 或周期轮询，只由 queue observation、NEXT/PREVIOUS、track transition 和既有 QRC 事件触发。

本阶段预热内容严格限于现有 QRC parsed cache 的精确查找、fingerprint 和逐字能力；不复制歌词正文、不在实时线程解密/压缩、不预编码 HQ。现有 QQ 音乐未暴露候选封面 identity，所以 artwork prewarm 只保留模型能力，实际不执行猜测式读取。

真实切歌时，只有唯一 CONFIRMED/STRONG 候选通过稳定身份和 fingerprint 复验才能从现有 QRC cache 原子应用到 `CurrentTrackRuntimeCache` 并恢复 CurrentWord。任何不匹配、未 ready、cache miss 或 fingerprint 变化都丢弃候选并继续原冷路径。WEAK 以 `weak_local_only` 拒绝，不能变成可见状态。

## 6. iOS Cache-First Handoff

由于 Gate 1 没有高置信候选，本阶段没有新增 iOS Prefetch Cache、PrefetchTransferCoordinator 或候选 UI 状态。Sony 当前 PlaybackState 仍是 iOS 当前歌曲的唯一权威来源。

已实现的是当前歌曲确认后的精确 FullLyrics cache-first handoff：iOS 可先展示按 trackId 且复核 title+artist 的本地歌词；只有 Sony 返回相同 trackId、当前 generation、内容 fingerprint、schema 和行数后，才把它标记为正式可浏览内容。旧歌曲迟到回调继续受 generation 栅栏拒绝。

## 7. FullLyrics 缓存校验与增量发布

V3 `f3` bit3 定义 `mediaCacheValidationV1`。只有双方 ACK 后，iOS 才在原 `GET_FULL_LYRICS` 中附带紧凑字段：`id/p/w/f/fp/sv/n/tc/rc`。请求省略 Sony 从未使用的墙上时间字段，保持在常见 182-byte ATT write 内。

Sony 对实际歌词生成 SHA-256 截断 fingerprint，覆盖 title、artist、每行时长/正文、translation、romanization 和逐字 timing；并精确比较 schema、主歌词行数、翻译行数和罗马音行数。完全相同时返回轻量 `fullLyricsNotModified` 并跳过 A2/legacy 正文；不匹配时返回 `fullLyricsCacheMetadata` 后执行原完整传输。

iOS cache v3 将 Sony QRC 远端 fingerprint 与 iOS 已落盘正文的 local fingerprint 分开保存。远端 fingerprint 用于 `fullLyricsNotModified` 协商；local fingerprint 由 iOS 对实际收到并持久化的主歌词、逐字和 secondary 内容独立计算，用于发现本地损坏。两者不能互相替代，因为 Sony QRC fingerprint 可以覆盖未包含在当前 FullLyrics 正文中的完整逐字数据。只有远端 descriptor 完整、local fingerprint 精确复算一致时才发送校验请求。legacy v1/v2 仍可即时显示，但不能抑制一次完整传输；成功冷取后迁移到 v3。缓存损坏、descriptor 不完整或 not-modified 与本地内容不一致时删除该条并只强制刷新一次；新 descriptor 只在完整正文实际发布后落盘，不能覆盖到旧正文。negative cache 不参与校验。CurrentLine、LyricWindow、CurrentWord、position anchor 和 generation 始终继续实时发送。

## 8. Capability、协议与兼容

- 未协商 bit3：Sony/iOS 完全沿用 V2/legacy FullLyrics。
- 新 iOS + 旧 Sony：新增请求字段不会发送；正常完整传输。
- 新 Sony + 旧 iOS/Android Controller：旧端不声明 bit3，不收到 not-modified/metadata。
- A1/A2 固定 header、UUID、command/status characteristic、已有命令名和 payload 均不变。
- capability 和歌词传输状态继续按控制器地址隔离；没有共享 prefetch session。
- `sid/es` 只在该设备实际协商 `statusMetaV1` 后附加；MTU 185 等小 payload 会关闭 bit0，不能把未协商元数据挤进 `mediaLoadState` 或错误状态。
- 当前媒体的 TrackInfo、PlaybackState、歌词、CurrentWord 与 A1 封面统一通过 `MediaWireGenerationPolicy` 解析 wire generation；只有精确 identity 匹配的 runtime generation 才可复用，身份不匹配立即走正式冷路径 generation。

跨端 `prefetchManifest`、`PREFETCH_MEDIA` 和 purpose=prefetch A1/A2 会话均未实现。原因是高置信候选覆盖率为 0，保留它们只会增加无收益协议和 BLE 流量。

## 9. BLE 带宽预算

本阶段预取 BLE packets/bytes 为 0。P0 控制/PlaybackState/CurrentWord、P1 LyricWindow/正式 Preview、P2 正式 FullLyrics/Secondary、P3 HQ/History/Diagnostics 的既有优先级不变。FullLyrics 校验命中反而消除 P2 传输，不占用 CurrentWord 队列。

真机压力还确认 Android 的 notify callback 可能早于 L2CAP 实际排空。封面 chunk 因此使用独立的 15ms 最小 pacing，歌词和实时 JSON 不受影响；收到 command write 时同步预留 response quiet window，让 GATT write response 先于后台图片继续入队。最终 100 次快速切歌中 313/313 次 command write 收到 callback，write timeout、response failed 和 L2CAP congestion 均为 0。

## 10. Trace 与指标

Sony 输出 `predictionCandidateCreated/Updated`、`predictionPrewarmQueued/Start`、`predictionLyricsReady`、`predictionArtworkReady`、`predictionReady`、`predictionPromotionAttempt/Promoted/Rejected/Invalidated/Expired`、`cacheValidationHit/Miss` 和 `fullLyricsTransferSkipped`。iOS 输出 `cacheValidationRequest/Hit/Miss`、`cachedLyricsPublished` 和 `fullLyricsTransferSkipped`。

关联字段只包含 candidateId、identityDigest、trackId、generation、transferId、confidence、source、monoMs、result 和 reason；不记录标题、歌词或图片。

最终真机快速压力数据（100 个 NEXT/PREVIOUS 控制，68 个 eligible transition）观察到：FullLyrics 校验命中/跳过 30 次、节省估算正文 538,028 bytes、Preview cache hit 62；prefetch bytes=0。预测候选 37 个且均为 WEAK，本地预热 queued=4、ready=3、promoted=0，符合不得晋升弱候选的屏障。正确性计数 stale accepted、wrong CurrentWord、wrong artwork、visible false positive、duplicate control、cold fallback failure 均为 0。

缓存协议另有一次冷迁移和一次热命中真机证据：热命中请求包含 `fp/sv/n/tc/rc`，Sony 返回 `fullLyricsNotModified`，iOS 从 v3 cache 发布 59 行且没有 A2 正文。封面链路同一歌曲的 TrackInfo、PlaybackState、Preview/HQ A1 和 CurrentWord 全部使用 generation 1，未出现 generation mismatch 拒绝或旧图回写。

## 11. 性能结果与 SLO

高置信候选为 0，因此 Warm Path 的全部指标为 `NOT APPLICABLE`，不得宣称 ≤150/250/350/300ms 已完成。

最终 Phase3 快速压力的可比指标如下；正式报告保存在本机 `/tmp/musicble_phase3_control_headroom_15ms_final100/`：

| metric | Phase2 p95 | Phase3 p95 | change | result |
|---|---:|---:|---:|---|
| command → Track publish | 621.8ms | 632.6ms | +1.7% | PASS，回退 <10% |
| Track → current lyric | 1230.8ms | 1210.1ms | -1.7% | PASS，无回退 |
| Track → CurrentWord | 样本不足 | 362ms（1 个样本） | N/A | 样本不足，不作回退结论 |
| Track → Preview | 650ms | 707.2ms | +8.8% | PASS，回退 <10% 且 ≤800ms |
| Track → HQ | 652.2ms | 707.2ms | +8.4% | PASS，回退 <10% 且 ≤2500ms |
| iOS decode | 1ms | 1ms | 0% | PASS |
| iOS publish | 20.9ms | 18.5ms | -11.5% | PASS |

Preview/HQ 的正式传输继续使用 Sony/iOS 精确封面栅栏；15ms 封面 pacing 保住了 100 次压力下的控制响应窗口，同时最终 Preview/HQ p95 仍处于 Cold SLO。Warm artwork 因无预测来源为 N/A。

## 12. 资源开销

- Hot Set 固定两项，只有短字符串、枚举、时间戳和 boolean，远小于 2MB。
- iOS FullLyrics cache 仍为 80 项/8MB 磁盘、16 项/2MB 解码内存上限，没有第二份歌词 cache。
- 新增 Timer：0；新增持续轮询：0；新增主线程同步磁盘 I/O：0。
- 预热不在 MediaSession callback、BLE HandlerThread 或 CurrentWord 实时路径做 QRC 解密/压缩。
- 预取 BLE packets=0；notify RTT 不受预取影响。

## 13. 自动化工具

```bash
./tools/smoke/prediction_source_audit_v4.sh --expected-transitions 100 --json
./tools/smoke/predictive_media_v4_test.sh --runs 30 --json
./tools/smoke/predictive_media_v4_test.sh --runs 100 --fast-switch --json
./tools/smoke/predictive_media_v4_test.sh --duration-minutes 120 --json
```

产物分别位于 `/tmp/musicble_prediction_audit_v4/<timestamp>/` 和 `/tmp/musicble_predictive_v4/<timestamp>/`。报告脚本测试覆盖无候选、命中/错误候选、预热完成/未完成、cache hit/skip、缺失/乱序 Trace、stale transfer、双端事件去重和 Warm/Cold 分类。

快速场景以 Sony 实际收到的 NEXT/PREVIOUS 控制数作为压力执行数；播放器合并掉的身份转换继续计入 latency missing，不用后续转换冒充样本。

## 14. 失败与回退

- queue/null、activeQueueItemId=-1：不创建高置信候选，正常冷路径。
- WEAK 历史候选：只做本地 cache index prewarm，切歌时拒绝晋升。
- candidate 过期/淘汰/fingerprint 变化：取消或失效，冷路径。
- FullLyrics cache miss/corrupt/schema/secondary mismatch：完整传输；iOS 强刷最多一次。
- capability timeout/旧客户端/metadata 超 MTU：V2/legacy 完整传输。
- 连接 suspect/stale：没有跨端预取需要取消，正式任务保持既有优先级。
- 小 MTU 未协商 `statusMetaV1`：不附加 `sid/es`，避免 notify payload 超限；其他 V3 capability 保持独立。
- 高频切歌与封面并发：command write 到达时先预留响应窗口，图片以 15ms 最小间隔继续；若真实连接仍无 callback，iOS 仍按既有两阶段 suspect/reconnect 规则处理。

## 15. 未实现能力

- 跨端候选 Manifest、歌词/封面 prefetch 和 iOS PredictiveMediaCache：Gate 4 SKIPPED。
- 预测封面提前编码/HQ prefetch：当前没有合法候选 identity，SKIPPED。
- Warm Path SLO：当前设备不提供预测队列，NOT APPLICABLE。
- 双控制器混合 capability 真机、两小时 soak：需第二控制器/专门长时间窗口，未执行时必须标记 SKIPPED。

这些边界是审计结论，不是未来协议承诺。只有实际设备暴露稳定队列且审计证明准确率、提前量和 BLE 收益后，才重新打开跨端预取 Gate。

## 16. 第四阶段 Cold-Path 后续

第四阶段没有重新打开 Predictive Prefetch，也没有猜测候选歌曲。它处理所有歌曲都适用的 current lyric/CurrentWord 冷路径：lyrics-ready 事件直接发布精确 current line，CurrentWord 使用独立 boundary scheduler 和 event barrier，P0 状态不再停留在结束的大任务 interleave slot。

第三阶段的 `mediaCacheValidationV1`、FullLyrics skip、Hot Set 容量/TTL、15ms AlbumArt pacing、wire generation 和全部 capability/legacy fallback 保持不变。第四阶段正常场景的 current lyric p95 降至 94.3～115.2ms，立即 eligible Track → first word p95 降至 146.8～225.4ms；远程 command → Track 仍受 QQ 音乐/MediaSession dispatch → metadata 的约 0.78 秒外部长尾限制。

详细结果见 [COLD_PATH_HANDOFF_V4.md](/Volumes/雷电/project/MusicBleController/docs/COLD_PATH_HANDOFF_V4.md)。
