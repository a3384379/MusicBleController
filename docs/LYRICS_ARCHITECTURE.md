# 歌词架构

本文记录 QRC 原文、逐字、翻译、罗马音、懒加载恢复和 iOS 显示链路。

## 模块职责

- Sony `PlaybackStateReader`：读取 MediaSession，调用 `LyricManager` 获取当前行，返回 `playbackState.lyric` 和轻量诊断字段。
- Sony `LyricManager`：当前歌曲歌词状态机、单线程异步加载、retryable failure、lazy wait、Recovery Engine。
- Sony `QrcLyricManager`：扫描 QQMusic/qrc group、解密 QRC、解析逐字、翻译 translrc、罗马音 romaqrc，对齐时间轴。
- Sony `QrcLyricCacheManager`：L1/L2 cache、fuzzy、alias、negative、group cache fingerprint、V3 有效性。
- Sony `QrcDirectoryWatcher` / `QrcIncrementalPrebuildManager`：监听 QQMusic 文件后到并增量解析。
- Sony `LyricRecoveryEngine`：当前歌曲无歌词后短期恢复窗口，处理 QQ音乐懒加载歌词缓存。
- iOS `BLETestManager`：接收 `playbackState.lyric`、`fullLyrics*`、`lyricSecondary*`、歌词诊断。
- iOS `FullLyricsView`：显示原文、翻译、罗马音，逐字高亮只作用于原文。

## 核心文件

- Sony 当前状态：[PlaybackStateReader.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/PlaybackStateReader.kt)
- Sony 状态机：[LyricManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/LyricManager.kt)
- Sony QRC：[QrcLyricManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcLyricManager.kt)
- Sony 模型：[QrcLyricModels.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcLyricModels.kt)
- Sony cache：[QrcLyricCacheManager.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcLyricCacheManager.kt)
- Sony watcher：[QrcDirectoryWatcher.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/QrcDirectoryWatcher.kt)
- Sony recovery：[LyricRecoveryEngine.kt](/Volumes/雷电/project/MusicBleController/PlayerAgentApp/src/main/java/com/example/playeragent/media/LyricRecoveryEngine.kt)
- iOS full lyrics：[FullLyricsView.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/FullLyricsView.swift)
- iOS 诊断：[LyricDiagnostic.swift](/Volumes/雷电/project/MusicBleController/IOSBleFeasibility/IOSBleFeasibility/LyricDiagnostic.swift)

## 数据流

1. iOS 或 AutoPush 请求播放状态。
2. Sony `PlaybackStateReader.readPlaybackState()` 选中当前 MediaController。
3. `LyricManager.requestLyricLoadAsync()` 根据 title/artist/album 调度异步加载。
4. `LyricManager.getCurrentLine(position)` 快速返回当前缓存行；不在快速路径同步解密/重建。
5. QRC 加载顺序大致为 L1/L2 exact、alias、fuzzy、negative/cooldown、QRC fallback。
6. 成功后 `playbackState.lyric` 立即可用；iOS 再按需 `GET_FULL_LYRICS`。
7. `GET_FULL_LYRICS` 只发原文、时间、可选 words。
8. 翻译/罗马音通过 `GET_LYRIC_SECONDARY mode=translation|romanization` 独立分片发送。

## 关键状态

- `QrcLyricLine`：`timeMs`、`durationMs`、`text`、`words`、`translation`、`romanization`。
- `ParsedLyric`：包含 `schemaVersion`、`wordTimingStatus`、`groupFingerprint`、`cacheBuildVersion=3`、translation/romanization parse failed 标记。
- `QrcGroupFingerprint`：记录 qrc/producer/ex/translrc/romaqrc 的 lastModified、size 和存在性。
- `LyricRecoveryState`：`WAITING_QQMUSIC_CACHE`、`WATCHING_RECENT_QRC`、`RETRY_SCHEDULED`、`RETRYING`、`RESOLVED`、`EXPIRED` 等。
- iOS `LyricLine`：解析 fullLyrics 和 secondary 后合并，secondary 会清洗 `//`、`/`、`暂无翻译` 等占位。

## 不允许随便修改的点

- 不要改 QRC Triple DES 解密核心算法。
- 不要在 `GET_PLAYBACK_STATE` / AutoPush 快速路径同步 rebuild QRC index 或全量扫描。
- 不要只凭 artist 匹配新 QRC 到当前歌曲。
- 不要把 translation/romanization 塞回基础 `fullLyricsChunk` 导致 MTU 裁剪。
- 不要让 Live Activity 显示翻译/罗马音或携带完整歌词。
- 不要删除 negative/cooldown/alias 策略，除非有日志证明它们错误。

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
