package com.example.playeragent.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTraceTest {
    @Test
    fun ringBufferKeepsNewestEventsInOrder() {
        var now = 10L
        val buffer = RealtimeTraceBuffer(capacity = 3) { now++ }

        buffer.append(stage = "one")
        buffer.append(stage = "two")
        buffer.append(stage = "three")
        buffer.append(stage = "four")

        assertEquals(listOf("two", "three", "four"), buffer.snapshot().map { it.stage })
        assertEquals(listOf(2L, 3L, 4L), buffer.snapshot().map { it.sequence })
    }

    @Test
    fun monotonicClockNeverRegresses() {
        val samples = ArrayDeque(listOf(100L, 90L, 120L))
        val buffer = RealtimeTraceBuffer(capacity = 4) { samples.removeFirst() }

        buffer.append(stage = "one")
        buffer.append(stage = "two")
        buffer.append(stage = "three")

        assertEquals(listOf(100L, 100L, 120L), buffer.snapshot().map { it.monoMs })
    }

    @Test
    fun traceLineHasStableFieldsAndNoPayloadBody() {
        val event = RealtimeTraceBuffer(capacity = 1) { 55L }.append(
            stage = "notifyEnqueued",
            trackId = "track 1",
            payloadType = "currentWord",
            result = "ok"
        )
        val line = event.logLine()

        assertTrue(line.startsWith("[RealtimeTrace] side=sony stage=notifyEnqueued monoMs=55"))
        assertTrue(line.contains("trackId=track_1"))
        assertTrue(line.contains("payloadType=currentWord"))
        assertFalse(line.contains("lyrics="))
        assertFalse(line.contains("bytes="))
    }

    @Test
    fun correlationFieldsKeepCommandTrackGenerationAndTransferIdentity() {
        val event = RealtimeTraceBuffer(capacity = 1) { 77L }.append(
            stage = "fullLyricsSendStart",
            commandSeq = 42L,
            commandType = "GET_FULL_LYRICS",
            trackId = "track-safe-id",
            generation = 9L,
            transferId = "transfer-3"
        )

        assertEquals(42L, event.commandSeq)
        assertEquals("track-safe-id", event.trackId)
        assertEquals(9L, event.generation)
        assertEquals("transfer-3", event.transferId)
    }

    @Test
    fun phaseFourFieldsArePrivacySafeAndRoundTrip() {
        val event = RealtimeTraceBuffer(capacity = 1) { 88L }.append(
            stage = "currentWordEligible",
            trackId = "track-safe-id",
            generation = 3L,
            handoffId = "command-42",
            triggerType = "IOS_NEXT",
            positionAnchorMs = 700L,
            lineIndex = 2,
            wordTimingStatus = "AVAILABLE",
            cacheSource = "PARSED",
            failureReason = null
        )

        assertEquals("command-42", event.handoffId)
        assertEquals("IOS_NEXT", event.triggerType)
        assertTrue(event.logLine().contains("wordTimingStatus=AVAILABLE"))
        assertTrue(event.logLine().length < 300)
        assertFalse(event.logLine().contains(" commandSeq=-"))
        assertFalse(event.logLine().contains("wordText="))
    }

    @Test
    fun commandHandoffIsPromotedByMatchingMediaObservation() {
        TrackHandoffTraceCoordinator.clear()
        val command = TrackHandoffTraceCoordinator.observeCommand(
            commandSeq = 7L,
            commandType = "NEXT",
            nowMs = 100L
        )
        val accepted = TrackHandoffTraceCoordinator.observeMediaSessionMetadata(
            trackId = "track-a",
            positionAnchorMs = 120L,
            nowMs = 120L
        )

        assertEquals("command-7", command?.handoffId)
        assertEquals(command?.handoffId, accepted?.handoffId)
        assertEquals(TrackHandoffTriggerType.IOS_NEXT, accepted?.triggerType)
        assertEquals(accepted, TrackHandoffTraceCoordinator.contextFor("track-a"))
        TrackHandoffTraceCoordinator.clear()
    }

    @Test
    fun changedNotificationCreatesBoundedAutomaticHandoff() {
        TrackHandoffTraceCoordinator.clear()
        TrackHandoffTraceCoordinator.observeNotificationMetadata(
            identityKey = "notification-a",
            nowMs = 200L
        )
        val accepted = TrackHandoffTraceCoordinator.observeMediaSessionMetadata(
            trackId = "track-b",
            positionAnchorMs = 220L,
            nowMs = 220L
        )

        assertTrue(accepted?.handoffId?.startsWith("sony-200-") == true)
        assertEquals(TrackHandoffTriggerType.UNKNOWN, accepted?.triggerType)
        TrackHandoffTraceCoordinator.clear()
    }

    @Test
    fun notificationFollowingMediaObservationKeepsCommandHandoff() {
        val original = RealtimeTrace.enabled
        try {
            RealtimeTrace.clear()
            RealtimeTrace.enabled = true
            TrackHandoffTraceCoordinator.clear()
            TrackHandoffTraceCoordinator.observeCommand(
                commandSeq = 11L,
                commandType = "NEXT",
                nowMs = 1_000L
            )
            TrackHandoffTraceCoordinator.observeMediaSessionMetadata(
                trackId = "track-c",
                positionAnchorMs = 1_050L,
                nowMs = 1_050L
            )
            TrackHandoffTraceCoordinator.observeNotificationMetadata(
                identityKey = "notification-c",
                nowMs = 1_400L
            )

            val notification = RealtimeTrace.snapshot().last {
                it.stage == "notificationMetadataObserved"
            }
            assertEquals("command-11", notification.handoffId)
            assertEquals("IOS_NEXT", notification.triggerType)
        } finally {
            TrackHandoffTraceCoordinator.clear()
            RealtimeTrace.enabled = original
            RealtimeTrace.clear()
        }
    }

    @Test
    fun disabledTraceDoesNotStoreEvents() {
        val original = RealtimeTrace.enabled
        try {
            RealtimeTrace.clear()
            RealtimeTrace.enabled = false
            assertNull(RealtimeTrace.record(stage = "commandReceived"))
            assertTrue(RealtimeTrace.snapshot().isEmpty())
        } finally {
            RealtimeTrace.enabled = original
            RealtimeTrace.clear()
        }
    }
}
