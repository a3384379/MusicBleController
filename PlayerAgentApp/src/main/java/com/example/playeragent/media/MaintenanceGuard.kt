package com.example.playeragent.media

import java.util.concurrent.CopyOnWriteArrayList

data class MaintenanceGuardSnapshot(
    val realtimeWindowActive: Boolean,
    val activeTrackId: String?,
    val windowReason: String?,
    val windowStartedAt: Long,
    val protectedUntil: Long,
    val pausedTasks: List<String>,
    val deferredTasks: List<String>,
    val maintenanceBusy: Boolean,
    val blockingTask: String?,
    val lastPauseAt: Long,
    val lastResumeAt: Long,
    val realtimeWindowCount: Int,
    val maintenancePausedCount: Int,
    val maintenanceDeferredCount: Int,
    val maintenanceBlockedCount: Int
)

object MaintenanceGuard {
    private const val TRACK_CHANGED_WINDOW_MS = 8_000L
    private const val FULL_LYRICS_WINDOW_MS = 5_000L
    private const val YIELD_SLEEP_MS = 250L

    private val lock = Any()
    private val pausedTasks = linkedSetOf<String>()
    private val deferredTasks = linkedSetOf<String>()
    private val finishListeners = CopyOnWriteArrayList<() -> Unit>()

    private var activeTrackId: String? = null
    private var windowReason: String? = null
    private var windowStartedAt: Long = 0L
    private var protectedUntil: Long = 0L
    private var currentParseTrackId: String? = null
    private var currentParseStartedAt: Long = 0L
    private var blockingTask: String? = null
    private var lastPauseAt: Long = 0L
    private var lastResumeAt: Long = 0L
    private var realtimeWindowCount: Int = 0
    private var maintenancePausedCount: Int = 0
    private var maintenanceDeferredCount: Int = 0
    private var maintenanceBlockedCount: Int = 0

    fun onTrackChanged(trackId: String, logger: (String) -> Unit) {
        startRealtimeWindow("track_changed", trackId, TRACK_CHANGED_WINDOW_MS, logger)
    }

    fun onFullLyricsRequested(trackId: String, logger: (String) -> Unit) {
        startRealtimeWindow("get_full_lyrics", trackId, FULL_LYRICS_WINDOW_MS, logger)
    }

    fun onCurrentTrackParseStart(trackId: String, logger: (String) -> Unit) {
        synchronized(lock) {
            currentParseTrackId = trackId
            currentParseStartedAt = System.currentTimeMillis()
            if (!isActiveLocked(currentParseStartedAt) && trackId.isNotBlank()) {
                startRealtimeWindowLocked(
                    reason = "lyrics_parse",
                    trackId = trackId,
                    durationMs = TRACK_CHANGED_WINDOW_MS,
                    now = currentParseStartedAt,
                    logger = logger
                )
            }
        }
        logger("[MaintenanceGuard] current track parse priority=high trackId=$trackId")
    }

    fun onCurrentTrackParseEnd(trackId: String, lyricsReady: Boolean, logger: (String) -> Unit) {
        val listenersToRun = synchronized(lock) {
            if (trackIdMatchesLocked(trackId, currentParseTrackId)) {
                currentParseTrackId = null
                currentParseStartedAt = 0L
            }
            if (lyricsReady && trackIdMatchesLocked(trackId, activeTrackId)) {
                endRealtimeWindowLocked("lyrics_ready", trackId, logger)
            } else {
                emptyList()
            }
        }
        listenersToRun.forEach { it.invokeSafely() }
    }

    fun shouldDeferMaintenance(
        task: MaintenanceTaskType,
        logger: (String) -> Unit
    ): Boolean {
        val listenersToRun = synchronized(lock) {
            expireIfNeededLocked(logger)
        }
        listenersToRun?.forEach { it.invokeSafely() }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!isActiveLocked(now)) {
                return false
            }
            val taskName = task.name
            deferredTasks += taskName
            blockingTask = taskName
            maintenanceDeferredCount += 1
            maintenanceBlockedCount += 1
            logger("[MaintenanceGuard] defer task=$taskName reason=realtime_window")
            logger("[MaintenanceGuard] block maintenance task=$taskName reason=current_track_priority")
            return true
        }
    }

    fun currentTrackHasPriority(trackId: String, songKey: String = ""): Boolean {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!isActiveLocked(now)) {
                return false
            }
            val identity = trackId.ifBlank { songKey }
            return identity.isBlank() || trackIdMatchesLocked(identity, activeTrackId)
        }
    }

    fun yieldIfRealtimeWindow(
        task: MaintenanceTaskType,
        token: MaintenanceToken?,
        logger: (String) -> Unit
    ): Boolean {
        var pausedLogged = false
        while (true) {
            if (token?.cancelled == true) {
                return false
            }
            val listenersToRun = synchronized(lock) {
                expireIfNeededLocked(logger)
            }
            listenersToRun?.forEach { it.invokeSafely() }
            val shouldPause = synchronized(lock) {
                isActiveLocked(System.currentTimeMillis())
            }
            if (!shouldPause) {
                if (pausedLogged) {
                    synchronized(lock) {
                        pausedTasks -= task.name
                        lastResumeAt = System.currentTimeMillis()
                    }
                    logger("[MaintenanceGuard] resume task=${task.name} reason=realtime_window_end")
                }
                return true
            }
            if (!pausedLogged) {
                pausedLogged = true
                synchronized(lock) {
                    pausedTasks += task.name
                    blockingTask = task.name
                    lastPauseAt = System.currentTimeMillis()
                    maintenancePausedCount += 1
                }
                logger("[MaintenanceGuard] pause task=${task.name} reason=realtime_window")
            }
            runCatching { Thread.sleep(YIELD_SLEEP_MS) }
        }
    }

    fun snapshot(maintenanceBusy: Boolean = QrcMaintenanceCoordinator.isRunning()): MaintenanceGuardSnapshot {
        synchronized(lock) {
            return MaintenanceGuardSnapshot(
                realtimeWindowActive = isActiveLocked(System.currentTimeMillis()),
                activeTrackId = activeTrackId,
                windowReason = windowReason,
                windowStartedAt = windowStartedAt,
                protectedUntil = protectedUntil,
                pausedTasks = pausedTasks.toList(),
                deferredTasks = deferredTasks.toList(),
                maintenanceBusy = maintenanceBusy,
                blockingTask = blockingTask,
                lastPauseAt = lastPauseAt,
                lastResumeAt = lastResumeAt,
                realtimeWindowCount = realtimeWindowCount,
                maintenancePausedCount = maintenancePausedCount,
                maintenanceDeferredCount = maintenanceDeferredCount,
                maintenanceBlockedCount = maintenanceBlockedCount
            )
        }
    }

    fun addWindowEndListener(listener: () -> Unit) {
        finishListeners += listener
    }

    private fun startRealtimeWindow(
        reason: String,
        trackId: String,
        durationMs: Long,
        logger: (String) -> Unit
    ) {
        synchronized(lock) {
            startRealtimeWindowLocked(reason, trackId, durationMs, System.currentTimeMillis(), logger)
        }
    }

    private fun startRealtimeWindowLocked(
        reason: String,
        trackId: String,
        durationMs: Long,
        now: Long,
        logger: (String) -> Unit
    ) {
        val wasActive = isActiveLocked(now)
        val previousTrackId = activeTrackId
        val normalizedTrackId = trackId.ifBlank { activeTrackId.orEmpty() }
        activeTrackId = normalizedTrackId.ifBlank { null }
        windowReason = reason
        if (!wasActive || !trackIdMatchesLocked(previousTrackId, normalizedTrackId)) {
            windowStartedAt = now
            realtimeWindowCount += 1
            logger("[MaintenanceGuard] realtime window start reason=$reason trackId=$normalizedTrackId")
        }
        protectedUntil = maxOf(protectedUntil, now + durationMs)
    }

    private fun expireIfNeededLocked(logger: (String) -> Unit): List<() -> Unit>? {
        val now = System.currentTimeMillis()
        if (windowStartedAt <= 0L) {
            return null
        }
        if (protectedUntil > 0L && now > protectedUntil && currentParseTrackId.isNullOrBlank()) {
            return endRealtimeWindowLocked("timeout", activeTrackId.orEmpty(), logger)
        }
        return null
    }

    private fun endRealtimeWindowLocked(
        reason: String,
        trackId: String,
        logger: (String) -> Unit
    ): List<() -> Unit> {
        if (!isActiveLocked(System.currentTimeMillis())) {
            return emptyList()
        }
        val endedTrackId = trackId.ifBlank { activeTrackId.orEmpty() }
        logger("[MaintenanceGuard] realtime window end reason=$reason trackId=$endedTrackId")
        activeTrackId = null
        windowReason = null
        windowStartedAt = 0L
        protectedUntil = 0L
        currentParseTrackId = null
        currentParseStartedAt = 0L
        blockingTask = null
        return finishListeners.toList()
    }

    private fun isActiveLocked(now: Long): Boolean {
        return protectedUntil > now || currentParseTrackId?.isNotBlank() == true
    }

    private fun trackIdMatchesLocked(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) {
            return true
        }
        return left == right || left.startsWith(right) || right.startsWith(left)
    }

    private fun (() -> Unit).invokeSafely() {
        runCatching { invoke() }
    }
}
