package com.example.playeragent.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleGattServerManagerPolicyTest {
    @Test
    fun playbackPollingSlowsOnlyAfterPausedStateIsKnown() {
        assertEquals(1_000L, BleGattServerManager.autoPushPollIntervalMs(null))
        assertEquals(1_000L, BleGattServerManager.autoPushPollIntervalMs(true))
        assertEquals(5_000L, BleGattServerManager.autoPushPollIntervalMs(false))
    }
}
