package com.example.controllerapp.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPlaceholderPolicyTest {
    @Test
    fun `QQ 228 gray placeholder is rejected`() {
        val gray = 0xff777777.toInt()
        assertTrue(
            ArtworkPlaceholderPolicy.isLikelyPlaceholder(
                228,
                228,
                IntArray(24 * 24) { gray }
            )
        )
    }

    @Test
    fun `colorful square and stable large grayscale image are retained`() {
        val colorful = IntArray(24 * 24) { index ->
            if (index % 2 == 0) 0xffff3300.toInt() else 0xff0055ff.toInt()
        }
        assertFalse(ArtworkPlaceholderPolicy.isLikelyPlaceholder(228, 228, colorful))
        assertFalse(
            ArtworkPlaceholderPolicy.isLikelyPlaceholder(
                780,
                780,
                IntArray(24 * 24) { 0xff777777.toInt() }
            )
        )
    }
}
