package com.example.controllerapp.data

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ControllerLogStore(context: Context) {
    private val directory = File(context.filesDir, "logs").apply { mkdirs() }
    val currentFile = File(directory, "controller-current.log")
    private val thread = HandlerThread("Controller-Log").apply { start() }
    private val handler = Handler(thread.looper)
    private val pending = ArrayDeque<String>()
    private val listeners = LinkedHashSet<(String) -> Unit>()
    private var writer: BufferedWriter? = null
    private var flushScheduled = false

    fun append(message: String) {
        val line = "${timestamp()} $message"
        Log.i("ControllerApp", line)
        synchronized(listeners) {
            listeners.toList().forEach { it(line) }
        }
        handler.post {
            pending.addLast(line)
            if (!flushScheduled) {
                flushScheduled = true
                handler.postDelayed(::flushOnThread, 350L)
            }
        }
    }

    fun addListener(listener: (String) -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    fun removeListener(listener: (String) -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    fun clear() {
        handler.post {
            pending.clear()
            writer?.close()
            writer = null
            currentFile.delete()
        }
    }

    fun close() {
        handler.post {
            flushOnThread()
            writer?.close()
            writer = null
            thread.quitSafely()
        }
    }

    private fun flushOnThread() {
        flushScheduled = false
        if (pending.isEmpty()) return
        val output = writer ?: BufferedWriter(FileWriter(currentFile, true)).also {
            writer = it
        }
        while (pending.isNotEmpty()) {
            output.append(pending.removeFirst())
            output.newLine()
        }
        output.flush()
    }

    private fun timestamp(): String = DATE_FORMAT.get()!!.format(Date())

    companion object {
        private val DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }
    }
}
