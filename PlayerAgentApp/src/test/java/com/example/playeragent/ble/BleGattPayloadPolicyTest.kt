package com.example.playeragent.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleGattPayloadPolicyTest {
    @Test
    fun maximumAndroidMtuDoesNotExceedGattAttributeLimit() {
        assertEquals(512, BleGattPayloadPolicy.maximumNotificationPayload(517))
    }

    @Test
    fun regularMtuKeepsAttPayloadSize() {
        assertEquals(244, BleGattPayloadPolicy.maximumNotificationPayload(247))
        assertEquals(20, BleGattPayloadPolicy.maximumNotificationPayload(23))
    }

    @Test
    fun invalidMtuCannotProduceNegativePayload() {
        assertEquals(0, BleGattPayloadPolicy.maximumNotificationPayload(2))
    }
}
