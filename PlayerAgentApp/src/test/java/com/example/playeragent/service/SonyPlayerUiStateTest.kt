package com.example.playeragent.service

import com.example.playeragent.ble.BleHealthState
import com.example.playeragent.ui.PlayerAgentUiInputs
import com.example.playeragent.ui.PlayerAgentUiStateMapper
import com.example.playeragent.ui.SafeRepairAction
import com.example.playeragent.ui.SetupAction
import com.example.playeragent.ui.SonyPlayerUiState
import com.example.playeragent.ui.UiTextKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyPlayerUiStateTest {

    @Test
    fun lyricInternalStatesAreMappedToClearChineseText() {
        assertEquals(
            "等待 QQ 音乐生成歌词缓存",
            SonyPlayerUiState.lyricStatusText("waiting QQMusic lyric cache")
        )
        assertEquals(
            "发现歌词文件，但无法安全关联",
            SonyPlayerUiState.lyricStatusText("no safe qrc candidate")
        )
        assertEquals("正在解析歌词", SonyPlayerUiState.lyricStatusText("lyrics loading"))
    }

    @Test
    fun relinkActionOnlyAppearsForActionableFailure() {
        assertTrue(SonyPlayerUiState.lyricNeedsRelink("qrc ambiguous"))
        assertTrue(SonyPlayerUiState.lyricNeedsRelink("parse failed"))
        assertFalse(SonyPlayerUiState.lyricNeedsRelink("waiting QQMusic lyric cache"))
        assertFalse(SonyPlayerUiState.lyricNeedsRelink("ready"))
    }

    @Test
    fun identicalSlicesRemainEqualForUiDeduplication() {
        val first = SonyPlayerUiState.LyricsState("line", "ready")
        val second = SonyPlayerUiState.LyricsState("line", "ready")
        assertEquals(first, second)
    }

    @Test
    fun controllableHealthMapsToClearHomeStatusAndSummaries() {
        val state = PlayerAgentUiStateMapper.map(completeInputs(BleHealthState.CONTROLLABLE))
        assertEquals(UiTextKey.STATUS_CONTROLLABLE, state.statusTitle)
        assertEquals(UiTextKey.SUMMARY_HEALTHY, state.bleSummary)
        assertEquals(UiTextKey.SUMMARY_CONTROLLABLE, state.iPhoneSummary)
        assertEquals(UiTextKey.ARTWORK_READY, state.artworkSummary)
        assertEquals(
            UiTextKey.ARTWORK_LOADING,
            PlayerAgentUiStateMapper.map(
                completeInputs(BleHealthState.CONTROLLABLE).copy(artworkStatus = "LOADING")
            ).artworkSummary
        )
    }

    @Test
    fun setupProgressAndMissingPermissionActionAreDerivedFromRealInputs() {
        val state = PlayerAgentUiStateMapper.map(
            completeInputs(BleHealthState.ADVERTISING).copy(fileAccess = false)
        )
        assertEquals(3, state.completedSetupSteps)
        val fileStep = state.setupSteps.first { it.title == UiTextKey.SETUP_FILE_TITLE }
        assertFalse(fileStep.complete)
        assertEquals(SetupAction.OPEN_FILE_ACCESS, fileStep.action)
    }

    @Test
    fun oneClickRepairContainsOnlySafeNonDestructiveActions() {
        val state = PlayerAgentUiStateMapper.map(
            completeInputs(BleHealthState.ERROR).copy(
                notificationAccess = false,
                runtimePermissions = false,
                serviceRunning = false
            )
        )
        assertEquals(
            listOf(
                SafeRepairAction.REQUEST_RUNTIME_PERMISSIONS,
                SafeRepairAction.OPEN_NOTIFICATION_ACCESS,
                SafeRepairAction.START_SERVICE
            ),
            state.safeRepairActions
        )
        assertFalse(state.safeRepairActions.any { it.name.contains("CLEAR") || it.name.contains("REBUILD") })
    }

    @Test
    fun notificationPresentationIsDeduplicatableAndHealthSpecific() {
        val first = PlayerAgentUiStateMapper.notification(BleHealthState.CONTROLLABLE)
        val second = PlayerAgentUiStateMapper.notification(BleHealthState.CONTROLLABLE)
        assertEquals(first, second)
        assertEquals(UiTextKey.NOTIFICATION_CONTROLLABLE_TITLE, first.title)
        assertEquals(
            UiTextKey.NOTIFICATION_RECOVERING_TITLE,
            PlayerAgentUiStateMapper.notification(BleHealthState.RECOVERING).title
        )
    }

    private fun completeInputs(healthState: BleHealthState) = PlayerAgentUiInputs(
        healthState = healthState,
        serviceRunning = true,
        notificationAccess = true,
        fileAccess = true,
        accessibilityEnabled = true,
        runtimePermissions = true,
        hasPlayback = true,
        lyricStatus = "ready",
        artworkStatus = "HQ"
    )
}
