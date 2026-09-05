package com.example.playeragent.ble

import com.example.playeragent.media.LyricsReadyGateSnapshot
import com.example.playeragent.media.LyricsReadyState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLyricFastPublishPolicyTest {
    @Test
    fun exactGenerationAndProtocolIdentityPublishImmediately() {
        assertTrue(
            CurrentLyricFastPublishPolicy.shouldPublish(
                ready = ready(),
                currentTrackId = "123456789abc",
                currentGeneration = 9L,
                currentLyric = "line",
                hasSubscribers = true
            )
        )
    }

    @Test
    fun staleIdentityGenerationAndEmptyContentAreRejected() {
        assertFalse(
            CurrentLyricFastPublishPolicy.shouldPublish(
                ready = ready(),
                currentTrackId = "ffffffffffff",
                currentGeneration = 9L,
                currentLyric = "line",
                hasSubscribers = true
            )
        )
        assertFalse(
            CurrentLyricFastPublishPolicy.shouldPublish(
                ready = ready(),
                currentTrackId = "123456789abc",
                currentGeneration = 8L,
                currentLyric = "line",
                hasSubscribers = true
            )
        )
        assertFalse(
            CurrentLyricFastPublishPolicy.shouldPublish(
                ready = ready(),
                currentTrackId = "123456789abc",
                currentGeneration = 9L,
                currentLyric = "",
                hasSubscribers = true
            )
        )
    }

    private fun ready(): LyricsReadyGateSnapshot {
        return LyricsReadyGateSnapshot(
            state = LyricsReadyState.READY,
            lyricsReady = true,
            trackId = "123456789abc-full-digest",
            generation = 9L,
            lineCount = 10
        )
    }
}
