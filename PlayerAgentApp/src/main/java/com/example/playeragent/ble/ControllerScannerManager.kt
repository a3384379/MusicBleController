package com.example.playeragent.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper

class ControllerScannerManager(
    private val bluetoothAdapter: BluetoothAdapter,
    private val logger: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var retryIndex = 0
    private var gattClientManager: ControllerGattClientManager? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            logger("onBatchScanResults: count=${results.size}")
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            logger("Controller scan failed: errorCode=$errorCode reason=${errorCodeToString(errorCode)}")
        }
    }

    fun startScan() {
        if (scanning) {
            logger("Controller scan already running")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            logger("Bluetooth disabled")
            return
        }

        retryIndex = 0
        mainHandler.postDelayed(
            {
                createScannerAndStartScan()
            },
            INITIAL_SCANNER_DELAY_MS
        )
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        mainHandler.removeCallbacksAndMessages(null)

        if (!scanning) {
            return
        }

        try {
            scanner?.stopScan(scanCallback)
            logger("Controller scan stopped")
        } catch (securityException: SecurityException) {
            logger("Controller scan stop failed: missing permission")
        } catch (exception: Exception) {
            logger("Controller scan stop failed: ${exception.message}")
        } finally {
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createScannerAndStartScan() {
        if (!bluetoothAdapter.isEnabled) {
            logger("Bluetooth disabled")
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner

        if (scanner == null) {
            if (retryIndex < MAX_RETRY_COUNT) {
                retryIndex += 1
                logger("Retry create scanner: index=$retryIndex")
                mainHandler.postDelayed(
                    {
                        createScannerAndStartScan()
                    },
                    RETRY_SCANNER_DELAY_MS
                )
            } else {
                logger("bluetoothLeScanner still null after retries")
            }
            return
        }

        logger("bluetoothLeScanner created")
        startScanWithCreatedScanner()
    }

    @SuppressLint("MissingPermission")
    private fun startScanWithCreatedScanner() {
        val createdScanner = scanner
        if (createdScanner == null) {
            logger("bluetoothLeScanner still null after retries")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            createdScanner.startScan(null, settings, scanCallback)
            scanning = true
            logger("Controller scan started")
        } catch (securityException: SecurityException) {
            scanning = false
            logger("Controller scan failed: missing permission")
        } catch (exception: Exception) {
            scanning = false
            logger("Controller scan failed: ${exception.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
        val address = device.address ?: "Unknown"

        logger("Scan result:\nname=$name\naddress=$address\nrssi=${result.rssi}")

        if (device.name?.contains(CONTROLLER_DEVICE_NAME) == true) {
            logger("Controller matched by device name")
            stopScan()

            gattClientManager?.close()
            gattClientManager = ControllerGattClientManager(logger)
            gattClientManager?.connect(device)
        }
    }

    private fun errorCodeToString(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY"
            else -> "UNKNOWN_$errorCode"
        }
    }

    companion object {
        private const val CONTROLLER_DEVICE_NAME = "MusicController"
        private const val INITIAL_SCANNER_DELAY_MS = 1500L
        private const val RETRY_SCANNER_DELAY_MS = 2000L
        private const val MAX_RETRY_COUNT = 3
    }
}
