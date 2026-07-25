package com.example.playeragent.ble

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the retained full-lyrics transfer and its compressed payload cache.
 *
 * Keeping both pieces behind one coordinator prevents artwork lifecycle changes
 * from invalidating a lyrics retry and gives disconnect/reset a single boundary.
 */
internal class LyricsTransferCoordinator(
    val compressedCache: CompressedLyricsCache = CompressedLyricsCache()
) {
    @Volatile
    private var retainedTransfer: FullLyricsBinaryTransfer? = null

    fun retain(transfer: FullLyricsBinaryTransfer) {
        retainedTransfer = transfer
    }

    fun retained(transferId: String): FullLyricsBinaryTransfer? {
        return retainedTransfer?.takeIf { it.transferId == transferId }
    }

    fun reset() {
        retainedTransfer = null
        compressedCache.clear()
    }

    fun clearRetryState() {
        retainedTransfer = null
    }
}

/**
 * Owns short-lived album-art retry records independently from lyrics state.
 */
internal class AlbumArtTransferCoordinator {
    private val retainedTransfers = ConcurrentHashMap<String, AlbumArtBinaryTransfer>()

    fun retain(transfer: AlbumArtBinaryTransfer, nowMs: Long = SystemClock.elapsedRealtime()) {
        retainedTransfers.entries.removeAll { it.value.expiresAtMs < nowMs }
        retainedTransfers[transfer.transferId] = transfer
    }

    fun retained(transferId: String): AlbumArtBinaryTransfer? = retainedTransfers[transferId]

    fun reset() {
        retainedTransfers.clear()
    }
}

/**
 * Owns connection-scoped protocol negotiation. This state is deliberately reset
 * for every subscription generation so an old central cannot leak capabilities
 * into a new connection.
 */
internal class ConnectionCommandCoordinator {
    data class Capabilities(
        val protocolVersion: Int = 1,
        val binaryAlbumArt: Boolean = false,
        val fullLyricsZlib: Boolean = false,
        val lyricWindow: Boolean = false,
        val ping: Boolean = false,
        val transferRetry: Boolean = false,
        val negotiated: Boolean = false
    )

    @Volatile
    var capabilities = Capabilities()
        private set

    @Volatile
    var negotiationGeneration: Long = 0L
        private set

    fun beginNegotiation(): Long {
        capabilities = Capabilities()
        negotiationGeneration += 1
        return negotiationGeneration
    }

    fun accept(requested: Capabilities) {
        capabilities = requested.copy(negotiated = true)
    }

    fun useLegacyIfCurrent(generation: Long): Boolean {
        if (generation != negotiationGeneration || capabilities.negotiated) return false
        capabilities = capabilities.copy(negotiated = true)
        return true
    }

    fun invalidate() {
        capabilities = Capabilities()
        negotiationGeneration += 1
    }
}

internal data class FullLyricsBinaryTransfer(
    val trackId: String,
    val transferId: String,
    val generation: Long,
    val start: BleNotifyQueue.Packet,
    val chunks: List<BleNotifyQueue.Packet>,
    val end: BleNotifyQueue.Packet,
    val expiresAtMs: Long
)

internal data class AlbumArtBinaryTransfer(
    val trackId: String,
    val quality: AlbumArtQuality,
    val transferId: String,
    val start: BleNotifyQueue.Packet,
    val chunks: List<BleNotifyQueue.Packet>,
    val end: BleNotifyQueue.Packet,
    val expiresAtMs: Long
)

internal enum class AlbumArtQuality(val wireValue: String) {
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
