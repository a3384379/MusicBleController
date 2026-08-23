package com.example.playeragent.service

import com.example.playeragent.ui.SonyPlayerUiState
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
}
