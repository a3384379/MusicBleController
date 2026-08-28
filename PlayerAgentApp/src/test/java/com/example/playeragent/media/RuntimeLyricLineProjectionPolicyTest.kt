package com.example.playeragent.media

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeLyricLineProjectionPolicyTest {
    @Test
    fun selectsLatestNonEmptyLineAtProjectedPosition() {
        val lines = listOf(
            RuntimeLyricLine(0L, "first"),
            RuntimeLyricLine(1_000L, ""),
            RuntimeLyricLine(2_000L, "second")
        )

        assertEquals(
            "first",
            RuntimeLyricLineProjectionPolicy.currentLineText(lines, 1_500L)
        )
        assertEquals(
            "second",
            RuntimeLyricLineProjectionPolicy.currentLineText(lines, 2_500L)
        )
        assertEquals(
            "",
            RuntimeLyricLineProjectionPolicy.currentLineText(lines, -1L)
        )
    }
}
