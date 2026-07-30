package com.example.playeragent.ble

/**
 * Detects a subscribed GATT link that has stopped producing any observable
 * activity. This must not depend on queued outbound work: some vendor stacks
 * miss the disconnect callback and otherwise leave an idle ghost subscriber
 * blocking advertising indefinitely.
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
}
