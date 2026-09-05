package com.example.playeragent.ui

import com.example.playeragent.ble.BleHealthState

enum class SetupAction {
    NONE,
    OPEN_NOTIFICATION_ACCESS,
    OPEN_FILE_ACCESS,
    OPEN_ACCESSIBILITY,
    REQUEST_RUNTIME_PERMISSIONS,
    START_SERVICE
}

enum class SafeRepairAction {
    OPEN_NOTIFICATION_ACCESS,
    OPEN_FILE_ACCESS,
    OPEN_ACCESSIBILITY,
    REQUEST_RUNTIME_PERMISSIONS,
    START_SERVICE,
    RECOVER_BLE
}

data class SetupStepUiState(
    val title: UiTextKey,
    val detail: UiTextKey,
    val complete: Boolean,
    val action: SetupAction
)

data class PlayerAgentProductUiState(
    val statusTitle: UiTextKey,
    val statusDetail: UiTextKey,
    val serviceSummary: UiTextKey,
    val bleSummary: UiTextKey,
    val iPhoneSummary: UiTextKey,
    val lyricSummary: String,
    val artworkSummary: UiTextKey,
    val setupSteps: List<SetupStepUiState>,
    val safeRepairActions: List<SafeRepairAction>
) {
    val completedSetupSteps: Int get() = setupSteps.count { it.complete }
}

data class PlayerAgentUiInputs(
    val healthState: BleHealthState,
    val serviceRunning: Boolean,
    val notificationAccess: Boolean,
    val fileAccess: Boolean,
    val accessibilityEnabled: Boolean,
    val runtimePermissions: Boolean,
    val hasPlayback: Boolean,
    val lyricStatus: String,
    val artworkStatus: String
)

enum class UiTextKey {
    STATUS_CONTROLLABLE,
    STATUS_CONTROLLABLE_DETAIL,
    STATUS_RECOVERING,
    STATUS_RECOVERING_DETAIL,
    STATUS_SETUP_REQUIRED,
    STATUS_SETUP_REQUIRED_DETAIL,
    STATUS_ACTION_REQUIRED,
    STATUS_SERVICE_STOPPED_DETAIL,
    STATUS_BLE_ERROR_DETAIL,
    STATUS_WAITING_IPHONE,
    STATUS_WAITING_IPHONE_DETAIL,
    SUMMARY_RUNNING,
    SUMMARY_NOT_RUNNING,
    SUMMARY_NOT_STARTED,
    SUMMARY_STARTING,
    SUMMARY_ADVERTISING,
    SUMMARY_CONNECTED,
    SUMMARY_SUBSCRIBED,
    SUMMARY_HEALTHY,
    SUMMARY_SUSPECT,
    SUMMARY_RECOVERING,
    SUMMARY_ERROR,
    SUMMARY_CONTROLLABLE,
    SUMMARY_CONNECTING,
    SUMMARY_NOT_CONNECTED,
    ARTWORK_READY,
    ARTWORK_LOADING,
    ARTWORK_UNAVAILABLE,
    SETUP_NOTIFICATION_TITLE,
    SETUP_NOTIFICATION_DETAIL,
    SETUP_FILE_TITLE,
    SETUP_FILE_DETAIL,
    SETUP_ACCESSIBILITY_TITLE,
    SETUP_ACCESSIBILITY_DETAIL,
    SETUP_RUNTIME_TITLE,
    SETUP_RUNTIME_DETAIL,
    NOTIFICATION_CONTROLLABLE_TITLE,
    NOTIFICATION_CONTROLLABLE_DETAIL,
    NOTIFICATION_RECOVERING_TITLE,
    NOTIFICATION_RECOVERING_DETAIL,
    NOTIFICATION_STOPPED_TITLE,
    NOTIFICATION_STOPPED_DETAIL,
    NOTIFICATION_ACTION_TITLE,
    NOTIFICATION_ACTION_DETAIL,
    NOTIFICATION_WAITING_TITLE,
    NOTIFICATION_WAITING_DETAIL
}

object PlayerAgentUiStateMapper {
    fun map(inputs: PlayerAgentUiInputs): PlayerAgentProductUiState {
        val steps = listOf(
            SetupStepUiState(
                title = UiTextKey.SETUP_NOTIFICATION_TITLE,
                detail = UiTextKey.SETUP_NOTIFICATION_DETAIL,
                complete = inputs.notificationAccess,
                action = actionWhenMissing(inputs.notificationAccess, SetupAction.OPEN_NOTIFICATION_ACCESS)
            ),
            SetupStepUiState(
                title = UiTextKey.SETUP_FILE_TITLE,
                detail = UiTextKey.SETUP_FILE_DETAIL,
                complete = inputs.fileAccess,
                action = actionWhenMissing(inputs.fileAccess, SetupAction.OPEN_FILE_ACCESS)
            ),
            SetupStepUiState(
                title = UiTextKey.SETUP_ACCESSIBILITY_TITLE,
                detail = UiTextKey.SETUP_ACCESSIBILITY_DETAIL,
                complete = inputs.accessibilityEnabled,
                action = actionWhenMissing(inputs.accessibilityEnabled, SetupAction.OPEN_ACCESSIBILITY)
            ),
            SetupStepUiState(
                title = UiTextKey.SETUP_RUNTIME_TITLE,
                detail = UiTextKey.SETUP_RUNTIME_DETAIL,
                complete = inputs.runtimePermissions,
                action = actionWhenMissing(inputs.runtimePermissions, SetupAction.REQUEST_RUNTIME_PERMISSIONS)
            )
        )

        val status = when {
            inputs.healthState == BleHealthState.CONTROLLABLE ->
                UiTextKey.STATUS_CONTROLLABLE to UiTextKey.STATUS_CONTROLLABLE_DETAIL
            inputs.healthState == BleHealthState.SUSPECT ||
                inputs.healthState == BleHealthState.RECOVERING ->
                UiTextKey.STATUS_RECOVERING to UiTextKey.STATUS_RECOVERING_DETAIL
            !inputs.runtimePermissions || !inputs.notificationAccess ->
                UiTextKey.STATUS_SETUP_REQUIRED to UiTextKey.STATUS_SETUP_REQUIRED_DETAIL
            !inputs.serviceRunning ->
                UiTextKey.STATUS_ACTION_REQUIRED to UiTextKey.STATUS_SERVICE_STOPPED_DETAIL
            inputs.healthState == BleHealthState.ERROR ->
                UiTextKey.STATUS_ACTION_REQUIRED to UiTextKey.STATUS_BLE_ERROR_DETAIL
            else ->
                UiTextKey.STATUS_WAITING_IPHONE to UiTextKey.STATUS_WAITING_IPHONE_DETAIL
        }

        return PlayerAgentProductUiState(
            statusTitle = status.first,
            statusDetail = status.second,
            serviceSummary = if (inputs.serviceRunning) UiTextKey.SUMMARY_RUNNING else UiTextKey.SUMMARY_NOT_RUNNING,
            bleSummary = bleSummary(inputs.healthState),
            iPhoneSummary = when (inputs.healthState) {
                BleHealthState.CONTROLLABLE -> UiTextKey.SUMMARY_CONTROLLABLE
                BleHealthState.CONNECTED, BleHealthState.SUBSCRIBED -> UiTextKey.SUMMARY_CONNECTING
                else -> UiTextKey.SUMMARY_NOT_CONNECTED
            },
            lyricSummary = SonyPlayerUiState.lyricStatusText(inputs.lyricStatus),
            artworkSummary = artworkSummary(inputs.artworkStatus),
            setupSteps = steps,
            safeRepairActions = safeRepairActions(inputs)
        )
    }

    fun notification(healthState: BleHealthState): ForegroundNotificationUiState {
        return when (healthState) {
            BleHealthState.CONTROLLABLE -> ForegroundNotificationUiState(
                UiTextKey.NOTIFICATION_CONTROLLABLE_TITLE, UiTextKey.NOTIFICATION_CONTROLLABLE_DETAIL
            )
            BleHealthState.SUSPECT, BleHealthState.RECOVERING -> ForegroundNotificationUiState(
                UiTextKey.NOTIFICATION_RECOVERING_TITLE, UiTextKey.NOTIFICATION_RECOVERING_DETAIL
            )
            BleHealthState.SERVICE_STOPPED -> ForegroundNotificationUiState(
                UiTextKey.NOTIFICATION_STOPPED_TITLE, UiTextKey.NOTIFICATION_STOPPED_DETAIL
            )
            BleHealthState.ERROR -> ForegroundNotificationUiState(
                UiTextKey.NOTIFICATION_ACTION_TITLE, UiTextKey.NOTIFICATION_ACTION_DETAIL
            )
            else -> ForegroundNotificationUiState(
                UiTextKey.NOTIFICATION_WAITING_TITLE, UiTextKey.NOTIFICATION_WAITING_DETAIL
            )
        }
    }

    private fun safeRepairActions(inputs: PlayerAgentUiInputs): List<SafeRepairAction> {
        return buildList {
            if (!inputs.runtimePermissions) add(SafeRepairAction.REQUEST_RUNTIME_PERMISSIONS)
            if (!inputs.notificationAccess) add(SafeRepairAction.OPEN_NOTIFICATION_ACCESS)
            if (!inputs.fileAccess) add(SafeRepairAction.OPEN_FILE_ACCESS)
            if (!inputs.accessibilityEnabled) add(SafeRepairAction.OPEN_ACCESSIBILITY)
            if (!inputs.serviceRunning) add(SafeRepairAction.START_SERVICE)
            if (inputs.serviceRunning && inputs.healthState != BleHealthState.CONTROLLABLE) {
                add(SafeRepairAction.RECOVER_BLE)
            }
        }
    }

    private fun actionWhenMissing(complete: Boolean, action: SetupAction): SetupAction {
        return if (complete) SetupAction.NONE else action
    }

    private fun bleSummary(state: BleHealthState): UiTextKey {
        return when (state) {
            BleHealthState.SERVICE_STOPPED -> UiTextKey.SUMMARY_NOT_STARTED
            BleHealthState.STARTING -> UiTextKey.SUMMARY_STARTING
            BleHealthState.ADVERTISING -> UiTextKey.SUMMARY_ADVERTISING
            BleHealthState.CONNECTED -> UiTextKey.SUMMARY_CONNECTED
            BleHealthState.SUBSCRIBED -> UiTextKey.SUMMARY_SUBSCRIBED
            BleHealthState.CONTROLLABLE -> UiTextKey.SUMMARY_HEALTHY
            BleHealthState.SUSPECT -> UiTextKey.SUMMARY_SUSPECT
            BleHealthState.RECOVERING -> UiTextKey.SUMMARY_RECOVERING
            BleHealthState.ERROR -> UiTextKey.SUMMARY_ERROR
        }
    }

    private fun artworkSummary(status: String): UiTextKey {
        val normalized = status.uppercase()
        return when {
            normalized == "LOADING" -> UiTextKey.ARTWORK_LOADING
            normalized.startsWith("READY") || normalized == "PREVIEW" || normalized == "HQ" ->
                UiTextKey.ARTWORK_READY
            else -> UiTextKey.ARTWORK_UNAVAILABLE
        }
    }
}

data class ForegroundNotificationUiState(
    val title: UiTextKey,
    val detail: UiTextKey
)
