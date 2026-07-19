package com.example.playeragent.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumArtCompressionPolicyTest {
    @Test
    fun previewKeepsRealtimeTargetAndAddsHighEntropyFallbacks() {
        val profiles = AlbumArtCompressionPolicy.previewProfiles()

        assertEquals(112, profiles.first().width)
        assertEquals(48, profiles.first().quality)
        assertEquals(40, profiles.last().width)
        assertEquals(20, profiles.last().quality)
        assertTrue(profiles.zipWithNext().all { (current, next) ->
            next.width < current.width && next.quality <= current.quality
        })
    }

    @Test
    fun hqCanScaleBelowFormer192PixelFloor() {
        val profiles = AlbumArtCompressionPolicy.hqProfiles(1024, 1024)

        assertTrue(profiles.any { it.width == 176 && it.quality == 62 })
        assertTrue(profiles.any { it.width == 160 && it.quality == 58 })
        assertEquals(128, profiles.last().width)
        assertEquals(50, profiles.last().quality)
    }

    @Test
    fun hqNeverUpscalesSmallSources() {
        val profiles = AlbumArtCompressionPolicy.hqProfiles(96, 72)

        assertTrue(profiles.all { it.width <= 96 && it.height <= 72 })
        assertEquals(profiles.size, profiles.distinct().size)
    }
}
