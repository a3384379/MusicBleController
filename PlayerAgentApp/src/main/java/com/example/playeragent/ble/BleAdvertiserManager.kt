package com.example.playeragent.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid

class BleAdvertiserManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val logger: (String) -> Unit
) {

    private val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
    @Volatile
    private var advertisingStartedOrRequested = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertisingStartedOrRequested = true
            logger("BLE advertising started with scan response")
        }

        override fun onStartFailure(errorCode: Int) {
            advertisingStartedOrRequested = false
            logger("BLE advertising start failed: code=$errorCode reason=${errorCodeToString(errorCode)}")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startAdvertising() {
        if (advertisingStartedOrRequested) {
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
            advertisingStartedOrRequested = true
            advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            logger("BLE advertising start requested")
        } catch (securityException: SecurityException) {
            advertisingStartedOrRequested = false
            logger("BLE advertising start failed: missing permission")
        } catch (exception: Exception) {
            advertisingStartedOrRequested = false
            logger("BLE advertising start failed: ${exception.message}")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopAdvertising() {
        if (!advertisingStartedOrRequested) {
            return
        }
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            logger("BLE advertising stopped")
        } catch (securityException: SecurityException) {
            logger("BLE advertising stop failed: missing permission")
        } catch (exception: Exception) {
            logger("BLE advertising stop failed: ${exception.message}")
        } finally {
            advertisingStartedOrRequested = false
        }
    }

    fun isAdvertising(): Boolean = advertisingStartedOrRequested

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
}
