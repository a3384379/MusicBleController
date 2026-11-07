package com.example.controllerapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.example.controllerapp.model.ArtworkState
import com.example.controllerapp.model.ConnectionPhase
import com.example.controllerapp.model.ConnectionState
import com.example.controllerapp.model.ControllerSettings
import com.example.controllerapp.model.LyricDisplayMode
import com.example.controllerapp.model.LyricLine
import com.example.controllerapp.model.LyricsState
import com.example.controllerapp.model.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class FullLyricsInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val connected = ConnectionState(phase = ConnectionPhase.CONNECTED, characteristicReady = true)
    private fun line(i: Int) = LyricLine(i, i * 1_000L, 1_000, "歌词 line $i")

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            File(context.cacheDir, "batch4-lyrics-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun dragBrowseSurvivesTimeMediaUpdatesAndPartialCompletionUntilReturnOrTrackChange() {
        val lines = (0..30).map(::line)
        val lyrics = mutableStateOf(LyricsState(trackId = "one", partialFullLines = lines.take(25)))
        val position = mutableLongStateOf(1_000L)
        val seeks = mutableListOf<Long>()
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                FullLyricsContent(
                    connected, PlaybackState(title = "Song"), lyrics.value, ArtworkState(),
                    ControllerSettings(lyricOffsetMs = 0), position.longValue,
                    rememberNavController(), true, {}, {}, { seeks += it }, {}, {}, {}
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("full_lyrics_list").performTouchInput { swipeUp(durationMillis = 500) }
        compose.onNodeWithTag("lyrics_return_current").assertIsDisplayed()
            .assertTextEquals("回到当前歌词")
        compose.onNodeWithTag("full_lyrics_list").performScrollToKey(10)
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("full_lyrics_list").getUnclippedBoundsInRoot()
        val viewportCenter = (viewport.top.value + viewport.bottom.value) / 2f
        val visible = (0..24).mapNotNull { index ->
            val nodes = compose.onAllNodesWithTag("lyric_row_$index").fetchSemanticsNodes()
            if (nodes.isEmpty()) null else {
                val bounds = compose.onNodeWithTag("lyric_row_$index").getUnclippedBoundsInRoot()
                if (bounds.bottom <= viewport.top || bounds.top >= viewport.bottom) null else index to bounds
            }
        }
        val centered = visible.minWithOrNull(compareBy<Pair<Int, androidx.compose.ui.unit.DpRect>> { (_, bounds) ->
            when {
                viewportCenter < bounds.top.value -> bounds.top.value - viewportCenter
                viewportCenter > bounds.bottom.value -> viewportCenter - bounds.bottom.value
                else -> 0f
            }
        }.thenBy { (_, bounds) ->
            kotlin.math.abs((bounds.top.value + bounds.bottom.value) / 2f - viewportCenter)
        }.thenBy { it.first })!!.first
        val selected = (0..24).filter { index ->
            compose.onAllNodesWithTag("lyric_seek_time_$index", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(centered), selected)
        val before = compose.onNodeWithTag("lyric_row_10").getUnclippedBoundsInRoot()
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle {
            position.longValue = 20_000L
            lyrics.value = lyrics.value.copy(fullLines = lines, isFinal = true)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lyrics_return_current").assertTextEquals("回到当前歌词")
        val after = compose.onNodeWithTag("lyric_row_10").getUnclippedBoundsInRoot()
        assertEquals(before.top.value, after.top.value, 1f)
        saveScreenshot("browsing")
        assertTrue("drag and media updates must never seek", seeks.isEmpty())
        compose.onNodeWithTag("lyrics_return_current").performTouchInput { click(center) }
        compose.onNodeWithTag("lyrics_return_current").assertTextEquals("跟随中")
        compose.onNodeWithTag("lyric_row_20").assertIsDisplayed()
        saveScreenshot("following")
        compose.runOnIdle { position.longValue = 24_000L }
        compose.onNodeWithTag("lyric_row_24").assertIsDisplayed()
        compose.onNodeWithTag("full_lyrics_list").performTouchInput { swipeUp(durationMillis = 500) }
        compose.onNodeWithTag("lyrics_return_current").assertTextEquals("回到当前歌词")
        compose.runOnIdle { lyrics.value = lyrics.value.copy(trackId = "two"); position.longValue = 4_000L }
        compose.onNodeWithTag("lyrics_return_current").assertTextEquals("跟随中")
        compose.onNodeWithTag("lyric_row_4").assertIsDisplayed()
        compose.runOnIdle { position.longValue = 8_000L }
        compose.onNodeWithTag("lyric_row_8").assertIsDisplayed()
        assertTrue(seeks.isEmpty())
    }

    @Test
    fun tapSelectedLineSeeksOnceAndControlsHaveRealTouchTargets() {
        val seeks = mutableListOf<Long>()
        compose.setContent {
            MaterialTheme {
                FullLyricsContent(
                    connected, PlaybackState(), LyricsState(trackId = "one", fullLines = (0..20).map(::line), isFinal = true),
                    ArtworkState(), ControllerSettings(lyricOffsetMs = 0), 0,
                    rememberNavController(), true, {}, {}, { seeks += it }, {}, {}, {}
                )
            }
        }
        compose.waitForIdle()
        SystemClock.sleep(300)
        compose.onNodeWithTag("lyric_row_0").performTouchInput { click(center) }
        compose.onNodeWithTag("lyrics_return_current").assertTextEquals("回到当前歌词")
        assertTrue(seeks.isEmpty())
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("lyric_seek_time_0", useUnmergedTree = true).assertExists()
        val returnBounds = compose.onNodeWithTag("lyrics_return_current").getUnclippedBoundsInRoot()
        assertTrue(returnBounds.bottom - returnBounds.top >= 48.dp)
        val time = compose.onNodeWithTag("lyric_seek_time_0", useUnmergedTree = true)
        val timeBounds = time.getUnclippedBoundsInRoot()
        assertTrue(timeBounds.bottom - timeBounds.top >= 48.dp)
        compose.onNodeWithTag("lyric_row_0").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf(0L), seeks)
        compose.onNodeWithTag("lyrics_return_current").assertTextEquals("跟随中")
        SystemClock.sleep(300)
        compose.onNodeWithTag("lyric_row_0").performTouchInput { click(center) }
        compose.onNodeWithTag("lyric_seek_time_0", useUnmergedTree = true)
            .performTouchInput { click(center) }
        assertEquals(listOf(0L, 0L), seeks)
    }

    @Test
    fun nonzeroWindowSelectionKeepsItsIdentityWhenFullLyricsArrive() {
        val all = (0..60).map(::line)
        val lyrics = mutableStateOf(LyricsState(trackId = "one", windowLines = all.subList(20, 25)))
        val seeks = mutableListOf<Long>()
        compose.setContent {
            MaterialTheme {
                FullLyricsContent(
                    connected, PlaybackState(), lyrics.value, ArtworkState(),
                    ControllerSettings(lyricOffsetMs = 0), 22_000,
                    rememberNavController(), true, {}, {}, { seeks += it }, {}, {}, {}
                )
            }
        }
        compose.waitForIdle()
        SystemClock.sleep(300)
        compose.onNodeWithTag("lyric_row_22").assertIsDisplayed()
            .performTouchInput { click(center) }
        compose.onNodeWithTag("lyric_seek_time_22", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { lyrics.value = lyrics.value.copy(fullLines = all, isFinal = true) }
        compose.onNodeWithTag("lyric_seek_time_22", useUnmergedTree = true).assertIsDisplayed()
            .performTouchInput { click(center) }
        assertEquals(listOf(22_000L), seeks)
    }

    @Test
    fun fullLyricsArrivingDuringDragKeepTheVisibleSelectionIdentity() {
        val all = (0..60).map(::line)
        val lyrics = mutableStateOf(LyricsState(trackId = "one", windowLines = all.subList(20, 40)))
        val seeks = mutableListOf<Long>()
        compose.setContent {
            MaterialTheme {
                FullLyricsContent(
                    connected, PlaybackState(), lyrics.value, ArtworkState(),
                    ControllerSettings(lyricOffsetMs = 0), 22_000,
                    rememberNavController(), true, {}, {}, { seeks += it }, {}, {}, {}
                )
            }
        }
        compose.waitForIdle()
        val list = compose.onNodeWithTag("full_lyrics_list")
        list.performTouchInput { down(center); moveBy(Offset(0f, -80f)) }
        compose.onNodeWithTag("lyrics_return_current").assertTextEquals("回到当前歌词")
        fun selectedIds(): List<Int> = (20..39).filter { index ->
            compose.onAllNodesWithTag("lyric_seek_time_$index", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val before = selectedIds().single()
        compose.runOnIdle { lyrics.value = lyrics.value.copy(fullLines = all, isFinal = true) }
        assertEquals(listOf(before), selectedIds())
        list.performTouchInput { up() }
        assertTrue(seeks.isEmpty())
    }

    @Test
    fun browsingSelectsLongLineContainingViewportCenterEvenWhenNextCenterIsCloser() {
        val longLine = LyricLine(0, 0, 1_000, "long lyrics 中文 English ".repeat(100))
        val lines = listOf(longLine) + (1..20).map(::line)
        val seeks = mutableListOf<Long>()
        compose.setContent {
            MaterialTheme {
                FullLyricsContent(
                    connected, PlaybackState(), LyricsState(trackId = "one", fullLines = lines, isFinal = true),
                    ArtworkState(), ControllerSettings(lyricOffsetMs = 0), 0,
                    rememberNavController(), true, {}, {}, { seeks += it }, {}, {}, {}
                )
            }
        }
        compose.waitForIdle()
        val list = compose.onNodeWithTag("full_lyrics_list")
        list.performTouchInput { swipeUp(durationMillis = 500) }
        compose.onNodeWithTag("lyrics_return_current").assertTextEquals("回到当前歌词")
        list.performScrollToKey(1)
        list.performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.8f, durationMillis = 1_000) }
        compose.waitForIdle()
        val viewport = list.getUnclippedBoundsInRoot()
        val longBounds = compose.onNodeWithTag("lyric_row_0").getUnclippedBoundsInRoot()
        val nextBounds = compose.onNodeWithTag("lyric_row_1").getUnclippedBoundsInRoot()
        val center = (viewport.top.value + viewport.bottom.value) / 2f
        assertTrue(longBounds.top.value < center && longBounds.bottom.value > center)
        assertTrue(nextBounds.top.value > center && nextBounds.top.value < viewport.bottom.value)
        assertTrue(kotlin.math.abs((nextBounds.top.value + nextBounds.bottom.value) / 2f - center) <
            kotlin.math.abs((longBounds.top.value + longBounds.bottom.value) / 2f - center))
        compose.onNodeWithTag("lyric_seek_time_0", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("lyric_seek_time_1", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(seeks.isEmpty())
    }

    @Test
    fun longOriginalAndAuxiliaryTextWrapWithoutSelectionOrCurrentChangingGeometry() {
        val original = "很长的歌词 mixed English and 中文 ".repeat(18)
        val translation = "Long translated text 翻译 ".repeat(18)
        val romanization = "romanization text ".repeat(18)
        val current = mutableStateOf(false)
        val selected = mutableStateOf(false)
        val mode = mutableStateOf(LyricDisplayMode.ALL)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    Box(Modifier.size(320.dp, 360.dp)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            FullLyricRow(
                                LyricLine(8, 8_000, 1_000, original, translation, romanization),
                                8_000, mode.value, current.value, selected.value, true, {}, {}
                            )
                        }
                    }
                }
            }
        }
        fun textLayout(tag: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val initial = textLayout("lyric_original_8")
        assertTrue(initial.lineCount > 3)
        assertFalse(initial.hasVisualOverflow)
        saveScreenshot("long-text-large")
        assertFalse(textLayout("lyric_aux_8_0").hasVisualOverflow)
        assertFalse(textLayout("lyric_aux_8_1").hasVisualOverflow)
        compose.onNodeWithTag("lyric_original_8", useUnmergedTree = true).assertTextEquals(original)
        compose.runOnIdle { current.value = true; selected.value = true }
        val active = textLayout("lyric_original_8")
        assertEquals(initial.size, active.size)
        assertEquals(initial.lineCount, active.lineCount)
        assertFalse(active.hasVisualOverflow)
        compose.runOnIdle { mode.value = LyricDisplayMode.ORIGINAL_TRANSLATION }
        compose.onNodeWithTag("lyric_aux_8_0", useUnmergedTree = true).assertTextEquals(translation)
        compose.runOnIdle { mode.value = LyricDisplayMode.ORIGINAL_ROMANIZATION }
        compose.onNodeWithTag("lyric_aux_8_0", useUnmergedTree = true).assertTextEquals(romanization)
        compose.runOnIdle { mode.value = LyricDisplayMode.ORIGINAL }
        compose.onNodeWithTag("lyric_aux_8_0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun lyricColorsMeetContrastOnWorstCaseWhiteArtwork() {
        val base = Color(0xff090b10)
        val brightArt = Color.White.copy(alpha = 0.76f).compositeOver(base)
        val background = Color(0xc8090b10).compositeOver(brightArt)
        val ordinary = Color.White.copy(alpha = 0.76f).compositeOver(background)
        val highlight = Color(0xff8ee6a3)
        fun contrast(color: Color) = (color.luminance() + 0.05f) / (background.luminance() + 0.05f)
        assertTrue(contrast(ordinary) >= 4.5f)
        assertTrue(contrast(highlight) >= 4.5f)
    }

    @Test
    fun compactLandscapeAndLargeFontKeepLyricsAndReturnReachableInBothThemes() {
        val dark = mutableStateOf(false)
        val seeks = mutableListOf<Long>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.size(400.dp, 320.dp)) {
                        FullLyricsContent(
                            connected, PlaybackState(),
                            LyricsState(trackId = "one", fullLines = (0..20).map(::line), isFinal = true),
                            ArtworkState(), ControllerSettings(lyricOffsetMs = 0), 0,
                            rememberNavController(), true, {}, {}, { seeks += it }, {}, {}, {}
                        )
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark.value = isDark }
            val list = compose.onNodeWithTag("full_lyrics_list")
            val bounds = list.getUnclippedBoundsInRoot()
            assertTrue("lyrics must retain room for two large lines", bounds.bottom - bounds.top >= 112.dp)
            val statusBounds = compose.onNodeWithTag("lyrics_status", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue("status must not cover lyrics", statusBounds.bottom <= bounds.top)
            val textBounds = compose.onNodeWithTag("lyric_original_0", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue("the current original line must be fully readable",
                textBounds.top >= bounds.top && textBounds.bottom <= bounds.bottom)
            list.performTouchInput {
                swipeUp(durationMillis = 100)
                down(center)
            }
            SystemClock.sleep(600)
            list.performTouchInput { up() }
            compose.onNodeWithTag("lyrics_return_current").assertIsDisplayed()
                .assertTextEquals("回到当前歌词")
            assertTrue("stopping inertia must not seek", seeks.isEmpty())
            saveScreenshot(if (isDark) "compact-dark" else "compact-light")
            compose.onNodeWithTag("lyrics_return_current").performTouchInput { click(center) }
            compose.onNodeWithTag("lyrics_return_current").assertTextEquals("跟随中")
            SystemClock.sleep(300)
            compose.onNodeWithTag("lyric_original_0", useUnmergedTree = true)
                .performTouchInput { click(center) }
            compose.onNodeWithTag("lyric_seek_time_0", useUnmergedTree = true).assertExists()
            assertTrue("first lyric touch only selects", seeks.isEmpty())
            compose.onNodeWithTag("lyrics_return_current").performTouchInput { click(center) }
        }
    }
}
