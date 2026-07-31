package com.example.controllerapp

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.PowerManager
import android.util.Base64
import com.example.controllerapp.data.ControllerLogStore
import com.example.controllerapp.data.ControllerPreferences
import com.example.controllerapp.data.NowPlayingSnapshotStore
import com.example.controllerapp.data.PlaybackHistoryDao
import com.example.controllerapp.data.PlaybackHistoryEntity
import com.example.controllerapp.media.ArtworkCache
import com.example.controllerapp.media.ArtworkEnhancer
import com.example.controllerapp.media.ArtworkPlaceholderPolicy
import com.example.controllerapp.media.CachedArtwork
import com.example.controllerapp.model.AppExperienceMode
import com.example.controllerapp.model.ArtworkLoadingStage
import com.example.controllerapp.model.ArtworkQuality
import com.example.controllerapp.model.ArtworkState
import com.example.controllerapp.model.ConnectionHealth
import com.example.controllerapp.model.ConnectionPhase
import com.example.controllerapp.model.ConnectionState
import com.example.controllerapp.model.ControllerSettings
import com.example.controllerapp.model.DailyListenStat
import com.example.controllerapp.model.DiagnosticsState
import com.example.controllerapp.model.HistoryState
import com.example.controllerapp.model.LyricDiagnosticState
import com.example.controllerapp.model.LyricDisplayMode
import com.example.controllerapp.model.LyricLine
import com.example.controllerapp.model.LyricLoadingStage
import com.example.controllerapp.model.LyricsState
import com.example.controllerapp.model.PlaybackHistorySession
import com.example.controllerapp.model.PlaybackPerformanceMode
import com.example.controllerapp.model.PlaybackState
import com.example.controllerapp.model.PlaybackStats
import com.example.controllerapp.model.PlaybackTopArtist
import com.example.controllerapp.model.PlaybackTopTrack
import com.example.controllerapp.protocol.CapabilityPolicy
import com.example.controllerapp.protocol.ControllerProtocolCodec
import com.example.controllerapp.protocol.CurrentWordOrderingFence
import com.example.controllerapp.protocol.LyricsPublicationPolicy
import com.example.controllerapp.protocol.MediaIdentityPolicy
import com.example.controllerapp.protocol.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

interface ControllerServiceActions {
    fun requestReconnect(reason: String, forceScan: Boolean = false)
    fun stopConnection()
    fun startLegacyRfcomm()
    fun stopLegacyRfcomm()
}

class ControllerRepository(
    context: Context,
    val preferences: ControllerPreferences,
    private val historyDao: PlaybackHistoryDao,
    val logStore: ControllerLogStore
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mediaDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Controller-MediaReducer").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val mediaScope = CoroutineScope(SupervisorJob() + mediaDispatcher)
    private val artworkCache = ArtworkCache(appContext)
    private val snapshotStore = NowPlayingSnapshotStore(appContext)
    private val sequence = AtomicLong(0L)
    private val transferLock = Any()
    private var commandSender: ((ByteArray) -> Boolean)? = null
    private var serviceActions: ControllerServiceActions? = null
    private var capabilities = ServerCapabilities()
    private var capabilityDecisionReady = false
    private var capabilityTimeout: Job? = null
    private var currentWordFence = CurrentWordOrderingFence()
    private var trackInfoTransfer: JsonChunkTransfer? = null
    private var lyricWindowTransfer: LyricWindowTransfer? = null
    private var activeLyricWindowRequest: LyricWindowRequest? = null
    private var lyricWindowRequestTimeout: Job? = null
    private var legacyLyricsTransfer: LegacyLyricsTransfer? = null
    private var binaryLyricsTransfer: BinaryLyricsTransfer? = null
    private val binaryLyricsRetryCounts = HashMap<String, Int>()
    private var activeFullLyricsRequest: FullLyricsRequest? = null
    private var fullLyricsRequestStartTimeout: Job? = null
    private var fullLyricsTransferTimeout: Job? = null
    private var legacyLyricsRetryCount = 0
    private var lastLegacyPartialPublishAtMs = 0L
    private val completedFullLyricsKeys = HashSet<String>()
    private val delayedLyricsRetryKeys = HashSet<String>()
    private val qrcWaitRetryCounts = HashMap<String, Int>()
    private val fullLyricsBinaryFallbackKeys = HashSet<String>()
    private var secondaryTransfer: SecondaryTransfer? = null
    private var secondaryRequestInFlightMode: String? = null
    private var secondaryTransferTimeout: Job? = null
    private val pendingSecondaryModes = ArrayDeque<String>()
    private val completedSecondaryKeys = HashSet<String>()
    private val requestedSecondaryKeys = HashSet<String>()
    private val secondaryRetryCounts = HashMap<String, Int>()
    private var artworkTransfer: ArtworkTransfer? = null
    private var artworkRequestTimeout: Job? = null
    private var artworkTransferTimeout: Job? = null
    private var hqArtworkPrefetchJob: Job? = null
    private val artworkRequestRetryCounts = HashMap<String, Int>()
    private val artworkRetryCounts = HashMap<String, Int>()
    private val artworkPlaceholderRefreshCounts = HashMap<String, Int>()
    private var textTransfer: TextTransfer? = null
    private var diagnosticTransferTimeout: Job? = null
    private val historyTransfers = HashMap<String, HistoryPayloadTransfer>()
    private val historySyncQueue = ArrayDeque<String>()
    private var historySyncTimeout: Job? = null
    private var volumeSyncTimeout: Job? = null
    private var volumeSyncAttempts = 0
    private val requestedArtwork = HashSet<String>()
    private val artworkRestoreEpoch = AtomicLong(0L)
    private var lastSnapshotSaveAtMs = 0L
    private var logListener: ((String) -> Unit)? = null

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    private val _lyrics = MutableStateFlow(LyricsState())
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    private val _artwork = MutableStateFlow(ArtworkState())
    val artwork: StateFlow<ArtworkState> = _artwork.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsState())
    val diagnostics: StateFlow<DiagnosticsState> = _diagnostics.asStateFlow()

    private val _history = MutableStateFlow(HistoryState())
    val history: StateFlow<HistoryState> = _history.asStateFlow()

    private val _settings = MutableStateFlow(ControllerSettings())
    val settings: StateFlow<ControllerSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            preferences.settings.collectLatest { value ->
                if (_settings.value != value) _settings.value = value
            }
        }
        scope.launch(Dispatchers.IO) {
            historyDao.observeRecent().collectLatest { values ->
                _history.update { it.copy(sessions = values.map(PlaybackHistoryEntity::toModel)) }
            }
        }
        scope.launch {
            snapshotStore.load()?.let { snapshot ->
                _playback.value = snapshot.playback
                _lyrics.value = LyricsState(
                    trackId = snapshot.playback.trackId,
                    currentText = snapshot.lines.firstOrNull()?.text.orEmpty(),
                    windowLines = snapshot.lines,
                    loadingStage = LyricLoadingStage.WINDOW_READY
                )
                if (snapshot.artworkId.isNotBlank()) {
                    restoreCachedArtwork(snapshot.artworkId, restored = true)
                }
            }
        }
        val listener: (String) -> Unit = { line ->
            _diagnostics.update { current ->
                current.copy(recentLogs = (current.recentLogs + line).takeLast(200))
            }
        }
        logListener = listener
        logStore.addListener(listener)
    }

    fun attachTransport(sender: ((ByteArray) -> Boolean)?) {
        commandSender = sender
    }

    fun attachServiceActions(actions: ControllerServiceActions?) {
        serviceActions = actions
    }

    fun updateConnectionPhase(
        phase: ConnectionPhase,
        deviceName: String = _connection.value.deviceName,
        address: String = _connection.value.deviceAddress,
        reason: String = ""
    ) {
        val previous = _connection.value
        val health = when (phase) {
            ConnectionPhase.CONNECTED -> ConnectionHealth.HEALTHY
            ConnectionPhase.DISCONNECTED -> ConnectionHealth.DISCONNECTED
            else -> if (previous.health == ConnectionHealth.STALE) {
                ConnectionHealth.STALE
            } else {
                ConnectionHealth.SUSPECT
            }
        }
        _connection.value = previous.copy(
            phase = phase,
            health = health,
            deviceName = deviceName.ifBlank { "Sony PlayerAgent" },
            deviceAddress = address,
            characteristicReady = phase == ConnectionPhase.CONNECTED,
            lastReconnectReason = reason.ifBlank { previous.lastReconnectReason }
        )
        if (phase == ConnectionPhase.DISCONNECTED) {
            capabilities = ServerCapabilities()
            capabilityDecisionReady = false
            capabilityTimeout?.cancel()
        }
    }

    fun onTransportReady(deviceName: String, address: String, mtu: Int) {
        val nextGeneration = _connection.value.generation + 1L
        _connection.value = _connection.value.copy(
            phase = ConnectionPhase.CONNECTED,
            health = ConnectionHealth.HEALTHY,
            deviceName = deviceName,
            deviceAddress = address,
            mtu = mtu,
            characteristicReady = true,
            generation = nextGeneration,
            reconnectAttempt = 0,
            lastNotifyElapsedMs = SystemClock.elapsedRealtime()
        )
        capabilities = ServerCapabilities()
        capabilityDecisionReady = false
        resetTransfers("new connection")
        currentWordFence = CurrentWordOrderingFence()
        sendCapabilities()
        scope.launch {
            delay(80L)
            sendCommand("GET_PLAYBACK_STATE")
            delay(80L)
            requestVolume()
        }
    }

    fun markNotifyActivity() {
        _connection.update {
            it.copy(
                health = ConnectionHealth.HEALTHY,
                lastNotifyElapsedMs = SystemClock.elapsedRealtime()
            )
        }
    }

    fun markHealth(value: ConnectionHealth, reason: String = "") {
        _connection.update {
            it.copy(
                health = value,
                lastReconnectReason = reason.ifBlank { it.lastReconnectReason }
            )
        }
    }

    fun setReconnectAttempt(attempt: Int, reason: String) {
        _connection.update {
            it.copy(
                reconnectAttempt = attempt.coerceAtLeast(0),
                lastReconnectReason = reason
            )
        }
    }

    fun recordSelfHealing(action: String) {
        if (action.isBlank()) return
        logStore.append("[SelfHealing] $action")
        _diagnostics.update {
            it.copy(selfHealingActions = (it.selfHealingActions + action).takeLast(50))
        }
    }

    fun onWriteResult(success: Boolean, payload: ByteArray) {
        if (!success) {
            val command = runCatching {
                JSONObject(String(payload, Charsets.UTF_8)).optString("cmd")
            }.getOrDefault("unknown")
            reportIssue("命令写入失败：$command")
        }
    }

    fun handleNotification(value: ByteArray) {
        markNotifyActivity()
        val snapshot = value.copyOf()
        mediaScope.launch { handleNotificationOnMedia(snapshot) }
    }

    private fun handleNotificationOnMedia(value: ByteArray) {
        when (value.firstOrNull()?.toInt()?.and(0xff)) {
            ControllerProtocolCodec.ALBUM_ART_MAGIC -> handleArtworkBinaryChunk(value)
            ControllerProtocolCodec.FULL_LYRICS_MAGIC -> handleLyricsBinaryChunk(value)
            else -> {
                val objectValue = runCatching {
                    JSONObject(String(value, Charsets.UTF_8))
                }.getOrElse {
                    reportIssue("状态消息解析失败")
                    return
                }
                handleJson(objectValue)
            }
        }
    }

    fun requestReconnect(reason: String = "manual", forceScan: Boolean = true) {
        serviceActions?.requestReconnect(reason, forceScan)
    }

    fun stopConnection() = serviceActions?.stopConnection()
    fun startLegacyRfcomm() = serviceActions?.startLegacyRfcomm()
    fun stopLegacyRfcomm() = serviceActions?.stopLegacyRfcomm()

    fun playPause() = sendCommand("PLAY_PAUSE")
    fun previous() = sendCommand("PREVIOUS")
    fun next() = sendCommand("NEXT")
    fun requestPlaybackState() = sendCommand("GET_PLAYBACK_STATE")
    fun requestVolume() {
        mediaScope.launch { requestVolumeOnMedia(force = false) }
    }

    private fun requestVolumeOnMedia(force: Boolean) {
        if (!force && volumeSyncTimeout?.isActive == true) return
        val sent = sendCommand("GET_VOLUME")
        if (!sent) return
        volumeSyncTimeout?.cancel()
        volumeSyncTimeout = mediaScope.launch {
            delay(VOLUME_SYNC_TIMEOUT_MS)
            if (volumeSyncAttempts < MAX_VOLUME_SYNC_RETRIES) {
                volumeSyncAttempts += 1
                logStore.append("[Volume] response timeout, retry=$volumeSyncAttempts")
                requestVolumeOnMedia(force = true)
            } else {
                volumeSyncAttempts = 0
                volumeSyncTimeout = null
                reportIssue("Sony 音量状态响应超时")
            }
        }
    }
    fun healthProbe() =
        if (_connection.value.serverSupportsV2) sendCommand("PING") else requestPlaybackState()

    fun seekTo(positionMs: Long) =
        sendCommand("SEEK_TO", JSONObject().put("position", positionMs.coerceAtLeast(0L)))

    fun setVolume(value: Int) =
        sendCommand("SET_VOLUME", JSONObject().put("volume", value.coerceAtLeast(0)))

    fun requestFullLyrics(forceLegacy: Boolean = false) {
        mediaScope.launch { requestFullLyricsOnMedia(forceLegacy, force = false) }
    }

    fun ensureFullLyrics() {
        mediaScope.launch { requestFullLyricsOnMedia(forceLegacy = false, force = false) }
    }

    fun retryLyrics() {
        mediaScope.launch {
            val trackId = _playback.value.trackId
            if (trackId.isBlank()) return@launch
            cancelFullLyricsTimers()
            activeFullLyricsRequest = null
            legacyLyricsTransfer = null
            binaryLyricsTransfer = null
            legacyLyricsRetryCount = 0
            fullLyricsBinaryFallbackKeys.remove(lyricsKey(trackId, _playback.value.generation))
            _lyrics.update {
                it.copy(
                    partialFullLines = emptyList(),
                    loadingStage = LyricLoadingStage.WAITING_QQ_QRC,
                    receivedChunks = 0,
                    expectedChunks = 0,
                    transferId = "",
                    retryCount = 0,
                    failureReason = ""
                )
            }
            requestLyricWindowOnMedia(force = true)
            requestFullLyricsOnMedia(forceLegacy = false, force = true)
        }
    }

    private fun requestFullLyricsOnMedia(forceLegacy: Boolean, force: Boolean) {
        val trackId = _playback.value.trackId
        if (trackId.isBlank()) return
        if (!capabilityDecisionReady) {
            logStore.append("[Lyrics] request deferred until capability decision trackId=$trackId")
            return
        }
        val generation = _playback.value.generation
        val key = lyricsKey(trackId, generation)
        if (!force && (completedFullLyricsKeys.contains(key) ||
                (_lyrics.value.trackId == trackId && _lyrics.value.isFinal)
            )
        ) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        val active = activeFullLyricsRequest
        if (!force && active != null && active.trackId == trackId &&
            MediaIdentityPolicy.generationMatches(active.generation, generation) &&
            now - active.sentAtElapsedMs < FULL_LYRICS_REQUEST_DEDUP_MS
        ) {
            logStore.append("[Lyrics] request deduplicated trackId=$trackId")
            return
        }
        if (forceLegacy) fullLyricsBinaryFallbackKeys += key
        val useBinary = !forceLegacy &&
            key !in fullLyricsBinaryFallbackKeys && capabilities.fullLyricsZlib
        val extra = JSONObject()
            .put("trackId", trackId)
            .put("positionMs", displayedPositionMs())
            .put("includeWordsAroundCurrent", true)
        if (useBinary) {
            extra.put("format", "zlib-json-v1")
        }
        val attempt = if (active?.trackId == trackId) active.attempt + 1 else 1
        activeFullLyricsRequest = FullLyricsRequest(
            trackId = trackId,
            generation = generation,
            sentAtElapsedMs = now,
            format = if (useBinary) "zlib-json-v1" else "legacy",
            attempt = attempt
        )
        _lyrics.update {
            it.copy(
                loadingStage = LyricLoadingStage.FULL_LYRICS,
                protocolFormat = if (useBinary) "zlib-json-v1" else "legacy",
                retryCount = (attempt - 1).coerceAtLeast(0),
                failureReason = ""
            )
        }
        val sent = sendCommand("GET_FULL_LYRICS", extra)
        logStore.append(
            "[Lyrics] request trackId=$trackId generation=$generation " +
                "format=${if (useBinary) "zlib-json-v1" else "legacy"} attempt=$attempt sent=$sent"
        )
        if (sent) {
            scheduleFullLyricsStartTimeout(activeFullLyricsRequest ?: return)
        } else {
            activeFullLyricsRequest = null
            _lyrics.update {
                it.copy(loadingStage = LyricLoadingStage.FAILED, failureReason = "完整歌词命令发送失败")
            }
        }
    }

    fun requestLyricWindow() {
        mediaScope.launch { requestLyricWindowOnMedia(force = false) }
    }

    private fun requestLyricWindowOnMedia(force: Boolean) {
        val trackId = _playback.value.trackId
        if (trackId.isBlank() || !capabilities.lyricWindow) return
        val generation = _playback.value.generation
        val now = SystemClock.elapsedRealtime()
        val active = activeLyricWindowRequest
        if (!force && ((lyricWindowTransfer?.trackId == trackId) ||
                (active?.trackId == trackId &&
                    MediaIdentityPolicy.generationMatches(active.generation, generation) &&
                    now - active.sentAtElapsedMs < LYRIC_WINDOW_REQUEST_DEDUP_MS))
        ) return
        val attempt = if (active?.trackId == trackId) active.attempt + 1 else 1
        val sent = sendCommand(
            "GET_LYRIC_WINDOW",
            JSONObject()
                .put("trackId", trackId)
                .put("positionMs", displayedPositionMs())
        )
        if (!sent) return
        activeLyricWindowRequest = LyricWindowRequest(trackId, generation, now, attempt)
        lyricWindowRequestTimeout?.cancel()
        lyricWindowRequestTimeout = mediaScope.launch {
            delay(LYRIC_WINDOW_START_TIMEOUT_MS)
            val waiting = activeLyricWindowRequest ?: return@launch
            if (_playback.value.trackId != waiting.trackId ||
                waiting.sentAtElapsedMs != now || lyricWindowTransfer != null ||
                _lyrics.value.isFinal
            ) return@launch
            if (waiting.attempt <= MAX_LYRIC_WINDOW_RETRIES) {
                logStore.append(
                    "[Lyrics] window start timeout trackId=${waiting.trackId} retry=${waiting.attempt}"
                )
                requestLyricWindowOnMedia(force = true)
            } else {
                activeLyricWindowRequest = null
                logStore.append("[Lyrics] window unavailable after retry trackId=${waiting.trackId}")
            }
        }
    }

    fun requestSecondary(mode: String) {
        mediaScope.launch { enqueueSecondaryOnMedia(mode) }
    }

    private fun enqueueSecondaryOnMedia(mode: String) {
        val trackId = _playback.value.trackId
        if (trackId.isBlank() || !_lyrics.value.isFinal || mode !in SECONDARY_MODES) return
        val key = secondaryKey(trackId, mode)
        if (key in completedSecondaryKeys || key in requestedSecondaryKeys ||
            pendingSecondaryModes.contains(mode) || secondaryTransfer?.mode == mode
        ) return
        requestedSecondaryKeys += key
        pendingSecondaryModes += mode
        requestNextSecondaryOnMedia()
    }

    fun requestLyricDiagnostic() {
        val trackId = _playback.value.trackId
        if (trackId.isBlank()) return
        sendCommand("GET_LYRIC_DIAGNOSTIC", JSONObject().put("trackId", trackId))
    }

    fun requestArtwork(quality: ArtworkQuality, forceRefresh: Boolean = false) {
        mediaScope.launch { requestArtworkOnMedia(quality, forceRefresh) }
    }

    private fun requestArtworkOnMedia(
        quality: ArtworkQuality,
        forceRefresh: Boolean = false
    ) {
        val id = _playback.value.trackId
        if (id.isBlank()) return
        val wireQuality = if (quality == ArtworkQuality.HQ) "hq" else "preview"
        val requestKey = "$id|$wireQuality"
        if (!forceRefresh && requestKey in requestedArtwork) {
            logStore.append("[Artwork] request deduplicated id=$id quality=$wireQuality")
            return
        }
        requestedArtwork += requestKey
        _artwork.update {
            it.copy(
                artworkId = id,
                loadingStage = if (quality == ArtworkQuality.HQ) {
                    ArtworkLoadingStage.HQ
                } else {
                    ArtworkLoadingStage.PREVIEW
                },
                failureReason = ""
            )
        }
        val sent = sendCommand(
            "ALBUM_ART_REQUEST",
            JSONObject()
                .put("id", id)
                .put("quality", wireQuality)
                .put("forceRefresh", forceRefresh)
        )
        if (!sent) {
            requestedArtwork.remove(requestKey)
            handleArtworkFailure("封面命令发送失败")
            return
        }
        scheduleArtworkRequestTimeout(id, quality)
        logStore.append("[Artwork] request id=$id quality=$wireQuality force=$forceRefresh")
    }

    fun forceRefreshArtwork() {
        mediaScope.launch {
            val id = _playback.value.trackId
            if (id.isBlank()) return@launch
            val iterator = requestedArtwork.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().startsWith("$id|")) iterator.remove()
            }
            artworkRequestTimeout?.cancel()
            artworkTransferTimeout?.cancel()
            hqArtworkPrefetchJob?.cancel()
            hqArtworkPrefetchJob = null
            artworkTransfer = null
            requestArtworkOnMedia(ArtworkQuality.PREVIEW, forceRefresh = true)
        }
    }

    fun requestSonyLogs(limit: Int = 80) {
        mediaScope.launch {
            requestRemoteText(
                command = "GET_LOGS",
                kind = "log",
                extra = JSONObject().put("limit", limit.coerceIn(1, 200))
            )
        }
    }

    fun requestMediaDump() {
        mediaScope.launch { requestRemoteText("DUMP_MEDIA_FIELDS", "dump") }
    }

    fun syncHistory() {
        if (_history.value.loading) return
        _history.update { it.copy(loading = true, status = "正在同步") }
        scope.launch(Dispatchers.IO) {
            synchronized(historySyncQueue) {
                historySyncQueue.clear()
                historySyncQueue.addAll(listOf("TODAY", "WEEK", "MONTH"))
            }
            val after = historyDao.latestSessionId() ?: 0L
            mediaScope.launch { requestHistorySinceOnMedia(after) }
        }
    }

    fun loadMoreHistory() {
        mediaScope.launch {
            if (_history.value.loading || !_history.value.hasMore) return@launch
            val before = _history.value.sessions.minOfOrNull { it.sessionId } ?: Long.MAX_VALUE
            _history.update { it.copy(loading = true, status = "正在加载更多") }
            val sent = sendCommand(
                "GET_PLAY_HISTORY_PAGE",
                JSONObject()
                    .put("requestId", requestId("page"))
                    .put("beforeSessionId", before)
                    .put("limit", 50)
            )
            if (sent) {
                scheduleHistoryTimeout("更多历史")
            } else {
                _history.update { it.copy(loading = false, status = "Sony 未连接") }
            }
        }
    }

    fun clearLocalHistory() {
        scope.launch(Dispatchers.IO) {
            historyDao.clear()
            _history.update { it.copy(stats = emptyMap(), status = "本地缓存已清理") }
        }
    }

    fun clearArtworkCache() {
        val id = _playback.value.trackId
        scope.launch {
            if (id.isNotBlank()) artworkCache.remove(id)
            _artwork.update {
                it.copy(
                    bitmap = null,
                    quality = ArtworkQuality.PLACEHOLDER,
                    enhancementMessage = "缓存已清理"
                )
            }
        }
    }

    fun clearLogs() {
        logStore.clear()
        _diagnostics.update { it.copy(recentLogs = emptyList()) }
    }

    fun updateExperienceMode(value: AppExperienceMode) {
        scope.launch { preferences.setExperienceMode(value) }
    }

    fun updatePerformanceMode(value: PlaybackPerformanceMode) {
        scope.launch { preferences.setPerformanceMode(value) }
    }

    fun updateAutoReconnect(value: Boolean) {
        scope.launch { preferences.setAutoReconnect(value) }
    }

    fun updateLyricOffset(value: Long) {
        scope.launch { preferences.setLyricOffsetMs(value) }
    }

    fun updateLyricMode(value: LyricDisplayMode) {
        scope.launch { preferences.setLyricDisplayMode(value) }
        if (value.showsTranslation) requestSecondary("translation")
        if (value.showsRomanization) requestSecondary("romanization")
    }

    fun updateArtworkSize(value: Int) {
        scope.launch { preferences.setArtworkDisplaySizeDp(value) }
    }

    fun updateArtworkEnhancement(value: Boolean) {
        scope.launch { preferences.setArtworkEnhancementEnabled(value) }
    }

    fun displayedPositionMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        val state = _playback.value
        if (!state.isPlaying || state.receivedAtElapsedMs <= 0L) return state.positionMs
        return (state.positionMs + nowElapsedMs - state.receivedAtElapsedMs)
            .coerceIn(0L, state.durationMs.coerceAtLeast(0L))
    }

    fun trimMemory(level: Int) = artworkCache.trimMemory(level)

    suspend fun historyArtwork(artworkId: String): Bitmap? {
        if (artworkId.isBlank()) return null
        return artworkCache.load(artworkId, ArtworkQuality.HQ, 128)?.bitmap
            ?: artworkCache.load(artworkId, ArtworkQuality.PREVIEW, 128)?.bitmap
    }

    fun close() {
        logListener?.let(logStore::removeListener)
        logListener = null
        commandSender = null
        serviceActions = null
        scope.cancel()
        mediaScope.cancel()
        mediaDispatcher.close()
    }

    private fun sendCapabilities() {
        capabilityDecisionReady = false
        val extra = JSONObject()
            .put("protocolVersion", 2)
            .put("albumArtBinary", true)
            .put("fullLyricsZlib", true)
            .put("lyricWindow", true)
            .put("ping", true)
            .put("transferRetry", true)
        sendCommand("CLIENT_CAPABILITIES", extra)
        capabilityTimeout?.cancel()
        capabilityTimeout = scope.launch {
            delay(CapabilityPolicy.ACK_TIMEOUT_MS)
            mediaScope.launch {
                val fallback = CapabilityPolicy.fallbackIfUnacknowledged(capabilities)
                if (fallback != capabilities) {
                    capabilities = fallback
                    capabilityDecisionReady = true
                    _connection.update {
                        it.copy(serverProtocolVersion = 1, serverSupportsV2 = false)
                    }
                    logStore.append("[Protocol] capability ACK timeout, legacy fallback")
                    requestCurrentMediaPayloadsOnMedia()
                }
            }
        }
    }

    private fun sendCommand(command: String, extra: JSONObject = JSONObject()): Boolean {
        val sender = commandSender ?: return false
        val value = JSONObject()
            .put("cmd", command)
            .put("seq", sequence.incrementAndGet().toString())
            .put("time", System.currentTimeMillis())
        extra.keys().forEach { key -> value.put(key, extra.opt(key)) }
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        val sent = sender(bytes)
        if (!sent) reportIssue("命令未发送：$command")
        return sent
    }

    private fun handleJson(value: JSONObject) {
        when (val type = value.optString("type")) {
            "clientCapabilitiesAck" -> handleCapabilities(value)
            "pong" -> markHealth(ConnectionHealth.HEALTHY)
            "playbackState" -> handlePlayback(value)
            "trackInfo" -> applyTrackInfo(value)
            "trackInfoStart" -> {
                trackInfoTransfer = JsonChunkTransfer(
                    expectedSize = value.optInt("size"),
                    expectedChunks = value.optInt("chunks")
                )
            }
            "trackInfoChunk" -> appendJsonChunk(trackInfoTransfer, value)
            "trackInfoEnd" -> finishTrackInfoTransfer()
            "currentWord" -> handleCurrentWord(value)
            "volumeState" -> handleVolume(value)
            "lyricWindowStart" -> startLyricWindow(value)
            "lyricWindowChunk" -> appendLyricWindow(value)
            "lyricWindowEnd" -> finishLyricWindow(value)
            "lyricWindowUnavailable" -> handleLyricWindowUnavailable(value)
            "fullLyricsStart" -> startLegacyLyrics(value)
            "fullLyricsChunk" -> appendLegacyLyrics(value)
            "fullLyricsEnd" -> finishLegacyLyrics(value)
            "fullLyricsUnavailable" -> handleLyricsUnavailable(value)
            "fullLyricsBinaryStart" -> startBinaryLyrics(value)
            "fullLyricsBinaryEnd" -> finishBinaryLyrics(value)
            "fullLyricsBinaryError" -> fallbackBinaryLyrics(value.optString("reason"))
            "lyricSecondaryStart" -> startSecondary(value)
            "lyricSecondaryPart" -> appendSecondary(value)
            "lyricSecondaryEnd" -> finishSecondary(value)
            "lyricSecondaryUnavailable", "lyricSecondaryError" ->
                handleSecondaryUnavailable(value)
            "lyricDiagnostic" -> handleLyricDiagnostic(value)
            "lyricDiagnosticUnavailable" -> reportIssue(
                "歌词诊断不可用：${value.optString("reason")}"
            )
            "albumArtOffer" -> handleArtworkOffer(value.optString("id"))
            "albumArtStart" -> startLegacyArtwork(value)
            "albumArtChunk" -> appendLegacyArtwork(value)
            "albumArtEnd" -> finishLegacyArtwork(value)
            "albumArtBinaryStart" -> startBinaryArtwork(value)
            "albumArtBinaryEnd" -> finishBinaryArtwork(value)
            "albumArtBinaryError" -> handleArtworkProtocolError(value)
            "albumArtUnavailable" -> handleArtworkUnavailable(value)
            "logStart", "mediaFieldDumpStart" -> startTextTransfer(type, value)
            "logChunk", "mediaFieldDumpChunk" -> appendTextTransfer(type, value)
            "logEnd", "mediaFieldDumpEnd" -> finishTextTransfer(type, value)
            "mediaFieldDumpError" -> finishRemoteTextWithError(
                "Media Dump失败：${value.optString("message")}"
            )
            "playHistoryPage", "playHistorySince", "playStats" -> handleHistoryPayload(value)
            "historyPayloadStart" -> startHistoryPayload(value)
            "historyPayloadChunk" -> appendHistoryPayload(value)
            "historyPayloadEnd" -> finishHistoryPayload(value)
            "playHistoryError" -> {
                historySyncTimeout?.cancel()
                historySyncTimeout = null
                synchronized(historySyncQueue) { historySyncQueue.clear() }
                _history.update {
                    it.copy(
                        loading = false,
                        status = value.optString("message", "同步失败")
                    )
                }
            }
            else -> logStore.append("[Protocol] unsupported type=$type")
        }
    }

    private fun handleCapabilities(value: JSONObject) {
        capabilities = ServerCapabilities(
            negotiated = true,
            protocolVersion = value.optInt("protocolVersion", 1),
            albumArtBinary = value.optBoolean("albumArtBinary", true),
            fullLyricsZlib = value.optBoolean("fullLyricsZlib"),
            lyricWindow = value.optBoolean("lyricWindow"),
            ping = value.optBoolean("ping"),
            transferRetry = value.optBoolean("transferRetry")
        )
        capabilityDecisionReady = true
        capabilityTimeout?.cancel()
        _connection.update {
            it.copy(
                serverProtocolVersion = capabilities.protocolVersion,
                serverSupportsV2 = capabilities.protocolVersion >= 2
            )
        }
        requestCurrentMediaPayloadsOnMedia()
        logStore.append("[Protocol] V2 capability negotiated")
    }

    private fun requestCurrentMediaPayloadsOnMedia() {
        val trackId = _playback.value.trackId
        if (trackId.isBlank()) return
        requestLyricWindowOnMedia(force = false)
        requestFullLyricsOnMedia(forceLegacy = false, force = false)
        if (_artwork.value.artworkId != trackId || _artwork.value.bitmap == null) {
            requestArtworkOnMedia(ArtworkQuality.PREVIEW)
        }
    }

    private fun handlePlayback(value: JSONObject) {
        val previous = _playback.value
        val trackId = previous.trackId
        val state = previous.copy(
            isPlaying = value.optBoolean("playing"),
            positionMs = value.optLong("position").coerceAtLeast(0L),
            durationMs = value.optLong("duration").coerceAtLeast(0L),
            receivedAtElapsedMs = SystemClock.elapsedRealtime(),
            restoredSnapshot = false
        )
        _playback.value = state
        val lyricText = value.optString("lyric")
        if (lyricText.isNotBlank()) {
            val previousLyricText = _lyrics.value.currentText
            _lyrics.update {
                if (it.trackId == trackId) it.copy(currentText = lyricText) else it
            }
            val lyricState = _lyrics.value
            val retryKey = lyricsKey(trackId, _playback.value.generation)
            if (!lyricState.isFinal && trackId.isNotBlank() &&
                (lyricState.lines.isEmpty() || lyricState.loadingStage == LyricLoadingStage.FAILED ||
                    lyricState.loadingStage == LyricLoadingStage.WAITING_QQ_QRC) &&
                (previousLyricText.isBlank() || retryKey !in delayedLyricsRetryKeys)
            ) {
                delayedLyricsRetryKeys += retryKey
                mediaScope.launch {
                    delay(200L)
                    if (_playback.value.trackId == trackId && !_lyrics.value.isFinal) {
                        requestLyricWindowOnMedia(force = true)
                        requestFullLyricsOnMedia(forceLegacy = false, force = true)
                    }
                }
            }
        }
        val lyricStatus = value.optString("lyricStatus")
        if (lyricStatus.isNotBlank()) {
            _diagnostics.update {
                it.copy(
                    lyricDiagnostic = LyricDiagnosticState(
                        status = lyricStatus,
                        reason = value.optString("lyricReason"),
                        suggestion = value.optString("lyricSuggestion")
                    )
                )
            }
        }
        saveSnapshotDebounced()
    }

    private fun applyTrackInfo(value: JSONObject) {
        val trackId = value.optString("trackId")
        if (trackId.isBlank()) {
            _playback.value = PlaybackState()
            _lyrics.value = LyricsState()
            _artwork.value = ArtworkState()
            scope.launch { snapshotStore.clear() }
            resetTransfers("no active track")
            return
        }
        val old = _playback.value
        val incomingGeneration = value.optLong("generation")
        val changed = MediaIdentityPolicy.isNewMedia(
            currentTrackId = old.trackId,
            currentGeneration = old.generation,
            incomingTrackId = trackId,
            incomingGeneration = incomingGeneration
        )
        _playback.value = old.copy(
            trackId = trackId,
            generation = incomingGeneration.takeIf { it > 0L } ?: old.generation,
            title = value.optString("title").ifBlank { "-" },
            artist = value.optString("artist").ifBlank { "-" },
            album = value.optString("album").ifBlank { "-" },
            restoredSnapshot = false
        )
        _artwork.update {
            if (it.artworkId == trackId) it.copy(restoredSnapshot = false) else it
        }
        if (changed) {
            resetMediaForTrack(trackId)
            restoreCachedArtwork(trackId, restored = false)
            scope.launch {
                delay(40L)
                mediaScope.launch {
                    if (capabilityDecisionReady) requestCurrentMediaPayloadsOnMedia()
                }
            }
        } else if (_artwork.value.artworkId != trackId || _artwork.value.bitmap == null) {
            restoreCachedArtwork(trackId, restored = false)
            requestArtwork(ArtworkQuality.PREVIEW)
        }
        saveSnapshotDebounced()
    }

    private fun handleCurrentWord(value: JSONObject) {
        val trackId = value.optString("trackId")
        if (trackId.isBlank() || trackId != _playback.value.trackId) return
        val accepted = currentWordFence.accept(
            incomingGeneration = value.optLong("generation", -1L),
            incomingSequence = value.optLong("seq", -1L),
            incomingPositionMs = value.optLong("position", -1L)
        ) ?: return
        currentWordFence = accepted
        _lyrics.update {
            if (it.trackId != trackId) {
                it
            } else {
                it.copy(
                    generation = value.optLong("generation", it.generation),
                    currentLineIndex = value.optInt("line", it.currentLineIndex),
                    currentWordIndex = value.optInt("word", it.currentWordIndex),
                    currentWordSequence = value.optLong("seq", it.currentWordSequence),
                    currentWordPositionMs = value.optLong(
                        "position",
                        it.currentWordPositionMs
                    )
                )
            }
        }
    }

    private fun handleVolume(value: JSONObject) {
        volumeSyncTimeout?.cancel()
        volumeSyncTimeout = null
        volumeSyncAttempts = 0
        _playback.update {
            it.copy(
                volumeCurrent = value.optInt("current").coerceAtLeast(0),
                volumeMax = value.optInt("max").coerceAtLeast(0)
            )
        }
    }

    private fun startLyricWindow(value: JSONObject) {
        val trackId = value.optString("trackId")
        val count = value.optInt("count")
        val generation = value.optLong("generation")
        if (!adoptIncomingGeneration(trackId, generation) ||
            count !in 1..5
        ) {
            return
        }
        lyricWindowRequestTimeout?.cancel()
        lyricWindowRequestTimeout = null
        activeLyricWindowRequest = null
        lyricWindowTransfer = LyricWindowTransfer(
            trackId = trackId,
            transferId = value.optString("transferId"),
            generation = generation,
            expectedCount = count
        )
    }

    private fun appendLyricWindow(value: JSONObject) {
        val transfer = lyricWindowTransfer ?: return
        if (!transfer.matches(value, _playback.value.trackId)) return
        ControllerProtocolCodec.decodeLyricLine(value)?.let {
            transfer.lines[it.index] = it
        }
    }

    private fun finishLyricWindow(value: JSONObject) {
        val transfer = lyricWindowTransfer ?: return
        lyricWindowTransfer = null
        if (!transfer.matches(value, _playback.value.trackId) ||
            value.optLong("generation") != transfer.generation ||
            !MediaIdentityPolicy.generationMatches(
                _playback.value.generation,
                transfer.generation
            ) ||
            transfer.lines.size != transfer.expectedCount
        ) {
            return
        }
        publishLyricWindow(transfer.lines.values.sortedBy(LyricLine::index))
    }

    private fun handleLyricWindowUnavailable(value: JSONObject) {
        val trackId = value.optString("trackId")
        if (trackId.isNotBlank() && trackId != _playback.value.trackId) return
        val generation = value.optLong("generation")
        if (generation > 0L && !MediaIdentityPolicy.generationMatches(
                _playback.value.generation,
                generation
            )
        ) return
        lyricWindowTransfer = null
        lyricWindowRequestTimeout?.cancel()
        lyricWindowRequestTimeout = null
        activeLyricWindowRequest = null
        logStore.append(
            "[Lyrics] window unavailable trackId=${_playback.value.trackId} " +
                "reason=${value.optString("reason")}"
        )
    }

    private fun startLegacyLyrics(value: JSONObject) {
        val trackId = value.optString("trackId")
        val count = value.optInt("count")
        if (trackId != _playback.value.trackId || count !in 1..MAX_FULL_LYRIC_LINES) return
        fullLyricsRequestStartTimeout?.cancel()
        fullLyricsRequestStartTimeout = null
        binaryLyricsTransfer = null
        legacyLyricsTransfer = LegacyLyricsTransfer(trackId, count)
        lastLegacyPartialPublishAtMs = 0L
        scheduleFullLyricsTransferTimeout(trackId, transferId = "legacy")
        _lyrics.update {
            it.copy(
                loadingStage = LyricLoadingStage.FULL_LYRICS,
                receivedChunks = 0,
                expectedChunks = count,
                transferId = "legacy",
                protocolFormat = "legacy"
            )
        }
        logStore.append("[Lyrics] legacy start trackId=$trackId lines=$count")
    }

    private fun appendLegacyLyrics(value: JSONObject) {
        val transfer = legacyLyricsTransfer ?: return
        if (value.optString("trackId") != transfer.trackId ||
            transfer.trackId != _playback.value.trackId
        ) {
            return
        }
        ControllerProtocolCodec.decodeLyricLine(value)?.let {
            if (it.index in 0 until transfer.expectedCount) {
                transfer.lines[it.index] = it
                if (it.index == 0 ||
                    it.index == transfer.expectedCount - 1 ||
                    it.index % 10 == 0
                ) {
                    _lyrics.update { state ->
                        state.copy(
                            receivedChunks = transfer.lines.size,
                            expectedChunks = transfer.expectedCount
                        )
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (transfer.lines.size >= 3 &&
                    now - lastLegacyPartialPublishAtMs >= PARTIAL_LYRICS_PUBLISH_MS
                ) {
                    lastLegacyPartialPublishAtMs = now
                    publishPartialLyrics(transfer.lines.values.sortedBy(LyricLine::index))
                }
            }
        }
    }

    private fun finishLegacyLyrics(value: JSONObject) {
        val transfer = legacyLyricsTransfer ?: return
        legacyLyricsTransfer = null
        if (value.optString("trackId") != transfer.trackId ||
            transfer.trackId != _playback.value.trackId
        ) {
            return
        }
        if (transfer.lines.size != transfer.expectedCount) {
            logStore.append(
                "[Lyrics] legacy incomplete trackId=${transfer.trackId} " +
                    "received=${transfer.lines.size}/${transfer.expectedCount}"
            )
            retryLegacyLyrics("legacy incomplete")
            return
        }
        completeFullLyricsTransfer(
            transfer.trackId,
            transfer.lines.values.sortedBy(LyricLine::index),
            "legacy"
        )
    }

    private fun startBinaryLyrics(value: JSONObject) {
        val trackId = value.optString("trackId", value.optString("id"))
        val generation = value.optLong("generation", value.optLong("g"))
        if (!adoptIncomingGeneration(trackId, generation)) {
            return
        }
        val transferId = value.optString("transferId", value.optString("tid"))
        val expectedSize = value.optInt("size", value.optInt("s"))
        val uncompressedSize = value.optInt("uncompressedSize", value.optInt("u"))
        val chunks = value.optInt("chunks", value.optInt("c"))
        val count = value.optInt("count", value.optInt("n"))
        val crc = ControllerProtocolCodec.parseHexCrc(
            value.optString("crc32", value.optString("crc"))
        )
        if (transferId.isBlank() ||
            expectedSize !in 1..24 * 1024 ||
            uncompressedSize !in 1..512 * 1024 ||
            chunks !in 1..0xffff ||
            count <= 0 ||
            crc == null
        ) {
            fallbackBinaryLyrics("invalid start")
            return
        }
        fullLyricsRequestStartTimeout?.cancel()
        fullLyricsRequestStartTimeout = null
        legacyLyricsTransfer = null
        binaryLyricsTransfer = BinaryLyricsTransfer(
            trackId = trackId,
            transferId = transferId,
            generation = generation,
            expectedSize = expectedSize,
            uncompressedSize = uncompressedSize,
            expectedChunks = chunks,
            expectedLineCount = count,
            expectedCrc = crc
        )
        _lyrics.update {
            it.copy(
                loadingStage = LyricLoadingStage.FULL_LYRICS,
                receivedChunks = 0,
                expectedChunks = chunks,
                transferId = transferId,
                protocolFormat = "zlib-json-v1"
            )
        }
        scheduleFullLyricsTransferTimeout(trackId, transferId)
        logStore.append(
            "[Lyrics] binary start trackId=$trackId transferId=$transferId chunks=$chunks lines=$count"
        )
    }

    private fun handleLyricsBinaryChunk(value: ByteArray) {
        val chunk = ControllerProtocolCodec.decodeBinaryChunk(
            value,
            ControllerProtocolCodec.FULL_LYRICS_MAGIC
        ) ?: return
        val transfer = binaryLyricsTransfer ?: return
        if (chunk.kindCode != 1 ||
            transfer.trackId != _playback.value.trackId ||
            chunk.total != transfer.expectedChunks
        ) {
            return
        }
        transfer.chunks[chunk.index] = chunk.payload
        if (chunk.index == 0 || chunk.index == chunk.total - 1 || chunk.index % 10 == 0) {
            _lyrics.update {
                it.copy(
                    receivedChunks = transfer.chunks.size,
                    expectedChunks = transfer.expectedChunks
                )
            }
        }
    }

    private fun finishBinaryLyrics(value: JSONObject) {
        val transfer = binaryLyricsTransfer ?: return
        val trackId = value.optString("trackId", value.optString("id"))
        val transferId = value.optString("transferId", value.optString("tid"))
        val generation = value.optLong("generation", value.optLong("g"))
        if (trackId != transfer.trackId ||
            transferId != transfer.transferId ||
            generation != transfer.generation ||
            trackId != _playback.value.trackId
        ) {
            return
        }
        val missing = ControllerProtocolCodec.missingIndexes(
            transfer.chunks,
            transfer.expectedChunks
        )
        if (missing.isNotEmpty()) {
            retryBinaryLyrics(transfer, missing, missing.size > 32, "missing chunks")
            return
        }
        fullLyricsTransferTimeout?.cancel()
        fullLyricsTransferTimeout = null
        scope.launch(Dispatchers.Default) {
            val compressed = ControllerProtocolCodec.reassemble(
                transfer.chunks,
                transfer.expectedChunks
            )
            val reason = when {
                compressed == null -> "reassemble failed"
                compressed.size != transfer.expectedSize -> "size mismatch"
                ControllerProtocolCodec.crc32(compressed) != transfer.expectedCrc -> "crc mismatch"
                else -> null
            }
            if (reason != null) {
                mediaScope.launch {
                    if (binaryLyricsTransfer?.transferId == transfer.transferId) {
                        retryBinaryLyrics(transfer, emptyList(), true, reason)
                    }
                }
                return@launch
            }
            val raw = ControllerProtocolCodec.zlibDecompress(
                compressed!!,
                transfer.uncompressedSize
            )
            val decoded = raw?.let(ControllerProtocolCodec::decodeLyricPayload)
            if (decoded == null ||
                decoded.first != transfer.trackId ||
                decoded.second != transfer.generation ||
                decoded.third.size != transfer.expectedLineCount
            ) {
                mediaScope.launch {
                    if (binaryLyricsTransfer?.transferId == transfer.transferId) {
                        retryBinaryLyrics(transfer, emptyList(), true, "decode failed")
                    }
                }
                return@launch
            }
            if (_playback.value.trackId == transfer.trackId &&
                MediaIdentityPolicy.generationMatches(
                    _playback.value.generation,
                    transfer.generation
                )
            ) {
                mediaScope.launch publishLyrics@{
                    if (_playback.value.trackId != transfer.trackId ||
                        !MediaIdentityPolicy.generationMatches(
                            _playback.value.generation,
                            transfer.generation
                        ) || binaryLyricsTransfer?.transferId != transfer.transferId
                    ) return@publishLyrics
                    binaryLyricsRetryCounts.remove(transfer.transferId)
                    binaryLyricsTransfer = null
                    completeFullLyricsTransfer(
                        transfer.trackId,
                        decoded.third,
                        "zlib-json-v1"
                    )
                }
            }
        }
    }

    private fun retryBinaryLyrics(
        transfer: BinaryLyricsTransfer,
        missing: List<Int>,
        retryAll: Boolean,
        reason: String
    ) {
        val count = binaryLyricsRetryCounts[transfer.transferId] ?: 0
        if (!capabilities.transferRetry || count >= 1) {
            fallbackBinaryLyrics(reason)
            return
        }
        binaryLyricsRetryCounts[transfer.transferId] = count + 1
        sendCommand(
            "RETRY_TRANSFER",
            JSONObject()
                .put("trackId", transfer.trackId)
                .put("transferId", transfer.transferId)
                .put("missing", ControllerProtocolCodec.missingArray(missing))
                .put("retryAll", retryAll)
        )
        scheduleFullLyricsTransferTimeout(transfer.trackId, transfer.transferId)
        _lyrics.update { it.copy(retryCount = count + 1, failureReason = reason) }
        logStore.append(
            "[Lyrics] binary retry transferId=${transfer.transferId} " +
                "retryAll=$retryAll missing=${missing.size} reason=$reason"
        )
    }

    private fun fallbackBinaryLyrics(reason: String) {
        val trackId = binaryLyricsTransfer?.trackId ?: _playback.value.trackId
        val generation = binaryLyricsTransfer?.generation ?: _playback.value.generation
        binaryLyricsTransfer = null
        fullLyricsTransferTimeout?.cancel()
        fullLyricsTransferTimeout = null
        fullLyricsBinaryFallbackKeys += lyricsKey(trackId, generation)
        activeFullLyricsRequest = null
        logStore.append("[Lyrics] binary fallback reason=$reason")
        if (trackId == _playback.value.trackId) {
            requestFullLyricsOnMedia(forceLegacy = true, force = true)
        }
    }

    private fun handleLyricsUnavailable(value: JSONObject) {
        val trackId = value.optString("trackId")
        if (trackId != _playback.value.trackId) return
        val generation = value.optLong("generation")
        if (generation > 0L && !MediaIdentityPolicy.generationMatches(
                _playback.value.generation,
                generation
            )
        ) return
        cancelFullLyricsTimers()
        activeFullLyricsRequest = null
        legacyLyricsTransfer = null
        binaryLyricsTransfer = null
        val reason = value.optString("reason", value.optString("lyricStatus"))
        val waitingForQrc = reason.contains("loading", true) ||
            reason.contains("waiting", true) ||
            reason.contains("pending", true)
        _lyrics.update {
            it.copy(
                loadingStage = if (waitingForQrc) {
                    LyricLoadingStage.WAITING_QQ_QRC
                } else {
                    LyricLoadingStage.FAILED
                },
                failureReason = reason
            )
        }
        logStore.append("[Lyrics] unavailable trackId=$trackId reason=$reason")
        if (waitingForQrc) scheduleQrcWaitRetry(trackId)
    }

    private fun publishLyricWindow(lines: List<LyricLine>) {
        val trackId = _playback.value.trackId
        if (trackId.isBlank()) return
        _lyrics.update {
            val localIndex = findCurrentLine(lines, displayedPositionMs())
            LyricsPublicationPolicy.publishWindow(
                state = it,
                trackId = trackId,
                generation = _playback.value.generation,
                lines = lines,
                currentLineIndex = lines.getOrNull(localIndex)?.index ?: -1
            )
        }
        logStore.append("[Lyrics] window published trackId=$trackId lines=${lines.size}")
        saveSnapshotDebounced()
    }

    private fun publishPartialLyrics(lines: List<LyricLine>) {
        val trackId = _playback.value.trackId
        if (trackId.isBlank() || _lyrics.value.isFinal) return
        _lyrics.update {
            LyricsPublicationPolicy.publishPartial(
                state = it,
                trackId = trackId,
                generation = _playback.value.generation,
                lines = lines
            )
        }
    }

    private fun completeFullLyricsTransfer(
        trackId: String,
        lines: List<LyricLine>,
        format: String
    ) {
        if (trackId != _playback.value.trackId || lines.isEmpty()) return
        cancelFullLyricsTimers()
        activeFullLyricsRequest = null
        legacyLyricsTransfer = null
        binaryLyricsTransfer = null
        legacyLyricsRetryCount = 0
        val sorted = lines.sortedBy(LyricLine::index)
        completedFullLyricsKeys += lyricsKey(trackId, _playback.value.generation)
        qrcWaitRetryCounts.remove(lyricsKey(trackId, _playback.value.generation))
        _lyrics.update {
            val localIndex = findCurrentLine(sorted, displayedPositionMs())
            LyricsPublicationPolicy.publishFull(
                state = it,
                trackId = trackId,
                generation = _playback.value.generation,
                lines = sorted,
                currentLineIndex = sorted.getOrNull(localIndex)?.index ?: -1,
                format = format
            )
        }
        logStore.append("[Lyrics] full published trackId=$trackId lines=${sorted.size} format=$format")
        val mode = _settings.value.lyricDisplayMode
        if (mode.showsTranslation) enqueueSecondaryOnMedia("translation")
        if (mode.showsRomanization) enqueueSecondaryOnMedia("romanization")
        saveSnapshotDebounced()
    }

    private fun scheduleFullLyricsStartTimeout(request: FullLyricsRequest) {
        fullLyricsRequestStartTimeout?.cancel()
        fullLyricsRequestStartTimeout = mediaScope.launch {
            delay(FULL_LYRICS_START_TIMEOUT_MS)
            val active = activeFullLyricsRequest ?: return@launch
            if (active.trackId != request.trackId ||
                active.generation != request.generation ||
                active.sentAtElapsedMs != request.sentAtElapsedMs ||
                legacyLyricsTransfer != null || binaryLyricsTransfer != null ||
                _playback.value.trackId != request.trackId
            ) return@launch
            if (active.attempt <= MAX_FULL_LYRICS_START_RETRIES) {
                logStore.append(
                    "[Lyrics] start timeout trackId=${active.trackId} retry=${active.attempt}"
                )
                requestFullLyricsOnMedia(
                    forceLegacy = active.format == "legacy",
                    force = true
                )
            } else {
                activeFullLyricsRequest = null
                _lyrics.update {
                    it.copy(
                        loadingStage = LyricLoadingStage.FAILED,
                        failureReason = "Sony 未开始发送完整歌词",
                        retryCount = active.attempt - 1
                    )
                }
                logStore.append("[Lyrics] start timeout exhausted trackId=${active.trackId}")
            }
        }
    }

    private fun scheduleFullLyricsTransferTimeout(trackId: String, transferId: String) {
        fullLyricsTransferTimeout?.cancel()
        fullLyricsTransferTimeout = mediaScope.launch {
            delay(FULL_LYRICS_TRANSFER_TIMEOUT_MS)
            if (_playback.value.trackId != trackId) return@launch
            val binary = binaryLyricsTransfer
            if (binary != null && binary.transferId == transferId) {
                val missing = ControllerProtocolCodec.missingIndexes(
                    binary.chunks,
                    binary.expectedChunks
                )
                retryBinaryLyrics(
                    binary,
                    missing,
                    missing.isEmpty() || missing.size > 32,
                    "transfer timeout"
                )
                return@launch
            }
            if (transferId == "legacy" && legacyLyricsTransfer?.trackId == trackId) {
                retryLegacyLyrics("legacy transfer timeout")
            }
        }
    }

    private fun retryLegacyLyrics(reason: String) {
        fullLyricsTransferTimeout?.cancel()
        fullLyricsTransferTimeout = null
        legacyLyricsTransfer = null
        if (legacyLyricsRetryCount >= MAX_LEGACY_LYRICS_RETRIES) {
            activeFullLyricsRequest = null
            _lyrics.update {
                it.copy(
                    loadingStage = LyricLoadingStage.FAILED,
                    failureReason = reason,
                    retryCount = legacyLyricsRetryCount
                )
            }
            logStore.append("[Lyrics] legacy failed reason=$reason")
            return
        }
        legacyLyricsRetryCount += 1
        logStore.append("[Lyrics] legacy retry=$legacyLyricsRetryCount reason=$reason")
        requestFullLyricsOnMedia(forceLegacy = true, force = true)
    }

    private fun cancelFullLyricsTimers() {
        fullLyricsRequestStartTimeout?.cancel()
        fullLyricsRequestStartTimeout = null
        fullLyricsTransferTimeout?.cancel()
        fullLyricsTransferTimeout = null
    }

    private fun lyricsKey(trackId: String, generation: Long) = "$trackId|$generation"

    private fun scheduleQrcWaitRetry(trackId: String) {
        val generation = _playback.value.generation
        val key = lyricsKey(trackId, generation)
        val retry = qrcWaitRetryCounts[key] ?: 0
        if (retry >= MAX_QRC_WAIT_RETRIES) {
            logStore.append("[Lyrics] QQ QRC wait retry exhausted trackId=$trackId")
            return
        }
        qrcWaitRetryCounts[key] = retry + 1
        mediaScope.launch {
            delay(QRC_WAIT_RETRY_DELAYS_MS[retry])
            if (_playback.value.trackId != trackId ||
                !MediaIdentityPolicy.generationMatches(
                    _playback.value.generation,
                    generation
                ) || _lyrics.value.isFinal
            ) return@launch
            logStore.append("[Lyrics] QQ QRC wait retry=${retry + 1} trackId=$trackId")
            requestLyricWindowOnMedia(force = true)
            requestFullLyricsOnMedia(forceLegacy = false, force = true)
        }
    }

    private fun secondaryKey(trackId: String, mode: String) = "$trackId|$mode"

    private fun startSecondary(value: JSONObject) {
        val trackId = value.optString("trackId")
        val transferId = value.optString("transferId")
        val mode = value.optString("mode")
        val expectedItems = value.optInt("itemCount")
        if (trackId != _playback.value.trackId || !_lyrics.value.isFinal ||
            transferId.isBlank() || mode !in SECONDARY_MODES || expectedItems <= 0 ||
            (secondaryRequestInFlightMode != null && secondaryRequestInFlightMode != mode)
        ) return
        secondaryTransferTimeout?.cancel()
        secondaryRequestInFlightMode = null
        secondaryTransfer = SecondaryTransfer(
            trackId = trackId,
            transferId = transferId,
            mode = mode,
            expectedItems = expectedItems
        )
        scheduleSecondaryTimeout(trackId, mode, transferId)
        logStore.append(
            "[Lyrics] secondary start trackId=$trackId mode=$mode items=$expectedItems"
        )
    }

    private fun appendSecondary(value: JSONObject) {
        val transfer = secondaryTransfer ?: return
        if (!transfer.matches(value, _playback.value.trackId)) return
        val line = value.optInt("lineIndex", -1)
        val part = value.optInt("partIndex", -1)
        val count = value.optInt("partCount", -1)
        if (line < 0 || part < 0 || count <= 0) return
        val lineParts = transfer.parts.getOrPut(line) { SecondaryLineParts(count) }
        if (lineParts.expectedCount == count) {
            lineParts.parts[part] = value.optString("text")
        }
    }

    private fun finishSecondary(value: JSONObject) {
        val transfer = secondaryTransfer ?: return
        if (!transfer.matches(value, _playback.value.trackId)) return
        val secondary = transfer.parts.mapNotNull { (line, parts) ->
            if (parts.parts.size != parts.expectedCount) {
                null
            } else {
                line to buildString {
                    repeat(parts.expectedCount) { append(parts.parts[it].orEmpty()) }
                }
            }
        }.toMap()
        if (secondary.size != transfer.expectedItems) {
            secondaryTransfer = null
            secondaryTransferTimeout?.cancel()
            retrySecondaryOnMedia(transfer.mode, "incomplete ${secondary.size}/${transfer.expectedItems}")
            return
        }
        secondaryTransfer = null
        secondaryTransferTimeout?.cancel()
        secondaryTransferTimeout = null
        _lyrics.update { state ->
            state.copy(
                fullLines = state.fullLines.map { line ->
                    val text = ControllerProtocolCodec.sanitizeSecondary(secondary[line.index])
                    when (transfer.mode) {
                        "translation" -> line.copy(translation = text)
                        "romanization" -> line.copy(romanization = text)
                        else -> line
                    }
                }
            )
        }
        val key = secondaryKey(transfer.trackId, transfer.mode)
        completedSecondaryKeys += key
        requestedSecondaryKeys.remove(key)
        secondaryRetryCounts.remove(key)
        logStore.append(
            "[Lyrics] secondary published trackId=${transfer.trackId} " +
                "mode=${transfer.mode} items=${secondary.size}"
        )
        saveSnapshotDebounced()
        requestNextSecondaryOnMedia()
    }

    private fun handleSecondaryUnavailable(value: JSONObject) {
        val trackId = value.optString("trackId", _playback.value.trackId)
        val mode = value.optString("mode", secondaryTransfer?.mode.orEmpty())
        secondaryTransfer = null
        secondaryRequestInFlightMode = null
        secondaryTransferTimeout?.cancel()
        secondaryTransferTimeout = null
        if (trackId.isNotBlank() && mode in SECONDARY_MODES) {
            val key = secondaryKey(trackId, mode)
            completedSecondaryKeys += key
            requestedSecondaryKeys.remove(key)
        }
        logStore.append(
            "[Lyrics] secondary unavailable trackId=$trackId mode=$mode " +
                "reason=${value.optString("reason", value.optString("message"))}"
        )
        requestNextSecondaryOnMedia()
    }

    private fun requestNextSecondaryOnMedia() {
        if (secondaryTransfer != null || secondaryRequestInFlightMode != null) return
        val mode = pendingSecondaryModes.removeFirstOrNull() ?: return
        val trackId = _playback.value.trackId
        if (trackId.isBlank() || !_lyrics.value.isFinal) return
        secondaryRequestInFlightMode = mode
        val sent = sendCommand(
            "GET_LYRIC_SECONDARY",
            JSONObject().put("trackId", trackId).put("mode", mode)
        )
        logStore.append("[Lyrics] secondary request trackId=$trackId mode=$mode sent=$sent")
        if (!sent) {
            secondaryRequestInFlightMode = null
            requestedSecondaryKeys.remove(secondaryKey(trackId, mode))
            requestNextSecondaryOnMedia()
            return
        }
        scheduleSecondaryTimeout(trackId, mode, transferId = "")
    }

    private fun scheduleSecondaryTimeout(trackId: String, mode: String, transferId: String) {
        secondaryTransferTimeout?.cancel()
        secondaryTransferTimeout = mediaScope.launch {
            delay(
                if (transferId.isBlank()) SECONDARY_START_TIMEOUT_MS
                else SECONDARY_TRANSFER_TIMEOUT_MS
            )
            if (_playback.value.trackId != trackId) return@launch
            val stillWaiting = if (transferId.isBlank()) {
                secondaryRequestInFlightMode == mode
            } else {
                secondaryTransfer?.transferId == transferId
            }
            if (!stillWaiting) return@launch
            secondaryTransfer = null
            secondaryRequestInFlightMode = null
            retrySecondaryOnMedia(mode, "timeout")
        }
    }

    private fun retrySecondaryOnMedia(mode: String, reason: String) {
        val trackId = _playback.value.trackId
        val key = secondaryKey(trackId, mode)
        val retry = secondaryRetryCounts[key] ?: 0
        if (trackId.isBlank() || retry >= MAX_SECONDARY_RETRIES) {
            requestedSecondaryKeys.remove(key)
            logStore.append("[Lyrics] secondary failed mode=$mode reason=$reason")
            requestNextSecondaryOnMedia()
            return
        }
        secondaryRetryCounts[key] = retry + 1
        requestedSecondaryKeys.remove(key)
        logStore.append("[Lyrics] secondary retry mode=$mode reason=$reason")
        enqueueSecondaryOnMedia(mode)
    }

    private fun handleLyricDiagnostic(value: JSONObject) {
        if (value.optString("trackId") != _playback.value.trackId) return
        val details = linkedMapOf<String, String>()
        value.keys().forEach { key ->
            if (key !in setOf("type", "trackId", "status", "reason", "suggestion")) {
                details[key] = value.opt(key)?.toString().orEmpty()
            }
        }
        _diagnostics.update {
            it.copy(
                lyricDiagnostic = LyricDiagnosticState(
                    status = value.optString("status"),
                    reason = value.optString("reason"),
                    suggestion = value.optString("suggestion"),
                    details = details
                )
            )
        }
    }

    private fun handleArtworkOffer(id: String) {
        if (id.isBlank() || id != _playback.value.trackId) return
        val current = _artwork.value
        if (current.artworkId == id && current.quality.rank >= ArtworkQuality.HQ.rank) {
            sendCommand(
                "ALBUM_ART_SKIP",
                JSONObject().put("id", id).put("quality", "hq")
            )
            return
        }
        if (current.artworkId == id && current.bitmap != null &&
            !current.cacheRequiresRefresh
        ) {
            // A valid cached Preview is already on screen. Re-requesting it
            // consumes the interactive BLE lane without improving first paint.
            logStore.append("[Artwork] preview cache confirmed id=$id, skip retransmit")
            scheduleHqArtworkPrefetch(id, CACHED_PREVIEW_HQ_DELAY_MS)
            return
        }
        requestArtwork(ArtworkQuality.PREVIEW)
    }

    private fun startLegacyArtwork(value: JSONObject) {
        val id = value.optString("id")
        if (id != _playback.value.trackId) return
        val chunks = value.optInt("chunks", value.optInt("totalChunks"))
        val size = value.optInt("size")
        if (chunks <= 0) return
        artworkRequestTimeout?.cancel()
        artworkRequestTimeout = null
        artworkTransfer = ArtworkTransfer(
            artworkId = id,
            quality = quality(value.optString("quality")),
            transferId = "",
            generation = 0L,
            expectedSize = size,
            expectedChunks = chunks,
            expectedCrc = null,
            binary = false
        )
        logStore.append(
            "[Artwork] legacy start id=$id quality=${wireQuality(quality(value.optString("quality")))} " +
                "chunks=$chunks size=$size"
        )
        scheduleArtworkTransferTimeout(artworkTransfer ?: return)
    }

    private fun appendLegacyArtwork(value: JSONObject) {
        val transfer = artworkTransfer ?: return
        if (transfer.binary ||
            transfer.artworkId != value.optString("id") ||
            transfer.artworkId != _playback.value.trackId
        ) {
            return
        }
        val index = value.optInt("index", -1)
        val data = value.optString("data")
        if (index in 0 until transfer.expectedChunks && data.isNotBlank()) {
            runCatching { Base64.decode(data, Base64.NO_WRAP) }
                .getOrNull()
                ?.let { transfer.chunks[index] = it }
        }
    }

    private fun finishLegacyArtwork(value: JSONObject) {
        val transfer = artworkTransfer ?: return
        if (transfer.binary ||
            value.optString("id") != transfer.artworkId ||
            transfer.artworkId != _playback.value.trackId
        ) {
            return
        }
        artworkTransfer = null
        artworkTransferTimeout?.cancel()
        artworkTransferTimeout = null
        finishArtworkData(transfer)
    }

    private fun startBinaryArtwork(value: JSONObject) {
        val id = value.optString("id")
        val generation = value.optLong("generation")
        if (!adoptIncomingGeneration(id, generation)) {
            return
        }
        val transferId = value.optString("transferId")
        val chunks = value.optInt("chunks")
        val size = value.optInt("size")
        val crc = ControllerProtocolCodec.parseHexCrc(value.optString("crc32"))
        if (transferId.isBlank() || chunks <= 0 || size <= 0 || crc == null) return
        artworkRequestTimeout?.cancel()
        artworkRequestTimeout = null
        artworkTransfer = ArtworkTransfer(
            artworkId = id,
            quality = quality(value.optString("quality")),
            transferId = transferId,
            generation = generation,
            expectedSize = size,
            expectedChunks = chunks,
            expectedCrc = crc,
            binary = true
        )
        _artwork.update {
            it.copy(
                artworkId = id,
                loadingStage = if (quality(value.optString("quality")) == ArtworkQuality.HQ) {
                    ArtworkLoadingStage.HQ
                } else {
                    ArtworkLoadingStage.PREVIEW
                },
                receivedChunks = 0,
                expectedChunks = chunks
            )
        }
        logStore.append(
            "[Artwork] binary start id=$id transferId=$transferId " +
                "quality=${wireQuality(quality(value.optString("quality")))} chunks=$chunks size=$size"
        )
        scheduleArtworkTransferTimeout(artworkTransfer ?: return)
    }

    private fun handleArtworkBinaryChunk(value: ByteArray) {
        val chunk = ControllerProtocolCodec.decodeBinaryChunk(
            value,
            ControllerProtocolCodec.ALBUM_ART_MAGIC
        ) ?: return
        val transfer = artworkTransfer ?: return
        if (!transfer.binary ||
            transfer.artworkId != _playback.value.trackId ||
            chunk.total != transfer.expectedChunks
        ) {
            return
        }
        val expectedQuality = when (transfer.quality) {
            ArtworkQuality.PREVIEW -> 1
            ArtworkQuality.HQ -> 3
            else -> 2
        }
        if (chunk.kindCode != expectedQuality) return
        transfer.chunks[chunk.index] = chunk.payload
        if (chunk.index == 0 || chunk.index == chunk.total - 1 || chunk.index % 10 == 0) {
            _artwork.update {
                it.copy(
                    receivedChunks = transfer.chunks.size,
                    expectedChunks = transfer.expectedChunks
                )
            }
        }
    }

    private fun finishBinaryArtwork(value: JSONObject) {
        val transfer = artworkTransfer ?: return
        if (!transfer.binary ||
            value.optString("id") != transfer.artworkId ||
            value.optString("transferId") != transfer.transferId ||
            value.optLong("generation") != transfer.generation ||
            !MediaIdentityPolicy.transferStillBelongsToCurrentTrack(
                currentTrackId = _playback.value.trackId,
                transferTrackId = transfer.artworkId,
                currentGeneration = _playback.value.generation,
                transferGeneration = transfer.generation
            )
        ) {
            logStore.append(
                "[Artwork] binary end ignored id=${value.optString("id")} " +
                    "transferId=${value.optString("transferId")}"
            )
            return
        }
        if (_playback.value.generation > transfer.generation && transfer.generation > 0L) {
            logStore.append(
                "[Artwork] completing same-track transfer after generation advance " +
                    "${transfer.generation}->${_playback.value.generation}"
            )
        }
        val missing = ControllerProtocolCodec.missingIndexes(
            transfer.chunks,
            transfer.expectedChunks
        )
        if (missing.isNotEmpty()) {
            retryArtwork(transfer, missing, missing.size > 32)
            return
        }
        artworkTransfer = null
        artworkTransferTimeout?.cancel()
        artworkTransferTimeout = null
        finishArtworkData(transfer)
    }

    private fun finishArtworkData(transfer: ArtworkTransfer) {
        scope.launch(Dispatchers.IO) {
            val data = ControllerProtocolCodec.reassemble(
                transfer.chunks,
                transfer.expectedChunks
            )
            val failure = when {
                data == null -> "封面分包不完整"
                transfer.expectedSize > 0 && data.size != transfer.expectedSize -> "封面大小不匹配"
                transfer.expectedCrc != null &&
                    ControllerProtocolCodec.crc32(data) != transfer.expectedCrc -> "封面CRC错误"
                else -> null
            }
            if (failure != null) {
                if (transfer.binary) {
                    mediaScope.launch { retryArtwork(transfer, emptyList(), true) }
                } else {
                    mediaScope.launch { handleArtworkFailure(failure) }
                }
                return@launch
            }
            val bitmap = ArtworkCache.decodeDownsampled(data!!, 780)
            if (bitmap == null || ArtworkPlaceholderPolicy.isLikelyPlaceholder(bitmap)) {
                bitmap?.recycle()
                mediaScope.launch placeholder@{
                    if (_playback.value.trackId != transfer.artworkId) return@placeholder
                    handleArtworkFailure("等待QQ音乐真实封面")
                    val refreshCount =
                        (artworkPlaceholderRefreshCounts[transfer.artworkId] ?: 0) + 1
                    artworkPlaceholderRefreshCounts[transfer.artworkId] = refreshCount
                    if (refreshCount <= MAX_PLACEHOLDER_REFRESHES) {
                        delay(500L * refreshCount)
                        if (_playback.value.trackId == transfer.artworkId) {
                            requestArtworkOnMedia(
                                ArtworkQuality.PREVIEW,
                                forceRefresh = true
                            )
                        }
                    }
                }
                return@launch
            }
            if (!MediaIdentityPolicy.transferStillBelongsToCurrentTrack(
                    currentTrackId = _playback.value.trackId,
                    transferTrackId = transfer.artworkId,
                    currentGeneration = _playback.value.generation,
                    transferGeneration = transfer.generation
                )
            ) {
                // The decode belongs to an obsolete song/connection generation. It was never
                // published or inserted into a cache, so release its native pixel allocation
                // immediately instead of waiting for a later GC during rapid song switching.
                bitmap.recycle()
                return@launch
            }
            artworkCache.store(
                artworkId = transfer.artworkId,
                quality = transfer.quality,
                data = data,
                bitmap = bitmap,
                maximumPixelSize = 780
            )
            mediaScope.launch publishArtwork@{
                if (!MediaIdentityPolicy.transferStillBelongsToCurrentTrack(
                        currentTrackId = _playback.value.trackId,
                        transferTrackId = transfer.artworkId,
                        currentGeneration = _playback.value.generation,
                        transferGeneration = transfer.generation
                    )
                ) {
                    return@publishArtwork
                }
                artworkPlaceholderRefreshCounts.remove(transfer.artworkId)
                artworkRequestRetryCounts.remove(
                    "${transfer.artworkId}|${wireQuality(transfer.quality)}"
                )
                requestedArtwork.remove(
                    "${transfer.artworkId}|${wireQuality(transfer.quality)}"
                )
                _artwork.value = ArtworkState(
                    artworkId = transfer.artworkId,
                    bitmap = bitmap,
                    quality = transfer.quality,
                    loadingStage = if (transfer.quality == ArtworkQuality.HQ) {
                        ArtworkLoadingStage.HQ_READY
                    } else {
                        ArtworkLoadingStage.PREVIEW_READY
                    },
                    receivedChunks = transfer.expectedChunks,
                    expectedChunks = transfer.expectedChunks,
                    cacheRequiresRefresh = false
                )
                logStore.append(
                    "[Artwork] published id=${transfer.artworkId} " +
                        "quality=${wireQuality(transfer.quality)} bytes=${data.size}"
                )
                saveSnapshotDebounced()
                if (transfer.quality == ArtworkQuality.PREVIEW) {
                    scheduleHqArtworkPrefetch(transfer.artworkId, hqDelayMs())
                } else if (_settings.value.artworkEnhancementEnabled) {
                    scope.launch {
                        val enhanced = ArtworkEnhancer.enhance(bitmap)
                        mediaScope.launch publish@{
                            if (_playback.value.trackId != transfer.artworkId ||
                                _artwork.value.bitmap !== bitmap || enhanced === bitmap
                            ) return@publish
                            _artwork.update {
                                it.copy(
                                    bitmap = enhanced,
                                    quality = ArtworkQuality.ENHANCED,
                                    enhancementMessage = "本地增强完成"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun retryArtwork(
        transfer: ArtworkTransfer,
        missing: List<Int>,
        retryAll: Boolean
    ) {
        val count = artworkRetryCounts[transfer.transferId] ?: 0
        if (!capabilities.transferRetry || count >= 1) {
            artworkTransfer = null
            handleArtworkFailure("封面重传失败")
            return
        }
        artworkRetryCounts[transfer.transferId] = count + 1
        sendCommand(
            "RETRY_TRANSFER",
            JSONObject()
                .put("trackId", transfer.artworkId)
                .put("transferId", transfer.transferId)
                .put("missing", ControllerProtocolCodec.missingArray(missing))
                .put("retryAll", retryAll)
        )
        scheduleArtworkTransferTimeout(transfer)
    }

    private fun scheduleArtworkRequestTimeout(
        artworkId: String,
        quality: ArtworkQuality
    ) {
        artworkRequestTimeout?.cancel()
        artworkRequestTimeout = mediaScope.launch {
            delay(ARTWORK_REQUEST_TIMEOUT_MS)
            if (_playback.value.trackId != artworkId || artworkTransfer != null) {
                return@launch
            }
            val key = "$artworkId|${wireQuality(quality)}"
            if (key !in requestedArtwork) return@launch
            requestedArtwork.remove(key)
            val retries = artworkRequestRetryCounts[key] ?: 0
            if (retries < MAX_ARTWORK_REQUEST_RETRIES) {
                artworkRequestRetryCounts[key] = retries + 1
                logStore.append(
                    "[Artwork] start timeout id=$artworkId quality=${wireQuality(quality)} retry=${retries + 1}"
                )
                requestArtworkOnMedia(quality, forceRefresh = true)
                return@launch
            }
            artworkRequestRetryCounts.remove(key)
            if (quality == ArtworkQuality.HQ && _artwork.value.bitmap != null) {
                _artwork.update {
                    it.copy(
                        loadingStage = if (it.quality.rank >= ArtworkQuality.HQ.rank) {
                            ArtworkLoadingStage.HQ_READY
                        } else {
                            ArtworkLoadingStage.PREVIEW_READY
                        },
                        failureReason = "HQ 封面请求超时，已保留当前封面"
                    )
                }
                logStore.append("[Artwork] HQ start timeout, current image retained id=$artworkId")
            } else {
                handleArtworkFailure("Sony 未开始发送封面")
            }
        }
    }

    private fun scheduleArtworkTransferTimeout(transfer: ArtworkTransfer) {
        artworkTransferTimeout?.cancel()
        artworkTransferTimeout = mediaScope.launch {
            delay(ARTWORK_TRANSFER_TIMEOUT_MS)
            val active = artworkTransfer ?: return@launch
            if (active !== transfer || active.artworkId != _playback.value.trackId) return@launch
            if (active.binary) {
                val missing = ControllerProtocolCodec.missingIndexes(
                    active.chunks,
                    active.expectedChunks
                )
                logStore.append(
                    "[Artwork] transfer timeout id=${active.artworkId} " +
                        "received=${active.chunks.size}/${active.expectedChunks}"
                )
                retryArtwork(
                    active,
                    missing,
                    retryAll = missing.isEmpty() || missing.size > 32
                )
            } else {
                artworkTransfer = null
                val key = "legacy|${active.artworkId}|${wireQuality(active.quality)}"
                val retries = artworkRetryCounts[key] ?: 0
                if (retries < MAX_ARTWORK_TRANSFER_RETRIES) {
                    artworkRetryCounts[key] = retries + 1
                    requestedArtwork.remove(
                        "${active.artworkId}|${wireQuality(active.quality)}"
                    )
                    logStore.append(
                        "[Artwork] legacy transfer timeout id=${active.artworkId} retry=${retries + 1}"
                    )
                    requestArtworkOnMedia(active.quality, forceRefresh = true)
                } else {
                    artworkRetryCounts.remove(key)
                    handleArtworkFailure("封面传输超时")
                }
            }
        }
    }

    private fun handleArtworkUnavailable(value: JSONObject) {
        if (value.optString("id") != _playback.value.trackId) return
        val generation = value.optLong("generation")
        if (generation > 0L && !MediaIdentityPolicy.generationMatches(
                _playback.value.generation,
                generation
            )
        ) return
        artworkRequestTimeout?.cancel()
        artworkRequestTimeout = null
        artworkTransferTimeout?.cancel()
        artworkTransferTimeout = null
        requestedArtwork.remove(
            "${value.optString("id")}|${wireQuality(quality(value.optString("quality")))}"
        )
        val reason = value.optString("reason", "封面不可用")
        if (quality(value.optString("quality")) == ArtworkQuality.HQ &&
            _artwork.value.bitmap != null
        ) {
            _artwork.update {
                it.copy(
                    loadingStage = ArtworkLoadingStage.PREVIEW_READY,
                    failureReason = reason
                )
            }
            logStore.append("[Artwork] HQ unavailable, preview retained reason=$reason")
        } else {
            handleArtworkFailure(reason)
        }
    }

    private fun handleArtworkProtocolError(value: JSONObject) {
        val transfer = artworkTransfer
        val id = value.optString("id", value.optString("trackId"))
        val transferId = value.optString("transferId")
        val generation = value.optLong("generation")
        if (id.isNotBlank() && id != _playback.value.trackId) return
        if (generation > 0L && !MediaIdentityPolicy.generationMatches(
                _playback.value.generation,
                generation
            )
        ) return
        if (transfer != null && transferId.isNotBlank() &&
            transferId != transfer.transferId
        ) return
        if (transfer != null && id.isNotBlank() && id != transfer.artworkId) return
        artworkTransfer = null
        handleArtworkFailure(
            value.optString("message", value.optString("reason", "封面协议错误"))
        )
    }

    private fun handleArtworkFailure(reason: String) {
        artworkRequestTimeout?.cancel()
        artworkRequestTimeout = null
        artworkTransferTimeout?.cancel()
        artworkTransferTimeout = null
        artworkTransfer = null
        val id = _playback.value.trackId
        if (id.isNotBlank()) {
            requestedArtwork.remove("$id|preview")
            requestedArtwork.remove("$id|hq")
        }
        _artwork.update {
            it.copy(
                loadingStage = when {
                    it.bitmap == null -> ArtworkLoadingStage.FAILED
                    it.quality.rank >= ArtworkQuality.HQ.rank -> ArtworkLoadingStage.HQ_READY
                    else -> ArtworkLoadingStage.PREVIEW_READY
                },
                failureReason = reason
            )
        }
        reportIssue(reason)
    }

    private fun restoreCachedArtwork(id: String, restored: Boolean) {
        val epoch = artworkRestoreEpoch.incrementAndGet()
        artworkCache.peekBest(id, MAIN_ARTWORK_MAXIMUM_PIXEL_SIZE)?.let { cached ->
            mediaScope.launch { publishCachedArtwork(id, cached, restored, epoch) }
            return
        }
        scope.launch(Dispatchers.IO) {
            val cached = artworkCache.loadBest(id, MAIN_ARTWORK_MAXIMUM_PIXEL_SIZE)
            mediaScope.launch {
                if (cached != null) publishCachedArtwork(id, cached, restored, epoch)
            }
        }
    }

    private fun publishCachedArtwork(
        id: String,
        cached: CachedArtwork,
        restored: Boolean,
        epoch: Long
    ) {
        if (epoch != artworkRestoreEpoch.get() || _playback.value.trackId != id) return
        _artwork.value = ArtworkState(
            artworkId = id,
            bitmap = cached.bitmap,
            quality = cached.quality,
            loadingStage = if (cached.quality == ArtworkQuality.HQ) {
                ArtworkLoadingStage.HQ_READY
            } else {
                ArtworkLoadingStage.PREVIEW_READY
            },
            restoredSnapshot = restored,
            cacheRequiresRefresh = cached.requiresRefresh,
            enhancementMessage = if (cached.requiresRefresh) "缓存展示·后台刷新" else "缓存命中"
        )
        logStore.append(
            "[Artwork] cache ${if (cached.requiresRefresh) "stale" else "hit"} " +
                "id=$id quality=${wireQuality(cached.quality)}"
        )
        if (cached.requiresRefresh && _connection.value.connected) {
            requestArtworkOnMedia(ArtworkQuality.PREVIEW, forceRefresh = true)
        }
    }

    private fun scheduleHqArtworkPrefetch(id: String, delayMs: Long) {
        hqArtworkPrefetchJob?.cancel()
        hqArtworkPrefetchJob = mediaScope.launch {
            delay(delayMs)
            val current = _artwork.value
            if (_playback.value.trackId == id && current.artworkId == id &&
                current.quality.rank < ArtworkQuality.HQ.rank
            ) {
                requestArtworkOnMedia(ArtworkQuality.HQ)
            }
        }
    }

    private fun startTextTransfer(type: String, value: JSONObject) {
        val kind = if (type.startsWith("log")) "log" else "dump"
        val chunks = value.optInt("chunks")
        if (chunks <= 0 && value.optBoolean("empty")) {
            diagnosticTransferTimeout?.cancel()
            diagnosticTransferTimeout = null
            if (kind == "log") {
                _diagnostics.update {
                    it.copy(sonyLogs = "Sony 暂无日志", remoteTransferInProgress = false)
                }
            } else {
                _diagnostics.update { it.copy(remoteTransferInProgress = false) }
            }
            return
        }
        textTransfer = TextTransfer(kind, value.optInt("size"), chunks)
        _diagnostics.update { it.copy(remoteTransferInProgress = true) }
        scheduleDiagnosticTransferTimeout(kind)
    }

    private fun appendTextTransfer(type: String, value: JSONObject) {
        val transfer = textTransfer ?: return
        val kind = if (type.startsWith("log")) "log" else "dump"
        if (kind != transfer.kind) return
        val index = value.optInt("index", -1)
        if (index !in 0 until transfer.expectedChunks) return
        runCatching { Base64.decode(value.optString("data"), Base64.NO_WRAP) }
            .getOrNull()
            ?.let {
                transfer.chunks[index] = it
                scheduleDiagnosticTransferTimeout(kind)
            }
    }

    private fun finishTextTransfer(type: String, value: JSONObject) {
        val transfer = textTransfer
        textTransfer = null
        diagnosticTransferTimeout?.cancel()
        diagnosticTransferTimeout = null
        if (value.optBoolean("empty")) {
            _diagnostics.update {
                it.copy(sonyLogs = "Sony 暂无日志", remoteTransferInProgress = false)
            }
            return
        }
        if (transfer == null ||
            transfer.chunks.size != transfer.expectedChunks ||
            (type.startsWith("log") && transfer.kind != "log") ||
            (type.startsWith("mediaFieldDump") && transfer.kind != "dump")
        ) {
            finishRemoteTextWithError("远程诊断数据分包不完整")
            return
        }
        val bytes = ControllerProtocolCodec.reassemble(
            transfer.chunks,
            transfer.expectedChunks
        ) ?: run {
            finishRemoteTextWithError("远程诊断数据重组失败")
            return
        }
        val text = String(bytes, Charsets.UTF_8)
        _diagnostics.update {
            if (transfer.kind == "log") {
                it.copy(sonyLogs = text, remoteTransferInProgress = false)
            } else {
                it.copy(mediaFieldDump = text, remoteTransferInProgress = false)
            }
        }
    }

    private fun requestRemoteText(
        command: String,
        kind: String,
        extra: JSONObject = JSONObject()
    ) {
        if (_diagnostics.value.remoteTransferInProgress) return
        _diagnostics.update { it.copy(remoteTransferInProgress = true) }
        val sent = sendCommand(command, extra)
        if (!sent) {
            _diagnostics.update { it.copy(remoteTransferInProgress = false) }
            return
        }
        scheduleDiagnosticTransferTimeout(kind)
    }

    private fun scheduleDiagnosticTransferTimeout(kind: String) {
        diagnosticTransferTimeout?.cancel()
        diagnosticTransferTimeout = mediaScope.launch {
            delay(DIAGNOSTIC_TRANSFER_TIMEOUT_MS)
            textTransfer = null
            diagnosticTransferTimeout = null
            _diagnostics.update { it.copy(remoteTransferInProgress = false) }
            reportIssue(if (kind == "log") "Sony 日志获取超时" else "Media Dump 获取超时")
        }
    }

    private fun finishRemoteTextWithError(reason: String) {
        diagnosticTransferTimeout?.cancel()
        diagnosticTransferTimeout = null
        textTransfer = null
        _diagnostics.update { it.copy(remoteTransferInProgress = false) }
        reportIssue(reason)
    }

    private fun startHistoryPayload(value: JSONObject) {
        val requestId = value.optString("requestId")
        val chunks = value.optInt("chunks")
        if (requestId.isBlank() || chunks <= 0) return
        historyTransfers[requestId] = HistoryPayloadTransfer(
            responseType = value.optString("responseType"),
            expectedSize = value.optInt("size"),
            expectedChunks = chunks
        )
        scheduleHistoryTimeout("历史数据传输")
    }

    private fun appendHistoryPayload(value: JSONObject) {
        val requestId = value.optString("requestId")
        val transfer = historyTransfers[requestId] ?: return
        val index = value.optInt("index", -1)
        if (index !in 0 until transfer.expectedChunks) return
        runCatching { Base64.decode(value.optString("data"), Base64.NO_WRAP) }
            .getOrNull()
            ?.let {
                transfer.chunks[index] = it
                scheduleHistoryTimeout("历史数据传输")
            }
    }

    private fun finishHistoryPayload(value: JSONObject) {
        val requestId = value.optString("requestId")
        val transfer = historyTransfers.remove(requestId) ?: return
        val data = ControllerProtocolCodec.reassemble(
            transfer.chunks,
            transfer.expectedChunks
        )
        if (data == null || (transfer.expectedSize > 0 && data.size != transfer.expectedSize)) {
            failHistorySync("历史数据分包不完整")
            return
        }
        val payload = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        if (payload == null) {
            failHistorySync("历史数据解析失败")
            return
        }
        handleHistoryPayload(payload)
    }

    private fun handleHistoryPayload(value: JSONObject) {
        when (value.optString("type")) {
            "playHistoryPage", "playHistorySince" -> {
                val sessions = decodeHistorySessions(value.optJSONArray("items"))
                val responseType = value.optString("type")
                val hasMore = value.optBoolean("hasMore")
                val lastSessionId = value.optLong(
                    "lastSessionId",
                    sessions.maxOfOrNull(PlaybackHistorySession::sessionId) ?: 0L
                )
                scope.launch(Dispatchers.IO) {
                    historyDao.upsert(sessions.map(PlaybackHistoryEntity::from))
                    _history.update {
                        it.copy(
                            status = if (sessions.isEmpty()) "历史已是最新" else "历史同步完成",
                            hasMore = if (responseType == "playHistoryPage") hasMore else it.hasMore
                        )
                    }
                    mediaScope.launch {
                        if (responseType == "playHistorySince" && hasMore && lastSessionId > 0L) {
                            requestHistorySinceOnMedia(lastSessionId)
                        } else {
                            requestNextHistoryStat()
                        }
                    }
                }
            }
            "playStats" -> {
                decodeStats(value)?.let { stats ->
                    _history.update {
                        it.copy(
                            stats = it.stats + (stats.range to stats),
                            status = "${stats.range} 统计已更新"
                        )
                    }
                }
                requestNextHistoryStat()
            }
        }
    }

    private fun requestNextHistoryStat() {
        val range = synchronized(historySyncQueue) {
            if (historySyncQueue.isEmpty()) null else historySyncQueue.removeFirst()
        }
        if (range == null) {
            historySyncTimeout?.cancel()
            historySyncTimeout = null
            _history.update { it.copy(loading = false, status = "同步完成") }
            return
        }
        val sent = sendCommand(
            "GET_PLAY_STATS",
            JSONObject()
                .put("requestId", requestId("stats"))
                .put("range", range)
        )
        if (sent) {
            scheduleHistoryTimeout("$range 统计")
        } else {
            requestNextHistoryStat()
        }
    }

    private fun requestHistorySinceOnMedia(afterSessionId: Long) {
        val sent = sendCommand(
            "GET_PLAY_HISTORY_SINCE",
            JSONObject()
                .put("requestId", requestId("history"))
                .put("afterSessionId", afterSessionId.coerceAtLeast(0L))
                .put("limit", 100)
        )
        if (sent) {
            scheduleHistoryTimeout("历史记录")
        } else {
            synchronized(historySyncQueue) { historySyncQueue.clear() }
            _history.update { it.copy(loading = false, status = "Sony 未连接，保留本地历史") }
        }
    }

    private fun scheduleHistoryTimeout(stage: String) {
        historySyncTimeout?.cancel()
        historySyncTimeout = mediaScope.launch {
            delay(HISTORY_SYNC_TIMEOUT_MS)
            synchronized(historySyncQueue) { historySyncQueue.clear() }
            historyTransfers.clear()
            historySyncTimeout = null
            _history.update {
                it.copy(
                    loading = false,
                    status = "$stage 超时，已保留本地数据，可稍后重试"
                )
            }
            logStore.append("[History] timeout stage=$stage")
        }
    }

    private fun failHistorySync(reason: String) {
        historySyncTimeout?.cancel()
        historySyncTimeout = null
        synchronized(historySyncQueue) { historySyncQueue.clear() }
        _history.update { it.copy(loading = false, status = "$reason，已保留本地数据") }
        logStore.append("[History] failed reason=$reason")
    }

    private fun decodeHistorySessions(array: JSONArray?): List<PlaybackHistorySession> =
        buildList {
            if (array == null) return@buildList
            repeat(array.length()) { index ->
                val value = array.optJSONObject(index) ?: return@repeat
                val sessionId = value.optLong("sessionId")
                if (sessionId <= 0L) return@repeat
                add(
                    PlaybackHistorySession(
                        sessionId = sessionId,
                        trackKey = value.optString("trackKey"),
                        title = value.optString("title"),
                        artist = value.optString("artist"),
                        album = value.optString("album"),
                        artworkId = value.optString("artworkId").ifBlank { null },
                        startedAt = value.optLong("startedAt"),
                        endedAt = value.opt("endedAt")
                            ?.takeUnless { it == JSONObject.NULL }
                            ?.toString()
                            ?.toLongOrNull(),
                        listenedMs = value.optLong("listenedMs"),
                        durationMs = value.optLong("durationMs"),
                        completed = value.optBoolean("completed"),
                        skipped = value.optBoolean("skipped"),
                        countedPlay = value.optBoolean("countedPlay")
                    )
                )
            }
        }

    private fun decodeStats(value: JSONObject): PlaybackStats? {
        val range = value.optString("range")
        if (range.isBlank()) return null
        return PlaybackStats(
            range = range,
            totalListenMs = value.optLong("totalListenMs"),
            playCount = value.optInt("playCount"),
            uniqueTrackCount = value.optInt("uniqueTrackCount"),
            completionRate = value.optDouble("completionRate"),
            skipRate = value.optDouble("skipRate"),
            topTracks = buildList {
                val array = value.optJSONArray("topTracks") ?: JSONArray()
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    add(
                        PlaybackTopTrack(
                            trackKey = item.optString("trackKey"),
                            title = item.optString("title"),
                            artist = item.optString("artist"),
                            listenedMs = item.optLong("listenedMs"),
                            playCount = item.optInt("playCount")
                        )
                    )
                }
            },
            topArtists = buildList {
                val array = value.optJSONArray("topArtists") ?: JSONArray()
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    add(
                        PlaybackTopArtist(
                            artist = item.optString("artist"),
                            listenedMs = item.optLong("listenedMs"),
                            playCount = item.optInt("playCount")
                        )
                    )
                }
            },
            dailyTrend = buildList {
                val array = value.optJSONArray("dailyTrend") ?: JSONArray()
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    add(
                        DailyListenStat(
                            dateKey = item.optString("dateKey"),
                            listenedMs = item.optLong("listenedMs"),
                            playCount = item.optInt("playCount")
                        )
                    )
                }
            }
        )
    }

    private fun appendJsonChunk(transfer: JsonChunkTransfer?, value: JSONObject) {
        transfer ?: return
        val index = value.optInt("index", -1)
        if (index !in 0 until transfer.expectedChunks) return
        runCatching { Base64.decode(value.optString("data"), Base64.NO_WRAP) }
            .getOrNull()
            ?.let { transfer.chunks[index] = it }
    }

    private fun finishTrackInfoTransfer() {
        val transfer = trackInfoTransfer ?: return
        trackInfoTransfer = null
        val data = ControllerProtocolCodec.reassemble(
            transfer.chunks,
            transfer.expectedChunks
        ) ?: return
        if (transfer.expectedSize > 0 && data.size != transfer.expectedSize) return
        runCatching { JSONObject(String(data, Charsets.UTF_8)) }
            .getOrNull()
            ?.let(::applyTrackInfo)
    }

    private fun resetMediaForTrack(trackId: String) {
        resetTransfers("track changed")
        currentWordFence = CurrentWordOrderingFence()
        requestedArtwork.clear()
        binaryLyricsRetryCounts.clear()
        artworkRetryCounts.clear()
        artworkRequestRetryCounts.clear()
        artworkPlaceholderRefreshCounts.clear()
        activeFullLyricsRequest = null
        legacyLyricsRetryCount = 0
        lastLegacyPartialPublishAtMs = 0L
        pendingSecondaryModes.clear()
        secondaryRequestInFlightMode = null
        completedSecondaryKeys.clear()
        requestedSecondaryKeys.clear()
        secondaryRetryCounts.clear()
        delayedLyricsRetryKeys.clear()
        qrcWaitRetryCounts.clear()
        _lyrics.value = LyricsState(
            trackId = trackId,
            loadingStage = LyricLoadingStage.WAITING_QQ_QRC
        )
        _artwork.value = ArtworkState(
            artworkId = trackId,
            loadingStage = ArtworkLoadingStage.PREVIEW
        )
    }

    private fun resetTransfers(reason: String) {
        synchronized(transferLock) {
            cancelFullLyricsTimers()
            activeFullLyricsRequest = null
            legacyLyricsRetryCount = 0
            lyricWindowRequestTimeout?.cancel()
            lyricWindowRequestTimeout = null
            secondaryTransferTimeout?.cancel()
            secondaryTransferTimeout = null
            trackInfoTransfer = null
            lyricWindowTransfer = null
            activeLyricWindowRequest = null
            legacyLyricsTransfer = null
            binaryLyricsTransfer = null
            secondaryTransfer = null
            secondaryRequestInFlightMode = null
            pendingSecondaryModes.clear()
            requestedSecondaryKeys.clear()
            secondaryRetryCounts.clear()
            artworkRequestTimeout?.cancel()
            artworkRequestTimeout = null
            artworkTransferTimeout?.cancel()
            artworkTransferTimeout = null
            hqArtworkPrefetchJob?.cancel()
            hqArtworkPrefetchJob = null
            volumeSyncTimeout?.cancel()
            volumeSyncTimeout = null
            volumeSyncAttempts = 0
            historySyncTimeout?.cancel()
            historySyncTimeout = null
            artworkTransfer = null
            requestedArtwork.clear()
            artworkRequestRetryCounts.clear()
            artworkRetryCounts.clear()
            diagnosticTransferTimeout?.cancel()
            diagnosticTransferTimeout = null
            textTransfer = null
            historyTransfers.clear()
        }
        logStore.append("[Transfer] reset reason=$reason")
    }

    private fun saveSnapshotDebounced() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSnapshotSaveAtMs < 350L) return
        lastSnapshotSaveAtMs = now
        val playback = _playback.value
        val lyrics = _lyrics.value.lines
        val artworkId = _artwork.value.artworkId
        scope.launch {
            delay(350L)
            if (_playback.value.trackId == playback.trackId &&
                MediaIdentityPolicy.generationMatches(
                    _playback.value.generation,
                    playback.generation
                )
            ) {
                snapshotStore.save(playback, lyricWindowAround(lyrics), artworkId)
            }
        }
    }

    private fun lyricWindowAround(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size <= 5) return lines
        val current = findCurrentLine(lines, displayedPositionMs()).coerceAtLeast(0)
        val start = (current - 2).coerceAtLeast(0).coerceAtMost(lines.size - 5)
        return lines.subList(start, start + 5)
    }

    private fun findCurrentLine(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var result = 0
        lines.forEachIndexed { index, line ->
            if (line.timeMs <= positionMs) result = index
        }
        return result
    }

    private fun adoptIncomingGeneration(trackId: String, incomingGeneration: Long): Boolean {
        val playback = _playback.value
        if (trackId.isBlank() || trackId != playback.trackId ||
            !MediaIdentityPolicy.canAdoptIncomingGeneration(
                playback.generation,
                incomingGeneration
            )
        ) return false
        if (incomingGeneration > 0L && incomingGeneration > playback.generation) {
            _playback.value = playback.copy(generation = incomingGeneration)
            _lyrics.update {
                if (it.trackId == trackId) it.copy(generation = incomingGeneration) else it
            }
            logStore.append(
                "[Media] adopted newer generation trackId=$trackId " +
                    "${playback.generation}->$incomingGeneration"
            )
        }
        return true
    }

    private fun quality(value: String): ArtworkQuality =
        if (value == "hq" || value == "full") ArtworkQuality.HQ else ArtworkQuality.PREVIEW

    private fun wireQuality(value: ArtworkQuality): String =
        if (value.rank >= ArtworkQuality.HQ.rank) "hq" else "preview"

    private fun hqDelayMs(): Long = when (_settings.value.performanceMode) {
        PlaybackPerformanceMode.SMOOTH -> 750L
        PlaybackPerformanceMode.POWER_SAVING -> 2_500L
        PlaybackPerformanceMode.AUTOMATIC -> {
            val powerSaving = appContext.getSystemService(PowerManager::class.java)
                ?.isPowerSaveMode == true
            if (powerSaving) 2_500L else 1_200L
        }
    }

    private fun reportIssue(message: String) {
        if (message.isBlank()) return
        logStore.append("[Issue] $message")
        _diagnostics.update { it.copy(lastIssue = message) }
    }

    private fun requestId(prefix: String) =
        "$prefix-${System.currentTimeMillis()}-${sequence.incrementAndGet()}"

    private data class JsonChunkTransfer(
        val expectedSize: Int,
        val expectedChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = HashMap()
    )

    private data class LyricWindowTransfer(
        val trackId: String,
        val transferId: String,
        val generation: Long,
        val expectedCount: Int,
        val lines: MutableMap<Int, LyricLine> = HashMap()
    ) {
        fun matches(value: JSONObject, currentTrackId: String): Boolean =
            trackId == currentTrackId &&
                value.optString("trackId") == trackId &&
                value.optString("transferId") == transferId
    }

    private data class LyricWindowRequest(
        val trackId: String,
        val generation: Long,
        val sentAtElapsedMs: Long,
        val attempt: Int
    )

    private data class LegacyLyricsTransfer(
        val trackId: String,
        val expectedCount: Int,
        val lines: MutableMap<Int, LyricLine> = HashMap()
    )

    private data class FullLyricsRequest(
        val trackId: String,
        val generation: Long,
        val sentAtElapsedMs: Long,
        val format: String,
        val attempt: Int
    )

    private data class BinaryLyricsTransfer(
        val trackId: String,
        val transferId: String,
        val generation: Long,
        val expectedSize: Int,
        val uncompressedSize: Int,
        val expectedChunks: Int,
        val expectedLineCount: Int,
        val expectedCrc: Long,
        val chunks: MutableMap<Int, ByteArray> = HashMap()
    )

    private data class SecondaryLineParts(
        val expectedCount: Int,
        val parts: MutableMap<Int, String> = HashMap()
    )

    private data class SecondaryTransfer(
        val trackId: String,
        val transferId: String,
        val mode: String,
        val expectedItems: Int,
        val parts: MutableMap<Int, SecondaryLineParts> = HashMap()
    ) {
        fun matches(value: JSONObject, currentTrackId: String): Boolean =
            trackId == currentTrackId &&
                value.optString("trackId") == trackId &&
                value.optString("transferId") == transferId &&
                value.optString("mode") == mode
    }

    private data class ArtworkTransfer(
        val artworkId: String,
        val quality: ArtworkQuality,
        val transferId: String,
        val generation: Long,
        val expectedSize: Int,
        val expectedChunks: Int,
        val expectedCrc: Long?,
        val binary: Boolean,
        val chunks: MutableMap<Int, ByteArray> = HashMap()
    )

    private data class TextTransfer(
        val kind: String,
        val expectedSize: Int,
        val expectedChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = HashMap()
    )

    private data class HistoryPayloadTransfer(
        val responseType: String,
        val expectedSize: Int,
        val expectedChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = HashMap()
    )

    companion object {
        private const val MAIN_ARTWORK_MAXIMUM_PIXEL_SIZE = 780
        private const val CACHED_PREVIEW_HQ_DELAY_MS = 350L
        private const val MAX_PLACEHOLDER_REFRESHES = 3
        private const val MAX_FULL_LYRIC_LINES = 1_000
        private const val LYRIC_WINDOW_REQUEST_DEDUP_MS = 800L
        private const val LYRIC_WINDOW_START_TIMEOUT_MS = 1_500L
        private const val MAX_LYRIC_WINDOW_RETRIES = 1
        private const val FULL_LYRICS_REQUEST_DEDUP_MS = 1_500L
        private const val FULL_LYRICS_START_TIMEOUT_MS = 3_000L
        private const val FULL_LYRICS_TRANSFER_TIMEOUT_MS = 6_000L
        private const val MAX_FULL_LYRICS_START_RETRIES = 2
        private const val MAX_LEGACY_LYRICS_RETRIES = 1
        private const val MAX_QRC_WAIT_RETRIES = 4
        private val QRC_WAIT_RETRY_DELAYS_MS = longArrayOf(800L, 1_500L, 3_000L, 5_000L)
        private const val PARTIAL_LYRICS_PUBLISH_MS = 250L
        private const val SECONDARY_START_TIMEOUT_MS = 3_000L
        private const val SECONDARY_TRANSFER_TIMEOUT_MS = 6_000L
        private const val MAX_SECONDARY_RETRIES = 1
        private const val ARTWORK_REQUEST_TIMEOUT_MS = 3_500L
        private const val ARTWORK_TRANSFER_TIMEOUT_MS = 6_500L
        private const val MAX_ARTWORK_REQUEST_RETRIES = 1
        private const val MAX_ARTWORK_TRANSFER_RETRIES = 1
        private const val VOLUME_SYNC_TIMEOUT_MS = 2_000L
        private const val MAX_VOLUME_SYNC_RETRIES = 1
        private const val HISTORY_SYNC_TIMEOUT_MS = 8_000L
        private const val DIAGNOSTIC_TRANSFER_TIMEOUT_MS = 6_000L
        private val SECONDARY_MODES = setOf("translation", "romanization")
    }
}
