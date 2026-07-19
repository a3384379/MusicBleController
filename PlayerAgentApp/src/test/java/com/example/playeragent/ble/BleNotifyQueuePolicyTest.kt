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
    fun bulkYieldsToInteractiveEveryFourPacketsAndKeepsFifoPeers() {
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
    fun commandResponseQuietWindowNeverReturnsNegativeDelay() {
        assertEquals(25L, BleNotifyQueue.remainingQuietDelayMs(1_025L, 1_000L))
        assertEquals(0L, BleNotifyQueue.remainingQuietDelayMs(1_000L, 1_000L))
        assertEquals(0L, BleNotifyQueue.remainingQuietDelayMs(999L, 1_000L))
    }
}
