package com.example.playeragent.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTransferCoordinatorsTest {
    @Test
    fun lyricsRetryStateIsIndependentFromAlbumArtState() {
        val lyrics = LyricsTransferCoordinator()
        val artwork = AlbumArtTransferCoordinator()
        val packet = BleNotifyQueue.Packet("chunk", byteArrayOf(1), 0)
        val lyricTransfer = FullLyricsBinaryTransfer(
            trackId = "track-a",
            transferId = "lyrics-a",
            generation = 9,
            start = packet,
            chunks = listOf(packet),
            end = packet,
            expiresAtMs = 2_000
        )
        lyrics.retain(lyricTransfer)
        artwork.reset()

        assertEquals(lyricTransfer, lyrics.retained("lyrics-a"))
        assertNull(lyrics.retained("art-a"))
    }

    @Test
    fun expiredArtworkEntriesArePrunedWithoutTouchingCurrentTransfer() {
        val coordinator = AlbumArtTransferCoordinator()
        val packet = BleNotifyQueue.Packet("chunk", byteArrayOf(1), 0)
        coordinator.retain(
            AlbumArtBinaryTransfer(
                trackId = "old",
                quality = AlbumArtQuality.PREVIEW,
                transferId = "old-transfer",
                start = packet,
                chunks = listOf(packet),
                end = packet,
                expiresAtMs = 99
            ),
            nowMs = 10
        )
        coordinator.retain(
            AlbumArtBinaryTransfer(
                trackId = "current",
                quality = AlbumArtQuality.HQ,
                transferId = "current-transfer",
                start = packet,
                chunks = listOf(packet),
                end = packet,
                expiresAtMs = 500
            ),
            nowMs = 100
        )

        assertNull(coordinator.retained("old-transfer"))
        assertEquals("current", coordinator.retained("current-transfer")?.trackId)
    }

    @Test
    fun capabilityFallbackOnlyAppliesToCurrentSubscriptionGeneration() {
        val coordinator = ConnectionCommandCoordinator()
        val first = coordinator.beginNegotiation()
        val second = coordinator.beginNegotiation()

        assertFalse(coordinator.useLegacyIfCurrent(first))
        assertTrue(coordinator.useLegacyIfCurrent(second))
        assertTrue(coordinator.capabilities.negotiated)

        coordinator.accept(
            ConnectionCommandCoordinator.Capabilities(
                protocolVersion = 2,
                binaryAlbumArt = true,
                fullLyricsZlib = true,
                lyricWindow = true,
                ping = true,
                transferRetry = true,
                negotiated = true
            )
        )
        assertEquals(2, coordinator.capabilities.protocolVersion)
        assertTrue(coordinator.capabilities.transferRetry)
    }
}
