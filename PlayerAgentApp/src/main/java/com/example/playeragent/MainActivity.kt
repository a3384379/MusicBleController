package com.example.playeragent

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.playeragent.ble.ControllerScannerManager
import com.example.playeragent.classicbluetooth.RfcommClientManager
import com.example.playeragent.media.LyricManager
import com.example.playeragent.media.PlaybackStateReader
import com.example.playeragent.service.PlayerAgentForegroundService

class MainActivity : Activity() {

    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var controllerScannerManager: ControllerScannerManager? = null
    private var rfcommClientManager: RfcommClientManager? = null

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(PlayerAgentForegroundService.EXTRA_LOG_MESSAGE)
                ?: return
            appendLog(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()
        initializeBluetooth()
        requestRequiredPermissions()
        appendLog("[PlayerAgent] UI ready")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(PlayerAgentForegroundService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(logReceiver)
    }

    override fun onDestroy() {
        controllerScannerManager?.stopScan()
        rfcommClientManager?.close()
        super.onDestroy()
    }

    private fun setupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val startButton = Button(this).apply {
            text = "Start PlayerAgent Service"
            setOnClickListener {
                if (!ensureRequiredPermissions()) {
                    appendLog("[PlayerAgent] Please grant permissions, then start service again")
                    return@setOnClickListener
                }
                val intent = Intent(this@MainActivity, PlayerAgentForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                appendLog("[PlayerAgent] Start service requested")
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop PlayerAgent Service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, PlayerAgentForegroundService::class.java))
                appendLog("[PlayerAgent] Stop service requested")
            }
        }

        val scanControllerButton = Button(this).apply {
            text = "Scan Controller"
            setOnClickListener {
                startControllerScan()
            }
        }

        val connectRfcommButton = Button(this).apply {
            text = "Connect RFCOMM Server"
            setOnClickListener {
                connectRfcommServer()
            }
        }

        val scanLrcButton = Button(this).apply {
            text = "SCAN LRC FILES"
            setOnClickListener {
                scanLrcFiles()
            }
        }

        logTextView = TextView(this).apply {
            textSize = 14f
            text = ""
        }

        logScrollView = ScrollView(this).apply {
            addView(logTextView)
        }

        root.addView(startButton)
        root.addView(stopButton)
        root.addView(scanControllerButton)
        root.addView(connectRfcommButton)
        root.addView(scanLrcButton)

        val scrollViewLayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0
        ).apply {
            weight = 1f
        }
        root.addView(logScrollView, scrollViewLayoutParams)
        setContentView(root)
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            appendLog("[PlayerAgent] BluetoothAdapter unavailable")
            return
        }

        appendLog("[PlayerAgent] Bluetooth initialized for controller scan")
    }

    private fun startControllerScan() {
        if (!ensureRequiredPermissions()) {
            appendLog("[PlayerAgent] Please grant permissions, then scan again")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            appendLog("[PlayerAgent] Cannot scan: BluetoothAdapter unavailable")
            return
        }

        controllerScannerManager?.stopScan()
        controllerScannerManager = ControllerScannerManager(
            bluetoothAdapter = adapter,
            logger = ::appendPlayerAgentLog
        )
        controllerScannerManager?.startScan()
    }

    private fun connectRfcommServer() {
        if (!ensureRequiredPermissions()) {
            appendLog("[PlayerAgent] Please grant permissions, then connect again")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            appendLog("[PlayerAgent] Cannot connect RFCOMM: BluetoothAdapter unavailable")
            return
        }

        if (!adapter.isEnabled) {
            appendLog("[PlayerAgent] Cannot connect RFCOMM: Bluetooth disabled")
            return
        }

        rfcommClientManager?.close()
        rfcommClientManager = RfcommClientManager(
            context = this,
            bluetoothAdapter = adapter,
            logger = ::appendPlayerAgentLog
        )
        rfcommClientManager?.connectToServer()
    }

    private fun scanLrcFiles() {
        if (!ensureRequiredPermissions()) {
            appendLog("[PlayerAgent] Please grant permissions, then scan LRC files again")
            return
        }

        Thread {
            try {
                appendThreadSafeLog("[LyricScan] requested")
                val playbackStateReader = PlaybackStateReader(
                    context = this,
                    logger = ::appendThreadSafeLog
                )
                val playbackState = playbackStateReader.readPlaybackState()
                val title = playbackState.optString("title")
                val artist = playbackState.optString("artist")
                val album = playbackState.optString("album")

                LyricManager(
                    context = this,
                    logger = ::appendThreadSafeLog
                ).scanLrcFiles(
                    title = title,
                    artist = artist,
                    album = album
                )
            } catch (exception: Exception) {
                appendThreadSafeLog("[LyricScan] failed: ${exception.message}")
            }
        }.apply {
            name = "LyricScanThread"
            start()
        }
    }

    private fun ensureRequiredPermissions(): Boolean {
        val missingPermissions = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSIONS
            )
            return false
        }

        return true
    }

    private fun requestRequiredPermissions() {
        ensureRequiredPermissions()
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        return permissions
    }

    private fun appendLog(message: String) {
        logTextView.append("$message\n")
        logScrollView.post {
            logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun appendPlayerAgentLog(message: String) {
        runOnUiThread {
            appendLog("[PlayerAgent] $message")
        }
    }

    private fun appendThreadSafeLog(message: String) {
        runOnUiThread {
            appendLog(message)
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}
