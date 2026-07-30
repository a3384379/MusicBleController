package com.example.controllerapp.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.example.controllerapp.ControllerApplication
import com.example.controllerapp.ControllerCommandGateway
import com.example.controllerapp.ControllerRepository
import com.example.controllerapp.ControllerServiceActions
import com.example.controllerapp.MainActivity
import com.example.controllerapp.ble.BleGattTransport
import com.example.controllerapp.ble.BleGattTransportListener
import com.example.controllerapp.classicbluetooth.RfcommServerManager
import com.example.controllerapp.model.ConnectionHealth
import com.example.controllerapp.model.ConnectionPhase
import com.example.controllerapp.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ControllerConnectionService :
    Service(),
    BleGattTransportListener,
    ControllerServiceActions {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: ControllerRepository
    private lateinit var commands: ControllerCommandGateway
    private lateinit var transport: BleGattTransport
    private lateinit var mediaSession: MediaSessionCompat
    private var rfcommManager: RfcommServerManager? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var manualStop = false
    private var healthProbeAtMs = 0L
    private var healthFailures = 0

    inner class LocalBinder : Binder() {
        val service: ControllerConnectionService
            get() = this@ControllerConnectionService
    }

    override fun onCreate() {
        super.onCreate()
        // Promote immediately. Some Android 15 vendor builds revoke the foreground-start
        // allowance as soon as a locked Activity loses TOP state, so BLE/MediaSession setup must
        // not run before this call.
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildBootstrapNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )
        val controllerApplication = application as ControllerApplication
        repository = controllerApplication.repository
        commands = controllerApplication.commandGateway
        transport = BleGattTransport(
            context = this,
            logger = repository.logStore::append,
            listener = this
        )
        repository.attachTransport { payload ->
            rfcommManager?.sendRawMessage(String(payload, Charsets.UTF_8))
                ?: transport.write(payload)
        }
        repository.attachServiceActions(this)
        createMediaSession()
        observeNotificationState()
        startHealthMonitor()
        scope.launch {
            val address = repository.preferences.savedDeviceAddress.first()
            startConnection(address, forceScan = false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREVIOUS -> commands.previous()
            ACTION_PLAY_PAUSE -> commands.playPause()
            ACTION_NEXT -> commands.next()
            ACTION_RECONNECT -> requestReconnect("notification", forceScan = false)
            ACTION_STOP -> stopConnection()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        reconnectJob?.cancel()
        repository.attachTransport(null)
        repository.attachServiceActions(null)
        stopRfcommOnly()
        transport.close()
        mediaSession.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onPhaseChanged(
        phase: ConnectionPhase,
        deviceName: String,
        address: String,
        reason: String
    ) {
        repository.updateConnectionPhase(phase, deviceName, address, reason)
        if (phase == ConnectionPhase.DISCONNECTED && !manualStop) {
            scheduleReconnect(reason.ifBlank { "link lost" })
        }
    }

    override fun onReady(deviceName: String, address: String, mtu: Int) {
        reconnectJob?.cancel()
        reconnectAttempt = 0
        healthProbeAtMs = 0L
        healthFailures = 0
        repository.onTransportReady(deviceName, address, mtu)
        scope.launch { repository.preferences.setSavedDeviceAddress(address) }
    }

    override fun onNotification(value: ByteArray) {
        healthProbeAtMs = 0L
        healthFailures = 0
        repository.handleNotification(value)
    }

    override fun onNotifyActivity() {
        healthProbeAtMs = 0L
        healthFailures = 0
        repository.markNotifyActivity()
    }

    override fun onWriteResult(success: Boolean, payload: ByteArray) {
        repository.onWriteResult(success, payload)
    }

    override fun requestReconnect(reason: String, forceScan: Boolean) {
        stopRfcommOnly()
        manualStop = false
        reconnectJob?.cancel()
        reconnectAttempt = 0
        repository.setReconnectAttempt(0, reason)
        repository.recordSelfHealing("重新连接：$reason")
        if (forceScan) {
            transport.reconnect(reason)
        } else {
            scope.launch {
                val address = repository.preferences.savedDeviceAddress.first()
                startConnection(address, forceScan = address.isBlank())
            }
        }
    }

    override fun stopConnection() {
        manualStop = true
        reconnectJob?.cancel()
        stopRfcommOnly()
        transport.disconnect()
    }

    override fun startLegacyRfcomm() {
        if (!hasBluetoothPermission()) {
            repository.updateConnectionPhase(
                ConnectionPhase.DISCONNECTED,
                reason = "Bluetooth permission missing"
            )
            return
        }
        reconnectJob?.cancel()
        manualStop = true
        stopRfcommOnly()
        transport.disconnect()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            repository.updateConnectionPhase(
                ConnectionPhase.DISCONNECTED,
                reason = "Bluetooth unavailable"
            )
            return
        }
        lateinit var manager: RfcommServerManager
        manager = RfcommServerManager(
            bluetoothAdapter = adapter,
            logger = { repository.logStore.append("[RFCOMM] $it") },
            onMessageReceived = { message ->
                repository.markNotifyActivity()
                repository.handleNotification(message.toByteArray(Charsets.UTF_8))
            },
            onConnectionChanged = connectionChanged@{ connected ->
                if (rfcommManager !== manager) return@connectionChanged
                if (connected) {
                    repository.onTransportReady("Sony RFCOMM", "RFCOMM", 23)
                    repository.recordSelfHealing("已切换到手动 RFCOMM 兼容链路")
                } else {
                    repository.updateConnectionPhase(
                        ConnectionPhase.DISCONNECTED,
                        deviceName = "Sony RFCOMM",
                        reason = "RFCOMM waiting for old Sony"
                    )
                }
            }
        )
        rfcommManager = manager
        repository.updateConnectionPhase(
            ConnectionPhase.CONNECTING,
            deviceName = "Sony RFCOMM",
            reason = "manual legacy compatibility"
        )
        manager.startServer()
    }

    override fun stopLegacyRfcomm() {
        stopRfcommOnly()
        manualStop = false
        repository.recordSelfHealing("已退出 RFCOMM 兼容链路，恢复 BLE V2")
        scope.launch {
            delay(250L)
            val address = repository.preferences.savedDeviceAddress.first()
            startConnection(address, forceScan = address.isBlank())
        }
    }

    private fun startConnection(savedAddress: String, forceScan: Boolean) {
        if (rfcommManager != null) return
        if (!hasBluetoothPermission()) {
            repository.updateConnectionPhase(
                ConnectionPhase.DISCONNECTED,
                reason = "Bluetooth permission missing"
            )
            return
        }
        manualStop = false
        transport.start(savedAddress, forceScan)
    }

    private fun stopRfcommOnly() {
        val active = rfcommManager
        rfcommManager = null
        active?.stopServer()
    }

    private fun hasBluetoothPermission(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!repository.settings.value.autoReconnect || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            reconnectAttempt += 1
            repository.setReconnectAttempt(reconnectAttempt, reason)
            delay(ReconnectPolicy.delayMs(reconnectAttempt))
            val address = repository.preferences.savedDeviceAddress.first()
            startConnection(
                address,
                forceScan = ReconnectPolicy.shouldForceScan(reconnectAttempt, address)
            )
        }
    }

    private fun startHealthMonitor() {
        scope.launch {
            while (true) {
                delay(5_000L)
                val connection = repository.connection.value
                if (!connection.connected) {
                    healthProbeAtMs = 0L
                    healthFailures = 0
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val silentFor = now - connection.lastNotifyElapsedMs
                val threshold = if (repository.playback.value.isPlaying) 15_000L else 30_000L
                if (silentFor < threshold) {
                    healthProbeAtMs = 0L
                    healthFailures = 0
                    continue
                }
                if (healthProbeAtMs == 0L) {
                    repository.markHealth(ConnectionHealth.SUSPECT, "notify silent")
                    repository.healthProbe()
                    healthProbeAtMs = now
                    continue
                }
                if (now - healthProbeAtMs >= 5_000L) {
                    healthFailures += 1
                    if (healthFailures >= 2) {
                        repository.markHealth(ConnectionHealth.STALE, "health probe timeout")
                        repository.recordSelfHealing("连续两次健康探测失败，重建当前 BLE 连接")
                        requestReconnect("health probe timeout", forceScan = false)
                    } else {
                        repository.healthProbe()
                        healthProbeAtMs = now
                    }
                }
            }
        }
    }

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, "SonyController").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    commands.playPause()
                }

                override fun onPause() {
                    commands.playPause()
                }

                override fun onSkipToPrevious() {
                    commands.previous()
                }

                override fun onSkipToNext() {
                    commands.next()
                }

                override fun onSeekTo(pos: Long) {
                    commands.seekTo(pos)
                }
            })
            isActive = true
        }
    }

    @SuppressLint("MissingPermission")
    @OptIn(FlowPreview::class)
    private fun observeNotificationState() {
        scope.launch {
            combine(
                repository.connection,
                repository.playback,
                repository.artwork
            ) { _, _, _ -> Unit }.debounce(NOTIFICATION_UPDATE_DEBOUNCE_MS).collect {
                val notification = buildNotification()
                runCatching {
                    NotificationManagerCompat.from(this@ControllerConnectionService)
                        .notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val playback = repository.playback.value
        updateMediaSession(playback)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val previous = actionIntent(ACTION_PREVIOUS, 1)
        val playPause = actionIntent(ACTION_PLAY_PAUSE, 2)
        val next = actionIntent(ACTION_NEXT, 3)
        val reconnect = actionIntent(ACTION_RECONNECT, 4)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(
                playback.title.takeUnless { it == "-" }
                    ?: if (repository.connection.value.connected) "等待 QQ 音乐播放" else "Sony 未连接"
            )
            .setContentText(playback.artist.takeUnless { it == "-" } ?: connectionSubtitle())
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        repository.artwork.value.bitmap?.let(builder::setLargeIcon)
        if (repository.connection.value.connected) {
            builder
                .addAction(android.R.drawable.ic_media_previous, "上一首", previous)
                .addAction(
                    if (playback.isPlaying) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play,
                    if (playback.isPlaying) "暂停" else "播放",
                    playPause
                )
                .addAction(android.R.drawable.ic_media_next, "下一首", next)
        } else {
            builder.addAction(android.R.drawable.stat_sys_data_bluetooth, "重新连接", reconnect)
        }
        return builder.build()
    }

    private fun buildBootstrapNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("正在连接 Sony")
            .setContentText("QQ 音乐控制服务正在启动")
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    private fun updateMediaSession(playback: PlaybackState) {
        val state = if (playback.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, repository.displayedPositionMs(), if (playback.isPlaying) 1f else 0f)
                .build()
        )
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, playback.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, playback.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, playback.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, playback.durationMs)
                .apply {
                    repository.artwork.value.bitmap?.let {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    }
                }
                .build()
        )
    }

    private fun connectionSubtitle(): String {
        val state = repository.connection.value
        return when (state.phase) {
            ConnectionPhase.SCANNING -> "正在查找 Sony"
            ConnectionPhase.CONNECTING,
            ConnectionPhase.DISCOVERING,
            ConnectionPhase.SUBSCRIBING,
            ConnectionPhase.RECONNECTING -> "正在连接 Sony"
            else -> state.lastReconnectReason.ifBlank { "点击重新连接" }
        }
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ControllerConnectionService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sony 播放控制",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "维持 Sony BLE 连接并提供后台播放控制"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_PREVIOUS = "com.example.controllerapp.action.PREVIOUS"
        const val ACTION_PLAY_PAUSE = "com.example.controllerapp.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.controllerapp.action.NEXT"
        const val ACTION_RECONNECT = "com.example.controllerapp.action.RECONNECT"
        const val ACTION_STOP = "com.example.controllerapp.action.STOP"
        private const val CHANNEL_ID = "sony_controller_connection"
        private const val NOTIFICATION_ID = 8201
        private const val NOTIFICATION_UPDATE_DEBOUNCE_MS = 250L
    }
}
