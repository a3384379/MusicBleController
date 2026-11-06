package com.example.playeragent.history

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap

/** One invocation's calendar rules, independent of SQLite's native process timezone. */
internal class PlaybackStatsCalendar(timeZone: TimeZone, private val locale: Locale) {
    private val zone = timeZone.clone() as TimeZone
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { this.timeZone = zone }

    fun rangeBounds(range: StatsRange, nowMs: Long): Pair<Long, Long> {
        if (range == StatsRange.ALL) return 0L to Long.MAX_VALUE
        val start = calendar(nowMs)
        when (range) {
            StatsRange.WEEK -> {
                while (start.get(Calendar.DAY_OF_WEEK) != start.firstDayOfWeek) {
                    start.add(Calendar.DAY_OF_MONTH, -1)
                }
            }
            StatsRange.MONTH -> start.set(Calendar.DAY_OF_MONTH, 1)
            else -> Unit
        }
        moveToDayStart(start)
        val end = start.clone() as Calendar
        when (range) {
            StatsRange.TODAY -> end.add(Calendar.DAY_OF_MONTH, 1)
            StatsRange.WEEK -> end.add(Calendar.DAY_OF_MONTH, 7)
            StatsRange.MONTH -> end.add(Calendar.MONTH, 1)
            StatsRange.ALL -> Unit
        }
        moveToDayStart(end)
        return start.timeInMillis to end.timeInMillis
    }

    fun dailyQuery(occupiedDays: List<PlaybackStatsTimeBucket>, rangeStart: Long, rangeEnd: Long): SupportSQLiteQuery {
        if (occupiedDays.isEmpty()) {
            return SimpleSQLiteQuery(
                "SELECT '' AS dateKey, 0 AS listenedMs, 0 AS playCount WHERE 0"
            )
        }
        val boundaries = TreeMap<Long, LocalDay>()
        for (bucket in occupiedDays) {
            for (day in days(bucket.firstStartedAt, bucket.lastStartedAt, rangeStart, rangeEnd)) {
                boundaries[day.startAt] = day
            }
        }
        // VALUES has neither the compound-SELECT 500-term limit nor the 999-bind
        // limit. All literals below come from our calendar, never external text.
        // The existing startedAt index lets each calendar day probe its own range.
        val sql = buildString {
            append("WITH day_boundaries(dateKey, startAt, endAt) AS (VALUES ")
            var separator = ""
            for (day in boundaries.values) {
                append(separator)
                append("('").append(day.dateKey).append("',")
                append(day.startAt).append(',').append(day.endAt).append(')')
                separator = ","
            }
            append(
                ") SELECT d.dateKey AS dateKey, SUM(s.listenedMs) AS listenedMs, " +
                    "SUM(CASE WHEN s.countedPlay THEN 1 ELSE 0 END) AS playCount " +
                    "FROM day_boundaries d JOIN play_sessions s " +
                    "ON s.startedAt >= d.startAt AND s.startedAt < d.endAt " +
                    "GROUP BY d.dateKey ORDER BY MIN(d.startAt) ASC"
            )
        }
        return SimpleSQLiteQuery(sql)
    }

    fun days(firstAt: Long, lastAt: Long, rangeStart: Long, rangeEnd: Long): Sequence<LocalDay> = sequence {
        var current = calendar(firstAt)
        moveToDayStart(current)
        while (current.timeInMillis <= lastAt && current.timeInMillis < rangeEnd) {
            val start = current.timeInMillis
            val next = (current.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            if (next.timeInMillis > start) moveToDayStart(next)
            // ALL's exclusive end is Long.MAX_VALUE. Avoid wrapping a Calendar
            // addition near that endpoint into an endless sequence.
            val end = if (next.timeInMillis > start) minOf(next.timeInMillis, rangeEnd) else rangeEnd
            val clippedStart = maxOf(start, rangeStart)
            if (clippedStart < end) yield(LocalDay(dateFormat.format(start), clippedStart, end))
            if (end == rangeEnd) break
            current = next
        }
    }

    private fun calendar(timeMs: Long): Calendar = GregorianCalendar(zone, locale).apply { timeInMillis = timeMs }

    private fun moveToDayStart(value: Calendar) {
        val targetDate = dateFormat.format(value.timeInMillis)
        value.set(Calendar.HOUR_OF_DAY, 0)
        value.set(Calendar.MINUTE, 0)
        value.set(Calendar.SECOND, 0)
        value.set(Calendar.MILLISECOND, 0)
        val candidate = value.timeInMillis
        if (dateFormat.format(candidate - 1L) != targetDate) return
        // Some zones repeat midnight. Calendar selects the later occurrence;
        // find the first instant of the date so the earlier hour is not lost.
        var low = candidate - HOUR_MS
        while (dateFormat.format(low) == targetDate) low -= HOUR_MS
        var high = candidate
        while (high - low > 1L) {
            val middle = low + (high - low) / 2L
            if (dateFormat.format(middle) == targetDate) high = middle else low = middle
        }
        value.timeInMillis = high
    }

    data class LocalDay(val dateKey: String, val startAt: Long, val endAt: Long)

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
