package com.example.playeragent.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.example.playeragent.logging.LogConfig
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BleNotifyQueue(
    private val serverProvider: () -> BluetoothGattServer?,
    private val characteristicProvider: () -> BluetoothGattCharacteristic?,
    private val logger: (String) -> Unit,
    private val localOnlyLogger: (String) -> Unit,
    private val verboseLogger: (String) -> Unit,
    private val onNotifySuccess: (type: String) -> Unit = {},
    private val onNotifyFailure: (type: String, status: Int, reason: String) -> Unit = { _, _, _ -> }
) {

    private val handlerThread = HandlerThread("BleNotifyQueue").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val jobs = ArrayDeque<SendJob>()
    private val linkProfiles = LinkedHashMap<String, BleLinkProfile>()
    private var activeJob: SendJob? = null
    private var activePacketIndex = 0
    private var activeJobStartedAtMs = 0L
    private var notificationInFlight = false
    private var interleavedPacketInFlight = false
    private var interleavedPacketType: String? = null
    private var interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
    private val latestInterleavedPackets = LinkedHashMap<String, InterleavedPacket>()
    private var lastInterleavedSavedLogAtMs = 0L
    private var lastInterleavedFlushedLogAtMs = 0L
    private var activeRequestId = 0L
    private var activeRequestType: String? = null
    private var notifyTimeoutRunnable: Runnable? = null
    private var drainingCancelledCallback = false
    private var cancelledCallbackDrainRunnable: Runnable? = null
    private var commandResponseQuietUntilMs = 0L
    private var activeNotifyStartedAtMs = 0L
    private var activeNotifyDeviceAddress: String? = null
    private var activeNotifyPacketType: String? = null

    fun resetLinkProfile(address: String, mtu: Int) {
        runOnQueueThread { resetLinkProfileOnQueue(address, mtu) }
    }

    @Synchronized
    private fun resetLinkProfileOnQueue(address: String, mtu: Int) {
        if (address.isBlank()) return
        linkProfiles[address] = BleLinkProfile(mtu)
        localOnlyLogger("[BleLink] reset device=$address mtu=$mtu")
    }

    fun updateLinkMtu(address: String, mtu: Int) {
        runOnQueueThread { updateLinkMtuOnQueue(address, mtu) }
    }

    @Synchronized
    private fun updateLinkMtuOnQueue(address: String, mtu: Int) {
        if (address.isBlank() || mtu <= 0) return
        linkProfiles.getOrPut(address) { BleLinkProfile(mtu) }.updateMtu(mtu)
        localOnlyLogger("[BleLink] mtu device=$address mtu=$mtu pacing reset")
    }

    fun enqueueShort(
        device: BluetoothDevice,
        type: String,
        value: ByteArray,
        delayAfterMs: Long = SHORT_MESSAGE_DELAY_MS
    ) {
        enqueueJob(
            SendJob(
                type = type,
                device = device,
                packets = listOf(
                    Packet(
                        type = type,
                        value = value,
                        delayAfterMs = delayAfterMs
                    )
                ),
                isLongJob = false
            )
        )
    }

    /**
     * Gives the ATT write response a short radio window before the next notification packet.
     * Older Sony Bluetooth stacks can otherwise acknowledge sendResponse() locally while the
     * iOS client never receives its write callback during a dense album-art transfer.
     */
    fun onCommandResponseSent() {
        runOnQueueThread { onCommandResponseSentOnQueue() }
    }

    @Synchronized
    private fun onCommandResponseSentOnQueue() {
        commandResponseQuietUntilMs = maxOf(
            commandResponseQuietUntilMs,
            SystemClock.elapsedRealtime() + COMMAND_RESPONSE_QUIET_MS
        )
        if (!notificationInFlight && !drainingCancelledCallback) {
            handler.postDelayed({ sendNextPacket() }, COMMAND_RESPONSE_QUIET_MS)
        }
    }

    fun enqueueLongJob(
        type: String,
        device: BluetoothDevice,
        packets: List<Packet>,
        priority: Priority = priorityFor(type),
        maxSendDurationMs: Long? = null,
        shouldCancel: (() -> Boolean)? = null,
        onComplete: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        if (packets.isEmpty()) {
            return
        }
        enqueueJob(
            SendJob(
                type = type,
                device = device,
                packets = packets,
                isLongJob = true,
                priority = priority,
                maxSendDurationMs = maxSendDurationMs,
                shouldCancel = shouldCancel,
                onComplete = onComplete,
                onFailure = onFailure
            )
        )
    }

    fun setLatestInterleavedShort(
        device: BluetoothDevice,
        type: String,
        value: ByteArray,
        delayAfterMs: Long = SHORT_MESSAGE_DELAY_MS
    ) {
        runOnQueueThread {
            setLatestInterleavedShortOnQueue(device, type, value, delayAfterMs)
        }
    }

    @Synchronized
    private fun setLatestInterleavedShortOnQueue(
        device: BluetoothDevice,
        type: String,
        value: ByteArray,
        delayAfterMs: Long
    ) {
        latestInterleavedPackets[type] = InterleavedPacket(
            device = device,
            packet = Packet(
                type = type,
                value = value,
                delayAfterMs = delayAfterMs
            )
        )
        logInterleavedEventThrottled(
            isSavedEvent = true,
            message = "[BleNotifyQueue] long job active, $type saved as latest"
        )
    }

    @Synchronized
    fun hasLongJobActiveOrQueued(): Boolean {
        return activeJob?.isLongJob == true || jobs.any { it.isLongJob }
    }

    @Synchronized
    fun hasJobTypeActiveOrQueued(type: String): Boolean {
        return activeJob?.type == type || jobs.any { it.type == type }
    }

    fun cancelJobTypes(types: Set<String>, reason: String) {
        runOnQueueThread { cancelJobTypesOnQueue(types, reason) }
    }

    @Synchronized
    private fun cancelJobTypesOnQueue(types: Set<String>, reason: String) {
        val removedJobs = jobs.filter { it.type in types }
        jobs.removeAll { it.type in types }
        removedJobs.forEach { failJob(it, reason) }
        val current = activeJob
        if (current != null && current.type in types) {
            failJob(current, reason)
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            interleavedPacketInFlight = false
            interleavedPacketType = null
            interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
        }
        if (removedJobs.isNotEmpty() || current?.type in types) {
            handler.removeCallbacksAndMessages(null)
            if (notificationInFlight) {
                beginCancelledCallbackDrain("cancelled active notify")
            } else {
                sendNextPacket()
            }
        }
    }

    fun onNotificationSent(deviceAddress: String?, status: Int) {
        runOnQueueThread { onNotificationSentOnQueue(deviceAddress, status) }
    }

    @Synchronized
    private fun onNotificationSentOnQueue(deviceAddress: String?, status: Int) {
        if (drainingCancelledCallback) {
            logger("[BleNotifyQueue] ignored callback status=$status while draining cancelled notify")
            finishCancelledCallbackDrain()
            return
        }
        val job = activeJob ?: return
        if (!notificationInFlight) {
            return
        }

        cancelNotifyTimeout()
        notificationInFlight = false
        val callbackAddress = deviceAddress
            ?.takeIf { it.isNotBlank() }
            ?: activeNotifyDeviceAddress
            ?: job.device.address
        val callbackType = activeNotifyPacketType
        val callbackRttMs = if (activeNotifyStartedAtMs > 0L) {
            SystemClock.elapsedRealtime() - activeNotifyStartedAtMs
        } else {
            0L
        }
        clearActiveNotifyMetrics()
        if (interleavedPacketInFlight) {
            val type = interleavedPacketType ?: callbackType ?: job.type
            interleavedPacketInFlight = false
            interleavedPacketType = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onNotifySuccess(type)
                recordAdaptiveSuccess(type, callbackAddress, callbackRttMs)
            } else {
                recordAdaptiveFailure(type, callbackAddress)
                logger("[BleNotifyQueue] interleaved notify failed type=$type status=$status")
                onNotifyFailure(type, status, "callback_failed")
            }
            handler.postDelayed({ sendNextPacket() }, interleavedDelayAfterMs)
            interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
            return
        }
        val packet = job.packets.getOrNull(activePacketIndex)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            val type = packet?.type ?: callbackType ?: job.type
            recordAdaptiveFailure(type, callbackAddress)
            logger(
                "[BleNotifyQueue] notify failed " +
                    "type=$type status=$status"
            )
            onNotifyFailure(type, status, "callback_failed")
            abortActiveJob(job, "notify callback failed type=$type status=$status")
            return
        } else {
            onNotifySuccess(packet?.type ?: job.type)
            packet?.let {
                recordAdaptiveSuccess(it.type, callbackAddress, callbackRttMs)
            }
        }
        activePacketIndex += 1
        job.packetsSinceYield += 1
        val delay = packet?.let {
            adaptiveDelayFor(it, callbackAddress)
        } ?: SHORT_MESSAGE_DELAY_MS
        handler.postDelayed({ sendNextPacket() }, delay)
    }

    fun removeDevice(address: String) {
        runOnQueueThread { removeDeviceOnQueue(address) }
    }

    @Synchronized
    private fun removeDeviceOnQueue(address: String) {
        val removedJobs = jobs.filter { it.device.address == address }
        jobs.removeAll { it.device.address == address }
        latestInterleavedPackets.entries.removeAll { it.value.device.address == address }
        linkProfiles.remove(address)
        val activeDeviceRemoved = activeJob?.device?.address == address
        if (interleavedPacketInFlight) {
            interleavedPacketInFlight = false
            interleavedPacketType = null
            interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
        }
        removedJobs.forEach { failJob(it, "device disconnected") }
        if (activeDeviceRemoved) {
            activeJob?.let { failJob(it, "device disconnected") }
            activeJob = null
            activePacketIndex = 0
        }
        handler.removeCallbacksAndMessages(null)
        if (notificationInFlight && activeDeviceRemoved) {
            beginCancelledCallbackDrain("device disconnected")
        } else {
            sendNextPacket()
        }
    }

    fun clearAllForDisconnect(reason: String) {
        runOnQueueThread { clearAllForDisconnectOnQueue(reason) }
    }

    @Synchronized
    private fun clearAllForDisconnectOnQueue(reason: String) {
        logger("[BleNotifyQueue] clear all reason=$reason")
        handler.removeCallbacksAndMessages(null)
        activeJob?.let { failJob(it, reason) }
        jobs.forEach { failJob(it, reason) }
        jobs.clear()
        latestInterleavedPackets.clear()
        linkProfiles.clear()
        interleavedPacketInFlight = false
        interleavedPacketType = null
        interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
        activeJob = null
        activePacketIndex = 0
        activeJobStartedAtMs = 0L
        notificationInFlight = false
        commandResponseQuietUntilMs = 0L
        activeRequestType = null
        activeRequestId += 1
        clearActiveNotifyMetrics()
        cancelNotifyTimeout()
        drainingCancelledCallback = false
        cancelledCallbackDrainRunnable = null
    }

    fun clear() {
        runOnQueueThread { clearOnQueue() }
    }

    @Synchronized
    private fun clearOnQueue() {
        handler.removeCallbacksAndMessages(null)
        activeJob?.let { failJob(it, "queue cleared") }
        jobs.forEach { failJob(it, "queue cleared") }
        jobs.clear()
        latestInterleavedPackets.clear()
        linkProfiles.clear()
        interleavedPacketInFlight = false
        interleavedPacketType = null
        interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
        activeJob = null
        activePacketIndex = 0
        notificationInFlight = false
        commandResponseQuietUntilMs = 0L
        activeRequestType = null
        activeRequestId += 1
        clearActiveNotifyMetrics()
        cancelNotifyTimeout()
        drainingCancelledCallback = false
        cancelledCallbackDrainRunnable = null
    }

    @Synchronized
    fun snapshot(): BleNotifyQueueSnapshot {
        return BleNotifyQueueSnapshot(
            notificationInFlight = notificationInFlight,
            pendingJobCount = jobs.size,
            activeJobType = activeJob?.type,
            activeDeviceAddress = activeJob?.device?.address,
            pendingShortMessageCount = latestInterleavedPackets.size
        )
    }

    private fun enqueueJob(job: SendJob) {
        runOnQueueThread { enqueueJobOnQueue(job) }
    }

    @Synchronized
    private fun enqueueJobOnQueue(job: SendJob) {
        if (!job.isLongJob && shouldCoalesceShortType(job.type)) {
            jobs.removeAll {
                !it.isLongJob &&
                    it.type == job.type &&
                    it.device.address == job.device.address
            }
        }
        jobs.addLast(job)
        sendNextPacket()
    }

    @Synchronized
    private fun sendNextPacket() {
        if (notificationInFlight || drainingCancelledCallback) {
            return
        }
        val quietDelayMs = remainingQuietDelayMs(
            commandResponseQuietUntilMs,
            SystemClock.elapsedRealtime()
        )
        if (quietDelayMs > 0L) {
            handler.postDelayed({ sendNextPacket() }, quietDelayMs)
            return
        }

        if (activeJob == null) {
            activeJob = pollNextJob() ?: return
            activePacketIndex = activeJob?.nextPacketIndex ?: 0
            activeJobStartedAtMs = activeJob?.startedAtMs
                ?.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime()
            activeJob?.startedAtMs = activeJobStartedAtMs
            activeJob?.takeIf { it.isLongJob }?.let {
                if (LogConfig.DEBUG_VERBOSE_LOG) {
                    verboseLogger(
                        "[BleNotifyQueue] job start " +
                            "type=${it.type} chunks=${it.chunkCount}"
                    )
                }
            }
        }

        var job = activeJob ?: return
        if (shouldYieldToPendingJob(job)) {
            job.nextPacketIndex = activePacketIndex
            job.packetsSinceYield = 0
            jobs.addFirst(job)
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            activeJob = pollNextJob() ?: return
            job = activeJob ?: return
            activePacketIndex = job.nextPacketIndex
            activeJobStartedAtMs = job.startedAtMs
                .takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime()
            job.startedAtMs = activeJobStartedAtMs
        }
        if (job.shouldCancel?.invoke() == true) {
            markJobFailed(job, "cancelled")
            job.onFailure?.invoke()
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            sendNextPacket()
            return
        }
        val maxSendDurationMs = job.maxSendDurationMs
        if (maxSendDurationMs != null &&
            activePacketIndex < job.packets.size &&
            SystemClock.elapsedRealtime() - activeJobStartedAtMs >
            maxSendDurationMs
        ) {
            if (job.type == "albumArt") {
                logger("[AlbumArt] timeout stop id=${job.albumArtId}")
            }
            markJobFailed(job, "timeout")
            job.onFailure?.invoke()
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            sendNextPacket()
            return
        }
        val interleaveInterval = interleaveIntervalFor(job.type)
        if (interleaveInterval > 0 &&
            activePacketIndex > 0 &&
            activePacketIndex % interleaveInterval == 0
        ) {
            val interleaved = pollInterleavedPacket(job.device.address)
            if (interleaved != null &&
                interleaved.device.address == job.device.address
            ) {
                val server = serverProvider()
                val characteristic = characteristicProvider()
                if (server != null && characteristic != null) {
                    interleavedDelayAfterMs = interleaved.packet.delayAfterMs
                    interleavedPacketInFlight = true
                    interleavedPacketType = interleaved.packet.type
                    notificationInFlight = true
                    markNotifyStarted(
                        interleaved.device.address,
                        interleaved.packet.type
                    )
                    logInterleavedEventThrottled(
                        isSavedEvent = false,
                        message = "[BleNotifyQueue] latest ${interleaved.packet.type} flushed during long job"
                    )
                    val requested = notify(
                        server,
                        characteristic,
                        interleaved.device,
                        interleaved.packet.value
                    )
                    if (!requested) {
                        onNotifyFailure(
                            interleaved.packet.type,
                            NOTIFY_REQUEST_REJECTED_STATUS,
                            "request_rejected"
                        )
                        interleavedPacketInFlight = false
                        interleavedPacketType = null
                        notificationInFlight = false
                        clearActiveNotifyMetrics()
                        interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
                        handler.postDelayed(
                            { sendNextPacket() },
                            interleaved.packet.delayAfterMs
                        )
                    } else {
                        startNotifyTimeout(interleaved.packet.type)
                    }
                    return
                } else {
                    latestInterleavedPackets[interleaved.packet.type] = interleaved
                }
            }
        }

        if (activePacketIndex >= job.packets.size) {
            if (job.isLongJob) {
                if (job.failed) {
                    job.onFailure?.invoke()
                } else {
                    when (job.type) {
                        "albumArt" -> logger(
                            "[AlbumArt] send end id=${job.albumArtId} " +
                                "quality=${job.albumArtQuality}"
                        )
                        "remoteLog" -> logger("[RemoteLog] send end")
                        "mediaFieldDump" ->
                            logger("[MediaFieldDump] send end")
                        "fullLyrics" ->
                            logger("[FullLyrics] send end")
                        "trackInfo" ->
                            logger("[TrackInfo] send end")
                        else -> logger("[BleNotifyQueue] job end type=${job.type}")
                    }
                    job.onComplete?.invoke()
                }
            }
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            sendNextPacket()
            return
        }

        val packet = job.packets[activePacketIndex]
        val server = serverProvider()
        val characteristic = characteristicProvider()
        if (server == null || characteristic == null) {
            logger("[BleNotifyQueue] send failed: GATT server unavailable")
            failJob(job, "GATT server unavailable")
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            sendNextPacket()
            return
        }

        notificationInFlight = true
        activeRequestType = packet.type
        markNotifyStarted(job.device.address, packet.type)
        val requested = notify(server, characteristic, job.device, packet.value)
        if (job.type == "albumArt") {
            logAlbumArtPacketProgress(job, packet)
        }
        val sendLog = "[BleNotifyQueue] send type=${packet.type} bytes=${packet.value.size}"
        when {
            packet.type == "mediaFieldDumpChunk" -> {
                // The dump contents are intentionally kept out of normal logs.
            }
            packet.type == "fullLyricsChunk" -> {
                if (LogConfig.DEBUG_VERBOSE_LOG) {
                    verboseLogger(sendLog)
                }
            }
            packet.type == "trackInfoChunk" -> {
                if (LogConfig.DEBUG_VERBOSE_LOG) {
                    verboseLogger(sendLog)
                }
            }
            packet.type == "logChunk" ||
                packet.type == "albumArtChunk" ||
                packet.type == "albumArtBinaryChunk" -> {
                if (activePacketIndex % CHUNK_PROGRESS_INTERVAL == 0) {
                    localOnlyLogger(
                        "$sendLog index=$activePacketIndex/${job.packets.size}"
                    )
                }
            }
            packet.type == "playbackState" ||
                packet.type == "volumeState" -> {
                if (LogConfig.DEBUG_VERBOSE_LOG) {
                    verboseLogger(sendLog)
                }
            }
            LogConfig.DEBUG_VERBOSE_LOG -> verboseLogger(sendLog)
        }

        if (!requested) {
            notificationInFlight = false
            activeRequestType = null
            clearActiveNotifyMetrics()
            recordAdaptiveFailure(packet.type, job.device.address)
            logger("[BleNotifyQueue] notify request rejected type=${packet.type}")
            onNotifyFailure(
                packet.type,
                NOTIFY_REQUEST_REJECTED_STATUS,
                "request_rejected"
            )
            abortActiveJob(job, "notify request rejected type=${packet.type}")
        } else {
            startNotifyTimeout(packet.type)
        }
    }

    private fun abortActiveJob(job: SendJob, reason: String) {
        markJobFailed(job, reason)
        job.onFailure?.invoke()
        activeJob = null
        activePacketIndex = 0
        activeJobStartedAtMs = 0L
        activeRequestType = null
        clearActiveNotifyMetrics()
        handler.postDelayed({ sendNextPacket() }, SHORT_MESSAGE_DELAY_MS)
    }

    private fun startNotifyTimeout(type: String) {
        cancelNotifyTimeout()
        val requestId = ++activeRequestId
        val timeout = Runnable {
            synchronized(this) {
                if (!notificationInFlight || requestId != activeRequestId) {
                    return@synchronized
                }
                val job = activeJob
                logger("[BleNotifyQueue] notify callback timeout type=$type job=${job?.type}")
                val address = activeNotifyDeviceAddress ?: job?.device?.address.orEmpty()
                recordAdaptiveFailure(type, address)
                onNotifyFailure(type, NOTIFY_CALLBACK_TIMEOUT_STATUS, "callback_timeout")
                if (job != null) {
                    markJobFailed(job, "notify callback timeout type=$type")
                    job.onFailure?.invoke()
                    activeJob = null
                    activePacketIndex = 0
                    activeJobStartedAtMs = 0L
                }
                notificationInFlight = false
                interleavedPacketInFlight = false
                interleavedPacketType = null
                activeRequestType = null
                clearActiveNotifyMetrics()
                beginCancelledCallbackDrain("notify callback timeout")
            }
        }
        notifyTimeoutRunnable = timeout
        handler.postDelayed(timeout, NOTIFY_CALLBACK_TIMEOUT_MS)
    }

    private fun cancelNotifyTimeout() {
        notifyTimeoutRunnable?.let(handler::removeCallbacks)
        notifyTimeoutRunnable = null
        activeRequestId += 1
    }

    private fun beginCancelledCallbackDrain(reason: String) {
        cancelNotifyTimeout()
        drainingCancelledCallback = true
        notificationInFlight = false
        activeRequestType = null
        clearActiveNotifyMetrics()
        cancelledCallbackDrainRunnable?.let(handler::removeCallbacks)
        val drain = Runnable {
            synchronized(this) {
                if (!drainingCancelledCallback) {
                    return@synchronized
                }
                logger("[BleNotifyQueue] cancelled notify drain elapsed reason=$reason")
                finishCancelledCallbackDrain()
            }
        }
        cancelledCallbackDrainRunnable = drain
        handler.postDelayed(drain, CANCELLED_CALLBACK_DRAIN_MS)
    }

    private fun finishCancelledCallbackDrain() {
        cancelledCallbackDrainRunnable?.let(handler::removeCallbacks)
        cancelledCallbackDrainRunnable = null
        drainingCancelledCallback = false
        handler.postDelayed({ sendNextPacket() }, SHORT_MESSAGE_DELAY_MS)
    }

    private fun pollInterleavedPacket(deviceAddress: String): InterleavedPacket? {
        val preferredTypes = listOf(
            "trackInfo",
            "playbackState",
            "volumeState",
            "currentWord"
        )
        val selectedKey = preferredTypes.firstOrNull { type ->
            latestInterleavedPackets[type]?.device?.address == deviceAddress
        } ?: latestInterleavedPackets.entries.firstOrNull {
            it.value.device.address == deviceAddress
        }?.key ?: return null
        return latestInterleavedPackets.remove(selectedKey)
    }

    private fun markJobFailed(job: SendJob, reason: String) {
        job.failed = true
        if (job.failureLogged) {
            return
        }
        job.failureLogged = true
        when (job.type) {
            "albumArt" -> {
                logger("[AlbumArt][BLE] failed reason=$reason")
                logger(
                    "[AlbumArt-Sony] send cancelled reason=$reason " +
                        "id=${job.albumArtId} quality=${job.albumArtQuality}"
                )
                logger("[AlbumArt-Sony] queue snapshot=${snapshot()}")
            }
            "remoteLog" ->
                logger("[RemoteLog] send failed reason=$reason")
            "mediaFieldDump" ->
                logger("[MediaFieldDump] send failed reason=$reason")
            "fullLyrics" ->
                logger("[FullLyrics] send failed reason=$reason")
            "trackInfo" ->
                logger("[TrackInfo] send failed reason=$reason")
            "playHistory", "playStats" ->
                logger("[HistoryBLE] cancelled reason=$reason")
        }
    }

    private fun logAlbumArtPacketProgress(job: SendJob, packet: Packet) {
        when (packet.type) {
            "albumArtBinaryStart", "albumArtStart" -> {
                logger(
                    "[AlbumArt-Sony] binary start id=${job.albumArtId} " +
                        "quality=${job.albumArtQuality} chunks=${job.chunkCount}"
                )
                logger("[AlbumArt-Sony] queue snapshot=${snapshot()}")
            }
            "albumArtBinaryChunk", "albumArtChunk" -> {
                val sent = job.packets
                    .take(activePacketIndex + 1)
                    .count { it.type == "albumArtBinaryChunk" || it.type == "albumArtChunk" }
                if (sent == 1 || sent % 20 == 0 || sent == job.chunkCount) {
                    logger(
                        "[AlbumArt-Sony] chunk progress id=${job.albumArtId} " +
                            "sent=$sent/${job.chunkCount}"
                    )
                }
            }
            "albumArtBinaryEnd", "albumArtEnd" -> {
                val costMs = if (activeJobStartedAtMs > 0L) {
                    SystemClock.elapsedRealtime() - activeJobStartedAtMs
                } else {
                    0L
                }
                logger(
                    "[AlbumArt-Sony] binary end id=${job.albumArtId} " +
                        "quality=${job.albumArtQuality} costMs=$costMs"
                )
            }
        }
    }

    private fun failJob(job: SendJob, reason: String) {
        markJobFailed(job, reason)
        job.onFailure?.invoke()
    }

    private fun pollNextJob(): SendJob? {
        val priority = jobs.minOfOrNull { it.priority.rank } ?: return null
        val selected = jobs.firstOrNull { it.priority.rank == priority } ?: return null
        jobs.remove(selected)
        return selected
    }

    private fun shouldYieldToPendingJob(job: SendJob): Boolean {
        if (activePacketIndex <= 0 || jobs.isEmpty()) {
            return false
        }
        val waitingPriority = jobs.minOfOrNull { it.priority.rank } ?: return false
        if (waitingPriority >= job.priority.rank) {
            return false
        }
        return shouldYieldForPriorities(
            active = job.priority,
            waiting = Priority.entries.first { it.rank == waitingPriority },
            packetsSinceYield = job.packetsSinceYield
        )
    }

    private fun adaptiveDelayFor(packet: Packet, address: String): Long {
        val profile = linkProfiles.getOrPut(address) {
            BleLinkProfile(DEFAULT_LINK_MTU)
        }
        return profile.delayFor(payloadKind(packet.type), packet.delayAfterMs)
    }

    private fun recordAdaptiveSuccess(
        type: String,
        address: String,
        callbackRttMs: Long
    ) {
        if (address.isBlank()) return
        val profile = linkProfiles.getOrPut(address) {
            BleLinkProfile(DEFAULT_LINK_MTU)
        }
        val beforeJson = profile.jsonDelayMs
        val beforeBinary = profile.binaryDelayMs
        val beforeArtwork = profile.artworkDelayMs
        profile.recordSuccess(payloadKind(type), callbackRttMs)
        if (beforeJson != profile.jsonDelayMs ||
            beforeBinary != profile.binaryDelayMs ||
            beforeArtwork != profile.artworkDelayMs ||
            callbackRttMs > CONGESTED_CALLBACK_LOG_RTT_MS
        ) {
            localOnlyLogger(
                "[BleLink] success device=$address type=$type rttMs=$callbackRttMs " +
                    "ewmaMs=${profile.ewmaCallbackRttMs.toInt()} " +
                    "jsonDelayMs=${profile.jsonDelayMs} " +
                    "binaryDelayMs=${profile.binaryDelayMs} " +
                    "artworkDelayMs=${profile.artworkDelayMs}"
            )
        }
    }

    private fun recordAdaptiveFailure(type: String, address: String) {
        if (address.isBlank()) return
        val profile = linkProfiles.getOrPut(address) {
            BleLinkProfile(DEFAULT_LINK_MTU)
        }
        profile.recordFailure(payloadKind(type))
        logger(
            "[BleLink] backoff device=$address type=$type " +
                "jsonDelayMs=${profile.jsonDelayMs} " +
                "binaryDelayMs=${profile.binaryDelayMs} " +
                "artworkDelayMs=${profile.artworkDelayMs} " +
                "failures=${profile.failureCount}"
        )
    }

    private fun payloadKind(type: String): BleLinkProfile.PayloadKind {
        return when {
            type == "fullLyricsBinaryChunk" ->
                BleLinkProfile.PayloadKind.BINARY_LYRIC
            type == "albumArtBinaryChunk" ->
                BleLinkProfile.PayloadKind.BINARY_ARTWORK
            type in JSON_LYRIC_PACKET_TYPES ->
                BleLinkProfile.PayloadKind.JSON_LYRIC
            else -> BleLinkProfile.PayloadKind.OTHER
        }
    }

    private fun markNotifyStarted(address: String, type: String) {
        activeNotifyStartedAtMs = SystemClock.elapsedRealtime()
        activeNotifyDeviceAddress = address
        activeNotifyPacketType = type
    }

    private fun clearActiveNotifyMetrics() {
        activeNotifyStartedAtMs = 0L
        activeNotifyDeviceAddress = null
        activeNotifyPacketType = null
    }

    private fun interleaveIntervalFor(jobType: String): Int {
        return realtimeInterleaveIntervalFor(jobType)
    }

    private fun logInterleavedEventThrottled(
        isSavedEvent: Boolean,
        message: String
    ) {
        if (LogConfig.DEBUG_VERBOSE_LOG) {
            verboseLogger(message)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val last = if (isSavedEvent) {
            lastInterleavedSavedLogAtMs
        } else {
            lastInterleavedFlushedLogAtMs
        }
        if (now - last < INTERLEAVED_LOG_THROTTLE_MS) {
            return
        }
        if (isSavedEvent) {
            lastInterleavedSavedLogAtMs = now
        } else {
            lastInterleavedFlushedLogAtMs = now
        }
        localOnlyLogger(message)
    }

    @SuppressLint("MissingPermission")
    private fun notify(
        server: BluetoothGattServer,
        characteristic: BluetoothGattCharacteristic,
        device: BluetoothDevice,
        value: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false,
                    value
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (exception: Exception) {
            logger("[BleNotifyQueue] notify exception=${exception.message}")
            false
        }
    }

    fun shutdown(reason: String) {
        if (Looper.myLooper() == handlerThread.looper) {
            clearAllForDisconnectOnQueue(reason)
            handlerThread.quitSafely()
            return
        }
        val completed = CountDownLatch(1)
        handler.post {
            clearAllForDisconnectOnQueue(reason)
            completed.countDown()
            handlerThread.quitSafely()
        }
        completed.await(2, TimeUnit.SECONDS)
    }

    private fun runOnQueueThread(action: () -> Unit) {
        if (Looper.myLooper() == handlerThread.looper) {
            action()
        } else {
            handler.post(action)
        }
    }

    data class Packet(
        val type: String,
        val value: ByteArray,
        val delayAfterMs: Long
    )

    data class BleNotifyQueueSnapshot(
        val notificationInFlight: Boolean,
        val pendingJobCount: Int,
        val activeJobType: String?,
        val activeDeviceAddress: String?,
        val pendingShortMessageCount: Int
    )

    private data class InterleavedPacket(
        val device: BluetoothDevice,
        val packet: Packet
    )

    private data class SendJob(
        val type: String,
        val device: BluetoothDevice,
        val packets: List<Packet>,
        val isLongJob: Boolean,
        val priority: Priority = Priority.P0_REALTIME,
        val maxSendDurationMs: Long? = null,
        val shouldCancel: (() -> Boolean)? = null,
        val onComplete: (() -> Unit)? = null,
        val onFailure: (() -> Unit)? = null,
        var failed: Boolean = false,
        var failureLogged: Boolean = false,
        var nextPacketIndex: Int = 0,
        var startedAtMs: Long = 0L,
        var packetsSinceYield: Int = 0
    ) {
        val chunkCount: Int
            get() = packets.count {
                it.type == "albumArtChunk" || it.type == "logChunk"
                    || it.type == "albumArtBinaryChunk"
                    || it.type == "mediaFieldDumpChunk"
                    || it.type == "trackInfoChunk"
                    || it.type == "fullLyricsChunk"
                    || it.type == "fullLyricsBinaryChunk"
                    || it.type == "historyPayloadChunk"
            }

        val albumArtId: String
            get() = albumArtEndField("id")

        val albumArtQuality: String
            get() = albumArtEndField("quality")

        private fun albumArtEndField(name: String): String {
            val endPacket = packets.lastOrNull {
                it.type == "albumArtEnd" || it.type == "albumArtBinaryEnd"
            } ?: return ""
            return try {
                JSONObject(endPacket.value.toString(Charsets.UTF_8))
                    .optString(name)
            } catch (_: Exception) {
                ""
            }
        }
    }

    companion object {
        private const val DEFAULT_LINK_MTU = 23
        private const val SHORT_MESSAGE_DELAY_MS = 20L
        private const val CHUNK_PROGRESS_INTERVAL = 20
        private const val REALTIME_INTERLEAVE_INTERVAL = 1
        private const val INTERLEAVED_LOG_THROTTLE_MS = 10_000L
        private const val NOTIFY_REQUEST_REJECTED_STATUS = -1
        private const val NOTIFY_CALLBACK_TIMEOUT_STATUS = -2
        private const val NOTIFY_CALLBACK_TIMEOUT_MS = 2_000L
        private const val CANCELLED_CALLBACK_DRAIN_MS = 750L
        private const val COMMAND_RESPONSE_QUIET_MS = 25L
        private const val BULK_YIELD_INTERVAL = 4
        private const val BACKGROUND_YIELD_INTERVAL = 1
        private const val CONGESTED_CALLBACK_LOG_RTT_MS = 120L
        private val JSON_LYRIC_PACKET_TYPES = setOf(
            "fullLyricsStart",
            "fullLyricsChunk",
            "fullLyricsEnd",
            "fullLyricsBinaryStart",
            "fullLyricsBinaryEnd",
            "lyricWindowStart",
            "lyricWindowChunk",
            "lyricWindowEnd"
        )
        private val COALESCIBLE_SHORT_TYPES = setOf(
            "playbackState",
            "currentWord",
            "volumeState",
            "albumArtOffer"
        )

        fun priorityFor(type: String): Priority {
            return when (type) {
                "trackInfo", "playbackState", "currentWord", "pong",
                "volumeState", "controlResponse" -> Priority.P0_REALTIME
                "lyricWindow" -> Priority.P1_INTERACTIVE
                "fullLyrics", "lyricSecondary" -> Priority.P2_BULK
                "albumArt", "remoteLog", "mediaFieldDump", "qrcDump",
                "playHistory", "playStats" -> Priority.P3_BACKGROUND
                else -> Priority.P0_REALTIME
            }
        }

        internal fun shouldYieldForPriorities(
            active: Priority,
            waiting: Priority,
            packetsSinceYield: Int
        ): Boolean {
            if (waiting.rank >= active.rank) return false
            return when (active) {
                Priority.P0_REALTIME -> false
                Priority.P1_INTERACTIVE -> true
                Priority.P2_BULK -> {
                    waiting == Priority.P0_REALTIME ||
                        packetsSinceYield >= BULK_YIELD_INTERVAL
                }
                Priority.P3_BACKGROUND -> packetsSinceYield >= BACKGROUND_YIELD_INTERVAL
            }
        }

        internal fun remainingQuietDelayMs(quietUntilMs: Long, nowMs: Long): Long {
            return (quietUntilMs - nowMs).coerceAtLeast(0L)
        }

        internal fun realtimeInterleaveIntervalFor(jobType: String): Int {
            return when (jobType) {
                "albumArt", "fullLyrics", "lyricSecondary",
                "remoteLog", "mediaFieldDump", "qrcDump", "playHistory", "playStats" ->
                    REALTIME_INTERLEAVE_INTERVAL
                else -> 0
            }
        }

        internal fun shouldCoalesceShortType(type: String): Boolean {
            return type in COALESCIBLE_SHORT_TYPES
        }
    }

    enum class Priority(val rank: Int) {
        P0_REALTIME(0),
        P1_INTERACTIVE(1),
        P2_BULK(2),
        P3_BACKGROUND(3)
    }
}
