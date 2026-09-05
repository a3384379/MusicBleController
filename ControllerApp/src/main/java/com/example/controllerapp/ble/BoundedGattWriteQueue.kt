package com.example.controllerapp.ble

import java.util.ArrayDeque

class BoundedGattWriteQueue(private val capacity: Int) {
    private val values = ArrayDeque<ByteArray>()

    init {
        require(capacity > 0)
    }

    fun offer(value: ByteArray): ByteArray? {
        val dropped = if (values.size >= capacity) values.removeFirst() else null
        values.addLast(value.copyOf())
        return dropped
    }

    fun poll(): ByteArray? = if (values.isEmpty()) null else values.removeFirst()

    fun clear() = values.clear()

    fun size(): Int = values.size
}
