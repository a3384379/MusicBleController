package com.example.controllerapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings

class BleScannerManager(
    private val bluetoothAdapter: BluetoothAdapter,
    private val logger: (String) -> Unit,
    private val onDeviceFound: (BluetoothDevice) -> Unit
) {

    private val scanner
        get() = bluetoothAdapter.bluetoothLeScanner

    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            logger("BLE scan failed: ${errorCodeToString(errorCode)}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanning) {
            logger("BLE scan already running")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            logger("Bluetooth is disabled")
            return
        }

        val bluetoothLeScanner = scanner
        if (bluetoothLeScanner == null) {
            logger("BluetoothLeScanner unavailable")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner.startScan(null, settings, scanCallback)
            scanning = true
            logger("BLE scan started")
        } catch (securityException: SecurityException) {
            scanning = false
            logger("BLE scan failed: missing permission")
        } catch (exception: Exception) {
            scanning = false
            logger("BLE scan failed: ${exception.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) {
            return
        }

        try {
            scanner?.stopScan(scanCallback)
            logger("BLE scan stopped")
        } catch (securityException: SecurityException) {
            logger("BLE stop scan failed: missing permission")
        } catch (exception: Exception) {
            logger("BLE stop scan failed: ${exception.message}")
        } finally {
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
        val address = device.address ?: "Unknown"

        logger("Scan result:\nname=$name\naddress=$address")

        if (name == ControllerUuids.TARGET_DEVICE_NAME) {
            logger("Target device found")
            onDeviceFound(device)
        }
    }

    private fun errorCodeToString(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APPLICATION_REGISTRATION_FAILED"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "OUT_OF_HARDWARE_RESOURCES"
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "SCANNING_TOO_FREQUENTLY"
            else -> "UNKNOWN_$errorCode"
        }
    }
}
