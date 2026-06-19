package com.example.playeragent.history

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PlaybackStatsCalculator(
    private val db: PlaybackHistoryDatabase
) {
    fun summary(range: StatsRange, nowMs: Long = System.currentTimeMillis()): PlaybackStatsSummary {
        val (rangeStart, rangeEnd) = rangeBounds(range, nowMs)
        val sessions = db.playSessionDao().getSessionsInRange(rangeStart, rangeEnd)
        val trackMap = sessions.mapNotNull { db.trackDao().getTrack(it.trackKey) }
            .associateBy { it.trackKey }

        val totalListenMs = sessions.sumOf { it.listenedMs }
        val playCount = sessions.count { it.countedPlay }
        val completedCount = sessions.count { it.completed }
        val skippedCount = sessions.count { it.skipped }
        val uniqueTrackCount = sessions.map { it.trackKey }.distinct().size

        val topTracks = sessions.groupBy { it.trackKey }
            .mapNotNull { (trackKey, grouped) ->
                val track = trackMap[trackKey] ?: return@mapNotNull null
                TopTrack(
                    trackKey = trackKey,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    artworkId = track.artworkId,
                    listenedMs = grouped.sumOf { it.listenedMs },
                    playCount = grouped.count { it.countedPlay },
                    completedCount = grouped.count { it.completed },
                    skippedCount = grouped.count { it.skipped }
                )
            }
            .sortedWith(compareByDescending<TopTrack> { it.listenedMs }.thenByDescending { it.playCount })
            .take(TOP_LIMIT)

        val topArtists = sessions.groupBy { trackMap[it.trackKey]?.artist.orEmpty().ifBlank { "未知歌手" } }
            .map { (artist, grouped) ->
                TopArtist(
                    artist = artist,
                    listenedMs = grouped.sumOf { it.listenedMs },
                    playCount = grouped.count { it.countedPlay },
                    trackCount = grouped.map { it.trackKey }.distinct().size
                )
            }
            .sortedWith(compareByDescending<TopArtist> { it.listenedMs }.thenByDescending { it.playCount })
            .take(TOP_LIMIT)

        val dailyTrend = sessions.groupBy { dateKey(it.startedAt) }
            .map { (dateKey, grouped) ->
                DailyListenStat(
                    dateKey = dateKey,
                    listenedMs = grouped.sumOf { it.listenedMs },
                    playCount = grouped.count { it.countedPlay }
                )
            }
            .sortedBy { it.dateKey }

        return PlaybackStatsSummary(
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            totalListenMs = totalListenMs,
            playCount = playCount,
            uniqueTrackCount = uniqueTrackCount,
            completedCount = completedCount,
            skippedCount = skippedCount,
            completionRate = if (playCount > 0) completedCount.toDouble() / playCount else 0.0,
            skipRate = if (playCount > 0) skippedCount.toDouble() / playCount else 0.0,
            topTracks = topTracks,
            topArtists = topArtists,
            dailyTrend = dailyTrend
        )
    }

    private fun rangeBounds(range: StatsRange, nowMs: Long): Pair<Long, Long> {
        if (range == StatsRange.ALL) {
            return 0L to Long.MAX_VALUE
        }
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (range) {
            StatsRange.TODAY -> Unit
            StatsRange.WEEK -> {
                val firstDay = calendar.firstDayOfWeek
                while (calendar.get(Calendar.DAY_OF_WEEK) != firstDay) {
                    calendar.add(Calendar.DAY_OF_MONTH, -1)
                }
            }
            StatsRange.MONTH -> calendar.set(Calendar.DAY_OF_MONTH, 1)
            StatsRange.ALL -> Unit
        }
        val start = calendar.timeInMillis
        val end = Calendar.getInstance().apply {
            timeInMillis = start
            when (range) {
                StatsRange.TODAY -> add(Calendar.DAY_OF_MONTH, 1)
                StatsRange.WEEK -> add(Calendar.DAY_OF_MONTH, 7)
                StatsRange.MONTH -> add(Calendar.MONTH, 1)
                StatsRange.ALL -> timeInMillis = Long.MAX_VALUE
            }
        }.timeInMillis
        return start to end
    }

    private fun dateKey(timeMs: Long): String {
        return DATE_FORMAT.get().format(timeMs)
    }

    private companion object {
        private const val TOP_LIMIT = 10
        private val DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("yyyy-MM-dd", Locale.US)
            }
        }
    }
}
