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
}
