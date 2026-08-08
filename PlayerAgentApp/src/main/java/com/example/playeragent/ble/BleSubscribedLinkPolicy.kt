package com.example.playeragent.ble

/**
 * Detects a subscribed GATT link that has stopped producing any observable
 * activity. Silence is only a reason to probe the link, never proof that the
 * link is dead: iOS can legitimately suspend all business traffic while the
 * app is in the background.
 */
internal object BleSubscribedLinkPolicy {
    fun isActivityStale(
        subscribed: Boolean,
        subscribedAtMs: Long,
        lastCommandSuccessAtMs: Long,
        lastNotifySuccessAtMs: Long,
        nowMs: Long,
        maxAgeMs: Long
    ): Boolean {
        if (!subscribed || maxAgeMs < 0L) {
            return false
        }
        val lastActivityAtMs = maxOf(
            subscribedAtMs,
            lastCommandSuccessAtMs,
            lastNotifySuccessAtMs
        )
        if (lastActivityAtMs <= 0L) {
            return false
        }
        val ageMs = nowMs - lastActivityAtMs
        return ageMs >= 0L && ageMs > maxAgeMs
    }

    fun isWithoutSuccessStale(
        subscribed: Boolean,
        subscribedAtMs: Long,
        lastCommandSuccessAtMs: Long,
        lastNotifySuccessAtMs: Long,
        nowMs: Long,
        maxAgeMs: Long
    ): Boolean {
        return subscribed &&
            lastCommandSuccessAtMs <= 0L &&
            lastNotifySuccessAtMs <= 0L &&
            subscribedAtMs > 0L &&
            nowMs - subscribedAtMs >= 0L &&
            nowMs - subscribedAtMs > maxAgeMs
    }

    fun shouldSendProbe(
        subscribedAtMs: Long,
        lastCommandSuccessAtMs: Long,
        lastNotifySuccessAtMs: Long,
        lastProbeAtMs: Long,
        nowMs: Long,
        staleAfterMs: Long,
        minimumProbeIntervalMs: Long
    ): Boolean {
        if (!isActivityStale(
                subscribed = true,
                subscribedAtMs = subscribedAtMs,
                lastCommandSuccessAtMs = lastCommandSuccessAtMs,
                lastNotifySuccessAtMs = lastNotifySuccessAtMs,
                nowMs = nowMs,
                maxAgeMs = staleAfterMs
            )
        ) {
            return false
        }
        if (lastProbeAtMs <= 0L) {
            return true
        }
        val sinceLastProbeMs = nowMs - lastProbeAtMs
        return sinceLastProbeMs >= 0L && sinceLastProbeMs >= minimumProbeIntervalMs
    }
}
