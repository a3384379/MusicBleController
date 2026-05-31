package com.example.controllerapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context

class BleGattClientManager(
    private val context: Context,
    private val logger: (String) -> Unit,
    private val onConnectionStateChanged: (String) -> Unit
) {

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger("GATT connection error: status=$status newState=$newState")
                onConnectionStateChanged("Connection error: $status")
                close()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logger("GATT connected")
                    onConnectionStateChanged("Connected")
                    try {
                        val requested = gatt.discoverServices()
                        logger("GATT discoverServices requested: $requested")
                    } catch (securityException: SecurityException) {
                        logger("GATT discoverServices failed: missing permission")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger("GATT disconnected")
                    onConnectionStateChanged("Disconnected")
                    close()
                }

                else -> {
                    logger("GATT state changed: newState=$newState")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger("GATT service discovery failed: status=$status")
                return
            }

            logger("GATT service discovery success")
            val service = gatt.getService(ControllerUuids.SERVICE_UUID)
            if (service == null) {
                logger("Target service not found")
                return
            }

            logger("Target service found: ${service.uuid}")
            logCharacteristicDiscovery(service)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close()
        val name = device.name ?: "Unknown"
        val address = device.address ?: "Unknown"
        logger("Connecting to device: name=$name address=$address")
        onConnectionStateChanged("Connecting")

        try {
            bluetoothGatt = device.connectGatt(context, false, callback)
        } catch (securityException: SecurityException) {
            logger("GATT connect failed: missing permission")
            onConnectionStateChanged("Connect failed")
        } catch (exception: Exception) {
            logger("GATT connect failed: ${exception.message}")
            onConnectionStateChanged("Connect failed")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            logger("GATT disconnect requested")
        } catch (securityException: SecurityException) {
            logger("GATT disconnect failed: missing permission")
        } catch (exception: Exception) {
            logger("GATT disconnect failed: ${exception.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        try {
            bluetoothGatt?.close()
        } catch (exception: Exception) {
            logger("GATT close failed: ${exception.message}")
        } finally {
            bluetoothGatt = null
            commandCharacteristic = null
            statusCharacteristic = null
        }
    }

    private fun logCharacteristicDiscovery(service: BluetoothGattService) {
        commandCharacteristic = service.getCharacteristic(ControllerUuids.COMMAND_CHARACTERISTIC_UUID)
        statusCharacteristic = service.getCharacteristic(ControllerUuids.STATUS_CHARACTERISTIC_UUID)

        if (commandCharacteristic != null) {
            logger("Command characteristic found: ${ControllerUuids.COMMAND_CHARACTERISTIC_UUID}")
        } else {
            logger("Command characteristic not found")
        }

        if (statusCharacteristic != null) {
            logger("Status characteristic found: ${ControllerUuids.STATUS_CHARACTERISTIC_UUID}")
        } else {
            logger("Status characteristic not found")
        }
    }
}
