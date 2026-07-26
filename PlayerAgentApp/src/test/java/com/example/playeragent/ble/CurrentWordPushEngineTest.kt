package com.example.playeragent.ble

import com.example.playeragent.media.CurrentWordState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentWordPushEngineTest {
    @Test
    fun blocksSmallPositionRegressionAndAddsOrderingFence() {
        var nowMs = 1_000L
        var state = word(positionMs = 1_000L, wordIndex = 1)
        val payloads = mutableListOf<JSONObject>()
        val engine = CurrentWordPushEngine(
            logger = {},
            sendStatusMessage = {
                payloads += JSONObject(it)
                true
            },
            expectedGeneration = { 7L },
            currentWordState = { state },
            elapsedRealtime = { nowMs }
        )

        // Establish the new-track baseline after its short safety holdoff.
        assertNull(engine.pushCurrentWord())
        nowMs += 500L
        assertTrue(engine.pushCurrentWord() != null)

        state = word(positionMs = 900L, wordIndex = 0)
        nowMs += 100L
        assertNull(engine.pushCurrentWord())

        state = word(positionMs = 1_700L, wordIndex = 2)
        nowMs += 100L
        assertTrue(engine.pushCurrentWord() != null)

        assertEquals(2, payloads.size)
        assertEquals(7L, payloads[0].getLong("generation"))
        assertEquals(1L, payloads[0].getLong("seq"))
        assertEquals(2L, payloads[1].getLong("seq"))
        assertEquals(1_700L, payloads[1].getLong("position"))
    }

    @Test
    fun acceptsLargeBackwardSeekAsNewTimeline() {
        var nowMs = 1_000L
        var state = word(positionMs = 8_000L, wordIndex = 4)
        var sent = 0
        val engine = CurrentWordPushEngine(
            logger = {},
            sendStatusMessage = {
                sent += 1
                true
            },
            expectedGeneration = { 7L },
            currentWordState = { state },
            elapsedRealtime = { nowMs }
        )

        assertNull(engine.pushCurrentWord())
        nowMs += 500L
        assertTrue(engine.pushCurrentWord() != null)

        state = word(positionMs = 2_000L, wordIndex = 1)
        nowMs += 100L
        assertTrue(engine.pushCurrentWord() != null)
        assertEquals(2, sent)
    }

    private fun word(positionMs: Long, wordIndex: Int): CurrentWordState {
        return CurrentWordState(
            trackId = "track-1",
            trackGeneration = 7L,
            lineIndex = 0,
            wordIndex = wordIndex,
            wordText = "word-$wordIndex",
            wordStartMs = positionMs,
            wordEndMs = positionMs + 400L,
            hasWordTiming = true,
            positionMs = positionMs,
            timestampMs = 10_000L + positionMs
        )
    }
}
