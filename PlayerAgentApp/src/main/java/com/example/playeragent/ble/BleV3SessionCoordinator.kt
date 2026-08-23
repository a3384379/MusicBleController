package com.example.playeragent.ble

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object BleV3Features {
    const val STATUS_META_V1 = 1 shl 0
    const val STRUCTURED_ERROR_V1 = 1 shl 1
    const val MEDIA_LOAD_STATE_V1 = 1 shl 2
    const val ALL = STATUS_META_V1 or STRUCTURED_ERROR_V1 or MEDIA_LOAD_STATE_V1
    const val STATUS_META_MIN_NOTIFY_PAYLOAD = 247
}

internal object BleV3CapabilityPolicy {
    fun negotiateF3(protocolVersion: Int, requestedF3: Int?, notifyPayload: Int): Int {
        if (protocolVersion < 3 || requestedF3 == null) return 0
        var negotiated = requestedF3 and BleV3Features.ALL
        if (notifyPayload < BleV3Features.STATUS_META_MIN_NOTIFY_PAYLOAD) {
            negotiated = negotiated and BleV3Features.STATUS_META_V1.inv()
        }
        return negotiated
    }

    fun f2(
        albumArtBinary: Boolean,
        fullLyricsZlib: Boolean,
        lyricWindow: Boolean,
        ping: Boolean,
        clockSyncV1: Boolean,
        transferRetry: Boolean
    ): Int {
        var value = 0
        if (albumArtBinary) value = value or (1 shl 0)
        if (fullLyricsZlib) value = value or (1 shl 1)
        if (lyricWindow) value = value or (1 shl 2)
        if (ping) value = value or (1 shl 3)
        if (clockSyncV1) value = value or (1 shl 4)
        if (transferRetry) value = value or (1 shl 5)
        return value
    }
}

internal object BleCapabilitiesAckPolicy {
    fun build(
        capabilities: ConnectionCommandCoordinator.Capabilities,
        requestedF3Present: Boolean,
        sessionId: String
    ): JSONObject {
        return if (capabilities.protocolVersion >= 3 && requestedF3Present) {
            JSONObject()
                .put("type", "clientCapabilitiesAck")
                .put("protocolVersion", 3)
                .put("f2", capabilities.f2)
                .put("f3", capabilities.f3)
                .put("sid", sessionId)
        } else {
            JSONObject()
                .put("type", "clientCapabilitiesAck")
                .put("protocolVersion", 2)
                .put("albumArtBinary", true)
                .put("fullLyricsZlib", true)
                .put("lyricWindow", true)
                .put("ping", true)
                .put("clockSyncV1", true)
                .put("transferRetry", true)
        }
    }
}

internal object BleV3PayloadFactory {
    fun commandError(
        seq: String,
        command: String,
        domain: String,
        code: String,
        retryable: Boolean
    ): JSONObject {
        return JSONObject()
            .put("type", "commandError")
            .put("seq", seq)
            .put("cmd", command)
            .put("domain", domain)
            .put("code", code)
            .put("retryable", retryable)
    }
}

internal class BleV3SessionCoordinator(
    val sessionId: String = UUID.randomUUID().toString().replace("-", "").take(8)
) {
    private val enqueueSequence = ConcurrentHashMap<String, AtomicLong>()
    private val mediaLoadStateKeys = ConcurrentHashMap<String, String>()

    fun decorate(address: String, value: JSONObject): JSONObject {
        val sequence = enqueueSequence.getOrPut(address) { AtomicLong(0L) }.incrementAndGet()
        return JSONObject(value.toString())
            .put("sid", sessionId)
            .put("es", sequence)
    }

    fun shouldSendMediaLoadState(
        address: String,
        resource: String,
        trackId: String,
        generation: Long,
        stage: String,
        reason: String
    ): Boolean {
        val mapKey = "$address|$resource|$trackId"
        val stateKey = "$generation|$stage|$reason"
        return mediaLoadStateKeys.put(mapKey, stateKey) != stateKey
    }

    fun resetAddress(address: String) {
        enqueueSequence.remove(address)
        mediaLoadStateKeys.keys.removeAll { it.startsWith("$address|") }
    }

    fun clear() {
        enqueueSequence.clear()
        mediaLoadStateKeys.clear()
    }
}
