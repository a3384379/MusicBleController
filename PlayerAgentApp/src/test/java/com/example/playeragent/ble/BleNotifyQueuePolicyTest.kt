package com.example.playeragent.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleNotifyQueuePolicyTest {
    @Test
    fun realtimePreemptsAtNextPacketBoundary() {
        assertTrue(
            BleNotifyQueue.shouldYieldForPriorities(
                BleNotifyQueue.Priority.P2_BULK,
                BleNotifyQueue.Priority.P0_REALTIME,
                packetsSinceYield = 1
            )
        )
        assertTrue(
            BleNotifyQueue.shouldYieldForPriorities(
                BleNotifyQueue.Priority.P3_BACKGROUND,
                BleNotifyQueue.Priority.P1_INTERACTIVE,
                packetsSinceYield = 1
            )
        )
    }

    @Test
    fun bulkYieldsToInteractiveEveryFourPackets() {
        assertFalse(
            BleNotifyQueue.shouldYieldForPriorities(
                BleNotifyQueue.Priority.P2_BULK,
                BleNotifyQueue.Priority.P1_INTERACTIVE,
                packetsSinceYield = 3
            )
        )
        assertTrue(
            BleNotifyQueue.shouldYieldForPriorities(
                BleNotifyQueue.Priority.P2_BULK,
                BleNotifyQueue.Priority.P1_INTERACTIVE,
                packetsSinceYield = 4
            )
        )
        assertFalse(
            BleNotifyQueue.shouldYieldForPriorities(
                BleNotifyQueue.Priority.P2_BULK,
                BleNotifyQueue.Priority.P2_BULK,
                packetsSinceYield = 10
            )
        )
    }

    @Test
    fun equalPriorityBulkTransfersRotateBetweenControllers() {
        assertFalse(
            BleNotifyQueue.shouldYieldToPeerAtSamePriority(
                BleNotifyQueue.Priority.P2_BULK,
                packetsSinceYield = 3,
                anotherDeviceWaiting = true
            )
        )
        assertTrue(
            BleNotifyQueue.shouldYieldToPeerAtSamePriority(
                BleNotifyQueue.Priority.P2_BULK,
                packetsSinceYield = 4,
                anotherDeviceWaiting = true
            )
        )
        assertTrue(
            BleNotifyQueue.shouldYieldToPeerAtSamePriority(
                BleNotifyQueue.Priority.P3_BACKGROUND,
                packetsSinceYield = 1,
                anotherDeviceWaiting = true
            )
        )
        assertFalse(
            BleNotifyQueue.shouldYieldToPeerAtSamePriority(
                BleNotifyQueue.Priority.P0_REALTIME,
                packetsSinceYield = 10,
                anotherDeviceWaiting = true
            )
        )
    }

    @Test
    fun interleavedStateCoalescingIsScopedByController() {
        val iosKey = BleNotifyQueue.interleavedPacketKey("ios", "currentWord")
        val androidKey = BleNotifyQueue.interleavedPacketKey("android", "currentWord")

        assertEquals("ios|currentWord", iosKey)
        assertEquals("android|currentWord", androidKey)
        assertFalse(iosKey == androidKey)
    }

    @Test
    fun commandResponseQuietWindowNeverReturnsNegativeDelay() {
        assertEquals(25L, BleNotifyQueue.remainingQuietDelayMs(1_025L, 1_000L))
        assertEquals(0L, BleNotifyQueue.remainingQuietDelayMs(1_000L, 1_000L))
        assertEquals(0L, BleNotifyQueue.remainingQuietDelayMs(999L, 1_000L))
    }

    @Test
    fun commandResponseQuietWindowIsPerDeviceAndDoesNotExtendActiveBurst() {
        val windows = CommandResponseQuietWindows(25L)

        assertEquals(1_025L, windows.reserve("ios", 1_000L))
        assertEquals(1_025L, windows.reserve("ios", 1_010L))
        assertEquals(
            15L,
            windows.remainingDelayMs("ios", "albumArtBinaryChunk", 1_010L)
        )
        assertEquals(
            0L,
            windows.remainingDelayMs("android", "albumArtBinaryChunk", 1_010L)
        )
        assertEquals(1_050L, windows.reserve("ios", 1_025L))
    }

    @Test
    fun commandResponseQuietWindowNeverDelaysRealtimeState() {
        val windows = CommandResponseQuietWindows(25L)
        windows.reserve("ios", 1_000L)

        listOf(
            "trackInfo",
            "trackInfoChunk",
            "playbackState",
            "currentWord",
            "lyricWindowChunk"
        ).forEach {
            assertEquals(0L, windows.remainingDelayMs("ios", it, 1_010L))
        }
        listOf(
            "albumArtBinaryChunk",
            "albumArtChunk",
            "fullLyricsBinaryChunk",
            "fullLyricsChunk",
            "lyricSecondaryPart",
            "logChunk",
            "mediaFieldDumpChunk",
            "historyPayloadChunk"
        ).forEach {
            assertTrue(BleNotifyQueue.isCommandResponseSensitivePacket(it))
            assertEquals(15L, windows.remainingDelayMs("ios", it, 1_010L))
        }
    }

    @Test
    fun commandResponseQuietWindowClearsPerDeviceAndGlobally() {
        val windows = CommandResponseQuietWindows(25L)
        windows.reserve("ios", 1_000L)
        windows.reserve("android", 1_000L)

        windows.remove("ios")
        assertEquals(
            0L,
            windows.remainingDelayMs("ios", "albumArtBinaryChunk", 1_010L)
        )
        assertEquals(
            15L,
            windows.remainingDelayMs("android", "albumArtBinaryChunk", 1_010L)
        )

        windows.clear()
        assertEquals(
            0L,
            windows.remainingDelayMs("android", "albumArtBinaryChunk", 1_010L)
        )
    }

    @Test
    fun everyLongTransferChecksRealtimePacketsAfterEachChunk() {
        listOf(
            "albumArt",
            "fullLyrics",
            "lyricSecondary",
            "remoteLog",
            "mediaFieldDump",
            "qrcDump",
            "playHistory",
            "playStats"
        ).forEach { type ->
            assertEquals(1, BleNotifyQueue.realtimeInterleaveIntervalFor(type))
        }
        assertEquals(0, BleNotifyQueue.realtimeInterleaveIntervalFor("playbackState"))
    }

    @Test
    fun onlyReplaceableStateMessagesAreCoalesced() {
        listOf(
            "playbackState",
            "currentWord",
            "volumeState",
            "albumArtOffer"
        ).forEach { type ->
            assertTrue(BleNotifyQueue.shouldCoalesceShortType(type))
        }
        listOf(
            "trackInfo",
            "controlResponse",
            "pong",
            "fullLyricsBinaryStart",
            "albumArtBinaryStart"
        ).forEach { type ->
            assertFalse(BleNotifyQueue.shouldCoalesceShortType(type))
        }
    }

    @Test
    fun terminalCallbackIsDeferredAndDispatchedExactlyOnce() {
        val gate = BleQueueTerminalCallbackGate()
        val pending = mutableListOf<() -> Unit>()
        var callbackCount = 0

        assertTrue(
            gate.dispatch(
                post = { pending += it },
                callback = { callbackCount += 1 }
            )
        )
        assertEquals(0, callbackCount)
        assertEquals(1, pending.size)
        assertFalse(
            gate.dispatch(
                post = { pending += it },
                callback = { callbackCount += 100 }
            )
        )

        pending.single().invoke()
        assertEquals(1, callbackCount)
    }
}
