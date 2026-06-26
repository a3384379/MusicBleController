package com.example.playeragent.ble

import com.example.playeragent.media.PlaybackStateDiff
import com.example.playeragent.media.PlaybackStateDiffType
import com.example.playeragent.media.PlaybackStateSnapshot
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class PlaybackStateBuffer(
    private val logger: (String) -> Unit,
    private val flushDelayMs: Long = DEFAULT_FLUSH_DELAY_MS,
    private val flush: (JSONObject, PlaybackStateSnapshot, PlaybackStateDiff, String, Int) -> Boolean
) {
    private val lock = Any()
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PlaybackStateBufferThread")
        }

    private var pending: PendingPlaybackState? = null
    private var scheduledFlush: ScheduledFuture<*>? = null

    fun offer(
        source: JSONObject,
        snapshot: PlaybackStateSnapshot,
        diff: PlaybackStateDiff
    ): Boolean {
        if (isImmediate(diff)) {
            flushPending("before_immediate")
            logger(
                "[PlaybackBuffer] flush reason=immediate_${diff.reason} coalesce=0 " +
                    "positionMs=${diff.positionMs} lineIndex=${diff.lineIndex}"
            )
            return flush(source, snapshot, diff, "immediate_${diff.reason}", 0)
        }

        synchronized(lock) {
            val existing = pending
            val count = (existing?.coalesceCount ?: 0) + 1
            pending = PendingPlaybackState(
                source = JSONObject(source.toString()),
                snapshot = snapshot,
                diff = diff,
                coalesceCount = count
            )
            if (existing != null) {
                logger(
                    "[PlaybackBuffer] coalesce count=$count reason=${diff.reason} " +
                        "positionMs=${diff.positionMs} lastPositionMs=${diff.lastPositionMs} " +
                        "lineIndex=${diff.lineIndex} lastLineIndex=${diff.lastLineIndex}"
                )
            }
            if (scheduledFlush == null || scheduledFlush?.isDone == true) {
                scheduledFlush = executor.schedule(
                    { flushPending("timer") },
                    flushDelayMs,
                    TimeUnit.MILLISECONDS
                )
            }
        }
        return true
    }

    fun flushPending(reason: String) {
        val item = synchronized(lock) {
            val value = pending
            pending = null
            scheduledFlush?.cancel(false)
            scheduledFlush = null
            value
        } ?: return

        logger(
            "[PlaybackBuffer] flush reason=$reason coalesce=${item.coalesceCount} " +
                "diffReason=${item.diff.reason} positionMs=${item.diff.positionMs} " +
                "lastPositionMs=${item.diff.lastPositionMs} lineIndex=${item.diff.lineIndex} " +
                "lastLineIndex=${item.diff.lastLineIndex}"
        )
        flush(item.source, item.snapshot, item.diff, reason, item.coalesceCount)
    }

    fun reset() {
        synchronized(lock) {
            pending = null
            scheduledFlush?.cancel(false)
            scheduledFlush = null
        }
    }

    fun shutdown() {
        reset()
        executor.shutdownNow()
    }

    private fun isImmediate(diff: PlaybackStateDiff): Boolean {
        return when (diff.type) {
            PlaybackStateDiffType.TrackChanged,
            PlaybackStateDiffType.PlaybackChanged,
            PlaybackStateDiffType.PositionJump,
            PlaybackStateDiffType.LyricChanged -> true
            else -> false
        }
    }

    private data class PendingPlaybackState(
        val source: JSONObject,
        val snapshot: PlaybackStateSnapshot,
        val diff: PlaybackStateDiff,
        val coalesceCount: Int
    )

    private companion object {
        private const val DEFAULT_FLUSH_DELAY_MS = 150L
    }
}
