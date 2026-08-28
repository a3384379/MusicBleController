package com.example.playeragent.diagnostics

import android.os.SystemClock

enum class TrackHandoffTriggerType {
    IOS_NEXT,
    IOS_PREVIOUS,
    SONY_MEDIA_SESSION_NEXT,
    NATURAL_AUTOPLAY,
    SONY_LOCAL_CONTROL,
    UNKNOWN
}

data class TrackHandoffTraceContext(
    val handoffId: String,
    val triggerType: TrackHandoffTriggerType,
    val commandSeq: Long? = null,
    val commandType: String? = null,
    val createdMonoMs: Long,
    val trackId: String? = null
)

/**
 * Privacy-safe, bounded correlation state for Phase 4 diagnostics.
 *
 * This coordinator never drives playback or media publication. It only binds a
 * pending NEXT/PREVIOUS command to the next distinct MediaSession identity so
 * trace events can be joined without changing a BLE header or payload.
 */
object TrackHandoffTraceCoordinator {
    private const val PENDING_TTL_MS = 15_000L
    private const val NOTIFICATION_FOLLOWUP_WINDOW_MS = 1_200L
    private val lock = Any()
    private var nextLocalId = 0L
    private var pending: TrackHandoffTraceContext? = null
    private var active: TrackHandoffTraceContext? = null
    private var lastObservedTrackId = ""
    private var lastNotificationIdentityKey = ""

    fun observeCommand(
        commandSeq: Long?,
        commandType: String,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): TrackHandoffTraceContext? {
        val trigger = when (commandType) {
            "NEXT" -> TrackHandoffTriggerType.IOS_NEXT
            "PREVIOUS" -> TrackHandoffTriggerType.IOS_PREVIOUS
            else -> return null
        }
        return synchronized(lock) {
            TrackHandoffTraceContext(
                handoffId = commandSeq?.let { "command-$it" } ?: localIdLocked(nowMs),
                triggerType = trigger,
                commandSeq = commandSeq,
                commandType = commandType,
                createdMonoMs = nowMs
            ).also { pending = it }
        }
    }

    fun observeNotificationMetadata(
        identityKey: String,
        nowMs: Long = SystemClock.elapsedRealtime()
    ) {
        val context = synchronized(lock) {
            val existing = validPendingLocked(nowMs)
            if (identityKey.isBlank() || identityKey == lastNotificationIdentityKey) {
                existing
            } else {
                lastNotificationIdentityKey = identityKey
                existing
                    ?: active?.takeIf {
                        nowMs - it.createdMonoMs <= NOTIFICATION_FOLLOWUP_WINDOW_MS
                    }
                    ?: TrackHandoffTraceContext(
                        handoffId = localIdLocked(nowMs),
                        triggerType = TrackHandoffTriggerType.UNKNOWN,
                        createdMonoMs = nowMs
                    ).also { pending = it }
            }
        }
        RealtimeTrace.record(
            stage = "notificationMetadataObserved",
            monoMs = nowMs,
            commandSeq = context?.commandSeq,
            commandType = context?.commandType,
            handoffId = context?.handoffId,
            triggerType = context?.triggerType?.name,
            payloadType = "notificationMetadata",
            result = "observed"
        )
    }

    fun observeMediaSessionMetadata(
        trackId: String,
        positionAnchorMs: Long,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): TrackHandoffTraceContext? {
        if (trackId.isBlank()) return null
        val update = synchronized(lock) {
            if (trackId == lastObservedTrackId) return@synchronized null
            lastObservedTrackId = trackId
            val base = validPendingLocked(nowMs) ?: TrackHandoffTraceContext(
                handoffId = localIdLocked(nowMs),
                triggerType = TrackHandoffTriggerType.UNKNOWN,
                createdMonoMs = nowMs
            )
            pending = null
            base.copy(trackId = trackId).also { active = it }
        } ?: return null
        RealtimeTrace.record(
            stage = "mediaSessionMetadataObserved",
            monoMs = nowMs,
            commandSeq = update.commandSeq,
            commandType = update.commandType,
            trackId = trackId,
            handoffId = update.handoffId,
            triggerType = update.triggerType.name,
            positionAnchorMs = positionAnchorMs,
            payloadType = "mediaSessionMetadata",
            result = "observed"
        )
        RealtimeTrace.record(
            stage = "trackIdentityCandidate",
            monoMs = nowMs,
            commandSeq = update.commandSeq,
            commandType = update.commandType,
            trackId = trackId,
            handoffId = update.handoffId,
            triggerType = update.triggerType.name,
            payloadType = "mediaSessionMetadata",
            result = "candidate"
        )
        return update
    }

    fun contextFor(trackId: String): TrackHandoffTraceContext? = synchronized(lock) {
        active?.takeIf { it.trackId == trackId }
    }

    fun pendingContext(nowMs: Long = SystemClock.elapsedRealtime()): TrackHandoffTraceContext? =
        synchronized(lock) { validPendingLocked(nowMs) }

    fun clear() {
        synchronized(lock) {
            pending = null
            active = null
            lastObservedTrackId = ""
            lastNotificationIdentityKey = ""
        }
    }

    private fun validPendingLocked(nowMs: Long): TrackHandoffTraceContext? {
        val candidate = pending ?: return null
        if (nowMs - candidate.createdMonoMs <= PENDING_TTL_MS) return candidate
        pending = null
        return null
    }

    private fun localIdLocked(nowMs: Long): String {
        nextLocalId += 1L
        return "sony-$nowMs-$nextLocalId"
    }
}
