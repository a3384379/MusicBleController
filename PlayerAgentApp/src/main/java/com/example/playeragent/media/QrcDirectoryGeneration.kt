package com.example.playeragent.media

import java.util.concurrent.atomic.AtomicLong

object QrcDirectoryGeneration {
    private val generation = AtomicLong(0L)

    fun current(): Long = generation.get()

    fun markChanged(
        groupId: String,
        event: String,
        logger: (String) -> Unit
    ): Long {
        val value = generation.incrementAndGet()
        logger("[QrcGeneration] changed generation=$value groupId=$groupId event=$event")
        return value
    }
}
