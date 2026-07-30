package com.example.controllerapp

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.example.controllerapp.data.ControllerLogStore
import com.example.controllerapp.data.ControllerPreferences
import com.example.controllerapp.data.NowPlayingSnapshotStore
import com.example.controllerapp.data.PlaybackHistoryDao
import com.example.controllerapp.data.PlaybackHistoryEntity
import com.example.controllerapp.media.ArtworkCache
import com.example.controllerapp.media.ArtworkEnhancer
import com.example.controllerapp.media.ArtworkPlaceholderPolicy
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
import com.example.controllerapp.protocol.MediaIdentityPolicy
import com.example.controllerapp.protocol.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val artworkCache = ArtworkCache(appContext)
    private val snapshotStore = NowPlayingSnapshotStore(appContext)
    private val sequence = AtomicLong(0L)
    private val transferLock = Any()
    private var commandSender: ((ByteArray) -> Boolean)? = null
    private var serviceActions: ControllerServiceActions? = null
    private var capabilities = ServerCapabilities()
    private var capabilityTimeout: Job? = null
    private var currentWordFence = CurrentWordOrderingFence()
    private var trackInfoTransfer: JsonChunkTransfer? = null
    private var lyricWindowTransfer: LyricWindowTransfer? = null
    private var legacyLyricsTransfer: LegacyLyricsTransfer? = null
    private var binaryLyricsTransfer: BinaryLyricsTransfer? = null
    private val binaryLyricsRetryCounts = HashMap<String, Int>()
    private var secondaryTransfer: SecondaryTransfer? = null
    private var artworkTransfer: ArtworkTransfer? = null
    private val artworkRetryCounts = HashMap<String, Int>()
    private val artworkPlaceholderRefreshCounts = HashMap<String, Int>()
    private var textTransfer: TextTransfer? = null
    private val historyTransfers = HashMap<String, HistoryPayloadTransfer>()
    private val historySyncQueue = ArrayDeque<String>()
    private val requestedArtwork = HashSet<String>()
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
                    lines = snapshot.lines,
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
        resetTransfers("new connection")
        currentWordFence = CurrentWordOrderingFence()
        sendCapabilities()
        scope.launch {
            delay(80L)
            sendCommand("GET_PLAYBACK_STATE")
            delay(80L)
            sendCommand("GET_VOLUME")
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
    fun requestVolume() = sendCommand("GET_VOLUME")
    fun healthProbe() =
        if (_connection.value.serverSupportsV2) sendCommand("PING") else requestPlaybackState()

    fun seekTo(positionMs: Long) =
        sendCommand("SEEK_TO", JSONObject().put("position", positionMs.coerceAtLeast(0L)))

    fun setVolume(value: Int) =
        sendCommand("SET_VOLUME", JSONObject().put("volume", value.coerceAtLeast(0)))

    fun requestFullLyrics(forceLegacy: Boolean = false) {
        val trackId = _playback.value.trackId
        if (trackId.isBlank()) return
        val extra = JSONObject()
            .put("trackId", trackId)
            .put("positionMs", displayedPositionMs())
            .put("includeWordsAroundCurrent", true)
        if (!forceLegacy && capabilities.fullLyricsZlib) {
            extra.put("format", "zlib-json-v1")
        }
        _lyrics.update {
            it.copy(
                loadingStage = LyricLoadingStage.FULL_LYRICS,
                failureReason = ""
            )
        }
        sendCommand("GET_FULL_LYRICS", extra)
    }

    fun requestLyricWindow() {
        val trackId = _playback.value.trackId
        if (trackId.isBlank() || !capabilities.lyricWindow) return
        sendCommand(
            "GET_LYRIC_WINDOW",
            JSONObject()
                .put("trackId", trackId)
                .put("positionMs", displayedPositionMs())
        )
    }

    fun requestSecondary(mode: String) {
        val trackId = _playback.value.trackId
        if (trackId.isBlank()) return
        sendCommand(
            "GET_LYRIC_SECONDARY",
            JSONObject().put("trackId", trackId).put("mode", mode)
        )
    }

    fun requestLyricDiagnostic() {
        val trackId = _playback.value.trackId
        if (trackId.isBlank()) return
        sendCommand("GET_LYRIC_DIAGNOSTIC", JSONObject().put("trackId", trackId))
    }

    fun requestArtwork(quality: ArtworkQuality, forceRefresh: Boolean = false) {
        val id = _playback.value.trackId
        if (id.isBlank()) return
        val wireQuality = if (quality == ArtworkQuality.HQ) "hq" else "preview"
        requestedArtwork += "$id|$wireQuality"
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
        sendCommand(
            "ALBUM_ART_REQUEST",
            JSONObject()
                .put("id", id)
                .put("quality", wireQuality)
                .put("forceRefresh", forceRefresh)
        )
    }

    fun forceRefreshArtwork() {
        val id = _playback.value.trackId
        if (id.isBlank()) return
        val iterator = requestedArtwork.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().startsWith("$id|")) iterator.remove()
        }
        requestArtwork(ArtworkQuality.PREVIEW, forceRefresh = true)
    }

    fun requestSonyLogs(limit: Int = 80) =
        sendCommand("GET_LOGS", JSONObject().put("limit", limit.coerceIn(1, 200)))

    fun requestMediaDump() = sendCommand("DUMP_MEDIA_FIELDS")

    fun syncHistory() {
        _history.update { it.copy(loading = true, status = "正在同步") }
        scope.launch(Dispatchers.IO) {
            synchronized(historySyncQueue) {
                historySyncQueue.clear()
                historySyncQueue.addAll(listOf("TODAY", "WEEK", "MONTH"))
            }
            val after = historyDao.latestSessionId() ?: 0L
            val sent = sendCommand(
                "GET_PLAY_HISTORY_SINCE",
                JSONObject()
                    .put("requestId", requestId("history"))
                    .put("afterSessionId", after)
                    .put("limit", 100)
            )
            if (!sent) {
                synchronized(historySyncQueue) { historySyncQueue.clear() }
                _history.update { it.copy(loading = false, status = "Sony 未连接，保留本地历史") }
            }
        }
    }

    fun loadMoreHistory() {
        val before = _history.value.sessions.minOfOrNull { it.sessionId } ?: Long.MAX_VALUE
        sendCommand(
            "GET_PLAY_HISTORY_PAGE",
            JSONObject()
                .put("requestId", requestId("page"))
                .put("beforeSessionId", before)
                .put("limit", 50)
        )
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
    }

    private fun sendCapabilities() {
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
            val fallback = CapabilityPolicy.fallbackIfUnacknowledged(capabilities)
            if (fallback != capabilities) {
                capabilities = fallback
                _connection.update {
                    it.copy(serverProtocolVersion = 1, serverSupportsV2 = false)
                }
                logStore.append("[Protocol] capability ACK timeout, legacy fallback")
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
            "lyricWindowUnavailable" -> lyricWindowTransfer = null
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
            "lyricSecondaryUnavailable", "lyricSecondaryError" -> secondaryTransfer = null
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
            "albumArtBinaryError" -> handleArtworkFailure(value.optString("message"))
            "albumArtUnavailable" -> handleArtworkUnavailable(value)
            "logStart", "mediaFieldDumpStart" -> startTextTransfer(type, value)
            "logChunk", "mediaFieldDumpChunk" -> appendTextTransfer(type, value)
            "logEnd", "mediaFieldDumpEnd" -> finishTextTransfer(type, value)
            "mediaFieldDumpError" -> reportIssue(
                "Media Dump失败：${value.optString("message")}"
            )
            "playHistoryPage", "playHistorySince", "playStats" -> handleHistoryPayload(value)
            "historyPayloadStart" -> startHistoryPayload(value)
            "historyPayloadChunk" -> appendHistoryPayload(value)
            "historyPayloadEnd" -> finishHistoryPayload(value)
            "playHistoryError" -> {
                _history.update {
                    it.copy(status = value.optString("message", "同步失败"))
                }
                requestNextHistoryStat()
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
        capabilityTimeout?.cancel()
        _connection.update {
            it.copy(
                serverProtocolVersion = capabilities.protocolVersion,
                serverSupportsV2 = capabilities.protocolVersion >= 2
            )
        }
        val trackId = _playback.value.trackId
        if (trackId.isNotBlank()) {
            requestLyricWindow()
            requestFullLyrics()
        }
        logStore.append("[Protocol] V2 capability negotiated")
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
            _lyrics.update {
                if (it.trackId == trackId) it.copy(currentText = lyricText) else it
            }
            if (_lyrics.value.lines.isEmpty() && trackId.isNotBlank()) {
                requestFullLyrics()
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
                requestLyricWindow()
                delay(80L)
                requestFullLyrics()
            }
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
        if (trackId != _playback.value.trackId ||
            !MediaIdentityPolicy.generationMatches(_playback.value.generation, generation) ||
            count !in 1..5
        ) {
            return
        }
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
        publishLyrics(transfer.lines.values.sortedBy(LyricLine::index), final = false)
    }

    private fun startLegacyLyrics(value: JSONObject) {
        val trackId = value.optString("trackId")
        val count = value.optInt("count")
        if (trackId != _playback.value.trackId || count <= 0) return
        legacyLyricsTransfer = LegacyLyricsTransfer(trackId, count)
        _lyrics.update {
            it.copy(
                loadingStage = LyricLoadingStage.FULL_LYRICS,
                receivedChunks = 0,
                expectedChunks = count
            )
        }
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
            }
        }
    }

    private fun finishLegacyLyrics(value: JSONObject) {
        val transfer = legacyLyricsTransfer ?: return
        legacyLyricsTransfer = null
        if (value.optString("trackId") != transfer.trackId ||
            transfer.trackId != _playback.value.trackId ||
            transfer.lines.size != transfer.expectedCount
        ) {
            return
        }
        publishLyrics(transfer.lines.values.sortedBy(LyricLine::index), final = true)
    }

    private fun startBinaryLyrics(value: JSONObject) {
        val trackId = value.optString("trackId", value.optString("id"))
        val generation = value.optLong("generation", value.optLong("g"))
        if (trackId != _playback.value.trackId ||
            !MediaIdentityPolicy.generationMatches(_playback.value.generation, generation)
        ) {
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
                expectedChunks = chunks
            )
        }
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
                retryBinaryLyrics(transfer, emptyList(), true, reason)
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
                retryBinaryLyrics(transfer, emptyList(), true, "decode failed")
                return@launch
            }
            if (_playback.value.trackId == transfer.trackId &&
                MediaIdentityPolicy.generationMatches(
                    _playback.value.generation,
                    transfer.generation
                ) &&
                binaryLyricsTransfer?.transferId == transfer.transferId
            ) {
                binaryLyricsRetryCounts.remove(transfer.transferId)
                binaryLyricsTransfer = null
                publishLyrics(decoded.third, final = true)
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
    }

    private fun fallbackBinaryLyrics(reason: String) {
        val trackId = binaryLyricsTransfer?.trackId ?: _playback.value.trackId
        binaryLyricsTransfer = null
        logStore.append("[Lyrics] binary fallback reason=$reason")
        if (trackId == _playback.value.trackId) {
            requestFullLyrics(forceLegacy = true)
        }
    }

    private fun handleLyricsUnavailable(value: JSONObject) {
        val trackId = value.optString("trackId")
        if (trackId != _playback.value.trackId) return
        val reason = value.optString("reason", value.optString("lyricStatus"))
        _lyrics.update {
            it.copy(
                loadingStage = if (reason.contains("loading", true) ||
                    reason.contains("waiting", true)
                ) {
                    LyricLoadingStage.WAITING_QQ_QRC
                } else {
                    LyricLoadingStage.FAILED
                },
                failureReason = reason
            )
        }
    }

    private fun publishLyrics(lines: List<LyricLine>, final: Boolean) {
        val trackId = _playback.value.trackId
        if (trackId.isBlank()) return
        _lyrics.update {
            val localIndex = findCurrentLine(lines, displayedPositionMs())
            it.copy(
                trackId = trackId,
                lines = lines.sortedBy(LyricLine::index),
                isFinal = final,
                currentLineIndex = lines.getOrNull(localIndex)?.index ?: -1,
                loadingStage = if (final) LyricLoadingStage.READY else {
                    LyricLoadingStage.WINDOW_READY
                },
                receivedChunks = lines.size,
                expectedChunks = lines.size,
                failureReason = ""
            )
        }
        if (final) {
            val mode = _settings.value.lyricDisplayMode
            if (mode.showsTranslation) requestSecondary("translation")
            if (mode.showsRomanization) requestSecondary("romanization")
        }
        saveSnapshotDebounced()
    }

    private fun startSecondary(value: JSONObject) {
        val trackId = value.optString("trackId")
        if (trackId != _playback.value.trackId || _lyrics.value.lines.isEmpty()) return
        secondaryTransfer = SecondaryTransfer(
            trackId = trackId,
            transferId = value.optString("transferId"),
            mode = value.optString("mode"),
            expectedItems = value.optInt("itemCount")
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
        secondaryTransfer = null
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
        _lyrics.update { state ->
            state.copy(
                lines = state.lines.map { line ->
                    val text = ControllerProtocolCodec.sanitizeSecondary(secondary[line.index])
                    when (transfer.mode) {
                        "translation" -> line.copy(translation = text)
                        "romanization" -> line.copy(romanization = text)
                        else -> line
                    }
                }
            )
        }
        saveSnapshotDebounced()
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
        requestArtwork(ArtworkQuality.PREVIEW)
    }

    private fun startLegacyArtwork(value: JSONObject) {
        val id = value.optString("id")
        if (id != _playback.value.trackId) return
        val chunks = value.optInt("chunks", value.optInt("totalChunks"))
        val size = value.optInt("size")
        if (chunks <= 0) return
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
        finishArtworkData(transfer)
    }

    private fun startBinaryArtwork(value: JSONObject) {
        val id = value.optString("id")
        val generation = value.optLong("generation")
        if (id != _playback.value.trackId ||
            !MediaIdentityPolicy.generationMatches(_playback.value.generation, generation)
        ) {
            return
        }
        val transferId = value.optString("transferId")
        val chunks = value.optInt("chunks")
        val size = value.optInt("size")
        val crc = ControllerProtocolCodec.parseHexCrc(value.optString("crc32"))
        if (transferId.isBlank() || chunks <= 0 || size <= 0 || crc == null) return
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
            !MediaIdentityPolicy.generationMatches(
                _playback.value.generation,
                transfer.generation
            ) ||
            transfer.artworkId != _playback.value.trackId
        ) {
            return
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
                    retryArtwork(transfer, emptyList(), true)
                } else {
                    handleArtworkFailure(failure)
                }
                return@launch
            }
            val bitmap = ArtworkCache.decodeDownsampled(data!!, 780)
            if (bitmap == null || ArtworkPlaceholderPolicy.isLikelyPlaceholder(bitmap)) {
                bitmap?.recycle()
                handleArtworkFailure("等待QQ音乐真实封面")
                val refreshCount =
                    (artworkPlaceholderRefreshCounts[transfer.artworkId] ?: 0) + 1
                artworkPlaceholderRefreshCounts[transfer.artworkId] = refreshCount
                if (refreshCount <= MAX_PLACEHOLDER_REFRESHES) {
                    scope.launch {
                        delay(500L * refreshCount)
                        if (_playback.value.trackId == transfer.artworkId) {
                            requestArtwork(ArtworkQuality.PREVIEW, forceRefresh = true)
                        }
                    }
                }
                return@launch
            }
            if (_playback.value.trackId != transfer.artworkId ||
                !MediaIdentityPolicy.generationMatches(
                    _playback.value.generation,
                    transfer.generation
                )
            ) {
                // The decode belongs to an obsolete song/connection generation. It was never
                // published or inserted into a cache, so release its native pixel allocation
                // immediately instead of waiting for a later GC during rapid song switching.
                bitmap.recycle()
                return@launch
            }
            artworkPlaceholderRefreshCounts.remove(transfer.artworkId)
            artworkCache.store(
                artworkId = transfer.artworkId,
                quality = transfer.quality,
                data = data,
                bitmap = bitmap,
                maximumPixelSize = 780
            )
            requestedArtwork.remove("${transfer.artworkId}|${wireQuality(transfer.quality)}")
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
                expectedChunks = transfer.expectedChunks
            )
            saveSnapshotDebounced()
            if (transfer.quality == ArtworkQuality.PREVIEW) {
                scope.launch {
                    delay(hqDelayMs())
                    if (_playback.value.trackId == transfer.artworkId) {
                        requestArtwork(ArtworkQuality.HQ)
                    }
                }
            } else if (_settings.value.artworkEnhancementEnabled) {
                val enhanced = ArtworkEnhancer.enhance(bitmap)
                if (_playback.value.trackId == transfer.artworkId && enhanced !== bitmap) {
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
    }

    private fun handleArtworkUnavailable(value: JSONObject) {
        if (value.optString("id") != _playback.value.trackId) return
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

    private fun handleArtworkFailure(reason: String) {
        _artwork.update {
            it.copy(
                loadingStage = ArtworkLoadingStage.FAILED,
                failureReason = reason
            )
        }
        reportIssue(reason)
    }

    private fun restoreCachedArtwork(id: String, restored: Boolean) {
        scope.launch {
            val hq = artworkCache.load(id, ArtworkQuality.HQ, 780)
            val cached = hq ?: artworkCache.load(id, ArtworkQuality.PREVIEW, 780)
            if (_playback.value.trackId != id || cached == null) return@launch
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
                enhancementMessage = if (cached.requiresRefresh) "缓存展示·后台刷新" else "缓存命中"
            )
            if (cached.requiresRefresh && _connection.value.connected) {
                requestArtwork(ArtworkQuality.PREVIEW, forceRefresh = true)
            }
        }
    }

    private fun startTextTransfer(type: String, value: JSONObject) {
        val kind = if (type.startsWith("log")) "log" else "dump"
        val chunks = value.optInt("chunks")
        if (chunks <= 0 && value.optBoolean("empty")) {
            if (kind == "log") {
                _diagnostics.update { it.copy(sonyLogs = "Sony 暂无日志") }
            }
            return
        }
        textTransfer = TextTransfer(kind, value.optInt("size"), chunks)
        _diagnostics.update { it.copy(remoteTransferInProgress = true) }
    }

    private fun appendTextTransfer(type: String, value: JSONObject) {
        val transfer = textTransfer ?: return
        val kind = if (type.startsWith("log")) "log" else "dump"
        if (kind != transfer.kind) return
        val index = value.optInt("index", -1)
        if (index !in 0 until transfer.expectedChunks) return
        runCatching { Base64.decode(value.optString("data"), Base64.NO_WRAP) }
            .getOrNull()
            ?.let { transfer.chunks[index] = it }
    }

    private fun finishTextTransfer(type: String, value: JSONObject) {
        val transfer = textTransfer
        textTransfer = null
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
            _diagnostics.update { it.copy(remoteTransferInProgress = false) }
            return
        }
        val bytes = ControllerProtocolCodec.reassemble(
            transfer.chunks,
            transfer.expectedChunks
        ) ?: return
        val text = String(bytes, Charsets.UTF_8)
        _diagnostics.update {
            if (transfer.kind == "log") {
                it.copy(sonyLogs = text, remoteTransferInProgress = false)
            } else {
                it.copy(mediaFieldDump = text, remoteTransferInProgress = false)
            }
        }
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
    }

    private fun appendHistoryPayload(value: JSONObject) {
        val requestId = value.optString("requestId")
        val transfer = historyTransfers[requestId] ?: return
        val index = value.optInt("index", -1)
        if (index !in 0 until transfer.expectedChunks) return
        runCatching { Base64.decode(value.optString("data"), Base64.NO_WRAP) }
            .getOrNull()
            ?.let { transfer.chunks[index] = it }
    }

    private fun finishHistoryPayload(value: JSONObject) {
        val requestId = value.optString("requestId")
        val transfer = historyTransfers.remove(requestId) ?: return
        val data = ControllerProtocolCodec.reassemble(
            transfer.chunks,
            transfer.expectedChunks
        ) ?: return
        if (transfer.expectedSize > 0 && data.size != transfer.expectedSize) return
        runCatching { JSONObject(String(data, Charsets.UTF_8)) }
            .getOrNull()
            ?.let(::handleHistoryPayload)
    }

    private fun handleHistoryPayload(value: JSONObject) {
        when (value.optString("type")) {
            "playHistoryPage", "playHistorySince" -> {
                val sessions = decodeHistorySessions(value.optJSONArray("items"))
                scope.launch(Dispatchers.IO) {
                    historyDao.upsert(sessions.map(PlaybackHistoryEntity::from))
                    _history.update {
                        it.copy(
                            status = if (sessions.isEmpty()) "历史已是最新" else "历史同步完成"
                        )
                    }
                    requestNextHistoryStat()
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
            _history.update { it.copy(loading = false, status = "同步完成") }
            return
        }
        val sent = sendCommand(
            "GET_PLAY_STATS",
            JSONObject()
                .put("requestId", requestId("stats"))
                .put("range", range)
        )
        if (!sent) requestNextHistoryStat()
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
        artworkPlaceholderRefreshCounts.clear()
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
            trackInfoTransfer = null
            lyricWindowTransfer = null
            legacyLyricsTransfer = null
            binaryLyricsTransfer = null
            secondaryTransfer = null
            artworkTransfer = null
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

    private fun quality(value: String): ArtworkQuality =
        if (value == "hq" || value == "full") ArtworkQuality.HQ else ArtworkQuality.PREVIEW

    private fun wireQuality(value: ArtworkQuality): String =
        if (value.rank >= ArtworkQuality.HQ.rank) "hq" else "preview"

    private fun hqDelayMs(): Long = when (_settings.value.performanceMode) {
        PlaybackPerformanceMode.SMOOTH -> 750L
        PlaybackPerformanceMode.POWER_SAVING -> 2_500L
        PlaybackPerformanceMode.AUTOMATIC -> 1_200L
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

    private data class LegacyLyricsTransfer(
        val trackId: String,
        val expectedCount: Int,
        val lines: MutableMap<Int, LyricLine> = HashMap()
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
        private const val MAX_PLACEHOLDER_REFRESHES = 3
    }
}
