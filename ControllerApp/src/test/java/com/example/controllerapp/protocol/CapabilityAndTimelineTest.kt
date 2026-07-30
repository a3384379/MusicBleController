package com.example.controllerapp.protocol

import com.example.controllerapp.model.LyricLine
import com.example.controllerapp.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CapabilityAndTimelineTest {
    @Test
    fun `capability timeout preserves acknowledged V2 and otherwise falls back`() {
        val v2 = ServerCapabilities(
            negotiated = true,
            protocolVersion = 2,
            albumArtBinary = true,
            fullLyricsZlib = true,
            lyricWindow = true,
            ping = true,
            transferRetry = true
        )
        assertSame(v2, CapabilityPolicy.fallbackIfUnacknowledged(v2))
        val fallback = CapabilityPolicy.fallbackIfUnacknowledged(
            ServerCapabilities(fullLyricsZlib = true)
        )
        assertFalse(fallback.negotiated)
        assertEquals(1, fallback.protocolVersion)
        assertFalse(fallback.fullLyricsZlib)
    }

    @Test
    fun `current word fence rejects stale sequence but accepts seek and generation change`() {
        val first = CurrentWordOrderingFence().accept(4, 10, 10_000)!!
        assertNull(first.accept(4, 10, 10_200))
        assertNull(first.accept(3, 99, 11_000))
        val afterSeek = first.accept(4, 11, 9_200)!!
        assertEquals(9_200L, afterSeek.positionMs)
        val newGeneration = afterSeek.accept(5, 1, 0)!!
        assertEquals(5L, newGeneration.generation)
        assertEquals(1L, newGeneration.sequence)
    }

    @Test
    fun `window line uses global server index without treating it as list offset`() {
        val lines = (7..11).map { index ->
            LyricLine(index, index * 1_000L, 900, "line $index")
        }
        assertEquals(2, LyricTimeline.currentLinePosition(lines, 9_100, 9))
        assertEquals(0, LyricTimeline.currentLinePosition(lines, 6_000, 7))
    }

    @Test
    fun `word timeline follows QRC boundaries`() {
        val line = LyricLine(
            index = 0,
            timeMs = 0,
            durationMs = 1_000,
            text = "hello",
            words = listOf(
                LyricWord(0, 0, 400, "he"),
                LyricWord(1, 400, 600, "llo")
            )
        )
        assertEquals(0, LyricTimeline.currentWordPosition(line, 399))
        assertEquals(1, LyricTimeline.currentWordPosition(line, 400))
    }
}
