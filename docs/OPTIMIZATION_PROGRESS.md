# 审计优化进度与剩余任务

更新日期：2026-11-07。本清单按全面审计后的七批优化方案跟踪实现、自动化验证和实机验收。原审计基线为 `85cee488`；本轮开始时为 `b5b6188`。代码已修复与真实设备已验收分别记录。

## 七批交付状态

| 批次 | 当前状态 | 尚需完成 |
|---|---|---|
| 1 Android 历史同步、分页、清理竞态 | `2570913` 已实现，44 项 JVM、48 项设备端测试记录见[历史架构](ANDROID_HISTORY_ARCHITECTURE.md) | Sony BLE 分包、断线恢复和双控制器实机验收 |
| 2 Sony 统计口径和查询 | `1ae52b5` 后端已实现，最多五次读表查询；21 项 Room 测试和大历史模拟器基准见[统计文档](SONY_PLAYBACK_STATISTICS.md) | 两端界面口径说明、Sony 大历史性能及旧系统实机验证 |
| 3 iOS 性能模式即时生效 | 已实现；154 项 XCTest 及 iPhone quick/full smoke 通过 | 实际能耗 A/B 待实测；断线停表另列待修项 |
| 4 两端歌词阅读和交互 | 未实施本轮方案 | 长句自然展开、动态字体、主题对比；Android 移除四秒自动回跳；无障碍、触摸及浏览矩阵 |
| 5 Android 日志可靠性 | 未实施 | 文件轮转、队列容量、异常降级、清理/关闭顺序和一致导出 |
| 6 CI、回归测试和文档 | 前三次修复已补充局部回归，CI 接入仍待完成 | 两端 Android lint、Room/Compose 设备回归、歌词实际布局/交互断言及失败证据归档 |
| 7 真机性能、稳定性与双控制器 | 尚未取得当前构建的完整验收证据 | 分段 Trace、新基线、正常切歌各 30 次、100 次压力、120 分钟连续播放、双控制器和性能模式能耗对照 |

`b5b6188` 另外修复了缺权限 BLE 调试恢复崩溃及 `--no-debug-control` 遗漏，验证见[smoke 指南](SMOKE_TEST_GUIDE.md#缺权限调试恢复修复验证2026-11-06-至-2026-11-07)。它不替代七批方案中仍需实机完成的项目。

## 当前可复现的剩余项

### 统计说明和歌词阅读

- [iOS PlaybackHistoryView](../IOSBleFeasibility/IOSBleFeasibility/PlaybackHistoryView.swift) 与 [Android StatsOverview](../ControllerApp/src/main/java/com/example/controllerapp/ui/ControllerAppUi.kt) 仍仅显示统计值。补充有效播放门槛、已结束会话分母、进行中会话及范围按开始时间归属的简短说明；不新增协议字段，也不再次修改已通过测试的公式。
- [FullLyricsView](../IOSBleFeasibility/IOSBleFeasibility/FullLyricsView.swift) 的完整歌词原文及翻译仍有限制三行的路径，字体仍需按动态字号验证。iOS 页面深色背景与分段选择器需在系统浅色、深色下分别核对。
- [Android FullLyricsScreen](../ControllerApp/src/main/java/com/example/controllerapp/ui/ControllerAppUi.kt) 仍在浏览后等待四秒恢复跟随；原文与辅助行存在三行省略。改为用户点击返回当前或切歌才恢复，保留单次 seek、防误触和稳定歌词 ID。
- Android 深色主题已经存在；本批次应补对比度和布局验收，不重复建设主题。Android BLE JSON 也已在专用媒体线程解析，不列为主线程解析缺陷。

歌词验收包括小屏、横屏、默认/大字/无障碍字号、原文/翻译/罗马音/全部模式、长中文/英文/混合文本，以及拖动、惯性、停留超过四秒、返回当前和切歌。截图之外还应断言完整文本可访问、控件命中和实际交互结果。

### Android 日志

[ControllerLogStore](../ControllerApp/src/main/java/com/example/controllerapp/data/ControllerLogStore.kt) 仍持续追加文件；待写队列和 Handler 任务没有容量上限，FileWriter、append、flush 及监听器异常缺少隔离。

按原方案先建立内部预算：单文件 4MB、最多四个文件、待写 512KB，作为可调整工程配置。入队前限额，单一后台 drain 统一写入、轮转、清理、关闭和导出；错误与关键状态优先保留，丢弃普通日志应汇总。磁盘异常不能递归写日志或无界重试。

验证磁盘不可写、目录异常、突发高频、写入恢复、clear/close 并发和重复关闭，检查容量与线程退出。

### CI 和回归覆盖

[build.yml](../.github/workflows/build.yml) 的 Android 作业仍主要运行 JVM 测试和 assembleDebug，尚未接入两模块 lintDebug 与模拟器设备端回归。历史 Room、分页和 Compose 回归已在本地建立，不应把“测试存在”写成“CI 已保护”。

原 Android UI 冒烟的旧标题断言已经在历史批次修复；后续工作是稳定标识和真实布局/触摸覆盖。iOS 仅检查截图尺寸的测试不能证明没有截字、低对比或无效命中。CI 应保留失败截图、测试报告和首轮业务失败，不能用启动重试覆盖业务断言。

### 显示时钟断线行为

本轮另确认既有差距：[PlaybackClockPolicy](../IOSBleFeasibility/IOSBleFeasibility/BLEObservableStateModels.swift) 的运行条件只包含播放、有效时长和 active 生命周期；断线清理没有统一停止显示计时器。若保留的播放状态仍为 playing，显示位置可能继续推进。

本轮第3批只修模式、低电状态和原生命周期条件，不新增连接状态判定。断线停表及重连后只用新权威锚点恢复，作为独立的显示时钟生命周期修复保留，不因本轮模式切换通过而关闭。

## 本轮第3批的完成条件

| 状态 | 显示进度间隔 |
|---|---:|
| 前台播放、流畅 | 100ms |
| 前台播放、自动、正常电量 | 250ms |
| 前台播放、自动、系统低电 | 500ms |
| 前台播放、省电 | 500ms |
| 暂停、inactive/background、无有效时长 | 不运行显示计时器 |

模式和系统低电状态变更立即应用；相同有效间隔不重建调度。重调度保留当前播放锚点与单调时间，旧回调失效，seek 编辑不被覆盖。歌词插值和频谱消费同一可观察低电状态。对象释放应取消调度及订阅。

验证真实偏好 publisher 的新值、低电通知、后台期间改模式及回前台刷新、暂停/无时长、连续进度、seek、旧回调和析构；补 Foundation Timer 的实际回调及取消测试。刷新次数降低不直接换算为耗电节省，真机 A/B 仍留在第7批。

## 第3批验证记录

2026-11-07 在 iOS 18.3 模拟器执行全量 154 项 XCTest，全部通过，无跳过；包括新增的七项行为回归及六种模式/电量组合的策略测试。修复前探针单独复现连续播放中省电仍为 100ms；永久回归改用可控制调度器和单调时钟，不通过反射读取私有 Timer。

首轮目标测试的零时长显示值与即时析构断言失败已保留：前者调整为本批要求的停表断言，后者等待既有 Live Activity 异步工作排空后，仍严格检查对象释放、取消和无后续调度。调整后的两项定向测试和全部 XCTest 通过，没有修改既有播放数据语义。

iPhone 完整 smoke 首次因本机缺开发 profile 失败；现有账号恢复主 App/扩展签名后，构建和安装成功，但第二轮仍因设备安全策略拒绝启动而为 Required 4/6。本机授权环境的代码签名核验通过，profile 当前有效且包含这台设备；这项检查不替代 iPhone 的开发者信任和启动授权。用户确认已信任且可手动打开 App 后，当前构建重新运行 full 为 Required 6/6，随后同构建 quick 也为 Required 6/6；两轮均为整体 PASS、Optional 4 PASS / 4 WARN / 1 SKIPPED / 0 FAIL。原失败报告保留，不计入通过轮次。

full 实际执行构建、安装、启动、日志、偏好持久化和文件检查；quick 跳过构建安装，检查同一已安装版本。可选 BLE/媒体检查分析已有日志，不将它们当作本轮 Sony 实时链路、低电量开关或能耗验收。

完整清单位于本次工作机 `/tmp/musicble-batch3-20261107/verification.json`；目录为临时证据，不是长期归档。本批不修改协议、数据库、UserDefaults 键或 Android 业务；用户原有本地化改动保持原样。

## 实机验收和明确延期项

[V4 验收记录](V4_ACCEPTANCE_STATUS.md) 的九月 SLO、封面冷加载和稳定性数据是历史证据。当前版本需重新采集，不能用本地单测或旧样本关闭未通过项。Sony 20 个 lint error 已在后续修复；最新记录为 0 error、30 warning，剩余是 warning 治理和 CI lint 接入。

实机阶段统一原方案 300ms 正式目标与旧文档 350ms 对照口径，区分手动用户意图与自然切歌身份确认两个 T0。跨端时钟不可信时，只报两端内部耗时；源头无歌词、无逐字、前奏及 QQMusic 缓存等待单独分类。保存全部尝试、缺样本原因和两小时完整日志。

预测跨端暖路径因没有可靠下一首来源仍不适用；安全配对、BOOT_COMPLETED 和完整二进制协议维持独立延期范围，不纳入本轮开发。

## 后续顺序和交付边界

第3批验证完成后，继续第4批歌词阅读，同时补齐两端统计说明；随后处理日志和 CI。显示时钟断线差距应在后续时钟生命周期修复中单独回归。真机 SLO、能耗和双控制器验收按第7批采集新证据。

所有批次保留 Sony 权威数据源、BLE UUID/命令/报文、歌曲身份与 generation/transferId 栅栏、缓存和后台执行边界。每批独立记录代码、测试、smoke、实机未验项；保护用户已有的本地化修改。
