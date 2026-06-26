package com.example.playeragent.media

enum class PlaybackStateDiffType {
    TrackChanged,
    PlaybackChanged,
    PositionJump,
    LyricChanged,
    CurrentWordChanged,
    AlbumArtChanged,
    RecoveryChanged,
    VolumeChanged,
    ConnectionChanged,
    NoChange
}

data class PlaybackStateDiff(
    val type: PlaybackStateDiffType,
    val changedFields: Set<String> = emptySet(),
    val positionDeltaMs: Long = 0L,
    val reason: String = type.name,
    val lastPositionMs: Long = 0L,
    val positionMs: Long = 0L,
    val lastLineIndex: Int = -1,
    val lineIndex: Int = -1,
    val currentWordKeyChanged: Boolean = false,
    val shouldPush: Boolean = type != PlaybackStateDiffType.NoChange,
    val lightweight: Boolean = type == PlaybackStateDiffType.CurrentWordChanged
)

data class PlaybackDiffMetrics(
    val snapshotBuildCount: Long = 0L,
    val diffCount: Long = 0L,
    val pushCount: Long = 0L,
    val skipCount: Long = 0L,
    val trackChangedCount: Long = 0L,
    val wordChangedCount: Long = 0L,
    val positionJumpCount: Long = 0L,
    val positionSmallSkipCount: Long = 0L,
    val identicalSkipCount: Long = 0L
)
