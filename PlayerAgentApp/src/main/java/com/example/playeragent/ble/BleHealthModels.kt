package com.example.playeragent.ble

enum class BleHealthState {
    SERVICE_STOPPED,
    STARTING,
    ADVERTISING,
    CONNECTED,
    SUBSCRIBED,
    CONTROLLABLE,
    SUSPECT,
    RECOVERING,
    ERROR
}

data class BleHealthSnapshot(
    val healthState: BleHealthState,
    val serviceRunning: Boolean,
    val gattStarted: Boolean,
    val gattState: String,
    val advertisingState: String,
    val connectedCount: Int,
    val subscribedCount: Int,
    val notificationInFlight: Int,
    val pendingJobs: Int,
    val lastCommandSuccessAt: Long,
    val lastNotifySuccessAt: Long,
    val lastNotifyFailureAt: Long,
    val notifyFailureCount: Int,
    val lastRecoveryAt: Long,
    val recoveryCount: Int,
    val reason: String?
) {
    companion object {
        fun stopped(reason: String? = null): BleHealthSnapshot {
            return BleHealthSnapshot(
                healthState = BleHealthState.SERVICE_STOPPED,
                serviceRunning = false,
                gattStarted = false,
                gattState = "none",
                advertisingState = "none",
                connectedCount = 0,
                subscribedCount = 0,
                notificationInFlight = 0,
                pendingJobs = 0,
                lastCommandSuccessAt = 0L,
                lastNotifySuccessAt = 0L,
                lastNotifyFailureAt = 0L,
                notifyFailureCount = 0,
                lastRecoveryAt = 0L,
                recoveryCount = 0,
                reason = reason
            )
        }
    }
}
