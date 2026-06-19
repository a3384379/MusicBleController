package com.example.playeragent.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.playeragent.logging.LogConfig
import org.json.JSONObject
import java.util.ArrayDeque

class BleNotifyQueue(
    private val serverProvider: () -> BluetoothGattServer?,
    private val characteristicProvider: () -> BluetoothGattCharacteristic?,
    private val logger: (String) -> Unit,
    private val localOnlyLogger: (String) -> Unit,
    private val verboseLogger: (String) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val jobs = ArrayDeque<SendJob>()
    private var activeJob: SendJob? = null
    private var activePacketIndex = 0
    private var activeJobStartedAtMs = 0L
    private var notificationInFlight = false
    private var interleavedPacketInFlight = false
    private var interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
    private var latestInterleavedPacket: InterleavedPacket? = null
    private var lastInterleavedSavedLogAtMs = 0L
    private var lastInterleavedFlushedLogAtMs = 0L

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

    fun enqueueLongJob(
        type: String,
        device: BluetoothDevice,
        packets: List<Packet>,
        maxSendDurationMs: Long? = null,
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
                maxSendDurationMs = maxSendDurationMs,
                onComplete = onComplete,
                onFailure = onFailure
            )
        )
    }

    @Synchronized
    fun setLatestInterleavedShort(
        device: BluetoothDevice,
        type: String,
        value: ByteArray,
        delayAfterMs: Long = SHORT_MESSAGE_DELAY_MS
    ) {
        val current = latestInterleavedPacket
        if (current?.packet?.type == "trackInfo" && type != "trackInfo") {
            return
        }
        latestInterleavedPacket = InterleavedPacket(
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

    @Synchronized
    fun onNotificationSent(status: Int) {
        val job = activeJob ?: return
        if (!notificationInFlight) {
            return
        }

        notificationInFlight = false
        if (interleavedPacketInFlight) {
            interleavedPacketInFlight = false
            handler.postDelayed({ sendNextPacket() }, interleavedDelayAfterMs)
            interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
            return
        }
        val packet = job.packets.getOrNull(activePacketIndex)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            logger(
                "[BleNotifyQueue] notify failed " +
                    "type=${packet?.type ?: job.type} status=$status"
            )
            markJobFailed(
                job,
                "notify callback failed type=${packet?.type ?: job.type} " +
                    "status=$status"
            )
        }
        activePacketIndex += 1
        val delay = packet?.delayAfterMs ?: SHORT_MESSAGE_DELAY_MS
        handler.postDelayed({ sendNextPacket() }, delay)
    }

    @Synchronized
    fun removeDevice(address: String) {
        val removedJobs = jobs.filter { it.device.address == address }
        jobs.removeAll { it.device.address == address }
        if (latestInterleavedPacket?.device?.address == address) {
            latestInterleavedPacket = null
            interleavedPacketInFlight = false
            interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
        }
        removedJobs.forEach { failJob(it, "device disconnected") }
        if (activeJob?.device?.address == address) {
            activeJob?.let { failJob(it, "device disconnected") }
            activeJob = null
            activePacketIndex = 0
            notificationInFlight = false
        }
        handler.removeCallbacksAndMessages(null)
        sendNextPacket()
    }

    @Synchronized
    fun clearAllForDisconnect(reason: String) {
        logger("[BleNotifyQueue] clear all reason=$reason")
        handler.removeCallbacksAndMessages(null)
        activeJob?.let { failJob(it, reason) }
        jobs.forEach { failJob(it, reason) }
        jobs.clear()
        latestInterleavedPacket = null
        interleavedPacketInFlight = false
        interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
        activeJob = null
        activePacketIndex = 0
        activeJobStartedAtMs = 0L
        notificationInFlight = false
    }

    @Synchronized
    fun clear() {
        handler.removeCallbacksAndMessages(null)
        activeJob?.let { failJob(it, "queue cleared") }
        jobs.forEach { failJob(it, "queue cleared") }
        jobs.clear()
        latestInterleavedPacket = null
        interleavedPacketInFlight = false
        interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
        activeJob = null
        activePacketIndex = 0
        notificationInFlight = false
    }

    @Synchronized
    fun snapshot(): BleNotifyQueueSnapshot {
        return BleNotifyQueueSnapshot(
            notificationInFlight = notificationInFlight,
            pendingJobCount = jobs.size,
            activeJobType = activeJob?.type,
            activeDeviceAddress = activeJob?.device?.address,
            pendingShortMessageCount = if (latestInterleavedPacket == null) 0 else 1
        )
    }

    @Synchronized
    private fun enqueueJob(job: SendJob) {
        jobs.addLast(job)
        sendNextPacket()
    }

    @Synchronized
    private fun sendNextPacket() {
        if (notificationInFlight) {
            return
        }

        if (activeJob == null) {
            activeJob = jobs.pollFirst() ?: return
            activePacketIndex = 0
            activeJobStartedAtMs = SystemClock.elapsedRealtime()
            activeJob?.takeIf { it.isLongJob }?.let {
                if (LogConfig.DEBUG_VERBOSE_LOG) {
                    verboseLogger(
                        "[BleNotifyQueue] job start " +
                            "type=${it.type} chunks=${it.chunkCount}"
                    )
                }
            }
        }

        val job = activeJob ?: return
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
            val interleaved = latestInterleavedPacket
            if (interleaved != null &&
                interleaved.device.address == job.device.address
            ) {
                val server = serverProvider()
                val characteristic = characteristicProvider()
                if (server != null && characteristic != null) {
                    latestInterleavedPacket = null
                    interleavedDelayAfterMs = interleaved.packet.delayAfterMs
                    interleavedPacketInFlight = true
                    notificationInFlight = true
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
                        interleavedPacketInFlight = false
                        notificationInFlight = false
                        interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
                        handler.postDelayed(
                            { sendNextPacket() },
                            interleaved.packet.delayAfterMs
                        )
                    }
                    return
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
        val requested = notify(server, characteristic, job.device, packet.value)
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
            logger("[BleNotifyQueue] notify request rejected type=${packet.type}")
            if (job.isLongJob) {
                markJobFailed(
                    job,
                    "notify request rejected type=${packet.type}"
                )
            }
            activePacketIndex += 1
            handler.postDelayed(
                { sendNextPacket() },
                packet.delayAfterMs
            )
        }
    }

    private fun markJobFailed(job: SendJob, reason: String) {
        job.failed = true
        if (job.failureLogged) {
            return
        }
        job.failureLogged = true
        when (job.type) {
            "albumArt" ->
                logger("[AlbumArt][BLE] failed reason=$reason")
            "remoteLog" ->
                logger("[RemoteLog] send failed reason=$reason")
            "mediaFieldDump" ->
                logger("[MediaFieldDump] send failed reason=$reason")
            "fullLyrics" ->
                logger("[FullLyrics] send failed reason=$reason")
            "trackInfo" ->
                logger("[TrackInfo] send failed reason=$reason")
        }
    }

    private fun failJob(job: SendJob, reason: String) {
        markJobFailed(job, reason)
        job.onFailure?.invoke()
    }

    private fun interleaveIntervalFor(jobType: String): Int {
        return when (jobType) {
            "albumArt" -> ALBUM_ART_INTERLEAVE_INTERVAL
            "fullLyrics" -> FULL_LYRICS_INTERLEAVE_INTERVAL
            "remoteLog", "mediaFieldDump", "qrcDump" ->
                OTHER_LONG_JOB_INTERLEAVE_INTERVAL
            else -> 0
        }
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
        val maxSendDurationMs: Long? = null,
        val onComplete: (() -> Unit)? = null,
        val onFailure: (() -> Unit)? = null,
        var failed: Boolean = false,
        var failureLogged: Boolean = false
    ) {
        val chunkCount: Int
            get() = packets.count {
                it.type == "albumArtChunk" || it.type == "logChunk"
                    || it.type == "albumArtBinaryChunk"
                    || it.type == "mediaFieldDumpChunk"
                    || it.type == "trackInfoChunk"
                    || it.type == "fullLyricsChunk"
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
        private const val SHORT_MESSAGE_DELAY_MS = 20L
        private const val CHUNK_PROGRESS_INTERVAL = 20
        private const val ALBUM_ART_INTERLEAVE_INTERVAL = 1
        private const val FULL_LYRICS_INTERLEAVE_INTERVAL = 10
        private const val OTHER_LONG_JOB_INTERLEAVE_INTERVAL = 5
        private const val INTERLEAVED_LOG_THROTTLE_MS = 10_000L
    }
}
