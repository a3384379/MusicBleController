package com.example.playeragent.history

import android.content.Context
import android.os.SystemClock
import com.example.playeragent.media.PlaybackStateReader
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PlaybackHistoryMonitor(
    context: Context,
    private val logger: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val repository = PlaybackHistoryRepository(appContext)
    private val reader = PlaybackStateReader(
        context = appContext,
        logger = logger,
        includeLyric = false
    )
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PlaybackHistoryMonitorThread").apply {
                isDaemon = true
            }
        }

    @Volatile
    private var running = false
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var activeSession: ActiveSession? = null
    private var lastTickElapsedMs: Long = 0L
    private var lastTickPlaying = false
    private var missingSinceElapsedMs: Long? = null

    fun start() {
        if (running) {
            return
        }
        running = true
        updateStatus()
        scheduledFuture = executor.scheduleWithFixedDelay(
            { safeTick() },
            0L,
            MONITOR_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        logger("[History] monitor started")
    }

    fun stop() {
        if (!running) {
            return
        }
        running = false
        scheduledFuture?.cancel(false)
        scheduledFuture = null
        executor.execute {
            runCatching {
                endActiveSession(EndReason.SERVICE_STOPPED)
                updateStatus()
            }.onFailure {
                logger("[History] stop flush failed error=${it.message}")
            }
        }
        executor.shutdown()
        logger("[History] monitor stopped")
    }

    private fun safeTick() {
        runCatching {
            tick()
        }.onFailure {
            logger("[History] tick failed error=${it.message}")
        }
    }

    private fun tick() {
        if (!running) {
            return
        }
        val nowWallMs = System.currentTimeMillis()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val snapshot = reader.readFastPlaybackSnapshot()
        if (snapshot == null || snapshot.title.isBlank()) {
            handleMissingSnapshot(nowElapsedMs, nowWallMs)
            return
        }
        missingSinceElapsedMs = null
        val snapshotIdentityKey = repository.buildIdentityKey(snapshot)
        val proposedTrackKey = repository.buildTrackKey(snapshot)
        val current = activeSession
        val trackKey = if (current != null && current.identityKey == snapshotIdentityKey) {
            current.trackKey
        } else {
            proposedTrackKey
        }
        repository.upsertTrackForSnapshot(trackKey, snapshot, nowWallMs)

        if (snapshot.stopped) {
            endActiveSession(EndReason.STOPPED)
            lastTickElapsedMs = nowElapsedMs
            lastTickPlaying = false
            updateStatus()
            return
        }

        if (snapshot.playing) {
            if (current == null) {
                startSession(trackKey, snapshotIdentityKey, snapshot, nowWallMs, nowElapsedMs)
            } else if (current.trackKey != trackKey ||
                current.packageName != snapshot.packageName
            ) {
                accumulateCurrentInterval(nowElapsedMs)
                endActiveSession(EndReason.TRACK_CHANGED)
                startSession(trackKey, snapshotIdentityKey, snapshot, nowWallMs, nowElapsedMs)
            } else if (shouldStartNewAfterLongPause(current, nowWallMs)) {
                endActiveSession(EndReason.PAUSE_TIMEOUT)
                startSession(trackKey, snapshotIdentityKey, snapshot, nowWallMs, nowElapsedMs)
            } else {
                accumulateCurrentInterval(nowElapsedMs)
                current.pauseStartedAtMs = null
                current.endPositionMs = snapshot.positionMs.coerceAtLeast(0L)
                current.durationMs = snapshot.durationMs.coerceAtLeast(current.durationMs)
                maybeCountPlay(current, nowWallMs)
                maybePersistActive(current, nowWallMs, force = false)
            }
        } else {
            if (current != null && current.trackKey == trackKey) {
                current.endPositionMs = snapshot.positionMs.coerceAtLeast(0L)
                current.durationMs = snapshot.durationMs.coerceAtLeast(current.durationMs)
                if (current.pauseStartedAtMs == null) {
                    current.pauseStartedAtMs = nowWallMs
                    persistActive(current, nowWallMs, force = true)
                }
            }
        }

        lastTickElapsedMs = nowElapsedMs
        lastTickPlaying = snapshot.playing
        updateStatus()
    }

    private fun handleMissingSnapshot(nowElapsedMs: Long, nowWallMs: Long) {
        val current = activeSession
        if (current == null) {
            lastTickElapsedMs = nowElapsedMs
            lastTickPlaying = false
            updateStatus()
            return
        }
        val missingSince = missingSinceElapsedMs ?: nowElapsedMs.also {
            missingSinceElapsedMs = it
        }
        if (nowElapsedMs - missingSince >= MEDIA_SESSION_MISSING_TIMEOUT_MS) {
            endActiveSession(EndReason.MEDIA_SESSION_MISSING)
        } else {
            maybePersistActive(current, nowWallMs, force = false)
        }
        lastTickElapsedMs = nowElapsedMs
        lastTickPlaying = false
        updateStatus()
    }

    private fun startSession(
        trackKey: String,
        identityKey: String,
        snapshot: FastPlaybackSnapshot,
        nowWallMs: Long,
        nowElapsedMs: Long
    ) {
        val entity = PlaySessionEntity(
            trackKey = trackKey,
            startedAt = nowWallMs,
            endedAt = null,
            listenedMs = 0L,
            startPositionMs = snapshot.positionMs.coerceAtLeast(0L),
            endPositionMs = snapshot.positionMs.coerceAtLeast(0L),
            durationMs = snapshot.durationMs.coerceAtLeast(0L),
            completed = false,
            skipped = false,
            countedPlay = false,
            packageName = snapshot.packageName,
            updatedAt = nowWallMs
        )
        val sessionId = repository.insertSession(entity)
        activeSession = ActiveSession(
            sessionId = sessionId,
            trackKey = trackKey,
            identityKey = identityKey,
            title = snapshot.title,
            artist = snapshot.artist,
            packageName = snapshot.packageName,
            startedAt = nowWallMs,
            listenedMs = 0L,
            persistedListenMs = 0L,
            startPositionMs = snapshot.positionMs.coerceAtLeast(0L),
            endPositionMs = snapshot.positionMs.coerceAtLeast(0L),
            durationMs = snapshot.durationMs.coerceAtLeast(0L),
            lastPersistedAtMs = nowWallMs
        )
        lastTickElapsedMs = nowElapsedMs
        lastTickPlaying = true
        logger("[History] session started id=$sessionId track=${snapshot.title}")
    }

    private fun accumulateCurrentInterval(nowElapsedMs: Long) {
        val current = activeSession ?: return
        if (!lastTickPlaying || lastTickElapsedMs <= 0L) {
            return
        }
        val rawDelta = nowElapsedMs - lastTickElapsedMs
        if (rawDelta <= 0L) {
            return
        }
        val delta = rawDelta.coerceAtMost(MAX_LISTEN_DELTA_MS)
        current.listenedMs += delta
    }

    private fun maybeCountPlay(current: ActiveSession, nowWallMs: Long) {
        if (current.countedPlay) {
            return
        }
        val duration = current.durationMs
        val shouldCount = current.listenedMs >= PLAY_COUNT_MIN_LISTEN_MS ||
            (duration > 0L && current.listenedMs >= (duration * PLAY_COUNT_DURATION_RATIO).toLong())
        if (!shouldCount) {
            return
        }
        current.countedPlay = true
        repository.markPlayCounted(current.trackKey, nowWallMs)
        persistActive(current, nowWallMs, force = true)
        logger("[History] play counted track=${current.title}")
    }

    private fun shouldStartNewAfterLongPause(
        current: ActiveSession,
        nowWallMs: Long
    ): Boolean {
        val pauseStartedAt = current.pauseStartedAtMs ?: return false
        return nowWallMs - pauseStartedAt >= PAUSE_SESSION_TIMEOUT_MS
    }

    private fun maybePersistActive(
        current: ActiveSession,
        nowWallMs: Long,
        force: Boolean
    ) {
        if (force || nowWallMs - current.lastPersistedAtMs >= ACTIVE_PERSIST_INTERVAL_MS) {
            persistActive(current, nowWallMs, force = force)
        }
    }

    private fun persistActive(
        current: ActiveSession,
        nowWallMs: Long,
        force: Boolean
    ) {
        val listenDelta = (current.listenedMs - current.persistedListenMs).coerceAtLeast(0L)
        if (!force && listenDelta <= 0L) {
            return
        }
        repository.updateSessionWithListenDelta(
            session = current.toEntity(endedAt = null, completed = false, skipped = false, nowWallMs),
            listenDeltaMs = listenDelta,
            nowMs = nowWallMs
        )
        current.persistedListenMs = current.listenedMs
        current.lastPersistedAtMs = nowWallMs
        logger("[History] session updated listenedMs=${current.listenedMs}")
    }

    private fun endActiveSession(reason: EndReason) {
        val current = activeSession ?: return
        val nowWallMs = System.currentTimeMillis()
        val completed = isCompleted(current)
        val skipped = !completed &&
            reason == EndReason.TRACK_CHANGED &&
            current.listenedMs < SKIP_MAX_LISTEN_MS
        if (!current.countedPlay) {
            maybeCountPlay(current, nowWallMs)
        }
        val listenDelta = (current.listenedMs - current.persistedListenMs).coerceAtLeast(0L)
        repository.finishSession(
            session = current.toEntity(
                endedAt = nowWallMs,
                completed = completed,
                skipped = skipped,
                nowMs = nowWallMs
            ),
            listenDeltaMs = listenDelta,
            completedIncrement = completed,
            skippedIncrement = skipped,
            nowMs = nowWallMs
        )
        activeSession = null
        lastTickPlaying = false
        missingSinceElapsedMs = null
        logger(
            "[History] session ended id=${current.sessionId} " +
                "listenedMs=${current.listenedMs} completed=$completed skipped=$skipped"
        )
    }

    private fun isCompleted(current: ActiveSession): Boolean {
        val duration = current.durationMs
        if (duration <= 0L) {
            return false
        }
        return current.endPositionMs >= duration - COMPLETION_POSITION_TOLERANCE_MS ||
            current.endPositionMs.toDouble() / duration.toDouble() >= COMPLETION_RATIO ||
            current.listenedMs >= (duration * COMPLETION_RATIO).toLong()
    }

    private fun updateStatus() {
        val current = activeSession
        STATUS.set(
            PlaybackHistoryMonitorStatus(
                running = running,
                activeSessionId = current?.sessionId,
                activeTitle = current?.title.orEmpty(),
                activeArtist = current?.artist.orEmpty(),
                activeListenedMs = current?.listenedMs ?: 0L,
                activeTrackKey = current?.trackKey.orEmpty(),
                lastUpdatedAt = System.currentTimeMillis()
            )
        )
    }

    private data class ActiveSession(
        val sessionId: Long,
        val trackKey: String,
        val identityKey: String,
        val title: String,
        val artist: String,
        val packageName: String,
        val startedAt: Long,
        var listenedMs: Long,
        var persistedListenMs: Long,
        val startPositionMs: Long,
        var endPositionMs: Long,
        var durationMs: Long,
        var countedPlay: Boolean = false,
        var pauseStartedAtMs: Long? = null,
        var lastPersistedAtMs: Long
    ) {
        fun toEntity(
            endedAt: Long?,
            completed: Boolean,
            skipped: Boolean,
            nowMs: Long
        ): PlaySessionEntity {
            return PlaySessionEntity(
                sessionId = sessionId,
                trackKey = trackKey,
                startedAt = startedAt,
                endedAt = endedAt,
                listenedMs = listenedMs,
                startPositionMs = startPositionMs,
                endPositionMs = endPositionMs,
                durationMs = durationMs,
                completed = completed,
                skipped = skipped,
                countedPlay = countedPlay,
                packageName = packageName,
                updatedAt = nowMs
            )
        }
    }

    private enum class EndReason {
        TRACK_CHANGED,
        MEDIA_SESSION_MISSING,
        STOPPED,
        PAUSE_TIMEOUT,
        SERVICE_STOPPED
    }

    companion object {
        private const val MONITOR_INTERVAL_MS = 1_000L
        private const val MAX_LISTEN_DELTA_MS = 5_000L
        private const val ACTIVE_PERSIST_INTERVAL_MS = 5_000L
        private const val MEDIA_SESSION_MISSING_TIMEOUT_MS = 10_000L
        private const val PAUSE_SESSION_TIMEOUT_MS = 30 * 60 * 1_000L
        private const val PLAY_COUNT_MIN_LISTEN_MS = 30_000L
        private const val PLAY_COUNT_DURATION_RATIO = 0.5
        private const val SKIP_MAX_LISTEN_MS = 15_000L
        private const val COMPLETION_RATIO = 0.9
        private const val COMPLETION_POSITION_TOLERANCE_MS = 5_000L

        private val STATUS = AtomicReference(PlaybackHistoryMonitorStatus())

        fun latestStatus(): PlaybackHistoryMonitorStatus {
            return STATUS.get()
        }
    }
}
