package com.example.playeragent.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

class BleAdvertiserManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val logger: (String) -> Unit
) {

    private val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var state = AdvertisingState.STOPPED
    @Volatile
    private var restartPending = false
    @Volatile
    private var alreadyStartedRetryUsed = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            val wasRestartPending = restartPending
            updateState(AdvertisingState.STARTED)
            restartPending = false
            alreadyStartedRetryUsed = false
            if (wasRestartPending) {
                logger("[BLE-ADV] restart success")
            }
            logger("BLE advertising started with scan response")
        }

        override fun onStartFailure(errorCode: Int) {
            updateState(AdvertisingState.STOPPED)
            restartPending = false
            logger(
                "[BLE-ADV] start failed code=$errorCode " +
                    "reason=${errorCodeToString(errorCode)}"
            )
            logger(
                "BLE advertising start failed: code=$errorCode " +
                    "reason=${errorCodeToString(errorCode)}"
            )
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED &&
                !alreadyStartedRetryUsed
            ) {
                alreadyStartedRetryUsed = true
                restartAdvertising(
                    reason = "ADVERTISE_FAILED_ALREADY_STARTED",
                    allowAlreadyStartedRetry = false
                )
            } else if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                logger("[BLE-ADV] already started retry exhausted")
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startAdvertising() {
        if (state == AdvertisingState.STARTED || state == AdvertisingState.STARTING) {
            logger("BLE advertising already running; skip initialization")
            return
        }
        if (advertiser == null) {
            logger("BLE advertiser unavailable")
            return
        }

        try {
            bluetoothAdapter.name = PlayerAgentUuids.ADVERTISED_NAME
            logger("Bluetooth device name set to ${PlayerAgentUuids.ADVERTISED_NAME}")
        } catch (securityException: SecurityException) {
            logger("Failed to set Bluetooth name: missing permission")
        } catch (exception: Exception) {
            logger("Failed to set Bluetooth name: ${exception.message}")
        }

        logger("BluetoothAdapter.name=${bluetoothAdapter.name}")
        logger("isMultipleAdvertisementSupported=${bluetoothAdapter.isMultipleAdvertisementSupported}")
        logger("isOffloadedFilteringSupported=${bluetoothAdapter.isOffloadedFilteringSupported}")
        logger("isOffloadedScanBatchingSupported=${bluetoothAdapter.isOffloadedScanBatchingSupported}")
        logger("BluetoothAdapter.state=${bluetoothAdapter.state}")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(PlayerAgentUuids.SERVICE_UUID))
            .build()

        try {
            updateState(AdvertisingState.STARTING)
            advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            logger("BLE advertising start requested")
        } catch (securityException: SecurityException) {
            updateState(AdvertisingState.STOPPED)
            restartPending = false
            logger("BLE advertising start failed: missing permission")
        } catch (exception: Exception) {
            updateState(AdvertisingState.STOPPED)
            restartPending = false
            logger("BLE advertising start failed: ${exception.message}")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopAdvertising() {
        handler.removeCallbacksAndMessages(null)
        restartPending = false
        if (state == AdvertisingState.STOPPED) {
            return
        }
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            logger("[BLE-ADV] stopped")
            logger("BLE advertising stopped")
        } catch (securityException: SecurityException) {
            logger("BLE advertising stop failed: missing permission")
        } catch (exception: Exception) {
            logger("BLE advertising stop failed: ${exception.message}")
        } finally {
            updateState(AdvertisingState.STOPPED)
        }
    }

    fun restartAdvertising(reason: String) {
        alreadyStartedRetryUsed = false
        restartAdvertising(reason = reason, allowAlreadyStartedRetry = true)
    }

    fun isAdvertising(): Boolean = state == AdvertisingState.STARTED

    fun getAdvertisingState(): AdvertisingState = state

    fun snapshot(): BleAdvertiserSnapshot {
        return BleAdvertiserSnapshot(
            state = state,
            restartPending = restartPending
        )
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun restartAdvertising(
        reason: String,
        allowAlreadyStartedRetry: Boolean
    ) {
        logger("[BLE-ADV] restart requested reason=$reason")
        if (!bluetoothAdapter.isEnabled) {
            logger("[BLE-ADV] restart skipped reason=bluetooth disabled")
            restartPending = false
            return
        }
        if (advertiser == null) {
            logger("[BLE-ADV] restart skipped reason=advertiser unavailable")
            restartPending = false
            return
        }

        handler.removeCallbacksAndMessages(null)
        restartPending = true
        handler.post {
            forceStopForRestart()
            handler.postDelayed(
                {
                    if (!bluetoothAdapter.isEnabled) {
                        logger("[BLE-ADV] restart skipped reason=bluetooth disabled")
                        restartPending = false
                        return@postDelayed
                    }
                    logger("[BLE-ADV] restart start")
                    updateState(AdvertisingState.STOPPED)
                    startAdvertising()
                    if (!allowAlreadyStartedRetry) {
                        restartPending = false
                    }
                },
                RESTART_DELAY_MS
            )
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun forceStopForRestart() {
        logger("[BLE-ADV] force stop before restart")
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (securityException: SecurityException) {
            logger("[BLE-ADV] force stop failed: missing permission")
        } catch (exception: Exception) {
            logger("[BLE-ADV] force stop failed: ${exception.message}")
        } finally {
            updateState(AdvertisingState.STOPPED)
        }
    }

    @Synchronized
    private fun updateState(newState: AdvertisingState) {
        val oldState = state
        if (oldState == newState) {
            return
        }
        state = newState
        logger("[BLE-ADV] state $oldState -> $newState")
    }

    private fun errorCodeToString(errorCode: Int): String {
        return when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
                "ADVERTISE_FAILED_DATA_TOO_LARGE: advertising data or scan response is too large"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: no advertising instance is available"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
                "ADVERTISE_FAILED_ALREADY_STARTED: advertising has already started"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
                "ADVERTISE_FAILED_INTERNAL_ERROR: internal Bluetooth stack error"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                "ADVERTISE_FAILED_FEATURE_UNSUPPORTED: BLE advertising feature is unsupported"
            else -> "UNKNOWN_$errorCode"
        }
    }

    enum class AdvertisingState {
        STOPPED,
        STARTING,
        STARTED
    }

    data class BleAdvertiserSnapshot(
        val state: AdvertisingState,
        val restartPending: Boolean
    )

    companion object {
        private const val RESTART_DELAY_MS = 400L
    }
}
