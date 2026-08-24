package com.example.playeragent.ble

import com.example.playeragent.media.LyricManager
import com.example.playeragent.media.QrcLyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullLyricsCacheValidationTest {

    @Test
    fun exactDescriptorIsAHit() {
        val descriptor = FullLyricsCacheValidation.describe("Song", "Artist", lines())
        assertEquals(
            FullLyricsCacheValidationDecision.HIT,
            FullLyricsCacheValidation.decide(
                capabilityEnabled = true,
                request = descriptor.asRequest(),
                actual = descriptor
            )
        )
        assertEquals(2, descriptor.lineCount)
        assertEquals(1, descriptor.translationLineCount)
        assertEquals(1, descriptor.romanizationLineCount)
        assertEquals(24, descriptor.fingerprint.length)
    }

    @Test
    fun capabilityAndCompleteRequestAreRequired() {
        val descriptor = FullLyricsCacheValidation.describe("Song", "Artist", lines())
        assertEquals(
            FullLyricsCacheValidationDecision.CAPABILITY_DISABLED,
            FullLyricsCacheValidation.decide(false, descriptor.asRequest(), descriptor)
        )
        assertEquals(
            FullLyricsCacheValidationDecision.REQUEST_MISSING,
            FullLyricsCacheValidation.decide(true, null, descriptor)
        )
    }

    @Test
    fun everyValidationDimensionCanForceColdTransfer() {
        val descriptor = FullLyricsCacheValidation.describe("Song", "Artist", lines())
        assertEquals(
            FullLyricsCacheValidationDecision.FINGERPRINT_MISMATCH,
            FullLyricsCacheValidation.decide(
                true,
                descriptor.asRequest().copy(fingerprint = "00".repeat(12)),
                descriptor
            )
        )
        assertEquals(
            FullLyricsCacheValidationDecision.SCHEMA_MISMATCH,
            FullLyricsCacheValidation.decide(
                true,
                descriptor.asRequest().copy(schemaVersion = 99),
                descriptor
            )
        )
        assertEquals(
            FullLyricsCacheValidationDecision.LINE_COUNT_MISMATCH,
            FullLyricsCacheValidation.decide(
                true,
                descriptor.asRequest().copy(lineCount = 1),
                descriptor
            )
        )
        assertEquals(
            FullLyricsCacheValidationDecision.SECONDARY_COUNT_MISMATCH,
            FullLyricsCacheValidation.decide(
                true,
                descriptor.asRequest().copy(translationLineCount = 0),
                descriptor
            )
        )
    }

    @Test
    fun secondaryAndWordChangesInvalidateFingerprint() {
        val baseline = FullLyricsCacheValidation.describe("Song", "Artist", lines())
        val translationChanged = FullLyricsCacheValidation.describe(
            "Song",
            "Artist",
            lines().mapIndexed { index, line ->
                if (index == 0) line.copy(translation = "changed") else line
            }
        )
        val wordChanged = FullLyricsCacheValidation.describe(
            "Song",
            "Artist",
            lines().mapIndexed { index, line ->
                if (index == 0) {
                    line.copy(words = line.words.map { it.copy(text = "changed") })
                } else {
                    line
                }
            }
        )
        assertNotEquals(baseline.fingerprint, translationChanged.fingerprint)
        assertNotEquals(baseline.fingerprint, wordChanged.fingerprint)
        assertTrue(FullLyricsCacheValidation.estimatedPayloadBytes(lines()) > 0)
    }

    @Test
    fun fingerprintMatchesIosFixedVector() {
        val descriptor = FullLyricsCacheValidation.describe(
            "Song",
            "Artist",
            listOf(
                LyricManager.LyricLine(
                    timeMs = 0,
                    durationMs = 1_000,
                    text = "line",
                    translation = "translation"
                )
            )
        )
        assertEquals("3e1c8ce388901c3763a5b1c3", descriptor.fingerprint)
    }

    private fun lines(): List<LyricManager.LyricLine> = listOf(
        LyricManager.LyricLine(
            timeMs = 0,
            durationMs = 1_000,
            text = "line one",
            translation = "translation",
            words = listOf(QrcLyricWord(0, 1_000, "line one"))
        ),
        LyricManager.LyricLine(
            timeMs = 1_000,
            durationMs = 1_000,
            text = "line two",
            romanization = "romanization"
        )
    )

    private fun FullLyricsCacheDescriptor.asRequest() = FullLyricsCacheValidationRequest(
        fingerprint = fingerprint,
        schemaVersion = schemaVersion,
        lineCount = lineCount,
        translationLineCount = translationLineCount,
        romanizationLineCount = romanizationLineCount
    )
}
