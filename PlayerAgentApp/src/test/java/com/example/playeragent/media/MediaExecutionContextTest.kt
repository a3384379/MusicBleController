package com.example.playeragent.media

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MediaExecutionContextTest {
    @Test
    fun closeStopsAllBoundedExecutionLanes() {
        val context = MediaExecutionContext("TestMedia")
        val ran = CountDownLatch(3)
        context.realtime.execute(ran::countDown)
        context.foregroundIO.execute(ran::countDown)
        context.maintenance.execute(ran::countDown)

        assertTrue(ran.await(2, TimeUnit.SECONDS))
        context.close()
        assertTrue(context.awaitTermination(2_000))
        assertTrue(context.realtime.isShutdown)
        assertTrue(context.foregroundIO.isShutdown)
        assertTrue(context.maintenance.isShutdown)
    }
}
