package com.example.playeragent.media

import android.os.SystemClock
import com.example.playeragent.diagnostics.RealtimeTrace
import java.io.Closeable
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class PredictionDirection {
    NEXT,
    PREVIOUS
}

enum class PredictionSource(val wireName: String) {
    MEDIA_SESSION_QUEUE("media_session_queue"),
    MANUAL_NEXT_WITH_QUEUE("manual_next_with_queue"),
    MANUAL_PREVIOUS_WITH_QUEUE("manual_previous_with_queue"),
    HISTORY_TRANSITION("history_transition")
}

enum class PredictionConfidence {
    CONFIRMED,
    STRONG,
    WEAK,
    NONE
}

enum class PredictionState {
    DISCOVERED,
    PREWARM_QUEUED,
    PREWARMING,
    LYRICS_READY,
    ARTWORK_READY,
    READY,
    PROMOTED,
    REJECTED,
    INVALIDATED,
    EXPIRED
}

data class PredictiveMediaTrack(
    val trackId: String,
    val songKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long = 0L,
    val mediaId: String = ""
) {
    val fallbackKey: String
        get() = PredictionIdentity.fallbackKey(title, artist, durationMs)

    val identityDigest: String
        get() = PredictionIdentity.digest(title, artist, album, durationMs, mediaId)

    val hasCompleteMetadata: Boolean
        get() = title.isNotBlank() && artist.isNotBlank()
}

data class PredictionCandidate(
    val candidateKey: String,
    val source: PredictionSource,
    val confidence: PredictionConfidence,
    val direction: PredictionDirection,
    val track: PredictiveMediaTrack,
    val queueItemId: Long = -1L,
    val createdElapsedMs: Long,
    val expiresElapsedMs: Long,
    val lyricFingerprint: String = "",
    val artworkId: String = "",
    val lyricsReady: Boolean = false,
    val artworkPreviewReady: Boolean = false,
    val compressedLyricsReady: Boolean = false,
    val state: PredictionState = PredictionState.DISCOVERED
)

data class PredictionPrewarmResult(
    val lyricsReady: Boolean,
    val lyricFingerprint: String = "",
    val lineCount: Int = 0,
    val hasWordTiming: Boolean = false,
    val compressedLyricsReady: Boolean = false,
    val artworkPreviewReady: Boolean = false,
    val artworkId: String = "",
    val source: String = "",
    val reason: String = ""
)

data class PredictionPromotionPayload(
    val lines: List<LyricManager.LyricLine>,
    val source: String,
    val lyricFingerprint: String
)

data class PredictionPromotionResult(
    val candidate: PredictionCandidate,
    val payload: PredictionPromotionPayload,
    val applyCostMs: Long
)

data class PredictiveLyricsMetrics(
    val candidateCount: Long = 0L,
    val mediaSessionQueueCandidateCount: Long = 0L,
    val manualNextHintCount: Long = 0L,
    val manualPreviousHintCount: Long = 0L,
    val historyTransitionCandidateCount: Long = 0L,
    val selectedCount: Long = 0L,
    val rejectedCount: Long = 0L,
    val expiredCount: Long = 0L,
    val invalidatedCount: Long = 0L,
    val preloadStartCount: Long = 0L,
    val preloadHitCount: Long = 0L,
    val preloadMissCount: Long = 0L,
    val preloadSuccessCount: Long = 0L,
    val preloadFailedCount: Long = 0L,
    val cachePutCount: Long = 0L,
    val cacheEvictCount: Long = 0L,
    val applyHitCount: Long = 0L,
    val applyMissCount: Long = 0L,
    val identityMismatchCount: Long = 0L,
    val preloadCostTotalMs: Long = 0L,
    val preloadCostMaxMs: Long = 0L,
    val applyCostTotalMs: Long = 0L,
    val applyCostMaxMs: Long = 0L
)

data class PredictionQueueItem(
    val queueItemId: Long,
    val mediaId: String,
    val track: PredictiveMediaTrack
)

data class PredictionQueueSnapshot(
    val activeQueueItemId: Long,
    val items: List<PredictionQueueItem>
)

object PredictionIdentity {
    fun digest(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mediaId: String
    ): String {
        val canonical = listOf(
            normalize(title),
            normalize(artist),
            normalize(album),
            durationBucket(durationMs).toString(),
            normalize(mediaId)
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun candidateKey(
        track: PredictiveMediaTrack,
        queueItemId: Long
    ): String {
        val stable = when {
            track.mediaId.isNotBlank() -> "media:${normalize(track.mediaId)}"
            queueItemId >= 0L -> "queue:$queueItemId"
            track.trackId.isNotBlank() -> "track:${track.trackId}"
            else -> track.fallbackKey
        }
        val material = "$stable|${track.identityDigest}"
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun fallbackKey(title: String, artist: String, durationMs: Long): String {
        return "fallback:${normalize(title)}|${normalize(artist)}|${durationBucket(durationMs)}"
    }

    fun exactMatch(candidate: PredictiveMediaTrack, actual: PredictiveMediaTrack): Boolean {
        if (!candidate.hasCompleteMetadata || !actual.hasCompleteMetadata) return false
        if (normalize(candidate.title) != normalize(actual.title)) return false
        if (normalize(candidate.artist) != normalize(actual.artist)) return false
        val mediaIdComparable = candidate.mediaId.isNotBlank() && actual.mediaId.isNotBlank()
        val trackIdComparable = candidate.trackId.isNotBlank() && actual.trackId.isNotBlank()
        if (mediaIdComparable && normalize(candidate.mediaId) != normalize(actual.mediaId)) {
            return false
        }
        if (!mediaIdComparable && trackIdComparable && candidate.trackId != actual.trackId) {
            return false
        }
        if (candidate.durationMs > 0L && actual.durationMs > 0L &&
            durationBucket(candidate.durationMs) != durationBucket(actual.durationMs)
        ) {
            return false
        }
        return mediaIdComparable || trackIdComparable
    }

    fun normalize(value: String): String {
        return value.trim().lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ")
    }

    private fun durationBucket(durationMs: Long): Long {
        return if (durationMs <= 0L) 0L else durationMs / 2_000L
    }
}

class PredictionSourceResolver(
    private val nowElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val ttlMs: Long = DEFAULT_CANDIDATE_TTL_MS
) {
    fun resolve(
        snapshot: PredictionQueueSnapshot,
        current: PredictiveMediaTrack,
        direction: PredictionDirection,
        source: PredictionSource
    ): PredictionCandidate? {
        if (snapshot.items.isEmpty()) return null
        val activeIndex = snapshot.items.indexOfFirst {
            snapshot.activeQueueItemId >= 0L && it.queueItemId == snapshot.activeQueueItemId
        }
        if (activeIndex >= 0 &&
            !PredictionIdentity.exactMatch(snapshot.items[activeIndex].track, current)
        ) {
            return null
        }
        val exactIndexes = snapshot.items.indices.filter { index ->
            PredictionIdentity.exactMatch(snapshot.items[index].track, current)
        }
        val currentIndex = when {
            activeIndex >= 0 -> activeIndex
            exactIndexes.size == 1 -> exactIndexes.single()
            else -> return null
        }
        val targetIndex = currentIndex + if (direction == PredictionDirection.NEXT) 1 else -1
        val target = snapshot.items.getOrNull(targetIndex) ?: return null
        if (target.track.title.isBlank()) return null
        val confidence = when {
            activeIndex >= 0 &&
                target.queueItemId >= 0L &&
                target.mediaId.isNotBlank() &&
                target.track.hasCompleteMetadata -> PredictionConfidence.CONFIRMED
            target.queueItemId >= 0L &&
                (target.mediaId.isNotBlank() || target.track.trackId.isNotBlank()) &&
                target.track.hasCompleteMetadata -> PredictionConfidence.STRONG
            else -> PredictionConfidence.WEAK
        }
        val created = nowElapsedMs()
        return PredictionCandidate(
            candidateKey = PredictionIdentity.candidateKey(
                target.track,
                target.queueItemId
            ),
            source = source,
            confidence = confidence,
            direction = direction,
            track = target.track,
            queueItemId = target.queueItemId,
            createdElapsedMs = created,
            expiresElapsedMs = created + ttlMs
        )
    }

    companion object {
        const val DEFAULT_CANDIDATE_TTL_MS = 2 * 60_000L
    }
}

class PredictiveHotSet(
    private val capacity: Int = 2,
    private val nowElapsedMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    init {
        require(capacity in 1..2) { "predictive hot set supports at most two candidates" }
    }

    private val entries = LinkedHashMap<String, PredictionCandidate>(capacity, 0.75f, true)

    @Synchronized
    fun upsert(candidate: PredictionCandidate): PredictionCandidate? {
        removeExpiredLocked(nowElapsedMs())
        val previous = entries[candidate.candidateKey]
        entries[candidate.candidateKey] = if (previous == null) {
            candidate
        } else {
            candidate.copy(
                createdElapsedMs = previous.createdElapsedMs,
                lyricsReady = previous.lyricsReady,
                artworkPreviewReady = previous.artworkPreviewReady,
                compressedLyricsReady = previous.compressedLyricsReady,
                lyricFingerprint = previous.lyricFingerprint,
                artworkId = previous.artworkId,
                state = previous.state
            )
        }
        if (entries.size <= capacity) return null
        val eldest = entries.entries.first()
        entries.remove(eldest.key)
        return eldest.value
    }

    @Synchronized
    fun get(candidateKey: String): PredictionCandidate? {
        return entries[candidateKey]
    }

    @Synchronized
    fun replace(candidate: PredictionCandidate): Boolean {
        if (!entries.containsKey(candidate.candidateKey)) return false
        entries[candidate.candidateKey] = candidate
        return true
    }

    @Synchronized
    fun remove(candidateKey: String): PredictionCandidate? = entries.remove(candidateKey)

    @Synchronized
    fun clear(): List<PredictionCandidate> {
        val removed = entries.values.toList()
        entries.clear()
        return removed
    }

    @Synchronized
    fun snapshot(): List<PredictionCandidate> {
        removeExpiredLocked(nowElapsedMs())
        return entries.values.toList()
    }

    @Synchronized
    fun removeExpired(): List<PredictionCandidate> = removeExpiredLocked(nowElapsedMs())

    private fun removeExpiredLocked(nowMs: Long): List<PredictionCandidate> {
        val expired = entries.values.filter { it.expiresElapsedMs <= nowMs }
        expired.forEach { entries.remove(it.candidateKey) }
        return expired
    }
}

class PredictiveMediaCoordinator(
    private val logger: (String) -> Unit,
    private val prewarmer: (PredictiveMediaTrack, () -> Boolean) -> PredictionPrewarmResult,
    private val promoter: (PredictiveMediaTrack) -> PredictionPromotionPayload?,
    executor: ExecutorService? = null,
    private val allowPrewarm: () -> Boolean = { true },
    private val enabled: Boolean = true,
    private val nowElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val hotSet: PredictiveHotSet = PredictiveHotSet(nowElapsedMs = nowElapsedMs)
) : Closeable {
    private val ownedExecutor = executor == null
    private val executor = executor ?: Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PredictiveMediaPrewarm").apply { priority = Thread.MIN_PRIORITY }
    }
    private val inFlight = mutableSetOf<String>()

    private val candidateCount = AtomicLong()
    private val mediaSessionQueueCandidateCount = AtomicLong()
    private val manualNextHintCount = AtomicLong()
    private val manualPreviousHintCount = AtomicLong()
    private val historyTransitionCandidateCount = AtomicLong()
    private val selectedCount = AtomicLong()
    private val rejectedCount = AtomicLong()
    private val expiredCount = AtomicLong()
    private val invalidatedCount = AtomicLong()
    private val preloadStartCount = AtomicLong()
    private val preloadHitCount = AtomicLong()
    private val preloadMissCount = AtomicLong()
    private val preloadSuccessCount = AtomicLong()
    private val preloadFailedCount = AtomicLong()
    private val cachePutCount = AtomicLong()
    private val cacheEvictCount = AtomicLong()
    private val applyHitCount = AtomicLong()
    private val applyMissCount = AtomicLong()
    private val identityMismatchCount = AtomicLong()
    private val preloadCostTotalMs = AtomicLong()
    private val preloadCostMaxMs = AtomicLong()
    private val applyCostTotalMs = AtomicLong()
    private val applyCostMaxMs = AtomicLong()

    fun onCandidate(candidate: PredictionCandidate) {
        if (!enabled || candidate.confidence == PredictionConfidence.NONE) return
        if (!candidate.track.hasCompleteMetadata) {
            reject(candidate, "metadata_incomplete")
            return
        }
        if (!allowPrewarm()) {
            reject(candidate, "power_policy")
            return
        }
        expiredCandidates()
        val existing = hotSet.get(candidate.candidateKey)
        if (existing != null) {
            hotSet.upsert(candidate)
            preloadHitCount.incrementAndGet()
            if (existing.source != candidate.source ||
                existing.confidence != candidate.confidence ||
                existing.direction != candidate.direction
            ) {
                trace("predictionCandidateUpdated", candidate, "updated")
            }
            return
        }
        candidateCount.incrementAndGet()
        selectedCount.incrementAndGet()
        when (candidate.source) {
            PredictionSource.MEDIA_SESSION_QUEUE -> mediaSessionQueueCandidateCount.incrementAndGet()
            PredictionSource.MANUAL_NEXT_WITH_QUEUE -> manualNextHintCount.incrementAndGet()
            PredictionSource.MANUAL_PREVIOUS_WITH_QUEUE -> manualPreviousHintCount.incrementAndGet()
            PredictionSource.HISTORY_TRANSITION -> historyTransitionCandidateCount.incrementAndGet()
        }
        val queued = candidate.copy(state = PredictionState.PREWARM_QUEUED)
        hotSet.upsert(queued)?.let {
            cacheEvictCount.incrementAndGet()
            trace("predictionInvalidated", it, "evicted", "capacity")
        }
        cachePutCount.incrementAndGet()
        trace("predictionCandidateCreated", queued, "accepted")
        synchronized(inFlight) {
            if (!inFlight.add(queued.candidateKey)) {
                preloadHitCount.incrementAndGet()
                return
            }
        }
        trace("predictionPrewarmQueued", queued, "queued")
        executor.execute { prewarm(queued) }
    }

    fun promote(actual: PredictiveMediaTrack): PredictionPromotionResult? {
        expiredCandidates()
        val startedAt = nowElapsedMs()
        val candidates = hotSet.snapshot()
        val promotableCandidates = candidates.filter {
            it.confidence in setOf(PredictionConfidence.CONFIRMED, PredictionConfidence.STRONG)
        }
        val candidate = promotableCandidates.singleOrNull {
            PredictionIdentity.exactMatch(it.track, actual)
        }
        if (candidate == null) {
            if (candidates.isNotEmpty()) {
                if (promotableCandidates.isNotEmpty()) {
                    identityMismatchCount.incrementAndGet()
                }
                rejectedCount.addAndGet(candidates.size.toLong())
                candidates.forEach {
                    val reason = if (it.confidence == PredictionConfidence.WEAK) {
                        "weak_local_only"
                    } else {
                        "identity_mismatch"
                    }
                    trace("predictionRejected", it, "rejected", reason)
                }
                hotSet.clear()
            }
            applyMissCount.incrementAndGet()
            return null
        }
        trace("predictionPromotionAttempt", candidate, "started")
        if (candidate.state != PredictionState.READY &&
            candidate.state != PredictionState.LYRICS_READY &&
            candidate.state != PredictionState.ARTWORK_READY
        ) {
            hotSet.remove(candidate.candidateKey)
            rejectedCount.incrementAndGet()
            applyMissCount.incrementAndGet()
            trace("predictionRejected", candidate, "rejected", "prewarm_not_ready")
            return null
        }
        val payload = promoter(actual)
        if (payload == null || payload.lines.isEmpty()) {
            hotSet.remove(candidate.candidateKey)
            rejectedCount.incrementAndGet()
            applyMissCount.incrementAndGet()
            trace("predictionRejected", candidate, "rejected", "cache_promotion_miss")
            return null
        }
        if (candidate.lyricFingerprint.isBlank() ||
            candidate.lyricFingerprint != payload.lyricFingerprint
        ) {
            hotSet.remove(candidate.candidateKey)
            rejectedCount.incrementAndGet()
            invalidatedCount.incrementAndGet()
            applyMissCount.incrementAndGet()
            trace("predictionInvalidated", candidate, "invalidated", "lyric_fingerprint_changed")
            return null
        }
        val costMs = (nowElapsedMs() - startedAt).coerceAtLeast(0L)
        val promoted = candidate.copy(state = PredictionState.PROMOTED)
        hotSet.remove(candidate.candidateKey)
        applyHitCount.incrementAndGet()
        applyCostTotalMs.addAndGet(costMs)
        updateMax(applyCostMaxMs, costMs)
        trace("predictionPromoted", promoted, "success")
        return PredictionPromotionResult(promoted, payload, costMs)
    }

    fun invalidateAll(reason: String) {
        hotSet.clear().forEach {
            invalidatedCount.incrementAndGet()
            trace("predictionInvalidated", it, "invalidated", reason)
        }
    }

    fun hotSetSnapshot(): List<PredictionCandidate> = hotSet.snapshot()

    fun metricsSnapshot(): PredictiveLyricsMetrics {
        expiredCandidates()
        return PredictiveLyricsMetrics(
            candidateCount = candidateCount.get(),
            mediaSessionQueueCandidateCount = mediaSessionQueueCandidateCount.get(),
            manualNextHintCount = manualNextHintCount.get(),
            manualPreviousHintCount = manualPreviousHintCount.get(),
            historyTransitionCandidateCount = historyTransitionCandidateCount.get(),
            selectedCount = selectedCount.get(),
            rejectedCount = rejectedCount.get(),
            expiredCount = expiredCount.get(),
            invalidatedCount = invalidatedCount.get(),
            preloadStartCount = preloadStartCount.get(),
            preloadHitCount = preloadHitCount.get(),
            preloadMissCount = preloadMissCount.get(),
            preloadSuccessCount = preloadSuccessCount.get(),
            preloadFailedCount = preloadFailedCount.get(),
            cachePutCount = cachePutCount.get(),
            cacheEvictCount = cacheEvictCount.get(),
            applyHitCount = applyHitCount.get(),
            applyMissCount = applyMissCount.get(),
            identityMismatchCount = identityMismatchCount.get(),
            preloadCostTotalMs = preloadCostTotalMs.get(),
            preloadCostMaxMs = preloadCostMaxMs.get(),
            applyCostTotalMs = applyCostTotalMs.get(),
            applyCostMaxMs = applyCostMaxMs.get()
        )
    }

    override fun close() {
        invalidateAll("coordinator_closed")
        synchronized(inFlight) { inFlight.clear() }
        if (ownedExecutor) executor.shutdownNow()
    }

    private fun prewarm(candidate: PredictionCandidate) {
        val startedAt = nowElapsedMs()
        preloadStartCount.incrementAndGet()
        updateCandidate(candidate.candidateKey) { it.copy(state = PredictionState.PREWARMING) }
        trace("predictionPrewarmStart", candidate, "started")
        try {
            val result = prewarmer(candidate.track) {
                val current = hotSet.get(candidate.candidateKey)
                current == null || current.expiresElapsedMs <= nowElapsedMs()
            }
            val costMs = (nowElapsedMs() - startedAt).coerceAtLeast(0L)
            preloadCostTotalMs.addAndGet(costMs)
            updateMax(preloadCostMaxMs, costMs)
            val current = hotSet.get(candidate.candidateKey) ?: return
            if (current.expiresElapsedMs <= nowElapsedMs()) {
                hotSet.remove(candidate.candidateKey)
                expiredCount.incrementAndGet()
                trace(
                    "predictionExpired",
                    current.copy(state = PredictionState.EXPIRED),
                    "expired",
                    "prewarm_completed_after_ttl"
                )
                return
            }
            if (result.lyricsReady || result.artworkPreviewReady) {
                preloadHitCount.incrementAndGet()
                preloadSuccessCount.incrementAndGet()
                val state = when {
                    result.lyricsReady && result.artworkPreviewReady -> PredictionState.READY
                    result.lyricsReady -> PredictionState.LYRICS_READY
                    else -> PredictionState.ARTWORK_READY
                }
                val updated = updateCandidate(candidate.candidateKey) {
                    it.copy(
                        lyricFingerprint = result.lyricFingerprint,
                        artworkId = result.artworkId,
                        lyricsReady = result.lyricsReady,
                        artworkPreviewReady = result.artworkPreviewReady,
                        compressedLyricsReady = result.compressedLyricsReady,
                        state = state
                    )
                }
                if (updated != null) {
                    if (result.lyricsReady) {
                        trace("predictionLyricsReady", updated, "ready")
                    }
                    if (result.artworkPreviewReady) {
                        trace("predictionArtworkReady", updated, "ready")
                    }
                    trace("predictionReady", updated, "ready")
                }
            } else {
                preloadMissCount.incrementAndGet()
                preloadFailedCount.incrementAndGet()
                rejectedCount.incrementAndGet()
                hotSet.remove(candidate.candidateKey)
                trace(
                    "predictionRejected",
                    current.copy(state = PredictionState.REJECTED),
                    "cache_miss",
                    result.reason
                )
            }
        } catch (exception: Exception) {
            preloadMissCount.incrementAndGet()
            preloadFailedCount.incrementAndGet()
            rejectedCount.incrementAndGet()
            hotSet.remove(candidate.candidateKey)
            trace(
                "predictionRejected",
                candidate.copy(state = PredictionState.REJECTED),
                "failure",
                "prewarm_exception"
            )
        } finally {
            synchronized(inFlight) { inFlight.remove(candidate.candidateKey) }
        }
    }

    private fun updateCandidate(
        candidateKey: String,
        transform: (PredictionCandidate) -> PredictionCandidate
    ): PredictionCandidate? {
        val current = hotSet.get(candidateKey) ?: return null
        val updated = transform(current)
        return if (hotSet.replace(updated)) updated else null
    }

    private fun expiredCandidates() {
        hotSet.removeExpired().forEach {
            expiredCount.incrementAndGet()
            trace("predictionExpired", it.copy(state = PredictionState.EXPIRED), "expired")
        }
    }

    private fun reject(candidate: PredictionCandidate, reason: String) {
        rejectedCount.incrementAndGet()
        trace("predictionRejected", candidate.copy(state = PredictionState.REJECTED), "rejected", reason)
    }

    private fun trace(
        stage: String,
        candidate: PredictionCandidate,
        result: String,
        reason: String? = null
    ) {
        RealtimeTrace.record(
            stage = stage,
            trackId = candidate.track.identityDigest,
            transferId = candidate.candidateKey,
            payloadType = "${candidate.source.wireName}:${candidate.confidence.name}",
            result = result,
            reason = reason
        )
        logger(
            "[PredictiveMedia] stage=$stage candidateId=${candidate.candidateKey} " +
                "identityDigest=${candidate.track.identityDigest} " +
                "source=${candidate.source.wireName} confidence=${candidate.confidence.name} " +
                "result=$result reason=${reason.orEmpty()}"
        )
    }

    private fun updateMax(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            if (value <= current || target.compareAndSet(current, value)) return
        }
    }
}
