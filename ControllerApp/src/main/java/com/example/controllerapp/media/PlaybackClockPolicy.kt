package com.example.controllerapp.media

import com.example.controllerapp.model.PlaybackPerformanceMode

object PlaybackClockPolicy {
    fun refreshIntervalMs(
        uiVisible: Boolean,
        isPlaying: Boolean,
        performanceMode: PlaybackPerformanceMode,
        systemPowerSaveMode: Boolean = false
    ): Long? {
        if (!uiVisible || !isPlaying) return null
        return when (performanceMode) {
            PlaybackPerformanceMode.SMOOTH -> 50L
            PlaybackPerformanceMode.POWER_SAVING -> 200L
            PlaybackPerformanceMode.AUTOMATIC -> if (systemPowerSaveMode) 200L else 100L
        }
    }

    fun animationsEnabled(
        performanceMode: PlaybackPerformanceMode,
        systemPowerSaveMode: Boolean,
        systemAnimationsEnabled: Boolean
    ): Boolean = systemAnimationsEnabled &&
        performanceMode != PlaybackPerformanceMode.POWER_SAVING &&
        !(performanceMode == PlaybackPerformanceMode.AUTOMATIC && systemPowerSaveMode)
}
