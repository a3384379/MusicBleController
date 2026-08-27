package com.example.playeragent.media

import android.os.SystemClock
import com.example.playeragent.diagnostics.RealtimeTrace
import com.example.playeragent.logging.LogConfig
import org.json.JSONObject

data class RuntimeLyricWord(
    val startMs: Long,
    val durationMs: Long,
    val text: String
)

data class RuntimeLyricLine(
    val timeMs: Long,
    val text: String,
    val durationMs: Long = 0L,
    val words: List<RuntimeLyricWord> = emptyList(),
    val translation: String? = null,
    val romanization: String? = null
)

data class RuntimeCacheMetrics(
    val cacheHit: Long = 0L,
    val cacheMiss: Long = 0L,
    val refreshCount: Long = 0L,
    val lastRefreshCostMs: Long = 0L,
    val lastTrackSwitchCostMs: Long = 0L
)

data class CurrentTrackRuntimeCacheSnapshot(
    val track: CurrentTrackSnapshot?,
    val metrics: RuntimeCacheMetrics,
    val lastSnapshot: PlaybackStateSnapshot? = null,
    val lastSentSnapshot: PlaybackStateSnapshot? = null,
    val playbackDiffMetrics: PlaybackDiffMetrics = PlaybackDiffMetrics()
)

data class CurrentWordState(
    val trackId: String,
    val trackGeneration: Long,
    val lineIndex: Int,
    val wordIndex: Int,
    val wordText: String,
    val wordStartMs: Long,
    val wordEndMs: Long,
    val hasWordTiming: Boolean,
    val positionMs: Long,
    val timestampMs: Long,
    val sampleElapsedMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val version: Int = 2
) {
    val wordKey: String
        get() = "$trackId|$trackGeneration|$lineIndex|$wordIndex|$wordStartMs"
}

data class CurrentWordEligibilitySnapshot(
    val eligible: Boolean,
    val reason: String,
    val trackId: String = "",
    val generation: Long = 0L,
    val positionMs: Long = 0L,
    val positionAnchorMs: Long = 0L,
    val lineIndex: Int = -1,
    val wordTimingStatus: String = "NOT_READY",
    val nextBoundaryDelayMs: Long? = null,
    val state: CurrentWordState? = null
)

internal object PlaybackPositionAnchorPolicy {
    fun projectedPositionMs(
        positionMs: Long,
        positionAnchorElapsedMs: Long,
        nowElapsedMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float
    ): Long {
        val elapsedMs = if (isPlaying && positionAnchorElapsedMs > 0L) {
            (nowElapsedMs - positionAnchorElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val speed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val projected = positionMs + (elapsedMs * speed).toLong()
        return if (durationMs > 0L) {
            projected.coerceIn(0L, durationMs)
        } else {
            projected.coerceAtLeast(0L)
        }
    }
}

object CurrentTrackRuntimeCache {
    private val lock = Any()

    private var current: CurrentTrackSnapshot? = null
    private var cacheHit = 0L
    private var cacheMiss = 0L
    private var refreshCount = 0L
    private var lastRefreshCostMs = 0L
    private var lastTrackSwitchCostMs = 0L
    private var lastSnapshot: PlaybackStateSnapshot? = null
    private var lastSentSnapshot: PlaybackStateSnapshot? = null
    private var currentTrackGeneration: Long = 0L

    fun updatePlaybackState(
        trackId: String,
        songKey: String,
        title: String,
        artist: String,
        album: String,
        positionMs: Long,
        positionSampleElapsedMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float,
        currentLine: String,
        lyricSource: String,
        lastPlaybackState: JSONObject,
        diagnosticSnapshot: String,
        logger: ((String) -> Unit)? = null
    ): CurrentTrackSnapshot {
        return mutate(logger) { previous, now, startedAt ->
            val trackChanged = previous == null ||
                previous.trackId != trackId ||
                previous.songKey != songKey
            val base = if (trackChanged) {
                currentTrackGeneration += 1
                logger?.invoke(
                    "[RuntimeCache] track changed trackId=$trackId songKey=$songKey " +
                        "title=$title generation=$currentTrackGeneration"
                )
                CurrentTrackSnapshot(
                    trackId = trackId,
                    songKey = songKey,
                    title = title,
                    artist = artist,
                    album = album,
                    trackChangedAtMs = System.currentTimeMillis(),
                    hasLyrics = false,
                    currentTrackGeneration = currentTrackGeneration
                )
            } else {
                previous!!
            }
            val updated = base.copy(
                trackId = trackId,
                songKey = songKey,
                title = title,
                artist = artist,
                album = album,
                positionMs = positionMs,
                positionAnchorElapsedMs = positionSampleElapsedMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
                currentLine = currentLine,
                currentWord = findCurrentWord(base.lyricLines, positionMs),
                lyricSource = lyricSource,
                lastPlaybackState = lastPlaybackState.toString(),
                diagnosticSnapshot = diagnosticSnapshot,
                lastUpdatedAtMs = now
            )
            if (trackChanged) {
                lastTrackSwitchCostMs = SystemClock.elapsedRealtime() - startedAt
            }
            updated
        }
    }

    fun updateLyrics(
        songKey: String,
        lines: List<LyricManager.LyricLine>,
        lyricSource: String,
        logger: ((String) -> Unit)? = null
    ) {
        val runtimeLines = lines.map { it.toRuntimeLine() }
        mutate(logger) { previous, now, _ ->
            if (previous == null || previous.songKey != songKey) {
                previous
            } else {
                logger?.invoke(
                    "[RuntimeCache] lyrics updated songKey=$songKey " +
                        "lines=${runtimeLines.size} source=$lyricSource"
                )
                previous.copy(
                    hasLyrics = runtimeLines.isNotEmpty(),
                    lyricSource = lyricSource,
                    lyricLines = runtimeLines,
                    translationLines = runtimeLines.map { it.translation },
                    romanizationLines = runtimeLines.map { it.romanization },
                    currentWord = findCurrentWord(runtimeLines, previous.positionMs),
                    lastUpdatedAtMs = now
                )
            }
        }
    }

    fun applyPredictiveLyrics(
        songKey: String,
        lines: List<LyricManager.LyricLine>,
        lyricSource: String,
        positionMs: Long,
        logger: ((String) -> Unit)? = null
    ) {
        val runtimeLines = lines.map { it.toRuntimeLine() }
        mutate(logger) { previous, now, startedAt ->
            if (previous == null || previous.songKey != songKey) {
                previous
            } else {
                val currentLine = findCurrentLineText(runtimeLines, positionMs)
                val currentWord = findCurrentWord(runtimeLines, positionMs)
                logger?.invoke(
                    "[RuntimeCache] predictive lyrics applied songKey=$songKey " +
                        "lines=${runtimeLines.size} source=$lyricSource " +
                        "positionMs=$positionMs currentLine=${currentLine.take(24)} " +
                        "hasWordTiming=${runtimeLines.any { it.words.isNotEmpty() }}"
                )
                previous.copy(
                    hasLyrics = runtimeLines.isNotEmpty(),
                    lyricSource = lyricSource,
                    lyricLines = runtimeLines,
                    translationLines = runtimeLines.map { it.translation },
                    romanizationLines = runtimeLines.map { it.romanization },
                    positionMs = positionMs,
                    positionAnchorElapsedMs = startedAt,
                    currentLine = currentLine,
                    currentWord = currentWord,
                    lastUpdatedAtMs = now
                )
            }
        }
    }

    fun updateCurrentLine(
        songKey: String,
        positionMs: Long,
        currentLine: String,
        logger: ((String) -> Unit)? = null
    ) {
        mutate(logger) { previous, now, startedAt ->
            if (previous == null || previous.songKey != songKey) {
                previous
            } else {
                previous.copy(
                    positionMs = positionMs,
                    positionAnchorElapsedMs = startedAt,
                    currentLine = currentLine,
                    currentWord = findCurrentWord(previous.lyricLines, positionMs),
                    lastUpdatedAtMs = now
                )
            }
        }
    }

    fun updateAlbumArt(
        trackId: String,
        albumArtId: String?,
        albumArtState: String,
        logger: ((String) -> Unit)? = null
    ) {
        mutate(logger) { previous, now, _ ->
            if (previous == null || previous.trackId != trackId) {
                previous
            } else {
                logger?.invoke(
                    "[RuntimeCache] albumArt updated trackId=$trackId " +
                        "albumArtId=${albumArtId.orEmpty()} state=$albumArtState"
                )
                previous.copy(
                    albumArtId = albumArtId,
                    albumArtState = albumArtState,
                    lastUpdatedAtMs = now
                )
            }
        }
    }

    fun updateRecovery(
        songKey: String,
        recoveryState: String,
        logger: ((String) -> Unit)? = null
    ) {
        mutate(logger) { previous, now, _ ->
            if (previous == null || previous.songKey != songKey) {
                previous
            } else {
                previous.copy(
                    recoveryState = recoveryState,
                    lastUpdatedAtMs = now
                )
            }
        }
    }

    fun snapshot(): CurrentTrackRuntimeCacheSnapshot {
        synchronized(lock) {
            if (current == null) {
                cacheMiss += 1
                RealtimeTrace.record(
                    stage = "runtimeCacheMiss",
                    payloadType = "currentTrack",
                    result = "miss"
                )
            } else {
                cacheHit += 1
                RealtimeTrace.record(
                    stage = "runtimeCacheHit",
                    trackId = current?.trackId,
                    generation = current?.currentTrackGeneration,
                    payloadType = "currentTrack",
                    result = "hit"
                )
            }
            return CurrentTrackRuntimeCacheSnapshot(
                track = current,
                metrics = metricsLocked(),
                lastSnapshot = lastSnapshot,
                lastSentSnapshot = lastSentSnapshot,
                playbackDiffMetrics = PlaybackStateDiffEngine.metricsSnapshot()
            )
        }
    }

    fun trackSnapshot(): CurrentTrackSnapshot? {
        return snapshot().track
    }

    fun lyricLinesSnapshot(songKey: String? = null): List<LyricManager.LyricLine> {
        val track = snapshot().track ?: return emptyList()
        if (songKey != null && track.songKey != songKey) {
            return emptyList()
        }
        return track.lyricLines.map { it.toLyricLine() }
    }

    fun metricsSnapshot(): RuntimeCacheMetrics {
        synchronized(lock) {
            return metricsLocked()
        }
    }

    fun buildPlaybackStateSnapshot(
        volume: Int? = null,
        connectionState: String = ""
    ): PlaybackStateSnapshot? {
        synchronized(lock) {
            val track = current ?: return null
            val wordState = findCurrentWordStateLocked(
                track = track,
                timestampMs = System.currentTimeMillis(),
                elapsedRealtimeMs = SystemClock.elapsedRealtime()
            )
            val snapshot = PlaybackStateSnapshot(
                trackId = track.trackId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                positionMs = track.positionMs,
                durationMs = track.durationMs,
                playing = track.isPlaying,
                albumArtId = track.albumArtId,
                currentLine = track.currentLine,
                currentLineIndex = wordState?.lineIndex ?: -1,
                currentWord = wordState?.let {
                    track.lyricLines
                        .getOrNull(it.lineIndex)
                        ?.words
                        ?.getOrNull(it.wordIndex)
                } ?: track.currentWord,
                currentWordState = wordState,
                lyricStatus = track.lyricSource,
                recoveryState = track.recoveryState,
                albumArtState = track.albumArtState,
                volume = volume,
                connectionState = connectionState,
                // Playback diffs compare position samples, so this must use the
                // monotonic position anchor rather than metadata/artwork update time.
                lastUpdatedAtMs = track.positionAnchorElapsedMs
            )
            lastSnapshot = snapshot
            PlaybackStateDiffEngine.recordSnapshotBuilt()
            return snapshot
        }
    }

    fun currentWordState(
        timestampMs: Long = System.currentTimeMillis(),
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    ): CurrentWordState? {
        synchronized(lock) {
            val track = current ?: return null
            return findCurrentWordStateLocked(track, timestampMs, elapsedRealtimeMs)
        }
    }

    fun currentWordEligibilitySnapshot(
        timestampMs: Long = System.currentTimeMillis(),
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    ): CurrentWordEligibilitySnapshot {
        synchronized(lock) {
            val track = current ?: return CurrentWordEligibilitySnapshot(
                eligible = false,
                reason = "LYRIC_NOT_READY"
            )
            val common = CurrentWordEligibilitySnapshot(
                eligible = false,
                reason = "UNKNOWN",
                trackId = track.trackId,
                generation = track.currentTrackGeneration,
                positionMs = track.positionMs,
                positionAnchorMs = track.positionAnchorElapsedMs
            )
            if (!track.isPlaying) return common.copy(reason = "PAUSED")
            if (track.positionAnchorElapsedMs <= 0L ||
                elapsedRealtimeMs < track.positionAnchorElapsedMs
            ) {
                return common.copy(reason = "CLOCK_UNTRUSTED")
            }
            if (track.lyricLines.isEmpty()) return common.copy(reason = "LYRIC_NOT_READY")
            val position = PlaybackPositionAnchorPolicy.projectedPositionMs(
                positionMs = track.positionMs,
                positionAnchorElapsedMs = track.positionAnchorElapsedMs,
                nowElapsedMs = elapsedRealtimeMs,
                durationMs = track.durationMs,
                isPlaying = track.isPlaying,
                playbackSpeed = track.playbackSpeed
            )
            val lineIndex = findLatestLineIndex(track.lyricLines, position)
            if (lineIndex < 0) {
                val firstBoundary = track.lyricLines.firstOrNull()?.let { line ->
                    line.words.firstOrNull()?.startMs ?: line.timeMs
                }
                return common.copy(
                    reason = "INTRO_WAIT",
                    positionMs = position,
                    nextBoundaryDelayMs = firstBoundary
                        ?.minus(position)
                        ?.coerceAtLeast(0L)
                )
            }
            val line = track.lyricLines[lineIndex]
            if (line.words.isEmpty()) {
                return common.copy(
                    reason = "NO_WORD_TIMING",
                    positionMs = position,
                    lineIndex = lineIndex,
                    wordTimingStatus = "LINE_ONLY"
                )
            }
            val wordIndex = findLatestWordIndex(line.words, position)
            if (wordIndex < 0) {
                return common.copy(
                    reason = "INTRO_WAIT",
                    positionMs = position,
                    lineIndex = lineIndex,
                    wordTimingStatus = "AVAILABLE",
                    nextBoundaryDelayMs = (line.words.first().startMs - position)
                        .coerceAtLeast(0L)
                )
            }
            val word = line.words[wordIndex]
            val wordEndMs = word.startMs + word.durationMs.coerceAtLeast(0L)
            if (word.durationMs > 0L && position >= wordEndMs) {
                val nextBoundary = line.words.getOrNull(wordIndex + 1)?.startMs
                    ?: track.lyricLines.getOrNull(lineIndex + 1)?.let { nextLine ->
                        nextLine.words.firstOrNull()?.startMs ?: nextLine.timeMs
                    }
                return common.copy(
                    reason = if (nextBoundary != null) "INTRO_WAIT" else "NO_ACTIVE_LINE",
                    positionMs = position,
                    lineIndex = lineIndex,
                    wordTimingStatus = "AVAILABLE",
                    nextBoundaryDelayMs = nextBoundary
                        ?.minus(position)
                        ?.coerceAtLeast(0L)
                )
            }
            val state = findCurrentWordStateLocked(track, timestampMs, elapsedRealtimeMs)
            return common.copy(
                eligible = state != null && state.hasWordTiming,
                reason = if (state != null && state.hasWordTiming) "ELIGIBLE" else "NO_WORD_TIMING",
                positionMs = position,
                lineIndex = lineIndex,
                wordTimingStatus = if (state?.hasWordTiming == true) "AVAILABLE" else "LINE_ONLY",
                state = state
            )
        }
    }

    fun traceIdentitySnapshot(): Pair<String, Long>? {
        synchronized(lock) {
            val track = current ?: return null
            return track.trackId to track.currentTrackGeneration
        }
    }

    /** Delay to the next word/line boundary, capped for periodic drift correction. */
    fun nextCurrentWordBoundaryDelayMs(
        timestampMs: Long = System.currentTimeMillis(),
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        maximumDriftCorrectionMs: Long = 500L
    ): Long? {
        synchronized(lock) {
            val track = current ?: return null
            if (!track.isPlaying || track.lyricLines.isEmpty()) return null
            val state = findCurrentWordStateLocked(
                track,
                timestampMs,
                elapsedRealtimeMs
            ) ?: return maximumDriftCorrectionMs
            val line = track.lyricLines.getOrNull(state.lineIndex)
            val candidates = mutableListOf<Long>()
            if (line != null && state.wordIndex >= 0) {
                line.words.getOrNull(state.wordIndex + 1)?.startMs?.let(candidates::add)
                if (state.wordEndMs > state.positionMs) candidates += state.wordEndMs
            }
            track.lyricLines.getOrNull(state.lineIndex + 1)?.timeMs?.let(candidates::add)
            val next = candidates.filter { it > state.positionMs }.minOrNull()
            return if (next == null) {
                maximumDriftCorrectionMs
            } else {
                (next - state.positionMs + 5L).coerceIn(20L, maximumDriftCorrectionMs)
            }
        }
    }

    fun currentGeneration(): Long {
        synchronized(lock) {
            return currentTrackGeneration
        }
    }

    fun diffFromLastSent(snapshot: PlaybackStateSnapshot): PlaybackStateDiff {
        synchronized(lock) {
            return PlaybackStateDiffEngine.diff(lastSentSnapshot, snapshot)
        }
    }

    fun markPlaybackSnapshotSent(
        snapshot: PlaybackStateSnapshot
    ) {
        synchronized(lock) {
            lastSentSnapshot = snapshot
            PlaybackStateDiffEngine.recordPush()
        }
    }

    fun markPlaybackSnapshotSkipped(diff: PlaybackStateDiff) {
        PlaybackStateDiffEngine.recordSkip(diff)
    }

    fun resetPlaybackDiffState() {
        synchronized(lock) {
            lastSnapshot = null
            lastSentSnapshot = null
        }
    }

    private fun mutate(
        logger: ((String) -> Unit)?,
        block: (CurrentTrackSnapshot?, Long, Long) -> CurrentTrackSnapshot?
    ): CurrentTrackSnapshot {
        synchronized(lock) {
            val startedAt = SystemClock.elapsedRealtime()
            val now = System.currentTimeMillis()
            val updated = block(current, now, startedAt)
            if (updated != null) {
                current = updated
            }
            lastRefreshCostMs = SystemClock.elapsedRealtime() - startedAt
            refreshCount += 1
            if (LogConfig.DEBUG_VERBOSE_LOG) {
                logger?.invoke(
                    "[RuntimeCache] snapshot refreshed hasTrack=${current != null} " +
                        "costMs=$lastRefreshCostMs refreshCount=$refreshCount"
                )
            }
            return current ?: CurrentTrackSnapshot(
                trackId = "",
                songKey = "",
                title = "",
                artist = "",
                album = "",
                trackChangedAtMs = 0L,
                hasLyrics = false,
                currentTrackGeneration = currentTrackGeneration
            )
        }
    }

    private fun metricsLocked(): RuntimeCacheMetrics {
        return RuntimeCacheMetrics(
            cacheHit = cacheHit,
            cacheMiss = cacheMiss,
            refreshCount = refreshCount,
            lastRefreshCostMs = lastRefreshCostMs,
            lastTrackSwitchCostMs = lastTrackSwitchCostMs
        )
    }

    private fun findCurrentWord(
        lines: List<RuntimeLyricLine>,
        positionMs: Long
    ): RuntimeLyricWord? {
        return findCurrentWordIndexed(lines, positionMs)?.word
    }

    private fun findCurrentLineText(
        lines: List<RuntimeLyricLine>,
        positionMs: Long
    ): String {
        val lineIndex = findCurrentLineIndexed(lines, positionMs)?.lineIndex ?: return ""
        return lines.getOrNull(lineIndex)?.text.orEmpty()
    }

    private fun findCurrentWordStateLocked(
        track: CurrentTrackSnapshot,
        timestampMs: Long,
        elapsedRealtimeMs: Long
    ): CurrentWordState? {
        if (track.trackId.isBlank() || track.lyricLines.isEmpty()) {
            return null
        }
        val position = PlaybackPositionAnchorPolicy.projectedPositionMs(
            positionMs = track.positionMs,
            positionAnchorElapsedMs = track.positionAnchorElapsedMs,
            nowElapsedMs = elapsedRealtimeMs,
            durationMs = track.durationMs,
            isPlaying = track.isPlaying,
            playbackSpeed = track.playbackSpeed
        )
        val indexed = findCurrentWordIndexed(track.lyricLines, position)
            ?: findCurrentLineIndexed(track.lyricLines, position)
            ?: return null
        return CurrentWordState(
            trackId = track.trackId,
            trackGeneration = track.currentTrackGeneration,
            lineIndex = indexed.lineIndex,
            wordIndex = indexed.wordIndex,
            wordText = indexed.word.text,
            wordStartMs = indexed.word.startMs,
            wordEndMs = indexed.word.startMs + indexed.word.durationMs.coerceAtLeast(0L),
            hasWordTiming = indexed.hasWordTiming,
            positionMs = position,
            timestampMs = timestampMs,
            sampleElapsedMs = elapsedRealtimeMs,
            playbackSpeed = track.playbackSpeed
        )
    }

    private fun findCurrentWordIndexed(
        lines: List<RuntimeLyricLine>,
        positionMs: Long
    ): IndexedRuntimeWord? {
        val currentLineIndex = findLatestLineIndex(lines, positionMs)
        if (currentLineIndex < 0) {
            return null
        }
        var fallback: IndexedRuntimeWord? = null
        for (lineIndex in currentLineIndex downTo maxOf(0, currentLineIndex - 1)) {
            val words = lines[lineIndex].words
            val wordIndex = findLatestWordIndex(words, positionMs)
            if (wordIndex < 0) {
                continue
            }
            val word = words[wordIndex]
            val indexed = IndexedRuntimeWord(lineIndex, wordIndex, word)
            fallback = fallback ?: indexed
            if (word.durationMs <= 0L || positionMs < word.startMs + word.durationMs) {
                return indexed
            }
        }
        return fallback
    }

    private fun findCurrentLineIndexed(
        lines: List<RuntimeLyricLine>,
        positionMs: Long
    ): IndexedRuntimeWord? {
        var lineIndex = findLatestLineIndex(lines, positionMs)
        while (lineIndex >= 0 && lines[lineIndex].text.isBlank()) {
            lineIndex -= 1
        }
        if (lineIndex < 0) {
            return null
        }
        val line = lines[lineIndex]
        val lineEndMs = when {
            line.durationMs > 0L -> line.timeMs + line.durationMs
            lineIndex + 1 < lines.size -> lines[lineIndex + 1].timeMs
            else -> line.timeMs
        }
        return IndexedRuntimeWord(
            lineIndex = lineIndex,
            wordIndex = -1,
            word = RuntimeLyricWord(
                startMs = line.timeMs,
                durationMs = (lineEndMs - line.timeMs).coerceAtLeast(0L),
                text = line.text
            ),
            hasWordTiming = false
        )
    }

    private fun findLatestLineIndex(
        lines: List<RuntimeLyricLine>,
        positionMs: Long
    ): Int {
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun findLatestWordIndex(
        words: List<RuntimeLyricWord>,
        positionMs: Long
    ): Int {
        var low = 0
        var high = words.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (words[mid].startMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private data class IndexedRuntimeWord(
        val lineIndex: Int,
        val wordIndex: Int,
        val word: RuntimeLyricWord,
        val hasWordTiming: Boolean = true
    )

    private fun LyricManager.LyricLine.toRuntimeLine(): RuntimeLyricLine {
        return RuntimeLyricLine(
            timeMs = timeMs,
            text = text,
            durationMs = durationMs,
            words = words.map {
                RuntimeLyricWord(
                    startMs = it.startMs,
                    durationMs = it.durationMs,
                    text = it.text
                )
            },
            translation = translation,
            romanization = romanization
        )
    }

    private fun RuntimeLyricLine.toLyricLine(): LyricManager.LyricLine {
        return LyricManager.LyricLine(
            timeMs = timeMs,
            text = text,
            durationMs = durationMs,
            words = words.map {
                QrcLyricWord(
                    startMs = it.startMs,
                    durationMs = it.durationMs,
                    text = it.text
                )
            },
            translation = translation,
            romanization = romanization
        )
    }
}
