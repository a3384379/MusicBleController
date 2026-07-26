package com.example.playeragent.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLinkProfileTest {
    @Test
    fun failureAndSlowCallbackBackOffOnlyTheirPayloadKind() {
        val profile = BleLinkProfile(initialMtu = 185)
        profile.recordFailure(BleLinkProfile.PayloadKind.BINARY_LYRIC)
        assertEquals(7L, profile.binaryDelayMs)
        assertEquals(5L, profile.jsonDelayMs)
        assertEquals(3L, profile.artworkDelayMs)

        profile.recordFailure(BleLinkProfile.PayloadKind.BINARY_ARTWORK)
        assertEquals(8L, profile.artworkDelayMs)
        repeat(20) {
            profile.recordSuccess(
                BleLinkProfile.PayloadKind.BINARY_ARTWORK,
                callbackRttMs = 20
            )
        }
        assertEquals(7L, profile.artworkDelayMs)

        profile.recordSuccess(
            BleLinkProfile.PayloadKind.JSON_LYRIC,
            callbackRttMs = 180
        )
        assertEquals(10L, profile.jsonDelayMs)
        assertTrue(profile.ewmaCallbackRttMs > 20.0)
    }

    @Test
    fun fastSuccessWindowReducesDelayAndMtuChangeResetsProfile() {
        val profile = BleLinkProfile(initialMtu = 23)
        profile.recordFailure(BleLinkProfile.PayloadKind.JSON_LYRIC)
        repeat(20) {
            profile.recordSuccess(
                BleLinkProfile.PayloadKind.JSON_LYRIC,
                callbackRttMs = 20
            )
        }
        assertEquals(9L, profile.jsonDelayMs)

        profile.updateMtu(247)
        assertEquals(247, profile.mtu)
        assertEquals(5L, profile.jsonDelayMs)
        assertEquals(2L, profile.binaryDelayMs)
        assertEquals(3L, profile.artworkDelayMs)
        assertEquals(0L, profile.successCount)
        assertEquals(0L, profile.failureCount)
    }
}
