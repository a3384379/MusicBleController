package com.example.playeragent.diagnostics

import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

data class RealtimeTraceEvent(
    val sequence: Long,
    val side: String = "sony",
    val stage: String,
    val monoMs: Long,
    val commandSeq: Long? = null,
    val commandType: String? = null,
    val trackId: String? = null,
    val generation: Long? = null,
    val transferId: String? = null,
    val payloadType: String? = null,
    val queueWaitMs: Long? = null,
    val processingMs: Long? = null,
    val chunkIndex: Int? = null,
    val chunkCount: Int? = null,
    val result: String? = null,
    val reason: String? = null,
    val handoffId: String? = null,
    val triggerType: String? = null,
    val positionAnchorMs: Long? = null,
    val lineIndex: Int? = null,
    val wordTimingStatus: String? = null,
    val cacheSource: String? = null,
    val failureReason: String? = null
) {
    fun logLine(): String {
        val fields = listOf(
            "side" to side,
            "stage" to stage,
            "monoMs" to monoMs.toString(),
            "handoffId" to handoffId,
            "triggerType" to triggerType,
            "commandSeq" to commandSeq?.toString(),
            "commandType" to commandType,
            "trackId" to trackId,
            "generation" to generation?.toString(),
            "transferId" to transferId,
            "payloadType" to payloadType,
            "queueWaitMs" to queueWaitMs?.toString(),
            "processingMs" to processingMs?.toString(),
            "chunkIndex" to chunkIndex?.toString(),
            "chunkCount" to chunkCount?.toString(),
            "result" to result,
            "reason" to reason,
            "positionAnchorMs" to positionAnchorMs?.toString(),
            "lineIndex" to lineIndex?.toString(),
            "wordTimingStatus" to wordTimingStatus,
            "cacheSource" to cacheSource,
            "failureReason" to failureReason
        )
        return "[RealtimeTrace] " + fields
            .filter { (_, value) -> value != null }
            .joinToString(" ") { (key, value) -> "$key=${safe(value.orEmpty())}" }
    }

    private fun safe(value: String): String = value
        .take(96)
        .map { character ->
            if (character.isLetterOrDigit() || character in "-_.:/") character else '_'
        }
        .joinToString("")
}

class RealtimeTraceBuffer(
    val capacity: Int = 2_048,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val storage = arrayOfNulls<RealtimeTraceEvent>(capacity)
    private var nextIndex = 0
    private var storedCount = 0
    private var nextSequence = 0L
    private var lastMonoMs = 0L

    @Synchronized
    fun append(
        stage: String,
        monoMs: Long? = null,
        commandSeq: Long? = null,
        commandType: String? = null,
        trackId: String? = null,
        generation: Long? = null,
        transferId: String? = null,
        payloadType: String? = null,
        queueWaitMs: Long? = null,
        processingMs: Long? = null,
        chunkIndex: Int? = null,
        chunkCount: Int? = null,
        result: String? = null,
        reason: String? = null,
        handoffId: String? = null,
        triggerType: String? = null,
        positionAnchorMs: Long? = null,
        lineIndex: Int? = null,
        wordTimingStatus: String? = null,
        cacheSource: String? = null,
        failureReason: String? = null
    ): RealtimeTraceEvent {
        val sampled = monoMs ?: clock()
        val stableMonoMs = maxOf(sampled, lastMonoMs)
        lastMonoMs = stableMonoMs
        nextSequence += 1L
        val event = RealtimeTraceEvent(
            sequence = nextSequence,
            stage = stage,
            monoMs = stableMonoMs,
            commandSeq = commandSeq,
            commandType = commandType,
            trackId = trackId,
            generation = generation,
            transferId = transferId,
            payloadType = payloadType,
            queueWaitMs = queueWaitMs,
            processingMs = processingMs,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
            result = result,
            reason = reason,
            handoffId = handoffId,
            triggerType = triggerType,
            positionAnchorMs = positionAnchorMs,
            lineIndex = lineIndex,
            wordTimingStatus = wordTimingStatus,
            cacheSource = cacheSource,
            failureReason = failureReason
        )
        storage[nextIndex] = event
        nextIndex = (nextIndex + 1) % capacity
        storedCount = minOf(storedCount + 1, capacity)
        return event
    }

    @Synchronized
    fun snapshot(): List<RealtimeTraceEvent> {
        if (storedCount == 0) return emptyList()
        val start = if (storedCount == capacity) nextIndex else 0
        return (0 until storedCount).mapNotNull { offset ->
            storage[(start + offset) % capacity]
        }
    }

    @Synchronized
    fun clear() {
        storage.fill(null)
        nextIndex = 0
        storedCount = 0
        lastMonoMs = 0L
    }
}

object RealtimeTrace {
    @Volatile
    var enabled: Boolean = false

    private val buffer = RealtimeTraceBuffer()
    private val sink = AtomicReference<(String) -> Unit>({})
    private val logExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "realtime-trace-log").apply { isDaemon = true }
    }

    fun configure(logger: (String) -> Unit, enabled: Boolean = this.enabled) {
        sink.set(logger)
        this.enabled = enabled
    }

    fun record(
        stage: String,
        monoMs: Long? = null,
        commandSeq: Long? = null,
        commandType: String? = null,
        trackId: String? = null,
        generation: Long? = null,
        transferId: String? = null,
        payloadType: String? = null,
        queueWaitMs: Long? = null,
        processingMs: Long? = null,
        chunkIndex: Int? = null,
        chunkCount: Int? = null,
        result: String? = null,
        reason: String? = null,
        handoffId: String? = null,
        triggerType: String? = null,
        positionAnchorMs: Long? = null,
        lineIndex: Int? = null,
        wordTimingStatus: String? = null,
        cacheSource: String? = null,
        failureReason: String? = null
    ): RealtimeTraceEvent? {
        if (!enabled) return null
        val event = buffer.append(
            stage = stage,
            monoMs = monoMs,
            commandSeq = commandSeq,
            commandType = commandType,
            trackId = trackId,
            generation = generation,
            transferId = transferId,
            payloadType = payloadType,
            queueWaitMs = queueWaitMs,
            processingMs = processingMs,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
            result = result,
            reason = reason,
            handoffId = handoffId,
            triggerType = triggerType,
            positionAnchorMs = positionAnchorMs,
            lineIndex = lineIndex,
            wordTimingStatus = wordTimingStatus,
            cacheSource = cacheSource,
            failureReason = failureReason
        )
        logExecutor.execute { sink.get().invoke(event.logLine()) }
        return event
    }

    fun snapshot(): List<RealtimeTraceEvent> = buffer.snapshot()

    fun clear() = buffer.clear()
}
