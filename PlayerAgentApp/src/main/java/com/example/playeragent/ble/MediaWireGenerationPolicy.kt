package com.example.playeragent.ble

/**
 * Selects the generation exposed on the BLE media protocol.
 *
 * The reactive controller owns a process-local task generation while the
 * runtime cache owns the authoritative current-track generation used by
 * CurrentWord, lyrics, and media load state. The counters can have different
 * offsets after a service/GATT restart, so protocol messages must not mix them.
 */
internal object MediaWireGenerationPolicy {
    fun resolve(
        protocolTrackId: String,
        runtimeTrackId: String?,
        runtimeGeneration: Long?,
        fallbackGeneration: Long
    ): Long {
        val protocolId = protocolTrackId.trim()
        val runtimeId = runtimeTrackId.orEmpty().trim()
        val matchingIdentity = protocolId.isNotEmpty() && runtimeId.isNotEmpty() &&
            (runtimeId == protocolId ||
                runtimeId.startsWith(protocolId) ||
                protocolId.startsWith(runtimeId))
        return if (matchingIdentity && runtimeGeneration != null && runtimeGeneration > 0L) {
            runtimeGeneration
        } else {
            fallbackGeneration
        }
    }
}
