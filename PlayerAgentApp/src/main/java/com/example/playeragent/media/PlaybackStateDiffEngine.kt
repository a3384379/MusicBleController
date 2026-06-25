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
            if (diff.type == PlaybackStateDiffType.NoChange) {
                skipCount += 1
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
                positionJumpCount = positionJumpCount
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
                changedFields = setOf("initial")
            )
        }

        val trackChanged = old.trackId != new.trackId ||
            old.title != new.title ||
            old.artist != new.artist ||
            old.album != new.album
        if (trackChanged) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.TrackChanged,
                changedFields = changedIdentityFields(old, new)
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
                }
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
                positionDeltaMs = positionDrift
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
                }
            )
        }

        if (old.currentWordKey != new.currentWordKey) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.CurrentWordChanged,
                changedFields = setOf("currentWord"),
                positionDeltaMs = positionDelta
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
                }
            )
        }

        if (old.recoveryState != new.recoveryState) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.RecoveryChanged,
                changedFields = setOf("recoveryState")
            )
        }

        if (old.volume != new.volume) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.VolumeChanged,
                changedFields = setOf("volume")
            )
        }

        if (old.connectionState != new.connectionState) {
            return PlaybackStateDiff(
                type = PlaybackStateDiffType.ConnectionChanged,
                changedFields = setOf("connectionState")
            )
        }

        return PlaybackStateDiff(
            type = PlaybackStateDiffType.NoChange,
            positionDeltaMs = positionDelta,
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
}
