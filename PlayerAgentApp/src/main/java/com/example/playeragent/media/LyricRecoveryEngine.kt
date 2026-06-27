package com.example.playeragent.media

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class LyricRecoveryState {
    IDLE,
    WAITING_QQMUSIC_CACHE,
    WATCHING_RECENT_QRC,
    RETRY_SCHEDULED,
    RETRYING,
    RESOLVED,
    EXPIRED,
    DISABLED
}

data class LyricRecoverySnapshot(
    val state: LyricRecoveryState = LyricRecoveryState.IDLE,
    val songKey: String = "",
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val startedAt: Long = 0L,
    val expiresAt: Long = 0L,
    val lastRetryAt: Long = 0L,
    val retryCount: Int = 0,
    val lastReason: String = "",
    val lastQrcGeneration: Long = 0L,
    val recentCandidateCount: Int = 0,
    val lastSuggestion: String = ""
)

class LyricRecoveryEngine(
    private val logger: (String) -> Unit,
    private val retryCallback: (reason: String, bypassRetryableCooldown: Boolean) -> Boolean
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "LyricRecoveryThread")
    }
    private var session: RecoverySession? = null
    private var timerFuture: ScheduledFuture<*>? = null
    private var debounceFuture: ScheduledFuture<*>? = null
    private val fastProbeFutures = mutableListOf<ScheduledFuture<*>>()

    @Synchronized
    fun onActiveSongNoLyrics(
        songKey: String,
        trackId: String,
        title: String,
        artist: String,
        album: String,
        reason: String,
        qrcGeneration: Long
    ) {
        if (!isRecoverableReason(reason)) {
            return
        }
        val now = System.currentTimeMillis()
        val existing = session
        if (existing?.songKey == songKey &&
            existing.state != LyricRecoveryState.EXPIRED &&
            existing.state != LyricRecoveryState.DISABLED
        ) {
            existing.lastReason = reason
            existing.lastQrcGeneration = qrcGeneration
            return
        }
        cancelTimersLocked()
        val state = if (reason == WAITING_QQMUSIC_LYRIC_CACHE_REASON) {
            LyricRecoveryState.WAITING_QQMUSIC_CACHE
        } else {
            LyricRecoveryState.WATCHING_RECENT_QRC
        }
        session = RecoverySession(
            songKey = songKey,
            trackId = trackId,
            title = title,
            artist = artist,
            album = album,
            startedAt = now,
            expiresAt = now + RECOVERY_WINDOW_MS,
            lastReason = reason,
            lastQrcGeneration = qrcGeneration,
            state = state
        )
        logger("[LyricRecovery] start songKey=$songKey reason=$reason expiresInMs=$RECOVERY_WINDOW_MS")
        logger("[LyricRecovery] state IDLE -> ${state.name}")
        scheduleRetryLocked("initial", INITIAL_RETRY_DELAY_MS, bypassRetryableCooldown = true)
        scheduleFastProbeRetriesLocked()
        scheduleTimerRetryLocked()
    }

    @Synchronized
    fun onQrcGenerationChanged(oldGeneration: Long, newGeneration: Long) {
        val current = session ?: return
        if (!current.isActive()) {
            return
        }
        current.lastQrcGeneration = newGeneration
        logger(
            "[LyricRecovery] qrc generation changed " +
                "songKey=${current.songKey} old=$oldGeneration new=$newGeneration"
        )
        scheduleDebouncedRetryLocked("qrc generation changed", bypassRetryableCooldown = true)
    }

    @Synchronized
    fun onIncrementalBatchDone(groupIds: Collection<String>) {
        val current = session ?: return
        if (!current.isActive() || groupIds.isEmpty()) {
            return
        }
        current.recentCandidateCount += groupIds.size
        groupIds.take(8).forEach { groupId ->
            logger(
                "[LyricRecovery] recent group groupId=$groupId " +
                    "title=unknown artist=unknown valid=false"
            )
            logger("[LyricRecovery] recent candidate rejected reason=metadata diagnostic unavailable")
        }
        scheduleDebouncedRetryLocked("incremental batch done", bypassRetryableCooldown = true)
    }

    @Synchronized
    fun onFuzzyIndexReady() {
        val current = session ?: return
        if (!current.isActive()) {
            return
        }
        scheduleRetryLocked("fuzzy index ready", delayMs = 0L, bypassRetryableCooldown = false)
    }

    @Synchronized
    fun onManualRefresh() {
        val current = session ?: return
        if (!current.isActive()) {
            return
        }
        logger("[LyricRecovery] manual refresh songKey=${current.songKey}")
        scheduleRetryLocked("manual refresh", delayMs = 0L, bypassRetryableCooldown = true)
    }

    @Synchronized
    fun onSongChanged() {
        val current = session
        if (current != null && current.isActive()) {
            logger("[LyricRecovery] cancelled reason=song changed songKey=${current.songKey}")
        }
        session = null
        cancelTimersLocked()
    }

    @Synchronized
    fun onLyricLoaded(songKey: String, lineCount: Int) {
        val current = session ?: return
        if (current.songKey != songKey) {
            return
        }
        if (lineCount > 0) {
            current.state = LyricRecoveryState.RESOLVED
            val costMs = System.currentTimeMillis() - current.startedAt
            logger("[LyricRecovery] resolved songKey=$songKey lines=$lineCount costMs=$costMs")
            session = null
            cancelTimersLocked()
        }
    }

    @Synchronized
    fun onFinalFailure(songKey: String, reason: String) {
        val current = session ?: return
        if (current.songKey != songKey) {
            return
        }
        current.state = if (reason.contains("negative", ignoreCase = true)) {
            LyricRecoveryState.DISABLED
        } else {
            LyricRecoveryState.EXPIRED
        }
        current.lastReason = reason
        logger("[LyricRecovery] expired songKey=$songKey retryCount=${current.retryCount} reason=$reason")
        cancelTimersLocked()
    }

    @Synchronized
    fun snapshot(): LyricRecoverySnapshot {
        val current = session ?: return LyricRecoverySnapshot()
        expireIfNeededLocked(current)
        return LyricRecoverySnapshot(
            state = current.state,
            songKey = current.songKey,
            trackId = current.trackId,
            title = current.title,
            artist = current.artist,
            album = current.album,
            startedAt = current.startedAt,
            expiresAt = current.expiresAt,
            lastRetryAt = current.lastRetryAt,
            retryCount = current.retryCount,
            lastReason = current.lastReason,
            lastQrcGeneration = current.lastQrcGeneration,
            recentCandidateCount = current.recentCandidateCount,
            lastSuggestion = current.lastSuggestion
        )
    }

    @Synchronized
    fun isActiveFor(songKey: String): Boolean {
        val current = session ?: return false
        expireIfNeededLocked(current)
        return current.songKey == songKey && current.isActive()
    }

    @Synchronized
    fun shutdown() {
        cancelTimersLocked()
        scheduler.shutdownNow()
    }

    private fun scheduleTimerRetryLocked() {
        timerFuture?.cancel(false)
        timerFuture = scheduler.scheduleAtFixedRate(
            {
                fireRetry("timer", bypassRetryableCooldown = false)
            },
            TIMER_RETRY_INTERVAL_MS,
            TIMER_RETRY_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        logger("[LyricRecovery] retry scheduled reason=timer delayMs=$TIMER_RETRY_INTERVAL_MS")
    }

    private fun scheduleFastProbeRetriesLocked() {
        fastProbeFutures.forEach { it.cancel(false) }
        fastProbeFutures.clear()
        FAST_PROBE_RETRY_DELAYS_MS.forEach { delayMs ->
            val future = scheduler.schedule(
                {
                    fireRetry("fast probe", bypassRetryableCooldown = true)
                },
                delayMs,
                TimeUnit.MILLISECONDS
            )
            fastProbeFutures += future
            logger("[LyricRecovery] retry scheduled reason=fast_probe delayMs=$delayMs")
        }
    }

    private fun scheduleDebouncedRetryLocked(
        reason: String,
        bypassRetryableCooldown: Boolean
    ) {
        debounceFuture?.cancel(false)
        debounceFuture = scheduler.schedule(
            {
                fireRetry(reason, bypassRetryableCooldown)
            },
            WATCHER_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS
        )
        logger("[LyricRecovery] retry scheduled reason=$reason delayMs=$WATCHER_DEBOUNCE_MS")
    }

    private fun scheduleRetryLocked(
        reason: String,
        delayMs: Long,
        bypassRetryableCooldown: Boolean
    ) {
        scheduler.schedule(
            {
                fireRetry(reason, bypassRetryableCooldown)
            },
            delayMs,
            TimeUnit.MILLISECONDS
        )
        logger("[LyricRecovery] retry scheduled reason=$reason delayMs=$delayMs")
    }

    private fun fireRetry(reason: String, bypassRetryableCooldown: Boolean) {
        val target: RecoverySession = synchronized(this) {
            val current = session ?: return
            if (!current.isActive()) {
                return
            }
            if (expireIfNeededLocked(current)) {
                return
            }
            if (current.retryCount >= MAX_RETRY_COUNT) {
                current.state = LyricRecoveryState.EXPIRED
                logger("[LyricRecovery] expired songKey=${current.songKey} retryCount=${current.retryCount}")
                return
            }
            val now = System.currentTimeMillis()
            if (!bypassRetryableCooldown && now - current.lastRetryAt < MIN_RETRY_GAP_MS) {
                logger("[LyricRecovery] retry skipped reason=rate limited songKey=${current.songKey}")
                return
            }
            current.state = LyricRecoveryState.RETRYING
            current.retryCount += 1
            current.lastRetryAt = now
            current.lastReason = reason
            current.lastSuggestion = if (bypassRetryableCooldown) {
                "open_qqmusic_lyrics"
            } else {
                "retry_later"
            }
            logger("[LyricRecovery] retry now reason=$reason songKey=${current.songKey}")
            if (bypassRetryableCooldown) {
                logger("[LyricRecovery] bypass retryable cooldown reason=$reason")
            } else {
                logger("[LyricRecovery] respect cooldown retryAfter=${current.lastRetryAt + MIN_RETRY_GAP_MS}")
            }
            current.copy()
        }
        val accepted = retryCallback(reason, bypassRetryableCooldown)
        synchronized(this) {
            val current = session
            if (current?.songKey != target.songKey) {
                logger("[LyricRecovery] retry skipped reason=song changed")
                return
            }
            if (!accepted && current.state == LyricRecoveryState.RETRYING) {
                current.state = LyricRecoveryState.RETRY_SCHEDULED
                logger("[LyricRecovery] retry skipped reason=in flight")
            } else if (current.state == LyricRecoveryState.RETRYING) {
                current.state = LyricRecoveryState.WAITING_QQMUSIC_CACHE
            }
        }
    }

    private fun expireIfNeededLocked(current: RecoverySession): Boolean {
        if (!current.isActive()) {
            return false
        }
        if (System.currentTimeMillis() < current.expiresAt) {
            return false
        }
        current.state = LyricRecoveryState.EXPIRED
        logger("[LyricRecovery] expired songKey=${current.songKey} retryCount=${current.retryCount}")
        cancelTimersLocked()
        return true
    }

    private fun cancelTimersLocked() {
        timerFuture?.cancel(false)
        debounceFuture?.cancel(false)
        fastProbeFutures.forEach { it.cancel(false) }
        fastProbeFutures.clear()
        timerFuture = null
        debounceFuture = null
    }

    private fun isRecoverableReason(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized == WAITING_QQMUSIC_LYRIC_CACHE_REASON ||
            normalized.contains("no safe qrc candidate") ||
            normalized.contains("qrc cooldown retry pending") ||
            normalized.contains("fuzzy index warming") ||
            normalized.contains("exact miss fuzzy not ready") ||
            normalized.contains("maintenance busy")
    }

    private data class RecoverySession(
        val songKey: String,
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val startedAt: Long,
        val expiresAt: Long,
        var lastRetryAt: Long = 0L,
        var retryCount: Int = 0,
        var lastReason: String,
        var lastQrcGeneration: Long,
        var recentCandidateCount: Int = 0,
        var lastSuggestion: String = "open_qqmusic_lyrics",
        var state: LyricRecoveryState
    ) {
        fun isActive(): Boolean {
            return state != LyricRecoveryState.IDLE &&
                state != LyricRecoveryState.RESOLVED &&
                state != LyricRecoveryState.EXPIRED &&
                state != LyricRecoveryState.DISABLED
        }

        fun copy(): RecoverySession = RecoverySession(
            songKey = songKey,
            trackId = trackId,
            title = title,
            artist = artist,
            album = album,
            startedAt = startedAt,
            expiresAt = expiresAt,
            lastRetryAt = lastRetryAt,
            retryCount = retryCount,
            lastReason = lastReason,
            lastQrcGeneration = lastQrcGeneration,
            recentCandidateCount = recentCandidateCount,
            lastSuggestion = lastSuggestion,
            state = state
        )
    }

    companion object {
        private const val WAITING_QQMUSIC_LYRIC_CACHE_REASON = "waiting qqmusic lyric cache"
        private const val RECOVERY_WINDOW_MS = 3 * 60_000L
        private const val TIMER_RETRY_INTERVAL_MS = 30_000L
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private val FAST_PROBE_RETRY_DELAYS_MS = longArrayOf(3_000L, 7_000L, 15_000L, 25_000L)
        private const val WATCHER_DEBOUNCE_MS = 2_000L
        private const val MIN_RETRY_GAP_MS = 30_000L
        private const val MAX_RETRY_COUNT = 10
    }
}
