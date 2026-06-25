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
    val metrics: RuntimeCacheMetrics
)

object CurrentTrackRuntimeCache {
    private val lock = Any()

    private var current: CurrentTrackSnapshot? = null
    private var cacheHit = 0L
    private var cacheMiss = 0L
    private var refreshCount = 0L
    private var lastRefreshCostMs = 0L
    private var lastTrackSwitchCostMs = 0L

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
                metrics = metricsLocked()
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
        return lines.asSequence()
            .flatMap { it.words.asSequence() }
            .lastOrNull { word ->
                positionMs >= word.startMs &&
                    (word.durationMs <= 0L || positionMs < word.startMs + word.durationMs)
            }
    }

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
