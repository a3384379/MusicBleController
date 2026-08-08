package com.example.playeragent.ble

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

internal object MultiControllerPolicy {
    const val MAX_CONTROLLERS = 2

    fun hasConnectionCapacity(connectedCount: Int): Boolean {
        return connectedCount.coerceAtLeast(0) < MAX_CONTROLLERS
    }

    fun shouldIsolateOnlyFailingControllers(
        failingCount: Int,
        subscribedCount: Int
    ): Boolean {
        return failingCount > 0 && failingCount < subscribedCount
    }
}

/**
 * Owns the retained full-lyrics transfer and its compressed payload cache.
 *
 * Keeping both pieces behind one coordinator prevents artwork lifecycle changes
 * from invalidating a lyrics retry and gives disconnect/reset a single boundary.
 */
internal class LyricsTransferCoordinator(
    val compressedCache: CompressedLyricsCache = CompressedLyricsCache()
) {
    private val retainedTransfers = ConcurrentHashMap<String, FullLyricsBinaryTransfer>()

    fun retain(transfer: FullLyricsBinaryTransfer) {
        retainedTransfers[transferKey(transfer.ownerAddress, transfer.transferId)] = transfer
    }

    fun retained(ownerAddress: String, transferId: String): FullLyricsBinaryTransfer? {
        return retainedTransfers[transferKey(ownerAddress, transferId)]
    }

    fun reset() {
        retainedTransfers.clear()
        compressedCache.clear()
    }

    fun clearRetryState() {
        retainedTransfers.clear()
    }

    fun resetAddress(ownerAddress: String) {
        retainedTransfers.entries.removeAll { it.value.ownerAddress == ownerAddress }
    }

    private fun transferKey(ownerAddress: String, transferId: String): String {
        return "$ownerAddress|$transferId"
    }
}

/**
 * Owns short-lived album-art retry records independently from lyrics state.
 */
internal class AlbumArtTransferCoordinator {
    private val retainedTransfers = ConcurrentHashMap<String, AlbumArtBinaryTransfer>()

    fun retain(transfer: AlbumArtBinaryTransfer, nowMs: Long = SystemClock.elapsedRealtime()) {
        retainedTransfers.entries.removeAll { it.value.expiresAtMs < nowMs }
        retainedTransfers[transferKey(transfer.ownerAddress, transfer.transferId)] = transfer
    }

    fun retained(ownerAddress: String, transferId: String): AlbumArtBinaryTransfer? {
        return retainedTransfers[transferKey(ownerAddress, transferId)]
    }

    fun resetAddress(ownerAddress: String) {
        retainedTransfers.entries.removeAll { it.value.ownerAddress == ownerAddress }
    }

    fun reset() {
        retainedTransfers.clear()
    }

    private fun transferKey(ownerAddress: String, transferId: String): String {
        return "$ownerAddress|$transferId"
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
        val clockSyncV1: Boolean = false,
        val transferRetry: Boolean = false,
        val negotiated: Boolean = false
    )

    private data class ClientSession(
        val capabilities: Capabilities = Capabilities(),
        val negotiationGeneration: Long = 0L,
        val subscribedAtMs: Long = 0L
    )

    private val sessions = ConcurrentHashMap<String, ClientSession>()

    @Synchronized
    fun beginNegotiation(address: String, nowMs: Long = SystemClock.elapsedRealtime()): Long {
        val previous = sessions[address]
        val session = ClientSession(
            negotiationGeneration = (previous?.negotiationGeneration ?: 0L) + 1L,
            subscribedAtMs = nowMs
        )
        sessions[address] = session
        return session.negotiationGeneration
    }

    @Synchronized
    fun accept(address: String, requested: Capabilities) {
        val previous = sessions[address] ?: ClientSession()
        sessions[address] = previous.copy(
            capabilities = requested.copy(negotiated = true)
        )
    }

    @Synchronized
    fun useLegacyIfCurrent(address: String, generation: Long): Boolean {
        val session = sessions[address] ?: return false
        if (generation != session.negotiationGeneration || session.capabilities.negotiated) {
            return false
        }
        sessions[address] = session.copy(
            capabilities = session.capabilities.copy(negotiated = true)
        )
        return true
    }

    fun capabilities(address: String): Capabilities {
        return sessions[address]?.capabilities ?: Capabilities()
    }

    fun subscribedAtMs(address: String): Long {
        return sessions[address]?.subscribedAtMs ?: 0L
    }

    @Synchronized
    fun invalidate(address: String) {
        val session = sessions[address] ?: return
        sessions[address] = session.copy(
            capabilities = Capabilities(),
            negotiationGeneration = session.negotiationGeneration + 1L
        )
    }

    fun remove(address: String) {
        sessions.remove(address)
    }

    fun clear() {
        sessions.clear()
    }
}

/**
 * Prevents two controllers reacting to the same human action from toggling or
 * skipping twice. Repeated commands from the same controller remain untouched.
 */
internal class MultiControllerCommandGate(
    private val duplicateWindowMs: Long = 300L
) {
    private data class AcceptedCommand(val ownerAddress: String, val acceptedAtMs: Long)

    private val acceptedByCommand = ConcurrentHashMap<String, AcceptedCommand>()

    @Synchronized
    fun shouldExecute(command: String, ownerAddress: String, nowMs: Long): Boolean {
        val previous = acceptedByCommand[command]
        val duplicateFromAnotherController = previous != null &&
            previous.ownerAddress != ownerAddress &&
            nowMs - previous.acceptedAtMs in 0..duplicateWindowMs
        if (duplicateFromAnotherController) {
            return false
        }
        acceptedByCommand[command] = AcceptedCommand(ownerAddress, nowMs)
        return true
    }

    fun reset() {
        acceptedByCommand.clear()
    }
}

internal data class FullLyricsBinaryTransfer(
    val trackId: String,
    val transferId: String,
    val generation: Long,
    val start: BleNotifyQueue.Packet,
    val chunks: List<BleNotifyQueue.Packet>,
    val end: BleNotifyQueue.Packet,
    val expiresAtMs: Long,
    val ownerAddress: String = ""
)

internal data class AlbumArtBinaryTransfer(
    val trackId: String,
    val quality: AlbumArtQuality,
    val transferId: String,
    val start: BleNotifyQueue.Packet,
    val chunks: List<BleNotifyQueue.Packet>,
    val end: BleNotifyQueue.Packet,
    val expiresAtMs: Long,
    val ownerAddress: String = ""
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
