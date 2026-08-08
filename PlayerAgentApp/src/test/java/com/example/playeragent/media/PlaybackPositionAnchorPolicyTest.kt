package com.example.playeragent.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPositionAnchorPolicyTest {
    @Test
    fun projectsFromMonotonicAnchorAtPlaybackSpeed() {
        assertEquals(
            2_500L,
            PlaybackPositionAnchorPolicy.projectedPositionMs(
                positionMs = 1_000L,
                positionAnchorElapsedMs = 10_000L,
                nowElapsedMs = 11_000L,
                durationMs = 30_000L,
                isPlaying = true,
                playbackSpeed = 1.5f
            )
        )
    }

    @Test
    fun pausedPositionDoesNotAdvanceAndDurationIsClamped() {
        assertEquals(
            9_000L,
            PlaybackPositionAnchorPolicy.projectedPositionMs(
                positionMs = 9_000L,
                positionAnchorElapsedMs = 1_000L,
                nowElapsedMs = 5_000L,
                durationMs = 10_000L,
                isPlaying = false,
                playbackSpeed = 1f
            )
        )
        assertEquals(
            10_000L,
            PlaybackPositionAnchorPolicy.projectedPositionMs(
                positionMs = 9_000L,
                positionAnchorElapsedMs = 1_000L,
                nowElapsedMs = 5_000L,
                durationMs = 10_000L,
                isPlaying = true,
                playbackSpeed = 1f
            )
        )
    }
}
