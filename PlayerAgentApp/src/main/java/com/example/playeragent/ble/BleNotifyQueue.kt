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
import com.example.playeragent.diagnostics.RealtimeTrace
import com.example.playeragent.logging.LogConfig
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class BleQueueTerminalCallbackGate {
    private val dispatched = AtomicBoolean(false)

    fun dispatch(
        post: ((() -> Unit) -> Unit),
        callback: (() -> Unit)?
    ): Boolean {
        if (!dispatched.compareAndSet(false, true)) {
            return false
        }
        callback?.let(post)
        return true
    }
}

data class NotifyTraceContext(
    val trackId: String? = null,
    val generation: Long? = null,
    val handoffId: String? = null,
    val triggerType: String? = null,
    val positionAnchorMs: Long? = null,
    val lineIndex: Int? = null,
    val wordTimingStatus: String? = null,
    val hasCurrentLyric: Boolean = false
)

internal class CommandResponseQuietWindows(
    private val quietWindowMs: Long
) {
    private val quietUntilMsByAddress = LinkedHashMap<String, Long>()

    /** Keep the first deadline so a command burst cannot extend the window forever. */
    fun reserve(deviceAddress: String, nowMs: Long): Long {
        if (deviceAddress.isBlank()) return 0L
        val existing = quietUntilMsByAddress[deviceAddress]
        if (existing != null && existing > nowMs) {
            return existing
        }
        return (nowMs + quietWindowMs).also {
            quietUntilMsByAddress[deviceAddress] = it
        }
    }

    fun remainingDelayMs(
        deviceAddress: String,
        packetType: String,
        nowMs: Long
    ): Long {
        if (!BleNotifyQueue.isCommandResponseSensitivePacket(packetType)) {
            return 0L
        }
        val quietUntilMs = quietUntilMsByAddress[deviceAddress] ?: return 0L
        if (quietUntilMs <= nowMs) {
            quietUntilMsByAddress.remove(deviceAddress)
            return 0L
        }
        return quietUntilMs - nowMs
    }

    fun remove(deviceAddress: String) {
        quietUntilMsByAddress.remove(deviceAddress)
    }

    fun clear() {
        quietUntilMsByAddress.clear()
    }
}

internal class DeferredCommandResponseGate(
    private val maxPending: Int
) {
    data class Pending(
        val deviceAddress: String,
        val commandSeq: Long?,
        val commandType: String,
        val queuedAtMs: Long,
        val send: () -> Unit
    )

    private val pending = ArrayDeque<Pending>()

    fun enqueue(response: Pending): Boolean {
        if (pending.size >= maxPending) return false
        pending.addLast(response)
        return true
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun drainReady(blockedDeviceAddress: String? = null): List<Pending> {
        val ready = mutableListOf<Pending>()
        val retained = mutableListOf<Pending>()
        while (pending.isNotEmpty()) {
            val response = pending.removeFirst()
            if (blockedDeviceAddress != null &&
                response.deviceAddress == blockedDeviceAddress
            ) {
                retained += response
            } else {
                ready += response
            }
        }
        retained.forEach(pending::addLast)
        return ready
    }

    fun remove(deviceAddress: String): Int {
        val retained = pending.filterNot { it.deviceAddress == deviceAddress }
        val removed = pending.size - retained.size
        pending.clear()
        retained.forEach(pending::addLast)
        return removed
    }

    fun clear(): Int = pending.size.also { pending.clear() }
}

class BleNotifyQueue(
    private val serverProvider: () -> BluetoothGattServer?,
    private val characteristicProvider: () -> BluetoothGattCharacteristic?,
    private val logger: (String) -> Unit,
    private val localOnlyLogger: (String) -> Unit,
    private val verboseLogger: (String) -> Unit,
    private val onNotifySuccess: (deviceAddress: String, type: String) -> Unit = { _, _ -> },
    private val onNotifyFailure: (
        deviceAddress: String,
        type: String,
        status: Int,
        reason: String
    ) -> Unit = { _, _, _, _ -> }
) {

    private val handlerThread = HandlerThread("BleNotifyQueue").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val jobs = ArrayDeque<SendJob>()
    private val linkProfiles = LinkedHashMap<String, BleLinkProfile>()
    private val lastServedDeviceByPriority = LinkedHashMap<Priority, String>()
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
    private val commandResponseQuietWindows =
        CommandResponseQuietWindows(COMMAND_RESPONSE_QUIET_MS)
    private val deferredCommandResponses =
        DeferredCommandResponseGate(MAX_DEFERRED_COMMAND_RESPONSES)
    private var activeNotifyStartedAtMs = 0L
    private var activeNotifyDeviceAddress: String? = null
    private var activeNotifyPacketType: String? = null
    private var activeNotifyTraceContext: NotifyTraceContext? = null

    fun resetLinkProfile(address: String, mtu: Int) {
        runOnQueueThread { resetLinkProfileOnQueue(address, mtu) }
    }

    @Synchronized
    private fun resetLinkProfileOnQueue(address: String, mtu: Int) {
        if (address.isBlank()) return
        commandResponseQuietWindows.remove(address)
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
        delayAfterMs: Long = SHORT_MESSAGE_DELAY_MS,
        traceContext: NotifyTraceContext? = null
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
                isLongJob = false,
                traceContext = traceContext
            )
        )
    }

    /**
     * Serializes an ATT write response behind the currently in-flight notification.
     * Some Sony Bluetooth stacks return true from sendResponse() even when the response
     * collides with that notification in L2CAP and never reaches the controller.
     */
    fun sendCommandResponseWhenIdle(
        deviceAddress: String,
        commandSeq: Long?,
        commandType: String,
        send: () -> Unit
    ): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        val queued: Boolean
        val reason: String
        synchronized(this) {
            reserveCommandResponseWindow(deviceAddress, nowMs)
            reason = when {
                notificationInFlight && activeNotifyDeviceAddress == deviceAddress ->
                    "notify_in_flight"
                drainingCancelledCallback -> "cancelled_callback_drain"
                else -> "queue_boundary"
            }
            queued = deferredCommandResponses.enqueue(
                DeferredCommandResponseGate.Pending(
                    deviceAddress = deviceAddress,
                    commandSeq = commandSeq,
                    commandType = commandType,
                    queuedAtMs = nowMs,
                    send = send
                )
            )
        }
        RealtimeTrace.record(
            stage = if (queued) "commandResponseDeferred" else "commandResponseRejected",
            monoMs = nowMs,
            commandSeq = commandSeq,
            commandType = commandType,
            result = if (queued) "queued" else "rejected",
            reason = if (queued) reason else "response_queue_full"
        )
        if (queued) {
            handler.post { flushDeferredCommandResponses() }
        }
        return queued
    }

    @Synchronized
    fun onCommandResponseSent(deviceAddress: String) {
        reserveCommandResponseWindow(deviceAddress, SystemClock.elapsedRealtime())
    }

    private fun reserveCommandResponseWindow(deviceAddress: String, nowMs: Long) {
        if (deviceAddress.isBlank()) return
        val quietUntilMs = commandResponseQuietWindows.reserve(deviceAddress, nowMs)
        if (!notificationInFlight && !drainingCancelledCallback) {
            handler.postDelayed(
                { sendNextPacket() },
                (quietUntilMs - nowMs).coerceAtLeast(0L)
            )
        }
    }

    private fun flushDeferredCommandResponses() {
        val responses = synchronized(this) {
            if (drainingCancelledCallback) {
                return
            }
            deferredCommandResponses.drainReady(
                blockedDeviceAddress = if (notificationInFlight) {
                    activeNotifyDeviceAddress
                } else {
                    null
                }
            )
        }
        if (responses.isEmpty()) return
        responses.forEach { response ->
            val releasedAtMs = SystemClock.elapsedRealtime()
            RealtimeTrace.record(
                stage = "commandResponseReleased",
                monoMs = releasedAtMs,
                commandSeq = response.commandSeq,
                commandType = response.commandType,
                queueWaitMs = (releasedAtMs - response.queuedAtMs).coerceAtLeast(0L),
                result = "released"
            )
            runCatching(response.send).onFailure { exception ->
                logger(
                    "[BleNotifyQueue] command response failed " +
                        "cmd=${response.commandType} reason=${exception.message}"
                )
            }
        }
        handler.post { sendNextPacket() }
    }

    @Synchronized
    fun onMediaGenerationChanged() {
        commandResponseQuietWindows.clear()
        handler.post { sendNextPacket() }
    }

    fun enqueueLongJob(
        type: String,
        device: BluetoothDevice,
        packets: List<Packet>,
        priority: Priority = priorityFor(type),
        maxSendDurationMs: Long? = null,
        shouldCancel: (() -> Boolean)? = null,
        onComplete: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
        traceContext: NotifyTraceContext? = null
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
                onFailure = onFailure,
                traceContext = traceContext
            )
        )
    }

    fun setLatestInterleavedShort(
        device: BluetoothDevice,
        type: String,
        value: ByteArray,
        delayAfterMs: Long = SHORT_MESSAGE_DELAY_MS,
        traceContext: NotifyTraceContext? = null
    ) {
        runOnQueueThread {
            setLatestInterleavedShortOnQueue(
                device,
                type,
                value,
                delayAfterMs,
                traceContext
            )
        }
    }

    @Synchronized
    private fun setLatestInterleavedShortOnQueue(
        device: BluetoothDevice,
        type: String,
        value: ByteArray,
        delayAfterMs: Long,
        traceContext: NotifyTraceContext?
    ) {
        latestInterleavedPackets[interleavedPacketKey(device.address, type)] = InterleavedPacket(
            device = device,
            packet = Packet(
                type = type,
                value = value,
                delayAfterMs = delayAfterMs
            ),
            traceContext = traceContext
        )
        RealtimeTrace.record(
            stage = "notifyEnqueued",
            payloadType = type,
            chunkCount = 1,
            result = "queued_interleaved",
            trackId = traceContext?.trackId,
            generation = traceContext?.generation,
            handoffId = traceContext?.handoffId,
            triggerType = traceContext?.triggerType,
            positionAnchorMs = traceContext?.positionAnchorMs,
            lineIndex = traceContext?.lineIndex,
            wordTimingStatus = traceContext?.wordTimingStatus
        )
        recordPlaybackSpecificTrace(
            stage = "playbackEnqueued",
            packetType = type,
            context = traceContext,
            result = "queued_interleaved"
        )
        if (type == "playbackState" && traceContext?.hasCurrentLyric == true) {
            recordContextTrace(
                stage = "lyricCurrentLineEnqueued",
                payloadType = "playbackState",
                context = traceContext,
                result = "queued_interleaved"
            )
        }
        logInterleavedEventThrottled(
            isSavedEvent = true,
            message = "[BleNotifyQueue] long job active, $type saved as latest"
        )
    }

    @Synchronized
    fun hasLongJobActiveOrQueued(deviceAddress: String? = null): Boolean {
        return activeJob?.let {
            it.isLongJob && (deviceAddress == null || it.device.address == deviceAddress)
        } == true || jobs.any {
            it.isLongJob && (deviceAddress == null || it.device.address == deviceAddress)
        }
    }

    @Synchronized
    fun hasJobTypeActiveOrQueued(type: String, deviceAddress: String? = null): Boolean {
        return activeJob?.let {
            it.type == type && (deviceAddress == null || it.device.address == deviceAddress)
        } == true || jobs.any {
            it.type == type && (deviceAddress == null || it.device.address == deviceAddress)
        }
    }

    fun cancelJobTypes(
        types: Set<String>,
        reason: String,
        deviceAddress: String? = null
    ) {
        runOnQueueThread { cancelJobTypesOnQueue(types, reason, deviceAddress) }
    }

    @Synchronized
    private fun cancelJobTypesOnQueue(
        types: Set<String>,
        reason: String,
        deviceAddress: String?
    ) {
        val matches: (SendJob) -> Boolean = { job ->
            job.type in types &&
                (deviceAddress == null || job.device.address == deviceAddress)
        }
        val removedJobs = jobs.filter(matches)
        jobs.removeAll(matches)
        latestInterleavedPackets.entries.removeAll { (_, packet) ->
            packet.packet.type in types &&
                (deviceAddress == null || packet.device.address == deviceAddress)
        }
        val current = activeJob
        val activeJobRemoved = current != null && matches(current)
        (removedJobs + listOfNotNull(current.takeIf { activeJobRemoved })).forEach { job ->
            RealtimeTrace.record(
                stage = "notifyCancelled",
                payloadType = job.type,
                queueWaitMs = (SystemClock.elapsedRealtime() - job.enqueuedAtMs).coerceAtLeast(0L),
                result = "cancelled",
                reason = reason
            )
        }
        if (activeJobRemoved) {
            // Never clear the HandlerThread wholesale here: another device
            // may already have queued an enqueue/callback action. The stale
            // sendNext runnables are harmless and the active timeout is the
            // only callback that belongs to this cancelled notification.
            cancelNotifyTimeout()
        }
        removedJobs.forEach { failJob(it, reason) }
        if (activeJobRemoved && current != null) {
            failJob(current, reason)
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            interleavedPacketInFlight = false
            interleavedPacketType = null
            interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
        }
        if (removedJobs.isNotEmpty() || activeJobRemoved) {
            if (notificationInFlight) {
                beginCancelledCallbackDrain("cancelled active notify")
            } else {
                handler.post { sendNextPacket() }
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
        val expectedAddress = activeNotifyDeviceAddress ?: job.device.address
        if (!deviceAddress.isNullOrBlank() && deviceAddress != expectedAddress) {
            logger(
                "[BleNotifyQueue] ignored callback from other device " +
                    "expected=$expectedAddress actual=$deviceAddress status=$status"
            )
            return
        }

        cancelNotifyTimeout()
        notificationInFlight = false
        handler.post { flushDeferredCommandResponses() }
        val callbackAddress = deviceAddress
            ?.takeIf { it.isNotBlank() }
            ?: activeNotifyDeviceAddress
            ?: job.device.address
        val callbackType = activeNotifyPacketType
        val callbackTraceContext = activeNotifyTraceContext
        val callbackRttMs = if (activeNotifyStartedAtMs > 0L) {
            SystemClock.elapsedRealtime() - activeNotifyStartedAtMs
        } else {
            0L
        }
        RealtimeTrace.record(
            stage = "notifyCallback",
            payloadType = callbackType ?: job.type,
            processingMs = callbackRttMs,
            chunkIndex = activePacketIndex,
            chunkCount = job.packets.size,
            result = if (status == BluetoothGatt.GATT_SUCCESS) "success" else "failure",
            reason = if (status == BluetoothGatt.GATT_SUCCESS) null else "callback_failed",
            trackId = callbackTraceContext?.trackId,
            generation = callbackTraceContext?.generation,
            handoffId = callbackTraceContext?.handoffId,
            triggerType = callbackTraceContext?.triggerType,
            positionAnchorMs = callbackTraceContext?.positionAnchorMs,
            lineIndex = callbackTraceContext?.lineIndex,
            wordTimingStatus = callbackTraceContext?.wordTimingStatus
        )
        recordPlaybackSpecificTrace(
            stage = "playbackNotifyCallback",
            packetType = callbackType ?: job.type,
            context = callbackTraceContext,
            processingMs = callbackRttMs,
            result = if (status == BluetoothGatt.GATT_SUCCESS) "success" else "failure",
            reason = if (status == BluetoothGatt.GATT_SUCCESS) null else "callback_failed"
        )
        if (status == BluetoothGatt.GATT_SUCCESS &&
            callbackType == "playbackState" &&
            callbackTraceContext?.hasCurrentLyric == true
        ) {
            recordContextTrace(
                stage = "lyricCurrentLineSent",
                payloadType = "playbackState",
                context = callbackTraceContext,
                processingMs = callbackRttMs,
                result = "sent"
            )
        }
        if (callbackType == "albumArtOffer" && status == BluetoothGatt.GATT_SUCCESS) {
            RealtimeTrace.record(
                stage = "albumArtOfferSent",
                payloadType = "albumArtOffer",
                processingMs = callbackRttMs,
                result = "success"
            )
        }
        clearActiveNotifyMetrics()
        if (interleavedPacketInFlight) {
            val type = interleavedPacketType ?: callbackType ?: job.type
            interleavedPacketInFlight = false
            interleavedPacketType = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                dispatchNotifySuccess(callbackAddress, type)
                recordAdaptiveSuccess(type, callbackAddress, callbackRttMs)
            } else {
                recordAdaptiveFailure(type, callbackAddress)
                logger("[BleNotifyQueue] interleaved notify failed type=$type status=$status")
                dispatchNotifyFailure(callbackAddress, type, status, "callback_failed")
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
            dispatchNotifyFailure(callbackAddress, type, status, "callback_failed")
            abortActiveJob(job, "notify callback failed type=$type status=$status")
            return
        } else {
            dispatchNotifySuccess(callbackAddress, packet?.type ?: job.type)
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
        commandResponseQuietWindows.remove(address)
        val abandonedResponses = deferredCommandResponses.remove(address)
        if (abandonedResponses > 0) {
            logger(
                "[BleNotifyQueue] abandoned command responses count=$abandonedResponses " +
                    "reason=device_disconnected"
            )
        }
        linkProfiles.remove(address)
        lastServedDeviceByPriority.entries.removeAll { it.value == address }
        val activeDeviceRemoved = activeJob?.device?.address == address ||
            activeNotifyDeviceAddress == address
        if (interleavedPacketInFlight && activeNotifyDeviceAddress == address) {
            interleavedPacketInFlight = false
            interleavedPacketType = null
            interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
        }
        if (activeDeviceRemoved) {
            cancelNotifyTimeout()
        }
        removedJobs.forEach { failJob(it, "device disconnected") }
        if (activeDeviceRemoved) {
            activeJob?.let { failJob(it, "device disconnected") }
            activeJob = null
            activePacketIndex = 0
        }
        if (notificationInFlight && activeDeviceRemoved) {
            beginCancelledCallbackDrain("device disconnected")
        } else {
            handler.post { sendNextPacket() }
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
        lastServedDeviceByPriority.clear()
        interleavedPacketInFlight = false
        interleavedPacketType = null
        interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
        activeJob = null
        activePacketIndex = 0
        activeJobStartedAtMs = 0L
        notificationInFlight = false
        commandResponseQuietWindows.clear()
        deferredCommandResponses.clear()
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
        lastServedDeviceByPriority.clear()
        interleavedPacketInFlight = false
        interleavedPacketType = null
        interleavedDelayAfterMs = SHORT_MESSAGE_DELAY_MS
        activeJob = null
        activePacketIndex = 0
        notificationInFlight = false
        commandResponseQuietWindows.clear()
        deferredCommandResponses.clear()
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
            val coalesced = jobs.filter {
                !it.isLongJob &&
                    it.type == job.type &&
                    it.device.address == job.device.address
            }
            jobs.removeAll(coalesced.toSet())
            coalesced.forEach {
                RealtimeTrace.record(
                    stage = "notifyPreempted",
                    payloadType = it.type,
                    result = "coalesced",
                    reason = "latest_value_wins"
                )
            }
        }
        jobs.addLast(job)
        RealtimeTrace.record(
            stage = "notifyEnqueued",
            monoMs = job.enqueuedAtMs,
            payloadType = job.type,
            chunkCount = job.packets.size,
            result = "queued",
            trackId = job.traceContext?.trackId,
            generation = job.traceContext?.generation,
            handoffId = job.traceContext?.handoffId,
            triggerType = job.traceContext?.triggerType,
            positionAnchorMs = job.traceContext?.positionAnchorMs,
            lineIndex = job.traceContext?.lineIndex,
            wordTimingStatus = job.traceContext?.wordTimingStatus
        )
        recordPlaybackSpecificTrace(
            stage = "playbackEnqueued",
            packetType = job.type,
            context = job.traceContext,
            result = "queued"
        )
        if (job.type == "playbackState" && job.traceContext?.hasCurrentLyric == true) {
            recordContextTrace(
                stage = "lyricCurrentLineEnqueued",
                payloadType = "playbackState",
                context = job.traceContext,
                result = "queued"
            )
        }
        sendNextPacket()
    }

    @Synchronized
    private fun sendNextPacket() {
        if (notificationInFlight ||
            drainingCancelledCallback ||
            deferredCommandResponses.hasPending()
        ) {
            return
        }

        if (activeJob == null) {
            activateJob(pollNextJob() ?: return)
        }

        var job = activeJob ?: return
        if (shouldYieldToPendingJob(job)) {
            RealtimeTrace.record(
                stage = "notifyPreempted",
                payloadType = job.type,
                chunkIndex = activePacketIndex,
                chunkCount = job.packets.size,
                result = "yielded",
                reason = "higher_priority_pending"
            )
            job.nextPacketIndex = activePacketIndex
            job.packetsSinceYield = 0
            jobs.addFirst(job)
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            activateJob(pollNextJob() ?: return)
            job = activeJob ?: return
        }
        if (job.shouldCancel?.invoke() == true) {
            RealtimeTrace.record(
                stage = "notifyCancelled",
                payloadType = job.type,
                result = "cancelled",
                reason = "stale_generation"
            )
            failJob(job, "cancelled")
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            handler.post { sendNextPacket() }
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
            failJob(job, "timeout")
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            handler.post { sendNextPacket() }
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
                        interleaved.packet.type,
                        interleaved.traceContext
                    )
                    recordPlaybackSpecificTrace(
                        stage = "playbackNotifyStart",
                        packetType = interleaved.packet.type,
                        context = interleaved.traceContext,
                        result = "requested"
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
                        dispatchNotifyFailure(
                            interleaved.device.address,
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
                    latestInterleavedPackets[
                        interleavedPacketKey(interleaved.device.address, interleaved.packet.type)
                    ] = interleaved
                }
            }
        }

        if (activePacketIndex >= job.packets.size) {
            if (job.isLongJob) {
                if (job.failed) {
                    dispatchJobFailure(job)
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
                }
            }
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            if (job.isLongJob && !job.failed) {
                dispatchJobComplete(job)
            }
            // Terminal callbacks can enter BleGattServerManager. Queue the
            // next drain behind them so no callback runs while this monitor
            // is held and callback ordering remains deterministic.
            handler.post { sendNextPacket() }
            return
        }

        val packet = job.packets[activePacketIndex]
        val nowMs = SystemClock.elapsedRealtime()
        val quietDelayMs = commandResponseQuietWindows.remainingDelayMs(
            deviceAddress = job.device.address,
            packetType = packet.type,
            nowMs = nowMs
        )
        if (quietDelayMs > 0L) {
            // Keep this packet queued, but continue draining realtime state or
            // another controller whose response window is unrelated.
            job.nextPacketIndex = activePacketIndex
            jobs.addFirst(job)
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            val runnable = pollNextJob { candidate ->
                val candidatePacket = candidate.packets.getOrNull(candidate.nextPacketIndex)
                candidatePacket == null ||
                    commandResponseQuietWindows.remainingDelayMs(
                        deviceAddress = candidate.device.address,
                        packetType = candidatePacket.type,
                        nowMs = nowMs
                    ) == 0L
            }
            if (runnable != null) {
                activateJob(runnable)
                handler.post { sendNextPacket() }
            } else {
                handler.postDelayed({ sendNextPacket() }, quietDelayMs)
            }
            return
        }
        val server = serverProvider()
        val characteristic = characteristicProvider()
        if (server == null || characteristic == null) {
            logger("[BleNotifyQueue] send failed: GATT server unavailable")
            failJob(job, "GATT server unavailable")
            activeJob = null
            activePacketIndex = 0
            activeJobStartedAtMs = 0L
            handler.post { sendNextPacket() }
            return
        }

        notificationInFlight = true
        activeRequestType = packet.type
        markNotifyStarted(job.device.address, packet.type, job.traceContext)
        RealtimeTrace.record(
            stage = "notifySendStart",
            payloadType = packet.type,
            queueWaitMs = (SystemClock.elapsedRealtime() - job.enqueuedAtMs).coerceAtLeast(0L),
            chunkIndex = activePacketIndex,
            chunkCount = job.packets.size,
            result = "requested",
            trackId = job.traceContext?.trackId,
            generation = job.traceContext?.generation,
            handoffId = job.traceContext?.handoffId,
            triggerType = job.traceContext?.triggerType,
            positionAnchorMs = job.traceContext?.positionAnchorMs,
            lineIndex = job.traceContext?.lineIndex,
            wordTimingStatus = job.traceContext?.wordTimingStatus
        )
        recordPlaybackSpecificTrace(
            stage = "playbackNotifyStart",
            packetType = packet.type,
            context = job.traceContext,
            queueWaitMs = (SystemClock.elapsedRealtime() - job.enqueuedAtMs).coerceAtLeast(0L),
            result = "requested"
        )
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
            dispatchNotifyFailure(
                job.device.address,
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
        failJob(job, reason)
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
                RealtimeTrace.record(
                    stage = "notifyTimeout",
                    payloadType = type,
                    processingMs = if (activeNotifyStartedAtMs > 0L) {
                        SystemClock.elapsedRealtime() - activeNotifyStartedAtMs
                    } else {
                        NOTIFY_CALLBACK_TIMEOUT_MS
                    },
                    chunkIndex = activePacketIndex,
                    chunkCount = job?.packets?.size,
                    result = "timeout",
                    reason = "callback_timeout"
                )
                logger("[BleNotifyQueue] notify callback timeout type=$type job=${job?.type}")
                val address = activeNotifyDeviceAddress ?: job?.device?.address.orEmpty()
                recordAdaptiveFailure(type, address)
                dispatchNotifyFailure(
                    address,
                    type,
                    NOTIFY_CALLBACK_TIMEOUT_STATUS,
                    "callback_timeout"
                )
                if (job != null) {
                    failJob(job, "notify callback timeout type=$type")
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
        handler.post { flushDeferredCommandResponses() }
        handler.postDelayed({ sendNextPacket() }, SHORT_MESSAGE_DELAY_MS)
    }

    private fun pollInterleavedPacket(deviceAddress: String): InterleavedPacket? {
        val preferredTypes = listOf(
            "trackInfo",
            "playbackState",
            "volumeState",
            "currentWord"
        )
        val selectedKey = preferredTypes
            .map { type -> interleavedPacketKey(deviceAddress, type) }
            .firstOrNull(latestInterleavedPackets::containsKey)
            ?: latestInterleavedPackets.entries.firstOrNull {
                it.value.device.address == deviceAddress
            }?.key
            ?: return null
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
        dispatchJobFailure(job)
    }

    /**
     * Queue methods use the BleNotifyQueue monitor to serialize state reads
     * from diagnostic and media threads. Never call a manager callback inline
     * while that monitor is held: BleGattServerManager may hold its own monitor
     * while querying this queue, which otherwise creates an AB/BA deadlock.
     */
    private fun dispatchJobComplete(job: SendJob) {
        job.terminalCallbackGate.dispatch(
            post = { callback -> handler.post(callback) },
            callback = job.onComplete
        )
    }

    private fun dispatchJobFailure(job: SendJob) {
        job.terminalCallbackGate.dispatch(
            post = { callback -> handler.post(callback) },
            callback = job.onFailure
        )
    }

    private fun dispatchNotifySuccess(deviceAddress: String, type: String) {
        handler.post { onNotifySuccess(deviceAddress, type) }
    }

    private fun dispatchNotifyFailure(
        deviceAddress: String,
        type: String,
        status: Int,
        reason: String
    ) {
        handler.post { onNotifyFailure(deviceAddress, type, status, reason) }
    }

    private fun activateJob(selected: SendJob) {
        activeJob = selected
        activePacketIndex = selected.nextPacketIndex
        activeJobStartedAtMs = selected.startedAtMs
            .takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()
        selected.startedAtMs = activeJobStartedAtMs
        RealtimeTrace.record(
            stage = "notifyDequeued",
            payloadType = selected.type,
            queueWaitMs = (SystemClock.elapsedRealtime() - selected.enqueuedAtMs)
                .coerceAtLeast(0L),
            chunkIndex = selected.nextPacketIndex,
            chunkCount = selected.packets.size,
            result = "selected",
            trackId = selected.traceContext?.trackId,
            generation = selected.traceContext?.generation,
            handoffId = selected.traceContext?.handoffId,
            triggerType = selected.traceContext?.triggerType,
            positionAnchorMs = selected.traceContext?.positionAnchorMs,
            lineIndex = selected.traceContext?.lineIndex,
            wordTimingStatus = selected.traceContext?.wordTimingStatus
        )
        recordPlaybackSpecificTrace(
            stage = "playbackDequeued",
            packetType = selected.type,
            context = selected.traceContext,
            queueWaitMs = (SystemClock.elapsedRealtime() - selected.enqueuedAtMs)
                .coerceAtLeast(0L),
            result = "selected"
        )
        if (selected.isLongJob && LogConfig.DEBUG_VERBOSE_LOG) {
            verboseLogger(
                "[BleNotifyQueue] job start " +
                    "type=${selected.type} chunks=${selected.chunkCount}"
            )
        }
    }

    private fun pollNextJob(
        eligible: (SendJob) -> Boolean = { true }
    ): SendJob? {
        val candidates = jobs.filter(eligible)
        val priority = candidates.minOfOrNull { it.priority.rank } ?: return null
        val priorityValue = Priority.entries.first { it.rank == priority }
        val previousAddress = lastServedDeviceByPriority[priorityValue]
        val selected = candidates.firstOrNull {
            it.priority.rank == priority && it.device.address != previousAddress
        } ?: candidates.firstOrNull { it.priority.rank == priority } ?: return null
        jobs.remove(selected)
        lastServedDeviceByPriority[priorityValue] = selected.device.address
        return selected
    }

    private fun shouldYieldToPendingJob(job: SendJob): Boolean {
        if (activePacketIndex <= 0 || jobs.isEmpty()) {
            return false
        }
        val waitingPriority = jobs.minOfOrNull { it.priority.rank } ?: return false
        if (waitingPriority > job.priority.rank) {
            return false
        }
        if (waitingPriority == job.priority.rank) {
            val anotherDeviceWaiting = jobs.any {
                it.priority == job.priority && it.device.address != job.device.address
            }
            return shouldYieldToPeerAtSamePriority(
                priority = job.priority,
                packetsSinceYield = job.packetsSinceYield,
                anotherDeviceWaiting = anotherDeviceWaiting
            )
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

    private fun markNotifyStarted(
        address: String,
        type: String,
        traceContext: NotifyTraceContext?
    ) {
        activeNotifyStartedAtMs = SystemClock.elapsedRealtime()
        activeNotifyDeviceAddress = address
        activeNotifyPacketType = type
        activeNotifyTraceContext = traceContext
    }

    private fun clearActiveNotifyMetrics() {
        activeNotifyStartedAtMs = 0L
        activeNotifyDeviceAddress = null
        activeNotifyPacketType = null
        activeNotifyTraceContext = null
    }

    private fun recordPlaybackSpecificTrace(
        stage: String,
        packetType: String,
        context: NotifyTraceContext?,
        queueWaitMs: Long? = null,
        processingMs: Long? = null,
        result: String,
        reason: String? = null
    ) {
        if (packetType != "playbackState") return
        recordContextTrace(
            stage = stage,
            payloadType = packetType,
            context = context,
            queueWaitMs = queueWaitMs,
            processingMs = processingMs,
            result = result,
            reason = reason
        )
    }

    private fun recordContextTrace(
        stage: String,
        payloadType: String,
        context: NotifyTraceContext?,
        queueWaitMs: Long? = null,
        processingMs: Long? = null,
        result: String,
        reason: String? = null
    ) {
        RealtimeTrace.record(
            stage = stage,
            trackId = context?.trackId,
            generation = context?.generation,
            payloadType = payloadType,
            queueWaitMs = queueWaitMs,
            processingMs = processingMs,
            result = result,
            reason = reason,
            handoffId = context?.handoffId,
            triggerType = context?.triggerType,
            positionAnchorMs = context?.positionAnchorMs,
            lineIndex = context?.lineIndex,
            wordTimingStatus = context?.wordTimingStatus
        )
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
        val packet: Packet,
        val traceContext: NotifyTraceContext? = null
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
        var packetsSinceYield: Int = 0,
        val enqueuedAtMs: Long = SystemClock.elapsedRealtime(),
        val terminalCallbackGate: BleQueueTerminalCallbackGate =
            BleQueueTerminalCallbackGate(),
        val traceContext: NotifyTraceContext? = null
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
        private const val MAX_DEFERRED_COMMAND_RESPONSES = 8
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
        private val COMMAND_RESPONSE_SENSITIVE_PACKET_TYPES = setOf(
            "albumArtBinaryChunk",
            "albumArtChunk",
            "fullLyricsBinaryChunk",
            "fullLyricsChunk",
            "lyricSecondaryPart",
            "logChunk",
            "mediaFieldDumpChunk",
            "historyPayloadChunk"
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

        internal fun isCommandResponseSensitivePacket(type: String): Boolean {
            return type in COMMAND_RESPONSE_SENSITIVE_PACKET_TYPES
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

        internal fun interleavedPacketKey(deviceAddress: String, type: String): String {
            return "$deviceAddress|$type"
        }

        internal fun shouldYieldToPeerAtSamePriority(
            priority: Priority,
            packetsSinceYield: Int,
            anotherDeviceWaiting: Boolean
        ): Boolean {
            if (!anotherDeviceWaiting) return false
            return when (priority) {
                Priority.P0_REALTIME -> false
                Priority.P1_INTERACTIVE -> packetsSinceYield >= 1
                Priority.P2_BULK -> packetsSinceYield >= BULK_YIELD_INTERVAL
                Priority.P3_BACKGROUND -> packetsSinceYield >= BACKGROUND_YIELD_INTERVAL
            }
        }
    }

    enum class Priority(val rank: Int) {
        P0_REALTIME(0),
        P1_INTERACTIVE(1),
        P2_BULK(2),
        P3_BACKGROUND(3)
    }
}
