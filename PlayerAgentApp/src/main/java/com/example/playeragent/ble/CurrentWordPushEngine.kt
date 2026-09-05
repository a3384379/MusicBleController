package com.example.playeragent.ble

import android.os.SystemClock
import com.example.playeragent.diagnostics.RealtimeTrace
import com.example.playeragent.diagnostics.TrackHandoffTraceCoordinator
import com.example.playeragent.media.CurrentTrackRuntimeCache
import com.example.playeragent.media.CurrentWordEligibilitySnapshot
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
    private val includeClockSyncFields: () -> Boolean = { false },
    private val expectedGeneration: () -> Long = { CurrentTrackRuntimeCache.currentGeneration() },
    private val currentWordEligibility: (Long) -> CurrentWordEligibilitySnapshot = {
        elapsedRealtimeMs ->
        CurrentTrackRuntimeCache.currentWordEligibilitySnapshot(
            elapsedRealtimeMs = elapsedRealtimeMs
        )
    },
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }
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
    private var lastPushedPositionMs: Long = -1L
    private var nextSequence: Long = 0L
    private var lastLoggedLineIndex: Int = -1

    fun observeGeneration(generation: Long) {
        if (generation <= 0L) return
        synchronized(lock) {
            if (generation != lastObservedGeneration) {
                prepareGenerationLocked(generation)
            }
        }
    }

    @Synchronized
    fun pushCurrentWord(
        reason: String = "diff",
        force: Boolean = false
    ): CurrentWordState? {
        val startedAt = elapsedRealtime()
        val eligibility = currentWordEligibility(startedAt)
        val handoffTrace = TrackHandoffTraceCoordinator.contextFor(eligibility.trackId)
        RealtimeTrace.record(
            stage = "currentWordEligibilityEvaluated",
            monoMs = startedAt,
            trackId = eligibility.trackId.takeIf { it.isNotBlank() },
            generation = eligibility.generation.takeIf { it > 0L },
            payloadType = "currentWord",
            result = if (eligibility.eligible) "eligible" else "not_eligible",
            reason = eligibility.reason,
            handoffId = handoffTrace?.handoffId,
            triggerType = handoffTrace?.triggerType?.name,
            positionAnchorMs = eligibility.positionAnchorMs.takeIf { it > 0L },
            lineIndex = eligibility.lineIndex.takeIf { it >= 0 },
            wordTimingStatus = eligibility.wordTimingStatus,
            failureReason = eligibility.reason.takeUnless { eligibility.eligible }
        )
        RealtimeTrace.record(
            stage = if (eligibility.eligible) {
                "currentWordTimingEligible"
            } else {
                "currentWordNotEligible"
            },
            monoMs = startedAt,
            trackId = eligibility.trackId.takeIf { it.isNotBlank() },
            generation = eligibility.generation.takeIf { it > 0L },
            payloadType = "currentWord",
            result = if (eligibility.eligible) "eligible" else "not_eligible",
            reason = eligibility.reason,
            handoffId = handoffTrace?.handoffId,
            triggerType = handoffTrace?.triggerType?.name,
            positionAnchorMs = eligibility.positionAnchorMs.takeIf { it > 0L },
            lineIndex = eligibility.lineIndex.takeIf { it >= 0 },
            wordTimingStatus = eligibility.wordTimingStatus,
            failureReason = eligibility.reason.takeUnless { eligibility.eligible }
        )
        if (!eligibility.eligible) {
            recordSkip(eligibility.reason.lowercase())
            return null
        }
        val state = eligibility.state ?: run {
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
                recordSkipLocked("stale_generation", elapsedRealtime(), state, key)
            }
            logger(
                "[CurrentWordFence] skip reason=stale_generation " +
                    "trackId=${state.trackId} generation=${state.trackGeneration} " +
                    "currentGeneration=$currentGeneration"
            )
            return null
        }

        synchronized(lock) {
            val now = elapsedRealtime()
            if (state.trackGeneration != lastObservedGeneration) {
                prepareGenerationLocked(state.trackGeneration)
            }
            if (!force && key == lastPushedKey) {
                recordSkipLocked("same word", now, state, key)
                return null
            }
            if (!force && lastPushedPositionMs >= 0L && state.positionMs < lastPushedPositionMs) {
                val regressionMs = lastPushedPositionMs - state.positionMs
                if (regressionMs <= MAX_JITTER_REGRESSION_MS) {
                    recordSkipLocked(
                        "position regression",
                        now,
                        state,
                        key,
                        extra = " regressionMs=$regressionMs"
                    )
                    return null
                }
                // A larger backwards jump is a real seek. Start a new local
                // timeline while preserving the connection generation.
                lastPushedKey = ""
                lastPushedPositionMs = -1L
                logger(
                    "[CurrentWordFence] timeline discontinuity " +
                        "trackId=$outgoingTrackId regressionMs=$regressionMs"
                )
            }
            if (!force &&
                lastPushElapsedMs > 0L &&
                now - lastPushElapsedMs < MIN_CURRENT_WORD_INTERVAL_MS
            ) {
                recordSkipLocked("rate limited", now, state, key)
                return null
            }
        }

        val schedulerCreatedAt = elapsedRealtime()
        RealtimeTrace.record(
            stage = "currentWordEligible",
            monoMs = schedulerCreatedAt,
            trackId = eligibility.trackId.takeIf { it.isNotBlank() },
            generation = eligibility.generation.takeIf { it > 0L },
            payloadType = "currentWord",
            result = "eligible",
            reason = eligibility.reason,
            handoffId = handoffTrace?.handoffId,
            triggerType = handoffTrace?.triggerType?.name,
            positionAnchorMs = eligibility.positionAnchorMs.takeIf { it > 0L },
            lineIndex = eligibility.lineIndex.takeIf { it >= 0 },
            wordTimingStatus = eligibility.wordTimingStatus
        )
        RealtimeTrace.record(
            stage = "currentWordSchedulerCreated",
            monoMs = schedulerCreatedAt,
            trackId = eligibility.trackId.takeIf { it.isNotBlank() },
            generation = eligibility.generation.takeIf { it > 0L },
            payloadType = "currentWord",
            result = "activated",
            reason = eligibility.reason,
            handoffId = handoffTrace?.handoffId,
            triggerType = handoffTrace?.triggerType?.name,
            positionAnchorMs = eligibility.positionAnchorMs.takeIf { it > 0L },
            lineIndex = eligibility.lineIndex.takeIf { it >= 0 },
            wordTimingStatus = eligibility.wordTimingStatus
        )
        val sequenceAndFirst = synchronized(lock) {
            val first = nextSequence == 0L
            nextSequence += 1L
            nextSequence to first
        }
        val sequence = sequenceAndFirst.first
        val isFirstForGeneration = sequenceAndFirst.second
        val payload = JSONObject()
            .put("type", "currentWord")
            .put("trackId", outgoingTrackId)
            .put("generation", state.trackGeneration)
            .put("seq", sequence)
            .put("line", state.lineIndex)
            .put("word", state.wordIndex)
            .put("position", state.positionMs)
            .put("timestamp", state.timestampMs)
            .put("version", state.version)
        if (includeClockSyncFields()) {
            payload.put("sampleMono", state.sampleElapsedMs)
        }

        RealtimeTrace.record(
            stage = if (isFirstForGeneration) {
                "currentWordImmediateSnapshotEnqueued"
            } else {
                "currentWordBoundaryEnqueued"
            },
            trackId = outgoingTrackId,
            generation = state.trackGeneration,
            payloadType = "currentWord",
            result = "enqueued",
            reason = reason,
            handoffId = handoffTrace?.handoffId,
            triggerType = handoffTrace?.triggerType?.name,
            positionAnchorMs = state.sampleElapsedMs,
            lineIndex = state.lineIndex,
            wordTimingStatus = if (state.hasWordTiming) "AVAILABLE" else "LINE_ONLY"
        )

        val sendStartedAt = elapsedRealtime()
        RealtimeTrace.record(
            stage = "currentWordSendStart",
            monoMs = sendStartedAt,
            trackId = outgoingTrackId,
            generation = state.trackGeneration,
            payloadType = "currentWord",
            result = "started",
            reason = reason,
            handoffId = handoffTrace?.handoffId,
            triggerType = handoffTrace?.triggerType?.name,
            positionAnchorMs = state.sampleElapsedMs,
            lineIndex = state.lineIndex,
            wordTimingStatus = if (state.hasWordTiming) "AVAILABLE" else "LINE_ONLY"
        )
        val sent = sendStatusMessage(payload.toString())
        val sendEndedAt = elapsedRealtime()
        RealtimeTrace.record(
            stage = "currentWordSendEnd",
            monoMs = sendEndedAt,
            trackId = outgoingTrackId,
            generation = state.trackGeneration,
            payloadType = "currentWord",
            processingMs = (sendEndedAt - sendStartedAt).coerceAtLeast(0L),
            result = if (sent) "success" else "failure",
            reason = if (sent) reason else "send_failed",
            handoffId = handoffTrace?.handoffId,
            triggerType = handoffTrace?.triggerType?.name,
            positionAnchorMs = state.sampleElapsedMs,
            lineIndex = state.lineIndex,
            wordTimingStatus = if (state.hasWordTiming) "AVAILABLE" else "LINE_ONLY",
            failureReason = if (sent) null else "UNKNOWN"
        )
        synchronized(lock) {
            if (!sent) {
                recordSkipLocked("send failed", elapsedRealtime(), state, key)
                return null
            }
            val now = elapsedRealtime()
            if (lastPushElapsedMs > 0L) {
                intervalTotalMs += (now - lastPushElapsedMs).coerceAtLeast(0L)
                intervalCount += 1
            }
            lastPushElapsedMs = now
            lastPushedKey = key
            lastPushedPositionMs = state.positionMs
            pushCount += 1
            lastPushCostMs = now - startedAt
            val normalizedSuffix = if (outgoingTrackId != state.trackId) {
                " normalizedFrom=${state.trackId} idMode=short"
            } else {
                " idMode=canonical"
            }
            if (force ||
                state.lineIndex != lastLoggedLineIndex ||
                pushCount % PUSH_LOG_SAMPLE_INTERVAL == 0L
            ) {
                lastLoggedLineIndex = state.lineIndex
                logger(
                    "[CurrentWordPush] push trackId=$outgoingTrackId$normalizedSuffix " +
                        "generation=${state.trackGeneration} seq=$sequence " +
                        "line=${state.lineIndex} word=${state.wordIndex} " +
                        "wordText=${state.wordText.take(MAX_LOG_WORD_TEXT)} " +
                        "wordStartMs=${state.wordStartMs} wordEndMs=${state.wordEndMs} " +
                        "hasWordTiming=${state.hasWordTiming} positionMs=${state.positionMs} " +
                        "currentWordKey=$key reason=$reason force=$force costMs=$lastPushCostMs"
                )
            }
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
            lastPushedPositionMs = -1L
            nextSequence = 0L
            lastLoggedLineIndex = -1
        }
    }

    private fun prepareGenerationLocked(generation: Long) {
        lastObservedGeneration = generation
        lastPushedKey = ""
        lastPushElapsedMs = 0L
        lastPushedPositionMs = -1L
        nextSequence = 0L
        lastLoggedLineIndex = -1
    }

    fun resetTimeline() {
        synchronized(lock) {
            lastPushedKey = ""
            lastPushedPositionMs = -1L
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
            recordSkipLocked(reason, elapsedRealtime())
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
        RealtimeTrace.record(
            stage = if (reason == "same word" || reason == "rate limited") {
                "currentWordCoalesced"
            } else {
                "currentWordDropped"
            },
            monoMs = now,
            trackId = state?.trackId,
            generation = state?.trackGeneration,
            payloadType = "currentWord",
            result = "skipped",
            reason = reason.replace(' ', '_')
        )
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
        private const val SKIP_LOG_INTERVAL_MS = 5_000L
        private const val MAX_LOG_WORD_TEXT = 24
        private const val MAX_JITTER_REGRESSION_MS = 1_500L
        private const val PUSH_LOG_SAMPLE_INTERVAL = 10L
    }
}
