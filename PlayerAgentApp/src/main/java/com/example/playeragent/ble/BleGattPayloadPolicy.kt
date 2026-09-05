package com.example.playeragent.ble

/**
 * GATT attribute values are limited to 512 bytes even when Android reports the
 * maximum ATT MTU of 517 (517 - 3 would otherwise incorrectly yield 514).
 */
internal object BleGattPayloadPolicy {
    private const val ATT_HEADER_BYTES = 3
    const val MAX_ATTRIBUTE_VALUE_BYTES = 512

    fun maximumNotificationPayload(mtu: Int): Int {
        return (mtu - ATT_HEADER_BYTES)
            .coerceAtLeast(0)
            .coerceAtMost(MAX_ATTRIBUTE_VALUE_BYTES)
    }
}
