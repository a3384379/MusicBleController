package com.example.controllerapp.ui

internal data class LyricViewportItem(
    val linePosition: Int,
    val centerPx: Int
)

internal data class LyricTapResult(
    val selectedLinePosition: Int,
    val seekPositionMs: Long? = null
)

internal object LyricBrowsePolicy {
    fun nearestLine(
        items: List<LyricViewportItem>,
        viewportCenterPx: Int
    ): Int? = items.minByOrNull { item ->
        kotlin.math.abs(item.centerPx - viewportCenterPx)
    }?.linePosition

    fun onLineTapped(
        browsing: Boolean,
        selectedLinePosition: Int?,
        tappedLinePosition: Int,
        lineTimeMs: Long,
        connected: Boolean
    ): LyricTapResult {
        val confirmsCurrentSelection = browsing &&
            selectedLinePosition == tappedLinePosition &&
            connected
        return LyricTapResult(
            selectedLinePosition = tappedLinePosition,
            seekPositionMs = lineTimeMs.takeIf { confirmsCurrentSelection }
        )
    }
}
