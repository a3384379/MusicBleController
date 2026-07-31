package com.example.controllerapp.protocol

import com.example.controllerapp.model.LyricLine
import com.example.controllerapp.model.LyricLoadingStage
import com.example.controllerapp.model.LyricsState

/**
 * Pure reducer for the three lyric sources exposed by Sony.
 *
 * A short window is latency-first data, a partial list is transfer progress, and a final list is
 * authoritative. Keeping this ordering in one reducer prevents a late BLE window from replacing
 * an already completed full-lyrics response.
 */
object LyricsPublicationPolicy {
    fun publishWindow(
        state: LyricsState,
        trackId: String,
        generation: Long,
        lines: List<LyricLine>,
        currentLineIndex: Int
    ): LyricsState {
        val sorted = lines.sortedBy(LyricLine::index)
        return state.copy(
            trackId = trackId,
            generation = generation,
            windowLines = sorted,
            currentLineIndex = currentLineIndex,
            loadingStage = if (state.isFinal) {
                LyricLoadingStage.READY
            } else {
                LyricLoadingStage.WINDOW_READY
            },
            receivedChunks = if (state.isFinal) state.receivedChunks else sorted.size,
            expectedChunks = if (state.isFinal) state.expectedChunks else sorted.size,
            failureReason = ""
        )
    }

    fun publishPartial(
        state: LyricsState,
        trackId: String,
        generation: Long,
        lines: List<LyricLine>
    ): LyricsState {
        if (state.isFinal) return state
        return state.copy(
            trackId = trackId,
            generation = generation,
            partialFullLines = lines.sortedBy(LyricLine::index),
            loadingStage = LyricLoadingStage.FULL_LYRICS
        )
    }

    fun publishFull(
        state: LyricsState,
        trackId: String,
        generation: Long,
        lines: List<LyricLine>,
        currentLineIndex: Int,
        format: String
    ): LyricsState {
        val sorted = lines.sortedBy(LyricLine::index)
        return state.copy(
            trackId = trackId,
            generation = generation,
            partialFullLines = emptyList(),
            fullLines = sorted,
            isFinal = true,
            currentLineIndex = currentLineIndex,
            loadingStage = LyricLoadingStage.READY,
            receivedChunks = sorted.size,
            expectedChunks = sorted.size,
            transferId = "",
            protocolFormat = format,
            failureReason = ""
        )
    }
}
