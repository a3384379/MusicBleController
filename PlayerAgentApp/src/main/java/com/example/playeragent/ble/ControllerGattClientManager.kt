package com.example.playeragent.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper

class ControllerGattClientManager(
    private val logger: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logger("GATT connection state changed: status=$status newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger("GATT connection failed: status=$status newState=$newState")
                close()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logger("GATT connected")
                    mainHandler.postDelayed(
                        {
                            discoverServicesDelayed(gatt)
                        },
                        DISCOVER_SERVICES_DELAY_MS
                    )
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger("GATT disconnected")
                    close()
                }

                else -> {
                    logger("GATT state changed: newState=$newState")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            logger("onServicesDiscovered: status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger("discoverServices failed: status=$status")
                return
            }

            logger("discoverServices success")

            for (service in gatt.services) {
                logger("service uuid=${service.uuid}")

                if (service.uuid == PlayerAgentUuids.SERVICE_UUID) {
                    logger("Target service found")
                }

                for (characteristic in service.characteristics) {
                    logger("characteristic uuid=${characteristic.uuid}")

                    if (characteristic.uuid == PlayerAgentUuids.COMMAND_CHARACTERISTIC_UUID) {
                        logger("Command characteristic found")
                    }

                    if (characteristic.uuid == PlayerAgentUuids.STATUS_CHARACTERISTIC_UUID) {
                        logger("Status characteristic found")
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val context = resolveApplicationContext()
        if (context == null) {
            logger("connectGatt failed: application context unavailable")
            return
        }

        try {
            bluetoothGatt = device.connectGatt(context, false, callback)
            logger("connectGatt requested")
        } catch (securityException: SecurityException) {
            logger("connectGatt failed: missing permission")
        } catch (exception: Exception) {
            logger("connectGatt failed: ${exception.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        mainHandler.removeCallbacksAndMessages(null)

        try {
            bluetoothGatt?.close()
        } catch (exception: Exception) {
            logger("GATT close failed: ${exception.message}")
        } finally {
            bluetoothGatt = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServicesDelayed(gatt: BluetoothGatt) {
        logger("discoverServices delayed start")

        try {
            val result = gatt.discoverServices()
            logger("discoverServices requested:\nresult=$result")
        } catch (securityException: SecurityException) {
            logger("discoverServices failed: missing permission")
        } catch (exception: Exception) {
            logger("discoverServices failed: ${exception.message}")
        }
    }

    private fun resolveApplicationContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            currentApplicationMethod.invoke(null) as? Application
        } catch (exception: Exception) {
            null
        }
    }

    companion object {
        private const val DISCOVER_SERVICES_DELAY_MS = 800L
    }
}
