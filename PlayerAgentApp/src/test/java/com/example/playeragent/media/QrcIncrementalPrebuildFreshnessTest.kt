package com.example.playeragent.media

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrcIncrementalPrebuildFreshnessTest {

    @Test
    fun `fresh ex sidecar makes reused qrc current`() {
        val qrc = tempFile("qrc").apply { setLastModified(1_000L) }
        val ex = tempFile("ex").apply { setLastModified(10_000L) }
        val group = group(qrcFile = qrc, exFile = ex, lastModified = 10_000L)

        assertTrue(
            isQrcGroupRecentForCurrentTrack(
                group = group,
                trackChangedAtMs = 10_100L,
                matchWindowMs = 1_000L
            )
        )
    }

    @Test
    fun `old qrc group remains background work`() {
        val qrc = tempFile("qrc").apply { setLastModified(1_000L) }
        val ex = tempFile("ex").apply { setLastModified(1_100L) }
        val group = group(qrcFile = qrc, exFile = ex, lastModified = 1_100L)

        assertFalse(
            isQrcGroupRecentForCurrentTrack(
                group = group,
                trackChangedAtMs = 10_000L,
                matchWindowMs = 1_000L
            )
        )
    }

    @Test
    fun `aggregate timestamp remains a valid freshness signal`() {
        val qrc = tempFile("qrc").apply { setLastModified(1_000L) }
        val group = group(qrcFile = qrc, exFile = null, lastModified = 9_500L)

        assertTrue(
            isQrcGroupRecentForCurrentTrack(
                group = group,
                trackChangedAtMs = 10_000L,
                matchWindowMs = 1_000L
            )
        )
    }

    private fun tempFile(suffix: String): File =
        File.createTempFile("qrc-freshness-", ".$suffix").apply { deleteOnExit() }

    private fun group(
        qrcFile: File?,
        exFile: File?,
        lastModified: Long
    ): QrcFileGroup = QrcFileGroup(
        groupId = "test",
        qrcFile = qrcFile,
        producerFile = null,
        exFile = exFile,
        translrcFile = null,
        romaqrcFile = null,
        lastModified = lastModified
    )
}
