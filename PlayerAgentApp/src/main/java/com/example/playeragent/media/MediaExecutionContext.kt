package com.example.playeragent.media

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded execution lanes shared by the BLE media coordinators.
 *
 * Real-time control/reconnect work is isolated from foreground media I/O, while
 * history and diagnostics remain serialized on the maintenance lane.
 */
class MediaExecutionContext(
    threadNamePrefix: String = "PlayerAgent"
) : Closeable {
    private val foregroundThreadIndex = AtomicInteger(0)

    val realtime: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "$threadNamePrefix-Realtime")
    }
    val foregroundIO: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(
            runnable,
            "$threadNamePrefix-ForegroundIO-${foregroundThreadIndex.incrementAndGet()}"
        )
    }
    val maintenance: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "$threadNamePrefix-Maintenance").apply {
            priority = Thread.MIN_PRIORITY
        }
    }

    override fun close() {
        listOf(realtime, foregroundIO, maintenance).forEach(ExecutorService::shutdownNow)
    }

    fun awaitTermination(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        return listOf(realtime, foregroundIO, maintenance).all { executor ->
            val remaining = deadline - System.nanoTime()
            remaining > 0L && executor.awaitTermination(
                remaining,
                TimeUnit.NANOSECONDS
            )
        }
    }
}
