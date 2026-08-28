package com.example.playeragent.ble

import com.example.playeragent.media.CurrentWordState
import com.example.playeragent.media.CurrentWordEligibilitySnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentWordPushEngineTest {
    @Test
    fun eligibleSnapshotPublishesImmediatelyAfterGenerationObservation() {
        var sent = 0
        val engine = CurrentWordPushEngine(
            logger = {},
            sendStatusMessage = {
                sent += 1
                true
            },
            expectedGeneration = { 7L },
            currentWordEligibility = {
                eligible(word(positionMs = 1_000L, wordIndex = 1))
            },
            elapsedRealtime = { 1_000L }
        )

        engine.observeGeneration(7L)
        assertTrue(engine.pushCurrentWord() != null)
        assertEquals(1, sent)
    }

    @Test
    fun observingSameGenerationPreservesOrderingSequence() {
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
            currentWordEligibility = { eligible(state) },
            elapsedRealtime = { nowMs }
        )

        engine.observeGeneration(7L)
        assertTrue(engine.pushCurrentWord() != null)
        nowMs += 100L
        state = word(positionMs = 1_500L, wordIndex = 2)
        engine.observeGeneration(7L)
        assertTrue(engine.pushCurrentWord() != null)
        assertEquals(1L, payloads[0].getLong("seq"))
        assertEquals(2L, payloads[1].getLong("seq"))
    }

    @Test
    fun eventBarrierStillRejectsSnapshotFromOlderGeneration() {
        var sent = 0
        val engine = CurrentWordPushEngine(
            logger = {},
            sendStatusMessage = {
                sent += 1
                true
            },
            expectedGeneration = { 8L },
            currentWordEligibility = {
                eligible(word(positionMs = 1_000L, wordIndex = 1))
            },
            elapsedRealtime = { 1_000L }
        )

        engine.observeGeneration(8L)
        assertNull(engine.pushCurrentWord())
        assertEquals(0, sent)
    }

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
            currentWordEligibility = { eligible(state) },
            elapsedRealtime = { nowMs }
        )

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
            currentWordEligibility = { eligible(state) },
            elapsedRealtime = { nowMs }
        )

        assertTrue(engine.pushCurrentWord() != null)

        state = word(positionMs = 2_000L, wordIndex = 1)
        nowMs += 100L
        assertTrue(engine.pushCurrentWord() != null)
        assertEquals(2, sent)
    }

    @Test
    fun clockSyncPayloadAddsMonotonicSampleOnlyWhenNegotiated() {
        var nowMs = 1_000L
        val payloads = mutableListOf<JSONObject>()
        val engine = CurrentWordPushEngine(
            logger = {},
            sendStatusMessage = {
                payloads += JSONObject(it)
                true
            },
            includeClockSyncFields = { true },
            expectedGeneration = { 7L },
            currentWordEligibility = {
                eligible(
                    word(positionMs = 1_000L, wordIndex = 1)
                        .copy(sampleElapsedMs = 88_000L)
                )
            },
            elapsedRealtime = { nowMs }
        )

        assertTrue(engine.pushCurrentWord() != null)

        assertEquals(88_000L, payloads.single().getLong("sampleMono"))
        assertTrue(payloads.single().has("timestamp"))
        assertTrue(payloads.single().toString().toByteArray().size <= 182)
    }

    @Test
    fun introWaitNeverPublishesFutureWordSnapshot() {
        var sent = 0
        val futureWord = word(positionMs = 1_000L, wordIndex = 0)
        val engine = CurrentWordPushEngine(
            logger = {},
            sendStatusMessage = {
                sent += 1
                true
            },
            expectedGeneration = { 7L },
            currentWordEligibility = {
                eligible(futureWord).copy(
                    eligible = false,
                    reason = "INTRO_WAIT",
                    state = futureWord
                )
            },
            elapsedRealtime = { 2_000L }
        )

        engine.observeGeneration(7L)
        assertNull(engine.pushCurrentWord())
        assertEquals(0, sent)
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

    private fun eligible(state: CurrentWordState): CurrentWordEligibilitySnapshot {
        return CurrentWordEligibilitySnapshot(
            eligible = true,
            reason = "ELIGIBLE",
            trackId = state.trackId,
            generation = state.trackGeneration,
            positionMs = state.positionMs,
            positionAnchorMs = state.sampleElapsedMs,
            lineIndex = state.lineIndex,
            wordTimingStatus = "AVAILABLE",
            state = state
        )
    }
}
