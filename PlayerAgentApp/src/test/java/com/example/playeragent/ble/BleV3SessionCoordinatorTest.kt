package com.example.playeragent.ble

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleV3SessionCoordinatorTest {

    @Test
    fun capabilityBitsAreNegotiatedAndStatusMetaRequiresLargePayload() {
        assertEquals(
            BleV3Features.ALL,
            BleV3CapabilityPolicy.negotiateF3(3, BleV3Features.ALL, 247)
        )
        assertEquals(
            BleV3Features.STRUCTURED_ERROR_V1 or
                BleV3Features.MEDIA_LOAD_STATE_V1 or
                BleV3Features.MEDIA_CACHE_VALIDATION_V1,
            BleV3CapabilityPolicy.negotiateF3(3, BleV3Features.ALL, 246)
        )
        assertEquals(0, BleV3CapabilityPolicy.negotiateF3(2, BleV3Features.ALL, 512))
        assertEquals(0, BleV3CapabilityPolicy.negotiateF3(3, null, 512))
        assertEquals(63, BleV3CapabilityPolicy.f2(true, true, true, true, true, true))
    }

    @Test
    fun v2AckRemainsVerboseAndV3AckIsCompact() {
        val v2 = BleCapabilitiesAckPolicy.build(
            ConnectionCommandCoordinator.Capabilities(protocolVersion = 2, negotiated = true),
            requestedF3Present = false,
            sessionId = "1234abcd"
        )
        assertEquals(2, v2.getInt("protocolVersion"))
        assertTrue(v2.getBoolean("albumArtBinary"))
        assertTrue(v2.getBoolean("fullLyricsZlib"))
        assertTrue(v2.getBoolean("lyricWindow"))
        assertTrue(v2.getBoolean("ping"))
        assertTrue(v2.getBoolean("clockSyncV1"))
        assertTrue(v2.getBoolean("transferRetry"))
        assertFalse(v2.has("f2"))
        assertFalse(v2.has("f3"))
        assertFalse(v2.has("sid"))
        assertFalse(v2.has("es"))

        val v3 = BleCapabilitiesAckPolicy.build(
            ConnectionCommandCoordinator.Capabilities(
                protocolVersion = 3,
                f2 = 63,
                f3 = 7,
                negotiated = true
            ),
            requestedF3Present = true,
            sessionId = "1234abcd"
        )
        assertEquals(3, v3.getInt("protocolVersion"))
        assertEquals(63, v3.getInt("f2"))
        assertEquals(7, v3.getInt("f3"))
        assertEquals("1234abcd", v3.getString("sid"))
        assertFalse(v3.has("albumArtBinary"))
    }

    @Test
    fun stateDedupeAndEnqueueSequenceArePerDevice() {
        val coordinator = BleV3SessionCoordinator("1234abcd")
        assertTrue(coordinator.shouldSendMediaLoadState("A", "lyrics", "t", 1, "waiting", "qrc_pending"))
        assertFalse(coordinator.shouldSendMediaLoadState("A", "lyrics", "t", 1, "waiting", "qrc_pending"))
        assertTrue(coordinator.shouldSendMediaLoadState("A", "lyrics", "t", 1, "ready", "transfer_complete"))
        assertTrue(coordinator.shouldSendMediaLoadState("B", "lyrics", "t", 1, "waiting", "qrc_pending"))

        val first = coordinator.decorate("A", JSONObject().put("type", "playbackState"))
        val second = coordinator.decorate("A", JSONObject().put("type", "currentWord"))
        assertEquals(1L, first.getLong("es"))
        assertEquals(2L, second.getLong("es"))
        assertEquals("1234abcd", second.getString("sid"))
    }

    @Test
    fun statusMetadataIsOnlyAddedWhenNegotiated() {
        val coordinator = BleV3SessionCoordinator("1234abcd")
        val value = JSONObject().put("type", "mediaLoadState")

        val withoutMetadata = coordinator.decorateIfEnabled("A", value, enabled = false)
        assertFalse(withoutMetadata.has("sid"))
        assertFalse(withoutMetadata.has("es"))

        val withMetadata = coordinator.decorateIfEnabled("A", value, enabled = true)
        assertEquals("1234abcd", withMetadata.getString("sid"))
        assertEquals(1L, withMetadata.getLong("es"))
    }

    @Test
    fun commandErrorKeepsOriginalSequence() {
        val error = BleV3PayloadFactory.commandError(
            seq = "42",
            command = "GET_FULL_LYRICS",
            domain = "lyrics",
            code = "qrc_pending",
            retryable = true
        )
        assertEquals("42", error.getString("seq"))
        assertEquals("GET_FULL_LYRICS", error.getString("cmd"))
        assertTrue(error.getBoolean("retryable"))

        error
            .put("retryAfterMs", 1_200)
            .put("trackId", "track-1")
            .put("generation", 7)
        val decorated = BleV3SessionCoordinator("1234abcd").decorate("A", error)
        assertEquals(1_200L, decorated.getLong("retryAfterMs"))
        assertEquals("track-1", decorated.getString("trackId"))
        assertEquals(7L, decorated.getLong("generation"))
        assertEquals("1234abcd", decorated.getString("sid"))
        assertEquals(1L, decorated.getLong("es"))
    }
}
