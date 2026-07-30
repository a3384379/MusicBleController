package com.example.controllerapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import com.example.controllerapp.model.ConnectionPhase
import java.util.UUID

interface BleGattTransportListener {
    fun onPhaseChanged(
        phase: ConnectionPhase,
        deviceName: String = "Sony PlayerAgent",
        address: String = "",
        reason: String = ""
    )

    fun onReady(deviceName: String, address: String, mtu: Int)
    fun onNotification(value: ByteArray)
    fun onNotifyActivity()
    fun onWriteResult(success: Boolean, payload: ByteArray)
}

@SuppressLint("MissingPermission")
class BleGattTransport(
    context: Context,
    private val logger: (String) -> Unit,
    private val listener: BleGattTransportListener
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter
        get() = bluetoothManager?.adapter
    private val thread = HandlerThread("Controller-BLE").apply { start() }
    private val handler = Handler(thread.looper)
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var broadScanFallback = false
    private var scanToken = 0L
    private var closing = false
    @Volatile
    private var ready = false
    private var mtu = DEFAULT_MTU
    private var connectionToken = 0L
    private val writes = BoundedGattWriteQueue(MAX_QUEUED_WRITES)
    private var activeWrite: ByteArray? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                val name = runCatching {
                    result.scanRecord?.deviceName ?: result.device.name
                }.getOrNull().orEmpty()
                val hasService = result.scanRecord?.serviceUuids
                    ?.contains(ParcelUuid(ControllerUuids.SERVICE_UUID)) == true
                if (hasService || name == ControllerUuids.TARGET_DEVICE_NAME) {
                    logger("[BLE] target discovered name=$name")
                    stopScanOnThread()
                    connectOnThread(result.device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                logger("[BLE] scan failed code=$errorCode")
                listener.onPhaseChanged(
                    ConnectionPhase.DISCONNECTED,
                    reason = "scan failed $errorCode"
                )
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            callbackGatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            handler.post {
                if (callbackGatt !== gatt) {
                    runCatching { callbackGatt.close() }
                    return@post
                }
                if (status != BluetoothGatt.GATT_SUCCESS ||
                    newState == BluetoothProfile.STATE_DISCONNECTED
                ) {
                    val reason = "gatt disconnected status=$status state=$newState"
                    logger("[BLE] $reason")
                    closeGattOnThread()
                    if (!closing) {
                        listener.onPhaseChanged(
                            ConnectionPhase.DISCONNECTED,
                            reason = reason
                        )
                    }
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val name = deviceName(callbackGatt.device)
                    logger("[BLE] connected name=$name")
                    listener.onPhaseChanged(
                        ConnectionPhase.DISCOVERING,
                        name,
                        callbackGatt.device.address.orEmpty()
                    )
                    val requested = runCatching {
                        callbackGatt.requestMtu(REQUESTED_MTU)
                    }.getOrDefault(false)
                    if (!requested) {
                        discoverServicesOnThread(callbackGatt)
                    } else {
                        handler.postDelayed({
                            if (commandCharacteristic == null && callbackGatt === gatt) {
                                discoverServicesOnThread(callbackGatt)
                            }
                        }, MTU_TIMEOUT_MS)
                    }
                }
            }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, value: Int, status: Int) {
            handler.post {
                if (callbackGatt !== gatt) return@post
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) {
                    value.coerceAtLeast(DEFAULT_MTU)
                } else {
                    DEFAULT_MTU
                }
                logger("[BLE] mtu=$mtu status=$status")
                discoverServicesOnThread(callbackGatt)
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            handler.post {
                if (callbackGatt !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnectionOnThread("service discovery failed $status")
                    return@post
                }
                val service = callbackGatt.getService(ControllerUuids.SERVICE_UUID)
                if (service == null) {
                    failConnectionOnThread("Sony service missing")
                    return@post
                }
                configureServiceOnThread(callbackGatt, service)
            }
        }

        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicValue(callbackGatt, characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicValue(callbackGatt, characteristic, value)
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            handler.post {
                if (callbackGatt !== gatt ||
                    descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID
                ) {
                    return@post
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnectionOnThread("notify subscription failed $status")
                    return@post
                }
                val device = callbackGatt.device
                logger("[BLE] notification subscribed")
                ready = true
                listener.onReady(deviceName(device), device.address.orEmpty(), mtu)
                drainWritesOnThread()
            }
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handler.post {
                if (callbackGatt !== gatt ||
                    characteristic.uuid != ControllerUuids.COMMAND_CHARACTERISTIC_UUID
                ) {
                    return@post
                }
                val payload = activeWrite ?: return@post
                activeWrite = null
                val success = status == BluetoothGatt.GATT_SUCCESS
                listener.onWriteResult(success, payload)
                if (!success) {
                    logger("[BLE] command write failed status=$status")
                }
                drainWritesOnThread()
            }
        }
    }

    fun start(savedAddress: String = "", forceScan: Boolean = false) {
        handler.post {
            closing = false
            if (isReady()) return@post
            if (!forceScan && savedAddress.isNotBlank()) {
                val device = runCatching { adapter?.getRemoteDevice(savedAddress) }.getOrNull()
                if (device != null) {
                    logger("[BLE] reconnecting saved Sony address")
                    connectOnThread(device)
                    return@post
                }
            }
            if (!forceScan) {
                val bondedSony = runCatching {
                    adapter?.bondedDevices?.firstOrNull { device ->
                        deviceName(device) == ControllerUuids.TARGET_DEVICE_NAME
                    }
                }.getOrNull()
                if (bondedSony != null) {
                    logger("[BLE] using bonded Sony address")
                    connectOnThread(bondedSony)
                    return@post
                }
            }
            startScanOnThread()
        }
    }

    fun reconnect(reason: String) {
        handler.post {
            logger("[BLE] reconnect reason=$reason")
            closing = false
            closeGattOnThread()
            startScanOnThread(reconnecting = true)
        }
    }

    fun write(value: ByteArray): Boolean {
        if (value.isEmpty() || !ready) return false
        handler.post {
            if (!ready) {
                listener.onWriteResult(false, value)
                return@post
            }
            val dropped = writes.offer(value)
            if (dropped != null) {
                logger("[BLE] command queue full dropped=${String(dropped).take(80)}")
            }
            drainWritesOnThread()
        }
        return true
    }

    fun disconnect() {
        handler.post {
            closing = true
            stopScanOnThread()
            closeGattOnThread()
            writes.clear()
            activeWrite = null
            listener.onPhaseChanged(ConnectionPhase.DISCONNECTED, reason = "manual stop")
        }
    }

    fun close() {
        handler.post {
            closing = true
            stopScanOnThread()
            closeGattOnThread()
            writes.clear()
            activeWrite = null
            thread.quitSafely()
        }
    }

    private fun handleCharacteristicValue(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?
    ) {
        if (value == null || value.isEmpty()) return
        handler.post {
            if (callbackGatt !== gatt ||
                characteristic.uuid != ControllerUuids.STATUS_CHARACTERISTIC_UUID
            ) {
                return@post
            }
            listener.onNotifyActivity()
            listener.onNotification(value.copyOf())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanOnThread(
        reconnecting: Boolean = false,
        useBroadFallback: Boolean = false
    ) {
        if (scanning) return
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            listener.onPhaseChanged(
                ConnectionPhase.DISCONNECTED,
                reason = "Bluetooth unavailable"
            )
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onPhaseChanged(
                ConnectionPhase.DISCONNECTED,
                reason = "BLE scanner unavailable"
            )
            return
        }
        /*
         * A few Android stacks evaluate hardware filters before merging Sony's
         * primary advertisement (device name) and scan response (service UUID).
         * Keep the efficient filtered path first, then use a short unfiltered
         * compatibility scan while still validating every result in callback.
         */
        val filters = if (useBroadFallback) {
            null
        } else {
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(ControllerUuids.SERVICE_UUID))
                    .build(),
                ScanFilter.Builder()
                    .setDeviceName(ControllerUuids.TARGET_DEVICE_NAME)
                    .build()
            )
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            broadScanFallback = useBroadFallback
            val token = ++scanToken
            listener.onPhaseChanged(
                if (reconnecting) ConnectionPhase.RECONNECTING else ConnectionPhase.SCANNING
            )
            logger(
                "[BLE] scan started reconnecting=$reconnecting " +
                    "mode=${if (useBroadFallback) "compat" else "filtered"}"
            )
            handler.postDelayed({
                if (scanning && token == scanToken && !broadScanFallback) {
                    logger("[BLE] filtered scan yielded no Sony; enabling compat scan")
                    stopScanOnThread()
                    startScanOnThread(
                        reconnecting = reconnecting,
                        useBroadFallback = true
                    )
                }
            }, FILTERED_SCAN_TIMEOUT_MS)
            handler.postDelayed({
                if (scanning && token == scanToken) {
                    stopScanOnThread()
                    listener.onPhaseChanged(
                        ConnectionPhase.DISCONNECTED,
                        reason = "scan timeout"
                    )
                }
            }, if (useBroadFallback) COMPAT_SCAN_TIMEOUT_MS else TOTAL_SCAN_GUARD_MS)
        }.onFailure {
            listener.onPhaseChanged(
                ConnectionPhase.DISCONNECTED,
                reason = "scan exception ${it.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanOnThread() {
        if (!scanning) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        broadScanFallback = false
        scanToken += 1L
    }

    @SuppressLint("MissingPermission")
    private fun connectOnThread(device: BluetoothDevice) {
        stopScanOnThread()
        closeGattOnThread()
        commandCharacteristic = null
        statusCharacteristic = null
        ready = false
        mtu = DEFAULT_MTU
        val name = deviceName(device)
        listener.onPhaseChanged(
            ConnectionPhase.CONNECTING,
            name,
            device.address.orEmpty()
        )
        logger("[BLE] connect name=$name address=${device.address}")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, callback)
        }
        val expectedGatt = gatt
        val token = ++connectionToken
        handler.postDelayed({
            if (token == connectionToken && expectedGatt === gatt && !ready) {
                failConnectionOnThread("connection setup timeout")
            }
        }, CONNECTION_SETUP_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun discoverServicesOnThread(callbackGatt: BluetoothGatt) {
        if (callbackGatt !== gatt || commandCharacteristic != null) return
        runCatching { callbackGatt.discoverServices() }
            .onFailure { failConnectionOnThread("discover exception ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun configureServiceOnThread(
        callbackGatt: BluetoothGatt,
        service: BluetoothGattService
    ) {
        commandCharacteristic = service.getCharacteristic(
            ControllerUuids.COMMAND_CHARACTERISTIC_UUID
        )
        statusCharacteristic = service.getCharacteristic(
            ControllerUuids.STATUS_CHARACTERISTIC_UUID
        )
        val status = statusCharacteristic
        if (commandCharacteristic == null || status == null) {
            failConnectionOnThread("required characteristic missing")
            return
        }
        listener.onPhaseChanged(
            ConnectionPhase.SUBSCRIBING,
            deviceName(callbackGatt.device),
            callbackGatt.device.address.orEmpty()
        )
        val enabled = runCatching {
            callbackGatt.setCharacteristicNotification(status, true)
        }.getOrDefault(false)
        val cccd = status.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (!enabled || cccd == null) {
            failConnectionOnThread("cannot enable notifications")
            return
        }
        val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callbackGatt.writeDescriptor(
                cccd,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            callbackGatt.writeDescriptor(cccd)
        }
        if (!requested) {
            failConnectionOnThread("descriptor write rejected")
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainWritesOnThread() {
        if (activeWrite != null) return
        val callbackGatt = gatt ?: return
        val characteristic = commandCharacteristic ?: return
        val value = writes.poll() ?: return
        activeWrite = value
        val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callbackGatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            callbackGatt.writeCharacteristic(characteristic)
        }
        if (!requested) {
            activeWrite = null
            listener.onWriteResult(false, value)
            handler.postDelayed(::drainWritesOnThread, WRITE_RETRY_DELAY_MS)
        } else {
            handler.postDelayed({
                if (activeWrite === value) {
                    activeWrite = null
                    logger("[BLE] command write callback timeout")
                    listener.onWriteResult(false, value)
                    failConnectionOnThread("command write callback timeout")
                }
            }, WRITE_CALLBACK_TIMEOUT_MS)
        }
    }

    private fun isReady(): Boolean =
        gatt != null && commandCharacteristic != null && statusCharacteristic != null

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: ControllerUuids.TARGET_DEVICE_NAME

    @SuppressLint("MissingPermission")
    private fun failConnectionOnThread(reason: String) {
        logger("[BLE] connection failed reason=$reason")
        closeGattOnThread()
        listener.onPhaseChanged(ConnectionPhase.DISCONNECTED, reason = reason)
    }

    @SuppressLint("MissingPermission")
    private fun closeGattOnThread() {
        val current = gatt
        gatt = null
        commandCharacteristic = null
        statusCharacteristic = null
        ready = false
        connectionToken += 1L
        activeWrite = null
        writes.clear()
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
    }

    companion object {
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 247
        private const val MTU_TIMEOUT_MS = 1_500L
        private const val FILTERED_SCAN_TIMEOUT_MS = 6_000L
        private const val COMPAT_SCAN_TIMEOUT_MS = 10_000L
        private const val TOTAL_SCAN_GUARD_MS = 18_000L
        private const val CONNECTION_SETUP_TIMEOUT_MS = 12_000L
        private const val WRITE_CALLBACK_TIMEOUT_MS = 3_000L
        private const val WRITE_RETRY_DELAY_MS = 100L
        private const val MAX_QUEUED_WRITES = 128
    }
}
