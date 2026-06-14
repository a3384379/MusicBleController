package com.example.playeragent.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

class ControllerGattClientManager(
    private val context: Context,
    private val logger: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logger("[BLE-B] GATT connection state changed: status=$status newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger("[BLE-B] GATT connection failed: status=$status newState=$newState")
                close()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logger("[BLE-B] connected")
                    requestTestMtu(gatt)
                    mainHandler.postDelayed(
                        {
                            discoverServicesDelayed(gatt)
                        },
                        DISCOVER_SERVICES_DELAY_MS
                    )
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger("[BLE-B] disconnected")
                    close()
                }

                else -> logger("[BLE-B] GATT state changed: newState=$newState")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            logger("[BLE-B] MTU changed: mtu=$mtu status=$status")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            logger("[BLE-B] onServicesDiscovered status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger("[BLE-B] discoverServices failed: status=$status")
                return
            }

            logger("[BLE-B] service discovered")

            for (service in gatt.services) {
                logger("[BLE-B] service uuid=${service.uuid}")

                if (service.uuid == PlayerAgentUuids.SERVICE_UUID) {
                    logger("[BLE-B] target service found")
                }

                for (characteristic in service.characteristics) {
                    logger("[BLE-B] characteristic uuid=${characteristic.uuid}")

                    if (characteristic.uuid == PlayerAgentUuids.COMMAND_CHARACTERISTIC_UUID) {
                        commandCharacteristic = characteristic
                        logger("[BLE-B] command characteristic found")
                    }

                    if (characteristic.uuid == PlayerAgentUuids.STATUS_CHARACTERISTIC_UUID) {
                        statusCharacteristic = characteristic
                        logger("[BLE-B] status characteristic found")
                    }
                }
            }

            subscribeToCommandNotifications(gatt)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: byteArrayOf()
            handleCommandNotification(gatt, characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCommandNotification(gatt, characteristic, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == PlayerAgentUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logger("[BLE-B] subscribed command notify")
                } else {
                    logger("[BLE-B] command notify subscription failed: status=$status")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == PlayerAgentUuids.STATUS_CHARACTERISTIC_UUID) {
                logger("[BLE-B] status write completed: status=$status")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        try {
            bluetoothGatt = device.connectGatt(context, false, callback)
            logger("[BLE-B] connectGatt requested")
        } catch (securityException: SecurityException) {
            logger("[BLE-B] connectGatt failed: missing permission")
        } catch (exception: Exception) {
            logger("[BLE-B] connectGatt failed: ${exception.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        mainHandler.removeCallbacksAndMessages(null)

        try {
            bluetoothGatt?.close()
        } catch (exception: Exception) {
            logger("[BLE-B] GATT close failed: ${exception.message}")
        } finally {
            bluetoothGatt = null
            commandCharacteristic = null
            statusCharacteristic = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServicesDelayed(gatt: BluetoothGatt) {
        logger("[BLE-B] discoverServices delayed start")

        try {
            val result = gatt.discoverServices()
            logger("[BLE-B] discoverServices requested: result=$result")
        } catch (securityException: SecurityException) {
            logger("[BLE-B] discoverServices failed: missing permission")
        } catch (exception: Exception) {
            logger("[BLE-B] discoverServices failed: ${exception.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestTestMtu(gatt: BluetoothGatt) {
        try {
            val requested = gatt.requestMtu(TEST_MTU)
            logger("[BLE-B] requestMtu($TEST_MTU) requested=$requested")
        } catch (securityException: SecurityException) {
            logger("[BLE-B] requestMtu failed: missing permission")
        } catch (exception: Exception) {
            logger("[BLE-B] requestMtu failed: ${exception.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToCommandNotifications(gatt: BluetoothGatt) {
        val characteristic = commandCharacteristic
        if (characteristic == null) {
            logger("[BLE-B] cannot subscribe: command characteristic missing")
            return
        }

        try {
            val notificationEnabled = gatt.setCharacteristicNotification(characteristic, true)
            logger("[BLE-B] setCharacteristicNotification result=$notificationEnabled")
            if (!notificationEnabled) {
                return
            }

            val descriptor = characteristic.getDescriptor(
                PlayerAgentUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID
            )
            if (descriptor == null) {
                logger("[BLE-B] cannot subscribe: CCCD missing")
                return
            }

            val writeRequested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            logger("[BLE-B] CCCD write requested=$writeRequested")
        } catch (securityException: SecurityException) {
            logger("[BLE-B] subscribe failed: missing permission")
        } catch (exception: Exception) {
            logger("[BLE-B] subscribe failed: ${exception.message}")
        }
    }

    private fun handleCommandNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (characteristic.uuid != PlayerAgentUuids.COMMAND_CHARACTERISTIC_UUID) {
            return
        }

        val text = value.toString(Charsets.UTF_8)
        logger("[BLE-B] notify received: $text")
        val command = try {
            JSONObject(text).optString("cmd")
        } catch (exception: Exception) {
            logger("[BLE-B] notify parse failed: ${exception.message}")
            ""
        }

        if (command == "PLAY_PAUSE") {
            writeStatusAck(gatt)
        } else {
            logger("[BLE-B] test command ignored: $command")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeStatusAck(gatt: BluetoothGatt) {
        val characteristic = statusCharacteristic
        if (characteristic == null) {
            logger("[BLE-B] status write failed: characteristic missing")
            return
        }

        val value = PlayerAgentUuids.PLAY_PAUSE_ACK.toByteArray(Charsets.UTF_8)
        try {
            val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            logger(
                "[BLE-B] status written requested=$requested " +
                    "value=${PlayerAgentUuids.PLAY_PAUSE_ACK}"
            )
        } catch (securityException: SecurityException) {
            logger("[BLE-B] status write failed: missing permission")
        } catch (exception: Exception) {
            logger("[BLE-B] status write failed: ${exception.message}")
        }
    }

    companion object {
        private const val DISCOVER_SERVICES_DELAY_MS = 800L
        private const val TEST_MTU = 185
    }
}
