package com.example.playeragent.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlayerAgentExecutionHubTest {

    @Test
    fun lanesUseBoundedNamedThreadsAndCloseTogether() {
        val hub = PlayerAgentExecutionHub("HubTest")
        val names = mutableListOf<String>()
        val latch = CountDownLatch(5)
        listOf(
            hub.realtime,
            hub.foregroundIO,
            hub.scheduled,
            hub.currentWord,
            hub.maintenance
        ).forEach { executor ->
            executor.execute {
                synchronized(names) { names += Thread.currentThread().name }
                latch.countDown()
            }
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(5, names.size)
        assertTrue(names.all { it.startsWith("HubTest-") })
        assertTrue(names.any { it == "HubTest-CurrentWord" })

        hub.close()
        assertTrue(hub.isShutdown())
    }
}
