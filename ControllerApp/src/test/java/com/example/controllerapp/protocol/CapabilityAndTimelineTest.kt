package com.example.controllerapp.protocol

import com.example.controllerapp.model.LyricLine
import com.example.controllerapp.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        assertEquals(0.5f, LyricTimeline.lineProgress(line, 500), 0.001f)
        assertEquals(0f, LyricTimeline.lineProgress(line, -100), 0.001f)
        assertEquals(1f, LyricTimeline.lineProgress(line, 2_000), 0.001f)
    }

    @Test
    fun `karaoke character progress uses word duration and preserves separators`() {
        val line = LyricLine(
            index = 1,
            timeMs = 1_000,
            durationMs = 1_200,
            text = "hello world",
            words = listOf(
                LyricWord(0, 1_000, 500, "hello"),
                LyricWord(1, 1_600, 500, "world")
            )
        )

        val midwayFirstWord = LyricTimeline.characterProgresses(line, 1_250)
        assertEquals(1f, midwayFirstWord[0], 0.001f)
        assertEquals(1f, midwayFirstWord[1], 0.001f)
        assertEquals(0.5f, midwayFirstWord[2], 0.001f)
        assertEquals(0f, midwayFirstWord[4], 0.001f)
        assertEquals(0f, midwayFirstWord[6], 0.001f)

        val betweenWords = LyricTimeline.characterProgresses(line, 1_550)
        assertEquals(1f, betweenWords[4], 0.001f)
        assertEquals(1f, betweenWords[5], 0.001f)
        assertEquals(0f, betweenWords[6], 0.001f)
    }

    @Test
    fun `karaoke progress falls back to line timing without words`() {
        val line = LyricLine(
            index = 2,
            timeMs = 2_000,
            durationMs = 1_000,
            text = "歌词"
        )

        val midway = LyricTimeline.characterProgresses(line, 2_250)
        assertEquals(0.5f, midway[0], 0.001f)
        assertEquals(0f, midway[1], 0.001f)
        assertTrue(LyricTimeline.characterProgresses(line, 3_000).all { it == 1f })
    }

    @Test
    fun `karaoke ignores invalid doubled QRC word clock and uses line clock`() {
        val line = LyricLine(
            index = 3,
            timeMs = 100_000,
            durationMs = 4_000,
            text = "abcd",
            words = listOf(LyricWord(0, 200_000, 500, "abcd"))
        )

        val midway = LyricTimeline.characterProgresses(line, 102_000)
        assertEquals(1f, midway[0], 0.001f)
        assertEquals(1f, midway[1], 0.001f)
        assertEquals(0f, midway[2], 0.001f)
        assertEquals(0f, midway[3], 0.001f)
    }
}
