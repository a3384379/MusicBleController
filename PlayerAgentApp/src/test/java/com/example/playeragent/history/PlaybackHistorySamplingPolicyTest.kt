package com.example.playeragent.history

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackHistorySamplingPolicyTest {
    @Test
    fun adaptsIntervalToPlaybackState() {
        assertEquals(
            PlaybackHistorySamplingPolicy.IDLE_INTERVAL_MS,
            PlaybackHistorySamplingPolicy.intervalMs(null, hasActiveSession = false)
        )
        assertEquals(
            PlaybackHistorySamplingPolicy.PLAYING_INTERVAL_MS,
            PlaybackHistorySamplingPolicy.intervalMs(null, hasActiveSession = true)
        )
        assertEquals(
            PlaybackHistorySamplingPolicy.PLAYING_INTERVAL_MS,
            PlaybackHistorySamplingPolicy.intervalMs(snapshot(playing = true), false)
        )
        assertEquals(
            PlaybackHistorySamplingPolicy.PAUSED_INTERVAL_MS,
            PlaybackHistorySamplingPolicy.intervalMs(snapshot(playing = false), true)
        )
        assertEquals(
            PlaybackHistorySamplingPolicy.IDLE_INTERVAL_MS,
            PlaybackHistorySamplingPolicy.intervalMs(
                snapshot(playing = false, stopped = true),
                true
            )
        )
    }

    private fun snapshot(
        playing: Boolean,
        stopped: Boolean = false
    ): FastPlaybackSnapshot {
        return FastPlaybackSnapshot(
            packageName = "com.tencent.qqmusic",
            title = "song",
            artist = "artist",
            album = "album",
            playing = playing,
            stopped = stopped,
            positionMs = 1_000L,
            durationMs = 100_000L
        )
    }
}
