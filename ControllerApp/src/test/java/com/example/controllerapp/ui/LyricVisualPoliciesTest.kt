package com.example.controllerapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricVisualPoliciesTest {
    @Test
    fun `first lyric tap selects and second connected tap confirms seek`() {
        val first = LyricBrowsePolicy.onLineTapped(
            browsing = false,
            selectedLinePosition = null,
            tappedLinePosition = 8,
            lineTimeMs = 42_300,
            connected = true
        )
        assertEquals(8, first.selectedLinePosition)
        assertNull(first.seekPositionMs)

        val confirmed = LyricBrowsePolicy.onLineTapped(
            browsing = true,
            selectedLinePosition = first.selectedLinePosition,
            tappedLinePosition = 8,
            lineTimeMs = 42_300,
            connected = true
        )
        assertEquals(42_300L, confirmed.seekPositionMs)
    }

    @Test
    fun `disconnected lyric selection never seeks`() {
        val result = LyricBrowsePolicy.onLineTapped(true, 3, 3, 8_000, false)
        assertEquals(3, result.selectedLinePosition)
        assertNull(result.seekPositionMs)
    }

    @Test
    fun `nearest viewport line follows visual center`() {
        assertEquals(
            4,
            LyricBrowsePolicy.nearestLine(
                listOf(
                    LyricViewportItem(3, 120),
                    LyricViewportItem(4, 205),
                    LyricViewportItem(5, 310)
                ),
                viewportCenterPx = 220
            )
        )
    }

    @Test
    fun `natural spectrum is bounded varied and track specific`() {
        val firstEngine = NaturalSpectrumEngine(36)
        val first = firstEngine.levels(
            SpectrumFrame("track-a", 12_000, true, 0.42f, "4:2")
        )
        val second = NaturalSpectrumEngine(36).levels(
            SpectrumFrame("track-b", 12_000, true, 0.42f, "4:2")
        )

        assertTrue(first.all { it in 0f..1f })
        assertTrue(first.maxOrNull()!! - first.minOrNull()!! > 0.08f)
        assertFalse(first.contentEquals(second))
    }
}
