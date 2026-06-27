package com.example.playeragent

import android.os.Debug
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

object StartupGuard {
    private const val HEAVY_TASK_DEFER_MS = 3_000L
    private const val UI_FIRST_FRAME_GRACE_MS = 5_000L
    private const val RETRY_MIN_DELAY_MS = 250L

    @Volatile
    private var appStartAtMs: Long = 0L
    @Volatile
    private var serviceStartedAtMs: Long = 0L
    @Volatile
    private var firstDrawnAtMs: Long = 0L
    private val debugStateLogged = AtomicBoolean(false)

    fun markAppOnCreate(logger: (String) -> Unit) {
        appStartAtMs = SystemClock.elapsedRealtime()
        firstDrawnAtMs = 0L
        debugStateLogged.set(false)
        logger("[StartupGuard] app onCreate")
        logSuspiciousDebugState(logger)
    }

    fun markFirstFrameDrawn(logger: (String) -> Unit) {
        if (firstDrawnAtMs == 0L) {
            firstDrawnAtMs = SystemClock.elapsedRealtime()
            logger("[StartupGuard] first frame drawn")
        }
    }

    fun markServiceOnCreate(logger: (String) -> Unit) {
        serviceStartedAtMs = SystemClock.elapsedRealtime()
        logger("[StartupGuard] service onCreate")
        logSuspiciousDebugState(logger)
    }

    fun markServiceOnStartCommandReturnFast(logger: (String) -> Unit) {
        logger("[StartupGuard] service onStartCommand return fast")
    }

    fun isHeavyTaskAllowed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val appStart = appStartAtMs
        val serviceStart = serviceStartedAtMs
        val startBase = listOf(appStart, serviceStart)
            .filter { it > 0L }
            .minOrNull()
            ?: now
        val quietWindowPassed = now - startBase >= HEAVY_TASK_DEFER_MS
        val uiReady = appStart == 0L ||
            firstDrawnAtMs > 0L ||
            now - appStart >= UI_FIRST_FRAME_GRACE_MS
        return quietWindowPassed && uiReady
    }

    fun runWhenHeavyTaskAllowed(
        taskName: String,
        handler: Handler,
        logger: (String) -> Unit,
        block: () -> Unit
    ) {
        if (isHeavyTaskAllowed()) {
            logger("[StartupGuard] heavy task allowed=true task=$taskName")
            block()
            return
        }

        logger("[StartupGuard] heavy task allowed=false task=$taskName")
        logger("[StartupGuard] defer heavy task reason=app_starting task=$taskName")
        handler.postDelayed(
            {
                runWhenHeavyTaskAllowed(taskName, handler, logger, block)
            },
            nextRetryDelayMs()
        )
    }

    private fun nextRetryDelayMs(): Long {
        val now = SystemClock.elapsedRealtime()
        val appStart = appStartAtMs
        val serviceStart = serviceStartedAtMs
        val startBase = listOf(appStart, serviceStart)
            .filter { it > 0L }
            .minOrNull()
            ?: now
        val heavyTaskAllowedAt = startBase + HEAVY_TASK_DEFER_MS
        return (heavyTaskAllowedAt - now).coerceAtLeast(RETRY_MIN_DELAY_MS)
    }

    private fun logSuspiciousDebugState(logger: (String) -> Unit) {
        val suspicious = Debug.waitingForDebugger() || Debug.isDebuggerConnected()
        if (suspicious && debugStateLogged.compareAndSet(false, true)) {
            logger("[StartupGuard] suspicious debug state detected")
        }
    }
}
