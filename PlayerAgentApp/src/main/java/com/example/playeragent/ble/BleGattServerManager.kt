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
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.example.playeragent.history.HistorySessionRow
import com.example.playeragent.history.PlaybackHistoryRepository
import com.example.playeragent.history.PlaybackStatsSummary
import com.example.playeragent.history.StatsRange
import com.example.playeragent.media.AlbumArtTestManager
import com.example.playeragent.logging.LogConfig
import com.example.playeragent.logging.LogBuffer
import com.example.playeragent.media.MediaCommandExecutor
import com.example.playeragent.media.MediaFieldDumpManager
import com.example.playeragent.media.CurrentTrackSnapshot
import com.example.playeragent.media.IncrementalLyricsReady
import com.example.playeragent.media.PlaybackStateReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BleGattServerManager(
    context: Context,
    private val bluetoothManager: BluetoothManager,
    private val logger: (String) -> Unit,
    private val transientLogger: (String) -> Unit = logger,
    private val verboseLogger: (String) -> Unit = logger,
    private val advertisingStateProvider: () -> String = { "unknown" },
    private val onAllClientsDisconnected: (reason: String) -> Unit = {},
    private val onPlaybackUiState: (JSONObject) -> Unit = {}
) {

    private val appContext = context.applicationContext
    private val connectedDeviceAddresses = ConcurrentHashMap.newKeySet<String>()
    private val subscribedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtuByAddress = ConcurrentHashMap<String, Int>()
    private val recentConnectionCallbacks = ConcurrentHashMap<String, Long>()
    private val recentMtuCallbacks = ConcurrentHashMap<String, Long>()
    private val playbackStateReader = PlaybackStateReader(
        context = appContext,
        logger = logger
    )
    private val albumArtTestManager = AlbumArtTestManager(
        context = appContext,
        logger = logger
    )
    private val mediaCommandExecutor = MediaCommandExecutor(
        context = appContext,
        logger = logger,
        sendLine = { message -> sendStatusMessage(message) }
    )
    private val mediaFieldDumpManager = MediaFieldDumpManager(appContext)
    private val mediaFieldDumpExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "MediaFieldDumpThread")
        }
    private val mediaFieldDumpPreparing = AtomicBoolean(false)
    private val historyExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "PlaybackHistoryBleThread")
        }
    private val historyQueryPreparing = AtomicBoolean(false)
    private val albumArtHandler = Handler(Looper.getMainLooper())

    private var gattServer: BluetoothGattServer? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var autoPushExecutor: ScheduledExecutorService? = null
    private val notifyQueue = BleNotifyQueue(
        serverProvider = { gattServer },
        characteristicProvider = { statusCharacteristic },
        logger = logger,
        localOnlyLogger = transientLogger,
        verboseLogger = verboseLogger
    )
    @Volatile
    private var lastAlbumArtKey: String? = null
    @Volatile
    private var currentAlbumArtId: String? = null
    private val albumArtRequestsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val albumArtRequestsCompleted = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var pendingAlbumArt: PendingAlbumArt? = null
    @Volatile
    private var albumArtScheduleGeneration = 0L
    private var lastAutoPushSongKey: String? = null
    private var lastAutoPushPlaying: Boolean? = null
    @Volatile
    private var clientSupportsBinaryAlbumArt = false
    @Volatile
    private var started = false
    @Volatile
    private var serverState = ServerState.STOPPED

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logger("[BLE-A] service added success")
                updateServerState(ServerState.READY)
            } else {
                logger("[BLE-A] service added failed: status=$status")
                updateServerState(ServerState.FAILED)
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = device?.address ?: "unknown"
            val callbackKey = "$address|$status|$newState"
            if (shouldLogCallback(recentConnectionCallbacks, callbackKey)) {
                logger(
                    "[BLE-A] GATT connection state changed: " +
                        "device=$address status=$status newState=$newState"
                )
            }

            if (newState == BluetoothProfile.STATE_CONNECTED && device != null) {
                connectedDeviceAddresses.add(address)
                mtuByAddress[address] = DEFAULT_MTU
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val hasUsableAddress = address.isNotBlank() && address != "unknown"
                if (hasUsableAddress) {
                    connectedDeviceAddresses.remove(address)
                    subscribedDevices.remove(address)
                    mtuByAddress.remove(address)
                    notifyQueue.removeDevice(address)
                } else {
                    logger("[BLE-A] disconnect address unavailable, clearing all notify state")
                    connectedDeviceAddresses.clear()
                    subscribedDevices.clear()
                    mtuByAddress.clear()
                    notifyQueue.clearAllForDisconnect("unknown disconnect address")
                }
                lastAlbumArtKey = null
                currentAlbumArtId = null
                albumArtRequestsInFlight.clear()
                albumArtRequestsCompleted.clear()
                pendingAlbumArt = null
                albumArtScheduleGeneration += 1
                clientSupportsBinaryAlbumArt = false
                lastAutoPushSongKey = null
                lastAutoPushPlaying = null
                stopAutoPushIfUnused()
                logDisconnectDiagnostics(address)
                if (connectedDeviceAddresses.isEmpty()) {
                    onAllClientsDisconnected("gatt disconnected status=$status")
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            val address = device?.address ?: return
            mtuByAddress[address] = mtu
            val callbackKey = "$address|$mtu"
            if (shouldLogCallback(recentMtuCallbacks, callbackKey)) {
                logger("[BLE-A] MTU changed: device=$address mtu=$mtu")
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            notifyQueue.onNotificationSent(status)
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
            if (characteristic?.uuid != PlayerAgentUuids.COMMAND_CHARACTERISTIC_UUID) {
                logger("[BLE-A] unsupported characteristic write uuid=${characteristic?.uuid}")
                if (responseNeeded) {
                    sendWriteResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        value
                    )
                }
                return
            }

            val receiveElapsedMs = SystemClock.elapsedRealtime()
            val receiveWallMs = System.currentTimeMillis()
            val valueText = value?.toString(Charsets.UTF_8).orEmpty()
            logger(
                "[CTRL-Sony] write received seq=unknown cmdRaw=$valueText " +
                    "device=${device?.address ?: "unknown"} timeMs=$receiveWallMs"
            )
            logger("[BLE-A] command write received: $valueText")

            val parseStartedAtMs = SystemClock.elapsedRealtime()
            val request = try {
                JSONObject(valueText)
            } catch (exception: Exception) {
                logger("[BLE-A] command parse failed: ${exception.message}")
                if (responseNeeded) {
                    val responseStartMs = SystemClock.elapsedRealtime()
                    logger(
                        "[CTRL-Sony] sendResponse begin seq=unknown cmd=parse_failed " +
                            "timeMs=${System.currentTimeMillis()}"
                    )
                    val ok = sendWriteResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        value
                    )
                    logger(
                        "[CTRL-Sony] sendResponse end seq=unknown cmd=parse_failed " +
                            "costMs=${SystemClock.elapsedRealtime() - responseStartMs} ok=$ok"
                    )
                }
                return
            }

            val command = request.optString("cmd")
            val seq = request.optString("seq").ifBlank { "unknown" }
            logger(
                "[CTRL-Sony] command parsed seq=$seq cmd=$command " +
                    "parseCostMs=${SystemClock.elapsedRealtime() - parseStartedAtMs}"
            )
            logger("[BLE-A] command received: $command")
            logger(
                "[CTRL-Sony] before handle seq=$seq cmd=$command " +
                    "queueSnapshot=${controlQueueSnapshot()}"
            )
            val handleStartedAtMs = SystemClock.elapsedRealtime()
            handleCommand(command, request, seq)
            val handleCostMs = SystemClock.elapsedRealtime() - handleStartedAtMs
            logger(
                "[CTRL-Sony] after handle seq=$seq cmd=$command " +
                    "handleCostMs=$handleCostMs"
            )

            if (responseNeeded) {
                val responseStartMs = SystemClock.elapsedRealtime()
                logger(
                    "[CTRL-Sony] sendResponse begin seq=$seq cmd=$command " +
                        "timeMs=${System.currentTimeMillis()}"
                )
                val ok = sendWriteResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
                logger(
                    "[CTRL-Sony] sendResponse end seq=$seq cmd=$command " +
                        "costMs=${SystemClock.elapsedRealtime() - responseStartMs} ok=$ok"
                )
            }
            logger(
                "[CTRL-Sony] total cost seq=$seq cmd=$command " +
                    "totalCostMs=${SystemClock.elapsedRealtime() - receiveElapsedMs}"
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val isStatusCccd =
                descriptor?.uuid == PlayerAgentUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                    descriptor.characteristic.uuid == PlayerAgentUuids.STATUS_CHARACTERISTIC_UUID

            if (!isStatusCccd || device == null) {
                logger("[BLE-A] unsupported descriptor write uuid=${descriptor?.uuid}")
                if (responseNeeded) {
                    sendWriteResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        value
                    )
                }
                return
            }

            when {
                value?.contentEquals(
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                ) == true -> {
                    subscribedDevices[device.address] = device
                    logger("[BLE-A] status notify subscribed: device=${device.address}")
                    startAutoPush()
                }

                value?.contentEquals(
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                ) == true -> {
                    subscribedDevices.remove(device.address)
                    notifyQueue.removeDevice(device.address)
                    lastAlbumArtKey = null
                    currentAlbumArtId = null
                    albumArtRequestsInFlight.clear()
                    albumArtRequestsCompleted.clear()
                    pendingAlbumArt = null
                    albumArtScheduleGeneration += 1
                    clientSupportsBinaryAlbumArt = false
                    lastAutoPushSongKey = null
                    lastAutoPushPlaying = null
                    logger("[BLE-A] status notify unsubscribed: device=${device.address}")
                    stopAutoPushIfUnused()
                }

                else -> logger("[BLE-A] unsupported CCCD value")
            }

            if (responseNeeded) {
                sendWriteResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (started && gattServer != null) {
            logger("[BLE-A] GATT server already started; skip initialization")
            return true
        }
        updateServerState(ServerState.STARTING)

        try {
            gattServer = bluetoothManager.openGattServer(appContext, callback)
        } catch (securityException: SecurityException) {
            logger("[BLE-A] GATT server start failed: missing permission")
            return false
        } catch (exception: Exception) {
            logger("[BLE-A] GATT server start failed: ${exception.message}")
            return false
        }

        if (gattServer == null) {
            logger("[BLE-A] GATT server start failed: openGattServer returned null")
            updateServerState(ServerState.FAILED)
            return false
        }

        started = true
        logger("[BLE-A] GATT server started")

        val service = BluetoothGattService(
            PlayerAgentUuids.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val commandCharacteristic = BluetoothGattCharacteristic(
            PlayerAgentUuids.COMMAND_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val status = BluetoothGattCharacteristic(
            PlayerAgentUuids.STATUS_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        status.addDescriptor(
            BluetoothGattDescriptor(
                PlayerAgentUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        statusCharacteristic = status
        service.addCharacteristic(commandCharacteristic)
        service.addCharacteristic(status)

        try {
            val addRequested = gattServer?.addService(service) == true
            logger("[BLE-A] GATT addService requested: $addRequested")
        } catch (securityException: SecurityException) {
            logger("[BLE-A] GATT addService failed: missing permission")
        } catch (exception: Exception) {
            logger("[BLE-A] GATT addService failed: ${exception.message}")
        }
        return true
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun close() {
        close("close requested")
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun close(reason: String) {
        if (!started && gattServer == null) {
            updateServerState(ServerState.STOPPED)
            return
        }
        logger("[BLE-GATT] close reason=$reason")
        stopAutoPush()
        notifyQueue.clearAllForDisconnect(reason)
        lastAlbumArtKey = null
        currentAlbumArtId = null
        albumArtRequestsInFlight.clear()
        albumArtRequestsCompleted.clear()
        pendingAlbumArt = null
        albumArtScheduleGeneration += 1
        albumArtHandler.removeCallbacksAndMessages(null)
        lastAutoPushSongKey = null
        lastAutoPushPlaying = null
        subscribedDevices.clear()
        connectedDeviceAddresses.clear()
        mtuByAddress.clear()
        recentConnectionCallbacks.clear()
        recentMtuCallbacks.clear()
        statusCharacteristic = null
        mediaFieldDumpExecutor.shutdownNow()
        historyExecutor.shutdownNow()

        try {
            try {
                gattServer?.clearServices()
            } catch (_: Exception) {
                // Some vendor stacks throw while already tearing down; close still follows.
            }
            gattServer?.close()
            logger("[BLE-A] GATT server closed")
        } catch (securityException: SecurityException) {
            logger("[BLE-A] GATT server close failed: missing permission")
        } catch (exception: Exception) {
            logger("[BLE-A] GATT server close failed: ${exception.message}")
        } finally {
            gattServer = null
            started = false
            updateServerState(ServerState.STOPPED)
        }
    }

    fun isStarted(): Boolean = started && gattServer != null

    fun isServerReady(): Boolean = isStarted() && serverState == ServerState.READY

    fun snapshot(): BleGattServerSnapshot {
        val queueSnapshot = notifyQueue.snapshot()
        return BleGattServerSnapshot(
            serverState = serverState,
            started = started,
            connectedDevices = connectedDeviceAddresses.toList(),
            subscribedDevices = subscribedDevices.keys().toList(),
            notificationInFlight = queueSnapshot.notificationInFlight,
            pendingJobs = queueSnapshot.pendingJobCount,
            activeJob = queueSnapshot.activeJobType,
            pendingShortMessages = queueSnapshot.pendingShortMessageCount
        )
    }

    private fun updateServerState(newState: ServerState) {
        val oldState = serverState
        if (oldState == newState) {
            return
        }
        serverState = newState
        logger("[BLE-GATT] state $oldState -> $newState")
    }

    private fun logDisconnectDiagnostics(address: String) {
        val snapshot = notifyQueue.snapshot()
        logger(
            "[BLE-DIAG] disconnected address=$address " +
                "connectedDevices=${connectedDeviceAddresses.joinToString(prefix = "[", postfix = "]")} " +
                "advertisingState=${advertisingStateProvider()} " +
                "notificationInFlight=${snapshot.notificationInFlight} " +
                "pendingJobs=${snapshot.pendingJobCount} " +
                "activeJob=${snapshot.activeJobType} " +
                "pendingShortMessages=${snapshot.pendingShortMessageCount}"
        )
    }

    private fun controlQueueSnapshot(): String {
        val snapshot = notifyQueue.snapshot()
        return "notificationInFlight=${snapshot.notificationInFlight}," +
            "pendingJobs=${snapshot.pendingJobCount}," +
            "activeJobType=${snapshot.activeJobType}," +
            "activeDeviceAddress=${snapshot.activeDeviceAddress}," +
            "pendingShortMessageCount=${snapshot.pendingShortMessageCount}"
    }

    private fun shouldLogCallback(
        timestamps: ConcurrentHashMap<String, Long>,
        key: String
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previous = timestamps.put(key, now)
        return previous == null ||
            now - previous >= CALLBACK_LOG_DEDUP_WINDOW_MS
    }

    private fun handleCommand(command: String, request: JSONObject, seq: String? = null) {
        when (command) {
            "PLAY_PAUSE",
            "NEXT",
            "PREVIOUS",
            "VOLUME_UP",
            "VOLUME_DOWN" -> {
                cancelHistoryTransfersForControl(command)
                mediaCommandExecutor.execute(command, seq)
            }

            "SEEK_TO" -> {
                cancelHistoryTransfersForControl(command)
                val position = request.optLong("position").coerceAtLeast(0L)
                logger("[BLE-A][Seek] position=$position")
                mediaCommandExecutor.seekTo(position, seq)
                logger("[BLE-A][Seek] seekTo called")
                sendPlaybackState()
            }
            "SET_VOLUME" -> {
                cancelHistoryTransfersForControl(command)
                val requestedVolume = request.optInt("volume")
                val volumeStartedAtMs = SystemClock.elapsedRealtime()
                logger("[CTRL-Sony] volume begin seq=${seq ?: "unknown"} cmd=SET_VOLUME")
                logger("[BLE-A][Volume] SET_VOLUME requested=$requestedVolume")
                val volumeState = mediaCommandExecutor.setVolume(requestedVolume)
                val currentVolume = volumeState.optInt("current")
                val maxVolume = volumeState.optInt("max")
                logger(
                    "[BLE-A][Volume] clamped=${requestedVolume.coerceIn(0, maxVolume)} " +
                        "max=$maxVolume"
                )
                logger("[BLE-A][Volume] setStreamVolume called")
                logger("[BLE-A][Volume] after=$currentVolume")
                sendStatusMessage(volumeState.toString())
                logger("[BLE-A][Volume] notify volumeState")
                logger(
                    "[CTRL-Sony] volume end seq=${seq ?: "unknown"} " +
                        "cmd=SET_VOLUME costMs=${SystemClock.elapsedRealtime() - volumeStartedAtMs}"
                )
            }
            "GET_PLAYBACK_STATE" -> {
                sendPlaybackState(includeAlbumArt = true)
            }
            "GET_VOLUME" -> mediaCommandExecutor.sendVolumeState()
            "GET_LOGS" -> {
                val limit = request.optInt("limit", DEFAULT_LOG_LIMIT)
                    .coerceIn(0, MAX_LOG_LIMIT)
                sendRemoteLogs(limit)
            }
            "ALBUM_ART_SKIP" -> handleAlbumArtSkip(request)
            "ALBUM_ART_REQUEST" -> handleAlbumArtRequest(request)
            "CLIENT_CAPABILITIES" -> handleClientCapabilities(request)
            "DUMP_MEDIA_FIELDS" -> sendMediaFieldDump()
            "GET_FULL_LYRICS" -> sendFullLyrics(request)
            "GET_PLAY_HISTORY_PAGE" -> sendPlayHistoryPage(request)
            "GET_PLAY_HISTORY_SINCE" -> sendPlayHistorySince(request)
            "GET_PLAY_STATS" -> sendPlayStats(request)
            else -> logger("[BLE-A] unknown command: $command")
        }
    }

    private fun cancelHistoryTransfersForControl(command: String) {
        if (notifyQueue.hasJobTypeActiveOrQueued(PLAY_HISTORY_JOB_TYPE) ||
            notifyQueue.hasJobTypeActiveOrQueued(PLAY_STATS_JOB_TYPE)
        ) {
            logger("[HistoryBLE] cancelled reason=control command cmd=$command")
            notifyQueue.cancelJobTypes(
                setOf(PLAY_HISTORY_JOB_TYPE, PLAY_STATS_JOB_TYPE),
                "control command"
            )
        }
    }

    private fun handleClientCapabilities(request: JSONObject) {
        clientSupportsBinaryAlbumArt = request.optBoolean("albumArtBinary", false)
        logger("[BLE-A] client capability albumArtBinary=$clientSupportsBinaryAlbumArt")
    }

    private fun sendPlaybackState(includeAlbumArt: Boolean = false) {
        val source = playbackStateReader.readPlaybackState()
        onPlaybackUiState(source)
        if (includeAlbumArt) {
            sendTrackInfo(source)
        }
        sendCompactPlaybackState(source)
        if (includeAlbumArt) {
            sendAlbumArtIfSongChanged(source)
        }
    }

    fun currentTrackSnapshot(): CurrentTrackSnapshot? {
        return playbackStateReader.currentTrackSnapshot()
    }

    fun handleIncrementalLyricsReady(ready: IncrementalLyricsReady) {
        if (!ready.matchedCurrentTrack) {
            return
        }
        if (!playbackStateReader.applyIncrementalLyrics(ready)) {
            return
        }
        logger(
            "[Lyric] incremental playbackState refresh requested " +
                "trackId=${ready.currentTrack?.trackId.orEmpty()}"
        )
        sendPlaybackState()
    }

    @SuppressLint("MissingPermission")
    private fun sendStatusMessage(message: String): Boolean {
        if (gattServer == null || statusCharacteristic == null) {
            logger("[BLE-A] status notify skipped: GATT server unavailable")
            return false
        }

        val device = subscribedDevices.values.firstOrNull()
        if (device == null) {
            logger("[BLE-A] status notify skipped: no iPhone subscriber")
            return false
        }

        val value = message.toByteArray(Charsets.UTF_8)
        val maximumPayload = maximumPayloadFor(device)
        if (value.size > maximumPayload) {
            logger(
                "[BLE-A] status notify skipped: payload=${value.size} " +
                    "max=$maximumPayload type=${readMessageType(message)}"
            )
            return false
        }

        val messageType = readMessageType(message)
        if ((messageType == "playbackState" ||
                messageType == "trackInfo" ||
                messageType == "volumeState") &&
            notifyQueue.hasLongJobActiveOrQueued()
        ) {
            notifyQueue.setLatestInterleavedShort(
                device = device,
                type = messageType,
                value = value,
                delayAfterMs = SHORT_MESSAGE_DELAY_MS
            )
            return true
        }

        notifyQueue.enqueueShort(
            device = device,
            type = messageType,
            value = value,
            delayAfterMs = SHORT_MESSAGE_DELAY_MS
        )
        return true
    }

    @Synchronized
    private fun startAutoPush() {
        if (autoPushExecutor != null) {
            return
        }

        logger("[BLE-A][AutoPush] started")
        autoPushExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "BlePlaybackStateAutoPushThread")
        }.also { executor ->
            executor.scheduleAtFixedRate(
                {
                    pushPlaybackStateAutomatically()
                },
                0L,
                AUTO_PUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    @Synchronized
    private fun stopAutoPushIfUnused() {
        if (subscribedDevices.isEmpty()) {
            stopAutoPush()
        }
    }

    @Synchronized
    private fun stopAutoPush() {
        val executor = autoPushExecutor ?: return
        executor.shutdownNow()
        autoPushExecutor = null
        logger("[BLE-A][AutoPush] stopped")
    }

    private fun pushPlaybackStateAutomatically() {
        if (subscribedDevices.isEmpty()) {
            stopAutoPushIfUnused()
            return
        }

        try {
            val source = playbackStateReader.readPlaybackState()
            onPlaybackUiState(source)
            val wasPlaying = lastAutoPushPlaying
            val playing = source.optBoolean("playing")
            val songChanged = logAutoPushStateChanges(source)
            if (songChanged) {
                sendTrackInfo(source)
            }
            if (LogConfig.DEBUG_VERBOSE_LOG) {
                verboseLogger(
                    "[BLE-A][AutoPush] playbackState " +
                        "position=${source.optLong("position")} " +
                        "duration=${source.optLong("duration")}"
                )
            }
            val shouldSendPlaybackState =
                playing || songChanged || wasPlaying != false
            val result = if (shouldSendPlaybackState) {
                sendCompactPlaybackState(source)
            } else {
                true
            }
            if (songChanged) {
                sendAlbumArtIfSongChanged(source)
            }
            if (LogConfig.DEBUG_VERBOSE_LOG) {
                verboseLogger("[BLE-A][AutoPush] notify result=$result")
            }
        } catch (exception: Exception) {
            logger("[BLE-A][AutoPush] error=${exception.message}")
        }
    }

    private fun logAutoPushStateChanges(state: JSONObject): Boolean {
        val songKey = buildAlbumArtCacheKey(state)
        val title = state.optString("title")
        val playing = state.optBoolean("playing")
        val songChanged = songKey != lastAutoPushSongKey

        if (songChanged) {
            val oldSongKey = lastAutoPushSongKey.orEmpty()
            lastAutoPushSongKey = songKey
            logger("[SongChange] detected title=$title")
            logger("[SongChange] old=$oldSongKey")
            logger("[SongChange] new=$songKey")
            logger("[BLE-A][AutoPush] song changed title=$title")
        }
        if (playing != lastAutoPushPlaying) {
            lastAutoPushPlaying = playing
            logger("[BLE-A][AutoPush] play state changed playing=$playing")
        }
        return songChanged
    }

    private fun sendCompactPlaybackState(source: JSONObject): Boolean {
        val compactState = JSONObject()
            .put("type", "playbackState")
            .put("playing", source.optBoolean("playing"))
            .put("position", source.optLong("position"))
            .put("duration", source.optLong("duration"))
        val lyric = source.optString("lyric").take(MAX_LYRIC_TEXT_LENGTH)
        compactState.put("lyric", lyric)
        val device = subscribedDevices.values.firstOrNull()
        if (device != null) {
            val maximumPayload = maximumPayloadFor(device)
            var fittedLyric = lyric
            while (fittedLyric.isNotEmpty() &&
                compactState.toString().toByteArray(Charsets.UTF_8).size >
                maximumPayload
            ) {
                fittedLyric = fittedLyric.dropLast(1)
                compactState.put("lyric", fittedLyric)
            }
            if (compactState.toString().toByteArray(Charsets.UTF_8).size >
                maximumPayload
            ) {
                compactState.remove("lyric")
            }
        }
        return sendStatusMessage(compactState.toString())
    }

    private fun sendTrackInfo(source: JSONObject) {
        val device = subscribedDevices.values.firstOrNull() ?: run {
            logger("[TrackInfo] send skipped: no iPhone subscriber")
            return
        }
        val maximumPayload = maximumPayloadFor(device)
        val trackInfo = buildFittingTrackInfo(source, maximumPayload)
        if (trackInfo == null) {
            logger("[TrackInfo] send failed: payload too large")
            return
        }
        logger("[TrackInfo] send")
        if (notifyQueue.hasLongJobActiveOrQueued()) {
            logger("[TrackInfo] latest updated title=${source.optString("title")}")
        }
        sendStatusMessage(trackInfo.toString())
    }

    private fun buildFittingTrackInfo(
        source: JSONObject,
        maximumPayload: Int
    ): JSONObject? {
        val title = source.optString("title")
        val artist = source.optString("artist")
        val album = source.optString("album")
        val trackId = buildAlbumArtProtocolId(source)
        val candidates = listOf(
            TrackInfoLimit(30, 30, 20, includeAlbum = true),
            TrackInfoLimit(30, 30, 0, includeAlbum = false),
            TrackInfoLimit(20, 20, 0, includeAlbum = false)
        )
        candidates.forEach { limit ->
            val objectValue = JSONObject()
                .put("type", "trackInfo")
                .put("title", title.take(limit.titleLength))
                .put("artist", artist.take(limit.artistLength))
                .put("trackId", trackId)
            if (limit.includeAlbum) {
                objectValue.put("album", album.take(limit.albumLength))
            }
            if (objectValue.toString().toByteArray(Charsets.UTF_8).size <=
                maximumPayload
            ) {
                return objectValue
            }
        }
        return null
    }

    private fun buildTrackInfoPackets(
        trackInfoBytes: ByteArray,
        maximumPayload: Int
    ): List<BleNotifyQueue.Packet>? {
        for (rawChunkSize in MAX_TRACK_INFO_CHUNK_BYTES downTo 1) {
            val chunkCount =
                (trackInfoBytes.size + rawChunkSize - 1) / rawChunkSize
            val start = JSONObject()
                .put("type", "trackInfoStart")
                .put("size", trackInfoBytes.size)
                .put("chunks", chunkCount)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val end = JSONObject()
                .put("type", "trackInfoEnd")
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (start.size > maximumPayload || end.size > maximumPayload) {
                continue
            }

            val packets = mutableListOf(
                BleNotifyQueue.Packet(
                    type = "trackInfoStart",
                    value = start,
                    delayAfterMs = SHORT_MESSAGE_DELAY_MS
                )
            )
            var allChunksFit = true
            for (index in 0 until chunkCount) {
                val from = index * rawChunkSize
                val to = minOf(from + rawChunkSize, trackInfoBytes.size)
                val chunk = JSONObject()
                    .put("type", "trackInfoChunk")
                    .put("index", index)
                    .put(
                        "data",
                        Base64.encodeToString(
                            trackInfoBytes.copyOfRange(from, to),
                            Base64.NO_WRAP
                        )
                    )
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                if (chunk.size > maximumPayload) {
                    allChunksFit = false
                    break
                }
                packets += BleNotifyQueue.Packet(
                    type = "trackInfoChunk",
                    value = chunk,
                    delayAfterMs = SHORT_MESSAGE_DELAY_MS
                )
            }
            if (!allChunksFit) {
                continue
            }
            packets += BleNotifyQueue.Packet(
                type = "trackInfoEnd",
                value = end,
                delayAfterMs = SHORT_MESSAGE_DELAY_MS
            )
            return packets
        }
        return null
    }

    @Synchronized
    private fun sendAlbumArtIfSongChanged(playbackState: JSONObject) {
        if (!ALBUM_ART_ENABLED) {
            logger("[AlbumArt] disabled for diagnosis")
            return
        }
        val cacheKey = buildAlbumArtCacheKey(playbackState)
        val protocolId = buildAlbumArtProtocolId(playbackState)
        if (cacheKey == lastAlbumArtKey) {
            return
        }
        lastAlbumArtKey = cacheKey
        currentAlbumArtId = protocolId

        val pending = PendingAlbumArt(
            cacheKey = cacheKey,
            protocolId = protocolId,
            playbackState = JSONObject(playbackState.toString())
        )
        val generation = ++albumArtScheduleGeneration
        logger("[AlbumArt] scheduled after cooldown id=$protocolId")
        albumArtHandler.postDelayed(
            {
                synchronized(this) {
                    if (generation == albumArtScheduleGeneration) {
                        enqueueAlbumArtOfferOrPending(pending)
                    }
                }
            },
            ALBUM_ART_COOLDOWN_AFTER_TRACK_CHANGE_MS
        )
    }

    private fun enqueueAlbumArtOfferOrPending(pending: PendingAlbumArt) {
        if (!ALBUM_ART_ENABLED) {
            return
        }
        if (notifyQueue.hasJobTypeActiveOrQueued(ALBUM_ART_JOB_TYPE) ||
            albumArtRequestsInFlight.isNotEmpty()
        ) {
            pendingAlbumArt = pending
            logger("[AlbumArt] pending new id=${pending.protocolId}")
            return
        }

        val cacheKey = pending.cacheKey
        val protocolId = pending.protocolId
        logAlbumArtDebugIdentity(pending.playbackState, protocolId)
        val device = subscribedDevices.values.firstOrNull() ?: run {
            logger("[AlbumArt][BLE] no iPhone subscriber")
            return
        }
        currentAlbumArtId = protocolId
        albumArtRequestsInFlight.removeIf {
            !it.startsWith("$protocolId|")
        }
        albumArtRequestsCompleted.removeIf {
            !it.startsWith("$protocolId|")
        }

        val offer = JSONObject()
            .put("type", "albumArtOffer")
            .put("id", protocolId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (offer.size > maximumPayloadFor(device)) {
            logger("[AlbumArt] offer failed id=$protocolId reason=MTU")
            return
        }
        lastAlbumArtKey = cacheKey
        logger("[AlbumArt] offer id=$protocolId")
        logger("[AlbumArtDebug] offer sent id=$protocolId")
        notifyQueue.enqueueShort(
            device = device,
            type = "albumArtOffer",
            value = offer,
            delayAfterMs = SHORT_MESSAGE_DELAY_MS
        )
    }

    @Synchronized
    private fun sendPendingAlbumArtIfAny() {
        val pending = pendingAlbumArt ?: return
        pendingAlbumArt = null
        logger("[AlbumArt] send pending id=${pending.protocolId}")
        enqueueAlbumArtOfferOrPending(pending)
    }

    @Synchronized
    private fun handleAlbumArtSkip(request: JSONObject) {
        val protocolId = request.optString("id")
        if (protocolId.isBlank() || protocolId != currentAlbumArtId) {
            logger("[AlbumArt] skip ignored stale id=$protocolId")
            return
        }
        logger("[AlbumArt] cache confirmed id=$protocolId")
    }

    @Synchronized
    private fun handleAlbumArtRequest(request: JSONObject) {
        val protocolId = request.optString("id")
        val quality = AlbumArtQuality.fromWireValue(
            request.optString("quality")
        )
        if (protocolId.isBlank() || protocolId != currentAlbumArtId) {
            logger("[AlbumArt] request ignored stale id=$protocolId")
            return
        }
        if (quality == null) {
            logger("[AlbumArt] request ignored invalid quality")
            return
        }
        if (!ALBUM_ART_ENABLED) {
            logger("[AlbumArt] request ignored disabled")
            return
        }
        if (quality == AlbumArtQuality.FULL) {
            logger("[AlbumArt] full ignored id=$protocolId")
            return
        }
        if (quality == AlbumArtQuality.HQ) {
            if (!clientSupportsBinaryAlbumArt) {
                logger("[AlbumArtHQ] request skipped reason=binary unsupported")
                return
            }
            if (notifyQueue.hasJobTypeActiveOrQueued(FULL_LYRICS_JOB_TYPE) ||
                notifyQueue.hasJobTypeActiveOrQueued(REMOTE_LOG_JOB_TYPE) ||
                notifyQueue.hasJobTypeActiveOrQueued(MEDIA_FIELD_DUMP_JOB_TYPE)
            ) {
                logger("[AlbumArtHQ] request skipped reason=long job active")
                return
            }
        }
        val requestKey = "$protocolId|${quality.wireValue}"
        if (albumArtRequestsCompleted.contains(requestKey)) {
            logger("[AlbumArt] skip duplicate sending id=$protocolId")
            return
        }
        if (!albumArtRequestsInFlight.add(requestKey)) {
            logger("[AlbumArt] skip duplicate sending id=$protocolId")
            return
        }
        logger(
            "[AlbumArt] request id=$protocolId quality=${quality.wireValue}"
        )
        if (quality == AlbumArtQuality.HQ) {
            logger("[AlbumArtHQ] request accepted id=$protocolId")
        }
        logger("[AlbumArtDebug] id=$protocolId")

        val device = subscribedDevices.values.firstOrNull()
        if (device == null) {
            albumArtRequestsInFlight.remove(requestKey)
            logger("[AlbumArt] request failed: no iPhone subscriber")
            logger("[AlbumArtDebug] unavailable reason=no iPhone subscriber")
            return
        }
        val albumArt = albumArtTestManager.readCurrentNotificationAlbumArt()
        if (albumArt == null) {
            albumArtRequestsInFlight.remove(requestKey)
            sendAlbumArtUnavailable(
                device = device,
                protocolId = protocolId,
                quality = quality,
                reason = "no notification largeIcon"
            )
            return
        }

        val bitmap = albumArt.bitmap
        logger("[AlbumArt][BLE] source=${albumArt.source}")
        logger("[AlbumArtDebug] source ${albumArt.source} exists=true")
        logger(
            "[AlbumArt][BLE] original width=${bitmap.width} height=${bitmap.height}"
        )
        logger(
            "[AlbumArtDebug] bitmap width=${bitmap.width} height=${bitmap.height}"
        )
        if (isLikelyPlaceholderAlbumArt(bitmap)) {
            albumArtRequestsInFlight.remove(requestKey)
            sendAlbumArtUnavailable(
                device = device,
                protocolId = protocolId,
                quality = quality,
                reason = "placeholder album art"
            )
            return
        }
        val useBinaryAlbumArt =
            (quality == AlbumArtQuality.PREVIEW || quality == AlbumArtQuality.HQ) &&
                clientSupportsBinaryAlbumArt
        val maximumPayload = if (useBinaryAlbumArt) {
            albumArtMaximumPayloadFor(device)
        } else {
            minOf(albumArtMaximumPayloadFor(device), MAX_ALBUM_JSON_BYTES)
        }
        val preparation = prepareAlbumArt(
            bitmap = bitmap,
            deviceMaximumPayload = maximumPayload,
            protocolId = protocolId,
            quality = quality,
            binaryTransport = useBinaryAlbumArt
        )
        val preparedAlbumArt = preparation.prepared
        if (preparedAlbumArt == null) {
            albumArtRequestsInFlight.remove(requestKey)
            if (preparation.compressionFailed) {
                logger("[AlbumArt][BLE] unavailable")
                sendAlbumArtUnavailable(
                    device = device,
                    protocolId = protocolId,
                    quality = quality,
                    reason = "compress failed"
                )
            } else {
                logger("[AlbumArtDebug] unavailable reason=too large")
            }
            return
        }

        val compressedAlbumArt = preparedAlbumArt.compressed
        val albumPackets = preparedAlbumArt.packets
        val totalChunks = albumPackets.totalChunks
        if (quality == AlbumArtQuality.HQ) {
            logger(
                "[AlbumArtHQ] selected scale=${compressedAlbumArt.width} " +
                    "quality=${compressedAlbumArt.quality} " +
                    "bytes=${compressedAlbumArt.bytes.size} chunks=$totalChunks"
            )
        } else if (useBinaryAlbumArt) {
            logger(
                "[AlbumArtBinary] selected scale=${compressedAlbumArt.width} " +
                    "quality=${compressedAlbumArt.quality} " +
                    "bytes=${compressedAlbumArt.bytes.size} chunks=$totalChunks"
            )
        } else {
            logger(
                "[AlbumArt][BLE] selected quality=${quality.wireValue} " +
                    "scale=${compressedAlbumArt.width} " +
                    "quality=${compressedAlbumArt.quality} " +
                    "bytes=${compressedAlbumArt.bytes.size} chunks=$totalChunks"
            )
        }

        val startAt = SystemClock.elapsedRealtime()
        if (quality == AlbumArtQuality.HQ) {
            logger("[AlbumArtHQ] send start chunks=$totalChunks")
        } else if (useBinaryAlbumArt) {
            logger("[AlbumArtBinary] send start chunks=$totalChunks")
        } else {
            logger(
                "[AlbumArt] send start id=$protocolId " +
                    "quality=${quality.wireValue} chunks=$totalChunks"
            )
        }
        notifyQueue.enqueueLongJob(
            type = ALBUM_ART_JOB_TYPE,
            device = device,
            packets = albumPackets.packets,
            maxSendDurationMs = when (quality) {
                AlbumArtQuality.PREVIEW -> ALBUM_ART_PREVIEW_MAX_SEND_MS
                AlbumArtQuality.HQ -> ALBUM_ART_HQ_MAX_SEND_MS
                AlbumArtQuality.FULL -> ALBUM_ART_FULL_MAX_SEND_MS
            },
            shouldCancel = {
                quality == AlbumArtQuality.HQ && currentAlbumArtId != protocolId
            },
            onComplete = {
                albumArtRequestsInFlight.remove(requestKey)
                albumArtRequestsCompleted.add(requestKey)
                val costMs = SystemClock.elapsedRealtime() - startAt
                if (quality == AlbumArtQuality.HQ) {
                    logger("[AlbumArtHQ] send end costMs=$costMs")
                } else if (useBinaryAlbumArt) {
                    val avg = if (totalChunks > 0) {
                        costMs.toDouble() / totalChunks.toDouble()
                    } else {
                        0.0
                    }
                    logger(
                        "[AlbumArtBinary] send end costMs=$costMs " +
                            "avgChunkIntervalMs=${"%.1f".format(avg)}"
                    )
                }
                logger(
                    "[AlbumArt] send end id=$protocolId " +
                        "costMs=$costMs"
                )
                sendPendingAlbumArtIfAny()
            },
            onFailure = {
                albumArtRequestsInFlight.remove(requestKey)
                if (quality == AlbumArtQuality.HQ && currentAlbumArtId != protocolId) {
                    logger("[AlbumArtHQ] cancelled reason=track changed")
                }
                sendPendingAlbumArtIfAny()
            }
        )
    }

    private fun prepareAlbumArt(
        bitmap: Bitmap,
        deviceMaximumPayload: Int,
        protocolId: String,
        quality: AlbumArtQuality,
        binaryTransport: Boolean
    ): AlbumArtPreparation {
        val attempts = when (quality) {
            AlbumArtQuality.PREVIEW -> listOf(
                CompressionAttempt(192, 192, 55, PREVIEW_MAX_JPEG_BYTES),
                CompressionAttempt(176, 176, 52, PREVIEW_MAX_JPEG_BYTES),
                CompressionAttempt(160, 160, 50, PREVIEW_MAX_JPEG_BYTES),
                CompressionAttempt(144, 144, 45, PREVIEW_MAX_JPEG_BYTES),
                CompressionAttempt(128, 128, 45, PREVIEW_MAX_JPEG_BYTES),
                CompressionAttempt(112, 112, 40, PREVIEW_MAX_JPEG_BYTES),
                CompressionAttempt(96, 96, 35, PREVIEW_MAX_JPEG_BYTES)
            )
            AlbumArtQuality.HQ -> listOf(
                CompressionAttempt(280, 280, 85, HQ_MAX_JPEG_BYTES),
                CompressionAttempt(256, 256, 82, HQ_MAX_JPEG_BYTES),
                CompressionAttempt(240, 240, 80, HQ_MAX_JPEG_BYTES),
                CompressionAttempt(220, 220, 78, HQ_MAX_JPEG_BYTES),
                CompressionAttempt(200, 200, 75, HQ_MAX_JPEG_BYTES)
            )
            AlbumArtQuality.FULL -> listOf(
                CompressionAttempt(160, 160, 55, FULL_MAX_JPEG_BYTES),
                CompressionAttempt(144, 144, 50, FULL_MAX_JPEG_BYTES),
                CompressionAttempt(128, 128, 45, FULL_MAX_JPEG_BYTES),
                CompressionAttempt(112, 112, 42, FULL_MAX_JPEG_BYTES)
            )
        }
        val maximumChunks = when (quality) {
            AlbumArtQuality.PREVIEW -> ALBUM_ART_PREVIEW_MAX_CHUNKS
            AlbumArtQuality.HQ -> ALBUM_ART_HQ_MAX_CHUNKS
            AlbumArtQuality.FULL -> ALBUM_ART_FULL_MAX_CHUNKS
        }

        attempts.forEachIndexed { index, attempt ->
            if (index > 0) {
                if (quality == AlbumArtQuality.PREVIEW || quality == AlbumArtQuality.HQ) {
                    val prefix = if (binaryTransport) {
                        if (quality == AlbumArtQuality.HQ) "[AlbumArtHQ]" else "[AlbumArtBinary]"
                    } else {
                        "[AlbumArt]"
                    }
                    logger("$prefix fallback scale=${attempt.width}")
                } else {
                    logger(
                        "[AlbumArt][BLE] fallback scale=${attempt.width} " +
                            "quality=${attempt.quality}"
                    )
                }
            }

            val compressed = compressAlbumArt(bitmap, attempt)
            if (compressed == null) {
                if (quality == AlbumArtQuality.PREVIEW || quality == AlbumArtQuality.HQ) {
                    val prefix = if (binaryTransport) {
                        if (quality == AlbumArtQuality.HQ) "[AlbumArtHQ]" else "[AlbumArtBinary]"
                    } else {
                        "[AlbumArt]"
                    }
                    logger(
                        "$prefix candidate scale=${attempt.width} " +
                            "quality=${attempt.quality} bytes=0 chunks=0 " +
                            "accepted=false reason=compress_failed"
                    )
                    return@forEachIndexed
                }
                return AlbumArtPreparation(compressionFailed = true)
            }
            val packets = if (binaryTransport) {
                buildBinaryAlbumArtPackets(
                    deviceMaximumPayload = deviceMaximumPayload,
                    protocolId = protocolId,
                    quality = quality,
                    bytes = compressed.bytes,
                    maximumChunks = maximumChunks
                )
            } else {
                buildAlbumArtPackets(
                    deviceMaximumPayload = deviceMaximumPayload,
                    protocolId = protocolId,
                    quality = quality,
                    bytes = compressed.bytes,
                    maximumChunks = maximumChunks
                )
            }
            if (packets == null) {
                val prefix = if (binaryTransport && quality == AlbumArtQuality.HQ) {
                    "[AlbumArtHQ]"
                } else if (quality == AlbumArtQuality.PREVIEW && binaryTransport) {
                    "[AlbumArtBinary]"
                } else {
                    "[AlbumArt][BLE]"
                }
                logger(
                    "$prefix candidate scale=${attempt.width} " +
                        "quality=${attempt.quality} bytes=${compressed.bytes.size} " +
                        "chunks=0 accepted=false reason=chunks_exceed"
                )
                return@forEachIndexed
            }
            if (quality == AlbumArtQuality.PREVIEW || quality == AlbumArtQuality.HQ) {
                val prefix = if (binaryTransport) {
                    if (quality == AlbumArtQuality.HQ) "[AlbumArtHQ]" else "[AlbumArtBinary]"
                } else {
                    "[AlbumArt]"
                }
                val accepted = compressed.bytes.size <= attempt.maximumBytes &&
                    packets.totalChunks <= maximumChunks
                val rejectReason = when {
                    compressed.bytes.size > attempt.maximumBytes -> "bytes_exceed"
                    packets.totalChunks > maximumChunks -> "chunks_exceed"
                    else -> ""
                }
                logger(
                    "$prefix candidate scale=${attempt.width} " +
                        "quality=${attempt.quality} bytes=${compressed.bytes.size} " +
                        "chunks=${packets.totalChunks} accepted=$accepted" +
                        if (accepted) "" else " reason=$rejectReason"
                )
            }

            if (compressed.bytes.size <= attempt.maximumBytes &&
                packets.totalChunks <= maximumChunks
            ) {
                if (quality == AlbumArtQuality.PREVIEW || quality == AlbumArtQuality.HQ) {
                    val prefix = if (binaryTransport) {
                        if (quality == AlbumArtQuality.HQ) "[AlbumArtHQ]" else "[AlbumArtBinary]"
                    } else {
                        "[AlbumArt]"
                    }
                    logger(
                        "$prefix selected scale=${attempt.width} " +
                            "quality=${attempt.quality} bytes=${compressed.bytes.size} " +
                            "chunks=${packets.totalChunks}"
                    )
                } else {
                    logger(
                        "[AlbumArt] ${quality.wireValue} selected " +
                            "scale=${attempt.width} quality=${attempt.quality}"
                    )
                }
                return AlbumArtPreparation(
                    prepared = PreparedAlbumArt(
                        compressed = compressed,
                        packets = packets
                    )
                )
            }

            logger(
                "[AlbumArt][BLE] candidate scale=${attempt.width} " +
                    "quality=${attempt.quality} bytes=${compressed.bytes.size} " +
                    "chunks=${packets.totalChunks} exceeds " +
                    "${attempt.maximumBytes} bytes or $maximumChunks chunks"
            )
        }
        if (quality == AlbumArtQuality.PREVIEW) {
            logger("[AlbumArtBinary] no acceptable candidate")
        } else if (quality == AlbumArtQuality.HQ) {
            logger("[AlbumArtHQ] unavailable reason=no acceptable compressed candidate")
        } else {
            logger("[AlbumArt] skip because too large chunks")
        }
        return AlbumArtPreparation()
    }

    private fun compressAlbumArt(
        bitmap: Bitmap,
        attempt: CompressionAttempt
    ): CompressedAlbumArt? {
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            attempt.width,
            attempt.height,
            true
        )
        val output = ByteArrayOutputStream()
        val compressed = scaled.compress(
            Bitmap.CompressFormat.JPEG,
            attempt.quality,
            output
        )
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        if (!compressed) {
            return null
        }

        val bytes = output.toByteArray()
        if (bytes.isEmpty()) {
            return null
        }
        return CompressedAlbumArt(
            bytes = bytes,
            width = scaled.width,
            height = scaled.height,
            quality = attempt.quality
        )
    }

    private fun buildAlbumArtPackets(
        deviceMaximumPayload: Int,
        protocolId: String,
        quality: AlbumArtQuality,
        bytes: ByteArray,
        maximumChunks: Int
    ): AlbumArtPackets? {
        val payloadLimit = minOf(
            deviceMaximumPayload,
            MAX_ALBUM_JSON_BYTES
        )
        if (payloadLimit <= 0) {
            return null
        }

        for (rawChunkSize in MAX_ALBUM_CHUNK_RAW_BYTES downTo 1) {
            val totalChunks =
                (bytes.size + rawChunkSize - 1) / rawChunkSize
            if (totalChunks > maximumChunks) {
                return null
            }
            val packets = mutableListOf<BleNotifyQueue.Packet>()
            val start = JSONObject()
                .put("type", "albumArtStart")
                .put("id", protocolId)
                .put("quality", quality.wireValue)
                .put("size", bytes.size)
                .put("chunks", totalChunks)
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (start.size > payloadLimit) {
                continue
            }
            packets += albumArtPacket("albumArtStart", start)

            var allChunksFit = true
            for (index in 0 until totalChunks) {
                val from = index * rawChunkSize
                val to = minOf(from + rawChunkSize, bytes.size)
                val rawChunk = bytes.copyOfRange(from, to)
                val chunk = JSONObject()
                    .put("type", "albumArtChunk")
                    .put("id", protocolId)
                    .put("quality", quality.wireValue)
                    .put("index", index)
                    .put(
                        "data",
                        Base64.encodeToString(rawChunk, Base64.NO_WRAP)
                    )
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                if (chunk.size > payloadLimit) {
                    allChunksFit = false
                    break
                }
                packets += albumArtPacket("albumArtChunk", chunk)
            }
            if (!allChunksFit) {
                continue
            }

            val end = JSONObject()
                .put("type", "albumArtEnd")
                .put("id", protocolId)
                .put("quality", quality.wireValue)
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (end.size > payloadLimit) {
                continue
            }
            packets += albumArtPacket("albumArtEnd", end)
            return AlbumArtPackets(
                totalChunks = totalChunks,
                packets = packets
            )
        }
        return null
    }

    private fun buildBinaryAlbumArtPackets(
        deviceMaximumPayload: Int,
        protocolId: String,
        quality: AlbumArtQuality,
        bytes: ByteArray,
        maximumChunks: Int
    ): AlbumArtPackets? {
        val maxPayload = deviceMaximumPayload
        val chunkDataSize = maxPayload - ALBUM_ART_BINARY_HEADER_BYTES
        logger(
            "[AlbumArtBinary] mtu=${maxPayload + ATT_HEADER_SIZE} " +
                "maxPayload=$maxPayload chunkDataSize=$chunkDataSize"
        )
        if (chunkDataSize <= 0) {
            return null
        }
        val totalChunks = (bytes.size + chunkDataSize - 1) / chunkDataSize
        if (totalChunks > maximumChunks || totalChunks > UShort.MAX_VALUE.toInt()) {
            return null
        }
        val start = JSONObject()
            .put("type", "albumArtBinaryStart")
            .put("id", protocolId)
            .put("quality", quality.wireValue)
            .put("size", bytes.size)
            .put("chunks", totalChunks)
            .put("format", "jpg")
            .toString()
            .toByteArray(Charsets.UTF_8)
        val end = JSONObject()
            .put("type", "albumArtBinaryEnd")
            .put("id", protocolId)
            .put("quality", quality.wireValue)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (start.size > maxPayload || end.size > maxPayload) {
            return null
        }

        val packets = mutableListOf<BleNotifyQueue.Packet>()
        packets += BleNotifyQueue.Packet(
            type = "albumArtBinaryStart",
            value = start,
            delayAfterMs = ALBUM_ART_BINARY_NOTIFICATION_DELAY_MS
        )
        val qualityCode = when (quality) {
            AlbumArtQuality.PREVIEW -> 1
            AlbumArtQuality.HQ -> 3
            AlbumArtQuality.FULL -> 2
        }
        for (index in 0 until totalChunks) {
            val from = index * chunkDataSize
            val to = minOf(from + chunkDataSize, bytes.size)
            val rawSize = to - from
            val packet = ByteArray(ALBUM_ART_BINARY_HEADER_BYTES + rawSize)
            packet[0] = ALBUM_ART_BINARY_MAGIC.toByte()
            packet[1] = qualityCode.toByte()
            packet[2] = ((index ushr 8) and 0xff).toByte()
            packet[3] = (index and 0xff).toByte()
            packet[4] = ((totalChunks ushr 8) and 0xff).toByte()
            packet[5] = (totalChunks and 0xff).toByte()
            bytes.copyInto(
                destination = packet,
                destinationOffset = ALBUM_ART_BINARY_HEADER_BYTES,
                startIndex = from,
                endIndex = to
            )
            packets += BleNotifyQueue.Packet(
                type = "albumArtBinaryChunk",
                value = packet,
                delayAfterMs = ALBUM_ART_BINARY_NOTIFICATION_DELAY_MS
            )
        }
        packets += BleNotifyQueue.Packet(
            type = "albumArtBinaryEnd",
            value = end,
            delayAfterMs = ALBUM_ART_BINARY_NOTIFICATION_DELAY_MS
        )
        return AlbumArtPackets(
            totalChunks = totalChunks,
            packets = packets
        )
    }

    private fun albumArtPacket(
        type: String,
        value: ByteArray
    ): BleNotifyQueue.Packet {
        return BleNotifyQueue.Packet(
            type = type,
            value = value,
            delayAfterMs = ALBUM_ART_NOTIFICATION_DELAY_MS
        )
    }

    private fun sendAlbumArtUnavailable(
        device: BluetoothDevice,
        protocolId: String,
        quality: AlbumArtQuality,
        reason: String
    ) {
        val value = JSONObject()
            .put("type", "albumArtUnavailable")
            .put("id", protocolId)
            .put("quality", quality.wireValue)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (value.size > maximumPayloadFor(device)) {
            logger("[AlbumArt][BLE] unavailable message exceeds MTU")
            logger("[AlbumArtDebug] unavailable reason=unavailable message exceeds MTU")
            return
        }
        logger("[AlbumArt][BLE] unavailable")
        logger("[AlbumArtDebug] unavailable reason=$reason")
        notifyQueue.enqueueShort(
            device = device,
            type = "albumArtUnavailable",
            value = value,
            delayAfterMs = SHORT_MESSAGE_DELAY_MS
        )
    }

    private fun buildAlbumArtCacheKey(playbackState: JSONObject): String {
        return listOf(
            playbackState.optString("title"),
            playbackState.optString("artist"),
            playbackState.optString("album")
        ).joinToString("|")
    }

    private fun buildAlbumArtProtocolId(playbackState: JSONObject): String {
        val source = listOf(
            playbackState.optString("title"),
            playbackState.optString("artist"),
            playbackState.optString("album")
        ).joinToString("|").ifBlank { "unknown" }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .take(ALBUM_ART_ID_HASH_BYTES)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun logAlbumArtDebugIdentity(
        playbackState: JSONObject,
        protocolId: String
    ) {
        val title = playbackState.optString("title")
        val artist = playbackState.optString("artist")
        val album = playbackState.optString("album")
        logger(
            "[AlbumArtDebug] song title=$title artist=$artist album=$album"
        )
        logger(
            "[AlbumArtDebug] id source title=$title artist=$artist album=$album"
        )
        logger("[AlbumArtDebug] id=$protocolId")
    }

    private fun isLikelyPlaceholderAlbumArt(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        val sampleWidth = minOf(24, bitmap.width)
        val sampleHeight = minOf(24, bitmap.height)
        val sampled = if (sampleWidth == bitmap.width &&
            sampleHeight == bitmap.height
        ) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        }
        return try {
            val pixels = IntArray(sampleWidth * sampleHeight)
            sampled.getPixels(
                pixels,
                0,
                sampleWidth,
                0,
                0,
                sampleWidth,
                sampleHeight
            )
            val colorBuckets = HashSet<Int>()
            var colorfulPixels = 0
            var visiblePixels = 0
            pixels.forEach { pixel ->
                val alpha = pixel ushr 24
                if (alpha < 24) return@forEach
                val red = (pixel ushr 16) and 0xff
                val green = (pixel ushr 8) and 0xff
                val blue = pixel and 0xff
                val maximum = maxOf(red, green, blue)
                val minimum = minOf(red, green, blue)
                if (maximum > 0 &&
                    (maximum - minimum).toFloat() / maximum.toFloat() > 0.12f
                ) {
                    colorfulPixels += 1
                }
                colorBuckets.add(
                    ((red / 32) shl 10) or
                        ((green / 32) shl 5) or
                        (blue / 32)
                )
                visiblePixels += 1
            }
            visiblePixels > 0 &&
                colorBuckets.size <= 10 &&
                colorfulPixels * 20 <= visiblePixels
        } finally {
            if (sampled !== bitmap) sampled.recycle()
        }
    }

    private fun maximumPayloadFor(device: BluetoothDevice): Int {
        val mtu = mtuByAddress[device.address] ?: DEFAULT_MTU
        return (mtu - ATT_HEADER_SIZE).coerceAtLeast(0)
    }

    private fun albumArtMaximumPayloadFor(device: BluetoothDevice): Int {
        val actualPayload = maximumPayloadFor(device)
        return if (actualPayload >= MIN_ALBUM_ART_PAYLOAD_BYTES) {
            actualPayload
        } else {
            ASSUMED_IOS_ALBUM_ART_PAYLOAD_BYTES
        }
    }

    private fun sendRemoteLogs(limit: Int) {
        val device = subscribedDevices.values.firstOrNull() ?: run {
            logger("[RemoteLog] send skipped: no iPhone subscriber")
            return
        }
        val lines = LogBuffer.getRecentLogs(limit)
        if (lines.isEmpty()) {
            logger("[RemoteLog] send start lines=0 chunks=0")
            val end = JSONObject()
                .put("type", "logEnd")
                .put("empty", true)
                .toString()
                .toByteArray(Charsets.UTF_8)
            notifyQueue.enqueueShort(
                device = device,
                type = "logEnd",
                value = end,
                delayAfterMs = LOG_NOTIFICATION_DELAY_MS
            )
            logger("[RemoteLog] send end")
            return
        }

        val textBytes = lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        val maximumPayload = minOf(
            maximumPayloadFor(device),
            MAX_LOG_JSON_BYTES
        )
        val rawChunkSize = chooseLogChunkSize(maximumPayload)
        if (rawChunkSize <= 0) {
            logger("[RemoteLog] send failed: MTU too small")
            return
        }

        val totalChunks =
            (textBytes.size + rawChunkSize - 1) / rawChunkSize
        logger(
            "[RemoteLog] send start lines=${lines.size} chunks=$totalChunks"
        )
        val packets = mutableListOf<BleNotifyQueue.Packet>()
        val start = JSONObject()
            .put("type", "logStart")
            .put("totalLines", lines.size)
            .put("chunks", totalChunks)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (start.size > maximumPayload) {
            logger("[RemoteLog] send failed: start exceeds MTU")
            return
        }
        packets += BleNotifyQueue.Packet(
            type = "logStart",
            value = start,
            delayAfterMs = LOG_NOTIFICATION_DELAY_MS
        )

        for (index in 0 until totalChunks) {
            val from = index * rawChunkSize
            val to = minOf(from + rawChunkSize, textBytes.size)
            val rawChunk = textBytes.copyOfRange(from, to)
            val chunk = JSONObject()
                .put("type", "logChunk")
                .put("index", index)
                .put("data", Base64.encodeToString(rawChunk, Base64.NO_WRAP))
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (chunk.size > maximumPayload) {
                logger("[RemoteLog] send failed: chunk $index exceeds MTU")
                return
            }
            packets += BleNotifyQueue.Packet(
                type = "logChunk",
                value = chunk,
                delayAfterMs = LOG_NOTIFICATION_DELAY_MS
            )
        }

        val end = JSONObject()
            .put("type", "logEnd")
            .toString()
            .toByteArray(Charsets.UTF_8)
        packets += BleNotifyQueue.Packet(
            type = "logEnd",
            value = end,
            delayAfterMs = LOG_NOTIFICATION_DELAY_MS
        )

        notifyQueue.enqueueLongJob(
            type = REMOTE_LOG_JOB_TYPE,
            device = device,
            packets = packets
        )
    }

    private fun sendMediaFieldDump() {
        logger("[MediaFieldDump] requested")
        if (subscribedDevices.isEmpty()) {
            logger("[MediaFieldDump] send failed: no iPhone subscriber")
            return
        }
        if (notifyQueue.hasJobTypeActiveOrQueued(MEDIA_FIELD_DUMP_JOB_TYPE) ||
            !mediaFieldDumpPreparing.compareAndSet(false, true)
        ) {
            logger("[MediaFieldDump] request already active")
            return
        }

        mediaFieldDumpExecutor.execute {
            try {
                val dump = mediaFieldDumpManager.dumpAllFields()
                val bytes = dump.toByteArray(Charsets.UTF_8)
                logger("[MediaFieldDump] built bytes=${bytes.size}")
                enqueueMediaFieldDump(bytes)
            } catch (throwable: Throwable) {
                logger(
                    "[MediaFieldDump] build failed: " +
                        "${throwable.javaClass.simpleName}: ${throwable.message}"
                )
                sendMediaFieldDumpError(
                    "${throwable.javaClass.simpleName}: ${throwable.message}"
                )
            } finally {
                mediaFieldDumpPreparing.set(false)
            }
        }
    }

    private fun enqueueMediaFieldDump(bytes: ByteArray) {
        val device = subscribedDevices.values.firstOrNull() ?: run {
            logger("[MediaFieldDump] send failed: no iPhone subscriber")
            return
        }
        val maximumPayload = maximumPayloadFor(device)
        val rawChunkSize = chooseMediaFieldDumpChunkSize(maximumPayload)
        if (rawChunkSize <= 0) {
            sendMediaFieldDumpError("MTU too small")
            return
        }

        val totalChunks =
            (bytes.size + rawChunkSize - 1) / rawChunkSize
        val packets = mutableListOf<BleNotifyQueue.Packet>()
        val start = JSONObject()
            .put("type", "mediaFieldDumpStart")
            .put("size", bytes.size)
            .put("chunks", totalChunks)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (start.size > maximumPayload) {
            sendMediaFieldDumpError("start packet exceeds MTU")
            return
        }
        packets += BleNotifyQueue.Packet(
            type = "mediaFieldDumpStart",
            value = start,
            delayAfterMs = MEDIA_FIELD_DUMP_DELAY_MS
        )

        for (index in 0 until totalChunks) {
            val from = index * rawChunkSize
            val to = minOf(from + rawChunkSize, bytes.size)
            val chunk = JSONObject()
                .put("type", "mediaFieldDumpChunk")
                .put("index", index)
                .put(
                    "data",
                    Base64.encodeToString(
                        bytes.copyOfRange(from, to),
                        Base64.NO_WRAP
                    )
                )
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (chunk.size > maximumPayload) {
                sendMediaFieldDumpError("chunk $index exceeds MTU")
                return
            }
            packets += BleNotifyQueue.Packet(
                type = "mediaFieldDumpChunk",
                value = chunk,
                delayAfterMs = MEDIA_FIELD_DUMP_DELAY_MS
            )
        }

        val end = JSONObject()
            .put("type", "mediaFieldDumpEnd")
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (end.size > maximumPayload) {
            sendMediaFieldDumpError("end packet exceeds MTU")
            return
        }
        packets += BleNotifyQueue.Packet(
            type = "mediaFieldDumpEnd",
            value = end,
            delayAfterMs = MEDIA_FIELD_DUMP_DELAY_MS
        )

        logger("[MediaFieldDump] chunks=$totalChunks")
        logger("[MediaFieldDump] send start")
        notifyQueue.enqueueLongJob(
            type = MEDIA_FIELD_DUMP_JOB_TYPE,
            device = device,
            packets = packets
        )
    }

    private fun chooseMediaFieldDumpChunkSize(maximumPayload: Int): Int {
        for (candidate in MAX_MEDIA_FIELD_DUMP_CHUNK_BYTES downTo 1) {
            val sample = JSONObject()
                .put("type", "mediaFieldDumpChunk")
                .put("index", 9999)
                .put(
                    "data",
                    Base64.encodeToString(
                        ByteArray(candidate),
                        Base64.NO_WRAP
                    )
                )
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (sample.size <= maximumPayload) {
                return candidate
            }
        }
        return 0
    }

    private fun sendMediaFieldDumpError(message: String) {
        val device = subscribedDevices.values.firstOrNull() ?: return
        val maximumPayload = maximumPayloadFor(device)
        val safeMessage = message.take(MAX_MEDIA_FIELD_DUMP_ERROR_CHARS)
        val value = JSONObject()
            .put("type", "mediaFieldDumpError")
            .put("message", safeMessage)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (value.size > maximumPayload) {
            logger("[MediaFieldDump] error packet exceeds MTU")
            return
        }
        notifyQueue.enqueueShort(
            device = device,
            type = "mediaFieldDumpError",
            value = value,
            delayAfterMs = SHORT_MESSAGE_DELAY_MS
        )
    }

    private fun sendPlayHistoryPage(request: JSONObject) {
        val requestId = request.optString("requestId").ifBlank {
            "history-${System.currentTimeMillis()}"
        }
        val beforeSessionId = if (request.has("beforeSessionId") &&
            !request.isNull("beforeSessionId")
        ) {
            request.optLong("beforeSessionId")
        } else {
            null
        }
        val limit = request.optInt("limit", DEFAULT_HISTORY_PAGE_LIMIT)
            .coerceIn(1, MAX_HISTORY_PAGE_LIMIT)
        executeHistoryQuery(requestId, PLAY_HISTORY_JOB_TYPE, "playHistoryPage") {
            val startedAtMs = SystemClock.elapsedRealtime()
            val rowsWithLookahead = PlaybackHistoryRepository(appContext)
                .getRecentSessions(beforeSessionId, limit + 1)
            val rows = rowsWithLookahead.take(limit)
            logger(
                "[HistoryBLE] query end type=page requestId=$requestId " +
                    "count=${rows.size} costMs=${SystemClock.elapsedRealtime() - startedAtMs}"
            )
            JSONObject()
                .put("type", "playHistoryPage")
                .put("requestId", requestId)
                .put("items", historyRowsToJson(rows))
                .put("nextBeforeSessionId", rows.lastOrNull()?.sessionId ?: JSONObject.NULL)
                .put("hasMore", rowsWithLookahead.size > rows.size)
        }
    }

    private fun sendPlayHistorySince(request: JSONObject) {
        val requestId = request.optString("requestId").ifBlank {
            "history-since-${System.currentTimeMillis()}"
        }
        val afterSessionId = request.optLong("afterSessionId", 0L).coerceAtLeast(0L)
        val limit = request.optInt("limit", DEFAULT_HISTORY_PAGE_LIMIT)
            .coerceIn(1, MAX_HISTORY_PAGE_LIMIT)
        executeHistoryQuery(requestId, PLAY_HISTORY_JOB_TYPE, "playHistorySince") {
            val startedAtMs = SystemClock.elapsedRealtime()
            val rowsWithLookahead = PlaybackHistoryRepository(appContext)
                .getSessionsAfterId(afterSessionId, limit + 1)
            val rows = rowsWithLookahead.take(limit)
            logger(
                "[HistoryBLE] query end type=since requestId=$requestId " +
                    "count=${rows.size} costMs=${SystemClock.elapsedRealtime() - startedAtMs}"
            )
            JSONObject()
                .put("type", "playHistorySince")
                .put("requestId", requestId)
                .put("items", historyRowsToJson(rows))
                .put("lastSessionId", rows.lastOrNull()?.sessionId ?: afterSessionId)
                .put("hasMore", rowsWithLookahead.size > rows.size)
        }
    }

    private fun sendPlayStats(request: JSONObject) {
        val requestId = request.optString("requestId").ifBlank {
            "stats-${System.currentTimeMillis()}"
        }
        val rangeValue = request.optString("range", StatsRange.WEEK.name)
        val range = runCatching { StatsRange.valueOf(rangeValue.uppercase()) }.getOrNull()
        if (range == null) {
            sendHistoryError(requestId, "playStats", "invalid range=$rangeValue")
            return
        }
        executeHistoryQuery(requestId, PLAY_STATS_JOB_TYPE, "playStats") {
            val startedAtMs = SystemClock.elapsedRealtime()
            val stats = PlaybackHistoryRepository(appContext).stats(range)
            logger(
                "[HistoryBLE] query end type=stats requestId=$requestId " +
                    "range=${range.name} costMs=${SystemClock.elapsedRealtime() - startedAtMs}"
            )
            statsToJson(requestId, range, stats)
        }
    }

    private fun executeHistoryQuery(
        requestId: String,
        jobType: String,
        responseType: String,
        buildPayload: () -> JSONObject
    ) {
        val device = subscribedDevices.values.firstOrNull() ?: run {
            logger("[HistoryBLE] request skipped requestId=$requestId reason=no subscriber")
            return
        }
        if (!historyQueryPreparing.compareAndSet(false, true)) {
            logger("[HistoryBLE] request busy requestId=$requestId")
            sendHistoryError(requestId, responseType, "history query already active")
            return
        }
        logger("[HistoryBLE] request type=$responseType requestId=$requestId")
        historyExecutor.execute {
            try {
                logger("[HistoryBLE] query start requestId=$requestId")
                enqueueHistoryPayload(device, jobType, responseType, requestId, buildPayload())
            } catch (throwable: Throwable) {
                logger("[HistoryBLE] failed requestId=$requestId reason=${throwable.message}")
                sendHistoryError(requestId, responseType, throwable.message ?: "query failed")
            } finally {
                historyQueryPreparing.set(false)
            }
        }
    }

    private fun enqueueHistoryPayload(
        device: BluetoothDevice,
        jobType: String,
        responseType: String,
        requestId: String,
        payload: JSONObject
    ) {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        logger("[HistoryBLE] payload bytes=${bytes.size} requestId=$requestId")
        val maximumPayload = maximumPayloadFor(device)
        if (bytes.size <= maximumPayload) {
            notifyQueue.enqueueShort(
                device = device,
                type = responseType,
                value = bytes,
                delayAfterMs = HISTORY_NOTIFICATION_DELAY_MS
            )
            logger("[HistoryBLE] send end requestId=$requestId chunks=0")
            return
        }
        val rawChunkSize = chooseHistoryChunkSize(maximumPayload, requestId)
        if (rawChunkSize <= 0) {
            sendHistoryError(requestId, responseType, "MTU too small")
            return
        }
        val totalChunks = (bytes.size + rawChunkSize - 1) / rawChunkSize
        val packets = mutableListOf<BleNotifyQueue.Packet>()
        val start = JSONObject()
            .put("type", "historyPayloadStart")
            .put("requestId", requestId)
            .put("responseType", responseType)
            .put("size", bytes.size)
            .put("chunks", totalChunks)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (start.size > maximumPayload) {
            sendHistoryError(requestId, responseType, "start packet exceeds MTU")
            return
        }
        packets += BleNotifyQueue.Packet(
            type = "historyPayloadStart",
            value = start,
            delayAfterMs = HISTORY_NOTIFICATION_DELAY_MS
        )
        for (index in 0 until totalChunks) {
            val from = index * rawChunkSize
            val to = minOf(from + rawChunkSize, bytes.size)
            val chunk = JSONObject()
                .put("type", "historyPayloadChunk")
                .put("requestId", requestId)
                .put("index", index)
                .put(
                    "data",
                    Base64.encodeToString(bytes.copyOfRange(from, to), Base64.NO_WRAP)
                )
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (chunk.size > maximumPayload) {
                sendHistoryError(requestId, responseType, "chunk $index exceeds MTU")
                return
            }
            packets += BleNotifyQueue.Packet(
                type = "historyPayloadChunk",
                value = chunk,
                delayAfterMs = HISTORY_NOTIFICATION_DELAY_MS
            )
        }
        val end = JSONObject()
            .put("type", "historyPayloadEnd")
            .put("requestId", requestId)
            .put("responseType", responseType)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (end.size > maximumPayload) {
            sendHistoryError(requestId, responseType, "end packet exceeds MTU")
            return
        }
        packets += BleNotifyQueue.Packet(
            type = "historyPayloadEnd",
            value = end,
            delayAfterMs = HISTORY_NOTIFICATION_DELAY_MS
        )
        logger("[HistoryBLE] send start requestId=$requestId chunks=$totalChunks")
        notifyQueue.enqueueLongJob(
            type = jobType,
            device = device,
            packets = packets,
            maxSendDurationMs = HISTORY_MAX_SEND_MS,
            onComplete = {
                logger("[HistoryBLE] send end requestId=$requestId chunks=$totalChunks")
            },
            onFailure = {
                logger("[HistoryBLE] failed requestId=$requestId reason=transport failed")
            }
        )
    }

    private fun chooseHistoryChunkSize(maximumPayload: Int, requestId: String): Int {
        for (candidate in MAX_HISTORY_CHUNK_RAW_BYTES downTo 1) {
            val sample = JSONObject()
                .put("type", "historyPayloadChunk")
                .put("requestId", requestId)
                .put("index", 9999)
                .put("data", Base64.encodeToString(ByteArray(candidate), Base64.NO_WRAP))
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (sample.size <= maximumPayload) {
                return candidate
            }
        }
        return 0
    }

    private fun sendHistoryError(requestId: String, responseType: String, message: String) {
        val device = subscribedDevices.values.firstOrNull() ?: return
        val value = JSONObject()
            .put("type", "playHistoryError")
            .put("requestId", requestId)
            .put("responseType", responseType)
            .put("message", message.take(MAX_HISTORY_ERROR_CHARS))
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (value.size > maximumPayloadFor(device)) {
            logger("[HistoryBLE] error packet exceeds MTU")
            return
        }
        notifyQueue.enqueueShort(
            device = device,
            type = "playHistoryError",
            value = value,
            delayAfterMs = HISTORY_NOTIFICATION_DELAY_MS
        )
    }

    private fun historyRowsToJson(rows: List<HistorySessionRow>): JSONArray {
        return JSONArray().also { array ->
            rows.forEach { row ->
                array.put(
                    JSONObject()
                        .put("sessionId", row.sessionId)
                        .put("trackKey", row.trackKey)
                        .put("title", row.title)
                        .put("artist", row.artist)
                        .put("album", row.album)
                        .put("artworkId", row.artworkId ?: JSONObject.NULL)
                        .put("startedAt", row.startedAt)
                        .put("endedAt", row.endedAt ?: JSONObject.NULL)
                        .put("listenedMs", row.listenedMs)
                        .put("durationMs", row.durationMs)
                        .put("completed", row.completed)
                        .put("skipped", row.skipped)
                        .put("countedPlay", row.countedPlay)
                )
            }
        }
    }

    private fun statsToJson(
        requestId: String,
        range: StatsRange,
        stats: PlaybackStatsSummary
    ): JSONObject {
        return JSONObject()
            .put("type", "playStats")
            .put("requestId", requestId)
            .put("range", range.name)
            .put("rangeStart", stats.rangeStart)
            .put("rangeEnd", stats.rangeEnd)
            .put("totalListenMs", stats.totalListenMs)
            .put("playCount", stats.playCount)
            .put("uniqueTrackCount", stats.uniqueTrackCount)
            .put("completedCount", stats.completedCount)
            .put("skippedCount", stats.skippedCount)
            .put("completionRate", stats.completionRate)
            .put("skipRate", stats.skipRate)
            .put("topTracks", JSONArray().also { array ->
                stats.topTracks.forEach { track ->
                    array.put(
                        JSONObject()
                            .put("trackKey", track.trackKey)
                            .put("title", track.title)
                            .put("artist", track.artist)
                            .put("album", track.album)
                            .put("artworkId", track.artworkId ?: JSONObject.NULL)
                            .put("listenedMs", track.listenedMs)
                            .put("playCount", track.playCount)
                            .put("completedCount", track.completedCount)
                            .put("skippedCount", track.skippedCount)
                    )
                }
            })
            .put("topArtists", JSONArray().also { array ->
                stats.topArtists.forEach { artist ->
                    array.put(
                        JSONObject()
                            .put("artist", artist.artist)
                            .put("listenedMs", artist.listenedMs)
                            .put("playCount", artist.playCount)
                            .put("trackCount", artist.trackCount)
                    )
                }
            })
            .put("dailyTrend", JSONArray().also { array ->
                stats.dailyTrend.forEach { day ->
                    array.put(
                        JSONObject()
                            .put("dateKey", day.dateKey)
                            .put("listenedMs", day.listenedMs)
                            .put("playCount", day.playCount)
                    )
                }
            })
    }

    private fun sendFullLyrics(request: JSONObject) {
        val device = subscribedDevices.values.firstOrNull() ?: run {
            logger("[FullLyrics] send skipped: no iPhone subscriber")
            return
        }
        val buildStartedAtMs = SystemClock.elapsedRealtime()
        val source = playbackStateReader.readPlaybackState()
        val trackId = buildAlbumArtProtocolId(source)
        val title = source.optString("title")
        val artist = source.optString("artist")
        val lines = playbackStateReader.lyricLinesSnapshot()
            .filter { it.text.isNotBlank() }
            .take(MAX_FULL_LYRICS_LINES)
        val includeWordsAroundCurrent =
            request.optBoolean("includeWordsAroundCurrent", false)
        val requestedPositionMs = request.optLong(
            "positionMs",
            source.optLong("position", 0L)
        )
        val currentLineIndex = if (includeWordsAroundCurrent) {
            findCurrentLyricIndex(lines, requestedPositionMs)
        } else {
            -1
        }
        val wordLineIndexes = if (currentLineIndex >= 0) {
            setOf(currentLineIndex - 1, currentLineIndex, currentLineIndex + 1)
                .filter { it in lines.indices }
                .toSet()
        } else {
            emptySet()
        }

        if (lines.isEmpty()) {
            val unavailable = JSONObject()
                .put("type", "fullLyricsUnavailable")
                .put("trackId", trackId)
                .put("reason", "no parsed lyrics")
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (unavailable.size <= maximumPayloadFor(device)) {
                notifyQueue.enqueueShort(
                    device = device,
                    type = "fullLyricsUnavailable",
                    value = unavailable,
                    delayAfterMs = SHORT_MESSAGE_DELAY_MS
                )
            }
            logger("[FullLyrics] unavailable trackId=$trackId reason=no parsed lyrics")
            return
        }

        val maximumPayload = maximumPayloadFor(device)
        val packets = mutableListOf<BleNotifyQueue.Packet>()
        val start = buildFittingFullLyricsStart(
            trackId = trackId,
            title = title,
            artist = artist,
            count = lines.size,
            maximumPayload = maximumPayload
        ) ?: run {
            logger("[FullLyrics] send failed: start exceeds MTU")
            return
        }
        packets += BleNotifyQueue.Packet(
            type = "fullLyricsStart",
            value = start,
            delayAfterMs = FULL_LYRICS_NOTIFICATION_DELAY_MS
        )

        lines.forEachIndexed { index, line ->
            val chunk = buildFittingFullLyricsChunk(
                trackId = trackId,
                index = index,
                timeMs = line.timeMs,
                durationMs = line.durationMs,
                text = line.text,
                words = if (index in wordLineIndexes) line.words else emptyList(),
                includeWords = index in wordLineIndexes,
                maximumPayload = maximumPayload
            )
            if (chunk == null) {
                logger("[FullLyrics] send failed: chunk $index exceeds MTU")
                return
            }
            packets += BleNotifyQueue.Packet(
                type = "fullLyricsChunk",
                value = chunk,
                delayAfterMs = FULL_LYRICS_NOTIFICATION_DELAY_MS
            )
        }

        val end = JSONObject()
            .put("type", "fullLyricsEnd")
            .put("trackId", trackId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (end.size > maximumPayload) {
            logger("[FullLyrics] send failed: end exceeds MTU")
            return
        }
        packets += BleNotifyQueue.Packet(
            type = "fullLyricsEnd",
            value = end,
            delayAfterMs = FULL_LYRICS_NOTIFICATION_DELAY_MS
        )
        if (playbackStateReader.lyricLinesSnapshot().size > MAX_FULL_LYRICS_LINES) {
            logger("[FullLyrics] truncated count=${playbackStateReader.lyricLinesSnapshot().size}")
        }
        val wordsLines = wordLineIndexes.count { lines[it].words.isNotEmpty() }
        logger(
            "[FullLyrics] mode=lineOnly wordsAroundCurrent=$includeWordsAroundCurrent " +
                "wordLines=$wordsLines"
        )
        logger(
            "[FullLyricsPerf] build done lines=${lines.size} " +
                "wordsLines=$wordsLines costMs=${SystemClock.elapsedRealtime() - buildStartedAtMs}"
        )
        val sendStartedAtMs = SystemClock.elapsedRealtime()
        logger("[FullLyrics] send start trackId=$trackId count=${lines.size}")
        notifyQueue.enqueueLongJob(
            type = FULL_LYRICS_JOB_TYPE,
            device = device,
            packets = packets,
            onComplete = {
                logger(
                    "[FullLyricsPerf] send end costMs=" +
                        "${SystemClock.elapsedRealtime() - sendStartedAtMs}"
                )
                logger("[FullLyrics] send end trackId=$trackId")
            }
        )
    }

    private fun findCurrentLyricIndex(
        lines: List<com.example.playeragent.media.LyricManager.LyricLine>,
        positionMs: Long
    ): Int {
        if (lines.isEmpty()) {
            return -1
        }
        var result = 0
        lines.forEachIndexed { index, line ->
            if (line.timeMs <= positionMs) {
                result = index
            } else {
                return result
            }
        }
        return result
    }

    private fun buildFittingFullLyricsStart(
        trackId: String,
        title: String,
        artist: String,
        count: Int,
        maximumPayload: Int
    ): ByteArray? {
        val limits = listOf(
            30 to 30,
            20 to 20,
            12 to 12,
            0 to 0
        )
        limits.forEach { (titleLimit, artistLimit) ->
            val objectValue = JSONObject()
                .put("type", "fullLyricsStart")
                .put("trackId", trackId)
                .put("title", title.take(titleLimit))
                .put("artist", artist.take(artistLimit))
                .put("count", count)
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (objectValue.size <= maximumPayload) {
                return objectValue
            }
        }
        return null
    }

    private fun buildFittingFullLyricsChunk(
        trackId: String,
        index: Int,
        timeMs: Long,
        durationMs: Long,
        text: String,
        words: List<com.example.playeragent.media.QrcLyricWord>,
        includeWords: Boolean,
        maximumPayload: Int
    ): ByteArray? {
        var fittedText = text.take(MAX_FULL_LYRICS_TEXT_LENGTH)
        if (includeWords && words.isNotEmpty() && fittedText == text) {
            val withWords = buildFullLyricsChunkJson(
                trackId = trackId,
                index = index,
                timeMs = timeMs,
                durationMs = durationMs,
                text = fittedText,
                words = words
            ).toByteArray(Charsets.UTF_8)
            if (withWords.size <= maximumPayload) {
                if (LogConfig.DEBUG_VERBOSE_LOG) {
                    verboseLogger(
                        "[FullLyrics] chunk index=$index words=${words.size} " +
                            "payloadBytes=${withWords.size}"
                    )
                }
                return withWords
            }
            logger("[FullLyrics] words omitted index=$index reason=payload too large")
        }
        while (true) {
            val value = buildFullLyricsChunkJson(
                trackId = trackId,
                index = index,
                timeMs = timeMs,
                durationMs = durationMs,
                text = fittedText,
                words = emptyList()
            ).toByteArray(Charsets.UTF_8)
            if (value.size <= maximumPayload) {
                if (LogConfig.DEBUG_VERBOSE_LOG) {
                    verboseLogger(
                        "[FullLyrics] chunk index=$index words=0 " +
                            "payloadBytes=${value.size}"
                    )
                }
                return value
            }
            if (fittedText.isEmpty()) {
                return null
            }
            fittedText = fittedText.dropLast(1)
        }
    }

    private fun buildFullLyricsChunkJson(
        trackId: String,
        index: Int,
        timeMs: Long,
        durationMs: Long,
        text: String,
        words: List<com.example.playeragent.media.QrcLyricWord>
    ): String {
        return JSONObject()
            .put("type", "fullLyricsChunk")
            .put("trackId", trackId)
            .put("index", index)
            .put("timeMs", timeMs)
            .put("durationMs", durationMs)
            .put("text", text)
            .also { objectValue ->
                if (words.isNotEmpty()) {
                    objectValue.put(
                        "words",
                        JSONArray().also { array ->
                            words.forEach { word ->
                                if (word.text.isNotBlank()) {
                                    array.put(
                                        JSONObject()
                                            .put("startMs", word.startMs)
                                            .put("durationMs", word.durationMs)
                                            .put("text", word.text)
                                    )
                                }
                            }
                        }
                    )
                }
            }
            .toString()
    }

    private fun chooseLogChunkSize(maximumPayload: Int): Int {
        for (candidate in MAX_LOG_CHUNK_RAW_BYTES downTo 1) {
            val sample = JSONObject()
                .put("type", "logChunk")
                .put("index", 9999)
                .put(
                    "data",
                    Base64.encodeToString(ByteArray(candidate), Base64.NO_WRAP)
                )
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (sample.size <= maximumPayload) {
                return candidate
            }
        }
        return 0
    }

    private fun readMessageType(message: String): String {
        return try {
            JSONObject(message).optString("type", "unknown")
        } catch (_: Exception) {
            "invalid"
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendWriteResponse(
        device: BluetoothDevice?,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ): Boolean {
        val server = gattServer
        if (server == null || device == null) {
            logger("[BLE-A] write response skipped: server or device unavailable")
            return false
        }

        return try {
            val sent = server.sendResponse(device, requestId, status, offset, value)
            logger("[BLE-A] write response sent=$sent status=$status")
            sent
        } catch (securityException: SecurityException) {
            logger("[BLE-A] write response failed: missing permission")
            false
        } catch (exception: Exception) {
            logger("[BLE-A] write response failed: ${exception.message}")
            false
        }
    }

    companion object {
        private const val DEFAULT_MTU = 23
        private const val ATT_HEADER_SIZE = 3
        private const val MIN_ALBUM_ART_PAYLOAD_BYTES = 100
        private const val ASSUMED_IOS_ALBUM_ART_PAYLOAD_BYTES = 182
        private const val AUTO_PUSH_INTERVAL_MS = 1000L
        private const val ALBUM_ART_ENABLED = true
        private const val ALBUM_ART_PREVIEW_MAX_CHUNKS = 35
        private const val ALBUM_ART_HQ_MAX_CHUNKS = 95
        private const val ALBUM_ART_FULL_MAX_CHUNKS = 90
        private const val ALBUM_ART_PREVIEW_MAX_SEND_MS = 1200L
        private const val ALBUM_ART_HQ_MAX_SEND_MS = 8000L
        private const val ALBUM_ART_FULL_MAX_SEND_MS = 3500L
        private const val ALBUM_ART_COOLDOWN_AFTER_TRACK_CHANGE_MS = 1500L
        private const val MAX_TRACK_INFO_TEXT_LENGTH = 300
        private const val MAX_TRACK_INFO_CHUNK_BYTES = 300
        private const val MAX_ALBUM_CHUNK_RAW_BYTES = 60
        private const val MAX_ALBUM_JSON_BYTES = 180
        private const val ALBUM_ART_BINARY_MAGIC = 0xA1
        private const val ALBUM_ART_BINARY_HEADER_BYTES = 6
        private const val ALBUM_ART_ID_HASH_BYTES = 12
        private const val PREVIEW_MAX_JPEG_BYTES = 6200
        private const val HQ_MAX_JPEG_BYTES = 16000
        private const val FULL_MAX_JPEG_BYTES = 5200
        private const val SHORT_MESSAGE_DELAY_MS = 20L
        private const val ALBUM_ART_NOTIFICATION_DELAY_MS = 35L
        private const val ALBUM_ART_BINARY_NOTIFICATION_DELAY_MS = 5L
        private const val LOG_NOTIFICATION_DELAY_MS = 20L
        private const val MAX_LOG_CHUNK_RAW_BYTES = 300
        private const val MAX_LOG_JSON_BYTES = 480
        private const val DEFAULT_LOG_LIMIT = 30
        private const val MAX_LOG_LIMIT = 50
        private const val MAX_MEDIA_FIELD_DUMP_CHUNK_BYTES = 300
        private const val MAX_MEDIA_FIELD_DUMP_ERROR_CHARS = 80
        private const val MEDIA_FIELD_DUMP_DELAY_MS = 25L
        private const val FULL_LYRICS_NOTIFICATION_DELAY_MS = 20L
        private const val MAX_FULL_LYRICS_LINES = 120
        private const val MAX_FULL_LYRICS_TEXT_LENGTH = 80
        private const val ALBUM_ART_JOB_TYPE = "albumArt"
        private const val TRACK_INFO_JOB_TYPE = "trackInfo"
        private const val FULL_LYRICS_JOB_TYPE = "fullLyrics"
        private const val MAX_LYRIC_TEXT_LENGTH = 30
        private const val REMOTE_LOG_JOB_TYPE = "remoteLog"
        private const val MEDIA_FIELD_DUMP_JOB_TYPE = "mediaFieldDump"
        private const val PLAY_HISTORY_JOB_TYPE = "playHistory"
        private const val PLAY_STATS_JOB_TYPE = "playStats"
        private const val DEFAULT_HISTORY_PAGE_LIMIT = 10
        private const val MAX_HISTORY_PAGE_LIMIT = 20
        private const val MAX_HISTORY_CHUNK_RAW_BYTES = 300
        private const val HISTORY_NOTIFICATION_DELAY_MS = 20L
        private const val HISTORY_MAX_SEND_MS = 8_000L
        private const val MAX_HISTORY_ERROR_CHARS = 100
        private const val CALLBACK_LOG_DEDUP_WINDOW_MS = 500L
    }

    private data class CompressionAttempt(
        val width: Int,
        val height: Int,
        val quality: Int,
        val maximumBytes: Int
    )

    private data class TrackInfoLimit(
        val titleLength: Int,
        val artistLength: Int,
        val albumLength: Int,
        val includeAlbum: Boolean
    )

    private data class PendingAlbumArt(
        val cacheKey: String,
        val protocolId: String,
        val playbackState: JSONObject
    )

    private enum class AlbumArtQuality(val wireValue: String) {
        PREVIEW("preview"),
        HQ("hq"),
        FULL("full");

        companion object {
            fun fromWireValue(value: String): AlbumArtQuality? {
                return entries.firstOrNull {
                    it.wireValue.equals(value, ignoreCase = true)
                }
            }
        }
    }

    enum class ServerState {
        STOPPED,
        STARTING,
        READY,
        FAILED
    }

    data class BleGattServerSnapshot(
        val serverState: ServerState,
        val started: Boolean,
        val connectedDevices: List<String>,
        val subscribedDevices: List<String>,
        val notificationInFlight: Boolean,
        val pendingJobs: Int,
        val activeJob: String?,
        val pendingShortMessages: Int
    )

    private data class CompressedAlbumArt(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val quality: Int
    )

    private data class AlbumArtPackets(
        val totalChunks: Int,
        val packets: List<BleNotifyQueue.Packet>
    )

    private data class PreparedAlbumArt(
        val compressed: CompressedAlbumArt,
        val packets: AlbumArtPackets
    )

    private data class AlbumArtPreparation(
        val prepared: PreparedAlbumArt? = null,
        val compressionFailed: Boolean = false
    )
}
