package com.example.playeragent.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private var notificationInFlight = false

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
                onComplete = onComplete,
                onFailure = onFailure
            )
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
    fun clear() {
        handler.removeCallbacksAndMessages(null)
        activeJob?.let { failJob(it, "queue cleared") }
        jobs.forEach { failJob(it, "queue cleared") }
        jobs.clear()
        activeJob = null
        activePacketIndex = 0
        notificationInFlight = false
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
        if (activePacketIndex >= job.packets.size) {
            if (job.isLongJob) {
                if (job.failed) {
                    job.onFailure?.invoke()
                } else {
                    when (job.type) {
                        "albumArt" -> logger(
                            "[AlbumArt][BLE] send end id=${job.albumArtId}"
                        )
                        "remoteLog" -> logger("[RemoteLog] send end")
                        else -> logger("[BleNotifyQueue] job end type=${job.type}")
                    }
                    job.onComplete?.invoke()
                }
            }
            activeJob = null
            activePacketIndex = 0
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
            sendNextPacket()
            return
        }

        notificationInFlight = true
        val requested = notify(server, characteristic, job.device, packet.value)
        val sendLog = "[BleNotifyQueue] send type=${packet.type} bytes=${packet.value.size}"
        when {
            packet.type == "logChunk" || packet.type == "albumArtChunk" -> {
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
        }
    }

    private fun failJob(job: SendJob, reason: String) {
        markJobFailed(job, reason)
        job.onFailure?.invoke()
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

    private data class SendJob(
        val type: String,
        val device: BluetoothDevice,
        val packets: List<Packet>,
        val isLongJob: Boolean,
        val onComplete: (() -> Unit)? = null,
        val onFailure: (() -> Unit)? = null,
        var failed: Boolean = false,
        var failureLogged: Boolean = false
    ) {
        val chunkCount: Int
            get() = packets.count {
                it.type == "albumArtChunk" || it.type == "logChunk"
            }

        val albumArtId: String
            get() {
                val endPacket = packets.lastOrNull {
                    it.type == "albumArtEnd"
                } ?: return ""
                return try {
                    JSONObject(endPacket.value.toString(Charsets.UTF_8))
                        .optString("id")
                } catch (_: Exception) {
                    ""
                }
            }
    }

    companion object {
        private const val SHORT_MESSAGE_DELAY_MS = 20L
        private const val CHUNK_PROGRESS_INTERVAL = 20
    }
}
