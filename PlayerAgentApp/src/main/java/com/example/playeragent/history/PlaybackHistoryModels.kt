package com.example.playeragent.history

data class FastPlaybackSnapshot(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String,
    val playing: Boolean,
    val stopped: Boolean,
    val positionMs: Long,
    val durationMs: Long
)

internal object PlaybackHistorySamplingPolicy {
    const val PLAYING_INTERVAL_MS = 5_000L
    const val PAUSED_INTERVAL_MS = 30_000L
    const val IDLE_INTERVAL_MS = 10_000L

    fun intervalMs(
        snapshot: FastPlaybackSnapshot?,
        hasActiveSession: Boolean
    ): Long {
        return when {
            snapshot == null || snapshot.title.isBlank() ->
                if (hasActiveSession) PLAYING_INTERVAL_MS else IDLE_INTERVAL_MS
            snapshot.playing -> PLAYING_INTERVAL_MS
            snapshot.stopped -> IDLE_INTERVAL_MS
            else -> PAUSED_INTERVAL_MS
        }
    }
}

enum class StatsRange {
    TODAY,
    WEEK,
    MONTH,
    ALL
}

data class PlaybackHistoryMonitorStatus(
    val running: Boolean = false,
    val activeSessionId: Long? = null,
    val activeTitle: String = "",
    val activeArtist: String = "",
    val activeListenedMs: Long = 0L,
    val activeTrackKey: String = "",
    val lastUpdatedAt: Long = 0L
)

data class HistoryStatusSnapshot(
    val monitorStatus: PlaybackHistoryMonitorStatus,
    val totalTracks: Int,
    val totalSessions: Int,
    val lastSessionId: Long,
    val todayListenMs: Long,
    val databasePath: String
)

data class HistorySessionRow(
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

data class TopTrack(
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkId: String?,
    val listenedMs: Long,
    val playCount: Int,
    val completedCount: Int,
    val skippedCount: Int
)

data class TopArtist(
    val artist: String,
    val listenedMs: Long,
    val playCount: Int,
    val trackCount: Int
)

data class DailyListenStat(
    val dateKey: String,
    val listenedMs: Long,
    val playCount: Int
)

data class PlaybackStatsSummary(
    val rangeStart: Long,
    val rangeEnd: Long,
    val totalListenMs: Long,
    val playCount: Int,
    val uniqueTrackCount: Int,
    val completedCount: Int,
    val skippedCount: Int,
    val completionRate: Double,
    val skipRate: Double,
    val topTracks: List<TopTrack>,
    val topArtists: List<TopArtist>,
    val dailyTrend: List<DailyListenStat>
)
