package com.example.playeragent.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.playeragent.MainActivity
import com.example.playeragent.ble.BleAdvertiserManager
import com.example.playeragent.ble.BleGattServerManager
import com.example.playeragent.logging.LogConfig
import com.example.playeragent.logging.LogBuffer

class PlayerAgentForegroundService : Service() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiserManager: BleAdvertiserManager? = null
    private var gattServerManager: BleGattServerManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("PlayerAgent is running"))
        log("Foreground service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initializeBluetooth()
        return START_STICKY
    }

    override fun onDestroy() {
        advertiserManager?.stopAdvertising()
        advertiserManager = null
        gattServerManager?.close()
        gattServerManager = null
        log("Foreground service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter

        if (adapter == null) {
            log("BluetoothAdapter not available")
            return
        }

        bluetoothAdapter = adapter
        log("Bluetooth initialized")

        val hasBle = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        log("BLE supported: $hasBle")
        if (!hasBle) {
            return
        }

        if (!hasBluetoothRuntimePermissions()) {
            log("Missing Bluetooth runtime permission")
            return
        }

        if (!adapter.isEnabled) {
            log("Bluetooth is disabled")
            return
        }

        val advertisingSupported = adapter.isMultipleAdvertisementSupported
        log("BLE advertising supported: $advertisingSupported")

        val existingGattServerManager = gattServerManager
        if (existingGattServerManager == null) {
            val manager = BleGattServerManager(
                context = this,
                bluetoothManager = bluetoothManager,
                logger = ::log,
                transientLogger = ::logLocalOnly,
                verboseLogger = ::logVerbose
            )
            if (manager.start()) {
                gattServerManager = manager
            }
        } else if (!existingGattServerManager.isStarted()) {
            existingGattServerManager.start()
        } else {
            logVerbose("GATT server already running; initialization skipped")
        }

        if (!advertisingSupported) {
            log("BLE advertising not supported")
            return
        }

        val existingAdvertiserManager = advertiserManager
        if (existingAdvertiserManager == null) {
            advertiserManager = BleAdvertiserManager(
                context = this,
                bluetoothAdapter = adapter,
                logger = ::log
            ).also {
                it.startAdvertising()
            }
        } else if (!existingAdvertiserManager.isAdvertising()) {
            existingAdvertiserManager.startAdvertising()
        } else {
            logVerbose("BLE advertising already running; initialization skipped")
        }
    }

    private fun hasBluetoothRuntimePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PlayerAgent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("PlayerAgent")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun log(message: String) {
        val fullMessage = "[PlayerAgent] $message"
        LogBuffer.append(fullMessage)
        publishLog(fullMessage)
    }

    private fun logVerbose(message: String) {
        if (LogConfig.DEBUG_VERBOSE_LOG) {
            log(message)
        }
    }

    private fun logLocalOnly(message: String) {
        publishLog("[PlayerAgent] $message")
    }

    private fun publishLog(fullMessage: String) {
        Log.i(TAG, fullMessage)
        sendBroadcast(
            Intent(ACTION_LOG)
                .setPackage(packageName)
                .putExtra(EXTRA_LOG_MESSAGE, fullMessage)
        )
    }

    companion object {
        const val ACTION_LOG = "com.example.playeragent.ACTION_LOG"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"

        private const val TAG = "PlayerAgent"
        private const val CHANNEL_ID = "player_agent_service"
        private const val NOTIFICATION_ID = 10001
    }
}
