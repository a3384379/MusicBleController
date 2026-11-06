package com.example.controllerapp

import com.example.controllerapp.data.HistoryCursorEntity
import com.example.controllerapp.data.PlaybackHistoryDao
import com.example.controllerapp.data.PlaybackHistoryEntity
import com.example.controllerapp.history.PlaybackHistoryCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHistoryCoordinatorTest {
    @Test fun syncUsesCommittedWatermarkAndRevisitsAnOpenSession() = runTest {
        val dao = FakeDao().apply { rows[900] = entity(900) }
        val commands = mutableListOf<JSONObject>()
        val coordinator = coordinator(dao, StandardTestDispatcher(testScheduler), commands)
        runCurrent()
        coordinator.sync()
        coordinator.sync()
        runCurrent()
        assertEquals(1, commands.size)
        assertEquals(0L, commands.last().getLong("afterSessionId"))
        coordinator.receive(since(commands.last(), listOf(item(1), item(2, open = true)), more = true))
        runCurrent()
        assertEquals(2L, commands.last().getLong("afterSessionId"))
        assertEquals(1L, dao.metadata!!.sinceSessionId)
        coordinator.receive(since(commands.last(), listOf(item(3)), more = false))
        runCurrent()
        assertEquals(1L, dao.metadata!!.sinceSessionId)
        finishStats(coordinator, commands) { runCurrent() }
        coordinator.sync()
        runCurrent()
        assertEquals(1L, commands.last().getLong("afterSessionId"))
        coordinator.receive(since(commands.last(), listOf(item(2), item(3)), more = false))
        runCurrent()
        assertEquals(3L, dao.metadata!!.sinceSessionId)
        assertNotNull(dao.rows[2]!!.endedAt)
        coordinator.close()
        runCurrent()
    }

    @Test fun cacheCrossesFiveHundredAndSlidesWithinABoundedWindow() = runTest {
        val dao = FakeDao().apply { (1L..1_200L).forEach { rows[it] = entity(it) } }
        val commands = mutableListOf<JSONObject>()
        val coordinator = coordinator(dao, StandardTestDispatcher(testScheduler), commands)
        runCurrent()
        assertEquals(500, coordinator.state.value.sessions.size)
        coordinator.loadMore()
        runCurrent()
        assertEquals(1_000, coordinator.state.value.sessions.size)
        coordinator.loadMore()
        runCurrent()
        assertEquals(1_000, coordinator.state.value.sessions.size)
        assertEquals(1L, coordinator.state.value.sessions.last().sessionId)
        assertTrue(coordinator.state.value.showingOlderWindow)
        assertTrue(commands.isEmpty())
        coordinator.showRecent()
        runCurrent()
        assertEquals(500, coordinator.state.value.sessions.size)
        assertEquals(1_200L, coordinator.state.value.sessions.first().sessionId)
        assertFalse(coordinator.state.value.showingOlderWindow)
        coordinator.close()
        runCurrent()
    }

    @Test fun olderCursorIsServerCursorAndSurvivesRecreationIndependentlyOfSince() = runTest {
        val dao = FakeDao().apply {
            rows[100] = entity(100)
            metadata = HistoryCursorEntity(sinceSessionId = 42)
        }
        val commands = mutableListOf<JSONObject>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = coordinator(dao, dispatcher, commands)
        runCurrent()
        first.loadMore()
        runCurrent()
        assertEquals(100L, commands.last().getLong("beforeSessionId"))
        first.receive(page(commands.last(), listOf(item(99), item(98)), next = 98, more = true))
        runCurrent()
        assertEquals(42L, dao.metadata!!.sinceSessionId)
        first.close()
        runCurrent()
        val second = coordinator(dao, dispatcher, commands)
        runCurrent()
        second.loadMore()
        runCurrent()
        assertEquals(98L, commands.last().getLong("beforeSessionId"))
        second.receive(page(commands.last(), listOf(item(97)), next = 1, more = true))
        runCurrent()
        assertFalse(second.state.value.loading)
        assertEquals(98L, dao.metadata!!.olderBeforeSessionId)
        assertNull(dao.rows[97])
        second.loadMore()
        runCurrent()
        assertEquals(98L, commands.last().getLong("beforeSessionId"))
        // An older Sony build may omit nextBefore; derive it from this valid page.
        second.receive(page(commands.last(), listOf(item(97)), next = null, more = false))
        runCurrent()
        assertEquals(97L, dao.metadata!!.olderBeforeSessionId)
        second.close()
        runCurrent()
    }

    @Test fun clearWaitsForRealCommitAndStillFinishesAfterDisconnectAndClose() = runTest {
        val dao = FakeDao()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.beforeCommit = { entered.complete(Unit); release.await() }
        val commands = mutableListOf<JSONObject>()
        val coordinator = coordinator(dao, StandardTestDispatcher(testScheduler), commands)
        coordinator.sync()
        runCurrent()
        val old = since(commands.last(), listOf(item(1)), more = false)
        coordinator.receive(old)
        runCurrent()
        assertTrue(entered.isCompleted)
        coordinator.clear()
        coordinator.invalidate("断线")
        coordinator.receive(old)
        coordinator.close()
        assertFalse(coordinator.state.value.loading)
        release.complete(Unit)
        runCurrent()
        assertTrue(dao.rows.isEmpty())
        assertNull(dao.metadata)
        assertFalse(coordinator.state.value.clearing)
        assertTrue(coordinator.state.value.sessions.isEmpty())
    }

    @Test fun repeatedStartDoesNotExtendDeadlineAndFailedStatsContinue() = runTest {
        val dao = FakeDao()
        val commands = mutableListOf<JSONObject>()
        val coordinator = coordinator(dao, StandardTestDispatcher(testScheduler), commands)
        coordinator.sync()
        runCurrent()
        coordinator.receive(since(commands.last(), emptyList(), more = false))
        runCurrent()
        val today = commands.last()
        val start = JSONObject().put("type", "historyPayloadStart")
            .put("requestId", today.getString("requestId")).put("responseType", "playStats")
            .put("size", 20).put("chunks", 1)
        coordinator.receive(start)
        runCurrent()
        advanceTimeBy(7_999)
        coordinator.receive(start)
        runCurrent()
        assertEquals("TODAY", commands.last().getString("range"))
        advanceTimeBy(1)
        runCurrent()
        assertEquals("WEEK", commands.last().getString("range"))
        assertEquals(setOf("TODAY"), coordinator.state.value.failedRanges)
        coordinator.receive(error(commands.last()))
        runCurrent()
        assertEquals("MONTH", commands.last().getString("range"))
        coordinator.receive(stats(commands.last()))
        runCurrent()
        assertFalse(coordinator.state.value.loading)
        assertEquals(setOf("TODAY", "WEEK"), coordinator.state.value.failedRanges)
        assertEquals(setOf("MONTH"), coordinator.state.value.stats.keys)
        coordinator.close()
        runCurrent()
    }

    @Test fun epochFenceRejectsQueuedOldResponseAndNewSyncKeepsItsDeadline() = runTest {
        val dao = FakeDao()
        val commands = mutableListOf<JSONObject>()
        val coordinator = coordinator(dao, StandardTestDispatcher(testScheduler), commands)
        coordinator.sync()
        runCurrent()
        val old = since(commands.last(), listOf(item(1)), more = false)
        val oldEpoch = coordinator.currentEpoch
        coordinator.invalidate("断线")
        coordinator.sync()
        coordinator.receive(old, oldEpoch)
        runCurrent()
        assertTrue(dao.rows.isEmpty())
        assertEquals(2, commands.size)
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals("TODAY", commands.last().getString("range"))
        finishStats(coordinator, commands) { runCurrent() }
        assertTrue(coordinator.state.value.status.contains("超时"))
        coordinator.close()
        runCurrent()
    }

    @Test fun storageFailureDoesNotAdvanceCursorAndCanRetry() = runTest {
        val dao = FakeDao().apply { beforeCommit = { error("disk failure") } }
        val commands = mutableListOf<JSONObject>()
        val coordinator = coordinator(dao, StandardTestDispatcher(testScheduler), commands)
        coordinator.sync()
        runCurrent()
        coordinator.receive(since(commands.last(), listOf(item(1)), more = false))
        runCurrent()
        finishStats(coordinator, commands) { runCurrent() }
        assertNull(dao.metadata)
        assertFalse(coordinator.state.value.loading)
        dao.beforeCommit = null
        coordinator.sync()
        runCurrent()
        assertEquals(0L, commands.last().getLong("afterSessionId"))
        coordinator.receive(since(commands.last(), listOf(item(1)), more = false))
        runCurrent()
        finishStats(coordinator, commands) { runCurrent() }
        assertEquals(1L, dao.metadata!!.sinceSessionId)
        coordinator.close()
        runCurrent()
    }

    @Test fun syncingNewRowsPreservesTheVisibleBottomWhileStayingBounded() = runTest {
        val dao = FakeDao().apply { (1L..1_000L).forEach { rows[it] = entity(it) } }
        val commands = mutableListOf<JSONObject>()
        val coordinator = coordinator(dao, StandardTestDispatcher(testScheduler), commands)
        runCurrent()
        coordinator.loadMore()
        runCurrent()
        assertEquals(1L, coordinator.state.value.sessions.last().sessionId)
        coordinator.sync()
        runCurrent()
        coordinator.receive(since(commands.last(), (1_001L..1_020L).map { item(it) }, more = false))
        runCurrent()
        finishStats(coordinator, commands) { runCurrent() }
        assertEquals(1_000, coordinator.state.value.sessions.size)
        assertEquals(1L, coordinator.state.value.sessions.last().sessionId)
        assertTrue(coordinator.state.value.showingOlderWindow)
        coordinator.showRecent()
        runCurrent()
        assertEquals(1_020L, coordinator.state.value.sessions.first().sessionId)
        assertEquals(500, coordinator.state.value.sessions.size)
        coordinator.close()
        runCurrent()
    }

    @Test fun sonyRatesAboveOneRemainReadableUntilSonyStatsAreRepaired() = runTest {
        val commands = mutableListOf<JSONObject>()
        val coordinator = coordinator(FakeDao(), StandardTestDispatcher(testScheduler), commands)
        coordinator.sync()
        runCurrent()
        coordinator.receive(since(commands.last(), emptyList(), more = false))
        runCurrent()
        coordinator.receive(stats(commands.last()).put("skipRate", 2.0))
        runCurrent()
        assertEquals(2.0, coordinator.state.value.stats["TODAY"]!!.skipRate, 0.0)
        assertTrue(coordinator.state.value.failedRanges.isEmpty())
        coordinator.close()
        runCurrent()
    }

    @Test fun olderPageOverlappingSinceCacheAdvancesWithAnHonestContinueMessage() = runTest {
        val dao = FakeDao().apply { rows[100] = entity(100) }
        val commands = mutableListOf<JSONObject>()
        val coordinator = coordinator(dao, StandardTestDispatcher(testScheduler), commands)
        runCurrent()
        coordinator.loadMore()
        runCurrent()
        coordinator.receive(page(commands.last(), listOf(item(99), item(98)), next = 98, more = true))
        runCurrent()
        assertEquals(98L, dao.metadata!!.olderBeforeSessionId)
        coordinator.sync()
        runCurrent()
        repeat(5) { index ->
            val ids = (index * 20 + 1L..index * 20 + 20L)
            coordinator.receive(since(commands.last(), ids.map { item(it) }, more = index < 4))
            runCurrent()
        }
        finishStats(coordinator, commands) { runCurrent() }
        assertEquals(100L, dao.metadata!!.sinceSessionId)
        assertEquals(98L, dao.metadata!!.olderBeforeSessionId)
        coordinator.loadMore() // Consume the older rows that since just cached.
        runCurrent()
        assertEquals(100, coordinator.state.value.sessions.size)
        assertEquals(1, commands.count { it.getString("cmd") == "GET_PLAY_HISTORY_PAGE" })
        coordinator.loadMore()
        runCurrent()
        assertEquals(98L, commands.last().getLong("beforeSessionId"))
        coordinator.receive(page(commands.last(), listOf(item(97), item(96)), next = 96, more = true))
        runCurrent()
        assertEquals(96L, dao.metadata!!.olderBeforeSessionId)
        assertEquals(100, coordinator.state.value.sessions.size)
        assertEquals("已检查缓存记录，可继续加载更早记录", coordinator.state.value.status)
        assertFalse(coordinator.state.value.loading)
        assertEquals(2, commands.count { it.getString("cmd") == "GET_PLAY_HISTORY_PAGE" })
        coordinator.loadMore()
        runCurrent()
        assertEquals(96L, commands.last().getLong("beforeSessionId"))
        coordinator.receive(page(commands.last(), emptyList(), next = null, more = false))
        runCurrent()
        assertEquals("已显示全部可用历史", coordinator.state.value.status)
        assertFalse(coordinator.state.value.hasMore)
        assertFalse(coordinator.state.value.loading)
        coordinator.close()
        runCurrent()
    }

    private fun coordinator(dao: FakeDao, dispatcher: TestDispatcher, commands: MutableList<JSONObject>) =
        PlaybackHistoryCoordinator(dao, dispatcher, send = { command, payload ->
            commands.add(JSONObject(payload.toString()).put("cmd", command))
            true
        })

    private fun finishStats(coordinator: PlaybackHistoryCoordinator, commands: List<JSONObject>, drain: () -> Unit) {
        repeat(3) {
            if (commands.last().optString("cmd") == "GET_PLAY_STATS" && coordinator.state.value.loading) {
                coordinator.receive(stats(commands.last()))
                drain()
            }
        }
    }

    private fun since(request: JSONObject, items: List<JSONObject>, more: Boolean) =
        JSONObject().put("type", "playHistorySince").put("requestId", request.getString("requestId"))
            .put("items", JSONArray(items)).put("hasMore", more)
            .put("lastSessionId", items.maxOfOrNull { it.getLong("sessionId") } ?: request.getLong("afterSessionId"))

    private fun page(request: JSONObject, items: List<JSONObject>, next: Long?, more: Boolean) =
        JSONObject().put("type", "playHistoryPage").put("requestId", request.getString("requestId"))
            .put("items", JSONArray(items)).put("hasMore", more).apply {
                if (next != null) put("nextBeforeSessionId", next)
            }

    private fun stats(request: JSONObject) = JSONObject().put("type", "playStats")
        .put("requestId", request.getString("requestId")).put("range", request.getString("range"))
        .put("totalListenMs", 1_000).put("playCount", 1).put("uniqueTrackCount", 1)
        .put("completionRate", 1.0).put("skipRate", 0.0)
        .put("topTracks", JSONArray()).put("topArtists", JSONArray()).put("dailyTrend", JSONArray())

    private fun error(request: JSONObject) = JSONObject().put("type", "playHistoryError")
        .put("requestId", request.getString("requestId")).put("responseType", "playStats").put("message", "failed")

    private fun item(id: Long, open: Boolean = false) = JSONObject().put("sessionId", id)
        .put("title", "Song $id").put("endedAt", if (open) JSONObject.NULL else 100L)

    private fun entity(id: Long) = PlaybackHistoryEntity(
        id, "t$id", "Song $id", "Artist", "Album", null, 1, 100, 50, 100, false, false, true
    )

    private class FakeDao : PlaybackHistoryDao {
        val rows = mutableMapOf<Long, PlaybackHistoryEntity>()
        var metadata: HistoryCursorEntity? = null
        var beforeCommit: (suspend () -> Unit)? = null
        override fun observeRecent(limit: Int): Flow<List<PlaybackHistoryEntity>> = flowOf(rows.values.toList())
        override suspend fun upsert(values: List<PlaybackHistoryEntity>) { values.forEach { rows[it.sessionId] = it } }
        override suspend fun clear() { rows.clear() }
        override suspend fun latestSessionId() = rows.keys.maxOrNull()
        override suspend fun oldestSessionId() = rows.keys.minOrNull()
        override suspend fun readBefore(beforeExclusive: Long, limit: Int) =
            rows.values.filter { it.sessionId < beforeExclusive }.sortedByDescending { it.sessionId }.take(limit)
        override suspend fun cursor() = metadata
        override suspend fun upsertCursor(cursor: HistoryCursorEntity) { metadata = cursor }
        override suspend fun clearCursor() { metadata = null }
        override suspend fun commitPage(values: List<PlaybackHistoryEntity>, cursor: HistoryCursorEntity) {
            beforeCommit?.invoke()
            upsert(values)
            upsertCursor(cursor)
        }
    }
}
