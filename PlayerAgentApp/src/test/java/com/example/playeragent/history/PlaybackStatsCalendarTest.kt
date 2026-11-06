package com.example.playeragent.history

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

class PlaybackStatsCalendarTest {
    @Test fun todayUsesNaturalDayAcrossHourAndHalfHourDstTransitions() {
        listOf(
            Triple("America/New_York", "2024-03-10", 23.0),
            Triple("America/New_York", "2024-11-03", 25.0),
            Triple("Australia/Lord_Howe", "2024-10-06", 23.5),
            Triple("Australia/Lord_Howe", "2024-04-07", 24.5)
        ).forEach { (zoneName, dateText, hours) ->
            val zone = ZoneId.of(zoneName)
            val date = LocalDate.parse(dateText)
            val now = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            val bounds = PlaybackStatsCalendar(TimeZone.getTimeZone(zone), Locale.US)
                .rangeBounds(StatsRange.TODAY, now)
            assertEquals(date.atStartOfDay(zone).toInstant().toEpochMilli(), bounds.first)
            assertEquals(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), bounds.second)
            assertEquals(hours, (bounds.second - bounds.first) / 3_600_000.0, 0.0)
        }
    }

    @Test fun repeatedAndSkippedMidnightIncludeEveryOccurrenceOfTheLocalDate() {
        listOf("America/Havana" to "2024-11-03", "America/Sao_Paulo" to "2018-11-04")
            .forEach { (zoneName, dateText) ->
                val zone = ZoneId.of(zoneName)
                val date = LocalDate.parse(dateText)
                val expectedStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val expectedEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val calculator = PlaybackStatsCalendar(TimeZone.getTimeZone(zone), Locale.US)
                val bounds = calculator.rangeBounds(StatsRange.TODAY, expectedStart + 12 * HOUR)
                assertEquals(zoneName, expectedStart to expectedEnd, bounds)
                val days = calculator.days(expectedStart, expectedEnd - 1L, expectedStart, expectedEnd).toList()
                assertEquals(1, days.size)
                assertEquals(dateText, days.single().dateKey)
                assertEquals(expectedStart, days.single().startAt)
                assertEquals(expectedEnd, days.single().endAt)
            }
    }

    @Test fun weekRetainsLocaleFirstDayWhileAllDayLabelsRemainGregorian() {
        val zone = ZoneId.of("Europe/Paris")
        val now = ZonedDateTime.of(2024, 3, 31, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val us = PlaybackStatsCalendar(TimeZone.getTimeZone(zone), Locale.US).rangeBounds(StatsRange.WEEK, now)
        val france = PlaybackStatsCalendar(TimeZone.getTimeZone(zone), Locale.FRANCE).rangeBounds(StatsRange.WEEK, now)
        assertEquals(LocalDate.of(2024, 3, 31).atStartOfDay(zone).toInstant().toEpochMilli(), us.first)
        assertEquals(LocalDate.of(2024, 4, 7).atStartOfDay(zone).toInstant().toEpochMilli(), us.second)
        assertEquals(LocalDate.of(2024, 3, 25).atStartOfDay(zone).toInstant().toEpochMilli(), france.first)
        assertEquals(LocalDate.of(2024, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli(), france.second)
        listOf(Locale("th", "TH"), Locale("ja", "JP", "JP")).forEach { locale ->
            val calendar = PlaybackStatsCalendar(TimeZone.getTimeZone(zone), locale)
            val bounds = calendar.rangeBounds(StatsRange.MONTH, now)
            assertEquals(LocalDate.of(2024, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli(), bounds.first)
            assertEquals(LocalDate.of(2024, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli(), bounds.second)
            assertEquals("2024-03-31", calendar.days(now, now, 0, Long.MAX_VALUE).single().dateKey)
        }
    }

    @Test fun leapMonthBoundsAndAllExclusiveBoundsStayUnchanged() {
        val calendar = PlaybackStatsCalendar(TimeZone.getTimeZone("UTC"), Locale.US)
        val month = calendar.rangeBounds(StatsRange.MONTH, millis("2024-02-29T12:00:00Z"))
        assertEquals(millis("2024-02-01T00:00:00Z") to millis("2024-03-01T00:00:00Z"), month)
        assertEquals(0L to Long.MAX_VALUE, calendar.rangeBounds(StatsRange.ALL, Long.MAX_VALUE))
    }

    @Test fun dailyBoundariesFollowCapturedZoneAfterTheMutableInputAndDefaultChange() {
        val original = TimeZone.getDefault()
        val supplied = TimeZone.getTimeZone("Asia/Kathmandu")
        val calendar = PlaybackStatsCalendar(supplied, Locale.US)
        try {
            supplied.rawOffset = 0
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val first = millis("2024-03-10T18:14:59Z")
            val last = millis("2024-03-10T18:15:00Z")
            val days = calendar.days(first, last, 0L, Long.MAX_VALUE).toList()
            assertEquals(listOf("2024-03-10", "2024-03-11"), days.map { it.dateKey })
            assertEquals(millis("2024-03-10T18:15:00Z"), days[0].endAt)
            assertEquals(days[0].endAt, days[1].startAt)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun allClipsItsFirstLocalDayToEpochInsteadOfIncludingNegativeSessions() {
        listOf("Asia/Shanghai", "America/New_York").forEach { name ->
            val calendar = PlaybackStatsCalendar(TimeZone.getTimeZone(name), Locale.US)
            val days = calendar.days(0, 0, 0, Long.MAX_VALUE).toList()
            assertEquals(1, days.size)
            assertEquals(name, 0L, days.single().startAt)
            assertTrue(days.single().endAt > 0L)
        }
    }

    @Test fun sparseMillionYearHistoryBuildsOnlyOccupiedLocalDays() {
        val future = LocalDate.of(2_000_000, 1, 1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val query = PlaybackStatsCalendar(TimeZone.getTimeZone("UTC"), Locale.US)
            .dailyQuery(listOf(PlaybackStatsTimeBucket(0, 0), PlaybackStatsTimeBucket(future, future)), 0, Long.MAX_VALUE)
        assertTrue(query.sql.length < 1_000)
        assertEquals(2, Regex("\\('[0-9-]+',").findAll(query.sql).count())
        assertTrue(query.sql.contains("2000000-01-01"))
        assertTrue(query.sql.contains("ORDER BY MIN(d.startAt)"))
    }

    @Test fun occupiedUtcDaysSharingALocalDateAreDeduplicatedBeforeTheSqlJoin() {
        val first = millis("2024-03-10T12:00:00Z")
        val second = millis("2024-03-11T01:00:00Z")
        val query = PlaybackStatsCalendar(TimeZone.getTimeZone("Pacific/Kiritimati"), Locale.US)
            .dailyQuery(listOf(PlaybackStatsTimeBucket(first, first), PlaybackStatsTimeBucket(second, second)), 0, Long.MAX_VALUE)
        assertEquals(1, Regex("\\('2024-03-11',").findAll(query.sql).count())
    }

    @Test fun dateSequenceStopsAtTheExclusiveLongEndpointWithoutCalendarWraparound() {
        val calendar = PlaybackStatsCalendar(TimeZone.getTimeZone("UTC"), Locale.US)
        val days = calendar.days(Long.MAX_VALUE - 1L, Long.MAX_VALUE - 1L, 0L, Long.MAX_VALUE).toList()
        assertEquals(1, days.size)
        assertTrue(days.single().startAt < Long.MAX_VALUE - 1L)
        assertEquals(Long.MAX_VALUE, days.single().endAt)
    }

    private fun millis(value: String) = Instant.parse(value).toEpochMilli()

    private companion object { const val HOUR = 3_600_000L }
}
