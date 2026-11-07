package com.example.controllerapp.ui

import com.example.controllerapp.model.PlaybackHistorySession
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDisplayPolicyTest {
    private val session = PlaybackHistorySession(
        sessionId = 1, trackKey = "track", title = "song", artist = "artist", album = "",
        artworkId = null, startedAt = 1, endedAt = null, listenedMs = 0, durationMs = 0,
        completed = false, skipped = false, countedPlay = false
    )

    @Test
    fun `active session status takes precedence over stale result flags`() {
        assertEquals("进行中 · 未计播放", HistoryDisplayPolicy.status(session.copy(completed = true)))
        assertEquals("进行中 · 已计播放", HistoryDisplayPolicy.status(session.copy(skipped = true, countedPlay = true)))
    }

    @Test
    fun `ended session preserves completion skip and interruption status`() {
        assertEquals("已完播", HistoryDisplayPolicy.status(session.copy(endedAt = 2, completed = true)))
        assertEquals("已跳过", HistoryDisplayPolicy.status(session.copy(endedAt = 2, skipped = true)))
        assertEquals("播放中断", HistoryDisplayPolicy.status(session.copy(endedAt = 2)))
    }
}
