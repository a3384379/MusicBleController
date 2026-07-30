package com.example.playeragent.ble

/**
 * Completed artwork transfers may be requested again when the client lost its
 * local cache or explicitly asks for a refresh. Only very recent duplicate
 * commands are suppressed; in-flight requests are guarded separately by
 * [BleGattServerManager].
 */
internal object AlbumArtRequestPolicy {
    const val COMPLETED_REQUEST_COOLDOWN_MS = 750L

    fun shouldAllowCompletedRequest(
        lastCompletedAtMs: Long?,
        nowMs: Long,
        forceRefresh: Boolean
    ): Boolean {
        if (forceRefresh || lastCompletedAtMs == null) {
            return true
        }
        val elapsedMs = nowMs - lastCompletedAtMs
        return elapsedMs < 0L || elapsedMs >= COMPLETED_REQUEST_COOLDOWN_MS
    }

    fun shouldResendOfferAfterRecovery(
        recoveredFromUnavailable: Boolean,
        hadWaitingClientRequest: Boolean
    ): Boolean {
        return recoveredFromUnavailable && !hadWaitingClientRequest
    }
}
