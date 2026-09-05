package com.example.playeragent.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumArtIdentityPolicyTest {
    @Test
    fun exactTrackIdentityIsAccepted() {
        assertTrue(
            AlbumArtIdentityPolicy.matches(
                expectedTitle = "Song",
                expectedArtist = "Artist",
                actualTitle = "song",
                actualArtist = "Artist / Guest"
            )
        )
    }

    @Test
    fun staleOrUnverifiableIdentityIsRejected() {
        assertFalse(
            AlbumArtIdentityPolicy.matches(
                expectedTitle = "New Song",
                expectedArtist = "Artist",
                actualTitle = "Old Song",
                actualArtist = "Artist"
            )
        )
        assertFalse(
            AlbumArtIdentityPolicy.matches(
                expectedTitle = "New Song",
                expectedArtist = "Artist",
                actualTitle = "",
                actualArtist = ""
            )
        )
    }

    @Test
    fun unspecifiedExpectedIdentityKeepsLegacyBehavior() {
        assertTrue(
            AlbumArtIdentityPolicy.matches(
                expectedTitle = "",
                expectedArtist = "",
                actualTitle = "",
                actualArtist = ""
            )
        )
    }
}
