package com.example.playeragent.ble

import android.os.SystemClock
import com.example.playeragent.media.CurrentTrackRuntimeCache
import com.example.playeragent.media.CurrentWordState
import org.json.JSONObject

data class CurrentWordMetrics(
    val pushCount: Long = 0L,
    val skipCount: Long = 0L,
    val averageIntervalMs: Long = 0L,
    val lastPushCostMs: Long = 0L
)

class CurrentWordPushEngine(
    private val logger: (String) -> Unit,
    private val sendStatusMessage: (String) -> Boolean,
    private val normalizeTrackId: (String) -> String = { it }
) {
    private val lock = Any()
    private var lastPushedKey: String = ""
    private var lastPushElapsedMs: Long = 0L
    private var pushCount: Long = 0L
    private var skipCount: Long = 0L
    private var intervalTotalMs: Long = 0L
    private var intervalCount: Long = 0L
    private var lastPushCostMs: Long = 0L
    private var lastSkipLogAtMs: Long = 0L

    fun pushCurrentWord(): CurrentWordState? {
        val startedAt = SystemClock.elapsedRealtime()
        val state = CurrentTrackRuntimeCache.currentWordState() ?: run {
            recordSkip("missing")
            return null
        }
        val outgoingTrackId = normalizeTrackId(state.trackId)
        val key = "$outgoingTrackId|${state.lineIndex}|${state.wordIndex}"

        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (key == lastPushedKey) {
                recordSkipLocked("same word", now)
                return null
            }
            if (lastPushElapsedMs > 0L &&
                now - lastPushElapsedMs < MIN_CURRENT_WORD_INTERVAL_MS
            ) {
                recordSkipLocked("rate limited", now)
                return null
            }
        }

        val payload = JSONObject()
            .put("type", "currentWord")
            .put("trackId", outgoingTrackId)
            .put("line", state.lineIndex)
            .put("word", state.wordIndex)
            .put("position", state.positionMs)
            .put("timestamp", state.timestampMs)
            .put("version", state.version)

        val sent = sendStatusMessage(payload.toString())
        synchronized(lock) {
            if (!sent) {
                recordSkipLocked("send failed", SystemClock.elapsedRealtime())
                return null
            }
            val now = SystemClock.elapsedRealtime()
            if (lastPushElapsedMs > 0L) {
                intervalTotalMs += (now - lastPushElapsedMs).coerceAtLeast(0L)
                intervalCount += 1
            }
            lastPushElapsedMs = now
            lastPushedKey = key
            pushCount += 1
            lastPushCostMs = now - startedAt
            val normalizedSuffix = if (outgoingTrackId != state.trackId) {
                " normalizedFrom=${state.trackId} idMode=short"
            } else {
                " idMode=canonical"
            }
            logger(
                "[CurrentWordPush] push trackId=$outgoingTrackId$normalizedSuffix " +
                    "line=${state.lineIndex} word=${state.wordIndex} " +
                    "position=${state.positionMs} costMs=$lastPushCostMs"
            )
        }
        return state
    }

    fun reset() {
        synchronized(lock) {
            lastPushedKey = ""
            lastPushElapsedMs = 0L
        }
    }

    fun metricsSnapshot(): CurrentWordMetrics {
        synchronized(lock) {
            return CurrentWordMetrics(
                pushCount = pushCount,
                skipCount = skipCount,
                averageIntervalMs = if (intervalCount > 0L) {
                    intervalTotalMs / intervalCount
                } else {
                    0L
                },
                lastPushCostMs = lastPushCostMs
            )
        }
    }

    fun logMetrics() {
        val metrics = metricsSnapshot()
        logger(
            "[CurrentWordPush] metrics push=${metrics.pushCount} " +
                "skip=${metrics.skipCount} avgIntervalMs=${metrics.averageIntervalMs} " +
                "lastPushCostMs=${metrics.lastPushCostMs}"
        )
    }

    private fun recordSkip(reason: String) {
        synchronized(lock) {
            recordSkipLocked(reason, SystemClock.elapsedRealtime())
        }
    }

    private fun recordSkipLocked(reason: String, now: Long) {
        skipCount += 1
        if (now - lastSkipLogAtMs >= SKIP_LOG_INTERVAL_MS) {
            lastSkipLogAtMs = now
            logger("[CurrentWordPush] skip reason=$reason")
        }
    }

    private companion object {
        private const val MIN_CURRENT_WORD_INTERVAL_MS = 60L
        private const val SKIP_LOG_INTERVAL_MS = 5_000L
    }
}
