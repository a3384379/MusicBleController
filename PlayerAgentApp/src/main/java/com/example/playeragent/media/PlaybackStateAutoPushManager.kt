package com.example.playeragent.media

import com.example.playeragent.logging.LogConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class PlaybackStateAutoPushManager(
    private val playbackStateReader: PlaybackStateReader,
    private val sendLine: (String) -> Unit,
    private val logger: (String) -> Unit
) {

    private var executor: ScheduledExecutorService? = null
    private var lastStateText: String? = null

    fun start() {
        if (executor != null) {
            logger("[PlaybackStateAutoPush] already running")
            return
        }

        lastStateText = null
        logger("[PlaybackStateAutoPush] start")

        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PlaybackStateAutoPushThread")
        }.also { scheduledExecutor ->
            scheduledExecutor.scheduleAtFixedRate(
                {
                    pushIfChanged()
                },
                0L,
                1000L,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
        lastStateText = null
        logger("[PlaybackStateAutoPush] stop")
    }

    private fun pushIfChanged() {
        try {
            val state = playbackStateReader.readPlaybackState()
            val stateText = state.toString()

            if (stateText != lastStateText) {
                sendLine(stateText)
                lastStateText = stateText
                if (LogConfig.DEBUG_VERBOSE_LOG) {
                    logger("[PlaybackStateAutoPush] pushed playbackState")
                }
            }
        } catch (exception: Exception) {
            logger("[PlaybackStateAutoPush] push failed: ${exception.message}")
        }
    }
}
