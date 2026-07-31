package com.example.controllerapp.media

import com.example.controllerapp.model.PlaybackPerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackClockPolicyTest {
    @Test
    fun `clock is suspended while paused or UI is hidden`() {
        assertNull(
            PlaybackClockPolicy.refreshIntervalMs(
                uiVisible = false,
                isPlaying = true,
                performanceMode = PlaybackPerformanceMode.SMOOTH
            )
        )
        assertNull(
            PlaybackClockPolicy.refreshIntervalMs(
                uiVisible = true,
                isPlaying = false,
                performanceMode = PlaybackPerformanceMode.AUTOMATIC
            )
        )
    }

    @Test
    fun `visible playback respects performance mode`() {
        assertEquals(
            50L,
            PlaybackClockPolicy.refreshIntervalMs(
                true,
                true,
                PlaybackPerformanceMode.SMOOTH
            )
        )
        assertEquals(
            100L,
            PlaybackClockPolicy.refreshIntervalMs(
                true,
                true,
                PlaybackPerformanceMode.AUTOMATIC
            )
        )
        assertEquals(
            200L,
            PlaybackClockPolicy.refreshIntervalMs(
                true,
                true,
                PlaybackPerformanceMode.POWER_SAVING
            )
        )
        assertEquals(
            200L,
            PlaybackClockPolicy.refreshIntervalMs(
                true,
                true,
                PlaybackPerformanceMode.AUTOMATIC,
                systemPowerSaveMode = true
            )
        )
    }

    @Test
    fun `automatic mode respects battery saver and reduced motion`() {
        assertEquals(
            false,
            PlaybackClockPolicy.animationsEnabled(
                PlaybackPerformanceMode.AUTOMATIC,
                systemPowerSaveMode = true,
                systemAnimationsEnabled = true
            )
        )
        assertEquals(
            false,
            PlaybackClockPolicy.animationsEnabled(
                PlaybackPerformanceMode.SMOOTH,
                systemPowerSaveMode = false,
                systemAnimationsEnabled = false
            )
        )
    }
}
