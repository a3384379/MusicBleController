package com.example.controllerapp.protocol

import com.example.controllerapp.model.LyricLine
import com.example.controllerapp.model.LyricLoadingStage
import com.example.controllerapp.model.LyricsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPublicationPolicyTest {
    private val full = (0 until 60).map { index ->
        LyricLine(index, index * 1_000L, 900L, "full-$index")
    }

    @Test
    fun `window remains useful without pretending to be full lyrics`() {
        val window = full.subList(20, 25)
        val result = LyricsPublicationPolicy.publishWindow(
            state = LyricsState(trackId = "song"),
            trackId = "song",
            generation = 7L,
            lines = window,
            currentLineIndex = 22
        )

        assertEquals(window, result.windowLines)
        assertEquals(window, result.lines)
        assertFalse(result.isFinal)
        assertEquals(LyricLoadingStage.WINDOW_READY, result.loadingStage)
    }

    @Test
    fun `late five line window cannot replace completed lyrics or progress`() {
        val completed = LyricsPublicationPolicy.publishFull(
            state = LyricsState(trackId = "song"),
            trackId = "song",
            generation = 7L,
            lines = full,
            currentLineIndex = 21,
            format = "zlib-json-v1"
        )
        val result = LyricsPublicationPolicy.publishWindow(
            state = completed,
            trackId = "song",
            generation = 7L,
            lines = full.subList(20, 25),
            currentLineIndex = 22
        )

        assertTrue(result.isFinal)
        assertEquals(full, result.lines)
        assertEquals(60, result.receivedChunks)
        assertEquals(60, result.expectedChunks)
        assertEquals(LyricLoadingStage.READY, result.loadingStage)
    }

    @Test
    fun `partial transfer is visible then atomically replaced by final lyrics`() {
        val initial = LyricsState(
            trackId = "song",
            windowLines = full.subList(5, 10)
        )
        val partial = LyricsPublicationPolicy.publishPartial(
            initial,
            "song",
            9L,
            full.take(12)
        )
        assertEquals(12, partial.lines.size)
        assertFalse(partial.isFinal)

        val completed = LyricsPublicationPolicy.publishFull(
            partial,
            "song",
            9L,
            full,
            currentLineIndex = 8,
            format = "legacy"
        )
        assertTrue(completed.isFinal)
        assertTrue(completed.partialFullLines.isEmpty())
        assertEquals(full, completed.lines)
    }

    @Test
    fun `partial data received after final is ignored`() {
        val completed = LyricsPublicationPolicy.publishFull(
            LyricsState(),
            "song",
            3L,
            full,
            currentLineIndex = 0,
            format = "legacy"
        )
        val result = LyricsPublicationPolicy.publishPartial(
            completed,
            "song",
            3L,
            full.take(3)
        )

        assertSame(completed, result)
    }
}
