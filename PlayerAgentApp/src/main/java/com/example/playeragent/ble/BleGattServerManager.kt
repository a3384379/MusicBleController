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
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.example.playeragent.history.HistorySessionRow
import com.example.playeragent.history.PlaybackHistoryRepository
import com.example.playeragent.history.PlaybackStatsSummary
import com.example.playeragent.history.StatsRange
import com.example.playeragent.diagnostics.RealtimeTrace
import com.example.playeragent.media.AlbumArtPlaceholderPolicy
import com.example.playeragent.media.AlbumArtTestManager
import com.example.playeragent.logging.LogConfig
import com.example.playeragent.logging.LogBuffer
import com.example.playeragent.media.MediaCommandExecutor
import com.example.playeragent.media.MediaFieldDumpManager
import com.example.playeragent.media.CurrentTrackRuntimeCache
import com.example.playeragent.media.CurrentTrackSnapshot
import com.example.playeragent.media.IncrementalLyricsReady
import com.example.playeragent.media.LyricTraceLogger
import com.example.playeragent.media.LyricsReadyGateSnapshot
import com.example.playeragent.media.PlayerAgentExecutionHub
import com.example.playeragent.media.PlaybackStateReader
import com.example.playeragent.media.PlaybackStateDiffType
import com.example.playeragent.media.ReactiveMediaController
import com.example.playeragent.media.TrackCapabilityTracker
import com.example.playeragent.service.PlayerNotificationListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BleGattServerManager(
    context: Context,
    private val bluetoothManager: BluetoothManager,
    private val logger: (String) -> Unit,
    private val transientLogger: (String) -> Unit = logger,
    private val verboseLogger: (String) -> Unit = logger,
    private val advertisingStateProvider: () -> String = { "unknown" },
    private val onAllClientsDisconnected: (reason: String) -> Unit = {},
    private val onControllerConnectionCountChanged: (connectedCount: Int) -> Unit = {},
    private val onPlaybackUiState: (JSONObject) -> Unit = {},
    private val executionHub: PlayerAgentExecutionHub
) {

    init {
        RealtimeTrace.configure(
            logger = logger,
            enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        )
    }

    private val appContext = context.applicationContext
    private val connectedDeviceAddresses = Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtuByAddress = ConcurrentHashMap<String, Int>()
    private val recentConnectionCallbacks = ConcurrentHashMap<String, Long>()
    private val recentMtuCallbacks = ConcurrentHashMap<String, Long>()
    private val reactiveMediaController = ReactiveMediaController(logger)
    private val playbackStateReader = PlaybackStateReader(
        context = appContext,
        logger = logger,
        reactiveMediaController = reactiveMediaController,
        executionHub = executionHub,
        onLyricsReady = ::handleLyricsReady
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
    private val mediaFieldDumpExecutor = executionHub.maintenance
    private val mediaFieldDumpPreparing = AtomicBoolean(false)
    private val historyExecutor = executionHub.maintenance
    private val historyQueryPreparing = AtomicBoolean(false)
    private val reconnectSyncExecutor = executionHub.realtime
    private val commandExecutor = executionHub.realtime
    private val lyricCommandExecutor = executionHub.foregroundIO
    private val albumArtFastPathExecutor = executionHub.foregroundIO
    private val mediaCallbackThread =
        HandlerThread("BleMedia-Callbacks").apply { start() }
    // Artwork notifications, transfer completion, and delayed lyric retries
    // must never contend with MainActivity's input loop on low-end Sony
    // hardware. State transitions remain protected by the manager monitor.
    private val albumArtHandler = Handler(mediaCallbackThread.looper)
    private val gattLifecycleHandler = Handler(Looper.getMainLooper())
    private val qqMusicArtworkListener: (String) -> Unit = { event ->
        // Start the latency-sensitive state/lyrics path before artwork recovery.
        // The latter may need notification bitmap I/O on slower Sony hardware.
        schedulePlaybackUiRefresh("qq_notification")
        albumArtHandler.post {
            retryCurrentAlbumArtAfterNotification(event)
            wakeAutoPushFromQqNotification()
        }
    }
    private val playbackUiRefreshInFlight = AtomicBoolean(false)
    private val playbackUiRefreshPending = AtomicBoolean(false)
    private val playbackUiRefreshReason = AtomicReference("initial")

    private var gattServer: BluetoothGattServer? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var gattServiceGeneration = 0L
    private val forcedRediscoveryGenerationByAddress = ConcurrentHashMap<String, Long>()
    private var autoPushExecutor: ScheduledExecutorService? = null
    private var autoPushTask: Future<*>? = null
    private var currentWordExecutor: ScheduledExecutorService? = null
    @Volatile
    private var currentWordPushTask: Future<*>? = null
    private val currentWordPushEngine = CurrentWordPushEngine(
        logger = logger,
        sendStatusMessage = { message -> sendStatusMessage(message) },
        normalizeTrackId = { trackId -> normalizeCurrentWordTrackId(trackId) },
        includeClockSyncFields = {
            val addresses = subscribedDevices.keys.toList()
            addresses.isNotEmpty() && addresses.all { address ->
                connectionCommandCoordinator.capabilities(address).clockSyncV1
            }
        }
    )
    private val playbackStateBuffer = PlaybackStateBuffer(
        logger = logger,
        scheduledExecutor = executionHub.scheduled,
        flush = { source, snapshot, diff, reason, coalesceCount ->
            flushBufferedPlaybackState(source, snapshot, diff, reason, coalesceCount)
        }
    )
    private val notifyQueue = BleNotifyQueue(
        serverProvider = { gattServer },
        characteristicProvider = { statusCharacteristic },
        logger = logger,
        localOnlyLogger = transientLogger,
        verboseLogger = verboseLogger,
        onNotifySuccess = ::recordNotifySuccess,
        onNotifyFailure = ::recordNotifyFailure
    )
    @Volatile
    private var lastAlbumArtKey: String? = null
    @Volatile
    private var currentAlbumArtId: String? = null
    private val albumArtRequestsInFlight = Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    private val albumArtRequestCompletedAtMs = ConcurrentHashMap<String, Long>()
    @Volatile
    private var pendingAlbumArt: PendingAlbumArt? = null
    @Volatile
    private var albumArtTaskGeneration = 0L
    @Volatile
    private var albumArtFastPathTask: Future<*>? = null
    @Volatile
    private var albumArtFastPathProtocolId: String? = null
    @Volatile
    private var albumArtFastPathCompletionPending = false
    private val albumArtPendingRequests =
        ConcurrentHashMap<String, PendingAlbumArtRequest>()
    private val albumArtSourceRetryCounts = ConcurrentHashMap<String, Int>()
    private val albumArtUnavailableProtocolIds = Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    private val albumArtCache =
        LinkedHashMap<String, AlbumArtCacheEntry>(ALBUM_ART_CACHE_CAPACITY * 2, 0.75f, true)
    private val encodedAlbumArtCache =
        LinkedHashMap<String, CompressedAlbumArt>(ENCODED_ART_CACHE_CAPACITY, 0.75f, true)
    private val albumArtPreparationLocks = ConcurrentHashMap<String, Any>()
    private var encodedAlbumArtCacheBytes = 0
    @Volatile
    private var currentAlbumArtPlaybackState: JSONObject? = null
    private val pendingFullLyricsRequests =
        ConcurrentHashMap<String, PendingFullLyricsRequest>()
    private val pendingLyricWindowRequests =
        ConcurrentHashMap<String, PendingLyricWindowRequest>()
    private val lyricsTransferCoordinator = LyricsTransferCoordinator()
    private val albumArtTransferCoordinator = AlbumArtTransferCoordinator()
    private val connectionCommandCoordinator = ConnectionCommandCoordinator()
    private val v3SessionCoordinator = BleV3SessionCoordinator()
    private val multiControllerCommandGate = MultiControllerCommandGate()
    private var lastAutoPushSongKey: String? = null
    private var lastAutoPushPlaying: Boolean? = null
    @Volatile
    private var lastAutoPushReadAtMs: Long = 0L
    private var lastPlaybackDiffSkipLogAtMs: Long = 0L
    private val reconnectSyncLastAtByAddress = ConcurrentHashMap<String, Long>()
    @Volatile
    private var started = false
    @Volatile
    private var serverState = ServerState.STOPPED
    @Volatile
    private var lastCommandSuccessAtMs = 0L
    @Volatile
    private var lastNotifySuccessAtMs = 0L
    @Volatile
    private var lastNotifyFailureAtMs = 0L
    private val notifyFailureCountByAddress = ConcurrentHashMap<String, Int>()
    private val subscribedAtByAddress = ConcurrentHashMap<String, Long>()
    private val lastCommandSuccessAtByAddress = ConcurrentHashMap<String, Long>()
    private val lastNotifySuccessAtByAddress = ConcurrentHashMap<String, Long>()
    private val lastNotifyFailureAtByAddress = ConcurrentHashMap<String, Long>()
    private val lastLinkProbeAtByAddress = ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastSubscribedAtMs = 0L
    @Volatile
    private var lastHealthSuccessLogAtMs = 0L

    init {
        TrackCapabilityTracker.setLogger(logger)
        PlayerNotificationListenerService.addQqMusicArtworkListener(qqMusicArtworkListener)
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logger("[BLE-A] service added success")
                updateServerState(ServerState.READY)
                connectedDevices.values.forEach { device ->
                    scheduleGattRediscoveryIfUnsubscribed(
                        device = device,
                        reason = "service_recreated"
                    )
                }
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
                val added = connectedDeviceAddresses.add(address)
                connectedDevices[address] = device
                mtuByAddress[address] = DEFAULT_MTU
                notifyQueue.resetLinkProfile(address, DEFAULT_MTU)
                logger("[ReconnectSync] central connected device=$address")
                scheduleGattRediscoveryIfUnsubscribed(
                    device = device,
                    reason = "central_connected"
                )
                if (added) {
                    onControllerConnectionCountChanged(connectedDeviceAddresses.size)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val hasUsableAddress = address.isNotBlank() && address != "unknown"
                var connectionRemoved = false
                if (hasUsableAddress) {
                    connectionRemoved = connectedDeviceAddresses.remove(address)
                    connectedDevices.remove(address)
                    subscribedDevices.remove(address)
                    forcedRediscoveryGenerationByAddress.remove(address)
                    mtuByAddress.remove(address)
                    notifyQueue.removeDevice(address)
                    notifyFailureCountByAddress.remove(address)
                    subscribedAtByAddress.remove(address)
                    lastCommandSuccessAtByAddress.remove(address)
                    lastNotifySuccessAtByAddress.remove(address)
                    lastNotifyFailureAtByAddress.remove(address)
                    lastLinkProbeAtByAddress.remove(address)
                    connectionCommandCoordinator.remove(address)
                    lyricsTransferCoordinator.resetAddress(address)
                    albumArtTransferCoordinator.resetAddress(address)
                    albumArtRequestsInFlight
                        .filter { it.startsWith("$address|") }
                        .forEach(albumArtRequestsInFlight::remove)
                    albumArtRequestCompletedAtMs.keys
                        .filter { it.startsWith("$address|") }
                        .forEach(albumArtRequestCompletedAtMs::remove)
                    albumArtPendingRequests.entries.removeAll {
                        it.value.device.address == address
                    }
                } else {
                    logger("[BLE-A] disconnect address unavailable, clearing all notify state")
                    connectedDeviceAddresses.clear()
                    connectedDevices.clear()
                    subscribedDevices.clear()
                    forcedRediscoveryGenerationByAddress.clear()
                    mtuByAddress.clear()
                    notifyQueue.clearAllForDisconnect("unknown disconnect address")
                    notifyFailureCountByAddress.clear()
                    subscribedAtByAddress.clear()
                    lastCommandSuccessAtByAddress.clear()
                    lastNotifySuccessAtByAddress.clear()
                    lastNotifyFailureAtByAddress.clear()
                    lastLinkProbeAtByAddress.clear()
                    connectionCommandCoordinator.clear()
                    lyricsTransferCoordinator.clearRetryState()
                    albumArtTransferCoordinator.reset()
                }
                if (subscribedDevices.isEmpty()) {
                    clearSharedClientRuntimeState()
                }
                stopAutoPushIfUnused()
                logDisconnectDiagnostics(address)
                if (connectionRemoved) {
                    onControllerConnectionCountChanged(connectedDeviceAddresses.size)
                }
                if (connectedDeviceAddresses.isEmpty()) {
                    onAllClientsDisconnected("gatt disconnected status=$status")
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            val address = device?.address ?: return
            mtuByAddress[address] = mtu
            notifyQueue.updateLinkMtu(address, mtu)
            val callbackKey = "$address|$mtu"
            if (shouldLogCallback(recentMtuCallbacks, callbackKey)) {
                logger("[BLE-A] MTU changed: device=$address mtu=$mtu")
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            notifyQueue.onNotificationSent(device?.address, status)
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
                device?.let {
                    sendCommandError(
                        device = it,
                        request = JSONObject().put("cmd", "").put("seq", ""),
                        domain = "protocol",
                        code = "invalid_json",
                        retryable = false
                    )
                }
                return
            }

            val command = request.optString("cmd")
            val seq = request.optString("seq").ifBlank { "unknown" }
            val commandSeq = seq.toLongOrNull()
            RealtimeTrace.record(
                stage = "commandReceived",
                monoMs = receiveElapsedMs,
                commandSeq = commandSeq,
                commandType = command,
                processingMs = (SystemClock.elapsedRealtime() - receiveElapsedMs)
                    .coerceAtLeast(0L),
                result = "received"
            )
            RealtimeTrace.record(
                stage = "commandValidated",
                commandSeq = commandSeq,
                commandType = command,
                processingMs = (SystemClock.elapsedRealtime() - parseStartedAtMs)
                    .coerceAtLeast(0L),
                result = "valid"
            )
            logger(
                "[CTRL-Sony] command parsed seq=$seq cmd=$command " +
                    "parseCostMs=${SystemClock.elapsedRealtime() - parseStartedAtMs}"
            )
            logger("[BLE-A] command received: $command")
            // Capability fallback is timed from CCCD subscription, while command
            // handling runs on a worker that can be busy with reconnect state sync.
            // Record a valid capability frame at receipt time so the 250 ms timer
            // cannot incorrectly downgrade this controller before its ACK is queued.
            if (command == "CLIENT_CAPABILITIES" && device != null) {
                connectionCommandCoordinator.accept(
                    device.address,
                    parseClientCapabilities(request, maximumPayloadFor(device))
                )
            }
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
                if (ok) {
                    notifyQueue.onCommandResponseSent()
                    recordCommandSuccess(command, device?.address)
                }
            }
            val dispatchExecutor = when (command) {
                "GET_LYRIC_WINDOW", "GET_FULL_LYRICS", "RETRY_TRANSFER" ->
                    lyricCommandExecutor
                else -> commandExecutor
            }
            dispatchExecutor.execute {
                if (!started) {
                    logger("[CTRL-Sony] command dropped after close seq=$seq cmd=$command")
                    return@execute
                }
                val handleStartedAtMs = SystemClock.elapsedRealtime()
                RealtimeTrace.record(
                    stage = "mediaControlDispatchStart",
                    monoMs = handleStartedAtMs,
                    commandSeq = commandSeq,
                    commandType = command,
                    queueWaitMs = (handleStartedAtMs - receiveElapsedMs).coerceAtLeast(0L),
                    result = "started"
                )
                logger(
                    "[CTRL-Sony] handle async begin seq=$seq cmd=$command " +
                        "queueSnapshot=${controlQueueSnapshot()}"
                )
                val sourceDevice = device
                if (sourceDevice == null) {
                    logger("[CTRL-Sony] command dropped reason=unknown source seq=$seq cmd=$command")
                    return@execute
                }
                val dispatchResult = runCatching {
                    handleCommand(sourceDevice, command, request, seq)
                }
                    .onFailure { exception ->
                        logger(
                            "[CTRL-Sony] handle async failed seq=$seq cmd=$command " +
                                "error=${exception.message}"
                        )
                    }
                val handleEndedAtMs = SystemClock.elapsedRealtime()
                RealtimeTrace.record(
                    stage = "mediaControlDispatchEnd",
                    monoMs = handleEndedAtMs,
                    commandSeq = commandSeq,
                    commandType = command,
                    processingMs = (handleEndedAtMs - handleStartedAtMs).coerceAtLeast(0L),
                    result = if (dispatchResult.isSuccess) "success" else "failure",
                    reason = if (dispatchResult.isSuccess) null else "handler_exception"
                )
                logger(
                    "[CTRL-Sony] handle async end seq=$seq cmd=$command " +
                        "costMs=${SystemClock.elapsedRealtime() - handleStartedAtMs}"
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

            val enabling = value?.contentEquals(
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == true
            if (enabling &&
                !subscribedDevices.containsKey(device.address) &&
                !MultiControllerPolicy.hasConnectionCapacity(subscribedDevices.size)
            ) {
                logger(
                    "[BLE-A] subscription rejected device=${device.address} " +
                        "reason=max_controllers limit=${MultiControllerPolicy.MAX_CONTROLLERS}"
                )
                if (responseNeeded) {
                    sendWriteResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        value
                    )
                }
                runCatching { gattServer?.cancelConnection(device) }
                return
            }

            when {
                value?.contentEquals(
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                ) == true -> {
                    subscribedDevices[device.address] = device
                    notifyFailureCountByAddress[device.address] = 0
                    lastSubscribedAtMs = SystemClock.elapsedRealtime()
                    subscribedAtByAddress[device.address] = lastSubscribedAtMs
                    lastCommandSuccessAtByAddress.remove(device.address)
                    lastNotifySuccessAtByAddress.remove(device.address)
                    lastLinkProbeAtByAddress.remove(device.address)
                    resetClientCapabilities(device.address)
                    val capabilityGeneration = connectionCommandCoordinator.beginNegotiation(
                        device.address,
                        lastSubscribedAtMs
                    )
                    logger("[BLE-A] status notify subscribed: device=${device.address}")
                    logger("[ReconnectSync] notify subscribed device=${device.address}")
                    startAutoPush()
                    scheduleReconnectStateSync(device, "notify_subscribed")
                    albumArtHandler.postDelayed({
                        if (connectionCommandCoordinator.useLegacyIfCurrent(
                                device.address,
                                capabilityGeneration
                            )
                        ) {
                            logger(
                                "[BLE-A] client capability timeout, legacy fallback " +
                                    "device=${device.address}"
                            )
                            sendPendingAlbumArtIfAny()
                        }
                    }, CLIENT_CAPABILITY_WAIT_MS)
                }

                value?.contentEquals(
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                ) == true -> {
                    subscribedDevices.remove(device.address)
                    notifyQueue.removeDevice(device.address)
                    notifyFailureCountByAddress.remove(device.address)
                    subscribedAtByAddress.remove(device.address)
                    lastCommandSuccessAtByAddress.remove(device.address)
                    lastNotifySuccessAtByAddress.remove(device.address)
                    lastNotifyFailureAtByAddress.remove(device.address)
                    lastLinkProbeAtByAddress.remove(device.address)
                    resetClientCapabilities(device.address)
                    connectionCommandCoordinator.remove(device.address)
                    albumArtRequestsInFlight
                        .filter { it.startsWith("${device.address}|") }
                        .forEach(albumArtRequestsInFlight::remove)
                    albumArtRequestCompletedAtMs.keys
                        .filter { it.startsWith("${device.address}|") }
                        .forEach(albumArtRequestCompletedAtMs::remove)
                    albumArtPendingRequests.entries.removeAll {
                        it.value.device.address == device.address
                    }
                    if (subscribedDevices.isEmpty()) {
                        clearSharedClientRuntimeState()
                    }
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

    /**
     * Android can preserve the physical central connection while this process
     * recreates its GATT database.  In that state iOS still believes it is
     * subscribed to the old status characteristic, while this server has no
     * CCCD subscription and cannot send metadata, lyrics, or artwork.  Give a
     * normal discovery a short grace period, then force exactly one disconnect
     * for this database generation so the iOS reconnect watchdog rediscovers
     * the characteristics and writes the new CCCD.
     */
    @SuppressLint("MissingPermission")
    private fun scheduleGattRediscoveryIfUnsubscribed(
        device: BluetoothDevice,
        reason: String
    ) {
        val address = device.address
        val generation = gattServiceGeneration
        gattLifecycleHandler.postDelayed({
            synchronized(this) {
                if (!started ||
                    generation != gattServiceGeneration ||
                    statusCharacteristic == null ||
                    !connectedDeviceAddresses.contains(address) ||
                    subscribedDevices.containsKey(address)
                ) {
                    return@synchronized
                }
                if (forcedRediscoveryGenerationByAddress[address] == generation) {
                    return@synchronized
                }
                forcedRediscoveryGenerationByAddress[address] = generation
                logger(
                    "[ReconnectSync] force rediscovery reason=$reason " +
                        "device=$address generation=$generation " +
                        "cause=connected_without_cccd"
                )
                try {
                    gattServer?.cancelConnection(device)
                } catch (securityException: SecurityException) {
                    logger("[ReconnectSync] force rediscovery failed: missing permission")
                } catch (exception: Exception) {
                    logger(
                        "[ReconnectSync] force rediscovery failed: " +
                            exception.message
                    )
                }
            }
        }, GATT_REDISCOVERY_GRACE_MS)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (started && gattServer != null) {
            logger("[BLE-A] GATT server already started; skip initialization")
            logger("[BleGattServer] already started")
            logger("[MediaSessionReader] already registered")
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
        gattServiceGeneration += 1
        forcedRediscoveryGenerationByAddress.clear()
        logger("[BLE-A] GATT server started")
        logger("[BleGattServer] started")
        logger("[MediaSessionReader] registered")

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
        schedulePlaybackUiRefresh("gatt_start")
        return true
    }

    private fun schedulePlaybackUiRefresh(reason: String) {
        if (!started) return
        playbackUiRefreshReason.set(reason)
        playbackUiRefreshPending.set(true)
        if (!playbackUiRefreshInFlight.compareAndSet(false, true)) return
        executionHub.foregroundIO.execute {
            try {
                do {
                    playbackUiRefreshPending.set(false)
                    if (!started) break
                    val latestReason = playbackUiRefreshReason.get()
                    val source = playbackStateReader.readPlaybackState()
                    onPlaybackUiState(source)
                    logger(
                        "[PlayerUI] service refresh reason=$latestReason " +
                            "title=${source.optString("title").take(48)}"
                    )
                } while (playbackUiRefreshPending.get())
            } catch (exception: Exception) {
                logger(
                    "[PlayerUI] service refresh failed " +
                        "reason=${playbackUiRefreshReason.get()} error=${exception.message}"
                )
            } finally {
                playbackUiRefreshInFlight.set(false)
                // Close the race where a request arrives after the loop's final
                // pending check but before inFlight is cleared.
                if (started && playbackUiRefreshPending.get()) {
                    schedulePlaybackUiRefresh(playbackUiRefreshReason.get())
                }
            }
        }
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
        notifyQueue.shutdown(reason)
        lastAlbumArtKey = null
        currentAlbumArtId = null
        albumArtRequestsInFlight.clear()
        albumArtRequestCompletedAtMs.clear()
        pendingAlbumArt = null
        pendingFullLyricsRequests.clear()
        pendingLyricWindowRequests.clear()
        lyricsTransferCoordinator.reset()
        albumArtTransferCoordinator.reset()
        connectionCommandCoordinator.clear()
        multiControllerCommandGate.reset()
        albumArtTaskGeneration += 1
        albumArtFastPathTask?.cancel(true)
        albumArtFastPathTask = null
        albumArtFastPathProtocolId = null
        albumArtFastPathCompletionPending = false
        albumArtPendingRequests.clear()
        albumArtUnavailableProtocolIds.clear()
        albumArtPreparationLocks.clear()
        albumArtHandler.removeCallbacksAndMessages(null)
        mediaCallbackThread.quitSafely()
        gattLifecycleHandler.removeCallbacksAndMessages(null)
        PlayerNotificationListenerService.removeQqMusicArtworkListener(qqMusicArtworkListener)
        lastAutoPushSongKey = null
        lastAutoPushPlaying = null
        subscribedDevices.clear()
        connectedDeviceAddresses.clear()
        connectedDevices.clear()
        forcedRediscoveryGenerationByAddress.clear()
        mtuByAddress.clear()
        recentConnectionCallbacks.clear()
        recentMtuCallbacks.clear()
        notifyFailureCountByAddress.clear()
        subscribedAtByAddress.clear()
        lastCommandSuccessAtByAddress.clear()
        lastNotifySuccessAtByAddress.clear()
        lastNotifyFailureAtByAddress.clear()
        lastLinkProbeAtByAddress.clear()
        statusCharacteristic = null
        playbackStateBuffer.shutdown()
        playbackStateReader.close()

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

    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        val currentId = currentAlbumArtId.orEmpty()
        synchronized(albumArtCache) {
            val staleEntries = albumArtCache.values
                .filter { it.protocolId != currentId }
                .distinctBy(AlbumArtCacheEntry::protocolId)
            staleEntries.forEach(::removeAlbumArtCacheEntryLocked)
        }
        synchronized(encodedAlbumArtCache) {
            val iterator = encodedAlbumArtCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val keepCurrentPreview = currentId.isNotBlank() &&
                    entry.key.startsWith("$currentId|preview|")
                if (!keepCurrentPreview) {
                    encodedAlbumArtCacheBytes -= entry.value.bytes.size
                    iterator.remove()
                }
            }
            encodedAlbumArtCacheBytes = encodedAlbumArtCacheBytes.coerceAtLeast(0)
        }
        albumArtPreparationLocks.keys.removeAll { key ->
            currentId.isBlank() || !key.startsWith("$currentId|")
        }
        logger("[AlbumArtCache] trim level=$level keepCurrent=${currentId.isNotBlank()}")
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

    fun healthSnapshot(
        serviceRunning: Boolean,
        advertisingState: String,
        lastRecoveryAt: Long,
        recoveryCount: Int,
        recovering: Boolean,
        reason: String? = null
    ): BleHealthSnapshot {
        val queueSnapshot = notifyQueue.snapshot()
        val connectedCount = connectedDeviceAddresses.size
        val subscribedCount = subscribedDevices.size
        val currentNotifyFailureCount = notifyFailureCount()
        val healthState = when {
            !serviceRunning -> BleHealthState.SERVICE_STOPPED
            recovering -> BleHealthState.RECOVERING
            serverState == ServerState.FAILED -> BleHealthState.ERROR
            !started || serverState == ServerState.STARTING -> BleHealthState.STARTING
            serverState != ServerState.READY -> BleHealthState.STARTING
            currentNotifyFailureCount >= NOTIFY_FAILURE_SUSPECT_THRESHOLD -> BleHealthState.SUSPECT
            hasAnyStaleSubscriber(SUSPECT_NO_SUCCESS_HEARTBEAT_MS) ->
                BleHealthState.SUSPECT
            subscribedCount > 0 && lastSuccessHeartbeatAtMs() > 0L -> BleHealthState.CONTROLLABLE
            subscribedCount > 0 -> BleHealthState.SUBSCRIBED
            connectedCount > 0 -> BleHealthState.CONNECTED
            advertisingState.equals("STARTED", ignoreCase = true) -> BleHealthState.ADVERTISING
            else -> BleHealthState.STARTING
        }
        return BleHealthSnapshot(
            healthState = healthState,
            serviceRunning = serviceRunning,
            gattStarted = started,
            gattState = serverState.name,
            advertisingState = advertisingState,
            connectedCount = connectedCount,
            subscribedCount = subscribedCount,
            notificationInFlight = if (queueSnapshot.notificationInFlight) 1 else 0,
            pendingJobs = queueSnapshot.pendingJobCount,
            lastCommandSuccessAt = lastCommandSuccessAtMs,
            lastNotifySuccessAt = lastNotifySuccessAtMs,
            lastNotifyFailureAt = lastNotifyFailureAtMs,
            notifyFailureCount = currentNotifyFailureCount,
            lastRecoveryAt = lastRecoveryAt,
            recoveryCount = recoveryCount,
            reason = reason
        )
    }

    fun clearStaleSubscribers(reason: String): Boolean {
        val staleAddresses = subscribedDevices.keys
            .filterNot { connectedDeviceAddresses.contains(it) }
        if (staleAddresses.isEmpty()) {
            return false
        }
        staleAddresses.forEach { address ->
            subscribedDevices.remove(address)
            notifyQueue.removeDevice(address)
            connectionCommandCoordinator.remove(address)
            lyricsTransferCoordinator.resetAddress(address)
            albumArtTransferCoordinator.resetAddress(address)
            pendingFullLyricsRequests.remove(address)
            pendingLyricWindowRequests.remove(address)
            notifyFailureCountByAddress.remove(address)
            subscribedAtByAddress.remove(address)
            lastCommandSuccessAtByAddress.remove(address)
            lastNotifySuccessAtByAddress.remove(address)
            lastNotifyFailureAtByAddress.remove(address)
            lastLinkProbeAtByAddress.remove(address)
            albumArtRequestsInFlight
                .filter { it.startsWith("$address|") }
                .forEach(albumArtRequestsInFlight::remove)
            albumArtRequestCompletedAtMs.keys
                .filter { it.startsWith("$address|") }
                .forEach(albumArtRequestCompletedAtMs::remove)
            albumArtPendingRequests.entries.removeAll {
                it.value.device.address == address
            }
        }
        logger(
            "[BleHealth] watchdog action=clear_stale_subscribers " +
                "reason=$reason addresses=$staleAddresses"
        )
        if (subscribedDevices.isEmpty()) {
            clearSharedClientRuntimeState()
            stopAutoPushIfUnused()
        }
        return true
    }

    fun hasAnyStaleSubscriber(ageMs: Long): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        return subscribedDevices.keys.any { address ->
            BleSubscribedLinkPolicy.isActivityStale(
                subscribed = true,
                subscribedAtMs = subscribedAtByAddress[address] ?: lastSubscribedAtMs,
                lastCommandSuccessAtMs = lastCommandSuccessAtByAddress[address] ?: 0L,
                lastNotifySuccessAtMs = lastNotifySuccessAtByAddress[address] ?: 0L,
                nowMs = nowMs,
                maxAgeMs = ageMs
            )
        }
    }

    fun notifyFailureCount(): Int = notifyFailureCountByAddress.values.maxOrNull() ?: 0

    fun failingSubscriberAddresses(threshold: Int): List<String> {
        if (threshold <= 0) return emptyList()
        return subscribedDevices.keys.filter { address ->
            (notifyFailureCountByAddress[address] ?: 0) >= threshold
        }
    }

    /**
     * Queues one tiny notification for each silent subscriber. A successful
     * notification refreshes that device's heartbeat through recordNotifySuccess;
     * a real callback failure is handled by the existing per-device failure
     * counter. Merely being quiet never tears down the shared GATT server.
     */
    fun probeStaleSubscribers(
        staleAfterMs: Long,
        minimumProbeIntervalMs: Long
    ): Int {
        val nowMs = SystemClock.elapsedRealtime()
        val candidates = subscribedDevices.entries.filter { (address, _) ->
            BleSubscribedLinkPolicy.shouldSendProbe(
                subscribedAtMs = subscribedAtByAddress[address] ?: lastSubscribedAtMs,
                lastCommandSuccessAtMs = lastCommandSuccessAtByAddress[address] ?: 0L,
                lastNotifySuccessAtMs = lastNotifySuccessAtByAddress[address] ?: 0L,
                lastProbeAtMs = lastLinkProbeAtByAddress[address] ?: 0L,
                nowMs = nowMs,
                staleAfterMs = staleAfterMs,
                minimumProbeIntervalMs = minimumProbeIntervalMs
            )
        }
        candidates.forEach { (address, device) ->
            lastLinkProbeAtByAddress[address] = nowMs
            // Keep the payload below the default 20-byte ATT payload so the
            // probe works even before an MTU upgrade. Unknown status types are
            // intentionally ignored by old controllers after refreshing health.
            sendShortJsonIfFits(
                device = device,
                type = "linkProbe",
                value = JSONObject().put("type", "link")
            )
        }
        return candidates.size
    }

    @SuppressLint("MissingPermission")
    fun disconnectSubscribers(addresses: Collection<String>, reason: String): Int {
        var requested = 0
        addresses.distinct().forEach { address ->
            val device = subscribedDevices[address] ?: return@forEach
            logger(
                "[BleHealth] isolate unhealthy controller device=$address " +
                    "reason=$reason failures=${notifyFailureCountByAddress[address] ?: 0}"
            )
            notifyFailureCountByAddress[address] = 0
            try {
                gattServer?.cancelConnection(device)
                requested += 1
            } catch (securityException: SecurityException) {
                logger("[BleHealth] controller isolation failed device=$address permission")
            } catch (exception: Exception) {
                logger(
                    "[BleHealth] controller isolation failed device=$address " +
                        "reason=${exception.message}"
                )
            }
        }
        return requested
    }

    private fun updateServerState(newState: ServerState) {
        val oldState = serverState
        if (oldState == newState) {
            return
        }
        serverState = newState
        logger("[BLE-GATT] state $oldState -> $newState")
    }

    private fun recordCommandSuccess(command: String, deviceAddress: String?) {
        lastCommandSuccessAtMs = SystemClock.elapsedRealtime()
        if (!deviceAddress.isNullOrBlank()) {
            lastCommandSuccessAtByAddress[deviceAddress] = lastCommandSuccessAtMs
        }
        logger("[BleHealth] state=CONTROLLABLE reason=command_success cmd=$command")
    }

    private fun recordNotifySuccess(deviceAddress: String, type: String) {
        lastNotifySuccessAtMs = SystemClock.elapsedRealtime()
        if (deviceAddress.isNotBlank()) {
            lastNotifySuccessAtByAddress[deviceAddress] = lastNotifySuccessAtMs
            notifyFailureCountByAddress[deviceAddress] = 0
        }
        val now = lastNotifySuccessAtMs
        if (now - lastHealthSuccessLogAtMs >= HEALTH_SUCCESS_LOG_INTERVAL_MS) {
            lastHealthSuccessLogAtMs = now
            logger(
                "[BleHealth] state=CONTROLLABLE reason=notify_success " +
                    "device=$deviceAddress type=$type"
            )
        }
    }

    private fun recordNotifyFailure(
        deviceAddress: String,
        type: String,
        status: Int,
        reason: String
    ) {
        lastNotifyFailureAtMs = SystemClock.elapsedRealtime()
        if (deviceAddress.isNotBlank()) {
            lastNotifyFailureAtByAddress[deviceAddress] = lastNotifyFailureAtMs
            notifyFailureCountByAddress[deviceAddress] =
                (notifyFailureCountByAddress[deviceAddress] ?: 0) + 1
        }
        val currentFailureCount = notifyFailureCount()
        logger(
            "[BleHealth] notify failure device=$deviceAddress type=$type status=$status " +
                "reason=$reason failureCount=$currentFailureCount " +
                "connected=${connectedDeviceAddresses.size} " +
                "subscribed=${subscribedDevices.size}"
        )
    }

    private fun lastSuccessHeartbeatAtMs(): Long {
        return lastNotifySuccessAtMs
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

    private fun handleCommand(
        sourceDevice: BluetoothDevice,
        command: String,
        request: JSONObject,
        seq: String? = null
    ) {
        val sourceAddress = sourceDevice.address
        val commandReceivedElapsedMs = SystemClock.elapsedRealtime()
        when (command) {
            "PLAY_PAUSE",
            "NEXT",
            "PREVIOUS",
            "VOLUME_UP",
            "VOLUME_DOWN" -> {
                cancelHistoryTransfersForControl(sourceAddress, command)
                if (command in MULTI_CONTROLLER_DEDUP_COMMANDS &&
                    !multiControllerCommandGate.shouldExecute(
                        command,
                        sourceAddress,
                        SystemClock.elapsedRealtime()
                    )
                ) {
                    logger(
                        "[MultiController] duplicate control suppressed " +
                            "cmd=$command device=$sourceAddress"
                    )
                    sendPlaybackState()
                    return
                }
                if (command == "NEXT") {
                    playbackStateReader.notifyManualNextHint(seq)
                } else if (command == "PREVIOUS") {
                    playbackStateReader.notifyManualPreviousHint(seq)
                }
                mediaCommandExecutor.execute(command, seq)
                if (command in MULTI_CONTROLLER_DEDUP_COMMANDS) {
                    scheduleAuthoritativeStateBroadcast(command)
                }
            }

            "SEEK_TO" -> {
                if (request.opt("position") !is Number) {
                    sendCommandError(
                        sourceDevice, request, "protocol", "invalid_position", false
                    )
                    return
                }
                cancelHistoryTransfersForControl(sourceAddress, command)
                val position = request.optLong("position").coerceAtLeast(0L)
                currentWordPushEngine.resetTimeline()
                logger("[BLE-A][Seek] position=$position")
                mediaCommandExecutor.seekTo(position, seq)
                logger("[BLE-A][Seek] seekTo called")
                sendPlaybackState()
                scheduleAuthoritativeStateBroadcast(command)
            }
            "SET_VOLUME" -> {
                if (request.opt("volume") !is Number) {
                    sendCommandError(
                        sourceDevice, request, "protocol", "invalid_volume", false
                    )
                    return
                }
                cancelHistoryTransfersForControl(sourceAddress, command)
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
                sendPlaybackStateTo(sourceDevice, includeAlbumArt = true)
            }
            "PING" -> sendPong(sourceDevice, request, commandReceivedElapsedMs)
            "GET_VOLUME" -> sendStatusMessageTo(
                sourceDevice,
                mediaCommandExecutor.createVolumeState().toString()
            )
            "GET_LOGS" -> {
                val limit = request.optInt("limit", DEFAULT_LOG_LIMIT)
                    .coerceIn(0, MAX_LOG_LIMIT)
                sendRemoteLogs(sourceDevice, limit)
            }
            "ALBUM_ART_SKIP" -> handleAlbumArtSkip(request)
            "ALBUM_ART_REQUEST" -> handleAlbumArtRequest(sourceDevice, request)
            "CLIENT_CAPABILITIES" -> handleClientCapabilities(sourceDevice, request)
            "DUMP_MEDIA_FIELDS" -> sendMediaFieldDump(sourceDevice)
            "GET_FULL_LYRICS" -> sendFullLyrics(sourceDevice, request)
            "GET_LYRIC_WINDOW" -> sendLyricWindow(sourceDevice, request)
            "RETRY_TRANSFER" -> retryTransfer(sourceDevice, request)
            "GET_LYRIC_SECONDARY" -> sendLyricSecondary(sourceDevice, request)
            "GET_LYRIC_DIAGNOSTIC" -> sendLyricDiagnostic(sourceDevice, request)
            "GET_PLAY_HISTORY_PAGE" -> sendPlayHistoryPage(sourceDevice, request)
            "GET_PLAY_HISTORY_SINCE" -> sendPlayHistorySince(sourceDevice, request)
            "GET_PLAY_STATS" -> sendPlayStats(sourceDevice, request)
            else -> {
                logger("[BLE-A] unknown command: $command")
                sendCommandError(
                    device = sourceDevice,
                    request = request,
                    domain = "protocol",
                    code = "unknown_command",
                    retryable = false
                )
            }
        }
    }

    private fun cancelHistoryTransfersForControl(sourceAddress: String, command: String) {
        if (notifyQueue.hasJobTypeActiveOrQueued(PLAY_HISTORY_JOB_TYPE, sourceAddress) ||
            notifyQueue.hasJobTypeActiveOrQueued(PLAY_STATS_JOB_TYPE, sourceAddress) ||
            notifyQueue.hasJobTypeActiveOrQueued(LYRIC_SECONDARY_JOB_TYPE, sourceAddress)
        ) {
            logger("[HistoryBLE] cancelled reason=control command cmd=$command")
            logger("[LyricSecondary] cancelled reason=control command cmd=$command")
            notifyQueue.cancelJobTypes(
                setOf(
                    PLAY_HISTORY_JOB_TYPE,
                    PLAY_STATS_JOB_TYPE,
                    LYRIC_SECONDARY_JOB_TYPE
                ),
                "control command",
                sourceAddress
            )
        }
    }

    private fun scheduleAuthoritativeStateBroadcast(command: String) {
        val delayMs = when (command) {
            "PLAY_PAUSE" -> 100L
            "SEEK_TO" -> 150L
            "NEXT", "PREVIOUS" -> 220L
            else -> 120L
        }
        albumArtHandler.postDelayed({
            if (!started || subscribedDevices.isEmpty()) return@postDelayed
            sendPlaybackState(includeAlbumArt = command == "NEXT" || command == "PREVIOUS")
        }, delayMs)
    }

    private fun handleClientCapabilities(device: BluetoothDevice, request: JSONObject) {
        val notifyPayload = maximumPayloadFor(device)
        val capabilities = parseClientCapabilities(request, notifyPayload)
        connectionCommandCoordinator.accept(device.address, capabilities)
        logger(
            "[BLE-A] client capability protocolVersion=${capabilities.protocolVersion} " +
                "albumArtBinary=${capabilities.binaryAlbumArt} " +
                "fullLyricsZlib=${capabilities.fullLyricsZlib} " +
                "lyricWindow=${capabilities.lyricWindow} ping=${capabilities.ping} " +
                "clockSyncV1=${capabilities.clockSyncV1} " +
                "transferRetry=${capabilities.transferRetry} " +
                "requestedF3=${request.optInt("f3").takeIf { request.has("f3") }} " +
                "notifyPayload=$notifyPayload negotiatedF2=${capabilities.f2} " +
                "negotiatedF3=${capabilities.f3}"
        )
        val ack = BleCapabilitiesAckPolicy.build(
            capabilities = capabilities,
            requestedF3Present = request.has("f3"),
            sessionId = v3SessionCoordinator.sessionId
        )
        logger("[BLE-V3] capabilities ack payload=$ack")
        sendShortJsonIfFits(device, "clientCapabilitiesAck", ack)
        sendPendingAlbumArtIfAny()
    }

    private fun parseClientCapabilities(
        request: JSONObject,
        notifyPayload: Int
    ): ConnectionCommandCoordinator.Capabilities {
        val protocolVersion = request.optInt("protocolVersion", 1).coerceAtLeast(1)
        val binaryAlbumArt = request.optBoolean("albumArtBinary", false)
        val fullLyricsZlib = request.optBoolean("fullLyricsZlib", false)
        val lyricWindow = request.optBoolean("lyricWindow", false)
        val ping = request.optBoolean("ping", false)
        val clockSyncV1 = request.optBoolean("clockSyncV1", false)
        val transferRetry = request.optBoolean("transferRetry", false)
        return ConnectionCommandCoordinator.Capabilities(
            protocolVersion = protocolVersion,
            binaryAlbumArt = binaryAlbumArt,
            fullLyricsZlib = fullLyricsZlib,
            lyricWindow = lyricWindow,
            ping = ping,
            clockSyncV1 = clockSyncV1,
            transferRetry = transferRetry,
            f2 = BleV3CapabilityPolicy.f2(
                binaryAlbumArt,
                fullLyricsZlib,
                lyricWindow,
                ping,
                clockSyncV1,
                transferRetry
            ),
            f3 = BleV3CapabilityPolicy.negotiateF3(
                protocolVersion = protocolVersion,
                requestedF3 = request.optInt("f3").takeIf { request.has("f3") },
                notifyPayload = notifyPayload
            ),
            negotiated = true
        )
    }

    private fun resetClientCapabilities(address: String) {
        connectionCommandCoordinator.invalidate(address)
        lyricsTransferCoordinator.resetAddress(address)
        albumArtTransferCoordinator.resetAddress(address)
        pendingFullLyricsRequests.remove(address)
        pendingLyricWindowRequests.remove(address)
        v3SessionCoordinator.resetAddress(address)
    }

    private fun clearSharedClientRuntimeState() {
        lastAlbumArtKey = null
        currentAlbumArtId = null
        currentAlbumArtPlaybackState = null
        albumArtRequestsInFlight.clear()
        albumArtRequestCompletedAtMs.clear()
        albumArtPendingRequests.clear()
        albumArtUnavailableProtocolIds.clear()
        pendingAlbumArt = null
        pendingFullLyricsRequests.clear()
        pendingLyricWindowRequests.clear()
        connectionCommandCoordinator.clear()
        lyricsTransferCoordinator.clearRetryState()
        albumArtTransferCoordinator.reset()
        multiControllerCommandGate.reset()
        v3SessionCoordinator.clear()
        lastAutoPushSongKey = null
        lastAutoPushPlaying = null
    }

    private fun sendPong(
        device: BluetoothDevice,
        request: JSONObject,
        serverReceiveElapsedMs: Long
    ) {
        val serverSendElapsedMs = SystemClock.elapsedRealtime()
        val value = JSONObject()
            .put("type", "pong")
            .put("seq", request.optString("seq"))
            .put("time", System.currentTimeMillis())
        if (request.optBoolean("clockSyncV1", false) &&
            request.has("clientSendElapsedMs")
        ) {
            value
                .put("clientSendElapsedMs", request.optLong("clientSendElapsedMs"))
                .put("serverReceiveElapsedMs", serverReceiveElapsedMs)
                .put("serverSendElapsedMs", serverSendElapsedMs)
        }
        sendShortJsonIfFits(
            device = device,
            type = "pong",
            value = value
        )
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

    private fun sendPlaybackStateTo(
        device: BluetoothDevice,
        includeAlbumArt: Boolean = false
    ) {
        if (!subscribedDevices.containsKey(device.address)) return
        val source = playbackStateReader.readPlaybackState()
        onPlaybackUiState(source)
        if (includeAlbumArt) {
            sendTrackInfoTo(device, source)
        }
        sendCompactPlaybackStateTo(device, source)
        if (includeAlbumArt) {
            val protocolId = buildAlbumArtProtocolId(source)
            if (protocolId.isNotBlank()) {
                enqueueAlbumArtOfferOrPending(
                    PendingAlbumArt(
                        cacheKey = buildAlbumArtCacheKey(source),
                        protocolId = protocolId,
                        playbackState = JSONObject(source.toString())
                    ),
                    targetDevice = device
                )
            }
        }
    }

    private fun scheduleReconnectStateSync(device: BluetoothDevice, reason: String) {
        val address = device.address ?: "unknown"
        val now = SystemClock.elapsedRealtime()
        val previous = reconnectSyncLastAtByAddress[address] ?: 0L
        if (now - previous < RECONNECT_SYNC_COOLDOWN_MS) {
            logger(
                "[ReconnectSync] skip reason=cooldown device=$address " +
                    "ageMs=${now - previous}"
            )
            return
        }
        reconnectSyncLastAtByAddress[address] = now
        reconnectSyncExecutor.execute {
            performReconnectStateSync(address, reason)
        }
    }

    private fun performReconnectStateSync(address: String, reason: String) {
        val startedAt = SystemClock.elapsedRealtime()
        logger("[ReconnectSync] start reason=$reason device=$address")
        try {
            val device = subscribedDevices[address] ?: run {
                logger("[ReconnectSync] skipped device=$address reason=not_subscribed")
                return
            }
            val source = playbackStateReader.readPlaybackState()
            onPlaybackUiState(source)
            sendTrackInfoTo(device, source)
            val sentPlayback = sendCompactPlaybackStateTo(device, source)
            logger(
                "[ReconnectSync] send playbackState reason=reconnect_sync " +
                    "sent=$sentPlayback positionMs=${source.optLong("position")} " +
                    "playing=${source.optBoolean("playing")}"
            )

            logger(
                "[ReconnectSync] defer currentWord " +
                    "reason=wait_for_playbackState_baseline " +
                    "delayMs=$RECONNECT_SYNC_CURRENT_WORD_DELAY_MS"
            )
            SystemClock.sleep(RECONNECT_SYNC_CURRENT_WORD_DELAY_MS)

            val currentWord = currentWordPushEngine.pushCurrentWord(
                reason = "reconnect_sync",
                force = true
            )
            if (currentWord != null) {
                logger(
                    "[ReconnectSync] send currentWord reason=reconnect_sync " +
                        "line=${currentWord.lineIndex} word=${currentWord.wordIndex}"
                )
            } else {
                logger("[ReconnectSync] skip currentWord reason=not_available")
            }

            val protocolId = buildAlbumArtProtocolId(source)
            if (protocolId.isBlank()) {
                logger("[ReconnectSync] skip albumArtOffer reason=no active QQ track")
            } else {
                val pending = PendingAlbumArt(
                    cacheKey = buildAlbumArtCacheKey(source),
                    protocolId = protocolId,
                    playbackState = JSONObject(source.toString())
                )
                enqueueAlbumArtOfferOrPending(pending, targetDevice = device)
                logger(
                    "[ReconnectSync] send albumArtOffer reason=reconnect_sync " +
                        "id=${pending.protocolId}"
                )
            }
        } catch (exception: Exception) {
            logger("[ReconnectSync] failed reason=${exception.message}")
        } finally {
            logger("[ReconnectSync] done costMs=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }

    fun currentTrackSnapshot(): CurrentTrackSnapshot? {
        return playbackStateReader.runtimeCacheSnapshot().track
            ?: playbackStateReader.currentTrackSnapshot()
    }

    private fun sendLyricDiagnostic(device: BluetoothDevice, request: JSONObject) {
        if (!subscribedDevices.containsKey(device.address)) {
            logger("[LyricDiag] send skipped: controller unsubscribed")
            return
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        logger("[LyricDiag] request trackId=${request.optString("trackId")}")
        val requestedTrackId = request.optString("trackId")
        val snapshot = playbackStateReader.lyricDiagnosticSnapshot()
        val currentTrackId = snapshot.trackId
        if (currentTrackId.isBlank()) {
            sendShortJsonIfFits(
                device = device,
                type = "lyricDiagnosticUnavailable",
                value = JSONObject()
                    .put("type", "lyricDiagnosticUnavailable")
                    .put("reason", "no active track")
            )
            logger("[LyricDiag] response unavailable reason=no active track")
            return
        }
        if (requestedTrackId.isNotBlank() && requestedTrackId != currentTrackId) {
            sendShortJsonIfFits(
                device = device,
                type = "lyricDiagnosticUnavailable",
                value = JSONObject()
                    .put("type", "lyricDiagnosticUnavailable")
                    .put("trackId", requestedTrackId)
                    .put("reason", "stale track")
            )
            logger("[LyricDiag] response unavailable reason=stale track")
            return
        }
        sendShortJsonIfFits(
            device = device,
            type = "lyricDiagnostic",
            value = JSONObject()
                .put("type", "lyricDiagnostic")
                .put("trackId", currentTrackId)
                .put("songKey", snapshot.songKey)
                .put("title", snapshot.title)
                .put("artist", snapshot.artist)
                .put("status", snapshot.status)
                .put("source", snapshot.source)
                .put("reason", snapshot.reason)
                .put("lines", snapshot.lines)
                .put("lastAttemptAt", snapshot.lastAttemptAt)
                .put("nextRetryAt", snapshot.nextRetryAt)
                .put("retryCount", snapshot.retryCount)
                .put("cooldownUntil", snapshot.cooldownUntil)
                .put("fuzzyIndexReady", snapshot.fuzzyIndexReady)
                .put("qrcIndexLoaded", snapshot.qrcIndexLoaded)
                .put("maintenanceBusy", snapshot.maintenanceBusy)
                .put("waitingQqMusicCache", snapshot.waitingQqMusicCache)
                .put("suggestion", snapshot.suggestion)
                .put("recoveryState", snapshot.recoveryState)
                .put("recoveryRetryCount", snapshot.recoveryRetryCount)
                .put("recoveryExpiresAt", snapshot.recoveryExpiresAt)
                .put("lastRecoveryReason", snapshot.lastRecoveryReason)
                .put("recentQrcCandidateCount", snapshot.recentQrcCandidateCount)
        )
        logger(
            "[LyricDiag] response status=${snapshot.status} reason=${snapshot.reason} " +
                "recoveryState=${snapshot.recoveryState} " +
                "costMs=${SystemClock.elapsedRealtime() - startedAtMs}"
        )
        val runtimeSnapshot = playbackStateReader.runtimeCacheSnapshot()
        val runtimeMetrics = runtimeSnapshot.metrics
        logger(
            "[RuntimeCache] metrics hit=${runtimeMetrics.cacheHit} " +
                "miss=${runtimeMetrics.cacheMiss} refresh=${runtimeMetrics.refreshCount} " +
                "lastRefreshCostMs=${runtimeMetrics.lastRefreshCostMs} " +
                "lastTrackSwitchCostMs=${runtimeMetrics.lastTrackSwitchCostMs}"
        )
        val playbackDiffMetrics = runtimeSnapshot.playbackDiffMetrics
        logger(
            "[PlaybackDiff] metrics snapshots=${playbackDiffMetrics.snapshotBuildCount} " +
                "diffs=${playbackDiffMetrics.diffCount} " +
                "push=${playbackDiffMetrics.pushCount} " +
                "skip=${playbackDiffMetrics.skipCount} " +
                "trackChanged=${playbackDiffMetrics.trackChangedCount} " +
                "wordChanged=${playbackDiffMetrics.wordChangedCount} " +
                "positionJump=${playbackDiffMetrics.positionJumpCount} " +
                "positionSmall=${playbackDiffMetrics.positionSmallSkipCount} " +
                "identical=${playbackDiffMetrics.identicalSkipCount}"
        )
        val predictiveMetrics = playbackStateReader.predictiveLyricsMetricsSnapshot()
        logger(
            "[PredictiveLyrics] metrics candidates=${predictiveMetrics.candidateCount} " +
                "queueCandidates=${predictiveMetrics.mediaSessionQueueCandidateCount} " +
                "manualNextHints=${predictiveMetrics.manualNextHintCount} " +
                "manualPreviousHints=${predictiveMetrics.manualPreviousHintCount} " +
                "historyCandidates=${predictiveMetrics.historyTransitionCandidateCount} " +
                "selected=${predictiveMetrics.selectedCount} " +
                "rejected=${predictiveMetrics.rejectedCount} " +
                "expired=${predictiveMetrics.expiredCount} " +
                "invalidated=${predictiveMetrics.invalidatedCount} " +
                "preloadStart=${predictiveMetrics.preloadStartCount} " +
                "preloadHit=${predictiveMetrics.preloadHitCount} " +
                "preloadMiss=${predictiveMetrics.preloadMissCount} " +
                "preloadSuccess=${predictiveMetrics.preloadSuccessCount} " +
                "preloadFailed=${predictiveMetrics.preloadFailedCount} " +
                "applyHit=${predictiveMetrics.applyHitCount} " +
                "applyMiss=${predictiveMetrics.applyMissCount} " +
                "identityMismatch=${predictiveMetrics.identityMismatchCount} " +
                "preloadCostMaxMs=${predictiveMetrics.preloadCostMaxMs} " +
                "applyCostMaxMs=${predictiveMetrics.applyCostMaxMs}"
        )
        currentWordPushEngine.logMetrics()
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

    fun retryCurrentLyricsFromWatcher(reason: String): Boolean {
        return playbackStateReader.retryActiveLyricsFromWatcher(reason)
    }

    fun notifyLyricIncrementalBatchDone(groupIds: Collection<String>) {
        playbackStateReader.notifyLyricIncrementalBatchDone(groupIds)
    }

    fun manualRefreshCurrentLyric(): Boolean {
        return playbackStateReader.manualRefreshCurrentLyric()
    }

    /**
     * Debug/UI refresh entry point that does not require an active BLE subscriber.
     * Refresh the QQ Music MediaSession snapshot first so LyricManager has the
     * current song identity before clearing only that song's retry state.
     */
    fun refreshCurrentPlaybackAndLyric(): Boolean {
        val playbackState = playbackStateReader.readPlaybackState()
        val manualRefreshStarted = playbackStateReader.manualRefreshCurrentLyric()
        return manualRefreshStarted || playbackState.optString("title").isNotBlank()
    }

    private fun sendShortJsonIfFits(
        device: BluetoothDevice,
        type: String,
        value: JSONObject
    ) {
        val maximumPayload = maximumPayloadFor(device)
        val capabilities = connectionCommandCoordinator.capabilities(device.address)
        val outgoing = if (
            type != "clientCapabilitiesAck" &&
            capabilities.statusMetaV1 &&
            !value.has("sid")
        ) {
            v3SessionCoordinator.decorate(device.address, value)
        } else {
            value
        }
        var bytes = outgoing.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > maximumPayload && outgoing !== value) {
            bytes = value.toString().toByteArray(Charsets.UTF_8)
        }
        if (bytes.size > maximumPayload) {
            logger("[BLE-A] short json skipped type=$type reason=payload too large bytes=${bytes.size}")
            return
        }
        notifyQueue.enqueueShort(
            device = device,
            type = type,
            value = bytes,
            delayAfterMs = SHORT_MESSAGE_DELAY_MS
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendStatusMessage(message: String): Boolean {
        if (gattServer == null || statusCharacteristic == null) {
            logger("[BLE-A] status notify skipped: GATT server unavailable")
            return false
        }
        val devices = subscribedDevices.values.toList()
        if (devices.isEmpty()) {
            logger("[BLE-A] status notify skipped: no controller subscriber")
            return false
        }
        var sent = false
        devices.forEach { device ->
            sent = sendStatusMessageTo(device, message) || sent
        }
        return sent
    }

    private fun sendStatusMessageTo(device: BluetoothDevice, message: String): Boolean {
        if (!subscribedDevices.containsKey(device.address)) return false
        val maximumPayload = maximumPayloadFor(device)
        val capabilities = connectionCommandCoordinator.capabilities(device.address)
        val originalObject = runCatching { JSONObject(message) }.getOrNull()
        val decorated = if (
            capabilities.statusMetaV1 &&
            originalObject != null &&
            !originalObject.has("sid")
        ) {
            v3SessionCoordinator.decorate(device.address, originalObject)
        } else {
            null
        }
        var value = (decorated?.toString() ?: message).toByteArray(Charsets.UTF_8)
        if (value.size > maximumPayload && decorated != null) {
            value = message.toByteArray(Charsets.UTF_8)
        }
        if (value.size > maximumPayload) {
            logger(
                "[BLE-A] status notify skipped: payload=${value.size} " +
                    "max=$maximumPayload type=${readMessageType(message)}"
            )
            TrackCapabilityTracker.onPayloadTooLarge()
            return false
        }

        val messageType = readMessageType(message)
        if ((messageType == "playbackState" ||
                messageType == "trackInfo" ||
                messageType == "volumeState" ||
                messageType == "currentWord") &&
            notifyQueue.hasLongJobActiveOrQueued(device.address)
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

    private fun sendCommandError(
        device: BluetoothDevice,
        request: JSONObject,
        domain: String,
        code: String,
        retryable: Boolean,
        retryAfterMs: Long? = null,
        trackId: String? = null,
        generation: Long? = null
    ) {
        val capabilities = connectionCommandCoordinator.capabilities(device.address)
        if (!capabilities.structuredErrorV1) return
        val value = BleV3PayloadFactory.commandError(
            seq = request.optString("seq"),
            command = request.optString("cmd"),
            domain = domain,
            code = code,
            retryable = retryable
        )
        retryAfterMs?.let { value.put("retryAfterMs", it) }
        trackId?.takeIf(String::isNotBlank)?.let { value.put("trackId", it) }
        generation?.let { value.put("generation", it) }
        enqueueV3Json(device, "commandError", value, BleNotifyQueue.Priority.P0_REALTIME)
    }

    private fun sendMediaLoadState(
        device: BluetoothDevice,
        resource: String,
        stage: String,
        reason: String,
        retryable: Boolean,
        trackId: String,
        generation: Long,
        retryAfterMs: Long? = null
    ) {
        val capabilities = connectionCommandCoordinator.capabilities(device.address)
        if (!capabilities.mediaLoadStateV1) return
        if (!v3SessionCoordinator.shouldSendMediaLoadState(
                address = device.address,
                resource = resource,
                trackId = trackId,
                generation = generation,
                stage = stage,
                reason = reason
            )
        ) return
        val value = JSONObject()
            .put("type", "mediaLoadState")
            .put("resource", resource)
            .put("stage", stage)
            .put("reason", reason)
            .put("retryable", retryable)
            .put("trackId", trackId)
            .put("generation", generation)
        retryAfterMs?.let { value.put("retryAfterMs", it) }
        enqueueV3Json(device, "mediaLoadState", value, BleNotifyQueue.Priority.P1_INTERACTIVE)
    }

    private fun enqueueV3Json(
        device: BluetoothDevice,
        type: String,
        value: JSONObject,
        priority: BleNotifyQueue.Priority
    ) {
        val decorated = v3SessionCoordinator.decorate(device.address, value)
        val bytes = decorated.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > maximumPayloadFor(device)) {
            logger("[BLE-V3] skipped type=$type reason=payload too large bytes=${bytes.size}")
            return
        }
        logger("[BLE-V3] enqueue type=$type payload=$decorated")
        notifyQueue.enqueueLongJob(
            type = type,
            device = device,
            packets = listOf(BleNotifyQueue.Packet(type, bytes, SHORT_MESSAGE_DELAY_MS)),
            priority = priority
        )
    }

    @Synchronized
    private fun startAutoPush() {
        if (autoPushExecutor != null) {
            return
        }

        logger("[BLE-A][AutoPush] started")
        CurrentTrackRuntimeCache.resetPlaybackDiffState()
        currentWordPushEngine.reset()
        playbackStateBuffer.reset()
        startCurrentWordPush()
        autoPushExecutor = executionHub.scheduled
        autoPushTask = executionHub.scheduled.scheduleAtFixedRate(
            {
                val now = SystemClock.elapsedRealtime()
                val interval = autoPushPollIntervalMs(lastAutoPushPlaying)
                if (now - lastAutoPushReadAtMs >= interval) {
                    pushPlaybackStateAutomatically()
                }
            },
            0L,
            AUTO_PUSH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    private fun stopAutoPushIfUnused() {
        if (subscribedDevices.isEmpty()) {
            stopAutoPush()
        }
    }

    @Synchronized
    private fun stopAutoPush() {
        if (autoPushExecutor == null) return
        autoPushTask?.cancel(false)
        autoPushTask = null
        autoPushExecutor = null
        stopCurrentWordPush()
        playbackStateBuffer.reset()
        CurrentTrackRuntimeCache.resetPlaybackDiffState()
        currentWordPushEngine.logMetrics()
        lastAutoPushReadAtMs = 0L
        logger("[BLE-A][AutoPush] stopped")
    }

    @Synchronized
    private fun wakeAutoPushFromQqNotification() {
        val executor = autoPushExecutor ?: return
        runCatching {
            executor.execute {
                pushPlaybackStateAutomatically()
            }
        }.onFailure {
            transientLogger("[BLE-A][AutoPush] notification wake skipped")
        }
    }

    private fun pushPlaybackStateAutomatically() {
        if (subscribedDevices.isEmpty()) {
            stopAutoPushIfUnused()
            return
        }

        try {
            lastAutoPushReadAtMs = SystemClock.elapsedRealtime()
            val source = playbackStateReader.readPlaybackState()
            onPlaybackUiState(source)
            val songChanged = logAutoPushStateChanges(source)
            val snapshot = CurrentTrackRuntimeCache.buildPlaybackStateSnapshot(
                connectionState = "subscribed"
            )
            val diff = snapshot?.let {
                CurrentTrackRuntimeCache.diffFromLastSent(it)
            }
            if (diff != null) {
                verboseLogger(
                    "[PlaybackDiff] candidate reason=${diff.reason} " +
                        "type=${diff.type} shouldPush=${diff.shouldPush} " +
                        "positionMs=${diff.positionMs} lastPositionMs=${diff.lastPositionMs} " +
                        "lineIndex=${diff.lineIndex} lastLineIndex=${diff.lastLineIndex} " +
                        "currentWordKeyChanged=${diff.currentWordKeyChanged}"
                )
            }
            val diffTrackChanged = diff?.type == PlaybackStateDiffType.TrackChanged
            if (songChanged || diffTrackChanged) {
                sendTrackInfo(source)
            }
            if (LogConfig.DEBUG_VERBOSE_LOG) {
                verboseLogger(
                    "[BLE-A][AutoPush] playbackState " +
                        "position=${source.optLong("position")} " +
                        "duration=${source.optLong("duration")}"
                )
            }
            val result = if (diff?.type == PlaybackStateDiffType.CurrentWordChanged) {
                logger(
                    "[PlaybackDiff] word changed -> currentWord " +
                        "fields=${diff.changedFields} deltaMs=${diff.positionDeltaMs}"
                )
                val sentState = currentWordPushEngine.pushCurrentWord()
                if (sentState != null) {
                    logger(
                        "[PlaybackDiff] currentWord sent without marking " +
                            "playbackState snapshot sent"
                    )
                    true
                } else {
                    CurrentTrackRuntimeCache.markPlaybackSnapshotSkipped(diff)
                    true
                }
            } else if (diff == null || diff.shouldPush) {
                logger(
                    "[PlaybackDiff] push reason=${diff?.reason ?: "unknown"} " +
                        "type=${diff?.type ?: "unknown"} " +
                        "fields=${diff?.changedFields.orEmpty()} " +
                        "deltaMs=${diff?.positionDeltaMs ?: 0L} " +
                        "positionMs=${diff?.positionMs ?: source.optLong("position")} " +
                        "lastPositionMs=${diff?.lastPositionMs ?: 0L} " +
                        "lineIndex=${diff?.lineIndex ?: -1} " +
                        "lastLineIndex=${diff?.lastLineIndex ?: -1} " +
                        "currentWordKeyChanged=${diff?.currentWordKeyChanged ?: false}"
                )
                if (snapshot != null && diff != null) {
                    playbackStateBuffer.offer(source, snapshot, diff)
                } else {
                    sendCompactPlaybackState(source)
                }
                true
            } else {
                CurrentTrackRuntimeCache.markPlaybackSnapshotSkipped(diff)
                if (LogConfig.DEBUG_VERBOSE_LOG) {
                    verboseLogger(
                        "[PlaybackDiff] skip reason=${diff.reason} " +
                            "deltaMs=${diff.positionDeltaMs} " +
                            "positionMs=${diff.positionMs} " +
                            "lastPositionMs=${diff.lastPositionMs} " +
                            "lineIndex=${diff.lineIndex} " +
                            "lastLineIndex=${diff.lastLineIndex} " +
                            "currentWordKeyChanged=${diff.currentWordKeyChanged}"
                    )
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPlaybackDiffSkipLogAtMs >=
                        PLAYBACK_DIFF_SKIP_LOG_INTERVAL_MS
                    ) {
                        lastPlaybackDiffSkipLogAtMs = now
                        logger(
                            "[PlaybackDiff] skip reason=${diff.reason} " +
                                "positionMs=${diff.positionMs} " +
                                "lastPositionMs=${diff.lastPositionMs} " +
                                "lineIndex=${diff.lineIndex} " +
                                "lastLineIndex=${diff.lastLineIndex} " +
                                "currentWordKeyChanged=${diff.currentWordKeyChanged}"
                        )
                    }
                }
                true
            }
            if (songChanged || diffTrackChanged) {
                sendAlbumArtIfSongChanged(source)
            }
            if (LogConfig.DEBUG_VERBOSE_LOG) {
                verboseLogger("[BLE-A][AutoPush] notify result=$result")
            }
        } catch (exception: Exception) {
            logger("[BLE-A][AutoPush] error=${exception.message}")
        }
    }

    @Synchronized
    private fun startCurrentWordPush() {
        if (currentWordExecutor != null) {
            return
        }
        logger("[CurrentWordPush] started")
        currentWordExecutor = executionHub.scheduled
        scheduleCurrentWordPush(CURRENT_WORD_INITIAL_DELAY_MS)
    }

    @Synchronized
    private fun scheduleCurrentWordPush(delayMs: Long) {
        val executor = currentWordExecutor ?: return
        currentWordPushTask?.cancel(false)
        RealtimeTrace.record(
            stage = "currentWordScheduleCreated",
            trackId = CurrentTrackRuntimeCache.trackSnapshot()?.trackId,
            generation = CurrentTrackRuntimeCache.currentGeneration(),
            payloadType = "currentWord",
            processingMs = delayMs.coerceAtLeast(0L),
            result = "scheduled"
        )
        currentWordPushTask = executor.schedule(
            {
                if (subscribedDevices.isEmpty()) {
                    stopCurrentWordPush()
                    return@schedule
                }
                val sentState = currentWordPushEngine.pushCurrentWord(reason = "boundary")
                if (sentState != null && LogConfig.DEBUG_VERBOSE_LOG) {
                    verboseLogger("[CurrentWordPush] sent at lyric boundary")
                }
                val nextDelay = CurrentTrackRuntimeCache.nextCurrentWordBoundaryDelayMs(
                    maximumDriftCorrectionMs = CURRENT_WORD_DRIFT_CORRECTION_MS
                )
                if (nextDelay != null) {
                    scheduleCurrentWordPush(nextDelay)
                } else {
                    currentWordPushTask = null
                    logger("[CurrentWordPush] paused; boundary task suspended")
                }
            },
            delayMs.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    private fun stopCurrentWordPush() {
        if (currentWordExecutor == null) return
        currentWordPushTask?.cancel(true)
        currentWordPushTask = null
        currentWordExecutor = null
        currentWordPushEngine.reset()
        logger("[CurrentWordPush] stopped")
    }

    private fun logAutoPushStateChanges(state: JSONObject): Boolean {
        val songKey = buildAlbumArtCacheKey(state)
        val title = state.optString("title")
        val playing = state.optBoolean("playing")
        val songChanged = songKey != lastAutoPushSongKey

        if (songChanged) {
            val oldSongKey = lastAutoPushSongKey.orEmpty()
            lastAutoPushSongKey = songKey
            currentWordPushEngine.reset()
            notifyQueue.cancelJobTypes(setOf("currentWord"), "track changed")
            logger("[SongChange] detected title=$title")
            logger("[SongChange] old=$oldSongKey")
            logger("[SongChange] new=$songKey")
            logger("[BLE-A][AutoPush] song changed title=$title")
            scheduleCurrentWordPush(CURRENT_WORD_TRACK_SWITCH_DELAY_MS)
        }
        if (playing != lastAutoPushPlaying) {
            lastAutoPushPlaying = playing
            logger("[BLE-A][AutoPush] play state changed playing=$playing")
            if (playing) {
                scheduleCurrentWordPush(0L)
            } else {
                currentWordPushTask?.cancel(false)
                currentWordPushTask = null
            }
        }
        return songChanged
    }

    private fun flushBufferedPlaybackState(
        source: JSONObject,
        snapshot: com.example.playeragent.media.PlaybackStateSnapshot,
        diff: com.example.playeragent.media.PlaybackStateDiff,
        reason: String,
        coalesceCount: Int
    ): Boolean {
        val sent = sendCompactPlaybackState(source)
        val payloadSize = compactPlaybackStatePayload(source, includeClockSync = false)
            .toString()
            .toByteArray(Charsets.UTF_8)
            .size
        logger(
            "[PlaybackBuffer] sent reason=$reason diffReason=${diff.reason} " +
                "coalesce=$coalesceCount payloadSize=$payloadSize " +
                "positionMs=${diff.positionMs} lastPositionMs=${diff.lastPositionMs} " +
                "lineIndex=${diff.lineIndex} lastLineIndex=${diff.lastLineIndex} " +
                "currentWordKeyChanged=${diff.currentWordKeyChanged}"
        )
        if (sent) {
            CurrentTrackRuntimeCache.markPlaybackSnapshotSent(snapshot)
        }
        return sent
    }

    private fun sendCompactPlaybackState(source: JSONObject): Boolean {
        val devices = subscribedDevices.values.toList()
        val maximumPayload = devices.minOfOrNull(::maximumPayloadFor) ?: 0
        val payloadSizeBeforeFit = compactPlaybackStatePayload(
            source,
            includeClockSync = false
        ).toString().toByteArray(Charsets.UTF_8).size
        logger(
            "[PlaybackState] compact payloadSize=$payloadSizeBeforeFit " +
                "maxPayload=$maximumPayload positionMs=${source.optLong("position")} " +
                "lyricLength=${source.optString("lyric").length}"
        )
        val sent = devices.fold(false) { anySent, device ->
            val fitted = fitCompactPlaybackState(
                compactPlaybackStatePayload(
                    source,
                    includeClockSync = connectionCommandCoordinator
                        .capabilities(device.address)
                        .clockSyncV1
                ),
                maximumPayloadFor(device)
            )
            sendStatusMessageTo(device, fitted.toString()) || anySent
        }
        if (sent) {
            TrackCapabilityTracker.onPlaybackStateSent(
                trackId = buildAlbumArtProtocolId(source),
                protocolId = buildAlbumArtProtocolId(source)
            )
        }
        return sent
    }

    private fun sendCompactPlaybackStateTo(
        device: BluetoothDevice,
        source: JSONObject
    ): Boolean {
        val fitted = fitCompactPlaybackState(
            compactPlaybackStatePayload(
                source,
                includeClockSync = connectionCommandCoordinator
                    .capabilities(device.address)
                    .clockSyncV1
            ),
            maximumPayloadFor(device)
        )
        return sendStatusMessageTo(device, fitted.toString())
    }

    private fun compactPlaybackStatePayload(
        source: JSONObject,
        includeClockSync: Boolean
    ): JSONObject {
        val payload = JSONObject()
            .put("type", "playbackState")
            .put("playing", source.optBoolean("playing"))
            .put("position", source.optLong("position"))
            .put("duration", source.optLong("duration"))
            .put("lyric", source.optString("lyric").take(MAX_LYRIC_TEXT_LENGTH))
        if (includeClockSync) {
            payload
                .put("sampleMono", source.optLong("positionSampleElapsedMs"))
                .put("speed", source.optDouble("speed", 1.0))
        }
        return payload
    }

    private fun fitCompactPlaybackState(
        compactState: JSONObject,
        maximumPayload: Int
    ): JSONObject {
        var fittedLyric = compactState.optString("lyric")
        while (fittedLyric.isNotEmpty() &&
            compactState.toString().toByteArray(Charsets.UTF_8).size >
            maximumPayload
        ) {
            fittedLyric = fittedLyric.dropLast(1)
            compactState.put("lyric", fittedLyric)
        }
        if (compactState.toString().toByteArray(Charsets.UTF_8).size > maximumPayload) {
            compactState.remove("lyric")
        }
        return compactState
    }

    private fun sendTrackInfo(source: JSONObject) {
        val devices = subscribedDevices.values.toList()
        if (devices.isEmpty()) {
            logger("[TrackInfo] send skipped: no controller subscriber")
            return
        }
        devices.forEach { device -> sendTrackInfoTo(device, source) }
    }

    private fun sendTrackInfoTo(device: BluetoothDevice, source: JSONObject) {
        val maximumPayload = maximumPayloadFor(device)
        val trackInfo = buildFittingTrackInfo(source, maximumPayload)
        if (trackInfo == null) {
            logger("[TrackInfo] send failed: payload too large")
            return
        }
        logger("[TrackInfo] send")
        if (notifyQueue.hasLongJobActiveOrQueued(device.address)) {
            logger("[TrackInfo] latest updated title=${source.optString("title")}")
        }
        sendStatusMessageTo(device, trackInfo.toString())
    }

    private fun buildFittingTrackInfo(
        source: JSONObject,
        maximumPayload: Int
    ): JSONObject? {
        val title = source.optString("title")
        val artist = source.optString("artist")
        val album = source.optString("album")
        val trackId = buildAlbumArtProtocolId(source)
        val generation = reactiveMediaController.generation()
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
                .put("generation", generation)
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
        if (protocolId.isBlank()) {
            clearAlbumArtStateForNoActiveTrack()
            logger("[AlbumArt] skip reason=no active QQ track")
            return
        }
        if (cacheKey == lastAlbumArtKey) {
            return
        }
        // Album-art discovery can run after the new track's lyrics are already
        // encoded and queued. Cancelling every lyric job here races that valid
        // same-track transfer (the first A2 packet is sent, then the remaining
        // packets are dropped and iOS waits for its timeout). Lyric jobs carry
        // their own trackId/generation fence, so only cancel the old artwork;
        // stale lyrics still self-cancel before their next packet.
        notifyQueue.cancelJobTypes(
            setOf(ALBUM_ART_JOB_TYPE),
            "track changed"
        )
        lastAlbumArtKey = cacheKey
        currentAlbumArtId = protocolId
        currentAlbumArtPlaybackState = JSONObject(playbackState.toString())
        albumArtSourceRetryCounts.clear()
        albumArtUnavailableProtocolIds.clear()
        CurrentTrackRuntimeCache.updateAlbumArt(
            trackId = protocolId,
            albumArtId = protocolId,
            albumArtState = "scheduled",
            logger = logger
        )
        TrackCapabilityTracker.onAlbumArtLoadStart(protocolId)
        RealtimeTrace.record(
                stage = "albumArtDetected",
            trackId = protocolId,
            generation = CurrentTrackRuntimeCache.currentGeneration(),
            payloadType = "albumArt",
            result = "detected"
        )

        val pending = PendingAlbumArt(
            cacheKey = cacheKey,
            protocolId = protocolId,
            playbackState = JSONObject(playbackState.toString())
        )
        logger("[AlbumArtFastPath] track_changed start id=$protocolId")
        val cached = albumArtCacheEntry(protocolId, cacheKey)
        if (cached != null) {
            RealtimeTrace.record(
                stage = "albumArtCacheHit",
                trackId = protocolId,
                generation = CurrentTrackRuntimeCache.currentGeneration(),
                payloadType = "albumArt",
                result = "hit"
            )
            logger("[AlbumArt] cache hit id=$protocolId source=${cached.source}")
            logger(
                "[AlbumArtFastPath] cache hit id=$protocolId source=${cached.source} " +
                    "size=${cached.width}x${cached.height} bytes=${cached.byteSize}"
            )
            TrackCapabilityTracker.onAlbumArtLoadDone(
                protocolId = protocolId,
                success = true,
                source = cached.source,
                width = cached.width,
                height = cached.height,
                byteSize = cached.byteSize
            )
            CurrentTrackRuntimeCache.updateAlbumArt(
                trackId = protocolId,
                albumArtId = protocolId,
                albumArtState = "CACHE_HIT",
                logger = logger
            )
            enqueueAlbumArtOfferOrPending(pending)
            return
        }
        logger("[AlbumArtFastPath] cache miss id=$protocolId")
        val mediaGeneration = reactiveMediaController.generation()
        if (!reactiveMediaController.tryStartAlbumArtTask(protocolId, mediaGeneration)) {
            pendingAlbumArt = pending
            logger("[AlbumArt] pending new id=$protocolId reason=album_art_inflight")
            return
        }
        startAlbumArtFastPathLoad(pending, mediaGeneration)
        // The offer is intentionally sent before the bitmap is ready. iOS can
        // request preview immediately; the pending-request path fulfills it as
        // soon as the fast source read completes. Capability negotiation still
        // provides the only required (maximum 250 ms) compatibility gate.
        enqueueAlbumArtOfferOrPending(pending)
    }

    /**
     * A stopped QQ session has no stable track identity.  Clear the transport
     * identity as well as the dedupe key so that resuming the same song sends
     * a fresh offer to a client which has already cleared its UI.
     *
     * Do not cancel the source-read task here: it is short lived, and letting
     * it finish releases ReactiveMediaController's in-flight permit.  If that
     * exact song resumes while the task is finishing, the pending offer below
     * is flushed by the task-completion path.
     */
    @Synchronized
    private fun clearAlbumArtStateForNoActiveTrack() {
        val previousId = currentAlbumArtId.orEmpty()
        val hadState = previousId.isNotBlank() ||
            lastAlbumArtKey != null ||
            pendingAlbumArt != null ||
            albumArtRequestsInFlight.isNotEmpty() ||
            albumArtPendingRequests.isNotEmpty()
        lastAlbumArtKey = null
        currentAlbumArtId = null
        currentAlbumArtPlaybackState = null
        pendingAlbumArt = null
        albumArtRequestsInFlight.clear()
        albumArtRequestCompletedAtMs.clear()
        albumArtPendingRequests.clear()
        albumArtSourceRetryCounts.clear()
        albumArtUnavailableProtocolIds.clear()
        notifyQueue.cancelJobTypes(
            setOf(ALBUM_ART_JOB_TYPE),
            "no active QQ track"
        )
        if (hadState) {
            logger("[AlbumArt] reset reason=no active QQ track oldId=$previousId")
        }
    }

    private fun enqueueAlbumArtOfferOrPending(
        pending: PendingAlbumArt,
        targetDevice: BluetoothDevice? = null
    ) {
        if (!ALBUM_ART_ENABLED) {
            return
        }
        if (pending.protocolId.isBlank()) {
            logger("[AlbumArt] offer skipped reason=empty protocol id")
            return
        }
        val cacheKey = pending.cacheKey
        val protocolId = pending.protocolId
        logAlbumArtDebugIdentity(pending.playbackState, protocolId)
        val devices = if (targetDevice != null) {
            listOfNotNull(subscribedDevices[targetDevice.address])
        } else {
            subscribedDevices.values.toList()
        }
        if (devices.isEmpty()) {
            logger("[AlbumArt][BLE] no controller subscriber")
            return
        }
        currentAlbumArtId = protocolId
        CurrentTrackRuntimeCache.updateAlbumArt(
            trackId = protocolId,
            albumArtId = protocolId,
            albumArtState = "offer",
            logger = logger
        )
        albumArtRequestsInFlight
            .filter { requestKey ->
                requestKey.substringAfter('|').substringBefore('|') != protocolId
            }
            .forEach(albumArtRequestsInFlight::remove)
        albumArtRequestCompletedAtMs.keys
            .filter { requestKey ->
                requestKey.substringAfter('|').substringBefore('|') != protocolId
            }
            .forEach(albumArtRequestCompletedAtMs::remove)

        val offer = JSONObject()
            .put("type", "albumArtOffer")
            .put("id", protocolId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        lastAlbumArtKey = cacheKey
        currentAlbumArtPlaybackState = JSONObject(pending.playbackState.toString())
        devices.forEach { device ->
            val capabilities = connectionCommandCoordinator.capabilities(device.address)
            val subscribedAtMs = connectionCommandCoordinator.subscribedAtMs(device.address)
            if (!capabilities.negotiated &&
                SystemClock.elapsedRealtime() - subscribedAtMs < CLIENT_CAPABILITY_WAIT_MS
            ) {
                pendingAlbumArt = pending
                logger(
                    "[AlbumArt] offer deferred reason=capability negotiation " +
                        "id=$protocolId device=${device.address}"
                )
                return@forEach
            }
            if (notifyQueue.hasJobTypeActiveOrQueued(ALBUM_ART_JOB_TYPE, device.address) ||
                albumArtRequestsInFlight.any { it.startsWith("${device.address}|") }
            ) {
                pendingAlbumArt = pending
                logger("[AlbumArt] pending new id=$protocolId device=${device.address}")
                return@forEach
            }
            if (offer.size > maximumPayloadFor(device)) {
                logger(
                    "[AlbumArt] offer failed id=$protocolId " +
                        "device=${device.address} reason=MTU"
                )
                return@forEach
            }
            logger("[AlbumArt] offer id=$protocolId device=${device.address}")
            logger("[AlbumArtDebug] offer sent id=$protocolId device=${device.address}")
            RealtimeTrace.record(
                stage = "albumArtOfferEnqueued",
                trackId = protocolId,
                generation = CurrentTrackRuntimeCache.currentGeneration(),
                payloadType = "albumArtOffer",
                result = "queued"
            )
            notifyQueue.enqueueShort(
                device = device,
                type = "albumArtOffer",
                value = offer,
                delayAfterMs = SHORT_MESSAGE_DELAY_MS
            )
        }
    }

    @Synchronized
    private fun sendPendingAlbumArtIfAny() {
        val pending = pendingAlbumArt ?: return
        pendingAlbumArt = null
        logger("[AlbumArt] send pending id=${pending.protocolId}")
        // Re-evaluate every subscribed controller. A second client may still
        // be negotiating capabilities or waiting behind its own art transfer;
        // enqueueShort coalesces duplicate offers per device.
        enqueueAlbumArtOfferOrPending(pending)
    }

    private fun scheduleAlbumArtSourceRetry(
        pending: PendingAlbumArt,
        reason: String
    ): Boolean {
        val attempt = (albumArtSourceRetryCounts[pending.protocolId] ?: 0) + 1
        albumArtSourceRetryCounts[pending.protocolId] = attempt
        val delayMs = ALBUM_ART_SOURCE_RETRY_DELAYS_MS.getOrNull(attempt - 1)
            ?: return false
        logger(
            "[AlbumArtFastPath] retry scheduled id=${pending.protocolId} " +
                "attempt=$attempt delayMs=$delayMs reason=$reason"
        )
        albumArtHandler.postDelayed(
            { retryCurrentAlbumArtAfterNotification("retry_$attempt") },
            delayMs
        )
        return true
    }

    @Synchronized
    private fun retryCurrentAlbumArtAfterNotification(event: String) {
        if (!started || !ALBUM_ART_ENABLED) {
            return
        }
        val playbackState = currentAlbumArtPlaybackState ?: return
        val protocolId = buildAlbumArtProtocolId(playbackState)
        if (protocolId.isBlank() || protocolId != currentAlbumArtId) {
            return
        }
        val cacheKey = buildAlbumArtCacheKey(playbackState)
        if (albumArtCacheEntry(protocolId, cacheKey) != null) {
            fulfillPendingAlbumArtRequests(protocolId)
            return
        }
        if (albumArtFastPathProtocolId == protocolId &&
            (albumArtFastPathTask?.isDone == false || albumArtFastPathCompletionPending)
        ) {
            return
        }
        val generation = reactiveMediaController.generation()
        if (!reactiveMediaController.tryStartAlbumArtTask(protocolId, generation)) {
            return
        }
        logger("[AlbumArtFastPath] retry reason=qq_notification_$event id=$protocolId")
        startAlbumArtFastPathLoad(
            pending = PendingAlbumArt(
                cacheKey = cacheKey,
                protocolId = protocolId,
                playbackState = JSONObject(playbackState.toString())
            ),
            mediaGeneration = generation
        )
    }

    private fun startAlbumArtFastPathLoad(
        pending: PendingAlbumArt,
        mediaGeneration: Long
    ) {
        val oldProtocolId = albumArtFastPathProtocolId
        val oldTask = albumArtFastPathTask
        if (oldTask != null && !oldTask.isDone && oldProtocolId != pending.protocolId) {
            oldTask.cancel(true)
            logger(
                "[AlbumArtTask] cancel oldTrack=${oldProtocolId.orEmpty()} " +
                    "newTrack=${pending.protocolId}"
            )
        }
        val generation = ++albumArtTaskGeneration
        albumArtFastPathProtocolId = pending.protocolId
        albumArtFastPathCompletionPending = true
        CurrentTrackRuntimeCache.updateAlbumArt(
            trackId = pending.protocolId,
            albumArtId = pending.protocolId,
            albumArtState = "LOAD_STARTED",
            logger = logger
        )
        logger("[AlbumArtFastPath] load start id=${pending.protocolId}")
        TrackCapabilityTracker.onAlbumArtLoadStart(pending.protocolId)
        val startedAt = SystemClock.elapsedRealtime()
        albumArtFastPathTask = albumArtFastPathExecutor.submit {
            val albumArt = try {
                albumArtTestManager.readCurrentNotificationAlbumArt(
                    expectedTitle = pending.playbackState.optString("title"),
                    expectedArtist = pending.playbackState.optString("artist")
                )
            } catch (exception: Exception) {
                logger("[AlbumArtFastPath] failed reason=${exception.message}")
                reactiveMediaController.markAlbumArtFinished(
                    trackId = pending.protocolId,
                    generation = mediaGeneration,
                    ready = false,
                    reason = exception.message ?: "load_failed"
                )
                TrackCapabilityTracker.onAlbumArtLoadDone(
                    protocolId = pending.protocolId,
                    success = false,
                    source = "none",
                    width = 0,
                    height = 0,
                    byteSize = 0,
                    reason = exception.message ?: "load_failed"
                )
                null
            }
            if (Thread.currentThread().isInterrupted) {
                reactiveMediaController.markAlbumArtFinished(
                    trackId = pending.protocolId,
                    generation = mediaGeneration,
                    ready = false,
                    reason = "interrupted"
                )
                logger("[AlbumArtTask] cancel oldTrack=${pending.protocolId} newTrack=interrupted")
                albumArtHandler.post {
                    synchronized(this) {
                        if (generation == albumArtTaskGeneration) {
                            albumArtFastPathCompletionPending = false
                        }
                    }
                }
                return@submit
            }
            albumArtHandler.post {
                synchronized(this) {
                    if (generation != albumArtTaskGeneration ||
                        currentAlbumArtId != pending.protocolId
                    ) {
                        reactiveMediaController.markAlbumArtFinished(
                            trackId = pending.protocolId,
                            generation = mediaGeneration,
                            ready = false,
                            reason = "generation_mismatch"
                        )
                        logger(
                            "[AlbumArtTask] cancel oldTrack=${pending.protocolId} " +
                                "newTrack=${currentAlbumArtId.orEmpty()}"
                        )
                        return@synchronized
                    }
                    albumArtFastPathCompletionPending = false
                    if (albumArt == null) {
                        reactiveMediaController.markAlbumArtFinished(
                            trackId = pending.protocolId,
                            generation = mediaGeneration,
                            ready = false,
                            reason = "no album art candidate"
                        )
                        CurrentTrackRuntimeCache.updateAlbumArt(
                            trackId = pending.protocolId,
                            albumArtId = pending.protocolId,
                            albumArtState = "FAILED",
                            logger = logger
                        )
                        logger("[AlbumArtFastPath] failed reason=no album art candidate")
                        TrackCapabilityTracker.onAlbumArtLoadDone(
                            protocolId = pending.protocolId,
                            success = false,
                            source = "none",
                            width = 0,
                            height = 0,
                            byteSize = 0,
                            reason = "source_app_not_provided"
                        )
                        if (!scheduleAlbumArtSourceRetry(pending, "no notification largeIcon")) {
                            failPendingAlbumArtRequests(
                                protocolId = pending.protocolId,
                                reason = "no notification largeIcon"
                            )
                        }
                        return@synchronized
                    }
                    if (AlbumArtPlaceholderPolicy.isLikelyPlaceholder(albumArt.bitmap)) {
                        reactiveMediaController.markAlbumArtFinished(
                            trackId = pending.protocolId,
                            generation = mediaGeneration,
                            ready = false,
                            reason = "placeholder album art"
                        )
                        CurrentTrackRuntimeCache.updateAlbumArt(
                            trackId = pending.protocolId,
                            albumArtId = pending.protocolId,
                            albumArtState = "FAILED",
                            logger = logger
                        )
                        logger("[AlbumArtFastPath] failed reason=placeholder album art")
                        TrackCapabilityTracker.onAlbumArtLoadDone(
                            protocolId = pending.protocolId,
                            success = false,
                            source = albumArt.source,
                            width = albumArt.bitmap.width,
                            height = albumArt.bitmap.height,
                            byteSize = albumArt.bitmap.allocationByteCount,
                            reason = "placeholder album art"
                        )
                        if (!scheduleAlbumArtSourceRetry(pending, "placeholder album art")) {
                            failPendingAlbumArtRequests(
                                protocolId = pending.protocolId,
                                reason = "placeholder album art"
                            )
                        }
                        return@synchronized
                    }
                    val costMs = SystemClock.elapsedRealtime() - startedAt
                    val hadWaitingClientRequest = albumArtPendingRequests.values.any {
                        it.protocolId == pending.protocolId
                    }
                    val recoveredFromUnavailable =
                        albumArtUnavailableProtocolIds.remove(pending.protocolId)
                    putAlbumArtCache(
                        protocolId = pending.protocolId,
                        cacheKey = pending.cacheKey,
                        bitmap = albumArt.bitmap,
                        source = albumArt.source
                    )
                    albumArtSourceRetryCounts.remove(pending.protocolId)
                    logger("[AlbumArt] fallback notification id=${pending.protocolId}")
                    CurrentTrackRuntimeCache.updateAlbumArt(
                        trackId = pending.protocolId,
                        albumArtId = pending.protocolId,
                        albumArtState = "PREVIEW_READY",
                        logger = logger
                    )
                    logger(
                        "[AlbumArtFastPath] preview ready costMs=$costMs " +
                            "size=${albumArt.bitmap.width}x${albumArt.bitmap.height}"
                    )
                    TrackCapabilityTracker.onAlbumArtLoadDone(
                        protocolId = pending.protocolId,
                        success = true,
                        source = albumArt.source,
                        width = albumArt.bitmap.width,
                        height = albumArt.bitmap.height,
                        byteSize = albumArt.bitmap.allocationByteCount
                    )
                    CurrentTrackRuntimeCache.updateAlbumArt(
                        trackId = pending.protocolId,
                        albumArtId = pending.protocolId,
                        albumArtState = "HQ_READY",
                        logger = logger
                    )
                    logger(
                        "[AlbumArtFastPath] hq ready costMs=$costMs " +
                            "size=${albumArt.bitmap.width}x${albumArt.bitmap.height}"
                    )
                    reactiveMediaController.markAlbumArtFinished(
                        trackId = pending.protocolId,
                        generation = mediaGeneration,
                        ready = true,
                        reason = albumArt.source
                    )
                    fulfillPendingAlbumArtRequests(pending.protocolId)
                    if (AlbumArtRequestPolicy.shouldResendOfferAfterRecovery(
                            recoveredFromUnavailable = recoveredFromUnavailable,
                            hadWaitingClientRequest = hadWaitingClientRequest
                        )
                    ) {
                        logger(
                            "[AlbumArtFastPath] recovered real artwork, " +
                                "resend offer id=${pending.protocolId}"
                        )
                        enqueueAlbumArtOfferOrPending(pending)
                    }
                    // The same track can resume while an older source-read is
                    // still completing.  Flush its refreshed offer now rather
                    // than waiting for another media-session callback.
                    sendPendingAlbumArtIfAny()
                }
            }
        }
    }

    private fun albumArtCacheEntry(
        protocolId: String,
        cacheKey: String?
    ): AlbumArtCacheEntry? {
        val now = SystemClock.elapsedRealtime()
        val keys = listOfNotNull(
            albumArtPrimaryCacheKey(protocolId),
            cacheKey?.let(::albumArtFallbackCacheKey)
        )
        synchronized(albumArtCache) {
            for (key in keys) {
                val entry = albumArtCache[key] ?: continue
                if (AlbumArtPlaceholderPolicy.isLikelyPlaceholder(entry.bitmap)) {
                    removeAlbumArtCacheEntryLocked(entry)
                    removeEncodedAlbumArtForProtocol(entry.protocolId)
                    logger(
                        "[AlbumArtCache] evict key=$key " +
                            "reason=qq_fallback_placeholder"
                    )
                    continue
                }
                if (now - entry.createdAtElapsedMs <= ALBUM_ART_CACHE_TTL_MS) {
                    logger("[AlbumArtCache] hit key=$key id=${entry.protocolId}")
                    return entry
                }
                removeAlbumArtCacheEntryLocked(entry)
                logger("[AlbumArtCache] evict key=$key reason=ttl")
            }
        }
        logger("[AlbumArtCache] miss id=$protocolId")
        return null
    }

    private fun putAlbumArtCache(
        protocolId: String,
        cacheKey: String,
        bitmap: Bitmap,
        source: String
    ) {
        val entry = AlbumArtCacheEntry(
            protocolId = protocolId,
            cacheKey = cacheKey,
            bitmap = bitmap,
            source = source,
            width = bitmap.width,
            height = bitmap.height,
            byteSize = bitmap.allocationByteCount,
            createdAtElapsedMs = SystemClock.elapsedRealtime()
        )
        synchronized(albumArtCache) {
            albumArtCache[albumArtPrimaryCacheKey(protocolId)] = entry
            albumArtCache[albumArtFallbackCacheKey(cacheKey)] = entry
            trimAlbumArtCacheLocked()
        }
        logger(
            "[AlbumArtCache] put id=$protocolId source=$source " +
                "size=${bitmap.width}x${bitmap.height} bytes=${bitmap.allocationByteCount}"
        )
    }

    private fun trimAlbumArtCacheLocked() {
        val uniqueEntries = Collections.newSetFromMap(
            IdentityHashMap<AlbumArtCacheEntry, Boolean>()
        )
        uniqueEntries.addAll(albumArtCache.values)
        var totalBytes = uniqueEntries.sumOf { it.byteSize.toLong() }
        while (
            uniqueEntries.size > ALBUM_ART_CACHE_CAPACITY ||
            (totalBytes > ALBUM_ART_CACHE_MAX_BYTES && uniqueEntries.size > 1)
        ) {
            val eldest = albumArtCache.entries.firstOrNull()?.value ?: break
            removeAlbumArtCacheEntryLocked(eldest)
            uniqueEntries.remove(eldest)
            totalBytes -= eldest.byteSize.toLong()
            logger(
                "[AlbumArtCache] evict id=${eldest.protocolId} " +
                    "reason=memory entries=${uniqueEntries.size} bytes=$totalBytes"
            )
        }
    }

    private fun removeAlbumArtCacheEntryLocked(entry: AlbumArtCacheEntry) {
        val iterator = albumArtCache.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value === entry) {
                iterator.remove()
            }
        }
    }

    private fun fulfillPendingAlbumArtRequests(protocolId: String) {
        val cacheKey = currentAlbumArtPlaybackState?.let(::buildAlbumArtCacheKey)
        val entry = albumArtCacheEntry(protocolId, cacheKey) ?: return
        val pending = albumArtPendingRequests.values
            .filter { it.protocolId == protocolId }
            .toList()
        pending.forEach { request ->
            albumArtPendingRequests.remove(request.requestKey)
            logger(
                "[AlbumArtSend] pending request fulfilled id=$protocolId " +
                    "quality=${request.quality.wireValue}"
            )
            sendAlbumArtFromBitmap(
                device = request.device,
                protocolId = request.protocolId,
                quality = request.quality,
                requestKey = request.requestKey,
                bitmap = entry.bitmap,
                selectedSource = entry.source,
                playbackState = currentAlbumArtPlaybackState
            )
        }
    }

    private fun failPendingAlbumArtRequests(protocolId: String, reason: String) {
        albumArtUnavailableProtocolIds.add(protocolId)
        val pending = albumArtPendingRequests.values
            .filter { it.protocolId == protocolId }
            .toList()
        pending.forEach { request ->
            albumArtPendingRequests.remove(request.requestKey)
            albumArtRequestsInFlight.remove(request.requestKey)
            sendAlbumArtUnavailable(
                device = request.device,
                protocolId = request.protocolId,
                quality = request.quality,
                reason = reason
            )
        }
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
    private fun handleAlbumArtRequest(device: BluetoothDevice, request: JSONObject) {
        val protocolId = request.optString("id")
        val quality = AlbumArtQuality.fromWireValue(
            request.optString("quality")
        )
        if (protocolId.isBlank() || protocolId != currentAlbumArtId) {
            logger("[AlbumArt] request ignored stale id=$protocolId")
            sendCommandError(
                device, request, "artwork", "stale_track", false,
                trackId = protocolId
            )
            return
        }
        if (quality == null) {
            logger("[AlbumArt] request ignored invalid quality")
            sendCommandError(
                device, request, "artwork", "invalid_quality", false,
                trackId = protocolId
            )
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
            if (!connectionCommandCoordinator.capabilities(device.address).binaryAlbumArt) {
                logger("[AlbumArtHQ] request skipped reason=binary unsupported")
                return
            }
        }
        val requestKey = "${device.address}|$protocolId|${quality.wireValue}"
        val forceRefresh = request.optBoolean("forceRefresh", false)
        val nowMs = SystemClock.elapsedRealtime()
        if (!AlbumArtRequestPolicy.shouldAllowCompletedRequest(
                lastCompletedAtMs = albumArtRequestCompletedAtMs[requestKey],
                nowMs = nowMs,
                forceRefresh = forceRefresh
            )
        ) {
            logger(
                "[AlbumArt] skip recent duplicate id=$protocolId " +
                    "quality=${quality.wireValue}"
            )
            return
        }
        if (!albumArtRequestsInFlight.add(requestKey)) {
            logger(
                "[AlbumArt] skip in-flight duplicate id=$protocolId " +
                    "quality=${quality.wireValue}"
            )
            return
        }
        logger(
            "[AlbumArt] request id=$protocolId quality=${quality.wireValue} " +
                "forceRefresh=$forceRefresh"
        )
        sendMediaLoadState(
            device = device,
            resource = "artwork",
            stage = "preparing",
            reason = "transfer_preparing",
            retryable = false,
            trackId = protocolId,
            generation = playbackStateReader.runtimeCacheSnapshot()
                .track?.currentTrackGeneration ?: 0L
        )
        TrackCapabilityTracker.onAlbumArtRequested(protocolId)
        if (quality == AlbumArtQuality.HQ) {
            logger("[AlbumArtHQ] request accepted id=$protocolId")
        }
        CurrentTrackRuntimeCache.updateAlbumArt(
            trackId = protocolId,
            albumArtId = protocolId,
            albumArtState = "request:${quality.wireValue}",
            logger = logger
        )
        logger("[AlbumArtDebug] id=$protocolId")

        if (!subscribedDevices.containsKey(device.address)) {
            albumArtRequestsInFlight.remove(requestKey)
            logger("[AlbumArt] request failed: controller no longer subscribed")
            logger("[AlbumArtDebug] unavailable reason=controller unsubscribed")
            return
        }
        val cached = albumArtCacheEntry(protocolId, buildAlbumArtCacheKeyFromProtocol(protocolId))
        if (cached != null) {
            logger("[AlbumArtSend] immediate from cache id=$protocolId quality=${quality.wireValue}")
            sendAlbumArtFromBitmap(
                device = device,
                protocolId = protocolId,
                quality = quality,
                requestKey = requestKey,
                bitmap = cached.bitmap,
                selectedSource = cached.source,
                playbackState = currentAlbumArtPlaybackState
            )
            return
        }
        sendMediaLoadState(
            device = device,
            resource = "artwork",
            stage = "waiting",
            reason = "source_pending",
            retryable = true,
            trackId = protocolId,
            generation = playbackStateReader.runtimeCacheSnapshot()
                .track?.currentTrackGeneration ?: 0L,
            retryAfterMs = ALBUM_ART_SOURCE_RETRY_DELAYS_MS.first()
        )
        albumArtPendingRequests[requestKey] = PendingAlbumArtRequest(
            device = device,
            protocolId = protocolId,
            quality = quality,
            requestKey = requestKey
        )
        logger("[AlbumArtSend] pending request quality=${quality.wireValue} id=$protocolId")
        retryCurrentAlbumArtAfterNotification("request")
    }

    private fun sendAlbumArtFromBitmap(
        device: BluetoothDevice,
        protocolId: String,
        quality: AlbumArtQuality,
        requestKey: String,
        bitmap: Bitmap,
        selectedSource: String,
        playbackState: JSONObject?
    ) {
        logger("[AlbumArt][BLE] source=$selectedSource")
        logger("[AlbumArtDebug] source $selectedSource exists=true")
        logger(
            "[AlbumArt][BLE] original width=${bitmap.width} height=${bitmap.height}"
        )
        logger(
            "[AlbumArtDebug] bitmap width=${bitmap.width} height=${bitmap.height}"
        )
        val useBinaryAlbumArt =
            (quality == AlbumArtQuality.PREVIEW || quality == AlbumArtQuality.HQ) &&
                connectionCommandCoordinator.capabilities(device.address).binaryAlbumArt
        val maximumPayload = if (useBinaryAlbumArt) {
            albumArtMaximumPayloadFor(device)
        } else {
            minOf(albumArtMaximumPayloadFor(device), MAX_ALBUM_JSON_BYTES)
        }
        val encodeStartedAtMs = SystemClock.elapsedRealtime()
        RealtimeTrace.record(
            stage = "previewEncodeStart",
            monoMs = encodeStartedAtMs,
            trackId = protocolId,
            generation = CurrentTrackRuntimeCache.currentGeneration(),
            payloadType = quality.wireValue,
            result = "started"
        )
        val preparation = prepareAlbumArt(
            bitmap = bitmap,
            deviceMaximumPayload = maximumPayload,
            protocolId = protocolId,
            quality = quality,
            binaryTransport = useBinaryAlbumArt
        )
        val encodeEndedAtMs = SystemClock.elapsedRealtime()
        RealtimeTrace.record(
            stage = "previewEncodeEnd",
            monoMs = encodeEndedAtMs,
            trackId = protocolId,
            generation = CurrentTrackRuntimeCache.currentGeneration(),
            payloadType = quality.wireValue,
            processingMs = (encodeEndedAtMs - encodeStartedAtMs).coerceAtLeast(0L),
            result = if (preparation.prepared != null) "success" else "failure",
            reason = if (preparation.prepared != null) null else "encode_failed"
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
                sendAlbumArtUnavailable(
                    device = device,
                    protocolId = protocolId,
                    quality = quality,
                    reason = "too large",
                    bestBytes = preparation.bestBytes,
                    bestChunks = preparation.bestChunks,
                    minCandidateScale = preparation.minCandidateScale
                )
            }
            return
        }

        val compressedAlbumArt = preparedAlbumArt.compressed
        val albumPackets = preparedAlbumArt.packets
        if (useBinaryAlbumArt) {
            rememberAlbumArtBinaryTransfer(
                device = device,
                protocolId = protocolId,
                quality = quality,
                packets = albumPackets.packets
            )
        }
        val totalChunks = albumPackets.totalChunks
        exportAlbumArtDiagnostics(
            protocolId = protocolId,
            playbackState = playbackState,
            selectedSource = selectedSource,
            sourceBitmap = bitmap,
            compressed = compressedAlbumArt,
            quality = quality,
            totalChunks = totalChunks
        )
        if (quality == AlbumArtQuality.HQ) {
            logger(
                "[AlbumArtHQ] selected scale=${compressedAlbumArt.width} " +
                    "quality=${compressedAlbumArt.quality} " +
                    "bytes=${compressedAlbumArt.bytes.size} chunks=$totalChunks " +
                    "fallback=${preparedAlbumArt.fallback}"
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
        RealtimeTrace.record(
            stage = if (quality == AlbumArtQuality.HQ) {
                "hqSendStart"
            } else {
                "previewSendStart"
            },
            monoMs = startAt,
            trackId = protocolId,
            generation = CurrentTrackRuntimeCache.currentGeneration(),
            payloadType = quality.wireValue,
            chunkCount = totalChunks,
            result = "started"
        )
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
        val artworkGeneration = playbackStateReader.runtimeCacheSnapshot()
            .track?.currentTrackGeneration ?: 0L
        sendMediaLoadState(
            device, "artwork", "transferring", "transfer_preparing", false,
            protocolId, artworkGeneration
        )
        notifyQueue.enqueueLongJob(
            type = ALBUM_ART_JOB_TYPE,
            device = device,
            packets = albumPackets.packets,
            priority = when (quality) {
                AlbumArtQuality.PREVIEW -> BleNotifyQueue.Priority.P1_INTERACTIVE
                AlbumArtQuality.FULL -> BleNotifyQueue.Priority.P2_BULK
                AlbumArtQuality.HQ -> BleNotifyQueue.Priority.P3_BACKGROUND
            },
            maxSendDurationMs = when (quality) {
                AlbumArtQuality.PREVIEW -> ALBUM_ART_PREVIEW_MAX_SEND_MS
                AlbumArtQuality.HQ -> ALBUM_ART_HQ_MAX_SEND_MS
                AlbumArtQuality.FULL -> ALBUM_ART_FULL_MAX_SEND_MS
            },
            shouldCancel = {
                currentAlbumArtId != protocolId
            },
            onComplete = {
                albumArtRequestsInFlight.remove(requestKey)
                albumArtRequestCompletedAtMs[requestKey] = SystemClock.elapsedRealtime()
                val costMs = SystemClock.elapsedRealtime() - startAt
                RealtimeTrace.record(
                    stage = if (quality == AlbumArtQuality.HQ) {
                        "hqSendEnd"
                    } else {
                        "previewSendEnd"
                    },
                    trackId = protocolId,
                    generation = artworkGeneration,
                    payloadType = quality.wireValue,
                    processingMs = costMs.coerceAtLeast(0L),
                    chunkCount = totalChunks,
                    result = "success"
                )
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
                TrackCapabilityTracker.onAlbumArtSent(protocolId)
                sendMediaLoadState(
                    device, "artwork", "ready", "transfer_complete", false,
                    protocolId, artworkGeneration
                )
                sendPendingAlbumArtIfAny()
                CurrentTrackRuntimeCache.updateAlbumArt(
                    trackId = protocolId,
                    albumArtId = protocolId,
                    albumArtState = "sent:${quality.wireValue}",
                    logger = logger
                )
            },
            onFailure = {
                albumArtRequestsInFlight.remove(requestKey)
                sendMediaLoadState(
                    device, "artwork", "failed", "transfer_failed", true,
                    protocolId, artworkGeneration
                )
                if (quality == AlbumArtQuality.HQ && currentAlbumArtId != protocolId) {
                    logger("[AlbumArtHQ] cancelled reason=track changed")
                }
                CurrentTrackRuntimeCache.updateAlbumArt(
                    trackId = protocolId,
                    albumArtId = protocolId,
                    albumArtState = "failed:${quality.wireValue}",
                    logger = logger
                )
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
        val encodedCacheKey = listOf(
            protocolId,
            quality.wireValue,
            bitmap.width,
            bitmap.height,
            bitmap.generationId
        ).joinToString("|")
        val preparationLock = albumArtPreparationLocks.getOrPut(encodedCacheKey) { Any() }
        return synchronized(preparationLock) {
            prepareAlbumArtLocked(
                bitmap = bitmap,
                deviceMaximumPayload = deviceMaximumPayload,
                protocolId = protocolId,
                quality = quality,
                binaryTransport = binaryTransport,
                encodedCacheKey = encodedCacheKey
            )
        }
    }

    private fun prepareAlbumArtLocked(
        bitmap: Bitmap,
        deviceMaximumPayload: Int,
        protocolId: String,
        quality: AlbumArtQuality,
        binaryTransport: Boolean,
        encodedCacheKey: String
    ): AlbumArtPreparation {
        getEncodedAlbumArt(encodedCacheKey)?.let { cached ->
            val cachedPackets = if (binaryTransport) {
                buildBinaryAlbumArtPackets(
                    deviceMaximumPayload,
                    protocolId,
                    quality,
                    cached.bytes,
                    when (quality) {
                        AlbumArtQuality.PREVIEW -> ALBUM_ART_PREVIEW_MAX_CHUNKS
                        AlbumArtQuality.HQ -> ALBUM_ART_HQ_MAX_CHUNKS
                        AlbumArtQuality.FULL -> ALBUM_ART_FULL_MAX_CHUNKS
                    }
                )
            } else {
                buildAlbumArtPackets(
                    deviceMaximumPayload,
                    protocolId,
                    quality,
                    cached.bytes,
                    when (quality) {
                        AlbumArtQuality.PREVIEW -> ALBUM_ART_PREVIEW_MAX_CHUNKS
                        AlbumArtQuality.HQ -> ALBUM_ART_HQ_MAX_CHUNKS
                        AlbumArtQuality.FULL -> ALBUM_ART_FULL_MAX_CHUNKS
                    }
                )
            }
            if (cachedPackets != null) {
                logger(
                    "[AlbumArtEncodedCache] hit id=$protocolId " +
                        "quality=${quality.wireValue} bytes=${cached.bytes.size}"
                )
                return AlbumArtPreparation(
                    prepared = PreparedAlbumArt(cached, cachedPackets)
                )
            }
        }
        val attempts = when (quality) {
            AlbumArtQuality.PREVIEW -> AlbumArtCompressionPolicy.previewProfiles().map {
                CompressionAttempt(it.width, it.height, it.quality, PREVIEW_MAX_JPEG_BYTES)
            }
            AlbumArtQuality.HQ -> AlbumArtCompressionPolicy.hqProfiles(
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height
            ).map {
                CompressionAttempt(it.width, it.height, it.quality, HQ_MAX_JPEG_BYTES)
            }.also {
                if (bitmap.width < 240 || bitmap.height < 240) {
                    logger(
                        "[AlbumArtHQ] source resolution limited " +
                            "width=${bitmap.width} height=${bitmap.height}"
                    )
                }
            }
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
        var bestBytes = Int.MAX_VALUE
        var bestChunks = 0
        var minCandidateScale = Int.MAX_VALUE

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
            minCandidateScale = minOf(minCandidateScale, minOf(compressed.width, compressed.height))
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
                if (compressed.bytes.size < bestBytes) {
                    bestBytes = compressed.bytes.size
                    bestChunks = estimateAlbumArtChunks(
                        binaryTransport = binaryTransport,
                        deviceMaximumPayload = deviceMaximumPayload,
                        bytes = compressed.bytes.size
                    )
                }
                val prefix = if (binaryTransport && quality == AlbumArtQuality.HQ) {
                    "[AlbumArtHQ]"
                } else if (quality == AlbumArtQuality.PREVIEW && binaryTransport) {
                    "[AlbumArtBinary]"
                } else {
                    "[AlbumArt][BLE]"
                }
                logger(
                    "$prefix candidate scale=${compressed.width} " +
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
                if (!accepted && compressed.bytes.size < bestBytes) {
                    bestBytes = compressed.bytes.size
                    bestChunks = packets.totalChunks
                }
                val rejectReason = when {
                    compressed.bytes.size > attempt.maximumBytes -> "bytes_exceed"
                    packets.totalChunks > maximumChunks -> "chunks_exceed"
                    else -> ""
                }
                logger(
                    "$prefix candidate scale=${compressed.width} " +
                        "quality=${attempt.quality} bytes=${compressed.bytes.size} " +
                        "chunks=${packets.totalChunks} accepted=$accepted" +
                        if (accepted) "" else " reason=$rejectReason"
                )
            }

            if (compressed.bytes.size <= attempt.maximumBytes &&
                packets.totalChunks <= maximumChunks
            ) {
                putEncodedAlbumArt(encodedCacheKey, compressed)
                if (quality == AlbumArtQuality.PREVIEW || quality == AlbumArtQuality.HQ) {
                    val prefix = if (binaryTransport) {
                        if (quality == AlbumArtQuality.HQ) "[AlbumArtHQ]" else "[AlbumArtBinary]"
                    } else {
                        "[AlbumArt]"
                    }
                    logger(
                        "$prefix selected scale=${compressed.width} " +
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
                        packets = packets,
                        fallback = quality == AlbumArtQuality.HQ &&
                            (attempt.width < bitmap.width ||
                                attempt.height < bitmap.height ||
                                attempt.quality < 88)
                    )
                )
            }

            logger(
                "[AlbumArt][BLE] candidate scale=${compressed.width} " +
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
        return AlbumArtPreparation(
            bestBytes = if (bestBytes == Int.MAX_VALUE) 0 else bestBytes,
            bestChunks = bestChunks,
            minCandidateScale = if (minCandidateScale == Int.MAX_VALUE) 0 else minCandidateScale
        )
    }

    private fun getEncodedAlbumArt(key: String): CompressedAlbumArt? {
        synchronized(encodedAlbumArtCache) {
            return encodedAlbumArtCache[key]
        }
    }

    private fun putEncodedAlbumArt(key: String, value: CompressedAlbumArt) {
        synchronized(encodedAlbumArtCache) {
            encodedAlbumArtCache.remove(key)?.let {
                encodedAlbumArtCacheBytes -= it.bytes.size
            }
            encodedAlbumArtCache[key] = value
            encodedAlbumArtCacheBytes += value.bytes.size
            while (encodedAlbumArtCache.size > ENCODED_ART_CACHE_CAPACITY ||
                encodedAlbumArtCacheBytes > ENCODED_ART_CACHE_MAX_BYTES
            ) {
                val eldest = encodedAlbumArtCache.entries.firstOrNull() ?: break
                encodedAlbumArtCache.remove(eldest.key)
                encodedAlbumArtCacheBytes -= eldest.value.bytes.size
            }
        }
    }

    private fun removeEncodedAlbumArtForProtocol(protocolId: String) {
        synchronized(encodedAlbumArtCache) {
            val prefix = "$protocolId|"
            val iterator = encodedAlbumArtCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith(prefix)) {
                    encodedAlbumArtCacheBytes -= entry.value.bytes.size
                    iterator.remove()
                }
            }
            encodedAlbumArtCacheBytes = encodedAlbumArtCacheBytes.coerceAtLeast(0)
        }
    }

    private fun estimateAlbumArtChunks(
        binaryTransport: Boolean,
        deviceMaximumPayload: Int,
        bytes: Int
    ): Int {
        val chunkSize = if (binaryTransport) {
            deviceMaximumPayload - ALBUM_ART_BINARY_HEADER_BYTES
        } else {
            MAX_ALBUM_CHUNK_RAW_BYTES
        }
        if (chunkSize <= 0) {
            return 0
        }
        return (bytes + chunkSize - 1) / chunkSize
    }

    private fun compressAlbumArt(
        bitmap: Bitmap,
        attempt: CompressionAttempt
    ): CompressedAlbumArt? {
        val scale = minOf(
            attempt.width.toFloat() / bitmap.width.toFloat(),
            attempt.height.toFloat() / bitmap.height.toFloat(),
            1f
        )
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (targetWidth == bitmap.width && targetHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }
        val output = ByteArrayOutputStream()
        val compressed = scaled.compress(
            Bitmap.CompressFormat.JPEG,
            attempt.quality,
            output
        )
        val compressedWidth = scaled.width
        val compressedHeight = scaled.height
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
            width = compressedWidth,
            height = compressedHeight,
            quality = attempt.quality
        )
    }

    private fun exportAlbumArtDiagnostics(
        protocolId: String,
        playbackState: JSONObject?,
        selectedSource: String,
        sourceBitmap: Bitmap,
        compressed: CompressedAlbumArt,
        quality: AlbumArtQuality,
        totalChunks: Int
    ) {
        if (!DEBUG_ART_DIAGNOSTICS) return
        try {
            val directory = appContext.getExternalFilesDir("AlbumArtDiagnostics") ?: return
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val sourceBytes = ByteArrayOutputStream().use { output ->
                sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            File(directory, "${protocolId}_source.png").writeBytes(sourceBytes)
            File(directory, "${protocolId}_${quality.wireValue}.jpg")
                .writeBytes(compressed.bytes)

            val metadataFile = File(directory, "${protocolId}_metadata.json")
            val metadata = if (metadataFile.exists()) {
                runCatching {
                    JSONObject(metadataFile.readText(Charsets.UTF_8))
                }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }
            metadata
                .put("id", protocolId)
                .put("title", playbackState?.optString("title").orEmpty())
                .put("artist", playbackState?.optString("artist").orEmpty())
                .put("album", playbackState?.optString("album").orEmpty())
                .put("selectedSource", selectedSource)
                .put("sourceWidth", sourceBitmap.width)
                .put("sourceHeight", sourceBitmap.height)
                .put("sourceConfig", sourceBitmap.config?.name ?: "unknown")
                .put("sourceAllocationByteCount", sourceBitmap.allocationByteCount)
                .put("sourcePngBytes", sourceBytes.size)
                .put("sourceSha256", sha256(sourceBytes))
                .put("${quality.wireValue}Width", compressed.width)
                .put("${quality.wireValue}Height", compressed.height)
                .put("${quality.wireValue}Bytes", compressed.bytes.size)
                .put("${quality.wireValue}Quality", compressed.quality)
                .put("${quality.wireValue}Chunks", totalChunks)
                .put("${quality.wireValue}Sha256", sha256(compressed.bytes))
                .put("updatedAt", System.currentTimeMillis())
            metadataFile.writeText(metadata.toString(2), Charsets.UTF_8)
            logger(
                "[ArtDiag-Sony] id=$protocolId quality=${quality.wireValue} " +
                    "source=${sourceBitmap.width}x${sourceBitmap.height} " +
                    "encoded=${compressed.width}x${compressed.height} " +
                    "bytes=${compressed.bytes.size} sha256=${sha256(compressed.bytes)}"
            )
        } catch (exception: Exception) {
            logger("[ArtDiag-Sony] export failed id=$protocolId error=${exception.message}")
        }
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
        val transferId = UUID.randomUUID().toString().replace("-", "").take(12)
        val crc32 = BleTransferCodec.crc32Hex(bytes)
        val generation = reactiveMediaController.generation()
        val start = JSONObject()
            .put("type", "albumArtBinaryStart")
            .put("id", protocolId)
            .put("quality", quality.wireValue)
            .put("transferId", transferId)
            .put("crc32", crc32)
            .put("generation", generation)
            .put("size", bytes.size)
            .put("chunks", totalChunks)
            .put("format", "jpg")
            .toString()
            .toByteArray(Charsets.UTF_8)
        val end = JSONObject()
            .put("type", "albumArtBinaryEnd")
            .put("id", protocolId)
            .put("quality", quality.wireValue)
            .put("transferId", transferId)
            .put("crc32", crc32)
            .put("generation", generation)
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
        reason: String,
        bestBytes: Int = 0,
        bestChunks: Int = 0,
        minCandidateScale: Int = 0
    ) {
        val objectValue = JSONObject()
            .put("type", "albumArtUnavailable")
            .put("id", protocolId)
            .put("quality", quality.wireValue)
            .put("reason", reason)
        if (bestBytes > 0) {
            objectValue.put("bestBytes", bestBytes)
        }
        if (bestChunks > 0) {
            objectValue.put("bestChunks", bestChunks)
        }
        if (minCandidateScale > 0) {
            objectValue.put("minCandidateScale", minCandidateScale)
        }
        val value = objectValue.toString()
            .toByteArray(Charsets.UTF_8)
        if (value.size > maximumPayloadFor(device)) {
            logger("[AlbumArt][BLE] unavailable message exceeds MTU")
            logger("[AlbumArtDebug] unavailable reason=unavailable message exceeds MTU")
            TrackCapabilityTracker.onPayloadTooLarge(protocolId = protocolId)
            return
        }
        logger("[AlbumArt][BLE] unavailable")
        logger("[AlbumArtDebug] unavailable reason=$reason")
        TrackCapabilityTracker.onAlbumArtLoadDone(
            protocolId = protocolId,
            success = false,
            source = "none",
            width = 0,
            height = 0,
            byteSize = 0,
            reason = reason
        )
        sendMediaLoadState(
            device = device,
            resource = "artwork",
            stage = if (reason.contains("compress", true)) "failed" else "unavailable",
            reason = when {
                reason.contains("compress", true) -> "encode_failed"
                reason.contains("source", true) || reason.contains("not found", true) ->
                    "source_unavailable"
                else -> "transfer_failed"
            },
            retryable = false,
            trackId = protocolId,
            generation = playbackStateReader.runtimeCacheSnapshot()
                .track?.currentTrackGeneration ?: 0L
        )
        if (quality == AlbumArtQuality.HQ) {
            logger(
                "[AlbumArtHQ] unavailable sent reason=$reason " +
                    "bestBytes=$bestBytes bestChunks=$bestChunks " +
                    "minCandidateScale=$minCandidateScale"
            )
        }
        notifyQueue.enqueueShort(
            device = device,
            type = "albumArtUnavailable",
            value = value,
            delayAfterMs = SHORT_MESSAGE_DELAY_MS
        )
    }

    private fun buildAlbumArtCacheKey(playbackState: JSONObject): String {
        val protocolId = buildAlbumArtProtocolId(playbackState)
        if (protocolId.isNotBlank()) {
            return "track:$protocolId"
        }
        // QQ Music can publish a provisional duration and correct it a few
        // hundred milliseconds later. Duration therefore cannot be part of
        // track identity: treating that correction as a track switch cancels
        // an in-flight lyric/art transfer for the song that is still playing.
        return listOf(
            playbackState.optString("title").trim(),
            playbackState.optString("artist").trim(),
            playbackState.optString("album").trim()
        ).joinToString("|")
    }

    private fun buildAlbumArtCacheKeyFromProtocol(protocolId: String): String {
        return currentAlbumArtPlaybackState?.let(::buildAlbumArtCacheKey)
            ?: "protocol:$protocolId"
    }

    private fun albumArtPrimaryCacheKey(protocolId: String): String = "track:$protocolId"

    private fun albumArtFallbackCacheKey(cacheKey: String): String = "identity:$cacheKey"

    private fun buildAlbumArtProtocolId(playbackState: JSONObject): String {
        return buildAlbumArtProtocolId(
            title = playbackState.optString("title"),
            artist = playbackState.optString("artist"),
            album = playbackState.optString("album")
        )
    }

    private fun buildAlbumArtProtocolId(
        title: String,
        artist: String,
        album: String
    ): String {
        // Never turn an empty QQ media session into a deterministic fake ID.
        // "||" used to hash to a stable value, which made the iPhone retain a
        // stale track and repeatedly request lyrics/artwork for it after QQ
        // Music was stopped.
        if (title.isBlank() && artist.isBlank() && album.isBlank()) {
            return ""
        }
        val source = listOf(title, artist, album).joinToString("|")
        return sha256(source.toByteArray(Charsets.UTF_8))
            .take(ALBUM_ART_ID_HASH_BYTES)
    }

    private fun normalizeCurrentWordTrackId(trackId: String): String {
        val trimmed = trackId.trim()
        if (trimmed.length <= ALBUM_ART_ID_HASH_BYTES) {
            return trimmed
        }
        return trimmed.take(ALBUM_ART_ID_HASH_BYTES)
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
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

    private fun maximumPayloadFor(device: BluetoothDevice): Int {
        val mtu = mtuByAddress[device.address] ?: DEFAULT_MTU
        return BleGattPayloadPolicy.maximumNotificationPayload(mtu)
    }

    private fun albumArtMaximumPayloadFor(device: BluetoothDevice): Int {
        return maximumPayloadFor(device)
    }

    private fun sendRemoteLogs(device: BluetoothDevice, limit: Int) {
        if (!subscribedDevices.containsKey(device.address)) {
            logger("[RemoteLog] send skipped: controller unsubscribed")
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

    private fun sendMediaFieldDump(device: BluetoothDevice) {
        logger("[MediaFieldDump] requested")
        if (!subscribedDevices.containsKey(device.address)) {
            logger("[MediaFieldDump] send failed: controller unsubscribed")
            return
        }
        if (notifyQueue.hasJobTypeActiveOrQueued(MEDIA_FIELD_DUMP_JOB_TYPE, device.address) ||
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
                enqueueMediaFieldDump(device, bytes)
            } catch (throwable: Throwable) {
                logger(
                    "[MediaFieldDump] build failed: " +
                        "${throwable.javaClass.simpleName}: ${throwable.message}"
                )
                sendMediaFieldDumpError(
                    device,
                    "${throwable.javaClass.simpleName}: ${throwable.message}"
                )
            } finally {
                mediaFieldDumpPreparing.set(false)
            }
        }
    }

    private fun enqueueMediaFieldDump(device: BluetoothDevice, bytes: ByteArray) {
        if (!subscribedDevices.containsKey(device.address)) {
            logger("[MediaFieldDump] send failed: controller unsubscribed")
            return
        }
        val maximumPayload = maximumPayloadFor(device)
        val rawChunkSize = chooseMediaFieldDumpChunkSize(maximumPayload)
        if (rawChunkSize <= 0) {
            sendMediaFieldDumpError(device, "MTU too small")
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
            sendMediaFieldDumpError(device, "start packet exceeds MTU")
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
                sendMediaFieldDumpError(device, "chunk $index exceeds MTU")
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
            sendMediaFieldDumpError(device, "end packet exceeds MTU")
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

    private fun sendMediaFieldDumpError(device: BluetoothDevice, message: String) {
        if (!subscribedDevices.containsKey(device.address)) return
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

    private fun sendPlayHistoryPage(device: BluetoothDevice, request: JSONObject) {
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
        executeHistoryQuery(device, requestId, PLAY_HISTORY_JOB_TYPE, "playHistoryPage") {
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

    private fun sendPlayHistorySince(device: BluetoothDevice, request: JSONObject) {
        val requestId = request.optString("requestId").ifBlank {
            "history-since-${System.currentTimeMillis()}"
        }
        val afterSessionId = request.optLong("afterSessionId", 0L).coerceAtLeast(0L)
        val limit = request.optInt("limit", DEFAULT_HISTORY_PAGE_LIMIT)
            .coerceIn(1, MAX_HISTORY_PAGE_LIMIT)
        executeHistoryQuery(device, requestId, PLAY_HISTORY_JOB_TYPE, "playHistorySince") {
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

    private fun sendPlayStats(device: BluetoothDevice, request: JSONObject) {
        val requestId = request.optString("requestId").ifBlank {
            "stats-${System.currentTimeMillis()}"
        }
        val rangeValue = request.optString("range", StatsRange.WEEK.name)
        val range = runCatching { StatsRange.valueOf(rangeValue.uppercase()) }.getOrNull()
        if (range == null) {
            sendHistoryError(device, requestId, "playStats", "invalid range=$rangeValue")
            return
        }
        executeHistoryQuery(device, requestId, PLAY_STATS_JOB_TYPE, "playStats") {
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
        device: BluetoothDevice,
        requestId: String,
        jobType: String,
        responseType: String,
        buildPayload: () -> JSONObject
    ) {
        if (!subscribedDevices.containsKey(device.address)) {
            logger("[HistoryBLE] request skipped requestId=$requestId reason=no subscriber")
            return
        }
        if (!historyQueryPreparing.compareAndSet(false, true)) {
            logger("[HistoryBLE] request busy requestId=$requestId")
            sendHistoryError(device, requestId, responseType, "history query already active")
            return
        }
        logger("[HistoryBLE] request type=$responseType requestId=$requestId")
        historyExecutor.execute {
            try {
                logger("[HistoryBLE] query start requestId=$requestId")
                enqueueHistoryPayload(device, jobType, responseType, requestId, buildPayload())
            } catch (throwable: Throwable) {
                logger("[HistoryBLE] failed requestId=$requestId reason=${throwable.message}")
                sendHistoryError(
                    device,
                    requestId,
                    responseType,
                    throwable.message ?: "query failed"
                )
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
            sendHistoryError(device, requestId, responseType, "MTU too small")
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
            sendHistoryError(device, requestId, responseType, "start packet exceeds MTU")
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
                sendHistoryError(device, requestId, responseType, "chunk $index exceeds MTU")
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
            sendHistoryError(device, requestId, responseType, "end packet exceeds MTU")
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

    private fun sendHistoryError(
        device: BluetoothDevice,
        requestId: String,
        responseType: String,
        message: String
    ) {
        if (!subscribedDevices.containsKey(device.address)) return
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

    private fun sendFullLyrics(device: BluetoothDevice, request: JSONObject) {
        if (request.optBoolean("forceRefresh", false)) {
            val started = playbackStateReader.manualRefreshCurrentLyric()
            logger("[LyricRetry] BLE force refresh requested started=$started")
        }
        if (!subscribedDevices.containsKey(device.address)) {
            val requestedTrackId = request.optString("trackId")
                .ifBlank { request.optString("id") }
            if (requestedTrackId.isNotBlank()) {
                lyricTrace(
                    stage = "fullLyricsSendSkip",
                    trackId = requestedTrackId,
                    reason = "no_subscriber",
                    extra = mapOf("status" to "no_subscriber")
                )
            }
            logger("[FullLyrics] send skipped: controller unsubscribed")
            return
        }
        val buildStartedAtMs = SystemClock.elapsedRealtime()
        val requestedTrackId = request.optString("trackId")
            .ifBlank { request.optString("id") }
        if (notifyQueue.hasJobTypeActiveOrQueued(FULL_LYRICS_JOB_TYPE, device.address)) {
            logger(
                "[FullLyrics] request skipped reason=transfer_in_progress " +
                    "trackId=$requestedTrackId"
            )
            lyricTrace(
                stage = "fullLyricsSendSkip",
                trackId = requestedTrackId,
                reason = "transfer_in_progress"
            )
            return
        }
        if (requestedTrackId.isNotBlank()) {
            lyricTrace(
                stage = "fullLyricsRequest",
                trackId = requestedTrackId,
                extra = mapOf(
                    "positionMs" to request.optLong(
                        "positionMs",
                        request.optLong("p", 0L)
                    ).toString()
                )
            )
        }
        val source = playbackSourceForRequestedTrack(requestedTrackId)
        val trackId = buildAlbumArtProtocolId(source)
        val traceId = requestedTrackId.ifBlank { trackId }
        TrackCapabilityTracker.onFullLyricsRequested(traceId, trackId)
        val title = source.optString("title")
        val artist = source.optString("artist")
        val runtimeSnapshot = playbackStateReader.runtimeCacheSnapshot()
        val runtimeLineCount = runtimeSnapshot.track?.lyricLines?.size ?: 0
        val runtimeGeneration = runtimeSnapshot.track?.currentTrackGeneration ?: 0L
        val readyGate = playbackStateReader.lyricsReadyGateSnapshot()
        val allLines = playbackStateReader.runtimeLyricLinesSnapshot()
        if (runtimeLineCount > 0) {
            RealtimeTrace.record(
                stage = "lyricCacheHit",
                trackId = traceId,
                generation = runtimeGeneration,
                payloadType = "fullLyrics",
                result = "hit"
            )
        }
        val candidateLines = allLines
            .filter { it.text.isNotBlank() }
            .take(MAX_FULL_LYRICS_LINES)
        val gateAllowsSend = lyricsReadyGateAllowsFullLyrics(
            gate = readyGate,
            requestedTrackId = requestedTrackId,
            protocolTrackId = trackId,
            runtimeGeneration = runtimeGeneration,
            lineCount = candidateLines.size
        )
        val lines = if (gateAllowsSend) candidateLines else emptyList()
        val fullLyricsSourceStage = if (runtimeLineCount > 0) {
            "fullLyricsFromRuntime"
        } else {
            "fullLyricsFromLyricManager"
        }
        lyricTrace(
            stage = fullLyricsSourceStage,
            trackId = traceId,
            songKey = readyGate.songKey,
            generation = readyGate.generation,
            costMs = SystemClock.elapsedRealtime() - buildStartedAtMs,
            extra = mapOf(
                "runtimeLines" to runtimeLineCount.toString(),
                "selectedLines" to lines.size.toString(),
                "readyState" to readyGate.state.name,
                "lyricsReady" to readyGate.lyricsReady.toString(),
                "gateAllowed" to gateAllowsSend.toString(),
                "runtimeGeneration" to runtimeGeneration.toString()
            )
        )
        val includeWordsAroundCurrent = request.optBoolean(
            "includeWordsAroundCurrent",
            request.optBoolean("w", false)
        )
        val requestedPositionMs = request.optLong(
            "positionMs",
            request.optLong("p", source.optLong("position", 0L))
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
        val debugLineIndexes = (wordLineIndexes + setOf(0, 1, 2))
            .filter { it in lines.indices }
            .toSet()

        if (lines.isEmpty()) {
            val unavailableReason = playbackStateReader.lyricUnavailableReason()
            val diagnostic = playbackStateReader.lyricDiagnosticSnapshot()
            if (!readyGate.lyricsReady || candidateLines.isEmpty()) {
                rememberPendingFullLyricsRequest(
                    device = device,
                    request = request,
                    requestedTrackId = requestedTrackId,
                    protocolTrackId = trackId,
                    generation = runtimeGeneration,
                    reason = unavailableReason,
                    readyGate = readyGate
                )
            }
            val recoveryRetryStarted = if (isFullLyricsRecoveryNudgeReason(unavailableReason) &&
                !request.optBoolean("_lyricsRecoveryRetry", false)
            ) {
                playbackStateReader.nudgeLyricRecoveryFromFullLyricsRequest()
            } else {
                false
            }
            var deferredForForegroundParse = false
            if (unavailableReason == "lyrics loading" &&
                !request.optBoolean("_lyricsReadyRetry", false)
            ) {
                logger("[FullLyrics] pending request reason=lyrics loading")
                val retryRequest = JSONObject(request.toString())
                    .put("_lyricsReadyRetry", true)
                val pendingToken = pendingFullLyricsRequests[device.address]
                albumArtHandler.postDelayed({
                    dispatchPendingFullLyricsRetry(
                        device = device,
                        pendingToken = pendingToken,
                        retryRequest = retryRequest,
                        reason = "lyrics loading"
                    )
                }, FULL_LYRICS_PENDING_RETRY_DELAY_MS)
                deferredForForegroundParse = true
            } else if (recoveryRetryStarted) {
                logger(
                    "[FullLyrics] pending request reason=$unavailableReason " +
                        "action=recovery_nudge"
                )
                val retryRequest = JSONObject(request.toString())
                    .put("_lyricsRecoveryRetry", true)
                val pendingToken = pendingFullLyricsRequests[device.address]
                albumArtHandler.postDelayed({
                    dispatchPendingFullLyricsRetry(
                        device = device,
                        pendingToken = pendingToken,
                        retryRequest = retryRequest,
                        reason = "recovery nudge"
                    )
                }, FULL_LYRICS_RECOVERY_RETRY_DELAY_MS)
                deferredForForegroundParse = true
            }
            if (deferredForForegroundParse) {
                sendMediaLoadState(
                    device = device,
                    resource = "lyrics",
                    stage = "waiting",
                    reason = "qrc_pending",
                    retryable = true,
                    trackId = trackId,
                    generation = runtimeGeneration,
                    retryAfterMs = FULL_LYRICS_RECOVERY_RETRY_DELAY_MS
                )
                sendCommandError(
                    device = device,
                    request = request,
                    domain = "lyrics",
                    code = "qrc_pending",
                    retryable = true,
                    retryAfterMs = FULL_LYRICS_RECOVERY_RETRY_DELAY_MS,
                    trackId = trackId,
                    generation = runtimeGeneration
                )
                logger(
                    "[FullLyrics] unavailable deferred trackId=$trackId " +
                        "reason=$unavailableReason maxWaitMs=$FULL_LYRICS_RECOVERY_RETRY_DELAY_MS"
                )
                return
            }
            val v3Reason = lyricLoadReasonCode(unavailableReason)
            val parseFailed = v3Reason == "qrc_parse_failed" || v3Reason == "transfer_failed"
            sendMediaLoadState(
                device = device,
                resource = "lyrics",
                stage = if (parseFailed) "failed" else "unavailable",
                reason = v3Reason,
                retryable = false,
                trackId = trackId,
                generation = runtimeGeneration
            )
            sendCommandError(
                device = device,
                request = request,
                domain = "lyrics",
                code = v3Reason,
                retryable = false,
                trackId = trackId,
                generation = runtimeGeneration
            )
            val unavailable = JSONObject()
                .put("type", "fullLyricsUnavailable")
                .put("trackId", trackId)
                .put("reason", unavailableReason)
                .put("lyricStatus", diagnostic.status)
                .put("lyricSuggestion", diagnostic.suggestion)
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
            logger(
                "[FullLyrics] unavailable trackId=$trackId " +
                    "reason=$unavailableReason"
            )
            lyricTrace(
                stage = "fullLyricsSendSkip",
                trackId = traceId,
                songKey = readyGate.songKey,
                generation = readyGate.generation,
                reason = unavailableReason,
                extra = mapOf("status" to diagnostic.status)
            )
            TrackCapabilityTracker.onFullLyricsSent(traceId, trackId, 0)
            return
        }
        clearMatchingPendingFullLyrics(
            device.address,
            requestedTrackId,
            trackId,
            runtimeGeneration
        )

        val maximumPayload = maximumPayloadFor(device)
        val requestedFormat = request.optString("format").ifBlank {
            if (request.optString("f") == "z") FULL_LYRICS_ZLIB_FORMAT else ""
        }
        val negotiatedCapabilities = connectionCommandCoordinator.capabilities(device.address)
        val cacheDescriptor = FullLyricsCacheValidation.describe(title, artist, lines)
        val cacheValidationRequest = parseFullLyricsCacheValidationRequest(request)
        val cacheValidationDecision = if (request.optBoolean("forceRefresh", false)) {
            FullLyricsCacheValidationDecision.REQUEST_MISSING
        } else {
            FullLyricsCacheValidation.decide(
                capabilityEnabled = negotiatedCapabilities.mediaCacheValidationV1,
                request = cacheValidationRequest,
                actual = cacheDescriptor
            )
        }
        if (cacheValidationDecision == FullLyricsCacheValidationDecision.HIT &&
            sendFullLyricsCacheStatus(
                device = device,
                type = "fullLyricsNotModified",
                trackId = trackId,
                generation = readyGate.generation,
                descriptor = cacheDescriptor
            )
        ) {
            val bytesSaved = FullLyricsCacheValidation.estimatedPayloadBytes(lines)
            sendMediaLoadState(
                device, "lyrics", "ready", "cache_validation_hit", false,
                trackId, readyGate.generation
            )
            RealtimeTrace.record(
                stage = "cacheValidationHit",
                trackId = traceId,
                generation = readyGate.generation,
                payloadType = "fullLyrics",
                processingMs = SystemClock.elapsedRealtime() - buildStartedAtMs,
                result = "hit"
            )
            RealtimeTrace.record(
                stage = "fullLyricsTransferSkipped",
                trackId = traceId,
                generation = readyGate.generation,
                payloadType = "fullLyrics",
                result = "skipped",
                reason = "cache_validation_hit bytesSaved=$bytesSaved"
            )
            logger(
                "[FullLyricsCacheValidation] hit trackId=$trackId " +
                    "lines=${lines.size} bytesSaved=$bytesSaved"
            )
            return
        }
        if (cacheValidationRequest != null) {
            RealtimeTrace.record(
                stage = "cacheValidationMiss",
                trackId = traceId,
                generation = readyGate.generation,
                payloadType = "fullLyrics",
                result = "miss",
                reason = cacheValidationDecision.name.lowercase()
            )
            logger(
                "[FullLyricsCacheValidation] miss trackId=$trackId " +
                    "reason=${cacheValidationDecision.name.lowercase()}"
            )
        }
        if (negotiatedCapabilities.mediaCacheValidationV1) {
            sendFullLyricsCacheStatus(
                device = device,
                type = "fullLyricsCacheMetadata",
                trackId = trackId,
                generation = readyGate.generation,
                descriptor = cacheDescriptor
            )
        }
        sendMediaLoadState(
            device = device,
            resource = "lyrics",
            stage = "preparing",
            reason = "transfer_preparing",
            retryable = false,
            trackId = trackId,
            generation = runtimeGeneration
        )
        if (requestedFormat == FULL_LYRICS_ZLIB_FORMAT &&
            negotiatedCapabilities.negotiated &&
            negotiatedCapabilities.protocolVersion >= SERVER_PROTOCOL_VERSION &&
            negotiatedCapabilities.fullLyricsZlib &&
            sendCompressedFullLyrics(
                device = device,
                trackId = trackId,
                title = title,
                artist = artist,
                generation = readyGate.generation,
                lines = lines,
                wordLineIndexes = wordLineIndexes,
                maximumPayload = maximumPayload,
                traceId = traceId,
                songKey = readyGate.songKey,
                buildStartedAtMs = buildStartedAtMs
            )
        ) {
            clearMatchingPendingFullLyrics(
                device.address,
                requestedTrackId,
                trackId,
                runtimeGeneration
            )
            return
        }
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
            if (index in debugLineIndexes) {
                val payload = runCatching { JSONObject(String(chunk, Charsets.UTF_8)) }
                    .getOrNull()
                logger(
                    "[FullLyricsDebug] chunk index=$index " +
                        "text=${line.text.take(24)} " +
                        "translation=${line.translation?.take(24).orEmpty()} " +
                        "romanization=${line.romanization?.take(24).orEmpty()} " +
                        "payloadKeys=${payload?.keys()?.asSequence()?.toList().orEmpty()}"
                )
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
        if (allLines.size > MAX_FULL_LYRICS_LINES) {
            logger("[FullLyrics] truncated count=${allLines.size}")
        }
        val wordsLines = wordLineIndexes.count { lines[it].words.isNotEmpty() }
        logger(
            "[FullLyricsDebug] first line trans=${lines.firstOrNull()?.translation?.take(24).orEmpty()} " +
                "roma=${lines.firstOrNull()?.romanization?.take(24).orEmpty()}"
        )
        logger(
            "[FullLyrics] mode=lineOnly wordsAroundCurrent=$includeWordsAroundCurrent " +
                "wordLines=$wordsLines"
        )
        logger(
            "[FullLyricsPerf] build done lines=${lines.size} " +
                "wordsLines=$wordsLines costMs=${SystemClock.elapsedRealtime() - buildStartedAtMs}"
        )
        lyricTrace(
            stage = "fullLyricsBuildDone",
            trackId = traceId,
            songKey = readyGate.songKey,
            generation = readyGate.generation,
            costMs = SystemClock.elapsedRealtime() - buildStartedAtMs,
            extra = mapOf(
                "lines" to lines.size.toString(),
                "wordsLines" to wordsLines.toString()
            )
        )
        val sendStartedAtMs = SystemClock.elapsedRealtime()
        sendMediaLoadState(
            device, "lyrics", "transferring", "transfer_preparing", false,
            trackId, readyGate.generation
        )
        logger("[FullLyrics] send start trackId=$trackId count=${lines.size}")
        lyricTrace(
            stage = "fullLyricsSendStart",
            trackId = traceId,
            songKey = readyGate.songKey,
            generation = readyGate.generation,
            extra = mapOf("lines" to lines.size.toString())
        )
        RealtimeTrace.record(
            stage = "fullLyricsEnqueued",
            monoMs = sendStartedAtMs,
            trackId = traceId,
            generation = readyGate.generation,
            payloadType = "fullLyricsJson",
            chunkCount = packets.size,
            result = "queued"
        )
        RealtimeTrace.record(
            stage = "fullLyricsSendStart",
            monoMs = sendStartedAtMs,
            trackId = traceId,
            generation = readyGate.generation,
            payloadType = "fullLyricsJson",
            result = "started"
        )
        notifyQueue.enqueueLongJob(
            type = FULL_LYRICS_JOB_TYPE,
            device = device,
            packets = packets,
            maxSendDurationMs = FULL_LYRICS_MAX_SEND_MS,
            shouldCancel = { !isLyricsTransferCurrent(trackId, readyGate.generation) },
            onComplete = {
                val sendEndedAtMs = SystemClock.elapsedRealtime()
                RealtimeTrace.record(
                    stage = "fullLyricsSendEnd",
                    monoMs = sendEndedAtMs,
                    trackId = traceId,
                    generation = readyGate.generation,
                    payloadType = "fullLyricsJson",
                    processingMs = (sendEndedAtMs - sendStartedAtMs).coerceAtLeast(0L),
                    result = "success"
                )
                logger(
                    "[FullLyricsPerf] send end costMs=" +
                        "${SystemClock.elapsedRealtime() - sendStartedAtMs}"
                )
                logger("[FullLyrics] send end trackId=$trackId")
                sendMediaLoadState(
                    device, "lyrics", "ready", "transfer_complete", false,
                    trackId, readyGate.generation
                )
                lyricTrace(
                    stage = "fullLyricsSendEnd",
                    trackId = traceId,
                    songKey = readyGate.songKey,
                    generation = readyGate.generation,
                    costMs = SystemClock.elapsedRealtime() - sendStartedAtMs,
                    extra = mapOf("lines" to lines.size.toString())
                )
                TrackCapabilityTracker.onFullLyricsSent(traceId, trackId, lines.size)
            },
            onFailure = {
                sendMediaLoadState(
                    device, "lyrics", "failed", "transfer_failed", true,
                    trackId, readyGate.generation
                )
                sendCommandError(
                    device, request, "lyrics", "transfer_failed", true,
                    trackId = trackId,
                    generation = readyGate.generation
                )
            }
        )
    }

    private fun sendCompressedFullLyrics(
        device: BluetoothDevice,
        trackId: String,
        title: String,
        artist: String,
        generation: Long,
        lines: List<com.example.playeragent.media.LyricManager.LyricLine>,
        wordLineIndexes: Set<Int>,
        maximumPayload: Int,
        traceId: String,
        songKey: String,
        buildStartedAtMs: Long
    ): Boolean {
        val cacheKey = CompressedLyricsCache.Key(
            songKey = songKey,
            trackId = trackId,
            generation = generation,
            format = FULL_LYRICS_ZLIB_FORMAT,
            contentFingerprint = fullLyricsContentFingerprint(
                title = title,
                artist = artist,
                lines = lines
            ),
            wordLineIndexes = wordLineIndexes.sorted().joinToString(",")
        )
        val compressedLyricsCache = lyricsTransferCoordinator.compressedCache
        val cached = compressedLyricsCache.get(cacheKey)
        val cacheEntry = cached ?: run {
            val body = JSONObject()
                .put("format", FULL_LYRICS_ZLIB_FORMAT)
                .put("trackId", trackId)
                .put("title", title)
                .put("artist", artist)
                .put("generation", generation)
                .put("count", lines.size)
                .put(
                    "lines",
                    JSONArray().also { array ->
                        lines.forEachIndexed { index, line ->
                            array.put(
                                JSONObject()
                                    .put("index", index)
                                    .put("timeMs", line.timeMs)
                                    .put("durationMs", line.durationMs)
                                    .put(
                                        "text",
                                        line.text.take(MAX_COMPRESSED_LYRIC_TEXT_LENGTH)
                                    )
                                    .also { item ->
                                        if (index in wordLineIndexes &&
                                            line.words.isNotEmpty()
                                        ) {
                                            item.put(
                                                "words",
                                                JSONArray().also { words ->
                                                    line.words.forEach { word ->
                                                        if (word.text.isNotBlank()) {
                                                            words.put(
                                                                JSONObject()
                                                                    .put("startMs", word.startMs)
                                                                    .put(
                                                                        "durationMs",
                                                                        word.durationMs
                                                                    )
                                                                    .put("text", word.text)
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                            )
                        }
                    }
                )
                .toString()
                .toByteArray(Charsets.UTF_8)
            val compressedBody = BleTransferCodec.zlibCompress(body)
            if (compressedBody.isEmpty() ||
                compressedBody.size > MAX_FULL_LYRICS_ZLIB_BYTES
            ) {
                logger(
                    "[FullLyricsV2] fallback legacy reason=compressed_size " +
                        "raw=${body.size} compressed=${compressedBody.size}"
                )
                return false
            }
            CompressedLyricsCache.Entry(
                compressed = compressedBody,
                uncompressedSize = body.size,
                crc32 = BleTransferCodec.crc32Hex(compressedBody)
            ).also { compressedLyricsCache.put(cacheKey, it) }
        }
        val compressed = cacheEntry.compressed
        val uncompressedSize = cacheEntry.uncompressedSize
        val crc32 = cacheEntry.crc32
        logger(
            "[FullLyricsCache] ${if (cached == null) "miss" else "hit"} " +
                "trackId=$trackId generation=$generation bytes=${compressed.size} " +
                "entries=${compressedLyricsCache.size()} totalBytes=${compressedLyricsCache.bytes()}"
        )
        if (compressed.isEmpty() || compressed.size > MAX_FULL_LYRICS_ZLIB_BYTES) {
            logger(
                "[FullLyricsV2] fallback legacy reason=compressed_size " +
                    "raw=$uncompressedSize compressed=${compressed.size}"
            )
            return false
        }
        val binaryChunks = runCatching {
            BleTransferCodec.binaryChunks(
                magic = FULL_LYRICS_BINARY_MAGIC,
                version = FULL_LYRICS_BINARY_VERSION,
                body = compressed,
                maximumPayload = maximumPayload
            )
        }.getOrElse {
            logger("[FullLyricsV2] fallback legacy reason=${it.message}")
            return false
        }
        val transferId = UUID.randomUUID().toString().replace("-", "").take(10)
        val verboseStart = JSONObject()
            .put("type", "fullLyricsBinaryStart")
            .put("trackId", trackId)
            .put("transferId", transferId)
            .put("generation", generation)
            .put("format", FULL_LYRICS_ZLIB_FORMAT)
            .put("size", compressed.size)
            .put("uncompressedSize", uncompressedSize)
            .put("chunks", binaryChunks.size)
            .put("count", lines.size)
            .put("crc32", crc32)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val verboseEnd = JSONObject()
            .put("type", "fullLyricsBinaryEnd")
            .put("trackId", trackId)
            .put("transferId", transferId)
            .put("generation", generation)
            .put("crc32", crc32)
            .toString()
            .toByteArray(Charsets.UTF_8)
        // A 185-byte ATT MTU leaves about 182 bytes for a notification. Keep the
        // descriptive form where it fits, but use v2-only aliases on constrained
        // links so the transfer does not needlessly fall back to legacy lyrics.
        val compactStart = JSONObject()
            .put("type", "fullLyricsBinaryStart")
            .put("id", trackId)
            .put("tid", transferId)
            .put("g", generation)
            .put("s", compressed.size)
            .put("u", uncompressedSize)
            .put("c", binaryChunks.size)
            .put("n", lines.size)
            .put("crc", crc32)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val compactEnd = JSONObject()
            .put("type", "fullLyricsBinaryEnd")
            .put("id", trackId)
            .put("tid", transferId)
            .put("g", generation)
            .put("crc", crc32)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val start = if (verboseStart.size <= maximumPayload) verboseStart else compactStart
        val end = if (verboseEnd.size <= maximumPayload) verboseEnd else compactEnd
        if (start.size > maximumPayload || end.size > maximumPayload) {
            logger("[FullLyricsV2] fallback legacy reason=metadata_exceeds_mtu")
            return false
        }
        val chunkPackets = binaryChunks.map {
            BleNotifyQueue.Packet(
                type = "fullLyricsBinaryChunk",
                value = it,
                delayAfterMs = FULL_LYRICS_BINARY_NOTIFICATION_DELAY_MS
            )
        }
        val startPacket = BleNotifyQueue.Packet(
            type = "fullLyricsBinaryStart",
            value = start,
            delayAfterMs = FULL_LYRICS_JSON_NOTIFICATION_DELAY_MS
        )
        val endPacket = BleNotifyQueue.Packet(
            type = "fullLyricsBinaryEnd",
            value = end,
            delayAfterMs = FULL_LYRICS_JSON_NOTIFICATION_DELAY_MS
        )
        lyricsTransferCoordinator.retain(FullLyricsBinaryTransfer(
            trackId = trackId,
            transferId = transferId,
            generation = generation,
            start = startPacket,
            chunks = chunkPackets,
            end = endPacket,
            expiresAtMs = SystemClock.elapsedRealtime() + FULL_LYRICS_RETRY_TTL_MS,
            ownerAddress = device.address
        ))
        val packets = buildList {
            add(startPacket)
            addAll(chunkPackets)
            add(endPacket)
        }
        val sendStartedAtMs = SystemClock.elapsedRealtime()
        sendMediaLoadState(
            device, "lyrics", "transferring", "transfer_preparing", false,
            trackId, generation
        )
        logger(
            "[FullLyricsV2] send start trackId=$trackId lines=${lines.size} " +
                "raw=$uncompressedSize compressed=${compressed.size} chunks=${binaryChunks.size} " +
                "metadata=${if (start === compactStart) "compact" else "verbose"}"
        )
        lyricTrace(
            stage = "fullLyricsBuildDone",
            trackId = traceId,
            songKey = songKey,
            generation = generation,
            costMs = SystemClock.elapsedRealtime() - buildStartedAtMs,
            extra = mapOf("format" to FULL_LYRICS_ZLIB_FORMAT, "lines" to lines.size.toString())
        )
        lyricTrace(
            stage = "fullLyricsSendStart",
            trackId = traceId,
            songKey = songKey,
            generation = generation,
            extra = mapOf(
                "format" to FULL_LYRICS_ZLIB_FORMAT,
                "lines" to lines.size.toString(),
                "chunks" to binaryChunks.size.toString()
            )
        )
        RealtimeTrace.record(
            stage = "fullLyricsEnqueued",
            monoMs = sendStartedAtMs,
            trackId = traceId,
            generation = generation,
            transferId = transferId,
            payloadType = "fullLyricsBinary",
            chunkCount = packets.size,
            result = "queued"
        )
        RealtimeTrace.record(
            stage = "fullLyricsSendStart",
            monoMs = sendStartedAtMs,
            trackId = traceId,
            generation = generation,
            transferId = transferId,
            payloadType = "fullLyricsBinary",
            result = "started"
        )
        notifyQueue.enqueueLongJob(
            type = FULL_LYRICS_JOB_TYPE,
            device = device,
            packets = packets,
            priority = BleNotifyQueue.Priority.P2_BULK,
            maxSendDurationMs = FULL_LYRICS_MAX_SEND_MS,
            shouldCancel = { !isLyricsTransferCurrent(trackId, generation) },
            onComplete = {
                val sendEndedAtMs = SystemClock.elapsedRealtime()
                RealtimeTrace.record(
                    stage = "fullLyricsSendEnd",
                    monoMs = sendEndedAtMs,
                    trackId = traceId,
                    generation = generation,
                    transferId = transferId,
                    payloadType = "fullLyricsBinary",
                    processingMs = (sendEndedAtMs - sendStartedAtMs).coerceAtLeast(0L),
                    result = "success"
                )
                sendMediaLoadState(
                    device, "lyrics", "ready", "transfer_complete", false,
                    trackId, generation
                )
                logger(
                    "[FullLyricsV2] send end trackId=$trackId " +
                        "costMs=${SystemClock.elapsedRealtime() - sendStartedAtMs}"
                )
                lyricTrace(
                    stage = "fullLyricsSendEnd",
                    trackId = traceId,
                    songKey = songKey,
                    generation = generation,
                    costMs = SystemClock.elapsedRealtime() - sendStartedAtMs,
                    extra = mapOf(
                        "format" to FULL_LYRICS_ZLIB_FORMAT,
                        "lines" to lines.size.toString()
                    )
                )
                TrackCapabilityTracker.onFullLyricsSent(traceId, trackId, lines.size)
            },
            onFailure = {
                sendMediaLoadState(
                    device, "lyrics", "failed", "transfer_failed", true,
                    trackId, generation
                )
            }
        )
        return true
    }

    private fun fullLyricsContentFingerprint(
        title: String,
        artist: String,
        lines: List<com.example.playeragent.media.LyricManager.LyricLine>
    ): Long {
        var hash = 1_469_598_103_934_665_603L
        fun mix(value: Long) {
            hash = (hash xor value) * 1_099_511_628_211L
        }
        mix(title.hashCode().toLong())
        mix(artist.hashCode().toLong())
        mix(lines.size.toLong())
        lines.forEach { line ->
            mix(line.timeMs)
            mix(line.durationMs)
            mix(line.text.hashCode().toLong())
            mix(line.translation.orEmpty().hashCode().toLong())
            mix(line.romanization.orEmpty().hashCode().toLong())
            mix(line.words.size.toLong())
            line.words.forEach { word ->
                mix(word.startMs)
                mix(word.durationMs)
                mix(word.text.hashCode().toLong())
            }
        }
        return hash
    }

    private fun parseFullLyricsCacheValidationRequest(
        request: JSONObject
    ): FullLyricsCacheValidationRequest? {
        val fingerprint = request.optString("knownFingerprint")
            .ifBlank { request.optString("fp") }
            .trim()
        if (fingerprint.isBlank() ||
            (!request.has("knownSchemaVersion") && !request.has("sv")) ||
            (!request.has("knownLineCount") && !request.has("n")) ||
            (!request.has("knownTranslationLineCount") && !request.has("tc")) ||
            (!request.has("knownRomanizationLineCount") && !request.has("rc"))
        ) {
            return null
        }
        return FullLyricsCacheValidationRequest(
            fingerprint = fingerprint,
            schemaVersion = if (request.has("knownSchemaVersion")) {
                request.optInt("knownSchemaVersion", -1)
            } else {
                request.optInt("sv", -1)
            },
            lineCount = if (request.has("knownLineCount")) {
                request.optInt("knownLineCount", -1)
            } else {
                request.optInt("n", -1)
            },
            translationLineCount = if (request.has("knownTranslationLineCount")) {
                request.optInt("knownTranslationLineCount", -1)
            } else {
                request.optInt("tc", -1)
            },
            romanizationLineCount = if (request.has("knownRomanizationLineCount")) {
                request.optInt("knownRomanizationLineCount", -1)
            } else {
                request.optInt("rc", -1)
            }
        )
    }

    private fun sendFullLyricsCacheStatus(
        device: BluetoothDevice,
        type: String,
        trackId: String,
        generation: Long,
        descriptor: FullLyricsCacheDescriptor
    ): Boolean {
        val value = JSONObject()
            .put("type", type)
            .put("id", trackId)
            .put("g", generation)
            .put("fp", descriptor.fingerprint)
            .put("sv", descriptor.schemaVersion)
            .put("n", descriptor.lineCount)
            .put("tc", descriptor.translationLineCount)
            .put("rc", descriptor.romanizationLineCount)
        if (value.toString().toByteArray(Charsets.UTF_8).size > maximumPayloadFor(device)) {
            logger("[FullLyricsCacheValidation] status skipped type=$type reason=metadata_exceeds_mtu")
            return false
        }
        sendShortJsonIfFits(device, type, value)
        return true
    }

    private fun sendLyricWindow(device: BluetoothDevice, request: JSONObject) {
        val capabilities = connectionCommandCoordinator.capabilities(device.address)
        if (!capabilities.negotiated || !capabilities.lyricWindow) return
        if (!subscribedDevices.containsKey(device.address)) return
        val source = playbackSourceForRequestedTrack(request.optString("trackId"))
        val trackId = buildAlbumArtProtocolId(source)
        val requestedTrackId = request.optString("trackId")
        if (trackId.isBlank() ||
            (requestedTrackId.isNotBlank() && !isSameProtocolTrackId(requestedTrackId, trackId))
        ) {
            sendLyricWindowUnavailable(device, trackId, "track mismatch")
            return
        }
        val lines = playbackStateReader.runtimeLyricLinesSnapshot()
            .filter { it.text.isNotBlank() }
            .take(MAX_FULL_LYRICS_LINES)
        val gate = playbackStateReader.lyricsReadyGateSnapshot()
        if (!lyricsReadyGateAllowsFullLyrics(
                gate,
                requestedTrackId,
                trackId,
                playbackStateReader.runtimeCacheSnapshot().track?.currentTrackGeneration ?: 0L,
                lines.size
            )
        ) {
            val reason = playbackStateReader.lyricUnavailableReason()
            pendingLyricWindowRequests[device.address] = PendingLyricWindowRequest(
                ownerAddress = device.address,
                request = JSONObject(request.toString()),
                requestedTrackId = requestedTrackId,
                protocolTrackId = trackId,
                generation = playbackStateReader.runtimeCacheSnapshot()
                    .track
                    ?.currentTrackGeneration
                    ?: 0L,
                createdAtMs = SystemClock.elapsedRealtime()
            )
            logger(
                "[LyricWindow] pending trackId=$trackId " +
                    "generation=${pendingLyricWindowRequests[device.address]?.generation} " +
                    "device=${device.address} reason=$reason"
            )
            RealtimeTrace.record(
                stage = "lyricWindowPendingQueued",
                trackId = requestedTrackId.ifBlank { trackId },
                generation = gate.generation,
                payloadType = "lyricWindow",
                result = "queued",
                reason = reason
            )
            return
        }
        pendingLyricWindowRequests.remove(device.address)
        val positionMs = request.optLong("positionMs", source.optLong("position", 0L))
        val currentIndex = findCurrentLyricIndex(lines, positionMs)
        val first = (currentIndex - 2).coerceAtLeast(0)
        val lastExclusive = (first + LYRIC_WINDOW_LINE_COUNT).coerceAtMost(lines.size)
        val window = lines.subList(first, lastExclusive)
        val transferId = UUID.randomUUID().toString().replace("-", "").take(12)
        val maximumPayload = maximumPayloadFor(device)
        val start = JSONObject()
            .put("type", "lyricWindowStart")
            .put("trackId", trackId)
            .put("transferId", transferId)
            .put("generation", gate.generation)
            .put("count", window.size)
            .put("currentIndex", currentIndex)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val packets = mutableListOf(
            BleNotifyQueue.Packet(
                "lyricWindowStart",
                start,
                FULL_LYRICS_JSON_NOTIFICATION_DELAY_MS
            )
        )
        window.forEachIndexed { offset, line ->
            val index = first + offset
            val chunk = buildFittingLyricWindowChunk(
                trackId,
                transferId,
                index,
                line.timeMs,
                line.durationMs,
                line.text,
                maximumPayload
            ) ?: return sendLyricWindowUnavailable(device, trackId, "payload exceeds MTU")
            packets += BleNotifyQueue.Packet(
                "lyricWindowChunk",
                chunk,
                FULL_LYRICS_JSON_NOTIFICATION_DELAY_MS
            )
        }
        val end = JSONObject()
            .put("type", "lyricWindowEnd")
            .put("trackId", trackId)
            .put("transferId", transferId)
            .put("generation", gate.generation)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (start.size > maximumPayload || end.size > maximumPayload) {
            sendLyricWindowUnavailable(device, trackId, "metadata exceeds MTU")
            return
        }
        packets += BleNotifyQueue.Packet(
            "lyricWindowEnd",
            end,
            FULL_LYRICS_JSON_NOTIFICATION_DELAY_MS
        )
        RealtimeTrace.record(
            stage = "lyricWindowEnqueued",
            trackId = trackId,
            generation = gate.generation,
            transferId = transferId,
            payloadType = "lyricWindow",
            chunkCount = packets.size,
            result = "queued"
        )
        RealtimeTrace.record(
            stage = "lyricWindowSendStart",
            trackId = trackId,
            generation = gate.generation,
            transferId = transferId,
            payloadType = "lyricWindow",
            result = "started"
        )
        notifyQueue.enqueueLongJob(
            type = LYRIC_WINDOW_JOB_TYPE,
            device = device,
            packets = packets,
            priority = BleNotifyQueue.Priority.P1_INTERACTIVE,
            shouldCancel = { !isLyricsTransferCurrent(trackId, gate.generation) },
            onComplete = {
                RealtimeTrace.record(
                    stage = "lyricWindowSendEnd",
                    trackId = trackId,
                    generation = gate.generation,
                    transferId = transferId,
                    payloadType = "lyricWindow",
                    result = "success"
                )
            }
        )
        logger("[LyricWindow] send trackId=$trackId first=$first count=${window.size}")
    }

    private fun buildFittingLyricWindowChunk(
        trackId: String,
        transferId: String,
        index: Int,
        timeMs: Long,
        durationMs: Long,
        text: String,
        maximumPayload: Int
    ): ByteArray? {
        var fittedText = text.take(MAX_COMPRESSED_LYRIC_TEXT_LENGTH)
        while (true) {
            val bytes = JSONObject()
                .put("type", "lyricWindowChunk")
                .put("trackId", trackId)
                .put("transferId", transferId)
                .put("index", index)
                .put("timeMs", timeMs)
                .put("durationMs", durationMs)
                .put("text", fittedText)
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (bytes.size <= maximumPayload) return bytes
            if (fittedText.isEmpty()) return null
            fittedText = fittedText.dropLast(1)
        }
    }

    private fun sendLyricWindowUnavailable(
        device: BluetoothDevice,
        trackId: String,
        reason: String
    ) {
        sendShortJsonIfFits(
            device,
            "lyricWindowUnavailable",
            JSONObject()
                .put("type", "lyricWindowUnavailable")
                .put("trackId", trackId)
                .put("reason", reason.take(80))
        )
    }

    private fun retryTransfer(device: BluetoothDevice, request: JSONObject) {
        val capabilities = connectionCommandCoordinator.capabilities(device.address)
        if (!capabilities.negotiated || !capabilities.transferRetry) return
        if (!subscribedDevices.containsKey(device.address)) return
        val transferId = request.optString("transferId")
        albumArtTransferCoordinator.retained(device.address, transferId)?.let { albumTransfer ->
            retryAlbumArtTransfer(device, request, albumTransfer)
            return
        }
        val transfer = lyricsTransferCoordinator.retained(device.address, transferId)
        if (transfer == null ||
            transfer.trackId != request.optString("trackId") ||
            transfer.expiresAtMs < SystemClock.elapsedRealtime() ||
            !isLyricsTransferCurrent(transfer.trackId, transfer.generation)
        ) {
            sendFullLyricsBinaryError(device, transferId, "transfer expired")
            return
        }
        val retryAll = request.optBoolean("retryAll", false)
        val missingArray = request.optJSONArray("missing")
        val missing = buildList {
            if (missingArray != null) {
                for (index in 0 until missingArray.length()) {
                    add(missingArray.optInt(index, -1))
                }
            }
        }.filter { it in transfer.chunks.indices }.distinct()
        val selectedIndexes = BleTransferCodec.retryChunkIndexes(
            totalChunks = transfer.chunks.size,
            missing = missing,
            retryAll = retryAll,
            maximumPartial = MAX_PARTIAL_RETRY_CHUNKS
        )
        val fullRetry = selectedIndexes.size == transfer.chunks.size
        val packets = buildList {
            if (fullRetry) {
                add(transfer.start)
            }
            selectedIndexes.forEach { add(transfer.chunks[it]) }
            add(transfer.end)
        }
        notifyQueue.enqueueLongJob(
            type = FULL_LYRICS_JOB_TYPE,
            device = device,
            packets = packets,
            priority = BleNotifyQueue.Priority.P2_BULK,
            maxSendDurationMs = FULL_LYRICS_MAX_SEND_MS,
            shouldCancel = {
                !isLyricsTransferCurrent(transfer.trackId, transfer.generation)
            }
        )
        logger(
            "[FullLyricsV2] retry transferId=$transferId " +
                "mode=${if (fullRetry) "full" else "partial"} chunks=${packets.size - 1}"
        )
    }

    private fun rememberAlbumArtBinaryTransfer(
        device: BluetoothDevice,
        protocolId: String,
        quality: AlbumArtQuality,
        packets: List<BleNotifyQueue.Packet>
    ) {
        val start = packets.firstOrNull { it.type == "albumArtBinaryStart" } ?: return
        val end = packets.lastOrNull { it.type == "albumArtBinaryEnd" } ?: return
        val transferId = runCatching {
            JSONObject(start.value.toString(Charsets.UTF_8)).optString("transferId")
        }.getOrDefault("")
        if (transferId.isBlank()) return
        albumArtTransferCoordinator.retain(AlbumArtBinaryTransfer(
            trackId = protocolId,
            quality = quality,
            transferId = transferId,
            start = start,
            chunks = packets.filter { it.type == "albumArtBinaryChunk" },
            end = end,
            expiresAtMs = SystemClock.elapsedRealtime() + ALBUM_ART_RETRY_TTL_MS,
            ownerAddress = device.address
        ))
    }

    private fun retryAlbumArtTransfer(
        device: BluetoothDevice,
        request: JSONObject,
        transfer: AlbumArtBinaryTransfer
    ) {
        if (transfer.expiresAtMs < SystemClock.elapsedRealtime() ||
            request.optString("trackId") != transfer.trackId ||
            currentAlbumArtId != transfer.trackId
        ) {
            sendAlbumArtBinaryError(device, transfer.transferId, "album transfer expired")
            return
        }
        val missingArray = request.optJSONArray("missing")
        val missing = buildList {
            if (missingArray != null) {
                for (index in 0 until missingArray.length()) {
                    add(missingArray.optInt(index, -1))
                }
            }
        }
        val selected = BleTransferCodec.retryChunkIndexes(
            totalChunks = transfer.chunks.size,
            missing = missing,
            retryAll = request.optBoolean("retryAll", false),
            maximumPartial = MAX_PARTIAL_RETRY_CHUNKS
        )
        val fullRetry = selected.size == transfer.chunks.size
        val packets = buildList {
            if (fullRetry) add(transfer.start)
            selected.forEach { add(transfer.chunks[it]) }
            add(transfer.end)
        }
        notifyQueue.enqueueLongJob(
            type = ALBUM_ART_JOB_TYPE,
            device = device,
            packets = packets,
            priority = if (transfer.quality == AlbumArtQuality.PREVIEW) {
                BleNotifyQueue.Priority.P1_INTERACTIVE
            } else {
                BleNotifyQueue.Priority.P3_BACKGROUND
            },
            maxSendDurationMs = if (transfer.quality == AlbumArtQuality.PREVIEW) {
                ALBUM_ART_PREVIEW_MAX_SEND_MS
            } else {
                ALBUM_ART_HQ_MAX_SEND_MS
            },
            shouldCancel = { currentAlbumArtId != transfer.trackId }
        )
        logger(
            "[AlbumArtBinary] retry transferId=${transfer.transferId} " +
                "mode=${if (fullRetry) "full" else "partial"} chunks=${selected.size}"
        )
    }

    private fun sendFullLyricsBinaryError(
        device: BluetoothDevice,
        transferId: String,
        reason: String
    ) {
        sendShortJsonIfFits(
            device,
            "fullLyricsBinaryError",
            JSONObject()
                .put("type", "fullLyricsBinaryError")
                .put("transferId", transferId)
                .put("reason", reason)
        )
    }

    private fun sendAlbumArtBinaryError(
        device: BluetoothDevice,
        transferId: String,
        reason: String
    ) {
        sendShortJsonIfFits(
            device,
            "albumArtBinaryError",
            JSONObject()
                .put("type", "albumArtBinaryError")
                .put("transferId", transferId)
                .put("message", reason)
        )
    }

    private fun lyricTrace(
        stage: String,
        trackId: String,
        songKey: String = "",
        generation: Long? = null,
        reason: String? = null,
        costMs: Long? = null,
        extra: Map<String, String> = emptyMap()
    ) {
        LyricTraceLogger.stage(
            runId = "unknown",
            trackId = trackId,
            songKey = songKey,
            generation = generation,
            stage = stage,
            reason = reason,
            costMs = costMs,
            extra = extra,
            sink = logger
        )
    }

    private fun sendLyricSecondary(device: BluetoothDevice, request: JSONObject) {
        if (!subscribedDevices.containsKey(device.address)) {
            logger("[LyricSecondary] send skipped: controller unsubscribed")
            return
        }
        val mode = request.optString("mode")
        if (mode != LYRIC_SECONDARY_MODE_TRANSLATION &&
            mode != LYRIC_SECONDARY_MODE_ROMANIZATION
        ) {
            sendLyricSecondaryUnavailable(
                device = device,
                trackId = request.optString("trackId"),
                mode = mode,
                reason = "invalid mode"
            )
            return
        }
        val source = playbackStateReader.readPlaybackState()
        val trackId = buildAlbumArtProtocolId(source)
        val requestedTrackId = request.optString("trackId")
        if (requestedTrackId.isNotBlank() && requestedTrackId != trackId) {
            sendLyricSecondaryUnavailable(
                device = device,
                trackId = requestedTrackId,
                mode = mode,
                reason = "stale track"
            )
            return
        }
        val lines = playbackStateReader.runtimeLyricLinesSnapshot()
            .filter { it.text.isNotBlank() }
            .take(MAX_FULL_LYRICS_LINES)
        if (lines.isEmpty()) {
            sendLyricSecondaryUnavailable(
                device = device,
                trackId = trackId,
                mode = mode,
                reason = "lyrics loading"
            )
            return
        }

        var skippedPlaceholderCount = 0
        val items: List<Pair<Int, String>> = lines.mapIndexedNotNull { index, line ->
            val rawText = when (mode) {
                LYRIC_SECONDARY_MODE_TRANSLATION -> line.translation
                LYRIC_SECONDARY_MODE_ROMANIZATION -> line.romanization
                else -> null
            }
            val text = sanitizeSecondaryLyricText(rawText)
            if (!rawText.isNullOrBlank() && text == null) {
                skippedPlaceholderCount += 1
                if (skippedPlaceholderCount == 1 || LogConfig.DEBUG_VERBOSE_LOG) {
                    logger("[LyricSecondary] skip placeholder line=$index mode=$mode")
                }
            }
            text?.let { index to it }
        }
        if (skippedPlaceholderCount > 1 && !LogConfig.DEBUG_VERBOSE_LOG) {
            logger("[LyricSecondary] skip placeholder count=$skippedPlaceholderCount mode=$mode")
        }
        if (items.isEmpty()) {
            sendLyricSecondaryUnavailable(
                device = device,
                trackId = trackId,
                mode = mode,
                reason = "not available"
            )
            return
        }

        val maximumPayload = maximumPayloadFor(device)
        val transferId = UUID.randomUUID().toString().take(8)
        val packets = mutableListOf<BleNotifyQueue.Packet>()
        val start = JSONObject()
            .put("type", "lyricSecondaryStart")
            .put("trackId", trackId)
            .put("transferId", transferId)
            .put("mode", mode)
            .put("lineCount", lines.size)
            .put("itemCount", items.size)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (start.size > maximumPayload) {
            sendLyricSecondaryUnavailable(
                device = device,
                trackId = trackId,
                mode = mode,
                reason = "start exceeds MTU"
            )
            return
        }
        packets += BleNotifyQueue.Packet(
            type = "lyricSecondaryStart",
            value = start,
            delayAfterMs = FULL_LYRICS_NOTIFICATION_DELAY_MS
        )

        var partTotal = 0
        items.forEach { (lineIndex, text) ->
            val parts = splitLyricSecondaryText(
                trackId = trackId,
                transferId = transferId,
                mode = mode,
                lineIndex = lineIndex,
                text = text,
                maximumPayload = maximumPayload
            )
            if (parts.isEmpty()) {
                logger("[LyricSecondary] line=$lineIndex skipped reason=part exceeds MTU")
                return@forEach
            }
            logger(
                "[LyricSecondary] line=$lineIndex chars=${text.length} " +
                    "bytes=${text.toByteArray(Charsets.UTF_8).size} parts=${parts.size}"
            )
            parts.forEachIndexed { partIndex, partText ->
                val part = buildLyricSecondaryPartJson(
                    trackId = trackId,
                    transferId = transferId,
                    mode = mode,
                    lineIndex = lineIndex,
                    partIndex = partIndex,
                    partCount = parts.size,
                    text = partText
                ).toByteArray(Charsets.UTF_8)
                if (part.size <= maximumPayload) {
                    packets += BleNotifyQueue.Packet(
                        type = "lyricSecondaryPart",
                        value = part,
                        delayAfterMs = FULL_LYRICS_NOTIFICATION_DELAY_MS
                    )
                    partTotal += 1
                } else {
                    logger(
                        "[LyricSecondary] part line=$lineIndex " +
                            "index=$partIndex/${parts.size} exceeds MTU bytes=${part.size}"
                    )
                }
            }
        }

        val end = JSONObject()
            .put("type", "lyricSecondaryEnd")
            .put("trackId", trackId)
            .put("transferId", transferId)
            .put("mode", mode)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (end.size > maximumPayload || packets.size <= 1) {
            sendLyricSecondaryUnavailable(
                device = device,
                trackId = trackId,
                mode = mode,
                reason = "payload unavailable"
            )
            return
        }
        packets += BleNotifyQueue.Packet(
            type = "lyricSecondaryEnd",
            value = end,
            delayAfterMs = FULL_LYRICS_NOTIFICATION_DELAY_MS
        )

        val startedAtMs = SystemClock.elapsedRealtime()
        logger(
            "[LyricSecondary] mtu=${maximumPayload + ATT_HEADER_SIZE} " +
                "maxPayload=$maximumPayload"
        )
        logger(
            "[LyricSecondary] send start trackId=$trackId mode=$mode " +
                "items=${items.size} parts=$partTotal"
        )
        notifyQueue.enqueueLongJob(
            type = LYRIC_SECONDARY_JOB_TYPE,
            device = device,
            packets = packets,
            shouldCancel = {
                val currentTrackId = playbackStateReader.readFastPlaybackSnapshot()
                    ?.let { snapshot ->
                        buildAlbumArtProtocolId(
                            title = snapshot.title,
                            artist = snapshot.artist,
                            album = snapshot.album
                        )
                    }
                currentTrackId != trackId
            },
            onComplete = {
                logger(
                    "[LyricSecondary] send end mode=$mode parts=$partTotal " +
                        "costMs=${SystemClock.elapsedRealtime() - startedAtMs}"
                )
            },
            onFailure = {
                logger("[LyricSecondary] cancelled reason=track changed")
            }
        )
    }

    private fun sendLyricSecondaryUnavailable(
        device: BluetoothDevice,
        trackId: String,
        mode: String,
        reason: String
    ) {
        val value = JSONObject()
            .put("type", "lyricSecondaryUnavailable")
            .put("trackId", trackId)
            .put("mode", mode)
            .put("reason", reason)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (value.size <= maximumPayloadFor(device)) {
            notifyQueue.enqueueShort(
                device = device,
                type = "lyricSecondaryUnavailable",
                value = value,
                delayAfterMs = SHORT_MESSAGE_DELAY_MS
            )
        }
        logger("[LyricSecondary] unavailable mode=$mode reason=$reason")
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

    private fun isFullLyricsRecoveryNudgeReason(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized.contains("lyric recovery active") ||
            normalized.contains("waiting qqmusic lyric cache") ||
            normalized.contains("qrc cooldown retry pending") ||
            normalized.contains("lyrics retry pending") ||
            normalized.contains("no safe qrc candidate")
    }

    private fun lyricLoadReasonCode(reason: String): String {
        val value = reason.lowercase()
        return when {
            "ambiguous" in value || "no safe" in value -> "qrc_ambiguous"
            "parse" in value || "decrypt" in value -> "qrc_parse_failed"
            "loading" in value || "waiting" in value || "cooldown" in value -> "qrc_pending"
            "transfer" in value -> "transfer_failed"
            else -> "qrc_not_found"
        }
    }

    private fun lyricsReadyGateAllowsFullLyrics(
        gate: LyricsReadyGateSnapshot,
        requestedTrackId: String,
        protocolTrackId: String,
        runtimeGeneration: Long,
        lineCount: Int
    ): Boolean {
        if (!gate.lyricsReady || lineCount <= 0) {
            return false
        }
        if (!isSameProtocolTrackId(gate.trackId, protocolTrackId) &&
            (requestedTrackId.isBlank() || !isSameProtocolTrackId(gate.trackId, requestedTrackId))
        ) {
            logger(
                "[LyricsState] ready gate blocked reason=track_mismatch " +
                    "gateTrackId=${gate.trackId} requested=$requestedTrackId protocol=$protocolTrackId"
            )
            return false
        }
        if (runtimeGeneration > 0L && gate.generation > 0L && runtimeGeneration != gate.generation) {
            logger(
                "[LyricsState] ready gate blocked reason=generation_mismatch " +
                    "gateGeneration=${gate.generation} runtimeGeneration=$runtimeGeneration"
            )
            return false
        }
        return true
    }

    private fun rememberPendingFullLyricsRequest(
        device: BluetoothDevice,
        request: JSONObject,
        requestedTrackId: String,
        protocolTrackId: String,
        generation: Long,
        reason: String,
        readyGate: LyricsReadyGateSnapshot
    ) {
        if (request.optBoolean("_lyricsReadyFlush", false)) {
            return
        }
        pendingFullLyricsRequests[device.address] = PendingFullLyricsRequest(
            ownerAddress = device.address,
            request = JSONObject(request.toString())
                .put("_lyricsReadyPending", true),
            requestedTrackId = requestedTrackId,
            protocolTrackId = protocolTrackId,
            generation = generation,
            createdAtMs = SystemClock.elapsedRealtime()
        )
        logger(
            "[LyricsState] pending request queued trackId=$protocolTrackId " +
                "requested=$requestedTrackId generation=$generation " +
                "state=${readyGate.state} reason=$reason"
        )
        RealtimeTrace.record(
            stage = "pendingQueued",
            trackId = requestedTrackId.ifBlank { protocolTrackId },
            generation = generation,
            payloadType = "fullLyrics",
            result = "queued",
            reason = reason
        )
        lyricTrace(
            stage = "pendingQueued",
            trackId = requestedTrackId.ifBlank { protocolTrackId },
            songKey = readyGate.songKey,
            generation = generation,
            reason = reason,
            extra = mapOf(
                "protocolTrackId" to protocolTrackId,
                "state" to readyGate.state.name
            )
        )
    }

    private fun clearMatchingPendingFullLyrics(
        ownerAddress: String,
        requestedTrackId: String,
        protocolTrackId: String,
        generation: Long
    ) {
        val pending = pendingFullLyricsRequests[ownerAddress] ?: return
        val trackMatches = isSameProtocolTrackId(pending.protocolTrackId, protocolTrackId) ||
            (requestedTrackId.isNotBlank() &&
                isSameProtocolTrackId(pending.requestedTrackId, requestedTrackId))
        val generationMatches = pending.generation <= 0L ||
            generation <= 0L ||
            pending.generation == generation
        if (trackMatches && generationMatches) {
            pendingFullLyricsRequests.remove(ownerAddress, pending)
        }
    }

    private fun dispatchPendingFullLyricsRetry(
        device: BluetoothDevice,
        pendingToken: PendingFullLyricsRequest?,
        retryRequest: JSONObject,
        reason: String
    ) {
        if (pendingToken == null || pendingFullLyricsRequests[device.address] !== pendingToken) {
            logger("[FullLyrics] pending retry skipped reason=already_flushed source=$reason")
            return
        }
        lyricCommandExecutor.execute {
            if (!started ||
                pendingFullLyricsRequests[device.address] !== pendingToken ||
                !subscribedDevices.containsKey(device.address)
            ) {
                logger("[FullLyrics] pending retry skipped reason=stale source=$reason")
                return@execute
            }
            logger("[FullLyrics] pending retry source=$reason")
            sendFullLyrics(device, retryRequest)
        }
    }

    private fun handleLyricsReady(snapshot: LyricsReadyGateSnapshot) {
        RealtimeTrace.record(
            stage = "lyricReady",
            trackId = snapshot.trackId,
            generation = snapshot.generation,
            payloadType = "lyrics",
            result = if (snapshot.lyricsReady) "ready" else "not_ready",
            reason = snapshot.reason
        )
        schedulePlaybackUiRefresh("lyrics_ready")
        flushPendingLyricWindow(snapshot)
        if (!snapshot.lyricsReady) {
            return
        }
        val currentTrack = playbackStateReader.runtimeCacheSnapshot().track
        if (currentTrack != null &&
            currentTrack.isPlaying &&
            subscribedDevices.isNotEmpty() &&
            isSameProtocolTrackId(currentTrack.trackId, snapshot.trackId)
        ) {
            // The boundary task may have suspended while the new track had no
            // parsed lines. Resume immediately when QRC parsing populates the
            // runtime cache instead of waiting for a later playback poll.
            scheduleCurrentWordPush(0L)
        }
        pendingFullLyricsRequests.entries.toList().forEach { (ownerAddress, pending) ->
            val trackMatches = isSameProtocolTrackId(snapshot.trackId, pending.protocolTrackId) ||
                isSameProtocolTrackId(snapshot.trackId, pending.requestedTrackId)
            val generationMatches = pending.generation <= 0L ||
                snapshot.generation <= 0L ||
                pending.generation == snapshot.generation
            if (!trackMatches || !generationMatches) {
                pendingFullLyricsRequests.remove(ownerAddress, pending)
                logger(
                    "[LyricsState] pending request dropped reason=stale " +
                        "device=$ownerAddress readyTrackId=${snapshot.trackId} " +
                        "pendingTrackId=${pending.protocolTrackId}"
                )
                return@forEach
            }
            val device = subscribedDevices[ownerAddress] ?: run {
                pendingFullLyricsRequests.remove(ownerAddress, pending)
                return@forEach
            }
            pendingFullLyricsRequests.remove(ownerAddress, pending)
            lyricCommandExecutor.execute {
                if (!started || !subscribedDevices.containsKey(ownerAddress)) {
                    return@execute
                }
                logger(
                    "[LyricsState] pending request flushed trackId=${pending.protocolTrackId} " +
                        "device=$ownerAddress lines=${snapshot.lineCount} " +
                        "waitMs=${SystemClock.elapsedRealtime() - pending.createdAtMs}"
                )
                RealtimeTrace.record(
                    stage = "pendingFlush",
                    trackId = pending.requestedTrackId.ifBlank { pending.protocolTrackId },
                    generation = snapshot.generation,
                    payloadType = "fullLyrics",
                    processingMs = (SystemClock.elapsedRealtime() - pending.createdAtMs)
                        .coerceAtLeast(0L),
                    result = "flushed"
                )
                lyricTrace(
                    stage = "pendingFlush",
                    trackId = pending.requestedTrackId.ifBlank { pending.protocolTrackId },
                    songKey = snapshot.songKey,
                    generation = snapshot.generation,
                    extra = mapOf(
                        "protocolTrackId" to pending.protocolTrackId,
                        "waitMs" to (SystemClock.elapsedRealtime() - pending.createdAtMs).toString()
                    )
                )
                sendFullLyrics(
                    device,
                    JSONObject(pending.request.toString()).put("_lyricsReadyFlush", true)
                )
            }
        }
    }

    private fun flushPendingLyricWindow(snapshot: LyricsReadyGateSnapshot) {
        pendingLyricWindowRequests.entries.toList().forEach { (ownerAddress, pending) ->
            val trackMatches = isSameProtocolTrackId(snapshot.trackId, pending.protocolTrackId) ||
                isSameProtocolTrackId(snapshot.trackId, pending.requestedTrackId)
            val generationMatches = pending.generation <= 0L ||
                snapshot.generation <= 0L ||
                pending.generation == snapshot.generation
            if (!trackMatches || !generationMatches) {
                pendingLyricWindowRequests.remove(ownerAddress, pending)
                logger(
                    "[LyricWindow] pending dropped reason=stale " +
                        "device=$ownerAddress readyTrackId=${snapshot.trackId} " +
                        "pendingTrackId=${pending.protocolTrackId}"
                )
                return@forEach
            }
            val device = subscribedDevices[ownerAddress] ?: run {
                pendingLyricWindowRequests.remove(ownerAddress, pending)
                return@forEach
            }
            if (!snapshot.lyricsReady) {
                if (snapshot.state.name == "FAILED") {
                    pendingLyricWindowRequests.remove(ownerAddress, pending)
                    sendLyricWindowUnavailable(
                        device,
                        pending.protocolTrackId,
                        snapshot.reason
                    )
                }
                return@forEach
            }
            pendingLyricWindowRequests.remove(ownerAddress, pending)
            lyricCommandExecutor.execute {
                if (!started || !subscribedDevices.containsKey(ownerAddress)) return@execute
                logger(
                    "[LyricWindow] pending flushed trackId=${pending.protocolTrackId} " +
                        "device=$ownerAddress lines=${snapshot.lineCount} " +
                        "waitMs=${SystemClock.elapsedRealtime() - pending.createdAtMs}"
                )
                RealtimeTrace.record(
                    stage = "lyricWindowPendingFlush",
                    trackId = pending.requestedTrackId.ifBlank { pending.protocolTrackId },
                    generation = snapshot.generation,
                    payloadType = "lyricWindow",
                    processingMs = (SystemClock.elapsedRealtime() - pending.createdAtMs)
                        .coerceAtLeast(0L),
                    result = "flushed"
                )
                sendLyricWindow(device, JSONObject(pending.request.toString()))
            }
        }
    }

    private fun isSameProtocolTrackId(left: String, right: String): Boolean {
        val cleanLeft = left.trim()
        val cleanRight = right.trim()
        if (cleanLeft.isBlank() || cleanRight.isBlank()) {
            return false
        }
        if (cleanLeft == cleanRight) {
            return true
        }
        val normalizedLeft = normalizeCurrentWordTrackId(cleanLeft)
        val normalizedRight = normalizeCurrentWordTrackId(cleanRight)
        return normalizedLeft.isNotBlank() && normalizedLeft == normalizedRight
    }

    private fun playbackSourceForRequestedTrack(requestedTrackId: String): JSONObject {
        val track = playbackStateReader.runtimeCacheSnapshot().track
        if (track != null &&
            (requestedTrackId.isBlank() || isSameProtocolTrackId(track.trackId, requestedTrackId))
        ) {
            return JSONObject()
                .put("title", track.title)
                .put("artist", track.artist)
                .put("album", track.album)
                .put("position", track.positionMs)
                .put("duration", track.durationMs)
                .put("playing", track.isPlaying)
        }
        return playbackStateReader.readPlaybackState()
    }

    private fun isLyricsTransferCurrent(trackId: String, generation: Long): Boolean {
        val track = playbackStateReader.runtimeCacheSnapshot().track ?: return false
        return BleTransferCodec.isCurrentTransfer(
            transferTrackId = trackId,
            transferGeneration = generation,
            currentTrackId = track.trackId,
            currentGeneration = track.currentTrackGeneration,
            trackIdsMatch = ::isSameProtocolTrackId
        )
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
                translation = null,
                romanization = null,
                words = words
            ).toByteArray(Charsets.UTF_8)
            if (withWords.size <= maximumPayload) {
                if (LogConfig.DEBUG_VERBOSE_LOG) {
                    verboseLogger(
                        "[FullLyrics] chunk index=$index " +
                            "words=${words.size} " +
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
                translation = null,
                romanization = null,
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
        translation: String?,
        romanization: String?,
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
                translation
                    ?.takeIf(String::isNotBlank)
                    ?.let { objectValue.put("translation", it) }
                romanization
                    ?.takeIf(String::isNotBlank)
                    ?.let { objectValue.put("romanization", it) }
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

    private fun sanitizeSecondaryLyricText(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        if (trimmed.all { it == '/' }) return null
        if (SECONDARY_LYRIC_PLACEHOLDERS.contains(trimmed.lowercase())) return null
        return trimmed
    }

    private fun splitLyricSecondaryText(
        trackId: String,
        transferId: String,
        mode: String,
        lineIndex: Int,
        text: String,
        maximumPayload: Int
    ): List<String> {
        // String.codePoints() is only available from API 24. Build Unicode scalar
        // strings explicitly so secondary lyrics remain surrogate-safe on minSdk 23.
        val codePoints = buildList {
            var offset = 0
            while (offset < text.length) {
                val codePoint = Character.codePointAt(text, offset)
                add(String(Character.toChars(codePoint)))
                offset += Character.charCount(codePoint)
            }
        }
        val parts = mutableListOf<String>()
        var current = ""
        codePoints.forEach { unit ->
            val candidate = current + unit
            val candidatePayload = buildLyricSecondaryPartJson(
                trackId = trackId,
                transferId = transferId,
                mode = mode,
                lineIndex = lineIndex,
                partIndex = parts.size,
                partCount = LYRIC_SECONDARY_PART_COUNT_PLACEHOLDER,
                text = candidate
            ).toByteArray(Charsets.UTF_8)
            if (candidatePayload.size <= maximumPayload) {
                current = candidate
            } else {
                if (current.isBlank()) {
                    return emptyList()
                }
                parts += current
                current = unit
                val singlePayload = buildLyricSecondaryPartJson(
                    trackId = trackId,
                    transferId = transferId,
                    mode = mode,
                    lineIndex = lineIndex,
                    partIndex = parts.size,
                    partCount = LYRIC_SECONDARY_PART_COUNT_PLACEHOLDER,
                    text = current
                ).toByteArray(Charsets.UTF_8)
                if (singlePayload.size > maximumPayload) {
                    return emptyList()
                }
            }
        }
        if (current.isNotBlank()) {
            parts += current
        }
        return parts
    }

    private fun buildLyricSecondaryPartJson(
        trackId: String,
        transferId: String,
        mode: String,
        lineIndex: Int,
        partIndex: Int,
        partCount: Int,
        text: String
    ): String {
        return JSONObject()
            .put("type", "lyricSecondaryPart")
            .put("trackId", trackId)
            .put("transferId", transferId)
            .put("mode", mode)
            .put("lineIndex", lineIndex)
            .put("partIndex", partIndex)
            .put("partCount", partCount)
            .put("text", text)
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
        private const val AUTO_PUSH_INTERVAL_MS = 1000L
        private const val AUTO_PUSH_PAUSED_INTERVAL_MS = 5000L
        private const val CURRENT_WORD_INITIAL_DELAY_MS = 100L
        private const val CURRENT_WORD_TRACK_SWITCH_DELAY_MS = 450L
        private const val CURRENT_WORD_DRIFT_CORRECTION_MS = 500L
        private const val SERVER_PROTOCOL_VERSION = 2
        private val MULTI_CONTROLLER_DEDUP_COMMANDS = setOf(
            "PLAY_PAUSE",
            "NEXT",
            "PREVIOUS"
        )
        private const val CLIENT_CAPABILITY_WAIT_MS = 250L
        private const val RECONNECT_SYNC_COOLDOWN_MS = 1_000L
        private const val RECONNECT_SYNC_CURRENT_WORD_DELAY_MS = 350L
        private const val PLAYBACK_DIFF_SKIP_LOG_INTERVAL_MS = 10_000L
        private const val ALBUM_ART_ENABLED = true
        private const val DEBUG_ART_DIAGNOSTICS = false
        private const val ALBUM_ART_PREVIEW_MAX_CHUNKS = 12
        private const val ALBUM_ART_HQ_MAX_CHUNKS = 48
        private const val ALBUM_ART_FULL_MAX_CHUNKS = 90
        private const val ALBUM_ART_PREVIEW_MAX_SEND_MS = 1200L
        private const val ALBUM_ART_HQ_MAX_SEND_MS = 8000L
        private const val ALBUM_ART_FULL_MAX_SEND_MS = 3500L
        private const val ALBUM_ART_RETRY_TTL_MS = 10_000L
        private val ALBUM_ART_SOURCE_RETRY_DELAYS_MS = longArrayOf(250L, 800L, 2_000L)
        private const val ALBUM_ART_CACHE_CAPACITY = 20
        private const val ALBUM_ART_CACHE_MAX_BYTES = 24L * 1024L * 1024L
        private const val ALBUM_ART_CACHE_TTL_MS = 30 * 60 * 1_000L
        private const val ENCODED_ART_CACHE_CAPACITY = 40
        private const val ENCODED_ART_CACHE_MAX_BYTES = 16 * 1024 * 1024
        private const val MAX_TRACK_INFO_TEXT_LENGTH = 300
        private const val MAX_TRACK_INFO_CHUNK_BYTES = 300
        private const val MAX_ALBUM_CHUNK_RAW_BYTES = 60
        private const val MAX_ALBUM_JSON_BYTES = 180
        private const val ALBUM_ART_BINARY_MAGIC = 0xA1
        private const val ALBUM_ART_BINARY_HEADER_BYTES = 6
        private const val ALBUM_ART_ID_HASH_BYTES = 12
        private const val PREVIEW_MAX_JPEG_BYTES = 1800
        private const val HQ_MAX_JPEG_BYTES = 8000
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

        internal fun autoPushPollIntervalMs(isPlaying: Boolean?): Long {
            return if (isPlaying == false) {
                AUTO_PUSH_PAUSED_INTERVAL_MS
            } else {
                AUTO_PUSH_INTERVAL_MS
            }
        }
        private const val FULL_LYRICS_NOTIFICATION_DELAY_MS = 20L
        private const val FULL_LYRICS_JSON_NOTIFICATION_DELAY_MS = 5L
        private const val FULL_LYRICS_BINARY_NOTIFICATION_DELAY_MS = 2L
        private const val FULL_LYRICS_MAX_SEND_MS = 10_000L
        private const val FULL_LYRICS_ZLIB_FORMAT = "zlib-json-v1"
        private const val FULL_LYRICS_BINARY_MAGIC = 0xA2
        private const val FULL_LYRICS_BINARY_VERSION = 1
        private const val MAX_FULL_LYRICS_ZLIB_BYTES = 24 * 1024
        private const val MAX_COMPRESSED_LYRIC_TEXT_LENGTH = 500
        private const val FULL_LYRICS_RETRY_TTL_MS = 10_000L
        private const val MAX_PARTIAL_RETRY_CHUNKS = 32
        private const val LYRIC_WINDOW_LINE_COUNT = 5
        private const val MAX_FULL_LYRICS_LINES = 120
        private const val MAX_FULL_LYRICS_TEXT_LENGTH = 80
        private const val ALBUM_ART_JOB_TYPE = "albumArt"
        private const val TRACK_INFO_JOB_TYPE = "trackInfo"
        private const val FULL_LYRICS_JOB_TYPE = "fullLyrics"
        private const val LYRIC_WINDOW_JOB_TYPE = "lyricWindow"
        private const val FULL_LYRICS_PENDING_RETRY_DELAY_MS = 900L
        private const val FULL_LYRICS_RECOVERY_RETRY_DELAY_MS = 1_200L
        private const val LYRIC_SECONDARY_JOB_TYPE = "lyricSecondary"
        private const val MAX_LYRIC_TEXT_LENGTH = 30
        private const val REMOTE_LOG_JOB_TYPE = "remoteLog"
        private const val MEDIA_FIELD_DUMP_JOB_TYPE = "mediaFieldDump"
        private const val PLAY_HISTORY_JOB_TYPE = "playHistory"
        private const val PLAY_STATS_JOB_TYPE = "playStats"
        private const val LYRIC_SECONDARY_MODE_TRANSLATION = "translation"
        private const val LYRIC_SECONDARY_MODE_ROMANIZATION = "romanization"
        private const val LYRIC_SECONDARY_PART_COUNT_PLACEHOLDER = 999
        private val SECONDARY_LYRIC_PLACEHOLDERS = setOf(
            "--",
            "---",
            "null",
            "nil",
            "none",
            "暂无",
            "暂无翻译",
            "暂无罗马音"
        )
        private const val DEFAULT_HISTORY_PAGE_LIMIT = 10
        private const val MAX_HISTORY_PAGE_LIMIT = 20
        private const val MAX_HISTORY_CHUNK_RAW_BYTES = 300
        private const val HISTORY_NOTIFICATION_DELAY_MS = 20L
        private const val HISTORY_MAX_SEND_MS = 8_000L
        private const val MAX_HISTORY_ERROR_CHARS = 100
        private const val CALLBACK_LOG_DEDUP_WINDOW_MS = 500L
        private const val GATT_REDISCOVERY_GRACE_MS = 5_000L
        private const val NOTIFY_FAILURE_SUSPECT_THRESHOLD = 3
        private const val SUSPECT_NO_SUCCESS_HEARTBEAT_MS = 30_000L
        private const val HEALTH_SUCCESS_LOG_INTERVAL_MS = 10_000L
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

    private data class PendingAlbumArtRequest(
        val device: BluetoothDevice,
        val protocolId: String,
        val quality: AlbumArtQuality,
        val requestKey: String
    )

    private data class PendingFullLyricsRequest(
        val ownerAddress: String,
        val request: JSONObject,
        val requestedTrackId: String,
        val protocolTrackId: String,
        val generation: Long,
        val createdAtMs: Long
    )

    private data class PendingLyricWindowRequest(
        val ownerAddress: String,
        val request: JSONObject,
        val requestedTrackId: String,
        val protocolTrackId: String,
        val generation: Long,
        val createdAtMs: Long
    )

    private data class AlbumArtCacheEntry(
        val protocolId: String,
        val cacheKey: String,
        val bitmap: Bitmap,
        val source: String,
        val width: Int,
        val height: Int,
        val byteSize: Int,
        val createdAtElapsedMs: Long
    )

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
        val packets: AlbumArtPackets,
        val fallback: Boolean = false
    )

    private data class AlbumArtPreparation(
        val prepared: PreparedAlbumArt? = null,
        val compressionFailed: Boolean = false,
        val bestBytes: Int = 0,
        val bestChunks: Int = 0,
        val minCandidateScale: Int = 0
    )
}
