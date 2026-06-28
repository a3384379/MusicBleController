package com.example.playeragent.media

import android.util.Log

object LyricTraceLogger {
    private const val TAG = "LyricTrace"

    fun stage(
        runId: String?,
        trackId: String?,
        songKey: String?,
        generation: Long?,
        stage: String,
        reason: String? = null,
        costMs: Long? = null,
        extra: Map<String, String> = emptyMap()
    ) {
        stage(
            runId = runId,
            trackId = trackId,
            songKey = songKey,
            generation = generation,
            stage = stage,
            reason = reason,
            costMs = costMs,
            extra = extra,
            sink = null
        )
    }

    fun stage(
        runId: String?,
        trackId: String?,
        songKey: String?,
        generation: Long?,
        stage: String,
        reason: String? = null,
        costMs: Long? = null,
        extra: Map<String, String> = emptyMap(),
        sink: ((String) -> Unit)? = null
    ) {
        val resolvedTrackId = sanitize(trackId).ifBlank { "-" }
        val resolvedSongKey = sanitize(songKey).ifBlank { "-" }
        val resolvedReason = sanitize(
            if (resolvedTrackId == "-" && resolvedSongKey == "-") {
                reason ?: "missing_identity"
            } else {
                reason
            }
        ).ifBlank { "-" }
        val message = buildString {
            append("[LyricTrace]")
            append(" runId=").append(sanitize(runId).ifBlank { "unknown" })
            append(" trackId=").append(resolvedTrackId)
            append(" songKey=").append(resolvedSongKey)
            append(" generation=").append(generation?.toString() ?: "-")
            append(" stage=").append(sanitize(stage).ifBlank { "unknown" })
            append(" reason=").append(resolvedReason)
            append(" costMs=").append(costMs?.toString() ?: "-")
            extra.forEach { (key, value) ->
                val safeKey = sanitizeKey(key)
                if (safeKey.isNotBlank()) {
                    append(' ').append(safeKey).append('=').append(sanitize(value).ifBlank { "-" })
                }
            }
        }
        Log.i(TAG, message)
        sink?.invoke(message)
    }

    fun legacy(
        id: String,
        stage: String,
        detail: String,
        sink: ((String) -> Unit)? = null
    ) {
        val fields = parseDetail(detail)
        val normalizedStage = normalizeLegacyStage(stage, fields)
        val fallbackTrackId = id.substringBefore("@").takeIf { it.isNotBlank() && it != "unknown" }
        val reason = fields["reason"] ?: fields["result"]
        val costMs = fields["costMs"]?.toLongOrNull()
        val generation = fields["generation"]?.toLongOrNull()
        val extras = fields.filterKeys {
            it !in setOf("trackId", "songKey", "generation", "reason", "result", "costMs")
        }
        stage(
            runId = fields["runId"] ?: "unknown",
            trackId = fields["trackId"] ?: fallbackTrackId,
            songKey = fields["songKey"],
            generation = generation,
            stage = normalizedStage,
            reason = reason,
            costMs = costMs,
            extra = extras,
            sink = sink
        )
    }

    private fun normalizeLegacyStage(stage: String, fields: Map<String, String>): String {
        return when (stage) {
            "reactiveEventReceived" -> "reactiveEvent"
            "lyricsParseScheduled", "loadScheduled" -> "parseScheduled"
            "loadStart" -> "qrcLookupStart"
            "runtimeCacheUpdated" -> "runtimeApplyEnd"
            "ready" -> "readyGateReady"
            "failed" -> "readyGateFailed"
            "qrcLookup" -> when (fields["result"]) {
                "start" -> "qrcLookupStart"
                "miss" -> "qrcFileNotFound"
                "hit" -> "qrcFileFound"
                else -> stage
            }
            "decrypt" -> when (fields["result"]) {
                "cancelled" -> "qrcDecryptEnd"
                else -> "qrcDecryptStart"
            }
            else -> stage
        }
    }

    private fun parseDetail(detail: String): Map<String, String> {
        if (detail.isBlank()) {
            return emptyMap()
        }
        val result = linkedMapOf<String, String>()
        val regex = Regex("""([A-Za-z][A-Za-z0-9_]+)=((?:"[^"]*")|(?:.*?)(?=\s+[A-Za-z][A-Za-z0-9_]+=|$))""")
        regex.findAll(detail).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2].trim().trim('"')
        }
        return result
    }

    private fun sanitize(value: String?): String {
        return value.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .trim()
    }

    private fun sanitizeKey(value: String): String {
        return sanitize(value).replace(Regex("""[^A-Za-z0-9_]"""), "_")
    }
}
