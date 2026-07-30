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
}
