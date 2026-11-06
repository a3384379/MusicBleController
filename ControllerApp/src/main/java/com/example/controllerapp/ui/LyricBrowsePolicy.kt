package com.example.controllerapp.ui

internal data class LyricViewportItem(
    val linePosition: Int,
    val topPx: Int,
    val bottomPx: Int
)

internal data class LyricTapResult(
    val selectedLineId: Int,
    val seekPositionMs: Long? = null
)

internal object LyricBrowsePolicy {
    private const val RELEASE_GUARD_MS = 250L

    fun canActivate(isScrolling: Boolean, lastScrollEndMs: Long, nowMs: Long): Boolean =
        !isScrolling && (lastScrollEndMs == Long.MIN_VALUE ||
            (nowMs >= lastScrollEndMs && nowMs - lastScrollEndMs >= RELEASE_GUARD_MS))

    fun nearestLine(
        items: List<LyricViewportItem>,
        viewportCenterPx: Int
    ): Int? = items.minWithOrNull(
        compareBy<LyricViewportItem> { item ->
            when {
                viewportCenterPx < item.topPx -> item.topPx.toLong() - viewportCenterPx
                viewportCenterPx > item.bottomPx -> viewportCenterPx.toLong() - item.bottomPx
                else -> 0L
            }
        }.thenBy { item ->
            kotlin.math.abs((item.topPx.toLong() + item.bottomPx) / 2L - viewportCenterPx)
        }.thenBy { it.linePosition }
    )?.linePosition

    fun onLineTapped(
        browsing: Boolean,
        selectedLineId: Int?,
        tappedLineId: Int,
        lineTimeMs: Long,
        connected: Boolean
    ): LyricTapResult {
        val confirmsCurrentSelection = browsing &&
            selectedLineId == tappedLineId &&
            connected
        return LyricTapResult(
            selectedLineId = tappedLineId,
            seekPositionMs = lineTimeMs.takeIf { confirmsCurrentSelection }
        )
    }
}
