package com.example.playeragent.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLockOrderRegressionTest {

    @Test
    fun `qrc cancellation and incremental cooldown clear do not form reverse lock edge`() {
        val lyricMonitor = Any()
        val qrcMonitor = Any()
        val qrcLocked = CountDownLatch(1)
        val lyricLocked = CountDownLatch(1)
        val cancellationGate = LyricRequestCancellationGate().apply {
            activate("song-a", 7L)
        }
        val cooldownStore = QrcCooldownStore().apply {
            put(
                "song-a",
                QrcCooldownStore.Entry(
                    retryAfterMs = Long.MAX_VALUE,
                    generation = 1L,
                    reason = "test"
                )
            )
        }
        val executor = Executors.newFixedThreadPool(2)

        val foreground = executor.submit {
            synchronized(qrcMonitor) {
                qrcLocked.countDown()
                assertTrue(lyricLocked.await(1, TimeUnit.SECONDS))
                assertFalse(cancellationGate.isCancelled("song-a", 7L))
            }
        }
        val incremental = executor.submit {
            synchronized(lyricMonitor) {
                lyricLocked.countDown()
                assertTrue(qrcLocked.await(1, TimeUnit.SECONDS))
                assertNotNull(cooldownStore.remove("song-a"))
            }
        }

        try {
            foreground.get(2, TimeUnit.SECONDS)
            incremental.get(2, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `new active request invalidates previous cancellation token atomically`() {
        val gate = LyricRequestCancellationGate()
        gate.activate("song-a", 1L)
        assertFalse(gate.isCancelled("song-a", 1L))

        gate.activate("song-b", 2L)

        assertTrue(gate.isCancelled("song-a", 1L))
        assertFalse(gate.isCancelled("song-b", 2L))
    }
}
