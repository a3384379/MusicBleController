package com.example.playeragent.ble

import com.example.playeragent.media.LyricManager
import java.security.MessageDigest

/**
 * Exact, content-addressed description of lyrics that are safe to reuse on a controller.
 * The digest intentionally covers secondary lyrics and word timing so a base-only cache
 * can never suppress a transfer for richer content.
 */
internal data class FullLyricsCacheDescriptor(
    val fingerprint: String,
    val schemaVersion: Int,
    val lineCount: Int,
    val translationLineCount: Int,
    val romanizationLineCount: Int
)

internal data class FullLyricsCacheValidationRequest(
    val fingerprint: String,
    val schemaVersion: Int,
    val lineCount: Int,
    val translationLineCount: Int,
    val romanizationLineCount: Int
)

internal enum class FullLyricsCacheValidationDecision {
    HIT,
    CAPABILITY_DISABLED,
    REQUEST_MISSING,
    FINGERPRINT_MISMATCH,
    SCHEMA_MISMATCH,
    LINE_COUNT_MISMATCH,
    SECONDARY_COUNT_MISMATCH
}

internal object FullLyricsCacheValidation {
    const val SCHEMA_VERSION = 1
    private const val FINGERPRINT_BYTES = 12

    fun describe(
        title: String,
        artist: String,
        lines: List<LyricManager.LyricLine>
    ): FullLyricsCacheDescriptor {
        val digest = MessageDigest.getInstance("SHA-256")
        updateField(digest, title.trim())
        updateField(digest, artist.trim())
        lines.forEachIndexed { index, line ->
            updateField(digest, index.toString())
            updateField(digest, line.timeMs.toString())
            updateField(digest, line.durationMs.toString())
            updateField(digest, line.text)
            updateField(digest, line.translation.orEmpty())
            updateField(digest, line.romanization.orEmpty())
            updateField(digest, line.words.size.toString())
            line.words.forEach { word ->
                updateField(digest, word.startMs.toString())
                updateField(digest, word.durationMs.toString())
                updateField(digest, word.text)
            }
        }
        return FullLyricsCacheDescriptor(
            fingerprint = digest.digest()
                .take(FINGERPRINT_BYTES)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) },
            schemaVersion = SCHEMA_VERSION,
            lineCount = lines.size,
            translationLineCount = lines.count { !it.translation.isNullOrBlank() },
            romanizationLineCount = lines.count { !it.romanization.isNullOrBlank() }
        )
    }

    fun decide(
        capabilityEnabled: Boolean,
        request: FullLyricsCacheValidationRequest?,
        actual: FullLyricsCacheDescriptor
    ): FullLyricsCacheValidationDecision {
        if (!capabilityEnabled) return FullLyricsCacheValidationDecision.CAPABILITY_DISABLED
        request ?: return FullLyricsCacheValidationDecision.REQUEST_MISSING
        if (request.schemaVersion != actual.schemaVersion) {
            return FullLyricsCacheValidationDecision.SCHEMA_MISMATCH
        }
        if (request.lineCount != actual.lineCount) {
            return FullLyricsCacheValidationDecision.LINE_COUNT_MISMATCH
        }
        if (request.translationLineCount != actual.translationLineCount ||
            request.romanizationLineCount != actual.romanizationLineCount
        ) {
            return FullLyricsCacheValidationDecision.SECONDARY_COUNT_MISMATCH
        }
        if (!request.fingerprint.equals(actual.fingerprint, ignoreCase = true)) {
            return FullLyricsCacheValidationDecision.FINGERPRINT_MISMATCH
        }
        return FullLyricsCacheValidationDecision.HIT
    }

    fun estimatedPayloadBytes(lines: List<LyricManager.LyricLine>): Int {
        return lines.sumOf { line ->
            line.text.toByteArray(Charsets.UTF_8).size +
                line.translation.orEmpty().toByteArray(Charsets.UTF_8).size +
                line.romanization.orEmpty().toByteArray(Charsets.UTF_8).size +
                line.words.sumOf { it.text.toByteArray(Charsets.UTF_8).size + 24 } +
                48
        }
    }

    private fun updateField(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
}
