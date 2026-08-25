package com.example.playeragent.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaWireGenerationPolicyTest {
    @Test
    fun `matching runtime track owns wire generation after service restart`() {
        assertEquals(
            107L,
            MediaWireGenerationPolicy.resolve(
                protocolTrackId = "0123456789ab",
                runtimeTrackId = "0123456789abcdef01234567",
                runtimeGeneration = 107L,
                fallbackGeneration = 105L
            )
        )
    }

    @Test
    fun `different runtime track cannot donate its generation`() {
        assertEquals(
            105L,
            MediaWireGenerationPolicy.resolve(
                protocolTrackId = "0123456789ab",
                runtimeTrackId = "fedcba987654321001234567",
                runtimeGeneration = 107L,
                fallbackGeneration = 105L
            )
        )
    }

    @Test
    fun `missing or uninitialized runtime generation uses task fallback`() {
        assertEquals(
            5L,
            MediaWireGenerationPolicy.resolve("0123456789ab", null, null, 5L)
        )
        assertEquals(
            5L,
            MediaWireGenerationPolicy.resolve(
                protocolTrackId = "0123456789ab",
                runtimeTrackId = "0123456789ab",
                runtimeGeneration = 0L,
                fallbackGeneration = 5L
            )
        )
    }
}
