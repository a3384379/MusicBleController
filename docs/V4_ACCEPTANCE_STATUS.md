# V4 阶段性交付与未达标指标

更新日期：2026-09-05。运行时证据基于提交 `8c55cd4234bc0c20c05f95f1e4ade429d3d08b7d`；本次合并准备只增加验收说明和 `master` CI 触发，不修改应用业务或协议。

## 主干接纳决定

维护者明确决定先将现有 V4 成果合并到 `master`，随后继续优化。该决定允许提前集成，不降低原验收阈值，也不把 FAIL、SKIPPED 或 NOT APPLICABLE 改为 PASS。

本次定位为 **V4 阶段性交付**，不是“V4 全量性能达标”或“第四阶段完成”。不创建正式发布标签。保留各阶段提交与回退记录，使用普通 merge commit；不 squash、rebase 或 force push。当前真机优化范围优先 iOS + Sony，Android Controller/双控制器的产品验收延期。

## 尚未满足的指标

以下时间单位均为 ms。9 月 4 日 NEXT/PREVIOUS 各 30 次是两个独立场景；9 月 5 日 NEXT 5 次是最终安装版补测，样本量较小，不与前两轮混算，也不隐藏其长尾反例。

| 项目 | 原目标 | 实测与证据 | 当前状态 |
|---|---|---|---|
| command → Track publish | p95 ≤350 | NEXT 30 次 546.4；PREVIOUS 30 次 391.8；最终 NEXT 5 次 3153.6（max 3591） | FAIL，长尾仍不稳定 |
| Track → current lyric | p95 ≤500 | NEXT 426.8（28 个）；PREVIOUS 566.6（25 个）；最终 NEXT 768.3（4 个） | 部分场景 FAIL，另有无本地 QRC/无当前行的覆盖缺口 |
| word eligible → publish | p95 ≤250，正常场景足够有效样本 | NEXT 189.0（4 个）、PREVIOUS 149.6（13 个）；未达到每场景 30 个有效样本 | 样本内 p95 达标，正式验收未完成 |
| Track → first CurrentWord | 立即 eligible 场景 p95 ≤500，并按前奏/无逐字等原因分类 | NEXT 只有 2 个首字样本，p95 606.6；PREVIOUS 聚合 11 个，p95 1912，仍需分离等待分类；最终 NEXT 无 eligible 样本 | 未通过；覆盖与可比分类不足，不用稳态逐字数据替代 |
| Track → Preview | p95 ≤800 | NEXT 30 次 1338.5；PREVIOUS 缓存路径 92.2；最终 NEXT 5 次 2643.2 | 冷路径 FAIL |
| Track → HQ | p95 ≤2500 | NEXT 30 次 3600.5；PREVIOUS 缓存路径 92.2；最终 NEXT 2927.3（3 个，另缺 2 个） | 冷路径 FAIL，补测另有覆盖缺口 |
| 两小时稳定性 | 0 crash / hard reconnect / L2CAP failure | 历史 120 分钟轮次约第 97 分钟出现 4 次 L2CAP write failure、1 次 Sony 系统 Bluetooth HCI timeout/process death；约 4.4 秒恢复 | FAIL；PlayerAgent 自身无 crash/ANR 不抵消系统蓝牙失败 |
| 自然下一首跨端 SLO | 可信时钟和完整逐次 Trace | 120 分钟有 30 次真实自然转换；恢复的 iOS Trace 仅 7 个完整 UI Handoff，且蓝牙重启后时钟不可信 | NOT APPLICABLE，需重测，不能宣称自然场景达标 |
| Sony lint | 不增加历史问题；历史债务单独保留 | 20 errors / 33 warnings，未增加、未 suppress | lint 仍 FAIL（历史基线）；“无新增”不等于 lint 全绿 |

专用逐字 QRC 的 90 秒窗口记录了 27 条 iOS accepted，日志派生 latency p95=114、未观察到 stale/main stall。该窗口是稳态、可能节流的日志证据，不是切歌首字完整 SLO 证明。

## 明确延期或不适用的能力

- Android Controller 与双控制器混合 capability 真机矩阵：SKIPPED（当前范围）。CI 中 Controller 单测和构建通过不能替代真机并发验证。
- 跨端预测预取、候选封面预热、iOS PredictiveMediaCache：SKIPPED。239 次转换的预测来源审计没有发现可用 MediaSession queue/activeQueueItemId，高置信候选覆盖率为 0。
- Predictive Warm Path 的 ≤150/250/350/300ms 目标：NOT APPLICABLE。保留本地事件化索引预热、精确歌词缓存校验和 FullLyrics skip，不通过猜歌补齐功能。
- 完整二进制控制协议、安全配对/受信任设备、BOOT_COMPLETED：后续独立范围，不随这次主干集成宣称完成。

## 已通过的检查及边界

- `8c55cd4` 的 [GitHub Actions](https://github.com/a3384379/MusicBleController/actions/runs/33938041361)：iOS simulator XCTest、Sony 和 Android Controller 单测/Debug 构建均 PASS。
- 最终 Sony APK 已覆盖安装；Sony 本地单测、Debug 构建 PASS。两端 quick smoke 均 PASS：Sony Required 8/8、Optional 5 PASS / 0 WARN / 2 SKIPPED；iOS Required 6/6、Optional 4 PASS / 4 WARN / 1 SKIPPED。
- quick 脚本跳过构建/安装；Sony 构建/安装另行完成。本批没有重新安装 iOS 或执行 iOS 全量 smoke。iOS CurrentWord 本轮 quick 为 WARN，不能写成通过。
- 100 次快速交替压力：100 个控制意图、53 次真实身份切换、39 个完整 Handoff 样本；`STALE_CONTENT=0`、`duplicate_control=0`、`malformed=0`。只用于这轮正确性验证，不等于 100 个正常性能样本或两小时稳定性通过。
- 时延报告脚本单测 29/29 PASS；`git diff --check` PASS。
- 本次合并准备不修改 BLE UUID、command/status 语义、A1/A2 Header 或应用运行路径。V4 历史上的 capability-gated 缓存校验等增量协议见 [BLE 协议](BLE_PROTOCOL.md)，不能笼统声称整个 V4 从未有协议增强。
- CI push 触发补充实际默认分支 `master`，保留 `main`、`codex/**` 和 PR 触发。源分支 CI、PR 集成结果与合并后的 master CI 应分别核对，不用源分支绿色冒充合并提交已通过。
- 当前工作区的 4 个 `tools/deploy/` 未提交文件不在本次合并内容中；不会自动提交、清理或覆盖。

## 后续验收清单

- [ ] 分离 command dispatch → MediaSession metadata 的播放器等待与本地回调排队，复测 NEXT/PREVIOUS 正常场景。
- [ ] 拆解封面请求/准备/编码前等待与 BLE 正式传输，修复冷 Preview/HQ 长尾；保持精确身份与 generation 栅栏。
- [ ] 使用本地确有逐字 QRC 的测试集，补齐各场景 30 个有效首字/eligible 样本；独立记录前奏、无 QRC、无逐字与无当前行。
- [ ] 定位 Sony 系统蓝牙栈故障，并用可持续保存、时钟可信的完整 Trace 重新完成两小时自然播放验收。
- [ ] 在后续明确恢复双端优先级时完成 Android Controller/双控制器真实兼容矩阵。
- [ ] 单独清理 Sony 历史 lint 债务，不通过整体 suppress 隐藏。

上述项只在有对应证据时关闭，不因合并到 `master` 自动关闭。

## 详细记录

- [第一阶段产品 UI](PRODUCT_UI_V2_IMPLEMENTATION.md)
- [第二阶段 Trace 与 SLO](REALTIME_SLO_V4.md)
- [第三阶段预测来源与缓存](PREDICTIVE_MEDIA_ENGINE_V4.md)
- [第四阶段完整测试与长尾记录](COLD_PATH_HANDOFF_V4.md)

真实歌曲名、原始日志和个人设备标识只留在本机测试产物中，不纳入本次文档。
