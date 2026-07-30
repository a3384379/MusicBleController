package com.example.controllerapp.protocol

import com.example.controllerapp.model.LyricLine

object LyricTimeline {
    fun currentLinePosition(
        lines: List<LyricLine>,
        positionMs: Long,
        serverLineIndex: Int = -1
    ): Int {
        if (lines.isEmpty()) return -1
        val byTime = lines.indexOfLast { it.timeMs <= positionMs }
        val serverPosition = lines.indexOfFirst { it.index == serverLineIndex }
        return when {
            serverPosition >= 0 &&
                lines[serverPosition].timeMs <= positionMs + SERVER_LEAD_TOLERANCE_MS ->
                serverPosition
            byTime >= 0 -> byTime
            else -> 0
        }
    }

    fun currentWordPosition(line: LyricLine, positionMs: Long): Int =
        line.words.indexOfLast { positionMs >= it.startMs }.coerceAtLeast(0)

    private const val SERVER_LEAD_TOLERANCE_MS = 1_000L
}
