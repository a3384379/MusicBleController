package com.example.playeragent.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricIncrementalIdentityTest {

    @Test
    fun `same track id accepts metadata representation changes`() {
        assertTrue(
            isSameIncrementalLyricTrack(
                activeSongKey = "规范标题|歌手|规范专辑",
                activeTrackId = "track-1",
                snapshotSongKey = "标题 (live版)|歌手|专辑 (氛围版)",
                snapshotTrackId = "track-1"
            )
        )
    }

    @Test
    fun `different track id rejects a stale incremental result`() {
        assertFalse(
            isSameIncrementalLyricTrack(
                activeSongKey = "当前歌曲|当前歌手|当前专辑",
                activeTrackId = "track-2",
                snapshotSongKey = "上一首歌|上一位歌手|上一张专辑",
                snapshotTrackId = "track-1"
            )
        )
    }

    @Test
    fun `song key remains fallback when track ids are unavailable`() {
        assertTrue(
            isSameIncrementalLyricTrack(
                activeSongKey = "同一首歌|歌手|专辑",
                activeTrackId = "",
                snapshotSongKey = "同一首歌|歌手|专辑",
                snapshotTrackId = ""
            )
        )
    }

    @Test
    fun `runtime track can seed an empty lyric manager`() {
        assertTrue(
            canAdoptIncrementalLyricTrack(
                activeSongKey = null,
                activeTrackId = "",
                snapshotSongKey = "末班车|赵乃吉|末班车",
                snapshotTrackId = "track-1"
            )
        )
    }

    @Test
    fun `runtime track never replaces an established active song`() {
        assertFalse(
            canAdoptIncrementalLyricTrack(
                activeSongKey = "当前歌曲|歌手|专辑",
                activeTrackId = "track-2",
                snapshotSongKey = "末班车|赵乃吉|末班车",
                snapshotTrackId = "track-1"
            )
        )
    }
}
