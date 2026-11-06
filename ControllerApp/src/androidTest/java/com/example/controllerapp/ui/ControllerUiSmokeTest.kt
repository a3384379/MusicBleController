package com.example.controllerapp.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ApplicationProvider
import com.example.controllerapp.ControllerApplication
import com.example.controllerapp.ControllerRepository
import com.example.controllerapp.ControllerViewModel
import com.example.controllerapp.data.HistoryCursorEntity
import com.example.controllerapp.data.PlaybackHistoryEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Collections

class ControllerUiSmokeTest {
    @get:Rule
    val compose = createComposeRule()
    private var historyRepository: ControllerRepository? = null
    private var historyCommands: List<JSONObject>? = null

    @Test
    fun playerAndFullLyricsNavigationRender() {
        setControllerContent()

        compose.onNodeWithText("QQ 音乐").assertIsDisplayed()
        compose.onNodeWithTag("compact_lyrics").assertIsDisplayed().performClick()
        compose.onNodeWithText("歌词").assertIsDisplayed()
        compose.onNodeWithText("跟随中").assertIsDisplayed()
    }

    @Test
    fun historySettingsAndHealthRoutesRenderFromDailyMenu() {
        setControllerContent()

        compose.onNodeWithContentDescription("打开菜单").performClick()
        compose.onNodeWithText("播放历史").performClick()
        compose.onNodeWithText("播放历史").assertIsDisplayed()
        compose.onNodeWithText("最近播放").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithContentDescription("打开菜单").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("性能模式").assertIsDisplayed()
        compose.onNodeWithText("本地封面增强").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("调试").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithContentDescription("打开菜单").performClick()
        compose.onNodeWithText("调试工具").performClick()
        compose.onNodeWithText("启动 RFCOMM 兼容模式")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("停止 RFCOMM 并恢复 BLE")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithContentDescription("打开菜单").performClick()
        compose.onNodeWithText("系统健康").performClick()
        compose.onNodeWithText("系统健康").assertIsDisplayed()
    }

    @Test
    fun historySyncKeepsReadingRowAndStatusVisibleThenReturnsToRecent() {
        val application = ApplicationProvider.getApplicationContext<ControllerApplication>()
        val repository = application.repository
        val dao = application.database.historyDao()
        val commands = Collections.synchronizedList(mutableListOf<JSONObject>())
        historyRepository = repository
        historyCommands = commands
        try {
            File(application.cacheDir, "history-ui-anchor.png").delete()
            File(application.cacheDir, "history-ui-recent.png").delete()
            repository.attachTransport(null)
            repository.clearLocalHistory()
            waitForHistory("clear before seed") {
                !repository.history.value.clearing && repository.history.value.sessions.isEmpty()
            }
            runBlocking {
                dao.commitPage((1L..1_000L).map(::historyRow), HistoryCursorEntity(sinceSessionId = 1_000L))
            }
            repository.showRecentHistory()
            waitForHistory("seed visible recent window") {
                repository.history.value.sessions.size == 500 &&
                    repository.history.value.sessions.firstOrNull()?.sessionId == 1_000L
            }
            repository.attachTransport { bytes ->
                commands += JSONObject(String(bytes, Charsets.UTF_8))
                true
            }
            setControllerContent()
            historyPhase("navigate to history")
            compose.onNodeWithContentDescription("打开菜单").performClick()
            compose.onNodeWithText("播放历史").performClick()
            // Drive navigation/LaunchedEffect before awaiting work on the BLE thread.
            compose.onNodeWithTag("history_list").assertIsDisplayed()

            // Entering the real route performs its normal initial sync.
            val initial = awaitHistoryCommand(commands, "GET_PLAY_HISTORY_SINCE")
            assertEquals(1_000L, initial.getLong("afterSessionId"))
            receiveHistory(repository, initial, emptyList())
            finishHistoryStatistics(repository, commands)
            waitForHistory("initial sync completed") { !repository.history.value.loading }
            compose.waitForIdle()
            historyPhase("scroll to row 510")
            compose.onNodeWithTag("history_list").performScrollToKey(510L)
            compose.onNodeWithTag("history_session_510").assertIsDisplayed()

            // The fixed sync action remains usable while this older row is on screen.
            historyPhase("sync while reading row 510")
            compose.onNodeWithContentDescription("同步历史").assertIsDisplayed().performClick()
            val update = awaitHistoryCommand(commands, "GET_PLAY_HISTORY_SINCE", after = 1)
            assertEquals(1_000L, update.getLong("afterSessionId"))
            receiveHistory(repository, update, (1_001L..1_020L).map(::historyRow))
            finishHistoryStatistics(repository, commands, after = 3)
            waitForHistory("new rows and stats committed while reading 510") {
                !repository.history.value.loading &&
                    repository.history.value.sessions.firstOrNull()?.sessionId == 1_020L
            }
            compose.waitForIdle()
            compose.onNodeWithTag("history_session_510").assertIsDisplayed()
            compose.onNodeWithTag("history_status").assertIsDisplayed().assertTextEquals("同步完成")
            saveHistoryScreenshot(application, "history-ui-anchor.png")

            historyPhase("load older window")
            compose.onNodeWithTag("history_list").performScrollToKey("history_load_more")
            val readingAnchor = (510L downTo 501L).firstOrNull {
                compose.onNodeWithTag("history_session_$it").isDisplayed()
            } ?: error("No recent-window session visible before loading older history")
            compose.onNodeWithTag("history_load_more").assertIsDisplayed().performClick()
            waitForHistory("load more moved to older window ending at 1") {
                !repository.history.value.loading && repository.history.value.showingOlderWindow &&
                    repository.history.value.sessions.lastOrNull()?.sessionId == 1L
            }
            compose.onNodeWithTag("history_session_$readingAnchor").assertIsDisplayed()
            historyPhase("return to recent window")
            compose.onNodeWithTag("history_show_recent").assertIsDisplayed().performClick()
            waitForHistory("return recent restored 500 rows starting at 1020") {
                !repository.history.value.showingOlderWindow &&
                    repository.history.value.sessions.size == 500 &&
                    repository.history.value.sessions.firstOrNull()?.sessionId == 1_020L
            }
            compose.waitForIdle()
            compose.onNodeWithText("最近播放").assertIsDisplayed()
            compose.onNodeWithTag("history_session_1020").assertIsDisplayed()
            saveHistoryScreenshot(application, "history-ui-recent.png")
        } finally {
            historyPhase("clear history in teardown")
            repository.attachTransport(null)
            repository.clearLocalHistory()
            waitForHistory("teardown cleared application database") {
                !repository.history.value.clearing && repository.history.value.sessions.isEmpty() &&
                    repository.history.value.stats.isEmpty()
            }
            runBlocking { assertNull(dao.latestSessionId()) }
            historyRepository = null
            historyCommands = null
        }
    }

    private fun receiveHistory(
        repository: ControllerRepository,
        request: JSONObject,
        rows: List<PlaybackHistoryEntity>
    ) {
        val payload = JSONObject().put("type", "playHistorySince")
            .put("requestId", request.getString("requestId"))
            .put("lastSessionId", rows.maxOfOrNull { it.sessionId } ?: request.getLong("afterSessionId"))
            .put("hasMore", false)
            .put("items", JSONArray().apply {
                rows.forEach { row ->
                    put(JSONObject().put("sessionId", row.sessionId).put("trackKey", row.trackKey)
                        .put("title", row.title).put("artist", row.artist).put("album", row.album)
                        .put("artworkId", JSONObject.NULL).put("startedAt", row.startedAt)
                        .put("endedAt", row.endedAt).put("listenedMs", row.listenedMs)
                        .put("durationMs", row.durationMs).put("completed", row.completed)
                        .put("skipped", row.skipped).put("countedPlay", row.countedPlay))
                }
            })
        repository.handleNotification(payload.toString().toByteArray(Charsets.UTF_8))
    }

    private fun finishHistoryStatistics(
        repository: ControllerRepository,
        commands: List<JSONObject>,
        after: Int = 0
    ) {
        repeat(3) { offset ->
            val request = awaitHistoryCommand(commands, "GET_PLAY_STATS", after + offset)
            val stats = JSONObject().put("type", "playStats")
                .put("requestId", request.getString("requestId")).put("range", request.getString("range"))
                .put("totalListenMs", 2_000L).put("playCount", 2).put("uniqueTrackCount", 2)
                .put("completionRate", 1.0).put("skipRate", 0.0)
                .put("topTracks", JSONArray()).put("topArtists", JSONArray()).put("dailyTrend", JSONArray())
            repository.handleNotification(stats.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun awaitHistoryCommand(commands: List<JSONObject>, command: String, after: Int = 0): JSONObject {
        fun matching() = synchronized(commands) { commands.filter { it.optString("cmd") == command } }
        waitForHistory("await $command ordinal=${after + 1}") { matching().size > after }
        return matching()[after]
    }

    private fun waitForHistory(phase: String, condition: () -> Boolean) {
        historyPhase("wait: $phase")
        try {
            runBlocking { withTimeout(5_000L) { while (!condition()) delay(10L) } }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val state = historyRepository?.history?.value
            val requests = historyCommands?.let { values ->
                synchronized(values) {
                    values.takeLast(20).map {
                        "${it.optString("cmd")}:${it.optString("requestId")}" +
                            ":range=${it.optString("range")}:after=${it.optLong("afterSessionId", -1L)}"
                    }
                }
            }
            throw AssertionError(
                "History UI phase '$phase' timed out: loading=${state?.loading}, clearing=${state?.clearing}, " +
                    "status=${state?.status}, rows=${state?.sessions?.size}, " +
                    "first=${state?.sessions?.firstOrNull()?.sessionId}, last=${state?.sessions?.lastOrNull()?.sessionId}, " +
                    "older=${state?.showingOlderWindow}, stats=${state?.stats?.keys}, failed=${state?.failedRanges}, " +
                    "commands=$requests",
                error
            )
        }
        historyPhase("completed: $phase")
    }

    private fun historyPhase(phase: String) {
        Log.i("HistoryUiRegression", phase)
    }

    private fun saveHistoryScreenshot(application: ControllerApplication, name: String) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(application.cacheDir, name).outputStream().use { output ->
                assertTrue("could not save history UI evidence", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun historyRow(id: Long) = PlaybackHistoryEntity(
        sessionId = id, trackKey = "ui-track-$id", title = "Song $id", artist = "Test Artist",
        album = "Test Album", artworkId = null, startedAt = id * 1_000L,
        endedAt = id * 1_000L + 1_000L, listenedMs = 1_000L, durationMs = 2_000L,
        completed = false, skipped = false, countedPlay = true
    )

    private fun setControllerContent() {
        val application =
            ApplicationProvider.getApplicationContext<ControllerApplication>()
        val viewModel = ControllerViewModel(application)
        compose.setContent {
            ControllerTheme {
                ControllerApp(viewModel)
            }
        }
    }
}
