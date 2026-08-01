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

    fun lineProgress(line: LyricLine, positionMs: Long): Float {
        val duration = line.durationMs.coerceAtLeast(1L)
        return ((positionMs - line.timeMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Returns a 0..1 highlight amount for every UTF-16 character in the original line.
     *
     * QQ QRC normally supplies word timing. Distributing each word duration across its
     * characters avoids the old whole-word jump while preserving spaces and punctuation from
     * [LyricLine.text]. Lines without word timing gracefully fall back to their line duration.
     */
    fun characterProgresses(line: LyricLine, positionMs: Long): FloatArray {
        if (line.text.isEmpty()) return FloatArray(0)
        if (line.words.isEmpty()) {
            return distributedCharacterProgress(line.text.length, lineProgress(line, positionMs))
        }

        val resolvedWordStarts = line.words.map { word ->
            resolveWordStartMs(line, word.startMs)
        }
        if (resolvedWordStarts.any { it == null }) {
            return distributedCharacterProgress(line.text.length, lineProgress(line, positionMs))
        }
        val wordStarts = resolvedWordStarts.filterNotNull()

        val result = FloatArray(line.text.length)
        var searchFrom = 0
        var mappedWords = 0
        var previousEnd = 0
        var previousProgress = 0f

        line.words.forEachIndexed { index, word ->
            if (word.text.isEmpty() || searchFrom >= line.text.length) return@forEachIndexed
            val wordStart = line.text.indexOf(word.text, startIndex = searchFrom)
            if (wordStart < 0) return@forEachIndexed
            val wordEnd = (wordStart + word.text.length).coerceAtMost(line.text.length)
            val durationMs = effectiveWordDurationMs(line, index, wordStarts)
            val wordProgress = (
                (positionMs - wordStarts[index]).toFloat() / durationMs.toFloat()
            ).coerceIn(0f, 1f)

            val separatorProgress = if (mappedWords == 0) wordProgress else previousProgress
            for (characterIndex in previousEnd until wordStart) {
                result[characterIndex] = separatorProgress
            }
            val characterSweep = wordProgress * (wordEnd - wordStart)
            for (characterIndex in wordStart until wordEnd) {
                result[characterIndex] = (
                    characterSweep - (characterIndex - wordStart)
                ).coerceIn(0f, 1f)
            }

            mappedWords += 1
            previousEnd = wordEnd
            previousProgress = wordProgress
            searchFrom = wordEnd
        }

        if (mappedWords == 0) {
            return distributedCharacterProgress(line.text.length, lineProgress(line, positionMs))
        }
        for (characterIndex in previousEnd until line.text.length) {
            result[characterIndex] = previousProgress
        }
        return result
    }

    private fun effectiveWordDurationMs(
        line: LyricLine,
        wordPosition: Int,
        wordStarts: List<Long>
    ): Long {
        val word = line.words[wordPosition]
        if (word.durationMs > 0L) return word.durationMs
        val wordStartMs = wordStarts[wordPosition]
        val nextStartMs = wordStarts.getOrNull(wordPosition + 1)
        if (nextStartMs != null && nextStartMs > wordStartMs) {
            return nextStartMs - wordStartMs
        }
        val lineEndMs = line.timeMs + line.durationMs
        return (lineEndMs - wordStartMs).coerceAtLeast(1L)
    }

    private fun resolveWordStartMs(line: LyricLine, rawStartMs: Long): Long? {
        val lineEndMs = line.timeMs + line.durationMs.coerceAtLeast(1L)
        if (rawStartMs in (line.timeMs - WORD_START_EARLY_TOLERANCE_MS)..
            (lineEndMs + WORD_START_LATE_TOLERANCE_MS)
        ) {
            return rawStartMs
        }
        if (rawStartMs >= 0L &&
            rawStartMs <= line.durationMs + WORD_START_LATE_TOLERANCE_MS
        ) {
            return line.timeMs + rawStartMs
        }
        return null
    }

    private fun distributedCharacterProgress(length: Int, progress: Float): FloatArray {
        val characterSweep = progress.coerceIn(0f, 1f) * length
        return FloatArray(length) { index ->
            (characterSweep - index).coerceIn(0f, 1f)
        }
    }

    private const val SERVER_LEAD_TOLERANCE_MS = 1_000L
    private const val WORD_START_EARLY_TOLERANCE_MS = 120L
    private const val WORD_START_LATE_TOLERANCE_MS = 2_000L
}
