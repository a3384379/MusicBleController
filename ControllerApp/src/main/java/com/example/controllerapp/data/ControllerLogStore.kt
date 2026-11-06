package com.example.controllerapp.data

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ControllerLogStore(context: Context) {
    private val thread = HandlerThread("Controller-Log").apply { start() }
    private val handler = Handler(thread.looper)
    private val core = BoundedLogCore(
        File(context.filesDir, "logs"), File(context.cacheDir, "log-snapshots"),
        // One delayed drain lets ordinary arrivals share a disk write; the core
        // bounds the queue and does not schedule another task while it is pending.
        { task -> handler.postDelayed(task, DRAIN_DELAY_MS) },
        { thread.quitSafely() }, onLine = ::notifyListeners
    )
    private val listeners = LinkedHashSet<(String) -> Unit>()
    private val notifying = ThreadLocal<Boolean>()

    fun append(message: String) = appendLine(message, false)
    fun appendImportant(message: String) = appendLine(message, true)

    private fun appendLine(message: String, important: Boolean) {
        val bounded = if (message.length > 8192) message.take(8192) + " [truncated]" else message
        val line = "${DATE_FORMAT.get()!!.format(Date())} $bounded"
        if (!core.append(line, important, notifying.get() != true)) return
        try { Log.i("ControllerApp", line) } catch (_: Exception) { }
    }

    private fun notifyListeners(line: String) {
        val copy = synchronized(listeners) { listeners.toList() }
        notifying.set(true)
        try {
            copy.forEach { listener -> try { listener(line) } catch (_: Exception) { } }
        } finally { notifying.remove() }
    }

    fun addListener(listener: (String) -> Unit) { synchronized(listeners) { listeners += listener } }
    fun removeListener(listener: (String) -> Unit) { synchronized(listeners) { listeners -= listener } }
    fun clear(done: (Boolean) -> Unit = {}) = core.clear(done)
    fun export(done: (File?, String?) -> Unit) = core.export(done)
    fun clearSnapshots(done: (Boolean) -> Unit) = core.clearSnapshots(done)
    fun close() = core.close()
    /** null until close finishes; false means at least one write remained unreported or scheduling failed. */
    fun closeSucceeded(): Boolean? = core.closeSucceeded()
    fun awaitClosed(timeoutMs: Long): Boolean {
        if (Thread.currentThread() === thread) return false
        val start = android.os.SystemClock.elapsedRealtime()
        if (!core.awaitClosed(timeoutMs)) return false
        val left = (timeoutMs - (android.os.SystemClock.elapsedRealtime() - start)).coerceAtLeast(0)
        if (left > 0) thread.join(left)
        return !thread.isAlive
    }

    companion object {
        private const val DRAIN_DELAY_MS = 350L
        private val DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }
    }
}
