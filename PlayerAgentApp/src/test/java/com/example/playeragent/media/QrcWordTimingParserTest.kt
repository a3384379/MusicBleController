package com.example.playeragent.media

import org.junit.Assert.assertEquals
import org.junit.Test

class QrcWordTimingParserTest {
    @Test
    fun `absolute QRC markers belong to the text before each marker`() {
        val parsed = QrcLyricUtils.parseQrcLineBody(
            lineStartMs = 100_208,
            body = "We'll (100208,200)light (100408,230)up (100638,240)the (100878,210)sky(101088,1040)"
        )

        assertEquals("We'll light up the sky", parsed.text)
        assertEquals(listOf("We'll ", "light ", "up ", "the ", "sky"), parsed.words.map { it.text })
        assertEquals(
            listOf(100_208L, 100_408L, 100_638L, 100_878L, 101_088L),
            parsed.words.map { it.startMs }
        )
        assertEquals(listOf(200L, 230L, 240L, 210L, 1_040L), parsed.words.map { it.durationMs })
    }

    @Test
    fun `relative QRC markers are normalized against line start`() {
        val parsed = QrcLyricUtils.parseQrcLineBody(
            lineStartMs = 10_000,
            body = "你(0,120)好(120,180)"
        )

        assertEquals(listOf("你", "好"), parsed.words.map { it.text })
        assertEquals(listOf(10_000L, 10_120L), parsed.words.map { it.startMs })
    }
}
