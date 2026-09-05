package com.example.controllerapp.protocol

internal object MediaIdentityPolicy {
    fun isNewMedia(
        currentTrackId: String,
        currentGeneration: Long,
        incomingTrackId: String,
        incomingGeneration: Long
    ): Boolean {
        if (currentTrackId != incomingTrackId) return true
        return incomingGeneration > 0L &&
            currentGeneration > 0L &&
            incomingGeneration != currentGeneration
    }

    fun generationMatches(currentGeneration: Long, incomingGeneration: Long): Boolean {
        return currentGeneration <= 0L ||
            incomingGeneration <= 0L ||
            currentGeneration == incomingGeneration
    }

    /**
     * A binary media transfer pins the Sony generation in its start and end
     * packets.  Sony can legitimately advance the runtime generation for the
     * same track while that small transfer is in flight (for example, when the
     * QRC fast path becomes ready).  In that case the bytes still belong to the
     * current track and must not be discarded just because the live generation
     * has moved forward.  A different track id remains an unconditional fence.
     */
    fun transferStillBelongsToCurrentTrack(
        currentTrackId: String,
        transferTrackId: String,
        currentGeneration: Long,
        transferGeneration: Long
    ): Boolean {
        if (currentTrackId.isBlank() || currentTrackId != transferTrackId) return false
        return currentGeneration <= 0L ||
            transferGeneration <= 0L ||
            currentGeneration >= transferGeneration
    }

    /**
     * Sony can advance its media generation while stabilizing metadata for the same track.
     * A newer transfer is therefore adoptable, while an older generation is always stale.
     */
    fun canAdoptIncomingGeneration(
        currentGeneration: Long,
        incomingGeneration: Long
    ): Boolean = currentGeneration <= 0L ||
        incomingGeneration <= 0L ||
        incomingGeneration >= currentGeneration
}
