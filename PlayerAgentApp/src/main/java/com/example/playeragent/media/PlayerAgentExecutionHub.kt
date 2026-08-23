package com.example.playeragent.media

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Service-scoped bounded execution lanes for Sony PlayerAgent work. */
class PlayerAgentExecutionHub(
    threadNamePrefix: String = "PlayerAgent"
) : Closeable {
    private val foregroundIndex = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    val realtime: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "$threadNamePrefix-Realtime")
    }
    val foregroundIO: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "$threadNamePrefix-ForegroundIO-${foregroundIndex.incrementAndGet()}")
    }
    val scheduled: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "$threadNamePrefix-Scheduled")
    }
    val maintenance: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "$threadNamePrefix-Maintenance").apply {
            priority = Thread.MIN_PRIORITY
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executors().forEach(ExecutorService::shutdownNow)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_WAIT_MS)
        executors().forEach { executor ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0L) {
                runCatching { executor.awaitTermination(remaining, TimeUnit.NANOSECONDS) }
            }
        }
    }

    fun isShutdown(): Boolean = executors().all(ExecutorService::isShutdown)

    private fun executors(): List<ExecutorService> =
        listOf(realtime, foregroundIO, scheduled, maintenance)

    companion object {
        private const val SHUTDOWN_WAIT_MS = 1_500L
    }
}
