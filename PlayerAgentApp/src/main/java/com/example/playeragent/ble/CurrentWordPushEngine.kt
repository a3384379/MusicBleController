package com.example.playeragent.ble

import android.os.SystemClock
import com.example.playeragent.media.CurrentTrackRuntimeCache
import com.example.playeragent.media.CurrentWordState
import com.example.playeragent.media.TrackCapabilityTracker
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
    private val normalizeTrackId: (String) -> String = { it },
    private val expectedGeneration: () -> Long = { CurrentTrackRuntimeCache.currentGeneration() }
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
    private var lastObservedGeneration: Long = -1L
    private var generationFirstSeenAtMs: Long = 0L

    fun pushCurrentWord(
        reason: String = "diff",
        force: Boolean = false
    ): CurrentWordState? {
        val startedAt = SystemClock.elapsedRealtime()
        val state = CurrentTrackRuntimeCache.currentWordState() ?: run {
            recordSkip("missing")
            return null
        }
        val outgoingTrackId = normalizeTrackId(state.trackId)
        val key = "$outgoingTrackId|${state.lineIndex}|${state.wordIndex}|${state.wordStartMs}"
        val currentGeneration = expectedGeneration()
        if (state.trackGeneration != currentGeneration) {
            TrackCapabilityTracker.onCurrentWordStaleBlocked(
                trackId = state.trackId,
                protocolId = outgoingTrackId
            )
            synchronized(lock) {
                recordSkipLocked("stale_generation", SystemClock.elapsedRealtime(), state, key)
            }
            logger(
                "[CurrentWordFence] skip reason=stale_generation " +
                    "trackId=${state.trackId} generation=${state.trackGeneration} " +
                    "currentGeneration=$currentGeneration"
            )
            return null
        }

        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (state.trackGeneration != lastObservedGeneration) {
                lastObservedGeneration = state.trackGeneration
                generationFirstSeenAtMs = now
            }
            val generationAgeMs = now - generationFirstSeenAtMs
            if (!force && generationAgeMs < TRACK_SWITCH_BASELINE_HOLDOFF_MS) {
                recordSkipLocked(
                    "track_switch_baseline_pending",
                    now,
                    state,
                    key,
                    extra = " generationAgeMs=$generationAgeMs"
                )
                logger(
                    "[CurrentWordFence] skip reason=track_switch_baseline_pending " +
                        "trackId=$outgoingTrackId generation=${state.trackGeneration} " +
                        "generationAgeMs=$generationAgeMs " +
                        "holdoffMs=$TRACK_SWITCH_BASELINE_HOLDOFF_MS"
                )
                return null
            }
            if (!force && key == lastPushedKey) {
                recordSkipLocked("same word", now, state, key)
                return null
            }
            if (!force &&
                lastPushElapsedMs > 0L &&
                now - lastPushElapsedMs < MIN_CURRENT_WORD_INTERVAL_MS
            ) {
                recordSkipLocked("rate limited", now, state, key)
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
                recordSkipLocked("send failed", SystemClock.elapsedRealtime(), state, key)
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
                    "generation=${state.trackGeneration} " +
                    "line=${state.lineIndex} word=${state.wordIndex} " +
                    "wordText=${state.wordText.take(MAX_LOG_WORD_TEXT)} " +
                    "wordStartMs=${state.wordStartMs} wordEndMs=${state.wordEndMs} " +
                    "hasWordTiming=${state.hasWordTiming} positionMs=${state.positionMs} " +
                    "currentWordKey=$key reason=$reason force=$force costMs=$lastPushCostMs"
            )
            TrackCapabilityTracker.onCurrentWordPushed(
                trackId = state.trackId,
                protocolId = outgoingTrackId
            )
        }
        return state
    }

    fun reset() {
        synchronized(lock) {
            lastPushedKey = ""
            lastPushElapsedMs = 0L
            lastObservedGeneration = -1L
            generationFirstSeenAtMs = 0L
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

    private fun recordSkipLocked(
        reason: String,
        now: Long,
        state: CurrentWordState? = null,
        currentWordKey: String = "",
        extra: String = ""
    ) {
        skipCount += 1
        if (now - lastSkipLogAtMs >= SKIP_LOG_INTERVAL_MS) {
            lastSkipLogAtMs = now
            val detail = if (state != null) {
                    " positionMs=${state.positionMs}" +
                    " generation=${state.trackGeneration}" +
                    " lineIndex=${state.lineIndex}" +
                    " wordIndex=${state.wordIndex}" +
                    " wordText=${state.wordText.take(MAX_LOG_WORD_TEXT)}" +
                    " wordStartMs=${state.wordStartMs}" +
                    " wordEndMs=${state.wordEndMs}" +
                    " hasWordTiming=${state.hasWordTiming}" +
                    " lastPushedWordKey=$lastPushedKey" +
                    " currentWordKey=$currentWordKey" +
                    extra
            } else {
                extra
            }
            logger("[CurrentWordPush] skip reason=$reason$detail")
        }
    }

    private companion object {
        private const val MIN_CURRENT_WORD_INTERVAL_MS = 60L
        private const val TRACK_SWITCH_BASELINE_HOLDOFF_MS = 450L
        private const val SKIP_LOG_INTERVAL_MS = 5_000L
        private const val MAX_LOG_WORD_TEXT = 24
    }
}
