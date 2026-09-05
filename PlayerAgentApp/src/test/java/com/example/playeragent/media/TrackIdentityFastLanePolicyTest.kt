package com.example.playeragent.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIdentityFastLanePolicyTest {
    @Test
    fun confirmedDistinctIdentityUsesFastLane() {
        assertTrue(
            TrackIdentityFastLanePolicy.shouldPublish(
                trackChanged = true,
                trackId = "track-2",
                title = "Song"
            )
        )
    }

    @Test
    fun refreshAndIncompleteMetadataStayOnNormalPath() {
        assertFalse(
            TrackIdentityFastLanePolicy.shouldPublish(
                trackChanged = false,
                trackId = "track-2",
                title = "Song"
            )
        )
        assertFalse(
            TrackIdentityFastLanePolicy.shouldPublish(
                trackChanged = true,
                trackId = "",
                title = "Song"
            )
        )
        assertFalse(
            TrackIdentityFastLanePolicy.shouldPublish(
                trackChanged = true,
                trackId = "track-2",
                title = ""
            )
        )
    }
}
