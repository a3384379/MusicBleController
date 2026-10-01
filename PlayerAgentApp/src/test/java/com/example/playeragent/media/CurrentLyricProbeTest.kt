package com.example.playeragent.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLyricProbeTest {
    @Test
    fun hanDetectionUsesLegacyUnicodeBlocks() {
        assertTrue(CurrentLyricProbe.containsChinese("歌词 test"))
        assertTrue(CurrentLyricProbe.containsChinese("\u3400"))
        assertTrue(CurrentLyricProbe.containsChinese("\uF900"))
        assertTrue(CurrentLyricProbe.containsChinese("〇"))
        assertFalse(CurrentLyricProbe.containsChinese("English lyrics"))
        assertFalse(CurrentLyricProbe.containsChinese("かな 😀"))
        assertFalse(CurrentLyricProbe.containsChinese(""))
    }
}
