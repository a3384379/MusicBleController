package com.example.playeragent.history

import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable

class PlaybackStatsCalculator(
    private val db: PlaybackHistoryDatabase
) {
    fun summary(
        range: StatsRange,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): PlaybackStatsSummary {
        val calendar = PlaybackStatsCalendar(timeZone, locale)
        val (rangeStart, rangeEnd) = calendar.rangeBounds(range, nowMs)
        // Keep all five queries, including metadata joins, in one read snapshot.
        // Query count stays at five. Allocations follow occupied dates and the two
        // bounded leaderboards; individual play-session entities are never loaded.
        return db.runInTransaction(Callable {
            val dao = db.playSessionDao()
            val totals = dao.statsTotals(rangeStart, rangeEnd)
            val topTracks = dao.statsTopTracks(rangeStart, rangeEnd, TOP_LIMIT)
            val topArtists = dao.statsTopArtists(rangeStart, rangeEnd, TOP_LIMIT, BLANK_CHARACTERS)
            val occupiedDays = dao.statsOccupiedUtcDays(rangeStart, rangeEnd)
            val dailyTrend = dao.statsDailyTrend(
                calendar.dailyQuery(occupiedDays, rangeStart, rangeEnd)
            )
            PlaybackStatsSummary(
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                totalListenMs = totals.totalListenMs,
                playCount = totals.playCount,
                uniqueTrackCount = totals.uniqueTrackCount,
                completedCount = totals.completedCount,
                skippedCount = totals.skippedCount,
                completionRate = outcomeRate(totals.completedCount, totals.endedSessionCount),
                skipRate = outcomeRate(totals.skippedCount, totals.endedSessionCount),
                topTracks = topTracks,
                topArtists = topArtists,
                dailyTrend = dailyTrend
            )
        })
    }

    private companion object {
        const val TOP_LIMIT = 10
        // SQLite TRIM's default only recognizes ASCII spaces. Match Kotlin's old
        // String.ifBlank behavior, including tabs, NBSP and full-width spaces.
        val BLANK_CHARACTERS = (0..Char.MAX_VALUE.code).asSequence()
            .map { it.toChar() }.filter { it.isWhitespace() }.joinToString("")
    }
}

internal fun outcomeRate(outcomes: Int, endedSessions: Int): Double =
    if (endedSessions > 0) outcomes.toDouble() / endedSessions else 0.0
