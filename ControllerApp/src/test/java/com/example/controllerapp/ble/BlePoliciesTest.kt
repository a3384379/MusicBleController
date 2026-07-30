package com.example.controllerapp.ble

import com.example.controllerapp.service.ReconnectPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePoliciesTest {
    @Test
    fun `write queue stays FIFO and drops only oldest pending command`() {
        val queue = BoundedGattWriteQueue(2)
        assertNull(queue.offer(byteArrayOf(1)))
        assertNull(queue.offer(byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(1), queue.offer(byteArrayOf(3)))
        assertEquals(2, queue.size())
        assertArrayEquals(byteArrayOf(2), queue.poll())
        assertArrayEquals(byteArrayOf(3), queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `reconnect backoff is bounded and periodically forces scan`() {
        assertEquals(1_000L, ReconnectPolicy.delayMs(1))
        assertEquals(2_000L, ReconnectPolicy.delayMs(2))
        assertEquals(16_000L, ReconnectPolicy.delayMs(5))
        assertEquals(30_000L, ReconnectPolicy.delayMs(6))
        assertEquals(30_000L, ReconnectPolicy.delayMs(100))
        assertTrue(ReconnectPolicy.shouldForceScan(1, ""))
        assertFalse(ReconnectPolicy.shouldForceScan(2, "AA:BB"))
        assertTrue(ReconnectPolicy.shouldForceScan(3, "AA:BB"))
    }
}
