package com.example.playeragent.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.playeragent.MainActivity
import com.example.playeragent.StartupGuard
import com.example.playeragent.ble.BleAdvertiserManager
import com.example.playeragent.ble.BleGattServerManager
import com.example.playeragent.ble.BleHealthSnapshot
import com.example.playeragent.ble.BleHealthState
import com.example.playeragent.history.PlaybackHistoryMonitor
import com.example.playeragent.logging.LogConfig
import com.example.playeragent.logging.LogBuffer
import com.example.playeragent.media.QrcDirectoryWatcher
import com.example.playeragent.media.QrcIncrementalPrebuildManager
import com.example.playeragent.media.QrcMaintenanceCoordinator
import com.example.playeragent.media.QrcWatcherStatus
import com.example.playeragent.media.MaintenanceTaskType

class PlayerAgentForegroundService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiserManager: BleAdvertiserManager? = null
    private var gattServerManager: BleGattServerManager? = null
    private var playbackHistoryMonitor: PlaybackHistoryMonitor? = null
    private var qrcIncrementalPrebuildManager: QrcIncrementalPrebuildManager? = null
    private var qrcDirectoryWatcher: QrcDirectoryWatcher? = null
    @Volatile
    private var serviceStopping = false
    @Volatile
    private var bluetoothAvailable = false
    private var recoverRunnable: Runnable? = null
    private var bleHealthWatchdogRunnable: Runnable? = null
    @Volatile
    private var bleRecovering = false
    @Volatile
    private var lastBleRecoveryAtMs = 0L
    @Volatile
    private var bleRecoveryCount = 0
    @Volatile
    private var lastPublishedBleHealthState: BleHealthState? = null
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) {
                return
            }
            val state = intent.getIntExtra(
                BluetoothAdapter.EXTRA_STATE,
                BluetoothAdapter.ERROR
            )
            handleBluetoothStateChanged(state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        StartupGuard.markServiceOnCreate(::log)
        running = true
        serviceStopping = false
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("PlayerAgent is running"))
        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        log("Foreground service started")
        startPlaybackHistoryMonitor()
        startBleHealthWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> stopSelf()
            ACTION_STOP_QRC_WATCHER -> stopQrcWatcher()
            ACTION_START_QRC_WATCHER -> startQrcWatcher()
            ACTION_RECOVER_BLE_STACK -> recoverBleStack("manual debug", respectCooldown = false)
            ACTION_DUMP_BLE_DIAGNOSTICS -> logBleDiagnostics("debug dump")
            ACTION_REFRESH_CURRENT_LYRIC -> refreshCurrentLyric()
            else -> {
                startPlaybackHistoryMonitor()
                scheduleDefaultStartupWork()
            }
        }
        StartupGuard.markServiceOnStartCommandReturnFast(::log)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        serviceStopping = true
        mainHandler.removeCallbacksAndMessages(null)
        bleHealthWatchdogRunnable = null
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        stopPlaybackHistoryMonitor()
        stopQrcWatcher()
        advertiserManager?.stopAdvertising()
        advertiserManager = null
        gattServerManager?.close("service destroyed")
        gattServerManager = null
        publishBleHealthSnapshot("service destroyed")
        log("Foreground service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureBleStackStarted(reason: String, forceRebuild: Boolean = false) {
        log("[BLE-RECOVERY] ensure start reason=$reason")
        initializeBluetooth(reason = reason, forceRebuild = forceRebuild)
    }

    private fun startPlaybackHistoryMonitor() {
        val existing = playbackHistoryMonitor
        if (existing != null) {
            return
        }
        playbackHistoryMonitor = PlaybackHistoryMonitor(
            context = this,
            logger = ::log
        ).also {
            it.start()
        }
    }

    private fun stopPlaybackHistoryMonitor() {
        playbackHistoryMonitor?.stop()
        playbackHistoryMonitor = null
    }

    private fun scheduleDefaultStartupWork() {
        mainHandler.post {
            if (serviceStopping) {
                return@post
            }
            ensureBleStackStarted("service start")
            log("[QrcWatcher] startup auto watcher disabled; use Debug Tools manual start")
            log("[LyricWarmup] startup auto warmup disabled; use Debug Tools manual warmup")
        }
    }

    private fun initializeBluetooth(
        reason: String = "initialize",
        forceRebuild: Boolean = false
    ) {
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
            log("[ControlServiceAutoStart] failed reason=permission_missing")
            publishBleHealthSnapshot("permission_missing")
            return
        }

        if (!adapter.isEnabled) {
            log("Bluetooth is disabled")
            bluetoothAvailable = false
            publishBleHealthSnapshot("bluetooth_disabled")
            return
        }
        bluetoothAvailable = true

        val advertisingSupported = adapter.isMultipleAdvertisementSupported
        log("BLE advertising supported: $advertisingSupported")

        logBleDiagnostics("before ensureBleStackStarted reason=$reason")

        if (forceRebuild) {
            log("[BLE-RECOVERY] close stale gatt server")
            advertiserManager?.stopAdvertising()
            advertiserManager = null
            gattServerManager?.close("force rebuild reason=$reason")
            gattServerManager = null
        }

        val existingGattServerManager = gattServerManager
        if (existingGattServerManager == null) {
            log("[BLE-RECOVERY] open gatt server")
            val manager = BleGattServerManager(
                context = this,
                bluetoothManager = bluetoothManager,
                logger = ::log,
                transientLogger = ::logLocalOnly,
                verboseLogger = ::logVerbose,
                advertisingStateProvider = {
                    advertiserManager?.getAdvertisingState()?.name ?: "none"
                },
                onAllClientsDisconnected = ::handleAllClientsDisconnected,
                onPlaybackUiState = ::publishPlaybackUiState
            )
            if (manager.start()) {
                gattServerManager = manager
                log("[BLE-RECOVERY] add service")
            }
        } else if (!existingGattServerManager.isStarted()) {
            log("[BLE-RECOVERY] restart existing gatt server")
            existingGattServerManager.start()
        } else {
            log("[BleGattServer] already started")
            logVerbose("GATT server already running; initialization skipped")
        }

        if (!advertisingSupported) {
            log("BLE advertising not supported")
            return
        }

        val existingAdvertiserManager = advertiserManager
        if (existingAdvertiserManager == null) {
            log("[BLE-RECOVERY] start advertising")
            advertiserManager = BleAdvertiserManager(
                context = this,
                bluetoothAdapter = adapter,
                logger = ::log
            ).also {
                it.startAdvertising()
            }
        } else if (!existingAdvertiserManager.isAdvertising()) {
            log("[BLE-RECOVERY] start advertising")
            existingAdvertiserManager.startAdvertising()
        } else {
            logVerbose("BLE advertising already running; initialization skipped")
        }
        logBleDiagnostics("after ensureBleStackStarted reason=$reason")
        publishBleHealthSnapshot("ensureBleStackStarted reason=$reason")
    }

    private fun handleBluetoothStateChanged(state: Int) {
        val name = when (state) {
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            else -> "UNKNOWN_$state"
        }
        log("[BT-STATE] state=$name")
        when (state) {
            BluetoothAdapter.STATE_TURNING_OFF,
            BluetoothAdapter.STATE_OFF -> handleBluetoothUnavailable()
            BluetoothAdapter.STATE_ON -> scheduleBleRecovery("bluetooth state on")
        }
    }

    private fun handleBluetoothUnavailable() {
        bluetoothAvailable = false
        recoverRunnable?.let { mainHandler.removeCallbacks(it) }
        recoverRunnable = null
        log("[BLE-RECOVERY] bluetooth off, clearing runtime state")
        gattServerManager?.close("bluetooth off")
        gattServerManager = null
        advertiserManager?.forceMarkStopped("bluetooth off")
        advertiserManager = null
        logBleDiagnostics("bluetooth off")
        publishBleHealthSnapshot("bluetooth off")
    }

    private fun scheduleBleRecovery(reason: String) {
        if (serviceStopping) {
            log("[BLE-RECOVERY] schedule skipped reason=service stopping")
            return
        }
        recoverRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            recoverRunnable = null
            recoverBleStack(reason, respectCooldown = false)
        }
        recoverRunnable = runnable
        log("[BLE-RECOVERY] scheduled reason=$reason")
        mainHandler.postDelayed(runnable, BLE_RECOVERY_DELAY_MS)
    }

    private fun recoverBleStack(reason: String, respectCooldown: Boolean = false) {
        if (serviceStopping) {
            log("[BLE-RECOVERY] skipped reason=service stopping")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (respectCooldown && lastBleRecoveryAtMs > 0L &&
            now - lastBleRecoveryAtMs < BLE_RECOVERY_COOLDOWN_MS
        ) {
            val remainingMs = BLE_RECOVERY_COOLDOWN_MS - (now - lastBleRecoveryAtMs)
            log("[BleHealth] recover skip reason=cooldown remainingMs=$remainingMs trigger=$reason")
            publishBleHealthSnapshot("recover cooldown")
            return
        }
        val adapter = bluetoothAdapter ?: getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            log("[BLE-RECOVERY] skipped reason=bluetooth disabled")
            publishBleHealthSnapshot("recover skipped bluetooth disabled")
            return
        }
        bluetoothAvailable = true
        bleRecovering = true
        lastBleRecoveryAtMs = now
        bleRecoveryCount += 1
        val startMs = SystemClock.elapsedRealtime()
        log("[BleHealth] recover start reason=$reason")
        publishBleHealthSnapshot("recovering reason=$reason")
        log("[BLE-RECOVERY] start reason=$reason")
        logBleDiagnostics("before recovery reason=$reason")
        ensureBleStackStarted(reason = reason, forceRebuild = true)
        bleRecovering = false
        val success = gattServerManager?.isServerReady() == true
        log("[BLE-RECOVERY] done")
        log("[BleHealth] recover done success=$success costMs=${SystemClock.elapsedRealtime() - startMs}")
        logBleDiagnostics("after recovery reason=$reason")
        publishBleHealthSnapshot("recover done reason=$reason")
    }

    private fun startBleHealthWatchdog() {
        if (bleHealthWatchdogRunnable != null) {
            return
        }
        val runnable = object : Runnable {
            override fun run() {
                if (!serviceStopping) {
                    runBleHealthWatchdog()
                    mainHandler.postDelayed(this, BLE_HEALTH_WATCHDOG_INTERVAL_MS)
                }
            }
        }
        bleHealthWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, BLE_HEALTH_WATCHDOG_INTERVAL_MS)
    }

    private fun runBleHealthWatchdog() {
        val snapshot = publishBleHealthSnapshot("watchdog")
        if (serviceStopping) {
            return
        }
        if (!bluetoothAvailable || bluetoothAdapter?.isEnabled != true) {
            return
        }
        val manager = gattServerManager
        if (manager == null || !snapshot.gattStarted || snapshot.gattState != "READY") {
            log("[BleHealth] watchdog action=recover_gatt reason=gatt_not_ready")
            recoverBleStack("gatt_not_ready", respectCooldown = true)
            return
        }

        if (snapshot.connectedCount == 0 && snapshot.subscribedCount > 0) {
            manager.clearStaleSubscribers("connected_empty_subscribed_non_empty")
            log(
                "[BleHealth] watchdog action=clear_stale_subscribers " +
                    "reason=connected_empty_subscribed_non_empty"
            )
            restartAdvertisingFromWatchdog("connected_empty_subscribed_non_empty")
            publishBleHealthSnapshot("stale subscribers cleared")
            return
        }

        if (snapshot.connectedCount == 0 &&
            !snapshot.advertisingState.equals("STARTED", ignoreCase = true)
        ) {
            restartAdvertisingFromWatchdog("advertising_not_started")
            return
        }

        if (snapshot.subscribedCount > 0 &&
            manager.notifyFailureCount() >= NOTIFY_FAILURE_RECOVERY_THRESHOLD
        ) {
            manager.clearStaleSubscribers("notify_failures")
            restartAdvertisingFromWatchdog("notify_failures")
            recoverBleStack("notify_failures", respectCooldown = true)
            return
        }

        if (snapshot.subscribedCount > 0 &&
            (manager.hasSuccessHeartbeatOlderThan(SUBSCRIBED_STALE_RECOVERY_MS) ||
                manager.subscribedWithoutSuccessOlderThan(SUBSCRIBED_STALE_RECOVERY_MS))
        ) {
            log("[BleHealth] state=SUSPECT reason=no_success_heartbeat ageMs>=$SUBSCRIBED_STALE_RECOVERY_MS")
            recoverBleStack("subscribed_stale", respectCooldown = true)
            return
        }

        if (snapshot.subscribedCount > 0 &&
            (manager.hasSuccessHeartbeatOlderThan(SUBSCRIBED_SUSPECT_MS) ||
                manager.subscribedWithoutSuccessOlderThan(SUBSCRIBED_SUSPECT_MS))
        ) {
            log("[BleHealth] state=SUSPECT reason=no_success_heartbeat ageMs>=$SUBSCRIBED_SUSPECT_MS")
            publishBleHealthSnapshot("subscribed suspect")
        }
    }

    private fun restartAdvertisingFromWatchdog(reason: String) {
        val advertiser = advertiserManager
        if (advertiser == null) {
            log("[BleHealth] watchdog action=recover_gatt reason=advertiser_unavailable")
            recoverBleStack("advertiser_unavailable", respectCooldown = true)
            return
        }
        log("[BleHealth] watchdog action=restart_advertising reason=$reason")
        advertiser.restartAdvertising("watchdog $reason")
        mainHandler.postDelayed(
            { publishBleHealthSnapshot("advertising restart reason=$reason") },
            ADVERTISING_RESTORE_DIAG_DELAY_MS
        )
    }

    private fun publishBleHealthSnapshot(reason: String): BleHealthSnapshot {
        val snapshot = buildBleHealthSnapshot(reason)
        latestBleHealthSnapshot = snapshot
        if (lastPublishedBleHealthState != snapshot.healthState) {
            lastPublishedBleHealthState = snapshot.healthState
            log("[BleHealth] state=${snapshot.healthState} reason=${snapshot.reason ?: reason}")
        }
        return snapshot
    }

    private fun buildBleHealthSnapshot(reason: String): BleHealthSnapshot {
        val manager = gattServerManager
        val advertisingState = advertiserManager?.getAdvertisingState()?.name ?: "none"
        if (!running || serviceStopping) {
            return BleHealthSnapshot.stopped(reason)
        }
        if (manager != null) {
            return manager.healthSnapshot(
                serviceRunning = true,
                advertisingState = advertisingState,
                lastRecoveryAt = lastBleRecoveryAtMs,
                recoveryCount = bleRecoveryCount,
                recovering = bleRecovering,
                reason = reason
            )
        }
        val state = when {
            bleRecovering -> BleHealthState.RECOVERING
            !bluetoothAvailable && bluetoothAdapter?.isEnabled == false -> BleHealthState.ERROR
            else -> BleHealthState.STARTING
        }
        return BleHealthSnapshot(
            healthState = state,
            serviceRunning = true,
            gattStarted = false,
            gattState = "none",
            advertisingState = advertisingState,
            connectedCount = 0,
            subscribedCount = 0,
            notificationInFlight = 0,
            pendingJobs = 0,
            lastCommandSuccessAt = 0L,
            lastNotifySuccessAt = 0L,
            lastNotifyFailureAt = 0L,
            notifyFailureCount = 0,
            lastRecoveryAt = lastBleRecoveryAtMs,
            recoveryCount = bleRecoveryCount,
            reason = reason
        )
    }

    private fun logBleDiagnostics(reason: String) {
        val gattSnapshot = gattServerManager?.snapshot()
        val advertiserSnapshot = advertiserManager?.snapshot()
        val watcher = qrcDirectoryWatcher
        log(
            "[BLE-DIAG] reason=$reason " +
                "bluetoothEnabled=${bluetoothAdapter?.isEnabled ?: false} " +
                "bluetoothAvailable=$bluetoothAvailable " +
                "gattState=${gattSnapshot?.serverState ?: "none"} " +
                "gattStarted=${gattSnapshot?.started ?: false} " +
                "advertisingState=${advertiserSnapshot?.state ?: "none"} " +
                "connectedDevices=${gattSnapshot?.connectedDevices ?: emptyList<String>()} " +
                "subscribedDevices=${gattSnapshot?.subscribedDevices ?: emptyList<String>()} " +
                "notificationInFlight=${gattSnapshot?.notificationInFlight ?: false} " +
                "pendingJobs=${gattSnapshot?.pendingJobs ?: 0} " +
                "activeJob=${gattSnapshot?.activeJob} " +
                "pendingShortMessages=${gattSnapshot?.pendingShortMessages ?: 0} " +
                "watcherRunning=${watcher != null}"
        )
        val health = publishBleHealthSnapshot("diagnostics reason=$reason")
        log(
            "[BLE-DIAG] healthState=${health.healthState} " +
                "notifyFailures=${health.notifyFailureCount} " +
                "lastCommandSuccessAt=${health.lastCommandSuccessAt} " +
                "lastNotifySuccessAt=${health.lastNotifySuccessAt} " +
                "lastRecoveryAt=${health.lastRecoveryAt} " +
                "recoveryCount=${health.recoveryCount}"
        )
    }

    private fun handleAllClientsDisconnected(reason: String) {
        val advertiser = advertiserManager
        val snapshot = advertiser?.snapshot()
        log(
            "[BLE-DIAG] all clients disconnected reason=$reason " +
                "advertisingState=${snapshot?.state ?: "none"} " +
                "restartPending=${snapshot?.restartPending ?: false}"
        )
        if (serviceStopping) {
            log("[BLE-ADV] restart skipped reason=service stopping")
            return
        }
        if (advertiser == null) {
            log("[BLE-ADV] restart skipped reason=advertiser unavailable")
            return
        }
        advertiser.restartAdvertising(reason)
        publishBleHealthSnapshot("all clients disconnected")
        mainHandler.postDelayed(
            {
                log(
                    "[BLE-DIAG] advertising restored " +
                        "state=${advertiser.getAdvertisingState()}"
                )
            },
            ADVERTISING_RESTORE_DIAG_DELAY_MS
        )
    }

    private fun startQrcWatcher() {
        val manager = qrcIncrementalPrebuildManager ?: QrcIncrementalPrebuildManager(
            context = this,
            logger = ::log,
            statusListener = ::publishQrcWatcherStatus,
            currentTrackProvider = {
                gattServerManager?.currentTrackSnapshot()
            },
            onIncrementalLyricsReady = { ready ->
                gattServerManager?.handleIncrementalLyricsReady(ready)
            },
            onBatchProcessed = { groups ->
                if (groups.isNotEmpty()) {
                    gattServerManager?.notifyLyricIncrementalBatchDone(groups)
                    val handled = gattServerManager?.retryCurrentLyricsFromWatcher(
                        "qrc watcher generation changed"
                    ) == true
                    if (handled) {
                        log("[LyricRetry] watcher retry requested groups=${groups.size}")
                    } else {
                        log("[LyricRetry] watcher retry skipped groups=${groups.size}")
                    }
                }
            }
        ).also {
            qrcIncrementalPrebuildManager = it
        }
        val watcher = qrcDirectoryWatcher ?: QrcDirectoryWatcher(
            incrementalPrebuildManager = manager,
            logger = ::log,
            statusListener = ::publishQrcWatcherStatus
        ).also {
            qrcDirectoryWatcher = it
        }
        watcher.start()
    }

    private fun stopQrcWatcher() {
        qrcDirectoryWatcher?.stop()
        qrcDirectoryWatcher = null
        qrcIncrementalPrebuildManager?.stop()
        qrcIncrementalPrebuildManager = null
        QrcMaintenanceCoordinator.finishCurrentIf(
            MaintenanceTaskType.QRC_INCREMENTAL_PREBUILD,
            "service stop watcher",
            ::log
        )
        publishQrcWatcherStatus(
            QrcWatcherStatus(
                watcherRunning = false,
                pendingGroups = 0,
                incrementalRunning = false,
                incrementalSuccess = 0,
                incrementalFailed = 0,
                incrementalSkipped = 0
            )
        )
    }

    private fun refreshCurrentLyric() {
        val handled = gattServerManager?.manualRefreshCurrentLyric() == true
        if (handled) {
            log("[LyricRetry] manual refresh requested from service")
        } else {
            log("[LyricRetry] manual refresh request not started")
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

    private fun publishQrcWatcherStatus(status: QrcWatcherStatus) {
        sendBroadcast(
            Intent(ACTION_QRC_WATCHER_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_QRC_WATCHER_RUNNING, status.watcherRunning)
                .putExtra(EXTRA_QRC_WATCHER_PENDING, status.pendingGroups)
                .putExtra(EXTRA_QRC_INCREMENTAL_RUNNING, status.incrementalRunning)
                .putExtra(EXTRA_QRC_INCREMENTAL_SUCCESS, status.incrementalSuccess)
                .putExtra(EXTRA_QRC_INCREMENTAL_FAILED, status.incrementalFailed)
                .putExtra(EXTRA_QRC_INCREMENTAL_SKIPPED, status.incrementalSkipped)
        )
    }

    private fun publishPlaybackUiState(state: org.json.JSONObject) {
        sendBroadcast(
            Intent(ACTION_PLAYER_UI_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_UI_TITLE, state.optString("title"))
                .putExtra(EXTRA_UI_ARTIST, state.optString("artist"))
                .putExtra(EXTRA_UI_ALBUM, state.optString("album"))
                .putExtra(EXTRA_UI_LYRIC, state.optString("lyric"))
                .putExtra(EXTRA_UI_LYRIC_STATUS, state.optString("lyricStatus"))
                .putExtra(EXTRA_UI_POSITION, state.optLong("position"))
                .putExtra(EXTRA_UI_DURATION, state.optLong("duration"))
        )
    }

    companion object {
        const val ACTION_LOG = "com.example.playeragent.ACTION_LOG"
        const val ACTION_PLAYER_UI_STATE =
            "com.example.playeragent.ACTION_PLAYER_UI_STATE"
        const val ACTION_QRC_WATCHER_STATUS =
            "com.example.playeragent.ACTION_QRC_WATCHER_STATUS"
        const val ACTION_STOP_SERVICE =
            "com.example.playeragent.ACTION_STOP_SERVICE"
        const val ACTION_START_QRC_WATCHER =
            "com.example.playeragent.ACTION_START_QRC_WATCHER"
        const val ACTION_STOP_QRC_WATCHER =
            "com.example.playeragent.ACTION_STOP_QRC_WATCHER"
        const val ACTION_RECOVER_BLE_STACK =
            "com.example.playeragent.ACTION_RECOVER_BLE_STACK"
        const val ACTION_DUMP_BLE_DIAGNOSTICS =
            "com.example.playeragent.ACTION_DUMP_BLE_DIAGNOSTICS"
        const val ACTION_REFRESH_CURRENT_LYRIC =
            "com.example.playeragent.ACTION_REFRESH_CURRENT_LYRIC"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"
        const val EXTRA_QRC_WATCHER_RUNNING = "extra_qrc_watcher_running"
        const val EXTRA_QRC_WATCHER_PENDING = "extra_qrc_watcher_pending"
        const val EXTRA_QRC_INCREMENTAL_RUNNING = "extra_qrc_incremental_running"
        const val EXTRA_QRC_INCREMENTAL_SUCCESS = "extra_qrc_incremental_success"
        const val EXTRA_QRC_INCREMENTAL_FAILED = "extra_qrc_incremental_failed"
        const val EXTRA_QRC_INCREMENTAL_SKIPPED = "extra_qrc_incremental_skipped"
        const val EXTRA_UI_TITLE = "extra_ui_title"
        const val EXTRA_UI_ARTIST = "extra_ui_artist"
        const val EXTRA_UI_ALBUM = "extra_ui_album"
        const val EXTRA_UI_LYRIC = "extra_ui_lyric"
        const val EXTRA_UI_LYRIC_STATUS = "extra_ui_lyric_status"
        const val EXTRA_UI_POSITION = "extra_ui_position"
        const val EXTRA_UI_DURATION = "extra_ui_duration"

        private const val TAG = "PlayerAgent"
        private const val CHANNEL_ID = "player_agent_service"
        private const val NOTIFICATION_ID = 10001
        private const val ADVERTISING_RESTORE_DIAG_DELAY_MS = 1_000L
        private const val BLE_RECOVERY_DELAY_MS = 1_200L
        private const val BLE_HEALTH_WATCHDOG_INTERVAL_MS = 5_000L
        private const val BLE_RECOVERY_COOLDOWN_MS = 30_000L
        private const val SUBSCRIBED_SUSPECT_MS = 30_000L
        private const val SUBSCRIBED_STALE_RECOVERY_MS = 45_000L
        private const val NOTIFY_FAILURE_RECOVERY_THRESHOLD = 3
        @Volatile
        private var running = false
        @Volatile
        private var latestBleHealthSnapshot = BleHealthSnapshot.stopped()

        fun isRunning(): Boolean = running

        fun bleHealthSnapshot(): BleHealthSnapshot = latestBleHealthSnapshot
    }
}
