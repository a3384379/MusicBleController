package com.example.playeragent.media

data class PlaybackStateSnapshot(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val albumArtId: String?,
    val currentLine: String,
    val currentWord: RuntimeLyricWord?,
    val currentWordState: CurrentWordState? = null,
    val lyricStatus: String,
    val recoveryState: String,
    val albumArtState: String,
    val volume: Int? = null,
    val connectionState: String = "",
    val lastUpdatedAtMs: Long
) {
    val currentWordKey: String
        get() = currentWordState?.wordKey ?: currentWord?.let {
            "${it.startMs}|${it.durationMs}|${it.text}"
        }.orEmpty()
}
