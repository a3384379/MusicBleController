package com.example.controllerapp

import com.example.controllerapp.model.ArtworkQuality

/**
 * The single command surface shared by Compose, MediaSession and debug tooling.
 *
 * Connection ownership and protocol decoding remain in [ControllerRepository]; callers never
 * construct wire JSON or bypass its generation/transfer guards.
 */
class ControllerCommandGateway internal constructor(
    private val repository: ControllerRepository
) {
    fun playPause() = repository.playPause()
    fun previous() = repository.previous()
    fun next() = repository.next()
    fun seekTo(positionMs: Long) = repository.seekTo(positionMs)
    fun setVolume(value: Int) = repository.setVolume(value)
    fun requestPlaybackState() = repository.requestPlaybackState()
    fun requestVolume() = repository.requestVolume()

    fun requestFullLyrics() = repository.requestFullLyrics()
    fun retryLyrics() {
        repository.requestLyricWindow()
        repository.requestFullLyrics()
    }

    fun requestLyricDiagnostic() = repository.requestLyricDiagnostic()
    fun requestArtwork(quality: ArtworkQuality = ArtworkQuality.HQ) =
        repository.requestArtwork(quality)

    fun retryArtwork() = repository.forceRefreshArtwork()
    fun clearArtworkCache() = repository.clearArtworkCache()

    fun syncHistory() = repository.syncHistory()
    fun loadMoreHistory() = repository.loadMoreHistory()
    fun clearLocalHistory() = repository.clearLocalHistory()

    fun requestSonyLogs() = repository.requestSonyLogs()
    fun requestMediaDump() = repository.requestMediaDump()
    fun reconnect(reason: String, forceScan: Boolean = false) =
        repository.requestReconnect(reason, forceScan)

    fun startLegacyRfcomm() = repository.startLegacyRfcomm()
    fun stopLegacyRfcomm() = repository.stopLegacyRfcomm()
}
