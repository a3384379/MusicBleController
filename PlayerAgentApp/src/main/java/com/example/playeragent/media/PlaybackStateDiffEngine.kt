package com.example.playeragent.media

object PlaybackStateDiffEngine {
    private val lock = Any()

    private var snapshotBuildCount = 0L
    private var diffCount = 0L
    private var pushCount = 0L
    private var skipCount = 0L
    private var trackChangedCount = 0L
    private var wordChangedCount = 0L
    private var positionJumpCount = 0L
    private var positionSmallSkipCount = 0L
    private var identicalSkipCount = 0L

    fun recordSnapshotBuilt() {
        synchronized(lock) {
            snapshotBuildCount += 1
        }
    }

    fun diff(
        old: PlaybackStateSnapshot?,
        new: PlaybackStateSnapshot
    ): PlaybackStateDiff {
        val result = calculateDiff(old, new)
        synchronized(lock) {
            diffCount += 1
            when (result.type) {
                PlaybackStateDiffType.TrackChanged -> trackChangedCount += 1
                PlaybackStateDiffType.CurrentWordChanged -> wordChangedCount += 1
                PlaybackStateDiffType.PositionJump -> positionJumpCount += 1
                else -> Unit
            }
        }
        return result
    }

    fun recordPush() {
        synchronized(lock) {
            pushCount += 1
        }
    }

    fun recordSkip(diff: PlaybackStateDiff) {
        synchronized(lock) {
            if (diff.type == PlaybackStateDiffType.NoChange || !diff.shouldPush) {
                skipCount += 1
                when (diff.reason) {
                    "position_delta_small" -> positionSmallSkipCount += 1
                    "identical" -> identicalSkipCount += 1
                }
            }
        }
    }

    fun metricsSnapshot(): PlaybackDiffMetrics {
        synchronized(lock) {
            return PlaybackDiffMetrics(
                snapshotBuildCount = snapshotBuildCount,
                diffCount = diffCount,
                pushCount = pushCount,
                skipCount = skipCount,
                trackChangedCount = trackChangedCount,
                wordChangedCount = wordChangedCount,
                positionJumpCount = positionJumpCount,
                positionSmallSkipCount = positionSmallSkipCount,
                identicalSkipCount = identicalSkipCount
            )
        }
    }

    private fun calculateDiff(
        old: PlaybackStateSnapshot?,
        new: PlaybackStateSnapshot
    ): PlaybackStateDiff {
        if (old == null) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.TrackChanged,
                changedFields = setOf("initial"),
                reason = "initial",
                positionMs = new.positionMs,
                lineIndex = new.currentLineIndex
            )
        }

        val trackChanged = old.trackId != new.trackId ||
            old.title != new.title ||
            old.artist != new.artist ||
            old.album != new.album
        if (trackChanged) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.TrackChanged,
                changedFields = changedIdentityFields(old, new),
                reason = "track_changed",
                lastPositionMs = old.positionMs,
                positionMs = new.positionMs,
                lastLineIndex = old.currentLineIndex,
                lineIndex = new.currentLineIndex,
                currentWordKeyChanged = old.currentWordKey != new.currentWordKey
            )
        }

        val playbackChanged = old.playing != new.playing ||
            old.durationMs != new.durationMs
        if (playbackChanged) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.PlaybackChanged,
                changedFields = setOf("playing", "duration").filterTo(mutableSetOf()) {
                    when (it) {
                        "playing" -> old.playing != new.playing
                        "duration" -> old.durationMs != new.durationMs
                        else -> false
                    }
                },
                reason = if (old.playing != new.playing) "playing_changed" else "duration_changed",
                lastPositionMs = old.positionMs,
                positionMs = new.positionMs,
                lastLineIndex = old.currentLineIndex,
                lineIndex = new.currentLineIndex,
                currentWordKeyChanged = old.currentWordKey != new.currentWordKey
            )
        }

        val positionDelta = kotlin.math.abs(new.positionMs - old.positionMs)
        val expectedPositionDelta = if (old.playing && new.playing) {
            (new.lastUpdatedAtMs - old.lastUpdatedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val positionDrift = kotlin.math.abs(positionDelta - expectedPositionDelta)
        if (positionDrift > POSITION_JUMP_THRESHOLD_MS) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.PositionJump,
                changedFields = setOf("position"),
                positionDeltaMs = positionDrift,
                reason = "position_jump",
                lastPositionMs = old.positionMs,
                positionMs = new.positionMs,
                lastLineIndex = old.currentLineIndex,
                lineIndex = new.currentLineIndex,
                currentWordKeyChanged = old.currentWordKey != new.currentWordKey
            )
        }

        if (old.currentLineIndex != new.currentLineIndex) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.LyricChanged,
                changedFields = setOf("lineIndex"),
                positionDeltaMs = positionDelta,
                reason = "line_index_changed",
                lastPositionMs = old.positionMs,
                positionMs = new.positionMs,
                lastLineIndex = old.currentLineIndex,
                lineIndex = new.currentLineIndex,
                currentWordKeyChanged = old.currentWordKey != new.currentWordKey
            )
        }

        if (old.currentLine != new.currentLine ||
            old.lyricStatus != new.lyricStatus
        ) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.LyricChanged,
                changedFields = setOf("currentLine", "lyricStatus").filterTo(mutableSetOf()) {
                    when (it) {
                        "currentLine" -> old.currentLine != new.currentLine
                        "lyricStatus" -> old.lyricStatus != new.lyricStatus
                        else -> false
                    }
                },
                reason = "lyric_changed",
                lastPositionMs = old.positionMs,
                positionMs = new.positionMs,
                lastLineIndex = old.currentLineIndex,
                lineIndex = new.currentLineIndex,
                currentWordKeyChanged = old.currentWordKey != new.currentWordKey
            )
        }

        if (old.currentWordKey != new.currentWordKey) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.CurrentWordChanged,
                changedFields = setOf("currentWord"),
                positionDeltaMs = positionDelta,
                reason = "current_word_changed",
                lastPositionMs = old.positionMs,
                positionMs = new.positionMs,
                lastLineIndex = old.currentLineIndex,
                lineIndex = new.currentLineIndex,
                currentWordKeyChanged = true
            )
        }

        if (old.albumArtId != new.albumArtId ||
            old.albumArtState != new.albumArtState
        ) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.AlbumArtChanged,
                changedFields = setOf("albumArtId", "albumArtState").filterTo(mutableSetOf()) {
                    when (it) {
                        "albumArtId" -> old.albumArtId != new.albumArtId
                        "albumArtState" -> old.albumArtState != new.albumArtState
                        else -> false
                    }
                },
                reason = "album_art_changed",
                lastPositionMs = old.positionMs,
                positionMs = new.positionMs,
                lastLineIndex = old.currentLineIndex,
                lineIndex = new.currentLineIndex,
                currentWordKeyChanged = old.currentWordKey != new.currentWordKey
            )
        }

        if (old.recoveryState != new.recoveryState) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.RecoveryChanged,
                changedFields = setOf("recoveryState"),
                reason = "recovery_changed",
                lastPositionMs = old.positionMs,
                positionMs = new.positionMs,
                lastLineIndex = old.currentLineIndex,
                lineIndex = new.currentLineIndex,
                currentWordKeyChanged = old.currentWordKey != new.currentWordKey
            )
        }

        if (old.volume != new.volume) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.VolumeChanged,
                changedFields = setOf("volume"),
                reason = "volume_changed",
                lastPositionMs = old.positionMs,
                positionMs = new.positionMs,
                lastLineIndex = old.currentLineIndex,
                lineIndex = new.currentLineIndex,
                currentWordKeyChanged = old.currentWordKey != new.currentWordKey
            )
        }

        if (old.connectionState != new.connectionState) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.ConnectionChanged,
                changedFields = setOf("connectionState"),
                reason = "connection_changed",
                lastPositionMs = old.positionMs,
                positionMs = new.positionMs,
                lastLineIndex = old.currentLineIndex,
                lineIndex = new.currentLineIndex,
                currentWordKeyChanged = old.currentWordKey != new.currentWordKey
            )
        }

        if (new.playing &&
            (positionDelta < POSITION_FULL_PUSH_THRESHOLD_MS || expectedPositionDelta > 0L)
        ) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.NoChange,
                positionDeltaMs = positionDelta,
                reason = "position_delta_small",
                lastPositionMs = old.positionMs,
                positionMs = new.positionMs,
                lastLineIndex = old.currentLineIndex,
                lineIndex = new.currentLineIndex,
                currentWordKeyChanged = false,
                shouldPush = false
            )
        }

        return PlaybackStateDiff(
            type = PlaybackStateDiffType.NoChange,
            positionDeltaMs = positionDelta,
            reason = "identical",
            lastPositionMs = old.positionMs,
            positionMs = new.positionMs,
            lastLineIndex = old.currentLineIndex,
            lineIndex = new.currentLineIndex,
            currentWordKeyChanged = false,
            shouldPush = false
        )
    }

    private fun changedIdentityFields(
        old: PlaybackStateSnapshot,
        new: PlaybackStateSnapshot
    ): Set<String> {
        val fields = mutableSetOf<String>()
        if (old.trackId != new.trackId) fields += "trackId"
        if (old.title != new.title) fields += "title"
        if (old.artist != new.artist) fields += "artist"
        if (old.album != new.album) fields += "album"
        return fields
    }

    private const val POSITION_JUMP_THRESHOLD_MS = 1000L
    private const val POSITION_FULL_PUSH_THRESHOLD_MS = 800L
}
