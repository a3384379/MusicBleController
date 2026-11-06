# 歌词架构

本文记录 QRC 原文、逐字、翻译、罗马音、懒加载恢复和 iOS 显示链路。

iOS 紧凑态歌词与画中画悬浮歌词使用两项独立、默认关闭的设置，并消费既有权威播放/歌词状态的薄投影。
实际接入文件、资源生命周期、26 项验收与尚未通过的真机验证见 [LYRICS_DISPLAY_IMPLEMENTATION.md](LYRICS_DISPLAY_IMPLEMENTATION.md)。

## 模块职责

- Sony `PlaybackStateReader`：读取 MediaSession，调用 `LyricManager` 获取当前行，返回 `playbackState.lyric` 和轻量诊断字段。
- Sony `LyricManager`：当前歌曲歌词状态机、单线程异步加载、retryable failure、lazy wait、Recovery Engine。
- Sony `QrcLyricManager`：扫描 QQMusic/qrc group、解密 QRC、解析逐字、翻译 translrc、罗马音 romaqrc，对齐时间轴。
- Sony `QrcLyricCacheManager`：L1/L2 cache、版本化 parsed index、title+artist 安全直达、fuzzy、alias、negative、group cache fingerprint。
- Sony `QrcDirectoryWatcher` / `QrcIncrementalPrebuildManager`：监听 QQMusic 文件后到并增量解析。
- Sony `LyricRecoveryEngine`：当前歌曲无歌词后短期恢复窗口，处理 QQ音乐懒加载歌词缓存。
- iOS `BLETestManager`：接收 `lyricWindow*`、legacy `fullLyrics*`、A2 zlib full lyrics、`lyricSecondary*` 和歌词诊断。
- Sony `LyricsTransferCoordinator` / `CompressedLyricsCache`：独立持有歌词重传与最多 16 项/512KB 的 zlib 正文和 CRC，不受封面生命周期影响。
- Sony `PredictiveMediaCoordinator`：最多两个候选的有界 Hot Set；只在低优先级线程验证现有 QRC parsed cache 和 fingerprint，不复制第二份完整歌词。
- iOS `FullLyricsView`：显示原文、翻译、罗马音，逐字高亮只作用于原文。
  - 完整歌词不限制原文、翻译或罗马音行数，字体随 Dynamic Type 缩放。当前行与普通行沿用同一排版尺寸，播放换句仅改变高亮，避免阅读中改变行高。紧凑高度及无障碍字号使用可滚动页面，并为歌词视口单独保留高度。
  - 曲目身份只使用当前文档的 trackId，同曲窗口扩展、翻译和罗马音到达不重置浏览模式。数组位置用于当前行和逐字计算，稳定 line.id 用于行、滚动锚和几何缓存；窗口从非零行号开始时两者不能混用。
  - `LyricsStore.documentRevision` 仅在文档确实变化时递增，不进入协议或持久化。阅读恢复按修订保留几何快照，优先以第一条完整可见行作为锚点，避免顶部半截行补齐辅助歌词后变高而推动阅读点；没有完整可见行时，仍保留覆盖视口中心的长行。先实例化稳定目标行，确认新布局行序及可见性后使用同一原生滚动状态恢复屏幕位置；旧修订或用户新动作不能恢复旧位置。
  - 歌词行不显示重复的播放图标；点击文字仍通过既有 `seekToLyricLine` 跳转。
  - 用户拖动或惯性滚动进入浏览模式，不发送跳转；停止惯性的触摸及滚动结束后的短暂释放窗口同样禁止误触跳转。自动滚动不进入浏览模式。
  - 浏览时按可见歌词整行的实际布局定位中央那一句，显示其原始 `timeMs` 起点；翻译/罗马音和长句属于同一行，几何缓存按歌词身份隔离，不使用当前播放时间冒充浏览时间。
  - 定位时间仅在定位句显示，并使用浅色文字与左侧细线标明归属；普通字号预留右侧同宽时间栏，无障碍字号改为每行上方等高保留区，避免时间出现时歌词换行或定位抖动。几何偏移缓存不发布每个像素的状态，只在定位行变化时刷新时间。
  - “回到当前歌词”位于歌词列表下方、底部控制上方的独立固定高度区域；跟随时保留区域，避免按钮出现时歌词重新排版。
- Android `FullLyricsContent`：由现有 Controller 状态驱动完整歌词页，原文和辅助行自然换行；浏览保持到主动返回当前或切歌，不设置四秒恢复期限。选中态和可见项使用稳定行号，长行定位使用视口中心到整行边界的距离。停稳后继续按最终布局定位，主动点选才锁定选择。短屏导航与模式合并、状态进入横向栏，正文不被提示覆盖。拖动及停止惯性的触摸受独立释放保护，普通两次选择/确认仍通过原 seek 动作提交。

## 核心文件

- Sony 当前状态：[PlaybackStateReader.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/PlaybackStateReader.kt)
- Sony 状态机：[LyricManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/LyricManager.kt)
- Sony QRC：[QrcLyricManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcLyricManager.kt)
- Sony 模型：[QrcLyricModels.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcLyricModels.kt)
- Sony cache：[QrcLyricCacheManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcLyricCacheManager.kt)
- Sony parsed index：[QrcParsedCacheIndexStore.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcParsedCacheIndexStore.kt)
- Sony watcher：[QrcDirectoryWatcher.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcDirectoryWatcher.kt)
- Sony recovery：[LyricRecoveryEngine.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/LyricRecoveryEngine.kt)
- iOS full lyrics：[FullLyricsView.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/FullLyricsView.swift)
- iOS 诊断：[LyricDiagnostic.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/LyricDiagnostic.swift)
- Sony 压缩热缓存：[CompressedLyricsCache.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/ble/CompressedLyricsCache.kt)
- Sony 歌词传输协调器：[MediaTransferCoordinators.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/ble/MediaTransferCoordinators.kt)

## 数据流

1. iOS 或 AutoPush 请求播放状态。
2. Sony `PlaybackStateReader.readPlaybackState()` 选中当前 MediaController。
3. `LyricManager.requestLyricLoadAsync()` 根据 title/artist/album 调度异步加载。
4. `LyricManager.getCurrentLine(position)` 快速返回当前缓存行；不在快速路径同步解密/重建。
5. QRC 前台顺序为 L1、alias、exact L2、title+artist 唯一安全直达、内存索引 fuzzy；不再为当前歌曲 `listFiles` 或解析所有缓存 JSON。
6. TrackInfo 后 iOS 优先请求最多 5 行的 `GET_LYRIC_WINDOW`，先构建可见 UI；完整歌词后台请求。
7. V2 完整歌词为 zlib JSON + A2 binary + CRC32；协商/大小失败自动回退 legacy 逐行协议。
   - `RETRY_TRANSFER` 使用歌词传输自己的 `trackId + generation + transferId`，与封面是否存在、是否完成无关。
   - 重传结果仍受当前 generation 栅栏保护，旧歌曲不得覆盖新歌。
8. 翻译/罗马音继续通过 `GET_LYRIC_SECONDARY mode=translation|romanization` 独立分片发送。iOS 分别维护开始、分包空闲和总传输超时；临时错误或缺包只重试一次，明确 unavailable 不重试，切歌/断线会取消旧连接代次的请求。
9. QRC 就绪回调会立即恢复 currentWord 边界调度；暂停时无周期任务，seek/恢复/切歌重新计算，最长 500ms 漂移校正。
10. iOS 主界面显示等待 QQ QRC、歌词窗口、完整歌词、完成或明确失败；“重试歌词”只清理当前歌曲 cooldown/失败状态，并用 `GET_FULL_LYRICS forceRefresh=true` 触发兼容刷新。
11. V4 预测候选只有在真实 Track Identity、稳定 mediaId/trackId 和 QRC fingerprint 全部复验后才能晋升；WEAK 历史候选永不晋升，失败立即走步骤 3 的冷路径。
12. `mediaCacheValidationV1` 启用时，iOS cache v3 分别保存 Sony QRC 远端 fingerprint 与 iOS 实际落盘正文的 local fingerprint，并附带 schema/主歌词、翻译和罗马音行数。远端 fingerprint 用于 not-modified 协商，local fingerprint 用于精确检测本地损坏；二者不能互相替代。精确命中返回 `fullLyricsNotModified`，不重复发送 FullLyrics；任一字段或正文不一致、cache 损坏或旧 cache v1/v2 都回退一次完整传输并迁移。新 descriptor 只随完整正文发布落盘，不能附着到旧正文。

## 关键状态

- `QrcLyricLine`：`timeMs`、`durationMs`、`text`、`words`、`translation`、`romanization`。
- `ParsedLyric`：包含 `schemaVersion`、`wordTimingStatus`、`groupFingerprint`、`cacheBuildVersion=3`、translation/romanization parse failed 标记。
- `QrcGroupFingerprint`：记录 qrc/producer/ex/translrc/romaqrc 的 lastModified、size 和存在性。
- `QrcParsedCacheIndex.json`：只保存 songKey、标准化 metadata、groupId、文件名、行数、时间和 fingerprint；500ms 防抖、临时文件原子替换。
- `LyricRecoveryState`：`WAITING_QQMUSIC_CACHE`、`WATCHING_RECENT_QRC`、`RETRY_SCHEDULED`、`RETRYING`、`RESOLVED`、`EXPIRED` 等。
- iOS `LyricLine`：解析 fullLyrics 和 secondary 后合并，secondary 会清洗 `//`、`/`、`暂无翻译` 等占位。
- iOS `FullLyricsCacheStore`：按精确 trackId 并复核标准化 title+artist，最多 80 首/8MB/30 天；缓存命中只做即时展示，仍请求 Sony 复验，远端主歌词刷新时保留已有翻译/罗马音直到新结果到达。
- 压缩缓存 key 包含 songKey、fingerprint、generation、格式和逐字行集合；翻译/罗马音、fingerprint、generation 改变时不复用旧正文。
- `PredictiveHotSet` 最多两个 metadata entry、TTL 2 分钟；大数据继续引用 QRC parsed cache 和 `CompressedLyricsCache`，没有第二份大缓存。

## 不允许随便修改的点

- 不要改 QRC Triple DES 解密核心算法。
- 不要在 `GET_PLAYBACK_STATE` / AutoPush 快速路径同步 rebuild QRC index 或全量扫描。
- 不要只凭 artist 匹配新 QRC 到当前歌曲。
- title+artist 直达仅在两者都匹配且候选唯一时使用；多候选必须再由专辑唯一化，否则回到完整 fuzzy 评分。
- 不要把 translation/romanization 塞回基础 `fullLyricsChunk` 导致 MTU 裁剪。
- 不要让 Live Activity 显示翻译/罗马音或携带完整歌词。
- 不要删除 negative/cooldown/alias 策略，除非有日志证明它们错误。
- 不要让歌词重传读取或比较 `currentAlbumArtId`。

## 常见问题排查入口

- Sony 无当前行：`[PlaybackState] lyric=`、`[Lyric]`、`[LyricAsync]`、`[LyricRetry]`。
- QQ音乐懒加载：`[LyricLazyLoad]`、`[LyricRecovery]`、`[QrcGeneration] changed`。
- cache 有效性：`[QrcCache] group invalid`、`fingerprint changed`、`translation missing`、`romanization missing`。
- 翻译/罗马音传输：Sony `[LyricSecondary]`，iOS `[Lyrics-iOS] secondary ...`。
- iOS UI：`FullLyricsView` 模式和 `PreferencesStore.lyricDisplayMode`。

## 修改后必须跑哪些 smoke test

- iOS 歌词 UI/接收：quick smoke。
- iOS UserDefaults 歌词显示模式/偏移：full smoke。
- Sony QRC/Recovery/Cache：Android build `./gradlew :PlayerAgentApp:assembleDebug`，真机播放有/无歌词、懒加载、翻译/罗马音歌曲。
- docs-only：`git diff --check`。

## 歌词阅读回归

iOS 的 `PerformanceStabilityTests` 验证长行实际布局、无障碍字号视口、命中和非零窗口行号；`LyricsReadingUITests` 独立 scheme 使用生产 `FullLyricsView` 执行真实拖动、点按、同曲补齐、返回和切歌。固定数据入口仅在 Debug 模拟器且显式传入 `--lyrics-reading-ui-test` 时存在，不创建 BLE manager，也不写测试偏好；普通启动、真机和 Release 沿用原入口。

Android 的 `FullLyricsInteractionTest` 验证真实 Compose 布局、触摸、长行定位、非零窗口补齐和跟随恢复。两端 UI 测试的回调验证与 Sony 实际执行 seek 分别验收；模拟器和固定数据截图不能替代真实设备的屏幕阅读器、BLE 或长时间播放验收。本批执行结果和未验项见[优化进度](OPTIMIZATION_PROGRESS.md)。

独立 iOS 手势回归入口：

```bash
xcodebuild -project IOSBleFeasibility/IOSBleFeasibility.xcodeproj \
  -scheme LyricsReadingUITests \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=18.3' \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO test
```

原 `sonyMusic` 共享 scheme 保留主 App 的运行、单测、Profile 和 Archive 入口。独立 UI scheme 不参与真机签名或发布；CI 接入仍属于第6批。

V4 实时性观测使用 `trackId + generation` 关联 `lyricRequestQueued`、`lyricReady`、pending、LyricWindow、FullLyrics 和 CurrentWord 阶段。Trace 不读取歌词正文，也不改变 QRC 加载、pending flush、队列优先级或 stale fence；指标定义见 [REALTIME_SLO_V4.md](/Volumes/雷电/project/MusicBleController/docs/REALTIME_SLO_V4.md)。

## V4 Cold-Path Current Lyric 与 CurrentWord

- `LyricManager` 对 lookup requested/start、runtime/parsed cache hit/miss、load、ready 和 current line selected 输出 `handoffId/trackId/generation/positionAnchor/lineIndex/wordTimingStatus/cacheSource/failureReason`；不写歌词正文。
- `lyricsReady` 先把精确匹配的行写入 `CurrentTrackRuntimeCache`，再通过 `LyricsReadyGateSnapshot` 触发 current PlaybackState 首包。只有当前 trackId、generation、非空 current line 和订阅状态完全匹配才发送，旧 Track 不会借 fast publish 回写。
- CurrentWord eligibility 区分 `ELIGIBLE`、`INTRO_WAIT`、`LINE_ONLY/NO_WORD_TIMING`、`LYRIC_NOT_READY`、`PAUSED`、`CLOCK_UNTRUSTED` 和 `NO_ACTIVE_LINE`。前奏/逐行/无歌词样本不冒充系统延迟失败。
- CurrentWord 使用独立 scheduled executor；匹配 generation 的 `lyricsReady` 是首包事件屏障，250ms 仅作 bounded fallback。若 position 已在词内立即发送 snapshot，否则按真实下一边界调度；暂停时 suspended，seek/恢复时重算。
- generation、sequence、position 和 anchor fence 全部保留。iOS 明确记录 `TRACK_MISMATCH/GENERATION_MISMATCH/SEQUENCE_OLD/POSITION_STALE/ANCHOR_STALE/DUPLICATE`，只有 accepted 后才发布。
- 第四阶段没有修改 QRC Triple DES、parser、index schema、fuzzy、negative、alias 或 Recovery Engine。当前复测中 `lyrics ready → current line enqueue` p95 为 21.8～69.5ms，三场景均满足 100ms；Sony dispatch-next 的有效 Track → current lyric p95 为 112.4ms。NEXT/PREVIOUS 原始 p95 被 `WAITING_QQMUSIC_CACHE/NO_QRC_FILE` 样本拉到约 3.8～3.9 秒，报告已按原因单独分类，不能归因于 parser 或首包 enqueue。

详细状态机、样本覆盖和未达标项见 [COLD_PATH_HANDOFF_V4.md](/Volumes/雷电/project/MusicBleController/docs/COLD_PATH_HANDOFF_V4.md)。
