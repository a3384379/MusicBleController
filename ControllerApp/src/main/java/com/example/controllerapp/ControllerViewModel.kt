package com.example.controllerapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controllerapp.model.AppExperienceMode
import com.example.controllerapp.model.ArtworkQuality
import com.example.controllerapp.model.LyricDisplayMode
import com.example.controllerapp.model.PlaybackPerformanceMode
import com.example.controllerapp.media.PlaybackClockPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ControllerViewModel(application: Application) : AndroidViewModel(application) {
    private val controllerApplication = application as ControllerApplication
    private val repository = controllerApplication.repository
    private val commands = controllerApplication.commandGateway

    val connection = repository.connection
    val playback = repository.playback
    val lyrics = repository.lyrics
    val artwork = repository.artwork
    val diagnostics = repository.diagnostics
    val history = repository.history
    val settings = repository.settings

    private val _displayedPositionMs = MutableStateFlow(0L)
    val displayedPositionMs: StateFlow<Long> = _displayedPositionMs.asStateFlow()
    private val uiVisible = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            while (true) {
                if (!uiVisible.value) {
                    uiVisible.first { it }
                    continue
                }
                val state = playback.value
                _displayedPositionMs.value = repository.displayedPositionMs()
                if (!state.isPlaying) {
                    playback.first { next ->
                        next.isPlaying ||
                            next.trackId != state.trackId ||
                            next.positionMs != state.positionMs
                    }
                    continue
                }
                val interval = PlaybackClockPolicy.refreshIntervalMs(
                    uiVisible = true,
                    isPlaying = true,
                    performanceMode = settings.value.performanceMode
                ) ?: continue
                delay(interval)
            }
        }
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
        if (visible) _displayedPositionMs.value = repository.displayedPositionMs()
    }

    fun playPause() {
        commands.playPause()
    }

    fun previous() {
        commands.previous()
    }

    fun next() {
        commands.next()
    }

    fun seekTo(positionMs: Long) {
        commands.seekTo(positionMs)
    }

    fun setVolume(value: Int) {
        commands.setVolume(value)
    }

    fun reconnect(forceScan: Boolean = false) {
        commands.reconnect("user", forceScan)
    }

    fun requestFullLyrics() {
        commands.requestFullLyrics()
    }

    fun retryLyrics() {
        commands.retryLyrics()
    }

    fun requestArtwork(quality: ArtworkQuality = ArtworkQuality.HQ) {
        commands.requestArtwork(quality)
    }

    fun retryArtwork() {
        commands.retryArtwork()
    }

    fun requestLyricDiagnostic() {
        commands.requestLyricDiagnostic()
    }

    fun requestSonyLogs() {
        commands.requestSonyLogs()
    }

    fun requestMediaDump() {
        commands.requestMediaDump()
    }

    fun requestPlaybackState() {
        commands.requestPlaybackState()
    }

    fun syncHistory() {
        commands.syncHistory()
    }

    fun loadMoreHistory() {
        commands.loadMoreHistory()
    }

    fun clearLocalHistory() {
        commands.clearLocalHistory()
    }

    fun clearArtworkCache() {
        commands.clearArtworkCache()
    }

    fun clearLogs() {
        repository.clearLogs()
    }

    fun startLegacyRfcomm() {
        commands.startLegacyRfcomm()
    }

    fun stopLegacyRfcomm() {
        commands.stopLegacyRfcomm()
    }

    fun updateExperienceMode(value: AppExperienceMode) {
        repository.updateExperienceMode(value)
    }

    fun updatePerformanceMode(value: PlaybackPerformanceMode) {
        repository.updatePerformanceMode(value)
    }

    fun updateAutoReconnect(value: Boolean) {
        repository.updateAutoReconnect(value)
    }

    fun updateLyricOffset(value: Long) {
        repository.updateLyricOffset(value)
        _displayedPositionMs.value = repository.displayedPositionMs()
    }

    fun updateLyricMode(value: LyricDisplayMode) {
        repository.updateLyricMode(value)
    }

    fun updateArtworkSize(value: Int) {
        repository.updateArtworkSize(value)
    }

    fun updateArtworkEnhancement(value: Boolean) {
        repository.updateArtworkEnhancement(value)
    }

    fun logFile() = repository.logStore.currentFile

    suspend fun historyArtwork(artworkId: String) =
        repository.historyArtwork(artworkId)
}
