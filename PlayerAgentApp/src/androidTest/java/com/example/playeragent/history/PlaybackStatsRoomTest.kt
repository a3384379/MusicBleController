package com.example.playeragent.history

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.GregorianCalendar
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Aggregates execute against Room 2.6.1 and real SQLite, never a replacement DAO. */
@RunWith(AndroidJUnit4::class)
class PlaybackStatsRoomTest {
    private lateinit var context: Context
    private lateinit var database: PlaybackHistoryDatabase
    private lateinit var queries: QueryRecorder
    private lateinit var originalTimeZone: TimeZone
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalTimeZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        if (artifactsInitialized.compareAndSet(false, true)) {
            File(context.cacheDir, "stats-queries.json").delete()
            File(context.cacheDir, "stats-benchmarks.json").delete()
            File(context.cacheDir, "stats-query-plan.json").delete()
        }
        queries = QueryRecorder()
        database = Room.inMemoryDatabaseBuilder(context, PlaybackHistoryDatabase::class.java)
            .setQueryCallback(queries.callback, DIRECT_EXECUTOR).build()
        database.openHelper.writableDatabase
        database.playSessionDao().countSessions()
    }

    @After
    fun tearDown() {
        queries.beforeBusinessQuery = null
        database.close()
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun emptyHistoryHasFiniteZeroRatesAndEmptyLists() {
        val result = measuredSummary(StatsRange.TODAY)
        assertEquals(0L, result.totalListenMs)
        assertEquals(0, result.playCount)
        assertEquals(0, result.uniqueTrackCount)
        assertEquals(0.0, result.completionRate, 0.0)
        assertEquals(0.0, result.skipRate, 0.0)
        assertTrue(result.topTracks.isEmpty())
        assertTrue(result.topArtists.isEmpty())
        assertTrue(result.dailyTrend.isEmpty())
        assertBoundedReads()
    }

    @Test
    fun allQuickSkipsUseEndedSessionsEvenWhenNoneWasCountedAsAPlay() {
        val tracks = listOf(track("a"))
        val rows = (1L..3L).map { session(it, "a", listenedMs = 100L, counted = false, skipped = true) }
        seed(tracks, rows)
        val result = assertMatchesReference(tracks, rows)
        assertEquals(0, result.playCount)
        assertEquals(3, result.skippedCount)
        assertEquals(1.0, result.skipRate, 0.0)
        assertEquals(0.0, result.completionRate, 0.0)
    }

    @Test
    fun oneCountedPlayAndTwoQuickSkipsReportTwoThirdsSkipped() {
        val tracks = listOf(track("a"))
        val rows = listOf(
            session(1L, "a", listenedMs = 35_000L, counted = true),
            session(2L, "a", listenedMs = 100L, counted = false, skipped = true),
            session(3L, "a", listenedMs = 200L, counted = false, skipped = true)
        )
        seed(tracks, rows)
        val result = assertMatchesReference(tracks, rows)
        assertEquals(1, result.playCount)
        assertEquals(2.0 / 3.0, result.skipRate, 1e-12)
        assertEquals(66.7, result.skipRate * 100.0, 0.05)
    }

    @Test
    fun allCompletedSessionsReportOneHundredPercentCompletion() {
        val tracks = listOf(track("a"), track("b"))
        val rows = (1L..4L).map { session(it, if (it % 2L == 0L) "a" else "b", completed = true) }
        seed(tracks, rows)
        val result = assertMatchesReference(tracks, rows)
        assertEquals(4, result.completedCount)
        assertEquals(1.0, result.completionRate, 0.0)
        assertEquals(0.0, result.skipRate, 0.0)
    }

    @Test
    fun openSessionContributesListenAndCountedPlayButNotEitherRate() {
        val tracks = listOf(track("a"))
        val rows = listOf(
            session(1L, "a", completed = true),
            session(2L, "a", counted = false, skipped = true),
            // Even inconsistent legacy outcome flags cannot make an open session final.
            session(3L, "a", listenedMs = 9_000L, ended = false, completed = true, skipped = true)
        )
        seed(tracks, rows)
        val result = assertMatchesReference(tracks, rows)
        assertEquals(2, result.playCount)
        assertEquals(11_000L, result.totalListenMs)
        assertEquals(1, result.completedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(0.5, result.completionRate, 0.0)
        assertEquals(0.5, result.skipRate, 0.0)
    }

    @Test
    fun onlyOpenSessionsHaveZeroOutcomeRates() {
        val tracks = listOf(track("a"))
        val rows = listOf(session(1L, "a", ended = false, completed = true, skipped = true))
        seed(tracks, rows)
        val result = assertMatchesReference(tracks, rows)
        assertEquals(1, result.playCount)
        assertEquals(0, result.completedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(0.0, result.completionRate, 0.0)
        assertEquals(0.0, result.skipRate, 0.0)
    }

    @Test
    fun shortSongOutcomesAreNotFilteredByThirtySecondCountThreshold() {
        val tracks = listOf(track("short").copy(durationMs = 2_000L))
        val rows = listOf(session(1L, "short", listenedMs = 1_500L, completed = true).copy(durationMs = 2_000L))
        seed(tracks, rows)
        val result = assertMatchesReference(tracks, rows)
        assertEquals(1, result.playCount)
        assertEquals(1.0, result.completionRate, 0.0)
        assertEquals(1_500L, result.topTracks.single().listenedMs)
    }

    @Test
    fun missingMetadataAndUnicodeBlankArtistsDoNotDropSessions() {
        val tracks = listOf(
            track("known", artist = "独立歌手"),
            track("blank", artist = " \t\u00a0\u2003 "),
            track("padded", artist = " 歌手 ")
        )
        val rows = listOf(
            session(1L, "known", listenedMs = 8_000L, completed = true),
            session(2L, "blank", listenedMs = 4_000L),
            session(3L, "orphan", listenedMs = 3_000L, counted = false, skipped = true),
            session(4L, "padded", listenedMs = 2_000L)
        )
        seed(tracks, rows)
        val result = assertMatchesReference(tracks, rows)
        val orphan = result.topTracks.single { it.trackKey == "orphan" }
        assertEquals("", orphan.title)
        assertEquals("", orphan.artist)
        assertEquals(null, orphan.artworkId)
        assertEquals(7_000L, result.topArtists.single { it.artist == "未知歌手" }.listenedMs)
        assertTrue(result.topArtists.any { it.artist == " 歌手 " })
        saveQueries("missing_metadata_and_unicode_artist", queries.snapshot())
    }

    @Test
    fun tiedTopListsHaveStableKeysAndAtMostTenItems() {
        val tracks = (0..11).map { track("key-%02d".format(Locale.US, it), artist = "artist-%02d".format(Locale.US, it)) }
        val rows = tracks.mapIndexed { index, track -> session(index + 1L, track.trackKey) }.reversed()
        seed(tracks.reversed(), rows)
        val result = assertMatchesReference(tracks, rows)
        assertEquals(tracks.take(10).map { it.trackKey }, result.topTracks.map { it.trackKey })
        assertEquals(tracks.take(10).map { it.artist }, result.topArtists.map { it.artist })
    }

    @Test
    fun naturalDayWeekMonthAndLocaleWeekStartUseHalfOpenRanges() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = localMillis(zone, "2026-11-06 12:34:56")
        val cases = listOf(
            RangeCase(StatsRange.TODAY, Locale.US, "2026-11-06 00:00:00", "2026-11-07 00:00:00"),
            RangeCase(StatsRange.WEEK, Locale.US, "2026-11-01 00:00:00", "2026-11-08 00:00:00"),
            RangeCase(StatsRange.WEEK, Locale.UK, "2026-11-02 00:00:00", "2026-11-09 00:00:00"),
            RangeCase(StatsRange.MONTH, Locale.US, "2026-11-01 00:00:00", "2026-12-01 00:00:00")
        )
        for (case in cases) {
            clear()
            val start = localMillis(zone, case.start)
            val end = localMillis(zone, case.end)
            val tracks = listOf(track("a"))
            val rows = listOf(
                session(1L, "a", startedAt = start - 1L),
                session(2L, "a", startedAt = start),
                session(3L, "a", startedAt = end - 1L),
                session(4L, "a", startedAt = end)
            )
            seed(tracks, rows)
            val result = assertMatchesReference(tracks, rows, case.range, now, zone, case.locale)
            assertEquals(start, result.rangeStart)
            assertEquals(end, result.rangeEnd)
            assertEquals(2_000L, result.totalListenMs)
            assertEquals(2, result.playCount)
        }
    }

    @Test
    fun dstDaysUseTwentyThreeTwentyFiveAndHalfHourNaturalLengths() {
        val cases = listOf(
            DstCase("America/New_York", "2026-03-08", "2026-03-09", 23.0),
            DstCase("America/New_York", "2026-11-01", "2026-11-02", 25.0),
            DstCase("Australia/Lord_Howe", "2026-10-04", "2026-10-05", 23.5),
            DstCase("Australia/Lord_Howe", "2026-04-05", "2026-04-06", 24.5)
        )
        for (case in cases) {
            clear()
            val zone = TimeZone.getTimeZone(case.zone)
            val start = localMillis(zone, "${case.day} 00:00:00")
            val end = localMillis(zone, "${case.nextDay} 00:00:00")
            val tracks = listOf(track("a"))
            val rows = listOf(
                session(1L, "a", startedAt = start - 1L),
                session(2L, "a", startedAt = start),
                session(3L, "a", startedAt = end - 1L),
                session(4L, "a", startedAt = end)
            )
            seed(tracks, rows)
            val result = assertMatchesReference(tracks, rows, StatsRange.TODAY,
                localMillis(zone, "${case.day} 12:00:00"), zone)
            assertEquals(case.hours * 3_600_000.0, (result.rangeEnd - result.rangeStart).toDouble(), 0.0)
            assertEquals(listOf(case.day), result.dailyTrend.map { it.dateKey })
            assertEquals(2_000L, result.dailyTrend.single().listenedMs)
        }
    }

    @Test
    fun perCallAndChangedDefaultTimeZonesDoNotReuseStaleDateFormatter() {
        val utc = TimeZone.getTimeZone("UTC")
        val pacific = TimeZone.getTimeZone("America/Los_Angeles")
        val tracks = listOf(track("a"))
        val rows = listOf(session(1L, "a", startedAt = localMillis(utc, "2026-11-06 00:30:00")))
        seed(tracks, rows)
        val calculator = PlaybackStatsCalculator(database)
        assertEquals("2026-11-06", calculator.summary(StatsRange.ALL, NOW, utc, Locale.US).dailyTrend.single().dateKey)
        assertEquals("2026-11-05", calculator.summary(StatsRange.ALL, NOW, pacific, Locale.US).dailyTrend.single().dateKey)
        TimeZone.setDefault(utc)
        assertEquals("2026-11-06", calculator.summary(StatsRange.ALL, NOW).dailyTrend.single().dateKey)
        TimeZone.setDefault(pacific)
        assertEquals("2026-11-05", calculator.summary(StatsRange.ALL, NOW).dailyTrend.single().dateKey)
    }

    @Test
    fun thaiLocaleKeepsGregorianDateKeysAndNaturalMonthBounds() {
        val zone = TimeZone.getTimeZone("Asia/Bangkok")
        val now = localMillis(zone, "2026-11-06 12:00:00")
        val tracks = listOf(track("a"))
        val rows = listOf(session(1L, "a", startedAt = localMillis(zone, "2026-11-01 00:00:00")))
        seed(tracks, rows)
        val result = assertMatchesReference(tracks, rows, StatsRange.MONTH, now, zone, Locale("th", "TH"))
        assertEquals(localMillis(zone, "2026-11-01 00:00:00"), result.rangeStart)
        assertEquals(localMillis(zone, "2026-12-01 00:00:00"), result.rangeEnd)
        assertEquals(listOf("2026-11-01"), result.dailyTrend.map { it.dateKey })
    }

    @Test
    fun allRangeIncludesEpochZeroAndExcludesNegativeAndLongMaxStartTimes() {
        val tracks = listOf(track("a"))
        val rows = listOf(
            session(1L, "a", startedAt = -1L), session(2L, "a", startedAt = 0L),
            session(3L, "a", startedAt = Long.MAX_VALUE)
        )
        seed(tracks, rows)
        for ((zoneId, expectedDay) in listOf("UTC" to "1970-01-01", "Asia/Shanghai" to "1970-01-01",
            "America/New_York" to "1969-12-31")) {
            val result = assertMatchesReference(tracks, rows, StatsRange.ALL, NOW, TimeZone.getTimeZone(zoneId))
            assertEquals(0L, result.rangeStart)
            assertEquals(Long.MAX_VALUE, result.rangeEnd)
            assertEquals(1_000L, result.totalListenMs)
            assertEquals(listOf(expectedDay), result.dailyTrend.map { it.dateKey })
        }
    }

    @Test
    fun sqlMatchesIndependentMemoryReferenceForMixedSessionsAcrossAllRanges() {
        val random = Random(7_919L)
        val zone = TimeZone.getTimeZone("America/New_York")
        val now = localMillis(zone, "2026-11-06 12:00:00")
        val tracks = (0..22).map { index ->
            track("track-%02d".format(Locale.US, index), artist = when (index % 5) {
                0 -> "\u00a0\t"; 1 -> "共同歌手"; else -> "Artist $index"
            })
        }
        val origin = localMillis(zone, "2026-10-01 00:00:00")
        val rows = (1L..400L).map { id ->
            session(id, "track-%02d".format(Locale.US, random.nextInt(26)),
                startedAt = origin + random.nextInt(42 * 24 * 60).toLong() * 60_000L,
                listenedMs = random.nextInt(60_000).toLong(), counted = random.nextBoolean(),
                completed = random.nextBoolean(), skipped = random.nextBoolean(),
                ended = random.nextInt(5) != 0)
        }
        seed(tracks, rows)
        StatsRange.values().forEach { range -> assertMatchesReference(tracks, rows, range, now, zone, Locale.UK) }
        saveQueries("mixed_sessions_natural_ranges", queries.snapshot())
    }

    @Test
    fun dailyValuesBeyondFiveHundredDaysUseActualSqlWithoutBindOrCompoundLimits() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val tracks = listOf(track("a"))
        val day = GregorianCalendar(zone, Locale.US).apply {
            timeInMillis = localMillis(zone, "2024-03-01 12:00:00")
        }
        val rows = (0..600).map { index ->
            session(index + 1L, "a", startedAt = day.timeInMillis).also { day.add(Calendar.DATE, 1) }
        }
        seed(tracks, rows)
        val result = assertMatchesReference(tracks, rows, StatsRange.ALL, NOW, zone)
        assertEquals(601, result.dailyTrend.size)
        assertEquals("2024-03-01", result.dailyTrend.first().dateKey)
        assertBoundedReads()
        saveQueries("daily_more_than_500_natural_days", queries.snapshot())
    }

    @Test
    fun sparseEpochToLongMaxHistoryAggregatesOnlyOccupiedDays() {
        val tracks = listOf(track("a"))
        val rows = listOf(session(1L, "a", startedAt = 0L), session(2L, "a", startedAt = Long.MAX_VALUE - 1L))
        seed(tracks, rows)
        val result = assertMatchesReference(tracks, rows, StatsRange.ALL)
        assertEquals(2_000L, result.totalListenMs)
        assertEquals(2, result.dailyTrend.size)
        saveQueries("sparse_epoch_to_long_max", queries.snapshot())
    }

    @Test
    fun concurrentWriterCannotMixTotalsMetadataAndDailyBucketsWithinOneSummary() {
        val file = File(context.cacheDir, "stats-snapshot-${System.nanoTime()}.db")
        val recorder = QueryRecorder()
        val reader = Room.databaseBuilder(context, PlaybackHistoryDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback(recorder.callback, DIRECT_EXECUTOR).build()
        val writer = Room.databaseBuilder(context, PlaybackHistoryDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        val executor = Executors.newFixedThreadPool(2)
        val reachedSecondRead = CountDownLatch(1)
        val allowSecondRead = CountDownLatch(1)
        val writerAttempted = CountDownLatch(1)
        val writerCommitted = CountDownLatch(1)
        val oldTrack = track("a", artist = "Before Artist")
        val newTrack = oldTrack.copy(title = "After Title", artist = "After Artist")
        val oldRows = listOf(session(1L, "a"))
        val newRow = session(2L, "a", listenedMs = 9_000L, completed = true)
        try {
            reader.runInTransaction { reader.trackDao().upsertTrack(oldTrack); reader.playSessionDao().insertSession(oldRows.single()) }
            writer.playSessionDao().countSessions() // Open both connections before the controlled race.
            recorder.begin()
            recorder.beforeBusinessQuery = { index ->
                if (index == 2) {
                    reachedSecondRead.countDown()
                    check(allowSecondRead.await(5L, TimeUnit.SECONDS)) { "test did not release second SELECT" }
                }
            }
            val reading = executor.submit(Callable { PlaybackStatsCalculator(reader).summary(StatsRange.ALL, NOW, UTC, Locale.US) })
            assertTrue("summary did not reach its second query", reachedSecondRead.await(5L, TimeUnit.SECONDS))
            val writing = executor.submit {
                writerAttempted.countDown()
                writer.runInTransaction {
                    writer.trackDao().upsertTrack(newTrack)
                    writer.playSessionDao().insertSession(newRow)
                }
                writerCommitted.countDown()
            }
            assertTrue(writerAttempted.await(5L, TimeUnit.SECONDS))
            // A real WAL writer may block on Room's transaction, or commit while a
            // read snapshot stays pinned. Neither may change the already-read summary.
            val committedWhilePaused = writerCommitted.await(300L, TimeUnit.MILLISECONDS)
            allowSecondRead.countDown()
            val result = reading.get(5L, TimeUnit.SECONDS)
            writing.get(5L, TimeUnit.SECONDS)
            assertSummaryEquals(reference(oldRows, listOf(oldTrack), StatsRange.ALL, NOW, UTC, Locale.US), result)
            assertTrue(recorder.businessQueries().size <= 5)
            assertTrue("summary did not start a transaction", recorder.snapshot().any { it.sql.startsWith("BEGIN", true) })
            recorder.beforeBusinessQuery = null
            recorder.end()
            assertSummaryEquals(reference(oldRows + newRow, listOf(newTrack), StatsRange.ALL, NOW, UTC, Locale.US),
                PlaybackStatsCalculator(reader).summary(StatsRange.ALL, NOW, UTC, Locale.US))
            emit(JSONObject().put("case", "concurrent_snapshot").put("writerCommittedWhilePaused", committedWhilePaused)
                .put("selectCount", recorder.businessQueries().size))
        } finally {
            allowSecondRead.countDown()
            recorder.beforeBusinessQuery = null
            executor.shutdownNow()
            executor.awaitTermination(5L, TimeUnit.SECONDS)
            writer.close()
            reader.close()
            context.deleteDatabase(file.absolutePath)
        }
    }

    @Test
    fun repeatedTrackBaselineAndOneTenHundredThousandRowsHaveMeasuredQueryBudgets() {
        val sameTrack = listOf(track("repeat"))
        val sameRows = (1L..1_000L).map { session(it, "repeat", completed = it % 2L == 0L) }
        seed(sameTrack, sameRows)
        val expected = reference(sameRows, sameTrack, StatsRange.ALL, NOW, UTC, Locale.US)
        val legacySamples = measureSamples("legacy_n_plus_one_1000_same_track", 1_000, 5, 1_001) {
            legacyNPlusOneSummary()
        }
        val aggregateSamples = measureSamples("aggregate_1000_same_track", 1_000, 5, 5) {
            PlaybackStatsCalculator(database).summary(StatsRange.ALL, NOW, UTC, Locale.US)
        }
        assertSummaryEquals(expected, legacySamples)
        assertSummaryEquals(expected, aggregateSamples)
        for (count in listOf(1_000, 10_000, 100_000)) {
            clear()
            val tracks = (0 until 128).map { track("scale-$it", artist = "Artist ${it % 17}") }
            val rows = (1L..count.toLong()).map { id ->
                session(id, "scale-${id % 128L}", startedAt = NOW - (id % 31L) * DAY_MS,
                    listenedMs = id % 20_000L + 1L, counted = id % 3L != 0L,
                    completed = id % 5L == 0L, skipped = id % 7L == 0L,
                    ended = id % 11L != 0L)
            }
            seed(tracks, rows)
            val reference = reference(rows, tracks, StatsRange.ALL, NOW, UTC, Locale.US)
            val actual = measureSamples("aggregate_multitrack_$count", count, 5, 5) {
                PlaybackStatsCalculator(database).summary(StatsRange.ALL, NOW, UTC, Locale.US)
            }
            assertSummaryEquals(reference, actual)
        }
    }

    @Test
    fun integerSumOverflowFailsExplicitlyAndDatabaseCanBeClearedAndReused() {
        val tracks = listOf(track("a"))
        seed(tracks, listOf(session(1L, "a", listenedMs = Long.MAX_VALUE), session(2L, "a", listenedMs = 1L)))
        var failure: SQLiteException? = null
        try {
            measuredSummary()
        } catch (error: SQLiteException) {
            failure = error
        }
        assertTrue("integer SUM overflow must surface as SQLiteException, found $failure",
            failure?.message?.contains("overflow", ignoreCase = true) == true)

        clear()
        val normalRows = listOf(session(3L, "a", listenedMs = 9_000L, completed = true))
        seed(tracks, normalRows)
        val recovered = assertMatchesReference(tracks, normalRows)
        assertEquals(9_000L, recovered.totalListenMs)
        assertEquals(1.0, recovered.completionRate, 0.0)
        emit(JSONObject().put("case", "integer_sum_overflow_recovery")
            .put("exceptionClass", failure?.javaClass?.name).put("message", failure?.message)
            .put("recoveredListenMs", recovered.totalListenMs))
    }

    @Test
    fun actualDailyQueryPlanUsesStartedAtIndexAndIsOutsideBusinessReadBudget() {
        val tracks = listOf(track("a"))
        val rows = (1L..1_000L).map { id -> session(id, "a", startedAt = NOW - (id % 31L) * DAY_MS) }
        seed(tracks, rows)
        assertMatchesReference(tracks, rows)
        val actualQueries = queries.snapshot()
        val businessReadCount = queries.businessQueries().size
        val daily = actualQueries.single { it.sql.trimStart().startsWith("WITH", ignoreCase = true) }
        val details = mutableListOf<String>()
        val planRows = JSONArray()
        // measuredSummary() has stopped the callback recorder. EXPLAIN is diagnostic
        // work, separate from the measured production SELECT/WITH request budget.
        database.openHelper.readableDatabase.query(
            SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${daily.sql}", daily.args.toTypedArray())
        ).use { cursor ->
            val detailColumn = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) {
                details += cursor.getString(detailColumn)
                planRows.put(JSONObject().apply {
                    cursor.columnNames.forEachIndexed { index, name -> put(name, cursor.getString(index)) }
                })
            }
        }
        assertTrue("daily query did not use the startedAt index: $details", details.any { detail ->
            detail.contains("index_play_sessions_startedAt", ignoreCase = true) ||
                (detail.contains("index", ignoreCase = true) && detail.contains("startedAt", ignoreCase = true))
        })
        assertEquals(businessReadCount, queries.businessQueries().size)
        appendArtifact("stats-query-plan.json", JSONObject().put("case", "actual_daily_query")
            .put("sql", daily.sql).put("bindArgs", JSONArray().apply { daily.args.forEach { put(it ?: JSONObject.NULL) } })
            .put("businessSelectCount", businessReadCount).put("sdkInt", Build.VERSION.SDK_INT).put("plan", planRows))
        saveQueries("query_plan_fixture_actual_queries", actualQueries)
        emit(JSONObject().put("case", "daily_started_at_index").put("businessSelectCount", businessReadCount)
            .put("details", JSONArray(details)))
    }

    private fun measuredSummary(
        range: StatsRange = StatsRange.ALL,
        now: Long = NOW,
        zone: TimeZone = UTC,
        locale: Locale = Locale.US
    ): PlaybackStatsSummary {
        queries.begin()
        return try { PlaybackStatsCalculator(database).summary(range, now, zone, locale) } finally { queries.end() }
    }

    private fun assertMatchesReference(
        tracks: List<TrackEntity>, rows: List<PlaySessionEntity>, range: StatsRange = StatsRange.ALL,
        now: Long = NOW, zone: TimeZone = UTC, locale: Locale = Locale.US
    ): PlaybackStatsSummary = measuredSummary(range, now, zone, locale).also {
        assertSummaryEquals(reference(rows, tracks, range, now, zone, locale), it)
        assertBoundedReads()
    }

    private fun assertBoundedReads() {
        val reads = queries.businessQueries()
        assertTrue("expected 1..5 aggregate SELECTs, found ${reads.size}: ${reads.map { it.sql }}", reads.size in 1..5)
        assertFalse("summary materialized every session", reads.any { it.sql.trim().matches(Regex("(?is)SELECT \\* FROM play_sessions.*")) })
    }

    private fun seed(tracks: List<TrackEntity>, rows: List<PlaySessionEntity>) {
        database.runInTransaction {
            tracks.forEach(database.trackDao()::upsertTrack)
            rows.forEach { database.playSessionDao().insertSession(it) }
        }
    }

    private fun clear() = database.runInTransaction {
        database.playSessionDao().clearSessions()
        database.trackDao().clearTracks()
    }

    /** Independent reference: no production range, date, DAO aggregate or rate helper. */
    private fun reference(
        source: List<PlaySessionEntity>, metadata: List<TrackEntity>, range: StatsRange,
        now: Long, zone: TimeZone, locale: Locale
    ): PlaybackStatsSummary {
        val bounds = referenceBounds(range, now, zone, locale)
        val rows = source.filter { it.startedAt >= bounds.first && it.startedAt < bounds.second }
        val tracks = metadata.associateBy { it.trackKey }
        val finished = rows.filter { it.endedAt != null }
        val completed = finished.count { it.completed }
        val skipped = finished.count { it.skipped }
        val topTracks = rows.groupBy { it.trackKey }.map { (key, sessions) ->
            val track = tracks[key]
            TopTrack(key, track?.title.orEmpty(), track?.artist.orEmpty(), track?.album.orEmpty(), track?.artworkId,
                sessions.sumOf { it.listenedMs }, sessions.count { it.countedPlay },
                sessions.count { it.endedAt != null && it.completed }, sessions.count { it.endedAt != null && it.skipped })
        }.sortedWith(compareByDescending<TopTrack> { it.listenedMs }.thenByDescending { it.playCount }.thenBy { it.trackKey }).take(10)
        val topArtists = rows.groupBy { tracks[it.trackKey]?.artist.orEmpty().ifBlank { "未知歌手" } }.map { (artist, sessions) ->
            TopArtist(artist, sessions.sumOf { it.listenedMs }, sessions.count { it.countedPlay }, sessions.map { it.trackKey }.distinct().size)
        }.sortedWith(compareByDescending<TopArtist> { it.listenedMs }.thenByDescending { it.playCount }.thenBy { it.artist }).take(10)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
        val daily = rows.groupBy { date.format(it.startedAt) }.map { (key, sessions) ->
            DailyListenStat(key, sessions.sumOf { it.listenedMs }, sessions.count { it.countedPlay })
        }.sortedBy { it.dateKey }
        return PlaybackStatsSummary(bounds.first, bounds.second, rows.sumOf { it.listenedMs }, rows.count { it.countedPlay },
            rows.map { it.trackKey }.distinct().size, completed, skipped,
            if (finished.isEmpty()) 0.0 else completed.toDouble() / finished.size,
            if (finished.isEmpty()) 0.0 else skipped.toDouble() / finished.size, topTracks, topArtists, daily)
    }

    private fun referenceBounds(range: StatsRange, now: Long, zone: TimeZone, locale: Locale): Pair<Long, Long> {
        if (range == StatsRange.ALL) return 0L to Long.MAX_VALUE
        val start = GregorianCalendar(zone, locale).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            when (range) {
                StatsRange.WEEK -> set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                StatsRange.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
                else -> Unit
            }
        }
        val end = (start.clone() as Calendar).apply {
            when (range) {
                StatsRange.TODAY -> add(Calendar.DATE, 1)
                StatsRange.WEEK -> add(Calendar.DATE, 7)
                StatsRange.MONTH -> add(Calendar.MONTH, 1)
                else -> Unit
            }
        }
        return start.timeInMillis to end.timeInMillis
    }

    private fun assertSummaryEquals(expected: PlaybackStatsSummary, actual: PlaybackStatsSummary) {
        assertEquals(expected.rangeStart, actual.rangeStart); assertEquals(expected.rangeEnd, actual.rangeEnd)
        assertEquals(expected.totalListenMs, actual.totalListenMs); assertEquals(expected.playCount, actual.playCount)
        assertEquals(expected.uniqueTrackCount, actual.uniqueTrackCount)
        assertEquals(expected.completedCount, actual.completedCount); assertEquals(expected.skippedCount, actual.skippedCount)
        assertEquals(expected.completionRate, actual.completionRate, 1e-12)
        assertEquals(expected.skipRate, actual.skipRate, 1e-12)
        assertTrue(actual.completionRate.isFinite() && actual.completionRate in 0.0..1.0)
        assertTrue(actual.skipRate.isFinite() && actual.skipRate in 0.0..1.0)
        assertEquals(expected.topTracks, actual.topTracks); assertEquals(expected.topArtists, actual.topArtists)
        assertEquals(expected.dailyTrend, actual.dailyTrend)
    }

    /** The original N+1 read pattern, retained only for an equal-data before/after sample. */
    private fun legacyNPlusOneSummary(): PlaybackStatsSummary {
        val sessions = database.playSessionDao().getSessionsInRange(0L, Long.MAX_VALUE)
        val tracks = sessions.mapNotNull { database.trackDao().getTrack(it.trackKey) }.associateBy { it.trackKey }
        val result = reference(sessions, tracks.values.toList(), StatsRange.ALL, NOW, UTC, Locale.US)
        val playCount = sessions.count { it.countedPlay }
        return result.copy(
            completionRate = if (playCount == 0) 0.0 else sessions.count { it.completed }.toDouble() / playCount,
            skipRate = if (playCount == 0) 0.0 else sessions.count { it.skipped }.toDouble() / playCount
        )
    }

    private fun measureSamples(label: String, rows: Int, repetitions: Int, maximumReads: Int, block: () -> PlaybackStatsSummary): PlaybackStatsSummary {
        val durations = mutableListOf<Double>()
        val details = JSONArray()
        var last: PlaybackStatsSummary? = null
        repeat(repetitions) {
            queries.begin()
            val before = usedJavaHeap()
            val began = SystemClock.elapsedRealtimeNanos()
            last = block()
            val elapsed = (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0
            val after = usedJavaHeap()
            queries.end()
            val reads = queries.businessQueries().size
            if (label.startsWith("legacy")) assertEquals(1_001, reads) else assertTrue("$label used $reads SELECTs", reads in 1..maximumReads)
            durations += elapsed
            details.put(JSONObject().put("elapsedMs", elapsed).put("selectCount", reads)
                .put("javaHeapUsedBeforeBytes", before).put("javaHeapUsedAfterBytes", after).put("javaHeapEndpointDeltaBytes", after - before))
        }
        val sorted = durations.sorted()
        val report = JSONObject().put("case", label).put("rowCount", rows).put("sampleCount", repetitions)
            .put("medianMs", sorted[sorted.size / 2]).put("p95NearestRankMs", sorted[kotlin.math.ceil(sorted.size * 0.95).toInt() - 1])
            .put("samples", details).put("sdkInt", Build.VERSION.SDK_INT)
            .put("environment", "Android emulator or device running instrumentation; not Sony acceptance")
            .put("heapMeaning", "Java heap endpoints including query instrumentation; not peak or precise allocation")
        if (label.startsWith("legacy")) report.put("baseline", "original N+1 queries and playCount denominator; fixture is entirely ended and counted")
        emit(report)
        appendArtifact("stats-benchmarks.json", report)
        if (label == "aggregate_1000_same_track") saveQueries(label, queries.snapshot())
        return requireNotNull(last)
    }

    private fun usedJavaHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun saveQueries(label: String, values: List<QueryEvent>) {
        appendArtifact("stats-queries.json", JSONObject().put("case", label).put("queries", JSONArray().apply {
            values.forEach { query -> put(JSONObject().put("sql", query.sql).put("bindArgs", JSONArray().apply {
                query.args.forEach { put(it ?: JSONObject.NULL) }
            })) }
        }))
    }

    private fun appendArtifact(name: String, value: JSONObject) {
        synchronized(artifactLock) {
            val file = File(context.cacheDir, name)
            val array = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            array.put(value)
            file.writeText(array.toString())
        }
    }

    private fun emit(value: JSONObject) {
        val line = "BENCH $value"
        Log.i("HistoryStatsBench", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$line\n") })
    }

    private fun session(
        id: Long, key: String, startedAt: Long = NOW - 1_000L, listenedMs: Long = 1_000L,
        counted: Boolean = true, completed: Boolean = false, skipped: Boolean = false, ended: Boolean = true
    ) = PlaySessionEntity(id, key, startedAt, if (ended) startedAt.takeIf { it < Long.MAX_VALUE }?.plus(1L) else null,
        listenedMs, 0L, listenedMs, 180_000L, completed, skipped, counted, "test.player", startedAt)

    private fun track(key: String, artist: String = "Artist") = TrackEntity(key, "Title $key", artist,
        "Album $key", "test.player", 180_000L, "art-$key", NOW, NOW, 0, 0, 0, 0L, NOW)

    private fun localMillis(zone: TimeZone, value: String): Long = requireNotNull(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .apply { timeZone = zone; isLenient = false }.parse(value)).time

    private data class RangeCase(val range: StatsRange, val locale: Locale, val start: String, val end: String)
    private data class DstCase(val zone: String, val day: String, val nextDay: String, val hours: Double)
    private data class QueryEvent(val sql: String, val args: List<Any?>)

    private class QueryRecorder {
        private val events = Collections.synchronizedList(mutableListOf<QueryEvent>())
        @Volatile private var recording = false
        @Volatile var beforeBusinessQuery: ((Int) -> Unit)? = null
        val callback = RoomDatabase.QueryCallback { sql, args ->
            if (recording) {
                events += QueryEvent(sql, args.toList())
                if (isBusinessSelect(sql)) beforeBusinessQuery?.invoke(businessQueries().size)
            }
        }
        fun begin() { synchronized(events) { events.clear() }; recording = true }
        fun end() { recording = false }
        fun snapshot(): List<QueryEvent> = synchronized(events) { events.toList() }
        fun businessQueries(): List<QueryEvent> = snapshot().filter { isBusinessSelect(it.sql) }
    }

    companion object {
        private const val NOW = 1_793_966_400_000L
        private const val DAY_MS = 86_400_000L
        private val UTC: TimeZone get() = TimeZone.getTimeZone("UTC")
        private val DIRECT_EXECUTOR = Executor { it.run() }
        private val artifactsInitialized = AtomicBoolean()
        private val artifactLock = Any()
        private fun isBusinessSelect(sql: String): Boolean {
            val normalized = sql.trimStart().lowercase(Locale.ROOT)
            return (normalized.startsWith("select") || normalized.startsWith("with")) &&
                (normalized.contains("play_sessions") || normalized.contains("tracks"))
        }
    }
}
