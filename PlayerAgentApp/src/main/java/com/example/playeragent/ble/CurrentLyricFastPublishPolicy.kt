package com.example.playeragent.ble

import com.example.playeragent.media.LyricsReadyGateSnapshot

object CurrentLyricFastPublishPolicy {
    fun shouldPublish(
        ready: LyricsReadyGateSnapshot,
        currentTrackId: String,
        currentGeneration: Long,
        currentLyric: String,
        hasSubscribers: Boolean
    ): Boolean {
        if (!ready.lyricsReady || !hasSubscribers || currentLyric.isBlank()) return false
        if (ready.trackId.isBlank() || currentTrackId.isBlank()) return false
        if (ready.generation <= 0L || currentGeneration != ready.generation) return false
        return ready.trackId.take(TRACK_ID_COMPARE_LENGTH) ==
            currentTrackId.take(TRACK_ID_COMPARE_LENGTH)
    }

    private const val TRACK_ID_COMPARE_LENGTH = 12
}
