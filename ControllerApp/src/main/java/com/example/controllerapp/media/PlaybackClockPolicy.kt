package com.example.controllerapp.media

import com.example.controllerapp.model.PlaybackPerformanceMode

object PlaybackClockPolicy {
    fun refreshIntervalMs(
        uiVisible: Boolean,
        isPlaying: Boolean,
        performanceMode: PlaybackPerformanceMode
    ): Long? {
        if (!uiVisible || !isPlaying) return null
        return when (performanceMode) {
            PlaybackPerformanceMode.SMOOTH -> 50L
            PlaybackPerformanceMode.POWER_SAVING -> 200L
            PlaybackPerformanceMode.AUTOMATIC -> 100L
        }
    }
}
