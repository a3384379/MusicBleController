package com.example.controllerapp

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controllerapp.data.ControllerLogStore
import com.example.controllerapp.data.ControllerPreferences
import com.example.controllerapp.data.HistoryCursorEntity
import com.example.controllerapp.data.PlaybackHistoryDao
import com.example.controllerapp.data.PlaybackHistoryDatabase
import com.example.controllerapp.data.PlaybackHistoryEntity
import com.example.controllerapp.history.PlaybackHistoryCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Real Room transactions with controlled BLE delivery and reducer scheduling. */
@RunWith(AndroidJUnit4::class)
class ControllerHistoryRegressionTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var database: PlaybackHistoryDatabase
    private lateinit var dao: GatedHistoryDao
    private lateinit var dispatcher: QueuedDispatcher
    private var coordinator: PlaybackHistoryCoordinator? = null
    private var repository: ControllerRepository? = null
    private var logStore: ControllerLogStore? = null
    private val commands = Collections.synchronizedList(mutableListOf<JSONObject>())
    private var rejectCommand: (String, JSONObject) -> Boolean = { _, _ -> false }

    private val history: PlaybackHistoryCoordinator get() = requireNotNull(coordinator)

    @Before
    fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "history-regression-${System.nanoTime()}").apply {
            mkdirs()
        }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        database = Room.inMemoryDatabaseBuilder(base, PlaybackHistoryDatabase::class.java)
            .build()
        dao = GatedHistoryDao(database.historyDao())
        dispatcher = QueuedDispatcher()
        coordinator = newCoordinator()
    }

    @After
    fun tearDown() = runBlocking {
        dao.commitGate?.release?.complete(Unit)
        dao.cursorGate?.release?.complete(Unit)
        dao.clearGate?.release?.complete(Unit)
        dao.readGate?.release?.complete(Unit)
        coordinator?.close()
        repository?.close()
        settle()
        database.close()
        logStore?.let { store ->
            // close() posts its flush. Removing files before this join fabricates a crash.
            val worker = ControllerLogStore::class.java.getDeclaredField("thread").apply {
                isAccessible = true
            }.get(store) as Thread
            store.close()
            worker.join(5_000L)
            assertFalse("log worker did not stop", worker.isAlive)
        }
        root.deleteRecursively()
        Unit
    }

    @Test
    fun multipartHistoryCompletesAcrossTrackChange() = runBlocking {
        val repo = useRepository()
        repo.syncHistory()
        val request = awaitCommand("GET_PLAY_HISTORY_SINCE")
        val payload = sessionsPayload(request, listOf(row(1L), row(2L)))
        val chunks = beginMultipart(request, payload, receive = ::receiveRepository)
        sendChunk(request, 0, chunks[0], ::receiveRepository)

        changeTrack(repo, "next-track")
        chunks.indices.drop(1).reversed().forEach { index ->
            sendChunk(request, index, chunks[index], ::receiveRepository)
        }
        endMultipart(request, ::receiveRepository)
        finishStats(::receiveRepository)

        await { !repo.history.value.loading }
        assertEquals(listOf(2L, 1L), repo.history.value.sessions.map { it.sessionId })
        assertEquals(2L, requireNotNull(dao.cursor()).sinceSessionId)
    }

    @Test
    fun trackChangeKeepsIncompleteHistoryDeadlineAndRetryUsable() = runBlocking {
        val repo = useRepository()
        repo.syncHistory()
        val first = awaitCommand("GET_PLAY_HISTORY_SINCE")
        val chunks = beginMultipart(
            first,
            sessionsPayload(first, listOf(row(1L))),
            receive = ::receiveRepository
        )
        sendChunk(first, 0, chunks.first(), ::receiveRepository)
        changeTrack(repo, "deadline-track")

        // A history timeout advances to statistics; complete them to test the public
        // retry state without relying on the wording of an intermediate status.
        finishStats(::receiveRepository)
        await { !repo.history.value.loading }
        assertTrue(repo.history.value.status.contains("超时"))
        assertNull(dao.latestSessionId())
        repo.syncHistory()
        val retry = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 1)
        assertNotEquals(first.getString("requestId"), retry.getString("requestId"))
        receiveRepository(sessionsPayload(retry, listOf(row(2L))))
        finishStats(::receiveRepository, after = 3)
        await { !repo.history.value.loading }
        assertEquals(listOf(2L), repo.history.value.sessions.map { it.sessionId })
    }

    @Test
    fun localHistoryBeyondFiveHundredRemainsReadableAndRemoteCursorAdvances() = runBlocking {
        dao.upsert((1_001L..2_600L).map(::row))
        await { history.state.value.sessions.size == 500 }
        assertEquals(2_101L, history.state.value.sessions.minOf { it.sessionId })

        var oldestVisible = 2_101L
        while (oldestVisible > 1_001L) {
            history.loadMore()
            await { history.state.value.sessions.minOf { it.sessionId } < oldestVisible }
            val visible = history.state.value.sessions
            assertEquals(visible.size, visible.map { it.sessionId }.distinct().size)
            assertTrue("visible history must stay bounded", visible.size <= 1_000)
            oldestVisible = visible.minOf { it.sessionId }
        }
        assertTrue(history.state.value.sessions.any { it.title == "Song 1001" })

        history.loadMore()
        val first = awaitCommand("GET_PLAY_HISTORY_PAGE")
        assertEquals(1_001L, first.getLong("beforeSessionId"))
        receive(sessionsPayload(first, (981L..1_000L).map(::row), hasMore = true))
        await { !history.state.value.loading && history.state.value.sessions.any { it.sessionId == 981L } }
        history.loadMore()
        val second = awaitCommand("GET_PLAY_HISTORY_PAGE", after = 1)
        assertEquals(981L, second.getLong("beforeSessionId"))
        receive(sessionsPayload(second, (961L..980L).map(::row)))
        await { !history.state.value.loading && history.state.value.sessions.any { it.sessionId == 961L } }
        assertTrue(history.state.value.sessions.any { it.title == "Song 961" })

        history.showRecent()
        await { history.state.value.sessions.firstOrNull()?.sessionId == 2_600L }
        assertEquals(500, history.state.value.sessions.size)
    }

    @Test
    fun localPaginationBoundariesRemainReadableWithBoundedWindows() = runBlocking {
        for (count in listOf(0, 1, 499, 500, 501, 1_000, 10_000)) {
            history.close()
            settle()
            dao.clearHistory()
            dao.commitPage(
                (1L..count.toLong()).map(::row),
                HistoryCursorEntity(olderHasMore = false)
            )
            coordinator = newCoordinator()
            val initialSize = minOf(500, count)
            await {
                history.state.value.sessions.size == initialSize &&
                    history.state.value.hasMore == (count > 500)
            }
            val seen = history.state.value.sessions.mapTo(mutableSetOf()) { it.sessionId }
            while (history.state.value.hasMore) {
                val oldest = history.state.value.sessions.last().sessionId
                history.loadMore()
                await {
                    !history.state.value.loading &&
                        history.state.value.sessions.last().sessionId < oldest
                }
                val visible = history.state.value.sessions
                assertTrue("count=$count exceeded the visible-row budget", visible.size <= 1_000)
                assertEquals(visible.size, visible.map { it.sessionId }.distinct().size)
                seen += visible.map { it.sessionId }
            }
            assertEquals("count=$count did not expose every local row",
                (1L..count.toLong()).toSet(), seen)
            assertFalse(history.state.value.loading)
            if (count == 0) assertTrue(history.state.value.sessions.isEmpty()) else {
                assertEquals("count=$count could not reach the oldest record",
                    1L, history.state.value.sessions.last().sessionId)
            }

            history.showRecent()
            await {
                history.state.value.sessions.size == initialSize &&
                    history.state.value.sessions.firstOrNull()?.sessionId == count.toLong().takeIf { it > 0L }
            }
            assertFalse(history.state.value.showingOlderWindow)
        }
        assertTrue("local reads unexpectedly sent a remote page request",
            commandList("GET_PLAY_HISTORY_PAGE").isEmpty())
    }

    @Test
    fun syncAndOlderCursorsPersistIndependentlyAcrossRestart() = runBlocking {
        history.sync()
        val first = awaitCommand("GET_PLAY_HISTORY_SINCE")
        assertEquals(0L, first.getLong("afterSessionId"))
        receive(sessionsPayload(first, (100L..119L).map(::row), hasMore = true))
        val second = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 1)
        assertEquals(119L, second.getLong("afterSessionId"))
        receive(sessionsPayload(second, (120L..125L).map(::row)))
        finishStats()
        await { !history.state.value.loading }
        val stats = requireNotNull(history.state.value.stats["TODAY"])
        assertEquals("Stats Song", stats.topTracks.single().title)
        assertEquals(1_500L, stats.topTracks.single().listenedMs)
        assertEquals("Stats Artist", stats.topArtists.single().artist)
        assertEquals("2026-11-06", stats.dailyTrend.single().dateKey)
        assertEquals(2_000L, stats.dailyTrend.single().listenedMs)

        history.loadMore()
        val page = awaitCommand("GET_PLAY_HISTORY_PAGE")
        assertEquals(100L, page.getLong("beforeSessionId"))
        receive(sessionsPayload(page, (80L..99L).map(::row), hasMore = true))
        await { !history.state.value.loading }
        assertEquals(125L, requireNotNull(dao.cursor()).sinceSessionId)
        assertEquals(80L, requireNotNull(dao.cursor()).olderBeforeSessionId)

        restartCoordinator()
        history.sync()
        val restarted = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 2)
        assertEquals(125L, restarted.getLong("afterSessionId"))
        receive(sessionsPayload(restarted, emptyList(), lastSessionId = 125L))
        finishStats(after = 3)
        await { !history.state.value.loading }
        history.loadMore()
        assertEquals(80L, awaitCommand("GET_PLAY_HISTORY_PAGE", after = 1).getLong("beforeSessionId"))
    }

    @Test
    fun incrementalSyncPreservesVisibleHistoryUntilReturningToRecent() = runBlocking {
        dao.upsert((501L..1_000L).map(::row))
        await { history.state.value.sessions.size == 500 }
        assertTrue(history.state.value.sessions.any { it.sessionId == 510L })
        history.sync()
        val request = awaitCommand("GET_PLAY_HISTORY_SINCE")
        receive(sessionsPayload(request, (1_001L..1_020L).map(::row)))
        finishStats()
        await { !history.state.value.loading }

        assertTrue("sync removed a row the user was reading",
            history.state.value.sessions.any { it.sessionId == 510L })
        assertEquals(1_020L, dao.latestSessionId())
        history.showRecent()
        await {
            history.state.value.sessions.firstOrNull()?.sessionId == 1_020L &&
                history.state.value.sessions.size == 500 &&
                history.state.value.sessions.lastOrNull()?.sessionId == 521L
        }
        assertEquals(500, history.state.value.sessions.size)
        assertEquals(521L, history.state.value.sessions.last().sessionId)
    }

    @Test
    fun openSessionIsReReadAfterRestartWhileSameSyncStillMakesProgress() = runBlocking {
        history.sync()
        val first = awaitCommand("GET_PLAY_HISTORY_SINCE")
        val open = row(11L).copy(endedAt = null, listenedMs = 500L)
        receive(sessionsPayload(first, listOf(row(10L), open, row(12L)), hasMore = true))
        val second = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 1)
        assertEquals(12L, second.getLong("afterSessionId"))
        receive(sessionsPayload(second, listOf(row(13L))))
        finishStats()
        await { !history.state.value.loading }
        assertEquals(10L, requireNotNull(dao.cursor()).sinceSessionId)

        restartCoordinator()
        history.sync()
        val restarted = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 2)
        assertEquals(10L, restarted.getLong("afterSessionId"))
        val completed = row(11L).copy(listenedMs = 6_000L, completed = true)
        receive(sessionsPayload(restarted, listOf(completed, row(12L), row(13L))))
        finishStats(after = 3)
        await { !history.state.value.loading }
        val saved = dao.readBefore(Long.MAX_VALUE, 100).single { it.sessionId == 11L }
        assertEquals(6_000L, saved.listenedMs)
        assertTrue(saved.completed)
        assertTrue(saved.endedAt != null)
        assertEquals(13L, requireNotNull(dao.cursor()).sinceSessionId)
    }

    @Test
    fun clearRejectsOldDirectAndMultipartResponses() = runBlocking {
        dao.upsert(listOf(row(1L)))
        history.sync()
        val old = awaitCommand("GET_PLAY_HISTORY_SINCE")
        val oldPayload = sessionsPayload(old, listOf(row(2L)))
        val chunks = beginMultipart(old, oldPayload)
        sendChunk(old, 0, chunks.first())
        history.clear()
        await { !history.state.value.clearing && history.state.value.sessions.isEmpty() }

        receive(oldPayload)
        chunks.indices.forEach { sendChunk(old, it, chunks[it]) }
        endMultipart(old)
        beginMultipart(old, oldPayload).forEachIndexed { index, bytes ->
            sendChunk(old, index, bytes)
        }
        endMultipart(old)
        settle()

        assertCleared()
        history.sync()
        assertEquals(0L, awaitCommand("GET_PLAY_HISTORY_SINCE", after = 1).getLong("afterSessionId"))
    }

    @Test
    fun clearWaitsForAcceptedIoThenRemovesRowsAndCursorAtomically() = runBlocking {
        history.sync()
        val request = awaitCommand("GET_PLAY_HISTORY_SINCE")
        val gate = Gate().also { dao.commitGate = it }
        receive(sessionsPayload(request, listOf(row(9L))))
        await { gate.entered.isCompleted }

        history.clear()
        dispatcher.runCurrent()
        gate.release.complete(Unit)
        await { !history.state.value.clearing && history.state.value.sessions.isEmpty() }
        settle()
        assertCleared()
        assertTrue("cleared sync must not continue to statistics", commandList("GET_PLAY_STATS").isEmpty())
    }

    @Test
    fun clearDuringQueuedCursorReadCannotLaunchOldSync() = runBlocking {
        dao.commitPage(listOf(row(90L)), HistoryCursorEntity(sinceSessionId = 90L))
        settle()
        val gate = Gate().also { dao.cursorGate = it }
        history.sync()
        await { gate.entered.isCompleted }
        history.clear()
        dispatcher.runCurrent()
        gate.release.complete(Unit)
        await { !history.state.value.clearing && history.state.value.sessions.isEmpty() }
        settle()
        assertCleared()
        assertTrue(commandList("GET_PLAY_HISTORY_SINCE").isEmpty())

        history.sync()
        assertEquals(0L, awaitCommand("GET_PLAY_HISTORY_SINCE").getLong("afterSessionId"))
    }

    @Test
    fun disconnectDuringClearFinishesClearingAndNewSyncStartsFromZero() = runBlocking {
        dao.commitPage(listOf(row(90L)), HistoryCursorEntity(sinceSessionId = 90L))
        await { history.state.value.sessions.isNotEmpty() }
        val gate = Gate().also { dao.clearGate = it }
        history.clear()
        await { gate.entered.isCompleted }
        assertTrue(history.state.value.clearing)

        history.invalidate("disconnected during clear")
        dispatcher.runCurrent()
        gate.release.complete(Unit)
        await { !history.state.value.clearing }
        assertCleared()
        history.sync()
        assertEquals(0L, awaitCommand("GET_PLAY_HISTORY_SINCE").getLong("afterSessionId"))
    }

    @Test
    fun invalidatedInitialCacheReadReloadsLocalRowsEvenWhenRemoteSendFails() = runBlocking {
        dao.upsert(listOf(row(42L)))
        val gate = Gate().also { dao.readGate = it }
        await { gate.entered.isCompleted }
        history.invalidate("connected while cache read was pending")
        gate.release.complete(Unit)
        rejectCommand = { _, _ -> true }
        history.sync()
        await { !history.state.value.loading && history.state.value.sessions.any { it.sessionId == 42L } }
        assertEquals("Song 42", history.state.value.sessions.single().title)
        assertEquals(42L, dao.latestSessionId())
    }

    @Test
    fun invalidationInsideSyncPublicationCannotRelabelOldWorkAsNewEpoch() = runBlocking {
        settle()
        val invalidated = AtomicBoolean()
        // StateFlow resumes this undispatched collector during loading publication,
        // before the original sync action can continue to its first DAO suspension.
        val observer = launch(Dispatchers.Unconfined) {
            history.state.collect { state ->
                if (state.loading && invalidated.compareAndSet(false, true)) {
                    history.invalidate("disconnect during sync publication")
                }
            }
        }
        try {
            history.sync()
            await { invalidated.get() }
            settle()
            assertTrue("invalidated sync sent a command in the replacement epoch",
                commandList("GET_PLAY_HISTORY_SINCE").isEmpty())
        } finally {
            observer.cancelAndJoin()
        }

        history.sync()
        val retry = awaitCommand("GET_PLAY_HISTORY_SINCE")
        receive(sessionsPayload(retry, listOf(row(1L))))
        finishStats()
        await { !history.state.value.loading }
        assertEquals(1L, dao.latestSessionId())
    }

    @Test
    fun reconnectIsolatesOldResponseAndOldDeadlineFromNewRequest() = runBlocking {
        history.sync()
        val old = awaitCommand("GET_PLAY_HISTORY_SINCE")
        val oldChunks = beginMultipart(old, sessionsPayload(old, listOf(row(1L))))
        sendChunk(old, 0, oldChunks.first())
        history.invalidate("disconnected")
        await { !history.state.value.loading }
        history.sync()
        val current = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 1)
        assertNotEquals(old.getString("requestId"), current.getString("requestId"))

        receive(sessionsPayload(old, listOf(row(1L))))
        oldChunks.indices.forEach { sendChunk(old, it, oldChunks[it]) }
        endMultipart(old)
        receive(sessionsPayload(current, listOf(row(2L))))
        finishStats()
        await { !history.state.value.loading }
        delay(REQUEST_TIMEOUT_MS + 50L)
        settle()
        assertEquals(listOf(2L), dao.readBefore(Long.MAX_VALUE, 100).map { it.sessionId })
        assertEquals(setOf("TODAY", "WEEK", "MONTH"), history.state.value.stats.keys)
        assertFalse(history.state.value.status.contains("超时"))
    }

    @Test
    fun concurrentSyncCallsDeduplicateAndCompletedSyncCanRunAgain() = runBlocking {
        coroutineScope {
            repeat(32) { launch(Dispatchers.Default) { history.sync() } }
        }
        val request = awaitCommand("GET_PLAY_HISTORY_SINCE")
        settle()
        assertEquals(1, commandList("GET_PLAY_HISTORY_SINCE").size)
        receive(sessionsPayload(request, listOf(row(1L))))
        finishStats()
        await { !history.state.value.loading }

        history.sync()
        val next = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 1)
        assertEquals(1L, next.getLong("afterSessionId"))
        assertNotEquals(request.getString("requestId"), next.getString("requestId"))
    }

    @Test
    fun missingChunkFailsWithoutDiscardingLocalRowsAndCanRetry() = runBlocking {
        assertFailureCanRetry { request ->
            val chunks = beginMultipart(request, sessionsPayload(request, listOf(row(8L))))
            sendChunk(request, 0, chunks.first())
            endMultipart(request)
        }
    }

    @Test
    fun malformedMultipartJsonFailsAndCanRetry() = runBlocking {
        assertFailureCanRetry { request ->
            val bytes = "{ broken history json".toByteArray(Charsets.UTF_8)
            startMultipart(request, bytes.size, 1)
            sendChunk(request, 0, bytes)
            endMultipart(request)
        }
    }

    @Test
    fun multipartLengthMismatchFailsAndCanRetry() = runBlocking {
        assertFailureCanRetry { request ->
            val bytes = sessionsPayload(request, listOf(row(8L))).toString().toByteArray()
            startMultipart(request, bytes.size + 1, 1)
            sendChunk(request, 0, bytes)
            endMultipart(request)
        }
    }

    @Test
    fun wrongDirectResponseTypeFailsAndCanRetry() = runBlocking {
        assertFailureCanRetry { request ->
            receive(sessionsPayload(request, listOf(row(8L))).put("type", "playHistoryPage"))
        }
    }

    @Test
    fun wrongMultipartEnvelopeTypeFailsAndCanRetry() = runBlocking {
        assertFailureCanRetry { request ->
            startMultipart(request, 20, 2, responseType = "playStats")
        }
    }

    @Test
    fun wrongNestedRequestIdFailsAndCanRetry() = runBlocking {
        assertFailureCanRetry { request ->
            val payload = sessionsPayload(request, listOf(row(8L))).put("requestId", "other-request")
            val chunks = beginMultipart(request, payload)
            chunks.indices.forEach { sendChunk(request, it, chunks[it]) }
            endMultipart(request)
        }
    }

    @Test
    fun unknownDirectRequestDoesNotMutateDataOrCompleteActiveRequest() = runBlocking {
        assertFailureCanRetry { request ->
            receive(sessionsPayload(request, listOf(row(8L))).put("requestId", "stale-request"))
            settle()
            assertTrue(history.state.value.loading)
            assertEquals(7L, dao.latestSessionId())
        }
    }

    @Test
    fun invalidChunkIndexFailsAndCanRetry() = runBlocking {
        assertFailureCanRetry { request ->
            val chunks = beginMultipart(request, sessionsPayload(request, listOf(row(8L))))
            sendChunk(request, chunks.size, chunks.first())
            endMultipart(request)
        }
    }

    @Test
    fun missingRequestIdCannotMutateHistoryAndRequestRemainsRetryable() = runBlocking {
        assertFailureCanRetry { request ->
            receive(sessionsPayload(request, listOf(row(8L))).apply { remove("requestId") })
            settle()
            assertTrue(history.state.value.loading)
            assertEquals(7L, dao.latestSessionId())
        }
    }

    @Test
    fun invalidStartParametersFailAndCanRetry() = runBlocking {
        assertFailureCanRetry { request -> startMultipart(request, size = -1, chunks = 0) }
    }

    @Test
    fun malformedBase64ChunkFailsAndCanRetry() = runBlocking {
        assertFailureCanRetry { request ->
            startMultipart(request, size = 20, chunks = 1)
            receive(JSONObject().put("type", "historyPayloadChunk")
                .put("requestId", request.getString("requestId"))
                .put("index", 0).put("data", "!!!invalid!!!"))
        }
    }

    @Test
    fun mismatchedMultipartEndTypeFailsAndCanRetry() = runBlocking {
        assertFailureCanRetry { request ->
            val chunks = beginMultipart(request, sessionsPayload(request, listOf(row(8L))))
            chunks.indices.forEach { sendChunk(request, it, chunks[it]) }
            receive(JSONObject().put("type", "historyPayloadEnd")
                .put("requestId", request.getString("requestId"))
                .put("responseType", "playStats"))
        }
    }

    @Test
    fun invalidSessionIdFailsWithoutWritingPartialRowsAndCanRetry() = runBlocking {
        assertFailureCanRetry { request ->
            receive(sessionsPayload(request, listOf(row(8L), row(0L))))
        }
    }

    @Test
    fun sinceCursorOutsideReturnedRangeFailsAndCanRetry() = runBlocking {
        assertFailureCanRetry { request ->
            receive(sessionsPayload(request, listOf(row(8L)), hasMore = true, lastSessionId = 999L))
        }
    }

    @Test
    fun noResponseTimesOutAndCanRetry() = runBlocking {
        assertFailureCanRetry { /* Deliberately drop every response. */ }
    }

    @Test
    fun sinceWithoutCursorProgressStopsAndCanRetry() = runBlocking {
        dao.commitPage(listOf(row(7L)), HistoryCursorEntity(sinceSessionId = 7L))
        assertFailureCanRetry { request ->
            assertEquals(7L, request.getLong("afterSessionId"))
            receive(sessionsPayload(request, emptyList(), hasMore = true, lastSessionId = 7L))
        }
    }

    @Test
    fun repeatedOlderPageFailsWithoutLoopingAndCanRetry() = runBlocking {
        dao.upsert(listOf(row(7L)))
        await { history.state.value.sessions.isNotEmpty() }
        history.loadMore()
        val first = awaitCommand("GET_PLAY_HISTORY_PAGE")
        receive(sessionsPayload(first, listOf(row(7L)), hasMore = true, nextBefore = 7L))
        await { !history.state.value.loading }
        assertEquals(1, commandList("GET_PLAY_HISTORY_PAGE").size)
        assertEquals(listOf(7L), dao.readBefore(Long.MAX_VALUE, 100).map { it.sessionId })
        history.loadMore()
        val retry = awaitCommand("GET_PLAY_HISTORY_PAGE", after = 1)
        assertEquals(first.getLong("beforeSessionId"), retry.getLong("beforeSessionId"))
    }

    @Test
    fun legacyPageWithoutNextBeforeUsesOnlyItsOwnRowsAndMakesProgress() = runBlocking {
        dao.upsert(listOf(row(100L)))
        await { history.state.value.sessions.isNotEmpty() }
        history.loadMore()
        val first = awaitCommand("GET_PLAY_HISTORY_PAGE")
        receive(sessionsPayload(first, (80L..99L).map(::row), hasMore = true).apply {
            remove("nextBeforeSessionId")
        })
        await { !history.state.value.loading }
        assertEquals(80L, requireNotNull(dao.cursor()).olderBeforeSessionId)
        assertTrue(history.state.value.sessions.any { it.sessionId == 80L })
        history.loadMore()
        val second = awaitCommand("GET_PLAY_HISTORY_PAGE", after = 1)
        assertEquals(80L, second.getLong("beforeSessionId"))
        receive(sessionsPayload(second, (60L..79L).map(::row), hasMore = false).apply {
            remove("nextBeforeSessionId")
        })
        await { !history.state.value.loading }
        assertTrue(history.state.value.sessions.any { it.sessionId == 60L })
    }

    @Test
    fun statisticsRangeMismatchSkipsFailedRangeAndCompletesRemainingRanges() = runBlocking {
        startEmptySync()
        val today = awaitCommand("GET_PLAY_STATS")
        receive(statsPayload(today).put("range", "MONTH"))
        val week = awaitCommand("GET_PLAY_STATS", after = 1)
        assertEquals("WEEK", week.getString("range"))
        receive(statsPayload(week))
        val month = awaitCommand("GET_PLAY_STATS", after = 2)
        assertEquals("MONTH", month.getString("range"))
        receive(statsPayload(month))
        await { !history.state.value.loading }
        assertEquals(setOf("WEEK", "MONTH"), history.state.value.stats.keys)
        assertEquals(setOf("TODAY"), history.state.value.failedRanges)
    }

    @Test
    fun statisticsTimeoutContinuesToNextRange() = runBlocking {
        startEmptySync()
        assertEquals("TODAY", awaitCommand("GET_PLAY_STATS").getString("range"))
        // No TODAY response. Its deadline must start WEEK rather than clear the queue.
        val week = awaitCommand("GET_PLAY_STATS", after = 1)
        assertEquals("WEEK", week.getString("range"))
        receive(statsPayload(week))
        val month = awaitCommand("GET_PLAY_STATS", after = 2)
        receive(statsPayload(month))
        await { !history.state.value.loading }
        assertEquals(setOf("WEEK", "MONTH"), history.state.value.stats.keys)
        assertEquals(setOf("TODAY"), history.state.value.failedRanges)
    }

    @Test
    fun statisticsSendFailureContinuesToNextRange() = runBlocking {
        rejectCommand = { command, extra ->
            command == "GET_PLAY_STATS" && extra.optString("range") == "TODAY"
        }
        startEmptySync()
        val week = awaitCommand("GET_PLAY_STATS", after = 1)
        assertEquals("WEEK", week.getString("range"))
        receive(statsPayload(week))
        val month = awaitCommand("GET_PLAY_STATS", after = 2)
        receive(statsPayload(month))
        await { !history.state.value.loading }
        assertEquals(setOf("WEEK", "MONTH"), history.state.value.stats.keys)
        assertEquals(setOf("TODAY"), history.state.value.failedRanges)
    }

    @Test
    fun malformedStatisticsCannotPublishZeroOrNanAsSuccess() = runBlocking {
        startEmptySync()
        val today = awaitCommand("GET_PLAY_STATS")
        receive(statsPayload(today).apply { remove("totalListenMs"); remove("completionRate") })
        val week = awaitCommand("GET_PLAY_STATS", after = 1)
        receive(statsPayload(week))
        val month = awaitCommand("GET_PLAY_STATS", after = 2)
        receive(statsPayload(month))
        await { !history.state.value.loading }
        assertEquals(setOf("TODAY"), history.state.value.failedRanges)
        assertEquals(setOf("WEEK", "MONTH"), history.state.value.stats.keys)
    }

    @Test
    fun statisticsPreserveSourceSkipRateAboveOneForProtocolCompatibility() = runBlocking {
        startEmptySync()
        val today = awaitCommand("GET_PLAY_STATS")
        receive(statsPayload(today).put("skipRate", 2.0))
        val week = awaitCommand("GET_PLAY_STATS", after = 1)
        receive(statsPayload(week))
        val month = awaitCommand("GET_PLAY_STATS", after = 2)
        receive(statsPayload(month))
        await { !history.state.value.loading }
        assertEquals(2.0, requireNotNull(history.state.value.stats["TODAY"]).skipRate, 0.0)
        assertTrue(history.state.value.failedRanges.isEmpty())
    }

    @Test
    fun explicitHistoryErrorAndWriteFailureBothAllowRetry() = runBlocking {
        history.sync()
        val first = awaitCommand("GET_PLAY_HISTORY_SINCE")
        receive(
            JSONObject().put("type", "playHistoryError")
                .put("requestId", first.getString("requestId"))
                .put("responseType", "playHistorySince").put("message", "query failed")
        )
        finishStats()
        await { !history.state.value.loading }
        history.sync()
        val second = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 1)
        history.onWriteFailed(second.getString("requestId"))
        finishStats(after = 3)
        await { !history.state.value.loading }
        history.sync()
        val third = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 2)
        receive(sessionsPayload(third, listOf(row(1L))))
        finishStats(after = 6)
        await { !history.state.value.loading }
        assertEquals(1L, dao.latestSessionId())
    }

    @Test
    fun rejectedHistorySendPreservesLocalRowsAndAllowsSuccessfulRetry() = runBlocking {
        dao.commitPage(listOf(row(7L)), HistoryCursorEntity(sinceSessionId = 7L))
        rejectCommand = { command, _ -> command == "GET_PLAY_HISTORY_SINCE" }
        history.sync()
        finishStats()
        await { !history.state.value.loading }
        assertEquals(listOf(7L), dao.readBefore(Long.MAX_VALUE, 100).map { it.sessionId })
        assertEquals(7L, requireNotNull(dao.cursor()).sinceSessionId)

        rejectCommand = { _, _ -> false }
        history.sync()
        val retry = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 1)
        assertEquals(7L, retry.getLong("afterSessionId"))
        receive(sessionsPayload(retry, listOf(row(8L))))
        finishStats(after = 3)
        await { !history.state.value.loading }
        assertEquals(8L, requireNotNull(dao.cursor()).sinceSessionId)
    }

    @Test
    fun cursorInsertFailureRollsBackRealRoomPageTransactionAndCanRetry() = runBlocking {
        val originalCursor = HistoryCursorEntity(sinceSessionId = 7L, olderBeforeSessionId = 7L)
        dao.commitPage(listOf(row(7L)), originalCursor)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_history_cursor BEFORE INSERT ON history_cursor " +
                "BEGIN SELECT RAISE(ABORT, 'intentional cursor failure'); END"
        )
        history.sync()
        val first = awaitCommand("GET_PLAY_HISTORY_SINCE")
        receive(sessionsPayload(first, listOf(row(8L))))
        finishStats()
        await { !history.state.value.loading }

        // The real DAO upserts rows before this INSERT. A transaction must undo it.
        assertEquals(listOf(row(7L)), dao.readBefore(Long.MAX_VALUE, 100))
        assertEquals(originalCursor, dao.cursor())
        assertEquals(listOf(7L), history.state.value.sessions.map { it.sessionId })
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_history_cursor")
        history.sync()
        val retry = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 1)
        assertEquals(7L, retry.getLong("afterSessionId"))
        receive(sessionsPayload(retry, listOf(row(8L))))
        finishStats(after = 3)
        await { !history.state.value.loading }
        val saved = dao.readBefore(Long.MAX_VALUE, 100)
        assertNull("wire JSON null artworkId must stay nullable", saved.first().artworkId)
        assertEquals(listOf(row(8L), row(7L)), saved)
        assertEquals(8L, requireNotNull(dao.cursor()).sinceSessionId)
    }

    @Test
    fun versionOneMigrationPreservesRowsWithoutInventingCompletedCursor() = runBlocking {
        val path = File(root, "legacy-history.db")
        val oldRows = listOf(
            row(90L).copy(
                trackKey = "legacy", title = "Legacy Song", artworkId = "legacy-artwork",
                listenedMs = 1_500L, completed = true, countedPlay = true
            ),
            row(91L).copy(
                title = "Open Legacy Song", artworkId = null, endedAt = null,
                listenedMs = 300L, skipped = true, countedPlay = false
            )
        )
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            // Exact v1 table: no v2 cursor table or newly generated Room identity.
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS playback_history (" +
                    "sessionId INTEGER NOT NULL PRIMARY KEY, trackKey TEXT NOT NULL, " +
                    "title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, " +
                    "artworkId TEXT, startedAt INTEGER NOT NULL, endedAt INTEGER, " +
                    "listenedMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, " +
                    "completed INTEGER NOT NULL, skipped INTEGER NOT NULL, countedPlay INTEGER NOT NULL)"
            )
            oldRows.forEach { row ->
                old.execSQL(
                    "INSERT INTO playback_history VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(row.sessionId, row.trackKey, row.title, row.artist,
                        row.album, row.artworkId, row.startedAt, row.endedAt,
                        row.listenedMs, row.durationMs, if (row.completed) 1 else 0,
                        if (row.skipped) 1 else 0, if (row.countedPlay) 1 else 0)
                )
            }
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, PlaybackHistoryDatabase::class.java, path.absolutePath)
            .addMigrations(PlaybackHistoryDatabase.MIGRATION_1_2).build()
        var migratedCoordinator: PlaybackHistoryCoordinator? = null
        try {
            val migratedDao = migrated.historyDao()
            assertEquals(oldRows.reversed(), migratedDao.readBefore(Long.MAX_VALUE, 10))
            assertEquals(0L, migratedDao.cursor()?.sinceSessionId ?: 0L)
            migratedCoordinator = newCoordinator(migratedDao)
            migratedCoordinator.sync()
            assertEquals(0L, awaitCommand("GET_PLAY_HISTORY_SINCE").getLong("afterSessionId"))
        } finally {
            migratedCoordinator?.close()
            settle()
            migrated.close()
        }
    }

    private fun newCoordinator(historyDao: PlaybackHistoryDao = dao) = PlaybackHistoryCoordinator(
        dao = historyDao,
        dispatcher = dispatcher,
        timeoutMs = REQUEST_TIMEOUT_MS,
        send = { command, extra ->
            commands += JSONObject(extra.toString()).put("cmd", command)
            !rejectCommand(command, extra)
        }
    )

    private suspend fun restartCoordinator() {
        history.close()
        settle()
        coordinator = newCoordinator()
        settle()
    }

    private fun useRepository(): ControllerRepository {
        history.close()
        coordinator = null
        val store = ControllerLogStore(context).also { logStore = it }
        return ControllerRepository(
            context,
            ControllerPreferences(context),
            dao,
            store,
            historyDispatcher = dispatcher,
            historyTimeoutMs = REQUEST_TIMEOUT_MS
        ).also { repo ->
            repository = repo
            repo.attachTransport { bytes ->
                commands += JSONObject(String(bytes, Charsets.UTF_8))
                true
            }
        }
    }

    private suspend fun changeTrack(repo: ControllerRepository, trackId: String) {
        receiveRepository(
            JSONObject().put("type", "trackInfo").put("trackId", trackId)
                .put("generation", 2L).put("title", "New Song").put("artist", "Artist")
        )
        await { repo.playback.value.trackId == trackId }
    }

    private suspend fun startEmptySync() {
        history.sync()
        val request = awaitCommand("GET_PLAY_HISTORY_SINCE")
        receive(sessionsPayload(request, emptyList()))
    }

    private suspend fun assertFailureCanRetry(inject: suspend (JSONObject) -> Unit) {
        dao.upsert(listOf(row(7L)))
        history.sync()
        val first = awaitCommand("GET_PLAY_HISTORY_SINCE")
        inject(first)
        finishStats()
        await { !history.state.value.loading }
        assertEquals(listOf(7L), dao.readBefore(Long.MAX_VALUE, 100).map { it.sessionId })
        history.sync()
        val retry = awaitCommand("GET_PLAY_HISTORY_SINCE", after = 1)
        assertNotEquals(first.getString("requestId"), retry.getString("requestId"))
        receive(sessionsPayload(retry, listOf(row(8L))))
        finishStats(after = 3)
        await { !history.state.value.loading }
        assertEquals(8L, dao.latestSessionId())
    }

    private suspend fun assertCleared() {
        assertNull(dao.latestSessionId())
        assertTrue(dao.readBefore(Long.MAX_VALUE, 100).isEmpty())
        val cursor = dao.cursor()
        assertEquals(0L, cursor?.sinceSessionId ?: 0L)
        assertEquals(Long.MAX_VALUE, cursor?.olderBeforeSessionId ?: Long.MAX_VALUE)
        assertTrue(history.state.value.sessions.isEmpty())
        assertTrue(history.state.value.stats.isEmpty())
        assertFalse(history.state.value.clearing)
    }

    private fun receive(value: JSONObject) = history.receive(value)

    private fun receiveRepository(value: JSONObject) = requireNotNull(repository)
        .handleNotification(value.toString().toByteArray(Charsets.UTF_8))

    private fun sessionsPayload(
        request: JSONObject,
        rows: List<PlaybackHistoryEntity>,
        hasMore: Boolean = false,
        lastSessionId: Long = rows.maxOfOrNull { it.sessionId } ?: request.optLong("afterSessionId"),
        nextBefore: Long? = rows.minOfOrNull { it.sessionId }
    ): JSONObject = JSONObject()
        .put("type", if (request.optString("cmd") == "GET_PLAY_HISTORY_PAGE") "playHistoryPage" else "playHistorySince")
        .put("requestId", request.getString("requestId"))
        .put("items", JSONArray().apply { rows.forEach { put(rowJson(it)) } })
        .put("hasMore", hasMore)
        .put("lastSessionId", lastSessionId)
        .put("nextBeforeSessionId", nextBefore ?: JSONObject.NULL)

    private fun statsPayload(request: JSONObject): JSONObject = JSONObject()
        .put("type", "playStats").put("requestId", request.getString("requestId"))
        .put("range", request.getString("range"))
        .put("totalListenMs", 2_000L).put("playCount", 2).put("uniqueTrackCount", 2)
        .put("completionRate", 1.0).put("skipRate", 0.0)
        .put("topTracks", JSONArray().put(JSONObject()
            .put("trackKey", "stats-track").put("title", "Stats Song").put("artist", "Stats Artist")
            .put("listenedMs", 1_500L).put("playCount", 2)))
        .put("topArtists", JSONArray().put(JSONObject()
            .put("artist", "Stats Artist").put("listenedMs", 2_000L).put("playCount", 2)))
        .put("dailyTrend", JSONArray().put(JSONObject()
            .put("dateKey", "2026-11-06").put("listenedMs", 2_000L).put("playCount", 2)))

    private suspend fun finishStats(receive: (JSONObject) -> Unit = ::receive, after: Int = 0) {
        repeat(3) { offset ->
            val request = awaitCommand("GET_PLAY_STATS", after + offset)
            receive(statsPayload(request))
        }
    }

    private fun beginMultipart(
        request: JSONObject,
        payload: JSONObject,
        receive: (JSONObject) -> Unit = ::receive
    ): List<ByteArray> {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val chunks = bytes.toList().chunked((bytes.size + 1) / 2).map { it.toByteArray() }
        check(chunks.size == 2)
        startMultipart(request, bytes.size, chunks.size, receive = receive)
        return chunks
    }

    private fun startMultipart(
        request: JSONObject,
        size: Int,
        chunks: Int,
        responseType: String = "playHistorySince",
        receive: (JSONObject) -> Unit = ::receive
    ) = receive(
        JSONObject().put("type", "historyPayloadStart")
            .put("requestId", request.getString("requestId"))
            .put("responseType", responseType).put("size", size).put("chunks", chunks)
    )

    private fun sendChunk(
        request: JSONObject,
        index: Int,
        bytes: ByteArray,
        receive: (JSONObject) -> Unit = ::receive
    ) = receive(
        JSONObject().put("type", "historyPayloadChunk")
            .put("requestId", request.getString("requestId"))
            .put("index", index).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
    )

    private fun endMultipart(request: JSONObject, receive: (JSONObject) -> Unit = ::receive) = receive(
        JSONObject().put("type", "historyPayloadEnd")
            .put("requestId", request.getString("requestId"))
            .put("responseType", "playHistorySince")
    )

    private fun commandList(command: String): List<JSONObject> = synchronized(commands) {
        commands.filter { it.optString("cmd") == command }
    }

    private suspend fun awaitCommand(command: String, after: Int = 0): JSONObject {
        await { commandList(command).size > after }
        return commandList(command)[after]
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000L) {
        while (true) {
            dispatcher.runCurrent()
            if (condition()) return@withTimeout
            delay(10L)
        }
    }

    private suspend fun settle() {
        repeat(12) {
            dispatcher.runCurrent()
            delay(5L)
        }
    }

    private fun row(id: Long) = PlaybackHistoryEntity(
        sessionId = id,
        trackKey = "track-$id",
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        artworkId = null,
        startedAt = id * 1_000L,
        endedAt = id * 1_000L + 1_000L,
        listenedMs = 1_000L,
        durationMs = 2_000L,
        completed = false,
        skipped = false,
        countedPlay = true
    )

    private fun rowJson(row: PlaybackHistoryEntity): JSONObject = JSONObject()
        .put("sessionId", row.sessionId).put("trackKey", row.trackKey)
        .put("title", row.title).put("artist", row.artist).put("album", row.album)
        .put("artworkId", row.artworkId ?: JSONObject.NULL).put("startedAt", row.startedAt)
        .put("endedAt", row.endedAt ?: JSONObject.NULL).put("listenedMs", row.listenedMs)
        .put("durationMs", row.durationMs).put("completed", row.completed)
        .put("skipped", row.skipped).put("countedPlay", row.countedPlay)

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun waitHere() {
            entered.complete(Unit)
            release.await()
        }
    }

    private class GatedHistoryDao(private val actual: PlaybackHistoryDao) : PlaybackHistoryDao by actual {
        var commitGate: Gate? = null
        var cursorGate: Gate? = null
        var clearGate: Gate? = null
        var readGate: Gate? = null

        override suspend fun commitPage(values: List<PlaybackHistoryEntity>, cursor: HistoryCursorEntity) {
            commitGate?.let { gate ->
                gate.waitHere()
                if (commitGate === gate) commitGate = null
            }
            actual.commitPage(values, cursor)
        }

        override suspend fun cursor(): HistoryCursorEntity? {
            val value = actual.cursor()
            cursorGate?.let { gate ->
                gate.waitHere()
                if (cursorGate === gate) cursorGate = null
            }
            return value
        }

        override suspend fun clearHistory() {
            clearGate?.let { gate ->
                gate.waitHere()
                if (clearGate === gate) clearGate = null
            }
            actual.clearHistory()
        }

        override suspend fun readBefore(beforeExclusive: Long, limit: Int): List<PlaybackHistoryEntity> {
            val values = actual.readBefore(beforeExclusive, limit)
            readGate?.let { gate ->
                gate.waitHere()
                if (readGate === gate) readGate = null
            }
            return values
        }
    }

    /** No coroutines-test dependency: Room resumes are explicitly drained by await(). */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.add(block)
        }

        fun runCurrent() {
            var remaining = 10_000
            while (true) {
                val next = queued.poll() ?: return
                check(remaining-- > 0) { "history reducer did not yield" }
                next.run()
            }
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 500L
    }
}
