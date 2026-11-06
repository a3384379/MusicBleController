package com.example.controllerapp.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.controllerapp.ControllerApplication
import com.example.controllerapp.MainActivity
import com.example.controllerapp.data.HistoryCursorEntity
import com.example.controllerapp.data.PlaybackHistoryEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class HistoryActivityRecreationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val permissions = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val packageName = instrumentation.targetContext.packageName
                val required = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(Manifest.permission.BLUETOOTH_SCAN)
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                required.forEach { instrumentation.uiAutomation.grantRuntimePermission(packageName, it) }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permissions).around(compose)

    @Test
    fun recreationRetainsOlderReadingAnchorAndReturnsToRecent() {
        val application = ApplicationProvider.getApplicationContext<ControllerApplication>()
        val repository = application.repository
        val dao = application.database.historyDao()
        try {
            // Use the real Activity and repository, but keep history sync offline.
            // Its failed requests settle normally; no transport stub can be lost on recreate.
            compose.waitForIdle()
            repository.attachTransport(null)
            repository.clearLocalHistory()
            awaitHistory { !repository.history.value.clearing && repository.history.value.sessions.isEmpty() }
            runBlocking {
                dao.commitPage((1L..1_020L).map(::historyRow), HistoryCursorEntity(sinceSessionId = 1_020L))
            }
            repository.showRecentHistory()
            awaitHistory { repository.history.value.sessions.firstOrNull()?.sessionId == 1_020L }

            compose.onNodeWithContentDescription("打开菜单").performClick()
            compose.onNodeWithText("播放历史").performClick()
            compose.onNodeWithTag("history_list").assertIsDisplayed()
            awaitHistory {
                !repository.history.value.loading &&
                    repository.history.value.status.contains("历史命令未发送")
            }
            repeat(2) {
                compose.onNodeWithTag("history_list").performScrollToKey("history_load_more")
                compose.onNodeWithTag("history_load_more").assertIsDisplayed().performClick()
                awaitHistory {
                    !repository.history.value.loading &&
                        repository.history.value.sessions.lastOrNull()?.sessionId == if (it == 0) 21L else 1L
                }
            }
            compose.onNodeWithTag("history_show_recent").assertIsDisplayed()
            val anchor = (30L downTo 21L).firstOrNull {
                compose.onNodeWithTag("history_session_$it").isDisplayed()
            } ?: error("No older-window session visible before Activity recreation")

            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            awaitHistory {
                !repository.history.value.loading && repository.history.value.showingOlderWindow &&
                    repository.history.value.status.contains("历史命令未发送")
            }
            compose.onNodeWithTag("history_session_$anchor").assertIsDisplayed()
            compose.onNodeWithTag("history_show_recent").assertIsDisplayed().performClick()
            awaitHistory {
                !repository.history.value.showingOlderWindow &&
                    repository.history.value.sessions.size == 500 &&
                    repository.history.value.sessions.firstOrNull()?.sessionId == 1_020L
            }
            compose.onNodeWithText("最近播放").assertIsDisplayed()
            compose.onNodeWithTag("history_session_1020").assertIsDisplayed()
        } finally {
            repository.attachTransport(null)
            repository.clearLocalHistory()
            awaitHistory { !repository.history.value.clearing && repository.history.value.sessions.isEmpty() }
        }
    }

    private fun awaitHistory(condition: () -> Boolean) {
        runBlocking { withTimeout(5_000L) { while (!condition()) delay(10L) } }
    }

    private fun historyRow(id: Long) = PlaybackHistoryEntity(
        sessionId = id, trackKey = "recreation-track-$id", title = "Song $id", artist = "Test Artist",
        album = "Test Album", artworkId = null, startedAt = id * 1_000L,
        endedAt = id * 1_000L + 1_000L, listenedMs = 1_000L, durationMs = 2_000L,
        completed = false, skipped = false, countedPlay = true
    )
}
