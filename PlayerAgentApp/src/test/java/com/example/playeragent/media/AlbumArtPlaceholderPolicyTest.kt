package com.example.playeragent.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumArtPlaceholderPolicyTest {
    @Test
    fun qqFallbackIconIsRejected() {
        val pixels = IntArray(24 * 24) { index ->
            if (index < 464) {
                argb(48, 48, 48)
            } else {
                argb(96, 96, 96)
            }
        }

        assertTrue(
            AlbumArtPlaceholderPolicy.isLikelyPlaceholder(
                sourceWidth = 228,
                sourceHeight = 228,
                pixels = pixels
            )
        )
    }

    @Test
    fun realColorArtworkAtSameSizeIsAccepted() {
        val pixels = IntArray(24 * 24) { index ->
            val row = index / 24
            val column = index % 24
            argb(
                red = (row * 11) and 0xff,
                green = (column * 9) and 0xff,
                blue = ((row + column) * 7) and 0xff
            )
        }

        assertFalse(
            AlbumArtPlaceholderPolicy.isLikelyPlaceholder(
                sourceWidth = 228,
                sourceHeight = 228,
                pixels = pixels
            )
        )
    }

    @Test
    fun detailedMonochromeArtworkIsNotMistakenForFallback() {
        val grayLevels = intArrayOf(20, 48, 76, 104, 132, 160, 188, 216)
        val pixels = IntArray(24 * 24) { index ->
            val gray = grayLevels[index % grayLevels.size]
            argb(gray, gray, gray)
        }

        assertFalse(
            AlbumArtPlaceholderPolicy.isLikelyPlaceholder(
                sourceWidth = 228,
                sourceHeight = 228,
                pixels = pixels
            )
        )
    }

    @Test
    fun tinyOrEmptyArtworkIsRejected() {
        assertTrue(
            AlbumArtPlaceholderPolicy.isLikelyPlaceholder(
                sourceWidth = 16,
                sourceHeight = 16,
                pixels = intArrayOf(argb(255, 0, 0))
            )
        )
        assertTrue(
            AlbumArtPlaceholderPolicy.isLikelyPlaceholder(
                sourceWidth = 228,
                sourceHeight = 228,
                pixels = intArrayOf()
            )
        )
    }

    private fun argb(red: Int, green: Int, blue: Int): Int {
        return (0xff shl 24) or
            ((red and 0xff) shl 16) or
            ((green and 0xff) shl 8) or
            (blue and 0xff)
    }
}
