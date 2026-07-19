package com.example.playeragent.media

import java.util.concurrent.atomic.AtomicReference

/**
 * Lock-free request identity used by QRC cancellation callbacks.
 *
 * QrcLyricManager performs foreground lookups while holding its own monitor.
 * Cancellation checks must therefore never enter LyricManager's monitor or a
 * foreground lookup can deadlock with incremental lyric application.
 */
internal class LyricRequestCancellationGate {
    private val active = AtomicReference(State(songKey = null, taskId = 0L))

    fun activate(songKey: String, taskId: Long) {
        active.set(State(songKey = songKey, taskId = taskId))
    }

    fun cancelAll() {
        active.set(State(songKey = null, taskId = Long.MIN_VALUE))
    }

    fun isCancelled(songKey: String, taskId: Long): Boolean {
        val snapshot = active.get()
        return snapshot.songKey != songKey || snapshot.taskId != taskId
    }

    fun activeSongKey(): String? = active.get().songKey

    private data class State(
        val songKey: String?,
        val taskId: Long
    )
}
