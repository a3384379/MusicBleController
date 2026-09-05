package com.example.playeragent.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTransferCoordinatorsTest {
    @Test
    fun advertisingCapacityAllowsExactlyTwoControllers() {
        assertTrue(MultiControllerPolicy.hasConnectionCapacity(0))
        assertTrue(MultiControllerPolicy.hasConnectionCapacity(1))
        assertFalse(MultiControllerPolicy.hasConnectionCapacity(2))
        assertFalse(MultiControllerPolicy.hasConnectionCapacity(3))
    }

    @Test
    fun oneFailingControllerIsIsolatedWithoutRebuildingHealthyConnection() {
        assertTrue(
            MultiControllerPolicy.shouldIsolateOnlyFailingControllers(
                failingCount = 1,
                subscribedCount = 2
            )
        )
        assertFalse(
            MultiControllerPolicy.shouldIsolateOnlyFailingControllers(
                failingCount = 2,
                subscribedCount = 2
            )
        )
        assertFalse(
            MultiControllerPolicy.shouldIsolateOnlyFailingControllers(
                failingCount = 1,
                subscribedCount = 1
            )
        )
    }

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
            expiresAtMs = 2_000,
            ownerAddress = "ios"
        )
        lyrics.retain(lyricTransfer)
        artwork.reset()

        assertEquals(lyricTransfer, lyrics.retained("ios", "lyrics-a"))
        assertNull(lyrics.retained("android", "lyrics-a"))
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
                expiresAtMs = 99,
                ownerAddress = "ios"
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
                expiresAtMs = 500,
                ownerAddress = "android"
            ),
            nowMs = 100
        )

        assertNull(coordinator.retained("ios", "old-transfer"))
        assertNull(coordinator.retained("ios", "current-transfer"))
        assertEquals(
            "current",
            coordinator.retained("android", "current-transfer")?.trackId
        )
    }

    @Test
    fun retryCleanupOnlyRemovesDisconnectedController() {
        val lyrics = LyricsTransferCoordinator()
        val packet = BleNotifyQueue.Packet("chunk", byteArrayOf(1), 0)
        listOf("ios", "android").forEach { owner ->
            lyrics.retain(
                FullLyricsBinaryTransfer(
                    trackId = "same-track",
                    transferId = "shared-id",
                    generation = 3,
                    start = packet,
                    chunks = listOf(packet),
                    end = packet,
                    expiresAtMs = 5_000,
                    ownerAddress = owner
                )
            )
        }

        lyrics.resetAddress("android")

        assertNull(lyrics.retained("android", "shared-id"))
        assertEquals("same-track", lyrics.retained("ios", "shared-id")?.trackId)
    }

    @Test
    fun capabilityFallbackOnlyAppliesToCurrentSubscriptionGeneration() {
        val coordinator = ConnectionCommandCoordinator()
        val first = coordinator.beginNegotiation("ios", nowMs = 1_000L)
        val second = coordinator.beginNegotiation("ios", nowMs = 1_100L)

        assertFalse(coordinator.useLegacyIfCurrent("ios", first))
        assertTrue(coordinator.useLegacyIfCurrent("ios", second))
        assertTrue(coordinator.capabilities("ios").negotiated)

        coordinator.accept(
            "ios",
            ConnectionCommandCoordinator.Capabilities(
                protocolVersion = 2,
                binaryAlbumArt = true,
                fullLyricsZlib = true,
                lyricWindow = true,
                ping = true,
                clockSyncV1 = true,
                transferRetry = true,
                negotiated = true
            )
        )
        assertEquals(2, coordinator.capabilities("ios").protocolVersion)
        assertTrue(coordinator.capabilities("ios").transferRetry)
        assertTrue(coordinator.capabilities("ios").clockSyncV1)
        assertFalse(coordinator.capabilities("android").negotiated)
    }

    @Test
    fun receivedCapabilitiesPreventFallbackWhileAckIsWaitingInWorkerQueue() {
        val coordinator = ConnectionCommandCoordinator()
        val generation = coordinator.beginNegotiation("ios", nowMs = 1_000L)

        coordinator.accept(
            "ios",
            ConnectionCommandCoordinator.Capabilities(
                protocolVersion = 2,
                binaryAlbumArt = true,
                ping = true,
                negotiated = true
            )
        )

        assertFalse(coordinator.useLegacyIfCurrent("ios", generation))
        assertEquals(2, coordinator.capabilities("ios").protocolVersion)
        assertTrue(coordinator.capabilities("ios").binaryAlbumArt)
    }

    @Test
    fun disconnectingOneClientDoesNotResetAnotherClient() {
        val coordinator = ConnectionCommandCoordinator()
        coordinator.beginNegotiation("ios", nowMs = 1_000L)
        coordinator.accept(
            "ios",
            ConnectionCommandCoordinator.Capabilities(protocolVersion = 2, ping = true)
        )
        coordinator.beginNegotiation("android", nowMs = 1_100L)
        coordinator.accept(
            "android",
            ConnectionCommandCoordinator.Capabilities(
                protocolVersion = 2,
                fullLyricsZlib = true
            )
        )

        coordinator.remove("android")

        assertTrue(coordinator.capabilities("ios").ping)
        assertTrue(coordinator.capabilities("ios").negotiated)
        assertFalse(coordinator.capabilities("android").negotiated)
    }

    @Test
    fun duplicateToggleFromAnotherControllerIsSuppressedWithinWindow() {
        val gate = MultiControllerCommandGate(duplicateWindowMs = 300L)

        assertTrue(gate.shouldExecute("PLAY_PAUSE", "ios", nowMs = 1_000L))
        assertFalse(gate.shouldExecute("PLAY_PAUSE", "android", nowMs = 1_200L))
        assertTrue(gate.shouldExecute("PLAY_PAUSE", "ios", nowMs = 1_250L))
        assertTrue(gate.shouldExecute("PLAY_PAUSE", "android", nowMs = 1_700L))
    }
}
