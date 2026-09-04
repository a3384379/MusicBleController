package com.example.playeragent.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleGattServerManagerPolicyTest {
    @Test
    fun trackIdentityAlwaysUsesRealtimeQueueDuringLongMediaTransfer() {
        assertFalse(
            StatusMessageDeliveryPolicy.canUseLatestInterleavedSlot("trackInfo")
        )
        assertFalse(
            StatusMessageDeliveryPolicy.canUseLatestInterleavedSlot("playbackState")
        )
        assertFalse(
            StatusMessageDeliveryPolicy.canUseLatestInterleavedSlot("currentWord")
        )
        assertTrue(
            StatusMessageDeliveryPolicy.canUseLatestInterleavedSlot("volumeState")
        )
        assertEquals(
            BleNotifyQueue.Priority.P0_REALTIME,
            BleNotifyQueue.priorityFor("trackInfo")
        )
    }

    @Test
    fun postControlBroadcastIncludesMediaOnlyAfterIdentityChanges() {
        assertFalse(
            PostControlBroadcastPolicy.shouldIncludeTrackMedia(
                command = "NEXT",
                trackIdBeforeControl = "track-a",
                observedTrackId = "track-a"
            )
        )
        assertTrue(
            PostControlBroadcastPolicy.shouldIncludeTrackMedia(
                command = "NEXT",
                trackIdBeforeControl = "track-a",
                observedTrackId = "track-b"
            )
        )
        assertTrue(
            PostControlBroadcastPolicy.shouldIncludeTrackMedia(
                command = "PREVIOUS",
                trackIdBeforeControl = "",
                observedTrackId = "track-b"
            )
        )
        assertFalse(
            PostControlBroadcastPolicy.shouldIncludeTrackMedia(
                command = "PLAY_PAUSE",
                trackIdBeforeControl = "track-a",
                observedTrackId = "track-b"
            )
        )
        assertFalse(
            PostControlBroadcastPolicy.shouldIncludeTrackMedia(
                command = "NEXT",
                trackIdBeforeControl = "track-a",
                observedTrackId = ""
            )
        )
    }

    @Test
    fun playbackPollingSlowsOnlyAfterPausedStateIsKnown() {
        assertEquals(1_000L, BleGattServerManager.autoPushPollIntervalMs(null))
        assertEquals(1_000L, BleGattServerManager.autoPushPollIntervalMs(true))
        assertEquals(5_000L, BleGattServerManager.autoPushPollIntervalMs(false))
    }

    @Test
    fun completedAlbumArtCanBeRetransmittedAfterCooldownOrForced() {
        assertTrue(
            AlbumArtRequestPolicy.shouldAllowCompletedRequest(
                lastCompletedAtMs = null,
                nowMs = 100L,
                forceRefresh = false
            )
        )
        assertFalse(
            AlbumArtRequestPolicy.shouldAllowCompletedRequest(
                lastCompletedAtMs = 100L,
                nowMs = 200L,
                forceRefresh = false
            )
        )
        assertTrue(
            AlbumArtRequestPolicy.shouldAllowCompletedRequest(
                lastCompletedAtMs = 100L,
                nowMs = 200L,
                forceRefresh = true
            )
        )
        assertTrue(
            AlbumArtRequestPolicy.shouldAllowCompletedRequest(
                lastCompletedAtMs = 100L,
                nowMs = 100L + AlbumArtRequestPolicy.COMPLETED_REQUEST_COOLDOWN_MS,
                forceRefresh = false
            )
        )
        assertTrue(
            AlbumArtRequestPolicy.shouldResendOfferAfterRecovery(
                recoveredFromUnavailable = true,
                hadWaitingClientRequest = false
            )
        )
        assertFalse(
            AlbumArtRequestPolicy.shouldResendOfferAfterRecovery(
                recoveredFromUnavailable = true,
                hadWaitingClientRequest = true
            )
        )
        assertFalse(
            AlbumArtRequestPolicy.shouldResendOfferAfterRecovery(
                recoveredFromUnavailable = false,
                hadWaitingClientRequest = false
            )
        )
    }

    @Test
    fun subscribedLinkSilenceRequestsRateLimitedProbeInsteadOfRecovery() {
        assertFalse(
            BleSubscribedLinkPolicy.isActivityStale(
                subscribed = true,
                subscribedAtMs = 1_000L,
                lastCommandSuccessAtMs = 1_100L,
                lastNotifySuccessAtMs = 1_200L,
                nowMs = 46_200L,
                maxAgeMs = 45_000L
            )
        )
        assertTrue(
            BleSubscribedLinkPolicy.isActivityStale(
                subscribed = true,
                subscribedAtMs = 1_000L,
                lastCommandSuccessAtMs = 1_100L,
                lastNotifySuccessAtMs = 1_200L,
                nowMs = 46_201L,
                maxAgeMs = 45_000L
            )
        )
        assertFalse(
            BleSubscribedLinkPolicy.isActivityStale(
                subscribed = false,
                subscribedAtMs = 1_000L,
                lastCommandSuccessAtMs = 1_100L,
                lastNotifySuccessAtMs = 1_200L,
                nowMs = 100_000L,
                maxAgeMs = 45_000L
            )
        )
        assertTrue(
            BleSubscribedLinkPolicy.isWithoutSuccessStale(
                subscribed = true,
                subscribedAtMs = 1_000L,
                lastCommandSuccessAtMs = 0L,
                lastNotifySuccessAtMs = 0L,
                nowMs = 46_001L,
                maxAgeMs = 45_000L
            )
        )
        assertTrue(
            BleSubscribedLinkPolicy.shouldSendProbe(
                subscribedAtMs = 1_000L,
                lastCommandSuccessAtMs = 1_100L,
                lastNotifySuccessAtMs = 1_200L,
                lastProbeAtMs = 0L,
                nowMs = 46_201L,
                staleAfterMs = 45_000L,
                minimumProbeIntervalMs = 30_000L
            )
        )
        assertFalse(
            BleSubscribedLinkPolicy.shouldSendProbe(
                subscribedAtMs = 1_000L,
                lastCommandSuccessAtMs = 1_100L,
                lastNotifySuccessAtMs = 1_200L,
                lastProbeAtMs = 46_201L,
                nowMs = 51_201L,
                staleAfterMs = 45_000L,
                minimumProbeIntervalMs = 30_000L
            )
        )
        assertTrue(
            BleSubscribedLinkPolicy.shouldSendProbe(
                subscribedAtMs = 1_000L,
                lastCommandSuccessAtMs = 1_100L,
                lastNotifySuccessAtMs = 1_200L,
                lastProbeAtMs = 46_201L,
                nowMs = 76_201L,
                staleAfterMs = 45_000L,
                minimumProbeIntervalMs = 30_000L
            )
        )
    }

    @Test
    fun activeControllerDoesNotMaskAnotherSilentController() {
        val nowMs = 80_000L
        val lastNotifyByAddress = mapOf(
            "ios" to 1_000L,
            "android" to 79_500L
        )
        val staleAddresses = lastNotifyByAddress.filter { (_, lastNotifyAtMs) ->
            BleSubscribedLinkPolicy.isActivityStale(
                subscribed = true,
                subscribedAtMs = 500L,
                lastCommandSuccessAtMs = 0L,
                lastNotifySuccessAtMs = lastNotifyAtMs,
                nowMs = nowMs,
                maxAgeMs = 45_000L
            )
        }.keys

        assertEquals(setOf("ios"), staleAddresses)
    }
}
