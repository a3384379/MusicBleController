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
import android.os.SystemClock
import android.util.Base64
import com.example.playeragent.media.AlbumArtTestManager
import com.example.playeragent.logging.LogConfig
import com.example.playeragent.logging.LogBuffer
import com.example.playeragent.media.MediaCommandExecutor
import com.example.playeragent.media.PlaybackStateReader
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class BleGattServerManager(
    context: Context,
    private val bluetoothManager: BluetoothManager,
    private val logger: (String) -> Unit,
    private val transientLogger: (String) -> Unit = logger,
    private val verboseLogger: (String) -> Unit = logger
) {

    private val appContext = context.applicationContext
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
    private var lastSentAlbumArtId: String? = null
    @Volatile
    private var sendingAlbumArtId: String? = null
    private var lastAutoPushSongKey: String? = null
    private var lastAutoPushPlaying: Boolean? = null
    @Volatile
    private var started = false

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logger("[BLE-A] service added success")
            } else {
                logger("[BLE-A] service added failed: status=$status")
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
                mtuByAddress[address] = DEFAULT_MTU
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedDevices.remove(address)
                mtuByAddress.remove(address)
                notifyQueue.removeDevice(address)
                lastAlbumArtKey = null
                lastSentAlbumArtId = null
                sendingAlbumArtId = null
                lastAutoPushSongKey = null
                lastAutoPushPlaying = null
                stopAutoPushIfUnused()
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

            val valueText = value?.toString(Charsets.UTF_8).orEmpty()
            logger("[BLE-A] command write received: $valueText")

            val request = try {
                JSONObject(valueText)
            } catch (exception: Exception) {
                logger("[BLE-A] command parse failed: ${exception.message}")
                if (responseNeeded) {
                    sendWriteResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        value
                    )
                }
                return
            }

            val command = request.optString("cmd")
            logger("[BLE-A] command received: $command")
            handleCommand(command, request)

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
                    lastSentAlbumArtId = null
                    sendingAlbumArtId = null
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
        if (!started && gattServer == null) {
            return
        }
        stopAutoPush()
        notifyQueue.clear()
        lastAlbumArtKey = null
        lastSentAlbumArtId = null
        sendingAlbumArtId = null
        lastAutoPushSongKey = null
        lastAutoPushPlaying = null
        subscribedDevices.clear()
        mtuByAddress.clear()
        recentConnectionCallbacks.clear()
        recentMtuCallbacks.clear()
        statusCharacteristic = null

        try {
            gattServer?.close()
            logger("[BLE-A] GATT server closed")
        } catch (securityException: SecurityException) {
            logger("[BLE-A] GATT server close failed: missing permission")
        } catch (exception: Exception) {
            logger("[BLE-A] GATT server close failed: ${exception.message}")
        } finally {
            gattServer = null
            started = false
        }
    }

    fun isStarted(): Boolean = started && gattServer != null

    private fun shouldLogCallback(
        timestamps: ConcurrentHashMap<String, Long>,
        key: String
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previous = timestamps.put(key, now)
        return previous == null ||
            now - previous >= CALLBACK_LOG_DEDUP_WINDOW_MS
    }

    private fun handleCommand(command: String, request: JSONObject) {
        when (command) {
            "PLAY_PAUSE",
            "NEXT",
            "PREVIOUS",
            "VOLUME_UP",
            "VOLUME_DOWN" -> mediaCommandExecutor.execute(command)

            "SEEK_TO" -> {
                val position = request.optLong("position").coerceAtLeast(0L)
                logger("[BLE-A][Seek] position=$position")
                mediaCommandExecutor.seekTo(position)
                logger("[BLE-A][Seek] seekTo called")
                sendPlaybackState()
            }
            "SET_VOLUME" -> {
                val requestedVolume = request.optInt("volume")
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
            else -> logger("[BLE-A] unknown command: $command")
        }
    }

    private fun sendPlaybackState(includeAlbumArt: Boolean = false) {
        val state = createCompactPlaybackState()
        sendStatusMessage(state.toString())
        if (includeAlbumArt) {
            sendAlbumArtIfSongChanged(state)
        }
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

        notifyQueue.enqueueShort(
            device = device,
            type = readMessageType(message),
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

        if (notifyQueue.hasLongJobActiveOrQueued()) {
            return
        }

        try {
            val state = createCompactPlaybackState()
            logAutoPushStateChanges(state)
            if (LogConfig.DEBUG_VERBOSE_LOG) {
                verboseLogger(
                    "[BLE-A][AutoPush] playbackState " +
                        "position=${state.optLong("position")} " +
                        "duration=${state.optLong("duration")}"
                )
            }
            val result = sendStatusMessage(state.toString()).also {
                sendAlbumArtIfSongChanged(state)
            }
            if (LogConfig.DEBUG_VERBOSE_LOG) {
                verboseLogger("[BLE-A][AutoPush] notify result=$result")
            }
        } catch (exception: Exception) {
            logger("[BLE-A][AutoPush] error=${exception.message}")
        }
    }

    private fun logAutoPushStateChanges(state: JSONObject) {
        val songKey = buildAlbumArtCacheKey(state)
        val title = state.optString("title")
        val playing = state.optBoolean("playing")

        if (songKey != lastAutoPushSongKey) {
            lastAutoPushSongKey = songKey
            logger("[BLE-A][AutoPush] song changed title=$title")
        }
        if (playing != lastAutoPushPlaying) {
            lastAutoPushPlaying = playing
            logger("[BLE-A][AutoPush] play state changed playing=$playing")
        }
    }

    private fun createCompactPlaybackState(): JSONObject {
        val source = playbackStateReader.readPlaybackState()
        return JSONObject()
            .put("type", "playbackState")
            .put("playing", source.optBoolean("playing"))
            .put("title", source.optString("title").take(MAX_STATE_TEXT_LENGTH))
            .put("artist", source.optString("artist").take(MAX_STATE_TEXT_LENGTH))
            .put("album", source.optString("album").take(MAX_STATE_TEXT_LENGTH))
            .put("position", source.optLong("position"))
            .put("duration", source.optLong("duration"))
            .put("lyric", source.optString("lyric").take(MAX_STATE_TEXT_LENGTH))
    }

    @Synchronized
    private fun sendAlbumArtIfSongChanged(playbackState: JSONObject) {
        val cacheKey = buildAlbumArtCacheKey(playbackState)
        val protocolId = buildAlbumArtProtocolId(playbackState)
        if (protocolId == sendingAlbumArtId) {
            logger("[AlbumArt][BLE] skip duplicate sending id=$protocolId")
            return
        }
        if (cacheKey == lastAlbumArtKey) {
            return
        }
        if (protocolId == lastSentAlbumArtId) {
            logger("[AlbumArt][BLE] skip already sent id=$protocolId")
            lastAlbumArtKey = cacheKey
            return
        }

        if (notifyQueue.hasJobTypeActiveOrQueued(ALBUM_ART_JOB_TYPE)) {
            logger("[AlbumArt][BLE] transfer already queued")
            return
        }

        val device = subscribedDevices.values.firstOrNull() ?: run {
            logger("[AlbumArt][BLE] no iPhone subscriber")
            return
        }
        sendingAlbumArtId = protocolId
        val albumArt = albumArtTestManager.readCurrentNotificationAlbumArt()
        if (albumArt == null) {
            sendingAlbumArtId = null
            sendAlbumArtUnavailable(device, protocolId)
            lastAlbumArtKey = cacheKey
            return
        }

        val bitmap = albumArt.bitmap
        logger("[AlbumArt][BLE] source=${albumArt.source}")
        logger(
            "[AlbumArt][BLE] original width=${bitmap.width} height=${bitmap.height}"
        )
        val maximumPayload = minOf(
            maximumPayloadFor(device),
            MAX_ALBUM_JSON_BYTES
        )
        val preparation = prepareAlbumArt(
            bitmap = bitmap,
            deviceMaximumPayload = maximumPayload,
            protocolId = protocolId
        )
        val preparedAlbumArt = preparation.prepared
        if (preparedAlbumArt == null) {
            sendingAlbumArtId = null
            if (preparation.compressionFailed) {
                logger("[AlbumArt][BLE] unavailable")
                sendAlbumArtUnavailable(device, protocolId)
                lastAlbumArtKey = cacheKey
            }
            return
        }

        val compressedAlbumArt = preparedAlbumArt.compressed
        val albumPackets = preparedAlbumArt.packets
        val totalChunks = albumPackets.totalChunks
        logger(
            "[AlbumArt][BLE] selected scale=${compressedAlbumArt.width} " +
                "quality=${compressedAlbumArt.quality} " +
                "bytes=${compressedAlbumArt.bytes.size} chunks=$totalChunks"
        )

        lastAlbumArtKey = cacheKey
        logger(
            "[AlbumArt][BLE] send start id=$protocolId chunks=$totalChunks"
        )
        notifyQueue.enqueueLongJob(
            type = ALBUM_ART_JOB_TYPE,
            device = device,
            packets = albumPackets.packets,
            onComplete = {
                markAlbumArtSent(
                    protocolId = protocolId,
                    cacheKey = cacheKey
                )
            },
            onFailure = {
                clearSendingAlbumArt(protocolId)
            }
        )
    }

    @Synchronized
    private fun markAlbumArtSent(
        protocolId: String,
        cacheKey: String
    ) {
        if (sendingAlbumArtId == protocolId) {
            lastSentAlbumArtId = protocolId
            lastAlbumArtKey = cacheKey
            sendingAlbumArtId = null
        }
    }

    @Synchronized
    private fun clearSendingAlbumArt(protocolId: String) {
        if (sendingAlbumArtId == protocolId) {
            sendingAlbumArtId = null
        }
    }

    private fun prepareAlbumArt(
        bitmap: Bitmap,
        deviceMaximumPayload: Int,
        protocolId: String
    ): AlbumArtPreparation {
        val attempts = listOf(
            CompressionAttempt(width = 160, height = 160, quality = 55),
            CompressionAttempt(width = 144, height = 144, quality = 50),
            CompressionAttempt(width = 128, height = 128, quality = 45),
            CompressionAttempt(width = 96, height = 96, quality = 35)
        )

        var index = 0
        while (index < attempts.size) {
            val attempt = attempts[index]
            if (index > 0) {
                logger(
                    "[AlbumArt][BLE] fallback scale=${attempt.width} " +
                        "quality=${attempt.quality}"
                )
            }

            val compressed = compressAlbumArt(bitmap, attempt)
                ?: return AlbumArtPreparation(compressionFailed = true)
            val packets = buildAlbumArtPackets(
                deviceMaximumPayload = deviceMaximumPayload,
                protocolId = protocolId,
                bytes = compressed.bytes
            )
            if (packets == null) {
                logger(
                    "[AlbumArt][BLE] build failed scale=${attempt.width}: " +
                        "MTU too small"
                )
                return AlbumArtPreparation()
            }

            val withinByteLimit =
                compressed.bytes.size <= MAX_ALBUM_JPEG_BYTES
            val withinChunkLimit =
                packets.totalChunks <= MAX_ALBUM_CHUNKS
            if (withinByteLimit && withinChunkLimit) {
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
                    "chunks=${packets.totalChunks} exceeds limit"
            )
            if (index == attempts.lastIndex) {
                logger(
                    "[AlbumArt][BLE] build failed: no quality profile fits " +
                        "$MAX_ALBUM_JPEG_BYTES bytes/$MAX_ALBUM_CHUNKS chunks"
                )
            }

            index = if (index == 0 && !withinChunkLimit) {
                2
            } else {
                index + 1
            }
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
            width = attempt.width,
            height = attempt.height,
            quality = attempt.quality
        )
    }

    private fun buildAlbumArtPackets(
        deviceMaximumPayload: Int,
        protocolId: String,
        bytes: ByteArray
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
            val packets = mutableListOf<BleNotifyQueue.Packet>()
            val start = JSONObject()
                .put("type", "albumArtStart")
                .put("id", protocolId)
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
        protocolId: String
    ) {
        val value = JSONObject()
            .put("type", "albumArtUnavailable")
            .put("id", protocolId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (value.size > maximumPayloadFor(device)) {
            logger("[AlbumArt][BLE] unavailable message exceeds MTU")
            return
        }
        logger("[AlbumArt][BLE] unavailable")
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
        val parts = listOf(
            playbackState.optString("title"),
            playbackState.optString("artist"),
            playbackState.optString("album")
        ).map {
            it.replace(Regex("[\\r\\n|]"), " ")
                .trim()
                .take(MAX_ALBUM_ID_PART_LENGTH)
        }
        return parts.joinToString("_").ifBlank { "unknown" }
    }

    private fun maximumPayloadFor(device: BluetoothDevice): Int {
        val mtu = mtuByAddress[device.address] ?: DEFAULT_MTU
        return (mtu - ATT_HEADER_SIZE).coerceAtLeast(0)
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
    ) {
        val server = gattServer
        if (server == null || device == null) {
            logger("[BLE-A] write response skipped: server or device unavailable")
            return
        }

        try {
            val sent = server.sendResponse(device, requestId, status, offset, value)
            logger("[BLE-A] write response sent=$sent status=$status")
        } catch (securityException: SecurityException) {
            logger("[BLE-A] write response failed: missing permission")
        } catch (exception: Exception) {
            logger("[BLE-A] write response failed: ${exception.message}")
        }
    }

    companion object {
        private const val DEFAULT_MTU = 23
        private const val ATT_HEADER_SIZE = 3
        private const val AUTO_PUSH_INTERVAL_MS = 1000L
        private const val MAX_STATE_TEXT_LENGTH = 30
        private const val MAX_ALBUM_JPEG_BYTES = 6000
        private const val MAX_ALBUM_CHUNKS = 120
        private const val MAX_ALBUM_CHUNK_RAW_BYTES = 48
        private const val MAX_ALBUM_JSON_BYTES = 180
        private const val MAX_ALBUM_ID_PART_LENGTH = 3
        private const val SHORT_MESSAGE_DELAY_MS = 20L
        private const val ALBUM_ART_NOTIFICATION_DELAY_MS = 35L
        private const val LOG_NOTIFICATION_DELAY_MS = 20L
        private const val MAX_LOG_CHUNK_RAW_BYTES = 300
        private const val MAX_LOG_JSON_BYTES = 480
        private const val DEFAULT_LOG_LIMIT = 30
        private const val MAX_LOG_LIMIT = 50
        private const val ALBUM_ART_JOB_TYPE = "albumArt"
        private const val REMOTE_LOG_JOB_TYPE = "remoteLog"
        private const val CALLBACK_LOG_DEDUP_WINDOW_MS = 500L
    }

    private data class CompressionAttempt(
        val width: Int,
        val height: Int,
        val quality: Int
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
