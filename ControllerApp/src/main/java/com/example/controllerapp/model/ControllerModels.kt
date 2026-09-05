package com.example.controllerapp.model

import android.graphics.Bitmap

enum class AppExperienceMode {
    DAILY,
    DEBUG
}

enum class PlaybackPerformanceMode {
    AUTOMATIC,
    SMOOTH,
    POWER_SAVING
}

enum class LyricDisplayMode {
    ORIGINAL,
    ORIGINAL_TRANSLATION,
    ORIGINAL_ROMANIZATION,
    ALL;

    val showsTranslation: Boolean
        get() = this == ORIGINAL_TRANSLATION || this == ALL

    val showsRomanization: Boolean
        get() = this == ORIGINAL_ROMANIZATION || this == ALL
}

data class ControllerSettings(
    val experienceMode: AppExperienceMode = AppExperienceMode.DAILY,
    val performanceMode: PlaybackPerformanceMode = PlaybackPerformanceMode.AUTOMATIC,
    val autoReconnect: Boolean = true,
    val lyricOffsetMs: Long = 600L,
    val lyricDisplayMode: LyricDisplayMode = LyricDisplayMode.ORIGINAL_TRANSLATION,
    val artworkDisplaySizeDp: Int = 260,
    val artworkEnhancementEnabled: Boolean = true
)

enum class ConnectionPhase {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    DISCOVERING,
    SUBSCRIBING,
    CONNECTED,
    RECONNECTING
}

enum class ConnectionHealth {
    DISCONNECTED,
    HEALTHY,
    SUSPECT,
    STALE
}

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val health: ConnectionHealth = ConnectionHealth.DISCONNECTED,
    val deviceName: String = "Sony PlayerAgent",
    val deviceAddress: String = "",
    val mtu: Int = 23,
    val characteristicReady: Boolean = false,
    val generation: Long = 0L,
    val reconnectAttempt: Int = 0,
    val lastReconnectReason: String = "",
    val lastNotifyElapsedMs: Long = 0L,
    val serverProtocolVersion: Int = 1,
    val serverSupportsV2: Boolean = false
) {
    val connected: Boolean
        get() = phase == ConnectionPhase.CONNECTED && characteristicReady
}

data class PlaybackState(
    val trackId: String = "",
    val generation: Long = 0L,
    val title: String = "-",
    val artist: String = "-",
    val album: String = "-",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volumeCurrent: Int = 0,
    val volumeMax: Int = 0,
    val receivedAtElapsedMs: Long = 0L,
    val restoredSnapshot: Boolean = false
)

data class LyricWord(
    val index: Int,
    val startMs: Long,
    val durationMs: Long,
    val text: String
)

data class LyricLine(
    val index: Int,
    val timeMs: Long,
    val durationMs: Long,
    val text: String,
    val translation: String? = null,
    val romanization: String? = null,
    val words: List<LyricWord> = emptyList()
)

enum class LyricLoadingStage {
    IDLE,
    WAITING_QQ_QRC,
    WINDOW_READY,
    FULL_LYRICS,
    READY,
    FAILED
}

data class LyricsState(
    val trackId: String = "",
    val generation: Long = 0L,
    val currentText: String = "",
    val windowLines: List<LyricLine> = emptyList(),
    val partialFullLines: List<LyricLine> = emptyList(),
    val fullLines: List<LyricLine> = emptyList(),
    val isFinal: Boolean = false,
    val currentLineIndex: Int = -1,
    val currentWordIndex: Int = -1,
    val currentWordSequence: Long = -1L,
    val currentWordPositionMs: Long = -1L,
    val loadingStage: LyricLoadingStage = LyricLoadingStage.IDLE,
    val receivedChunks: Int = 0,
    val expectedChunks: Int = 0,
    val transferId: String = "",
    val protocolFormat: String = "",
    val retryCount: Int = 0,
    val failureReason: String = ""
) {
    /**
     * The best lyrics currently available to the player UI.
     *
     * Keeping the three sources separate is important: a late five-line window must never
     * replace a completed full-lyrics transfer. Callers that only need display data can keep
     * using [lines], while the full-lyrics screen can inspect [isFinal] explicitly.
     */
    val lines: List<LyricLine>
        get() = when {
            fullLines.isNotEmpty() -> fullLines
            partialFullLines.isNotEmpty() -> partialFullLines
            else -> windowLines
        }
}

enum class ArtworkQuality(val rank: Int) {
    PLACEHOLDER(0),
    PREVIEW(1),
    HQ(2),
    ENHANCED(3)
}

enum class ArtworkLoadingStage {
    IDLE,
    PREVIEW,
    PREVIEW_READY,
    HQ,
    HQ_READY,
    FAILED
}

data class ArtworkState(
    val artworkId: String = "",
    val bitmap: Bitmap? = null,
    val quality: ArtworkQuality = ArtworkQuality.PLACEHOLDER,
    val loadingStage: ArtworkLoadingStage = ArtworkLoadingStage.IDLE,
    val receivedChunks: Int = 0,
    val expectedChunks: Int = 0,
    val restoredSnapshot: Boolean = false,
    val cacheRequiresRefresh: Boolean = false,
    val failureReason: String = "",
    val enhancementMessage: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArtworkState) return false
        return artworkId == other.artworkId &&
            bitmap === other.bitmap &&
            quality == other.quality &&
            loadingStage == other.loadingStage &&
            receivedChunks == other.receivedChunks &&
            expectedChunks == other.expectedChunks &&
            restoredSnapshot == other.restoredSnapshot &&
            cacheRequiresRefresh == other.cacheRequiresRefresh &&
            failureReason == other.failureReason &&
            enhancementMessage == other.enhancementMessage
    }

    override fun hashCode(): Int {
        var result = artworkId.hashCode()
        result = 31 * result + System.identityHashCode(bitmap)
        result = 31 * result + quality.hashCode()
        return result
    }
}

data class LyricDiagnosticState(
    val status: String = "",
    val reason: String = "",
    val suggestion: String = "",
    val details: Map<String, String> = emptyMap()
)

data class DiagnosticsState(
    val recentLogs: List<String> = emptyList(),
    val sonyLogs: String = "",
    val mediaFieldDump: String = "",
    val lyricDiagnostic: LyricDiagnosticState = LyricDiagnosticState(),
    val remoteTransferInProgress: Boolean = false,
    val lastIssue: String = "",
    val selfHealingActions: List<String> = emptyList()
)

data class PlaybackHistorySession(
    val sessionId: Long,
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val listenedMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
    val countedPlay: Boolean
)

data class PlaybackTopTrack(
    val trackKey: String,
    val title: String,
    val artist: String,
    val listenedMs: Long,
    val playCount: Int
)

data class PlaybackTopArtist(
    val artist: String,
    val listenedMs: Long,
    val playCount: Int
)

data class DailyListenStat(
    val dateKey: String,
    val listenedMs: Long,
    val playCount: Int
)

data class PlaybackStats(
    val range: String,
    val totalListenMs: Long,
    val playCount: Int,
    val uniqueTrackCount: Int,
    val completionRate: Double,
    val skipRate: Double,
    val topTracks: List<PlaybackTopTrack>,
    val topArtists: List<PlaybackTopArtist>,
    val dailyTrend: List<DailyListenStat>
)

data class HistoryState(
    val sessions: List<PlaybackHistorySession> = emptyList(),
    val stats: Map<String, PlaybackStats> = emptyMap(),
    val loading: Boolean = false,
    val status: String = "",
    val hasMore: Boolean = true
)
