package com.example.controllerapp.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaIdentityPolicyTest {
    @Test
    fun repeatedTrackWithNewGenerationIsNewMedia() {
        assertTrue(MediaIdentityPolicy.isNewMedia("same", 4L, "same", 5L))
    }

    @Test
    fun legacyPeerWithoutGenerationKeepsTrackCompatibility() {
        assertFalse(MediaIdentityPolicy.isNewMedia("same", 4L, "same", 0L))
        assertTrue(MediaIdentityPolicy.generationMatches(4L, 0L))
    }

    @Test
    fun transferGenerationRejectsStaleReplay() {
        assertFalse(MediaIdentityPolicy.generationMatches(5L, 4L))
        assertTrue(MediaIdentityPolicy.generationMatches(5L, 5L))
    }

    @Test
    fun newerSameTrackTransferCanAdvanceMetadataGenerationButOlderCannot() {
        assertTrue(MediaIdentityPolicy.canAdoptIncomingGeneration(1L, 2L))
        assertTrue(MediaIdentityPolicy.canAdoptIncomingGeneration(2L, 2L))
        assertFalse(MediaIdentityPolicy.canAdoptIncomingGeneration(2L, 1L))
        assertTrue(MediaIdentityPolicy.canAdoptIncomingGeneration(2L, 0L))
    }

    @Test
    fun artworkTransferSurvivesSameTrackGenerationAdvance() {
        assertTrue(
            MediaIdentityPolicy.transferStillBelongsToCurrentTrack(
                currentTrackId = "track-a",
                transferTrackId = "track-a",
                currentGeneration = 8L,
                transferGeneration = 3L
            )
        )
    }

    @Test
    fun artworkTransferRejectsDifferentTrackOrFutureGeneration() {
        assertFalse(
            MediaIdentityPolicy.transferStillBelongsToCurrentTrack(
                currentTrackId = "track-b",
                transferTrackId = "track-a",
                currentGeneration = 8L,
                transferGeneration = 3L
            )
        )
        assertFalse(
            MediaIdentityPolicy.transferStillBelongsToCurrentTrack(
                currentTrackId = "track-a",
                transferTrackId = "track-a",
                currentGeneration = 3L,
                transferGeneration = 8L
            )
        )
    }
}
