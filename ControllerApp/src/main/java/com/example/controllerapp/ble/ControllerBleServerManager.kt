package com.example.controllerapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid

class ControllerBleServerManager(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val bluetoothAdapter: BluetoothAdapter,
    private val logger: (String) -> Unit
) {

    private var gattServer: BluetoothGattServer? = null
    private val advertiser = bluetoothAdapter.bluetoothLeAdvertiser

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            logger(
                "GATT connection state changed:\n" +
                    "device=${device?.address ?: "Unknown"}\n" +
                    "status=$status\n" +
                    "newState=$newState"
            )

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> logger("Device connected")
                BluetoothProfile.STATE_DISCONNECTED -> logger("Device disconnected")
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logger("Service added callback success")
            } else {
                logger("Service added callback failed: status=$status")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            logger(
                "Characteristic read request:\n" +
                    "uuid=${characteristic?.uuid}"
            )

            try {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    "OK".toByteArray()
                )
            } catch (securityException: SecurityException) {
                logger("Characteristic read response failed: missing permission")
            } catch (exception: Exception) {
                logger("Characteristic read response failed: ${exception.message}")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val valueText = value?.toString(Charsets.UTF_8) ?: ""
            logger(
                "Characteristic write request:\n" +
                    "uuid=${characteristic?.uuid}\n" +
                    "value=$valueText"
            )

            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                } catch (securityException: SecurityException) {
                    logger("Characteristic write response failed: missing permission")
                } catch (exception: Exception) {
                    logger("Characteristic write response failed: ${exception.message}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!bluetoothAdapter.isEnabled) {
            logger("Bluetooth is disabled")
            return
        }

        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            logger("BLE advertising not supported")
            return
        }

        try {
            bluetoothAdapter.name = CONTROLLER_DEVICE_NAME
            logger("BluetoothAdapter.name=${bluetoothAdapter.name}")
        } catch (securityException: SecurityException) {
            logger("Failed to set Bluetooth name: missing permission")
            return
        } catch (exception: Exception) {
            logger("Failed to set Bluetooth name: ${exception.message}")
        }

        startGattServer()
        startAdvertising()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            logger("BLE advertising stopped")
        } catch (securityException: SecurityException) {
            logger("BLE advertising stop failed: missing permission")
        } catch (exception: Exception) {
            logger("BLE advertising stop failed: ${exception.message}")
        }

        try {
            gattServer?.close()
            logger("BLE server stopped")
        } catch (securityException: SecurityException) {
            logger("BLE server stop failed: missing permission")
        } catch (exception: Exception) {
            logger("BLE server stop failed: ${exception.message}")
        } finally {
            gattServer = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (gattServer != null) {
            logger("BLE server already started")
            return
        }

        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        } catch (securityException: SecurityException) {
            logger("BLE server start failed: missing permission")
            return
        } catch (exception: Exception) {
            logger("BLE server start failed: ${exception.message}")
            return
        }

        if (gattServer == null) {
            logger("BLE server start failed: openGattServer returned null")
            return
        }

        logger("BLE server started")

        val service = BluetoothGattService(
            ControllerUuids.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val commandCharacteristic = BluetoothGattCharacteristic(
            ControllerUuids.COMMAND_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val statusCharacteristic = BluetoothGattCharacteristic(
            ControllerUuids.STATUS_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccd = BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        statusCharacteristic.addDescriptor(cccd)

        service.addCharacteristic(commandCharacteristic)
        service.addCharacteristic(statusCharacteristic)

        try {
            val requested = gattServer?.addService(service) == true
            logger("BLE server addService requested: $requested")
        } catch (securityException: SecurityException) {
            logger("BLE server addService failed: missing permission")
        } catch (exception: Exception) {
            logger("BLE server addService failed: ${exception.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (advertiser == null) {
            logger("BLE advertiser unavailable")
            return
        }

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
            .addServiceUuid(ParcelUuid(ControllerUuids.SERVICE_UUID))
            .build()

        try {
            advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            logger("BLE advertising start requested")
        } catch (securityException: SecurityException) {
            logger("BLE advertising start failed: missing permission")
        } catch (exception: Exception) {
            logger("BLE advertising start failed: ${exception.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            logger("BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            logger("BLE advertising failed: code=$errorCode reason=${errorCodeToString(errorCode)}")
        }
    }

    private fun errorCodeToString(errorCode: Int): String {
        return when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "ADVERTISE_FAILED_DATA_TOO_LARGE"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ADVERTISE_FAILED_ALREADY_STARTED"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "ADVERTISE_FAILED_INTERNAL_ERROR"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED"
            else -> "UNKNOWN_$errorCode"
        }
    }

    companion object {
        private const val CONTROLLER_DEVICE_NAME = "MusicController"
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
