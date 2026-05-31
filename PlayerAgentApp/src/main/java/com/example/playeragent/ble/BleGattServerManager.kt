package com.example.playeragent.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context

class BleGattServerManager(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val logger: (String) -> Unit
) {

    private var gattServer: BluetoothGattServer? = null

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logger("Service added success")
            } else {
                logger("Service added failed: status=$status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = device?.address ?: "unknown"
            logger("GATT connection state changed: device=$address status=$status newState=$newState")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            gattServer = bluetoothManager.openGattServer(context, callback)
        } catch (securityException: SecurityException) {
            logger("GATT server start failed: missing permission")
            return
        } catch (exception: Exception) {
            logger("GATT server start failed: ${exception.message}")
            return
        }

        if (gattServer == null) {
            logger("GATT server start failed: openGattServer returned null")
            return
        }

        logger("GATT server started")

        val service = BluetoothGattService(
            PlayerAgentUuids.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val commandCharacteristic = BluetoothGattCharacteristic(
            PlayerAgentUuids.COMMAND_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val statusCharacteristic = BluetoothGattCharacteristic(
            PlayerAgentUuids.STATUS_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccd = BluetoothGattDescriptor(
            PlayerAgentUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        statusCharacteristic.addDescriptor(cccd)

        service.addCharacteristic(commandCharacteristic)
        service.addCharacteristic(statusCharacteristic)

        try {
            val addRequested = gattServer?.addService(service) == true
            logger("GATT addService requested: $addRequested")
        } catch (securityException: SecurityException) {
            logger("GATT addService failed: missing permission")
        } catch (exception: Exception) {
            logger("GATT addService failed: ${exception.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        try {
            gattServer?.close()
            logger("GATT server closed")
        } catch (securityException: SecurityException) {
            logger("GATT server close failed: missing permission")
        } catch (exception: Exception) {
            logger("GATT server close failed: ${exception.message}")
        } finally {
            gattServer = null
        }
    }
}
