package com.example.playeragent.media

import android.os.SystemClock
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
    val lineIndex: Int,
    val wordIndex: Int,
    val wordText: String,
    val wordStartMs: Long,
    val wordEndMs: Long,
    val hasWordTiming: Boolean,
    val positionMs: Long,
    val timestampMs: Long,
    val version: Int = 1
) {
    val wordKey: String
        get() = "$trackId|$lineIndex|$wordIndex|$wordStartMs"
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

    fun updatePlaybackState(
        trackId: String,
        songKey: String,
        title: String,
        artist: String,
        album: String,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
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
                logger?.invoke(
                    "[RuntimeCache] track changed trackId=$trackId songKey=$songKey title=$title"
                )
                CurrentTrackSnapshot(
                    trackId = trackId,
                    songKey = songKey,
                    title = title,
                    artist = artist,
                    album = album,
                    trackChangedAtMs = System.currentTimeMillis(),
                    hasLyrics = false
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
                durationMs = durationMs,
                isPlaying = isPlaying,
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
        mutate(logger) { previous, now, _ ->
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
        mutate(logger) { previous, now, _ ->
            if (previous == null || previous.songKey != songKey) {
                previous
            } else {
                previous.copy(
                    positionMs = positionMs,
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
            } else {
                cacheHit += 1
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
            val wordState = findCurrentWordStateLocked(track, System.currentTimeMillis())
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
                lastUpdatedAtMs = track.lastUpdatedAtMs
            )
            lastSnapshot = snapshot
            PlaybackStateDiffEngine.recordSnapshotBuilt()
            return snapshot
        }
    }

    fun currentWordState(timestampMs: Long = System.currentTimeMillis()): CurrentWordState? {
        synchronized(lock) {
            val track = current ?: return null
            return findCurrentWordStateLocked(track, timestampMs)
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
                hasLyrics = false
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
        timestampMs: Long
    ): CurrentWordState? {
        if (track.trackId.isBlank() || track.lyricLines.isEmpty()) {
            return null
        }
        val elapsedMs = if (track.isPlaying && track.lastUpdatedAtMs > 0L) {
            (timestampMs - track.lastUpdatedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val rawPosition = track.positionMs + elapsedMs
        val position = if (track.durationMs > 0L) {
            rawPosition.coerceIn(0L, track.durationMs)
        } else {
            rawPosition.coerceAtLeast(0L)
        }
        val indexed = findCurrentWordIndexed(track.lyricLines, position)
            ?: findCurrentLineIndexed(track.lyricLines, position)
            ?: return null
        return CurrentWordState(
            trackId = track.trackId,
            lineIndex = indexed.lineIndex,
            wordIndex = indexed.wordIndex,
            wordText = indexed.word.text,
            wordStartMs = indexed.word.startMs,
            wordEndMs = indexed.word.startMs + indexed.word.durationMs.coerceAtLeast(0L),
            hasWordTiming = indexed.hasWordTiming,
            positionMs = position,
            timestampMs = timestampMs
        )
    }

    private fun findCurrentWordIndexed(
        lines: List<RuntimeLyricLine>,
        positionMs: Long
    ): IndexedRuntimeWord? {
        var fallback: IndexedRuntimeWord? = null
        lines.forEachIndexed { lineIndex, line ->
            line.words.forEachIndexed { wordIndex, word ->
                if (positionMs >= word.startMs) {
                    val indexed = IndexedRuntimeWord(lineIndex, wordIndex, word)
                    fallback = indexed
                    if (word.durationMs <= 0L || positionMs < word.startMs + word.durationMs) {
                        return indexed
                    }
                }
            }
        }
        return fallback
    }

    private fun findCurrentLineIndexed(
        lines: List<RuntimeLyricLine>,
        positionMs: Long
    ): IndexedRuntimeWord? {
        var fallback: IndexedRuntimeWord? = null
        lines.forEachIndexed { lineIndex, line ->
            if (line.text.isBlank()) {
                return@forEachIndexed
            }
            if (positionMs >= line.timeMs) {
                val lineEndMs = when {
                    line.durationMs > 0L -> line.timeMs + line.durationMs
                    lineIndex + 1 < lines.size -> lines[lineIndex + 1].timeMs
                    else -> line.timeMs
                }
                val indexed = IndexedRuntimeWord(
                    lineIndex = lineIndex,
                    wordIndex = -1,
                    word = RuntimeLyricWord(
                        startMs = line.timeMs,
                        durationMs = (lineEndMs - line.timeMs).coerceAtLeast(0L),
                        text = line.text
                    ),
                    hasWordTiming = false
                )
                fallback = indexed
                if (positionMs < lineEndMs) {
                    return indexed
                }
            }
        }
        return fallback
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
