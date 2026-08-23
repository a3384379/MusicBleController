package com.example.playeragent.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QrcCurrentTrackMatchPolicyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptyMetadataUsesFirstNonCreditLyricTitle() {
        val title = QrcCurrentTrackMatchPolicy.extractTitleCandidate(
            listOf("末班车（Live 版）", "词：马嵩惟", "曲：李伟菘", "空气中吹来一阵风")
        )
        assertEquals("末班车（Live 版）", title)

        val decision = QrcCurrentTrackMatchPolicy.evaluateRecent(
            track = QrcCurrentTrackMatchPolicy.Track(
                title = "末班车 (Live)",
                artist = "赵乃吉",
                durationMs = 260_000L
            ),
            candidates = listOf(
                candidate(groupId = "-1453943104", lyricTitle = title, endMs = 248_000L)
            ),
            recentCandidateCount = 1
        )

        assertTrue(decision.matched)
        assertEquals("-1453943104", decision.candidate?.groupId)
    }

    @Test
    fun ambiguousMissingArtistCandidatesAreRejected() {
        val decision = QrcCurrentTrackMatchPolicy.evaluateRecent(
            track = QrcCurrentTrackMatchPolicy.Track("末班车", "赵乃吉"),
            candidates = listOf(
                candidate("1", "末班车"),
                candidate("2", "末班车（Live版）")
            )
        )
        assertFalse(decision.matched)
        assertEquals("ambiguous recent candidates", decision.reason)
    }

    @Test
    fun singleExactTitleAmongStartupCatchupBatchIsAccepted() {
        val decision = QrcCurrentTrackMatchPolicy.evaluateRecent(
            track = QrcCurrentTrackMatchPolicy.Track("末班车 (Live)", "赵乃吉"),
            candidates = listOf(candidate("-1453943104", "末班车（Live版）")),
            recentCandidateCount = 3
        )
        assertTrue(decision.matched)
    }

    @Test
    fun artistOnlyCandidateIsRejected() {
        val decision = QrcCurrentTrackMatchPolicy.evaluateRecent(
            track = QrcCurrentTrackMatchPolicy.Track("末班车", "赵乃吉"),
            candidates = listOf(
                candidate("1", "另一首歌", metadataArtist = "赵乃吉")
            )
        )
        assertFalse(decision.matched)
    }

    @Test
    fun exSavingTimeIsReadWithoutProducer() {
        val file = temporaryFolder.newFile("-1453943104.ex")
        file.writeText("""{"saving_t":1786544231343,"qrc_t":1753786731}""")
        assertEquals(1_786_544_231_343L, QrcLyricUtils.readExSavingTime(file))
    }

    @Test
    fun startupCatchupOnlyReturnsNewThreeGroups() {
        val selected = QrcWatcherCatchupPolicy.selectChanged(
            signals = listOf(
                QrcGroupChangeSignal("1", 100L),
                QrcGroupChangeSignal("2", 500L),
                QrcGroupChangeSignal("3", 300L),
                QrcGroupChangeSignal("4", 400L)
            ),
            watermarkMs = 100L,
            limit = 3
        )
        assertEquals(listOf("2", "4", "3"), selected.map(QrcGroupChangeSignal::groupId))
    }

    private fun candidate(
        groupId: String,
        lyricTitle: String,
        metadataArtist: String = "",
        endMs: Long = 0L
    ): QrcCurrentTrackMatchPolicy.Candidate {
        return QrcCurrentTrackMatchPolicy.Candidate(
            groupId = groupId,
            metadataTitle = "",
            metadataArtist = metadataArtist,
            lyricTitleCandidate = lyricTitle,
            lastLineEndMs = endMs,
            effectiveModifiedAtMs = 1L
        )
    }
}
