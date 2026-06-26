package com.example.playeragent.media

import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class PredictiveLyricsTrack(
    val trackId: String,
    val songKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long = 0L
) {
    val fallbackKey: String
        get() = PredictiveLyricsPipeline.fallbackKey(title, artist, durationMs)
}

data class PredictiveLyricsCandidate(
    val source: String,
    val confidence: Double,
    val track: PredictiveLyricsTrack,
    val mediaId: String = "",
    val queueId: Long = -1L,
    val reason: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)

data class PredictiveLyricsEntry(
    val track: PredictiveLyricsTrack,
    val lines: List<LyricManager.LyricLine>,
    val source: String,
    val buildTimeMs: Long,
    val cachedAtMs: Long,
    val hasWordTiming: Boolean
)

data class PredictiveLyricsApplyResult(
    val entry: PredictiveLyricsEntry,
    val applyCostMs: Long
)

data class PredictiveLyricsMetrics(
    val candidateCount: Long = 0L,
    val mediaSessionQueueCandidateCount: Long = 0L,
    val manualNextHintCount: Long = 0L,
    val historyTransitionCandidateCount: Long = 0L,
    val selectedCount: Long = 0L,
    val rejectedCount: Long = 0L,
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

data class PredictiveLyricsLoadResult(
    val lines: List<LyricManager.LyricLine>,
    val source: String,
    val reason: String
)

class PredictiveLyricsPipeline(
    private val logger: (String) -> Unit,
    private val loader: (PredictiveLyricsTrack) -> PredictiveLyricsLoadResult,
    private val enabled: Boolean = true
) {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PredictiveLyricsThread")
    }
    private val cache = object : LinkedHashMap<String, PredictiveLyricsEntry>(
        MAX_CACHE_SIZE,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, PredictiveLyricsEntry>?
        ): Boolean {
            val shouldEvict = size > MAX_CACHE_SIZE
            if (shouldEvict && eldest != null) {
                cacheEvictCount.incrementAndGet()
                logger(
                    "[PredictiveLyrics] cache evict key=${eldest.key} " +
                        "track=${eldest.value.track.title}"
                )
            }
            return shouldEvict
        }
    }
    private val inFlight = mutableSetOf<String>()

    private val candidateCount = AtomicLong(0L)
    private val mediaSessionQueueCandidateCount = AtomicLong(0L)
    private val manualNextHintCount = AtomicLong(0L)
    private val historyTransitionCandidateCount = AtomicLong(0L)
    private val selectedCount = AtomicLong(0L)
    private val rejectedCount = AtomicLong(0L)
    private val preloadStartCount = AtomicLong(0L)
    private val preloadHitCount = AtomicLong(0L)
    private val preloadMissCount = AtomicLong(0L)
    private val preloadSuccessCount = AtomicLong(0L)
    private val preloadFailedCount = AtomicLong(0L)
    private val cachePutCount = AtomicLong(0L)
    private val cacheEvictCount = AtomicLong(0L)
    private val applyHitCount = AtomicLong(0L)
    private val applyMissCount = AtomicLong(0L)
    private val identityMismatchCount = AtomicLong(0L)
    private val preloadCostTotalMs = AtomicLong(0L)
    private val preloadCostMaxMs = AtomicLong(0L)
    private val applyCostTotalMs = AtomicLong(0L)
    private val applyCostMaxMs = AtomicLong(0L)

    fun onCandidate(candidate: PredictiveLyricsCandidate) {
        if (!enabled) {
            return
        }
        val track = candidate.track
        if (track.title.isBlank()) {
            rejectedCount.incrementAndGet()
            logger(
                "[PredictiveLyricsCandidate] rejected reason=title_blank " +
                    "source=${candidate.source}"
            )
            return
        }
        when (candidate.source) {
            SOURCE_MEDIA_SESSION_QUEUE -> mediaSessionQueueCandidateCount.incrementAndGet()
            SOURCE_MANUAL_NEXT_WITH_QUEUE -> manualNextHintCount.incrementAndGet()
            SOURCE_HISTORY_TRANSITION -> historyTransitionCandidateCount.incrementAndGet()
        }
        candidateCount.incrementAndGet()
        selectedCount.incrementAndGet()
        logger(
            "[PredictiveLyricsCandidate] selected source=${candidate.source} " +
                "confidence=${candidate.confidence} title=${track.title} " +
                "artist=${track.artist} queueId=${candidate.queueId} " +
                "mediaId=${candidate.mediaId} reason=${candidate.reason}"
        )
        logger(
            "[PredictiveLyrics] candidate track=${track.title} artist=${track.artist} " +
                "trackId=${track.trackId} durationMs=${track.durationMs} " +
                "source=${candidate.source}"
        )
        val key = cacheKey(track)
        synchronized(lock) {
            evictExpiredLocked()
            val cached = cache[key] ?: cache[track.fallbackKey]
            if (cached != null && identityMatches(cached.track, track)) {
                preloadHitCount.incrementAndGet()
                logger(
                    "[PredictiveLyrics] preload hit track=${track.title} " +
                        "linesCount=${cached.lines.size} hasWordTiming=${cached.hasWordTiming}"
                )
                return
            }
            if (!inFlight.add(key)) {
                logger("[PredictiveLyrics] preload hit track=${track.title} reason=in_flight")
                return
            }
        }
        executor.execute {
            preload(track, key)
        }
    }

    fun putLoadedTrack(
        track: PredictiveLyricsTrack,
        lines: List<LyricManager.LyricLine>,
        source: String,
        buildTimeMs: Long = 0L
    ) {
        if (!enabled || track.title.isBlank() || lines.isEmpty()) {
            return
        }
        putEntry(
            PredictiveLyricsEntry(
                track = track,
                lines = lines.toList(),
                source = source,
                buildTimeMs = buildTimeMs,
                cachedAtMs = System.currentTimeMillis(),
                hasWordTiming = lines.any { it.words.isNotEmpty() }
            )
        )
    }

    fun applyIfAvailable(track: PredictiveLyricsTrack): PredictiveLyricsApplyResult? {
        if (!enabled || track.title.isBlank()) {
            return null
        }
        val startedAt = SystemClock.elapsedRealtime()
        synchronized(lock) {
            evictExpiredLocked()
            val entry = cache[cacheKey(track)] ?: cache[track.fallbackKey]
            if (entry == null) {
                applyMissCount.incrementAndGet()
                logger("[PredictiveLyrics] apply miss track=${track.title}")
                return null
            }
            if (!identityMatches(entry.track, track)) {
                identityMismatchCount.incrementAndGet()
                applyMissCount.incrementAndGet()
                logger(
                    "[PredictiveLyrics] identity mismatch cached=${entry.track.title} " +
                        "current=${track.title} cachedArtist=${entry.track.artist} " +
                        "currentArtist=${track.artist}"
                )
                return null
            }
            val costMs = SystemClock.elapsedRealtime() - startedAt
            applyHitCount.incrementAndGet()
            applyCostTotalMs.addAndGet(costMs)
            updateMax(applyCostMaxMs, costMs)
            logger(
                "[PredictiveLyrics] apply hit track=${track.title} " +
                    "applyCostMs=$costMs linesCount=${entry.lines.size} " +
                    "hasWordTiming=${entry.hasWordTiming}"
            )
            return PredictiveLyricsApplyResult(entry, costMs)
        }
    }

    fun metricsSnapshot(): PredictiveLyricsMetrics {
        return PredictiveLyricsMetrics(
            candidateCount = candidateCount.get(),
            mediaSessionQueueCandidateCount = mediaSessionQueueCandidateCount.get(),
            manualNextHintCount = manualNextHintCount.get(),
            historyTransitionCandidateCount = historyTransitionCandidateCount.get(),
            selectedCount = selectedCount.get(),
            rejectedCount = rejectedCount.get(),
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

    private fun preload(track: PredictiveLyricsTrack, key: String) {
        val startedAt = SystemClock.elapsedRealtime()
        preloadStartCount.incrementAndGet()
        logger("[PredictiveLyrics] preload start track=${track.title} key=$key")
        try {
            val result = loader(track)
            val costMs = SystemClock.elapsedRealtime() - startedAt
            preloadCostTotalMs.addAndGet(costMs)
            updateMax(preloadCostMaxMs, costMs)
            if (result.lines.isNotEmpty()) {
                preloadHitCount.incrementAndGet()
                preloadSuccessCount.incrementAndGet()
                putEntry(
                    PredictiveLyricsEntry(
                        track = track,
                        lines = result.lines,
                        source = result.source,
                        buildTimeMs = costMs,
                        cachedAtMs = System.currentTimeMillis(),
                        hasWordTiming = result.lines.any { it.words.isNotEmpty() }
                    )
                )
                logger(
                    "[PredictiveLyrics] preload hit track=${track.title} " +
                        "preloadCostMs=$costMs linesCount=${result.lines.size} " +
                        "hasWordTiming=${result.lines.any { it.words.isNotEmpty() }}"
                )
            } else {
                preloadMissCount.incrementAndGet()
                preloadFailedCount.incrementAndGet()
                logger(
                    "[PredictiveLyrics] preload miss track=${track.title} " +
                        "preloadCostMs=$costMs reason=${result.reason}"
                )
            }
        } catch (exception: Exception) {
            preloadMissCount.incrementAndGet()
            preloadFailedCount.incrementAndGet()
            logger(
                "[PredictiveLyrics] preload miss track=${track.title} " +
                    "reason=${exception.message.orEmpty()}"
            )
        } finally {
            synchronized(lock) {
                inFlight.remove(key)
            }
        }
    }

    private fun putEntry(entry: PredictiveLyricsEntry) {
        synchronized(lock) {
            evictExpiredLocked()
            cache[cacheKey(entry.track)] = entry
            cache[entry.track.fallbackKey] = entry
            cachePutCount.incrementAndGet()
            logger(
                "[PredictiveLyrics] cache put track=${entry.track.title} " +
                    "trackId=${entry.track.trackId} linesCount=${entry.lines.size} " +
                    "hasWordTiming=${entry.hasWordTiming} buildTimeMs=${entry.buildTimeMs}"
            )
        }
    }

    private fun evictExpiredLocked() {
        val now = System.currentTimeMillis()
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.cachedAtMs > CACHE_TTL_MS) {
                cacheEvictCount.incrementAndGet()
                logger(
                    "[PredictiveLyrics] cache evict key=${entry.key} " +
                        "reason=ttl track=${entry.value.track.title}"
                )
                iterator.remove()
            }
        }
    }

    private fun cacheKey(track: PredictiveLyricsTrack): String {
        return if (track.trackId.isNotBlank()) {
            "track:${track.trackId}"
        } else {
            track.fallbackKey
        }
    }

    private fun identityMatches(
        cached: PredictiveLyricsTrack,
        current: PredictiveLyricsTrack
    ): Boolean {
        if (cached.trackId.isNotBlank() && current.trackId.isNotBlank() &&
            cached.trackId == current.trackId
        ) {
            return true
        }
        if (normalize(cached.title) != normalize(current.title)) {
            return false
        }
        if (normalize(cached.artist) != normalize(current.artist)) {
            return false
        }
        if (cached.durationMs > 0L && current.durationMs > 0L) {
            return durationBucket(cached.durationMs) == durationBucket(current.durationMs)
        }
        return true
    }

    private fun updateMax(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            if (value <= current) {
                return
            }
            if (target.compareAndSet(current, value)) {
                return
            }
        }
    }

    companion object {
        private const val MAX_CACHE_SIZE = 10
        private const val CACHE_TTL_MS = 30 * 60_000L
        const val SOURCE_MEDIA_SESSION_QUEUE = "media_session_queue"
        const val SOURCE_MANUAL_NEXT_WITH_QUEUE = "manual_next_with_queue"
        const val SOURCE_HISTORY_TRANSITION = "history_transition"

        fun fallbackKey(title: String, artist: String, durationMs: Long): String {
            return "fallback:${normalize(title)}|${normalize(artist)}|${durationBucket(durationMs)}"
        }

        private fun normalize(value: String): String {
            return value.trim().lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ")
        }

        private fun durationBucket(durationMs: Long): Long {
            if (durationMs <= 0L) {
                return 0L
            }
            return durationMs / 2_000L
        }
    }
}
