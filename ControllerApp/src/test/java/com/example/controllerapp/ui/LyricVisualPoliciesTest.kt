package com.example.controllerapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricVisualPoliciesTest {
    @Test
    fun `scrolling and recent release cannot activate a lyric`() {
        assertFalse(LyricBrowsePolicy.canActivate(true, 1_000, 5_000))
        assertFalse(LyricBrowsePolicy.canActivate(false, 1_000, 1_249))
        assertTrue(LyricBrowsePolicy.canActivate(false, 1_000, 1_250))
        assertFalse(LyricBrowsePolicy.canActivate(false, 1_000, 999))
        assertTrue(LyricBrowsePolicy.canActivate(false, Long.MIN_VALUE, 0))
    }

    @Test
    fun `first lyric tap selects and second connected tap confirms seek`() {
        val first = LyricBrowsePolicy.onLineTapped(
            browsing = false,
            selectedLineId = null,
            tappedLineId = 8,
            lineTimeMs = 42_300,
            connected = true
        )
        assertEquals(8, first.selectedLineId)
        assertNull(first.seekPositionMs)

        val confirmed = LyricBrowsePolicy.onLineTapped(
            browsing = true,
            selectedLineId = first.selectedLineId,
            tappedLineId = 8,
            lineTimeMs = 42_300,
            connected = true
        )
        assertEquals(42_300L, confirmed.seekPositionMs)
    }

    @Test
    fun `disconnected lyric selection never seeks`() {
        val result = LyricBrowsePolicy.onLineTapped(true, 3, 3, 8_000, false)
        assertEquals(3, result.selectedLineId)
        assertNull(result.seekPositionMs)
    }

    @Test
    fun `nearest viewport line follows visual center`() {
        assertEquals(
            4,
            LyricBrowsePolicy.nearestLine(
                listOf(
                    LyricViewportItem(3, 100, 140),
                    LyricViewportItem(4, 180, 230),
                    LyricViewportItem(5, 280, 340)
                ),
                viewportCenterPx = 220
            )
        )
    }

    @Test
    fun `viewport center inside long line takes priority over closer adjacent center`() {
        assertEquals(20, LyricBrowsePolicy.nearestLine(
            listOf(LyricViewportItem(20, -900, 300), LyricViewportItem(21, 308, 408)),
            viewportCenterPx = 250
        ))
        assertEquals(21, LyricBrowsePolicy.nearestLine(
            listOf(LyricViewportItem(20, -900, 300), LyricViewportItem(21, 308, 408)),
            viewportCenterPx = 306
        ))
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
