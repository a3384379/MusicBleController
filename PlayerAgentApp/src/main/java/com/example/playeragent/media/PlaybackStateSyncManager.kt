package com.example.playeragent.media

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class PlaybackStateSyncManager(
    private val playbackStateReader: PlaybackStateReader,
    private val sender: (String) -> Unit,
    private val logger: (String) -> Unit
) {

    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (executor != null) {
            return
        }

        logger("[PlaybackStateSync] start")
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PlaybackStateSyncThread")
        }.also { scheduledExecutor ->
            scheduledExecutor.scheduleAtFixedRate(
                {
                    pushPlaybackState()
                },
                0L,
                1L,
                TimeUnit.SECONDS
            )
        }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
        logger("[PlaybackStateSync] stop")
    }

    private fun pushPlaybackState() {
        try {
            val state = playbackStateReader.readPlaybackState()
            sender(state.toString())
        } catch (exception: Exception) {
            logger("[PlaybackStateSync] push failed: ${exception.message}")
        }
    }
}
