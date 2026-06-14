package com.example.playeragent.logging

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object LogBuffer {

    private val entries = ArrayDeque<String>()
    private val timestampFormat = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss.SSS",
        Locale.US
    )

    @Synchronized
    fun append(message: String) {
        val singleLineMessage = message
            .replace("\r\n", " | ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .let(::truncateMessage)
        entries.addLast(
            "[${timestampFormat.format(Date())}] $singleLineMessage"
        )
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
    }

    @Synchronized
    fun getRecentLogs(limit: Int): List<String> {
        val safeLimit = limit.coerceIn(0, MAX_REQUEST_LIMIT)
        if (safeLimit == 0 || entries.isEmpty()) {
            return emptyList()
        }
        return entries.toList().takeLast(safeLimit)
    }

    @Synchronized
    fun getAllLogs(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun truncateMessage(message: String): String {
        if (message.length <= MAX_MESSAGE_LENGTH) {
            return message
        }
        return message.take(MAX_MESSAGE_LENGTH - TRUNCATED_SUFFIX.length) +
            TRUNCATED_SUFFIX
    }

    private const val MAX_ENTRIES = 300
    private const val MAX_REQUEST_LIMIT = 100
    private const val MAX_MESSAGE_LENGTH = 300
    private const val TRUNCATED_SUFFIX = "...<truncated>"
}
