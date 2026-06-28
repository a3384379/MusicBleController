package com.example.playeragent.media

import android.content.Context
import android.os.Environment
import com.example.playeragent.logging.LogConfig
import java.io.File
import java.nio.charset.Charset
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors

class LyricManager(
    context: Context,
    private val logger: (String) -> Unit,
    private val onLyricsReady: (LyricsReadyGateSnapshot) -> Unit = {}
) {

    private val appContext = context.applicationContext
    private var cachedKey: String? = null
    private var activeSongKey: String? = null
    private var activeTitle: String = ""
    private var activeArtist: String = ""
    private var activeAlbum: String = ""
    private var activeTrackId: String = ""
    private var activeDurationMs: Long = 0L
    private var activePositionMs: Long = 0L
    private var activeTrackChangedAtMs: Long = 0L
    private var activeLyricTraceId: String = ""
    private var activeLyricTraceStartedAtMs: Long = 0L
    private var loadedSongKey: String? = null
    private var loadingSongKey: String? = null
    private var foregroundLoadingSongKey: String? = null
    private var pendingRequest: LyricLoadRequest? = null
    private var cachedLines: List<LyricLine> = emptyList()
    private var lastLoggedLine: String? = null
    private var lastAlreadyLoadingLogAtMs: Long = 0L
    private var retryableFailureSongKey: String? = null
    private var retryableFailureReason: String = ""
    private var retryableFailureAtMs: Long = 0L
    private var finalEmptySongKey: String? = null
    private var finalEmptyReason: String = ""
    private var lastAttemptAtMs: Long = 0L
    private var lastSource: LyricSource = LyricSource.NONE
    private var retryWindowSongKey: String? = null
    private var retryWindowStartedAtMs: Long = 0L
    private var retryCountInWindow: Int = 0
    private var lastRetryAtMs: Long = 0L
    private var lastWatcherRetryAtMs: Long = 0L
    private var lastFullLyricsRecoveryNudgeAtMs: Long = 0L
    private var lazyWaitSongKey: String? = null
    private var lazyWaitStartedAtMs: Long = 0L
    private var lyricsReadyState: LyricsReadyState = LyricsReadyState.NOT_STARTED
    private var activeLyricsTaskId: Long = 0L
    private val lyricExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QrcLyricLoaderThread")
    }
    private val foregroundLyricExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QrcForegroundLyricThread").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    private val parsedCache = object : LinkedHashMap<String, LyricsParsedCacheEntry>(
        PARSED_CACHE_MAX_KEYS,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LyricsParsedCacheEntry>?
        ): Boolean {
            val shouldEvict = size > PARSED_CACHE_MAX_KEYS
            if (shouldEvict && eldest != null) {
                logger(
                    "[LyricsParsedCache] evict key=${eldest.key} " +
                        "songKey=${eldest.value.request.key}"
                )
            }
            return shouldEvict
        }
    }
    private val qrcLyricManager = QrcLyricManager(
        context = appContext,
        logger = logger
    )
    private val predictiveLyricsPipeline = PredictiveLyricsPipeline(
        logger = logger,
        loader = { track ->
            val result = qrcLyricManager.loadWithResult(track.title, track.artist, track.album)
            val lines = if (result.success) {
                result.lines.map {
                    LyricLine(
                        timeMs = it.timeMs,
                        text = it.text,
                        durationMs = it.durationMs,
                        words = it.words,
                        translation = it.translation,
                        romanization = it.romanization
                    )
                }
            } else {
                emptyList()
            }
            PredictiveLyricsLoadResult(
                lines = lines,
                source = if (result.success) LyricSource.QRC.name else LyricSource.NONE.name,
                reason = result.reason
            )
        }
    )
    private val recoveryEngine = LyricRecoveryEngine(
        logger = logger,
        retryCallback = { reason, bypassRetryableCooldown ->
            retryActiveSongFromRecovery(reason, bypassRetryableCooldown)
        }
    )

    init {
        QrcLyricCacheManager.addFuzzyIndexReadyListener {
            recoveryEngine.onFuzzyIndexReady()
            retryActiveSong("fuzzy index ready")
        }
        QrcMaintenanceCoordinator.addFinishListener { token ->
            logger("[LyricRetry] scheduled reason=maintenance done current active song type=${token.type}")
            retryActiveSong("maintenance done")
        }
    }

    fun scanLrcFiles(title: String, artist: String, album: String) {
        logger("[LyricScan] start")

        val foundFiles = mutableListOf<File>()
        val visitedCount = intArrayOf(0)
        val scanDirectories = scanDirectories()

        scanDirectories.forEach { directory ->
            logger("[LyricScan] scan directory=${directory.absolutePath}")
            logger("[LyricScan] directory exists=${directory.exists()}")

            try {
                scanDirectory(
                    directory = directory,
                    depth = 0,
                    foundFiles = foundFiles,
                    visitedCount = visitedCount
                )
            } catch (exception: Exception) {
                logger("[LyricScan] scan failed: ${directory.absolutePath} ${exception.message}")
            }
        }

        logger("[LyricScan] total lrc files=${foundFiles.size}")
        logger("[LyricScan] current title=$title")
        logger("[LyricScan] current artist=$artist")
        logger("[LyricScan] current album=$album")

        val candidates = foundFiles
            .map { file -> LyricCandidate(file = file, score = calculateScore(file, title, artist)) }
            .sortedByDescending { it.score }

        candidates.take(10).forEach { candidate ->
            logger("[LyricScan] candidate score=${candidate.score} file=${candidate.file.absolutePath}")
        }

        val bestMatch = candidates.firstOrNull()
        if (bestMatch == null) {
            logger("[LyricScan] best match=")
            return
        }

        logger("[LyricScan] best match=${bestMatch.file.absolutePath}")
        val parsedLines = parseLrc(bestMatch.file)
        if (parsedLines.isNotEmpty()) {
            scannedCache = ScannedLyricCache(
                title = title.trim(),
                artist = artist.trim(),
                file = bestMatch.file,
                lines = parsedLines
            )
            cachedKey = lyricKey(title, artist)
            cachedLines = parsedLines
            lastLoggedLine = null
            logger("[LyricScan] cached best match lyrics")
        }
    }

    @Synchronized
    fun requestLyricLoadAsync(
        title: String,
        artist: String,
        album: String = "",
        trackId: String = "",
        durationMs: Long = 0L,
        positionMs: Long = 0L,
        force: Boolean = false,
        reason: String = "playback state"
    ) {
        val key = lyricKey(title, artist, album)
        if (trackId.isNotBlank()) {
            TrackCapabilityTracker.onLyricLookupStart(key, trackId)
        }
        if (!force && key == activeSongKey && key == loadedSongKey) {
            activeTitle = title
            activeArtist = artist
            activeAlbum = album
            if (trackId.isNotBlank()) {
                activeTrackId = trackId
            }
            if (durationMs > 0L) {
                activeDurationMs = durationMs
            }
            if (positionMs > 0L) {
                activePositionMs = positionMs
            }
            return
        }
        if (!force &&
            key == activeSongKey &&
            key == retryableFailureSongKey
        ) {
            val now = System.currentTimeMillis()
            if (now - retryableFailureAtMs < RETRYABLE_RECHECK_INTERVAL_MS) {
                return
            }
            if (!consumeRetrySlotLocked(key, now)) {
                logger(
                    "[LyricRetry] skipped reason=rate limited count=$retryCountInWindow " +
                        "songKey=$key"
                )
                return
            }
            logger(
                "[LyricRetry] scheduled songKey=$key reason=retryable interval " +
                    "failureReason=$retryableFailureReason"
            )
        }

        if (key != activeSongKey) {
            val oldTrack = activeSongKey.orEmpty()
            activeLyricsTaskId += 1
            activeLyricTraceStartedAtMs = System.currentTimeMillis()
            activeLyricTraceId = buildLyricTraceId(trackId, key, activeLyricTraceStartedAtMs)
            lyricsReadyState = LyricsReadyState.NOT_STARTED
            if (loadingSongKey != null && loadingSongKey != key) {
                logger("[LyricsTask] cancel oldTrack=$oldTrack newTrack=$key")
            }
            logger("[LyricsFastPath] track_changed start songKey=$key trackId=$trackId")
            trace(
                activeLyricTraceId,
                "trackChanged",
                "songKey=$key trackId=$trackId title=${title.take(32)} " +
                    "artist=${artist.take(32)} t=$activeLyricTraceStartedAtMs"
            )
            activeSongKey = key
            activeTitle = title
            activeArtist = artist
            activeAlbum = album
            activeTrackId = trackId
            activeDurationMs = durationMs
            activePositionMs = positionMs
            activeTrackChangedAtMs = System.currentTimeMillis()
            cachedKey = key
            cachedLines = emptyList()
            loadedSongKey = null
            retryableFailureSongKey = null
            retryableFailureReason = ""
            finalEmptySongKey = null
            finalEmptyReason = ""
            if (lazyWaitSongKey != null) {
                logger("[LyricLazyLoad] cancelled reason=song changed")
            }
            recoveryEngine.onSongChanged()
            lazyWaitSongKey = null
            lazyWaitStartedAtMs = 0L
            lastLoggedLine = null
            if (!force && applyPredictiveLyricsLocked(key, title, artist, album, trackId, durationMs, positionMs)) {
                lyricsReadyState = LyricsReadyState.READY
                trace(
                    activeLyricTraceId,
                    "ready",
                    "source=predictive totalCostMs=${System.currentTimeMillis() - activeLyricTraceStartedAtMs}"
                )
                return
            }
        } else {
            activeTitle = title
            activeArtist = artist
            activeAlbum = album
            if (trackId.isNotBlank()) {
                activeTrackId = trackId
            }
            if (durationMs > 0L) {
                activeDurationMs = durationMs
            }
            if (positionMs > 0L) {
                activePositionMs = positionMs
            }
        }

        if (title.isBlank() && artist.isBlank()) {
            loadedSongKey = key
            lyricsReadyState = LyricsReadyState.FAILED
            return
        }
        if (isInFlightLocked(key)) {
            val now = System.currentTimeMillis()
            if (now - lastAlreadyLoadingLogAtMs >= REPEATED_LOADING_LOG_INTERVAL_MS) {
                lastAlreadyLoadingLogAtMs = now
                logger("[LyricAsync] already loading songKey=$key")
            }
            return
        }

        pendingRequest = LyricLoadRequest(
            key = key,
            title = title,
            artist = artist,
            album = album,
            trackId = trackId.ifBlank { activeTrackId },
            durationMs = durationMs.takeIf { it > 0L } ?: activeDurationMs,
            positionMs = positionMs.takeIf { it > 0L } ?: activePositionMs,
            taskId = activeLyricsTaskId,
            traceId = activeLyricTraceId.ifBlank {
                buildLyricTraceId(trackId, key, System.currentTimeMillis())
            },
            requestedAtMs = System.currentTimeMillis(),
            trackChangedAtMs = activeLyricTraceStartedAtMs,
            foreground = true
        )
        lastAttemptAtMs = System.currentTimeMillis()
        lyricsReadyState = LyricsReadyState.PARSING
        logger("[LyricAsync] scheduled songKey=$key reason=$reason force=$force")
            logger("[LyricsFastPath] lookup start songKey=$key reason=$reason")
            TrackCapabilityTracker.onLyricLookupStart(key, trackId)
            trace(
            pendingRequest?.traceId.orEmpty(),
            "loadScheduled",
            "songKey=$key reason=$reason force=$force " +
                "loadingSongKey=${loadingSongKey.orEmpty()} " +
                "foregroundLoadingSongKey=${foregroundLoadingSongKey.orEmpty()} pending=true"
        )
        if (loadingSongKey != null && loadingSongKey != key) {
            logger("[LyricAsync] latest song promoted songKey=$key")
        }
        startForegroundLyricLoadLocked(pendingRequest ?: return)
    }

    @Synchronized
    fun preloadPredictiveLyrics(candidate: PredictiveLyricsCandidate) {
        val track = candidate.track
        if (track.title.isBlank()) {
            return
        }
        if (loadingSongKey != null || foregroundLoadingSongKey != null) {
            logger(
                "[PredictiveLyrics] preload miss track=${track.title} " +
                    "reason=current lyric loading"
            )
            return
        }
        predictiveLyricsPipeline.onCandidate(candidate)
    }

    @Synchronized
    fun retryActiveSong(reason: String) {
        val key = activeSongKey ?: return
        if (cachedLines.isNotEmpty() && loadedSongKey == key) {
            logger("[LyricRetry] skipped reason=has lyrics songKey=$key")
            return
        }
        if (isInFlightLocked(key)) {
            logger("[LyricRetry] skipped reason=already in-flight songKey=$key")
            return
        }
        if (retryableFailureSongKey != key) {
            logger("[LyricRetry] skipped reason=no retryable failure songKey=$key")
            return
        }
        val now = System.currentTimeMillis()
        if (!consumeRetrySlotLocked(key, now)) {
            logger(
                "[LyricRetry] skipped reason=rate limited count=$retryCountInWindow " +
                    "songKey=$key"
            )
            return
        }
        if (isLazyWaitExpiredLocked(key, now)) {
            expireLazyWaitLocked(key)
            return
        }
        if (isWaitingForQqMusicCacheLocked(key)) {
            logger("[LyricLazyLoad] retry reason=timer")
        }
        logger(
            "[LyricRetry] scheduled songKey=$key reason=$reason " +
                "failureReason=$retryableFailureReason"
        )
        requestLyricLoadAsync(
            title = activeTitle,
            artist = activeArtist,
            album = activeAlbum,
            force = true,
            reason = reason
        )
    }

    @Synchronized
    fun retryActiveSongFromWatcher(reason: String): Boolean {
        val key = activeSongKey ?: return false
        if (cachedLines.isNotEmpty() && loadedSongKey == key) {
            logger("[LyricRetry] skipped reason=has lyrics songKey=$key")
            return false
        }
        if (retryableFailureSongKey != key) {
            logger("[LyricRetry] skipped reason=no retryable failure songKey=$key")
            return false
        }
        val now = System.currentTimeMillis()
        if (isLazyWaitExpiredLocked(key, now)) {
            expireLazyWaitLocked(key)
            return false
        }
        if (now - lastWatcherRetryAtMs < WATCHER_RETRY_MIN_INTERVAL_MS) {
            logger("[LyricRetry] skipped reason=rate limited watcher retry songKey=$key")
            return false
        }
        if (isInFlightLocked(key)) {
            logger("[LyricRetry] skipped reason=already in-flight songKey=$key")
            return false
        }
        lastWatcherRetryAtMs = now
        recoveryEngine.onQrcGenerationChanged(
            oldGeneration = recoveryEngine.snapshot().lastQrcGeneration,
            newGeneration = QrcDirectoryGeneration.current()
        )
        logger("[QrcCooldown] cleared active song reason=qrc generation changed")
        qrcLyricManager.removeUncertainCooldown(key, "qrc generation changed")
        logger("[LyricLazyLoad] retry reason=qrc watcher generation changed")
        logger("[LyricLazyLoad] current song retry after watcher generation")
        logger("[LyricRetry] scheduled songKey=$key reason=$reason")
        requestLyricLoadAsync(
            title = activeTitle,
            artist = activeArtist,
            album = activeAlbum,
            force = true,
            reason = reason
        )
        return true
    }

    @Synchronized
    fun manualRefreshCurrentLyric(): Boolean {
        val key = activeSongKey
        if (key.isNullOrBlank()) {
            logger("[LyricRetry] manual refresh skipped reason=no active song")
            return false
        }
        if (isInFlightLocked(key)) {
            logger("[LyricRetry] manual refresh skipped reason=in flight")
            return false
        }
        logger("[LyricRetry] manual refresh current songKey=$key")
        logger("[LyricRetry] manual refresh bypass retryable cooldown songKey=$key")
        recoveryEngine.onManualRefresh()
        qrcLyricManager.removeUncertainCooldown(key, "manual refresh current lyric")
        retryableFailureSongKey = null
        retryableFailureReason = ""
        retryableFailureAtMs = 0L
        finalEmptySongKey = null
        finalEmptyReason = ""
        lazyWaitSongKey = null
        lazyWaitStartedAtMs = 0L
        requestLyricLoadAsync(
            title = activeTitle,
            artist = activeArtist,
            album = activeAlbum,
            force = true,
            reason = "manual refresh current lyric"
        )
        return true
    }

    @Synchronized
    fun nudgeRecoveryFromFullLyricsRequest(): Boolean {
        val key = activeSongKey
        if (key.isNullOrBlank()) {
            logger("[LyricRecovery] nudge skipped reason=no active song source=fullLyricsRequest")
            return false
        }
        if (cachedLines.isNotEmpty() && loadedSongKey == key) {
            logger("[LyricRecovery] nudge skipped reason=has lyrics source=fullLyricsRequest songKey=$key")
            return false
        }
        if (isInFlightLocked(key)) {
            logger("[LyricRecovery] nudge skipped reason=in flight source=fullLyricsRequest songKey=$key")
            return false
        }
        val unavailableReason = currentUnavailableReason()
        if (!isRecoveryNudgeReason(unavailableReason)) {
            logger(
                "[LyricRecovery] nudge skipped reason=not retryable " +
                    "source=fullLyricsRequest unavailableReason=$unavailableReason songKey=$key"
            )
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastFullLyricsRecoveryNudgeAtMs < FULL_LYRICS_RECOVERY_NUDGE_MIN_INTERVAL_MS) {
            logger(
                "[LyricRecovery] nudge skipped reason=rate limited " +
                    "source=fullLyricsRequest ageMs=${now - lastFullLyricsRecoveryNudgeAtMs} songKey=$key"
            )
            return false
        }
        lastFullLyricsRecoveryNudgeAtMs = now
        logger(
            "[LyricRecovery] nudge source=fullLyricsRequest " +
                "unavailableReason=$unavailableReason songKey=$key"
        )
        return retryActiveSongFromRecovery(
            reason = "fullLyrics request",
            bypassRetryableCooldown = true
        )
    }

    @Synchronized
    fun notifyIncrementalBatchDone(groupIds: Collection<String>) {
        recoveryEngine.onIncrementalBatchDone(groupIds)
    }

    @Synchronized
    fun recoverySnapshot(): LyricRecoverySnapshot {
        return recoveryEngine.snapshot()
    }

    @Synchronized
    fun lyricsReadyGateSnapshot(): LyricsReadyGateSnapshot {
        return lyricsReadyGateSnapshotLocked()
    }

    private fun consumeRetrySlotLocked(key: String, now: Long): Boolean {
        if (retryWindowSongKey != key || now - retryWindowStartedAtMs > RETRY_RATE_WINDOW_MS) {
            retryWindowSongKey = key
            retryWindowStartedAtMs = now
            retryCountInWindow = 0
        }
        if (retryCountInWindow >= MAX_RETRIES_PER_WINDOW) {
            return false
        }
        retryCountInWindow += 1
        lastRetryAtMs = now
        return true
    }

    @Synchronized
    fun currentUnavailableReason(): String {
        val key = activeSongKey
        return when {
            key != null && isInFlightLocked(key) -> "lyrics loading"
            key != null && isWaitingForQqMusicCacheLocked(key) -> {
                if (isLazyWaitExpiredLocked(key, System.currentTimeMillis())) {
                    "no safe qrc candidate"
                } else {
                    "waiting qqmusic lyric cache"
                }
            }
            key != null && recoveryEngine.isActiveFor(key) -> {
                val recovery = recoveryEngine.snapshot()
                if (recovery.lastReason == WAITING_QQMUSIC_LYRIC_CACHE_REASON) {
                    WAITING_QQMUSIC_LYRIC_CACHE_REASON
                } else {
                    "lyric recovery active"
                }
            }
            key != null && retryableFailureSongKey == key -> retryableFailureReason.ifBlank {
                "lyrics retry pending"
            }
            key != null && finalEmptySongKey == key -> finalEmptyReason.ifBlank {
                "no lyrics final"
            }
            key != null && key != loadedSongKey -> "lyrics loading"
            else -> "no parsed lyrics"
        }
    }

    @Synchronized
    fun diagnosticSnapshot(trackId: String): LyricDiagnosticSnapshot {
        val key = activeSongKey.orEmpty()
        val now = System.currentTimeMillis()
        val lines = if (key.isNotBlank() && key == loadedSongKey) cachedLines.size else 0
        val reason = currentUnavailableReason()
        val status = when {
            key.isBlank() -> "no_lyrics_final"
            lines > 0 -> "loaded"
            isInFlightLocked(key) -> "loading"
            isWaitingForQqMusicCacheLocked(key) && !isLazyWaitExpiredLocked(key, now) ->
                "waiting_qqmusic_cache"
            recoveryEngine.isActiveFor(key) -> "lyric_recovery_active"
            retryableFailureSongKey == key -> {
                if (retryableFailureReason == "maintenance busy") {
                    "maintenance_busy"
                } else {
                    "retry_pending"
                }
            }
            finalEmptySongKey == key -> {
                if (finalEmptyReason.contains("safe", ignoreCase = true)) {
                    "no_safe_candidate"
                } else {
                    "no_lyrics_final"
                }
            }
            else -> "loading"
        }
        val nextRetryAt = when {
            status == "waiting_qqmusic_cache" ->
                (lastRetryAtMs + RETRYABLE_RECHECK_INTERVAL_MS).coerceAtLeast(now)
            status == "lyric_recovery_active" -> {
                val recovery = recoveryEngine.snapshot()
                (recovery.lastRetryAt + RETRYABLE_RECHECK_INTERVAL_MS).coerceAtLeast(now)
            }
            retryableFailureSongKey == key && retryableFailureAtMs > 0L ->
                retryableFailureAtMs + RETRYABLE_RECHECK_INTERVAL_MS
            else -> 0L
        }
        val cooldownUntil = if (retryableFailureSongKey == key && retryableFailureAtMs > 0L) {
            retryableFailureAtMs + RETRYABLE_RECHECK_INTERVAL_MS
        } else {
            0L
        }
        val recovery = recoveryEngine.snapshot()
        return LyricDiagnosticSnapshot(
            trackId = trackId,
            songKey = key,
            title = activeTitle,
            artist = activeArtist,
            status = status,
            source = if (lines > 0) lastSource.name else LyricSource.NONE.name,
            reason = reason,
            lines = lines,
            lastAttemptAt = lastAttemptAtMs,
            nextRetryAt = nextRetryAt,
            retryCount = retryCountInWindow,
            cooldownUntil = cooldownUntil,
            fuzzyIndexReady = QrcLyricCacheManager(appContext, logger).fuzzyIndexStatus().ready,
            qrcIndexLoaded = true,
            maintenanceBusy = QrcMaintenanceCoordinator.isRunning(),
            waitingQqMusicCache = status == "waiting_qqmusic_cache",
            suggestion = suggestionForStatus(status, reason),
            recoveryState = recovery.state.name,
            recoveryRetryCount = recovery.retryCount,
            recoveryExpiresAt = recovery.expiresAt,
            lastRecoveryReason = recovery.lastReason,
            recentQrcCandidateCount = recovery.recentCandidateCount
        )
    }

    fun loadLyric(title: String, artist: String, album: String = "") {
        val request = LyricLoadRequest(
            key = lyricKey(title, artist, album),
            title = title,
            artist = artist,
            album = album
        )
        val result = loadLyricBlocking(request)
        synchronized(this) {
            applyLoadedLyricLocked(request, result)
        }
    }

    private fun startNextLyricLoadLocked() {
        if (loadingSongKey != null) {
            return
        }
        val request = pendingRequest ?: return
        pendingRequest = null
        loadingSongKey = request.key
        lyricsReadyState = LyricsReadyState.PARSING
        logger(
            "[LyricAsync] task start songKey=${request.key} " +
                "title=${request.title} artist=${request.artist}"
        )
        trace(
            request.traceId,
            "loadStart",
            "queueWaitMs=${System.currentTimeMillis() - request.requestedAtMs} " +
                "songKey=${request.key}"
        )
        lyricExecutor.execute {
            val startedAt = System.currentTimeMillis()
            val result = loadLyricFastPath(request)
            synchronized(this) {
                val costMs = System.currentTimeMillis() - startedAt
                if (activeSongKey == request.key && request.taskId == activeLyricsTaskId) {
                    applyLoadedLyricLocked(request, result)
                    if (result.lineCount > 0) {
                        logger(
                            "[LyricAsync] success songKey=${request.key} " +
                                "lines=${result.lineCount} costMs=$costMs"
                        )
                    } else {
                        logger(
                            "[LyricAsync] failed songKey=${request.key} " +
                                "costMs=$costMs"
                        )
                    }
                    logger(
                        "[Lyric] async load done title=${request.title} " +
                            "source=${result.source} lines=${result.lineCount} costMs=$costMs"
                    )
                    if (result.lineCount > 0) {
                        trace(
                            request.traceId,
                            "ready",
                            "totalCostMs=${System.currentTimeMillis() - request.trackChangedAtMs} " +
                                "loadCostMs=$costMs source=${result.source} lines=${result.lineCount}"
                        )
                    } else {
                        trace(
                            request.traceId,
                            "failed",
                            "totalCostMs=${System.currentTimeMillis() - request.trackChangedAtMs} " +
                                "loadCostMs=$costMs reason=${result.failureReason} retryable=${result.retryable}"
                        )
                    }
                } else {
                    logger(
                        "[LyricAsync] stale result discarded songKey=${request.key}"
                    )
                    logger(
                        "[LyricsTask] cancel oldTrack=${request.key} " +
                            "newTrack=${activeSongKey.orEmpty()}"
                    )
                    logger("[Lyric] async load discarded title=${request.title}")
                }
                loadingSongKey = null
                startNextLyricLoadLocked()
            }
        }
    }

    private fun startForegroundLyricLoadLocked(request: LyricLoadRequest) {
        if (pendingRequest?.key == request.key && pendingRequest?.taskId == request.taskId) {
            pendingRequest = null
        }
        if (foregroundLoadingSongKey != null && foregroundLoadingSongKey != request.key) {
            logger(
                "[LyricAsync] foreground preempt old=${foregroundLoadingSongKey.orEmpty()} " +
                    "new=${request.key}"
            )
        }
        foregroundLoadingSongKey = request.key
        lyricsReadyState = LyricsReadyState.PARSING
        logger(
            "[LyricAsync] foreground task start songKey=${request.key} " +
                "title=${request.title} artist=${request.artist}"
        )
        trace(
            request.traceId,
            "loadStart",
            "queueWaitMs=${System.currentTimeMillis() - request.requestedAtMs} " +
                "songKey=${request.key} foreground=true"
        )
        foregroundLyricExecutor.execute {
            val startedAt = System.currentTimeMillis()
            val result = loadLyricFastPath(request)
            synchronized(this) {
                val costMs = System.currentTimeMillis() - startedAt
                if (activeSongKey == request.key && request.taskId == activeLyricsTaskId) {
                    applyLoadedLyricLocked(request, result)
                    if (result.lineCount > 0) {
                        logger(
                            "[LyricAsync] foreground success songKey=${request.key} " +
                                "lines=${result.lineCount} costMs=$costMs"
                        )
                    } else {
                        logger(
                            "[LyricAsync] foreground failed songKey=${request.key} " +
                                "costMs=$costMs"
                        )
                    }
                    logger(
                        "[Lyric] foreground load done title=${request.title} " +
                            "source=${result.source} lines=${result.lineCount} costMs=$costMs"
                    )
                    if (result.lineCount > 0) {
                        trace(
                            request.traceId,
                            "ready",
                            "totalCostMs=${System.currentTimeMillis() - request.trackChangedAtMs} " +
                                "loadCostMs=$costMs source=${result.source} lines=${result.lineCount}"
                        )
                    } else {
                        trace(
                            request.traceId,
                            "failed",
                            "totalCostMs=${System.currentTimeMillis() - request.trackChangedAtMs} " +
                                "loadCostMs=$costMs reason=${result.failureReason} retryable=${result.retryable}"
                        )
                    }
                } else {
                    logger(
                        "[LyricAsync] foreground stale result discarded songKey=${request.key}"
                    )
                    logger(
                        "[LyricsTask] cancel oldTrack=${request.key} " +
                            "newTrack=${activeSongKey.orEmpty()}"
                    )
                }
                if (foregroundLoadingSongKey == request.key) {
                    foregroundLoadingSongKey = null
                }
            }
        }
    }

    private fun applyLoadedLyricLocked(
        request: LyricLoadRequest,
        result: LyricLoadResult
    ) {
        cachedKey = request.key
        if (result.lines.isNotEmpty()) {
            cachedLines = result.lines
            loadedSongKey = request.key
            lastSource = result.source
            recoveryEngine.onLyricLoaded(request.key, result.lineCount)
            retryableFailureSongKey = null
            retryableFailureReason = ""
            retryableFailureAtMs = 0L
            finalEmptySongKey = null
            finalEmptyReason = ""
            if (lazyWaitSongKey == request.key) {
                val costMs = System.currentTimeMillis() - lazyWaitStartedAtMs
                logger(
                    "[LyricLazyLoad] resolved by QQMusic cache " +
                        "songKey=${request.key} costMsFromWaitStart=$costMs"
                )
                lazyWaitSongKey = null
                lazyWaitStartedAtMs = 0L
            }
            logger(
                "[LyricAsync] success marked loaded songKey=${request.key} " +
                    "lines=${result.lineCount}"
            )
            predictiveLyricsPipeline.putLoadedTrack(
                track = activePredictiveTrackLocked(request),
                lines = cachedLines,
                source = result.source.name,
                buildTimeMs = 0L
            )
            lyricsReadyState = LyricsReadyState.READY
            val runtimeApplyStartedAt = System.currentTimeMillis()
            trace(
                request.traceId,
                "runtimeApplyStart",
                "trackId=${request.trackId.ifBlank { activeTrackId }} " +
                    "songKey=${request.key} generation=${CurrentTrackRuntimeCache.currentGeneration()} " +
                    "lines=${cachedLines.size}"
            )
            CurrentTrackRuntimeCache.updateLyrics(
                songKey = request.key,
                lines = cachedLines,
                lyricSource = result.source.name,
                logger = logger
            )
            trace(
                request.traceId,
                "runtimeApplyEnd",
                "lines=${cachedLines.size} words=${cachedLines.sumOf { it.words.size }} " +
                    "trackId=${request.trackId.ifBlank { activeTrackId }} songKey=${request.key} " +
                    "generation=${CurrentTrackRuntimeCache.currentGeneration()} " +
                    "costMs=${System.currentTimeMillis() - runtimeApplyStartedAt}"
            )
            logger(
                "[LyricsFastPath] runtime apply done songKey=${request.key} " +
                    "costMs=${System.currentTimeMillis() - runtimeApplyStartedAt} " +
                    "lines=${cachedLines.size} hasWordTiming=${cachedLines.any { it.words.isNotEmpty() }}"
            )
            logger(
                "[LyricsState] index ready trackId=${request.trackId.ifBlank { activeTrackId }} " +
                    "songKey=${request.key} lines=${cachedLines.size} " +
                    "generation=${CurrentTrackRuntimeCache.currentGeneration()}"
            )
            logger("[LyricsState] lyricsReady=true")
            lyricsReadyState = LyricsReadyState.READY
            trace(
                request.traceId,
                "lyricsReadyGateReady",
                "trackId=${request.trackId.ifBlank { activeTrackId }} " +
                    "generation=${CurrentTrackRuntimeCache.currentGeneration()} " +
                    "lines=${cachedLines.size}"
            )
            onLyricsReady(lyricsReadyGateSnapshotLocked())
        } else if (result.retryable) {
            lyricsReadyState = if (isCooldownBlockedReason(result.failureReason)) {
                logger("[LyricsState] cooldown blocked retry only songKey=${request.key}")
                LyricsReadyState.COOLDOWN_BLOCKED
            } else {
                LyricsReadyState.FAILED
            }
            retryableFailureSongKey = request.key
            retryableFailureReason = result.failureReason.ifBlank { "lyrics retry pending" }
            retryableFailureAtMs = System.currentTimeMillis()
            lastSource = result.source
            loadedSongKey = null
            if (retryableFailureReason == WAITING_QQMUSIC_LYRIC_CACHE_REASON) {
                startLazyWaitIfNeededLocked(request.key)
            }
            recoveryEngine.onActiveSongNoLyrics(
                songKey = request.key,
                trackId = "",
                title = request.title,
                artist = request.artist,
                album = request.album,
                reason = retryableFailureReason,
                qrcGeneration = QrcDirectoryGeneration.current()
            )
            CurrentTrackRuntimeCache.updateRecovery(
                songKey = request.key,
                recoveryState = recoveryEngine.snapshot().state.name,
                logger = logger
            )
            if (result.source != LyricSource.QRC) {
                cachedLines = emptyList()
            }
            logger(
                "[LyricAsync] retryable empty, not marking loaded " +
                    "songKey=${request.key} reason=$retryableFailureReason"
            )
            trace(
                request.traceId,
                "readyGateFailed",
                "trackId=${request.trackId.ifBlank { activeTrackId }} songKey=${request.key} " +
                    "generation=${CurrentTrackRuntimeCache.currentGeneration()} " +
                    "reason=$retryableFailureReason state=${lyricsReadyState.name}"
            )
            onLyricsReady(lyricsReadyGateSnapshotLocked())
        } else {
            lyricsReadyState = LyricsReadyState.FAILED
            finalEmptySongKey = request.key
            finalEmptyReason = result.failureReason.ifBlank { "no lyrics final" }
            loadedSongKey = request.key
            lastSource = result.source
            cachedLines = emptyList()
            if (lazyWaitSongKey == request.key) {
                lazyWaitSongKey = null
                lazyWaitStartedAtMs = 0L
            }
            recoveryEngine.onFinalFailure(request.key, finalEmptyReason)
            CurrentTrackRuntimeCache.updateRecovery(
                songKey = request.key,
                recoveryState = recoveryEngine.snapshot().state.name,
                logger = logger
            )
            logger(
                "[LyricAsync] final empty, marked loaded " +
                    "songKey=${request.key} reason=$finalEmptyReason"
            )
            trace(
                request.traceId,
                "readyGateFailed",
                "trackId=${request.trackId.ifBlank { activeTrackId }} songKey=${request.key} " +
                    "generation=${CurrentTrackRuntimeCache.currentGeneration()} " +
                    "reason=$finalEmptyReason state=${lyricsReadyState.name}"
            )
            onLyricsReady(lyricsReadyGateSnapshotLocked())
        }
        lastLoggedLine = null
    }

    private fun loadLyricBlocking(request: LyricLoadRequest): LyricLoadResult {
        val blockingStartedAt = System.currentTimeMillis()
        val title = request.title
        val artist = request.artist
        val album = request.album

        if (shouldCancelRequest(request, "lrcLookup")) {
            return staleLyricResult()
        }
        val lrcStartedAt = System.currentTimeMillis()
        if (applyScannedCacheIfAvailable(title, artist)) {
            trace(
                request.traceId,
                "lrcLookup",
                "result=hit costMs=${System.currentTimeMillis() - lrcStartedAt} " +
                    "lines=${cachedLines.size}"
            )
            return LyricLoadResult(
                lines = cachedLines,
                lineCount = cachedLines.size,
                source = LyricSource.LRC
            )
        }

        logger("[Lyric] load title=$title artist=$artist")
        logger("[Lyric] title=$title")
        logger("[Lyric] artist=$artist")

        val directory = resolveLyricDirectory()
        logger("[Lyric] lyric directory=${directory.absolutePath}")
        logger("[Lyric] directory exists=${directory.exists()}")

        val matchedFile = findMatchedFile(directory, title, artist)
        if (matchedFile == null) {
            logger("[Lyric] no lyric file matched")
            trace(
                request.traceId,
                "lrcLookup",
                "result=miss costMs=${System.currentTimeMillis() - lrcStartedAt}"
            )
            if (!isLatestRequest(request)) {
                shouldCancelRequest(request, "qrcLookup")
                return staleLyricResult()
            }
            if (QrcMaintenanceCoordinator.isRunning()) {
                logger("[LyricAsync] maintenance busy foreground cache-only songKey=${request.key}")
                trace(request.traceId, "maintenance", "result=busy action=cache_only")
                val cacheOnlyResult = qrcLyricManager.loadCacheOnlyWithResult(
                    title = title,
                    artist = artist,
                    album = album,
                    traceId = request.traceId,
                    shouldCancel = { shouldCancelRequest(request, "qrcCacheOnly") }
                )
                if (cacheOnlyResult.success) {
                    val cachedQrcLines = cacheOnlyResult.lines.map {
                        LyricLine(
                            timeMs = it.timeMs,
                            text = it.text,
                            durationMs = it.durationMs,
                            words = it.words,
                            translation = it.translation,
                            romanization = it.romanization
                        )
                    }
                    trace(
                        request.traceId,
                        "qrcLookup",
                        "result=hit reason=cache_only lines=${cachedQrcLines.size}"
                    )
                    return LyricLoadResult(
                        lines = cachedQrcLines,
                        lineCount = cachedQrcLines.size,
                        source = LyricSource.QRC,
                        retryable = false,
                        failureReason = "cache only hit during maintenance"
                    )
                }
                return LyricLoadResult(
                    lines = emptyList(),
                    lineCount = 0,
                    source = LyricSource.NONE,
                    retryable = true,
                    failureReason = "maintenance busy"
                )
            }
            val qrcStartedAt = System.currentTimeMillis()
            trace(request.traceId, "qrcLookup", "result=start")
            trace(request.traceId, "qrcLookupStart", "songKey=${request.key}")
            TrackCapabilityTracker.onLyricLookupStart(request.key, request.trackId)
            val qrcResult = qrcLyricManager.loadWithResult(
                title,
                artist,
                album,
                request.traceId,
                shouldCancel = { shouldCancelRequest(request, "qrcLookup") },
                allowIndexRefresh = false
            )
            val qrcLines = if (qrcResult.success) {
                qrcResult.lines.map {
                    LyricLine(
                        timeMs = it.timeMs,
                        text = it.text,
                        durationMs = it.durationMs,
                        words = it.words,
                        translation = it.translation,
                        romanization = it.romanization
                    )
                }
            } else {
                emptyList()
            }
            trace(
                request.traceId,
                "qrcLookup",
                "result=${if (qrcResult.success) "hit" else "miss"} " +
                    "reason=${qrcResult.reason} retryable=${qrcResult.retryable} " +
                    "lines=${qrcLines.size} costMs=${System.currentTimeMillis() - qrcStartedAt}"
            )
            trace(
                request.traceId,
                "qrcLookupEnd",
                "result=${if (qrcResult.success) "hit" else "miss"} " +
                    "lines=${qrcLines.size} costMs=${System.currentTimeMillis() - qrcStartedAt}"
            )
            TrackCapabilityTracker.onLyricLookupDone(
                songKey = request.key,
                trackId = request.trackId,
                success = qrcResult.success,
                reason = qrcResult.reason,
                costMs = System.currentTimeMillis() - qrcStartedAt,
                lineCount = qrcLines.size
            )
            return LyricLoadResult(
                lines = qrcLines,
                lineCount = qrcLines.size,
                source = if (qrcResult.success) LyricSource.QRC else LyricSource.NONE,
                retryable = qrcResult.retryable,
                failureReason = qrcResult.reason
            )
        }

        logger("[Lyric] matched file=${matchedFile.absolutePath}")
        trace(
            request.traceId,
            "lrcLookup",
            "result=hit file=${matchedFile.name} costMs=${System.currentTimeMillis() - lrcStartedAt}"
        )
        if (shouldCancelRequest(request, "lrcParse")) {
            return staleLyricResult()
        }
        val parsedLines = parseLrc(matchedFile)
        logger("[Lyric] parsed lines count=${parsedLines.size}")
        trace(
            request.traceId,
            "parsed",
            "source=LRC lines=${parsedLines.size} " +
                "totalCostMs=${System.currentTimeMillis() - blockingStartedAt}"
        )
        return LyricLoadResult(
            lines = parsedLines,
            lineCount = parsedLines.size,
            source = LyricSource.LRC,
            retryable = false,
            failureReason = if (parsedLines.isEmpty()) "empty lrc" else ""
        )
    }

    private fun loadLyricFastPath(request: LyricLoadRequest): LyricLoadResult {
        val totalStartedAt = System.currentTimeMillis()
        if (shouldCancelRequest(request, "parsedCache")) {
            return staleLyricResult()
        }
        parsedCacheGet(request)?.let { cached ->
            val costMs = System.currentTimeMillis() - totalStartedAt
            trace(
                request.traceId,
                "parsedCache",
                "result=hit costMs=$costMs lines=${cached.result.lineCount}"
            )
            trace(
                request.traceId,
                "qrcLookupStart",
                "trackId=${request.trackId} songKey=${request.key} source=parsed_cache"
            )
            trace(
                request.traceId,
                "qrcFileFound",
                "trackId=${request.trackId} songKey=${request.key} " +
                    "source=parsed_cache lines=${cached.result.lineCount}"
            )
            trace(
                request.traceId,
                "qrcDecryptStart",
                "trackId=${request.trackId} songKey=${request.key} " +
                    "source=parsed_cache reason=skip_parsed_cache"
            )
            trace(
                request.traceId,
                "qrcDecryptEnd",
                "trackId=${request.trackId} songKey=${request.key} " +
                    "source=parsed_cache result=skipped reason=skip_parsed_cache costMs=0"
            )
            trace(
                request.traceId,
                "qrcParseStart",
                "trackId=${request.trackId} songKey=${request.key} " +
                    "source=parsed_cache reason=skip_parsed_cache"
            )
            trace(
                request.traceId,
                "qrcParseEnd",
                "trackId=${request.trackId} songKey=${request.key} " +
                    "source=parsed_cache result=skipped lines=${cached.result.lineCount} costMs=0"
            )
            trace(
                request.traceId,
                "indexBuildStart",
                "trackId=${request.trackId} songKey=${request.key} " +
                    "source=parsed_cache lines=${cached.result.lineCount}"
            )
            trace(
                request.traceId,
                "indexBuildEnd",
                "trackId=${request.trackId} songKey=${request.key} " +
                    "source=parsed_cache lines=${cached.result.lineCount} " +
                    "hasWordTiming=${cached.hasWordTiming} costMs=0"
            )
            logger(
                "[LyricsFastPath] ready totalCostMs=$costMs " +
                    "songKey=${request.key} source=parsed_cache lines=${cached.result.lineCount}"
            )
            TrackCapabilityTracker.onLyricParseDone(
                songKey = request.key,
                trackId = request.trackId,
                success = cached.result.lineCount > 0,
                lineCount = cached.result.lineCount,
                hasWordTiming = cached.hasWordTiming,
                parseCostMs = costMs,
                reason = if (cached.result.lineCount > 0) "" else "parsed cache empty"
            )
            return cached.result
        }
        trace(request.traceId, "parsedCache", "result=miss")

        val lookupStartedAt = System.currentTimeMillis()
        logger("[LyricsState] parse start trackId=${request.trackId} songKey=${request.key}")
        trace(request.traceId, "qrcParseStart", "songKey=${request.key} source=fastPath")
        logger("[LyricsFastPath] lookup start songKey=${request.key}")
        if (shouldCancelRequest(request, "lookup")) {
            return staleLyricResult()
        }
        val result = loadLyricBlocking(request)
        val lookupCostMs = System.currentTimeMillis() - lookupStartedAt
        logger(
            "[LyricsFastPath] lookup done songKey=${request.key} " +
                "costMs=$lookupCostMs lines=${result.lineCount} source=${result.source}"
        )

        if (result.lines.isNotEmpty()) {
            val parseStartedAt = System.currentTimeMillis()
            lyricsReadyState = LyricsReadyState.PARSING
            val parseCostMs = System.currentTimeMillis() - parseStartedAt
            logger(
                "[LyricsFastPath] parse done songKey=${request.key} " +
                    "costMs=$parseCostMs lines=${result.lineCount}"
            )
            trace(
                request.traceId,
                "qrcParseEnd",
                "result=success lines=${result.lineCount} costMs=$parseCostMs"
            )

            val indexStartedAt = System.currentTimeMillis()
            logger("[LyricsState] index build start trackId=${request.trackId} songKey=${request.key}")
            trace(request.traceId, "indexBuildStart", "lines=${result.lineCount}")
            lyricsReadyState = LyricsReadyState.INDEX_BUILDING
            val hasWordTiming = result.lines.any { it.words.isNotEmpty() }
            val indexCostMs = System.currentTimeMillis() - indexStartedAt
            logger(
                "[LyricsFastPath] index build done songKey=${request.key} " +
                    "costMs=$indexCostMs hasWordTiming=$hasWordTiming"
            )
            trace(
                request.traceId,
                "indexBuildEnd",
                "lines=${result.lineCount} hasWordTiming=$hasWordTiming costMs=$indexCostMs"
            )
            TrackCapabilityTracker.onLyricParseDone(
                songKey = request.key,
                trackId = request.trackId,
                success = true,
                lineCount = result.lineCount,
                hasWordTiming = hasWordTiming,
                parseCostMs = parseCostMs,
                reason = ""
            )
            parsedCachePut(request, result)
        } else {
            lyricsReadyState = LyricsReadyState.FAILED
            trace(
                request.traceId,
                "qrcParseEnd",
                "result=failed reason=${result.failureReason.ifBlank { "empty lyrics" }}"
            )
            TrackCapabilityTracker.onLyricParseDone(
                songKey = request.key,
                trackId = request.trackId,
                success = false,
                lineCount = 0,
                hasWordTiming = false,
                parseCostMs = 0L,
                reason = result.failureReason.ifBlank { "empty lyrics" }
            )
            logger(
                "[LyricsFastPath] failed songKey=${request.key} " +
                    "reason=${result.failureReason.ifBlank { "empty lyrics" }}"
            )
        }

        logger(
            "[LyricsFastPath] ready totalCostMs=${System.currentTimeMillis() - totalStartedAt} " +
                "songKey=${request.key} lines=${result.lineCount}"
        )
        return result
    }

    private fun applyPredictiveLyricsLocked(
        key: String,
        title: String,
        artist: String,
        album: String,
        trackId: String,
        durationMs: Long,
        positionMs: Long
    ): Boolean {
        val track = PredictiveLyricsTrack(
            trackId = trackId,
            songKey = key,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs
        )
        val result = predictiveLyricsPipeline.applyIfAvailable(track) ?: return false
        cachedKey = key
        cachedLines = result.entry.lines
        loadedSongKey = key
        lastSource = runCatching { LyricSource.valueOf(result.entry.source) }
            .getOrDefault(LyricSource.QRC)
        retryableFailureSongKey = null
        retryableFailureReason = ""
        retryableFailureAtMs = 0L
        finalEmptySongKey = null
        finalEmptyReason = ""
        lazyWaitSongKey = null
        lazyWaitStartedAtMs = 0L
        lastLoggedLine = null
        recoveryEngine.onLyricLoaded(key, cachedLines.size)
        logger(
            "[PredictiveLyrics] apply hit songKey=$key applyCostMs=${result.applyCostMs} " +
                "linesCount=${cachedLines.size} " +
                "hasWordTiming=${cachedLines.any { it.words.isNotEmpty() }}"
        )
        lyricsReadyState = LyricsReadyState.READY
        val runtimeApplyStartedAt = System.currentTimeMillis()
        CurrentTrackRuntimeCache.applyPredictiveLyrics(
            songKey = key,
            lines = cachedLines,
            lyricSource = result.entry.source,
            positionMs = positionMs,
            logger = logger
        )
        logger(
            "[LyricsFastPath] runtime apply done songKey=$key " +
                "costMs=${System.currentTimeMillis() - runtimeApplyStartedAt} " +
                "lines=${cachedLines.size} hasWordTiming=${cachedLines.any { it.words.isNotEmpty() }}"
        )
        logger(
            "[LyricsState] index ready trackId=$trackId songKey=$key " +
                "lines=${cachedLines.size} generation=${CurrentTrackRuntimeCache.currentGeneration()}"
        )
        logger("[LyricsState] lyricsReady=true")
        lyricsReadyState = LyricsReadyState.READY
        trace(
            activeLyricTraceId.ifBlank { buildLyricTraceId(trackId, key, System.currentTimeMillis()) },
            "lyricsReadyGateReady",
            "trackId=$trackId generation=${CurrentTrackRuntimeCache.currentGeneration()} " +
                "lines=${cachedLines.size}"
        )
        onLyricsReady(lyricsReadyGateSnapshotLocked())
        return cachedLines.isNotEmpty()
    }

    private fun parsedCacheGet(request: LyricLoadRequest): LyricsParsedCacheEntry? {
        synchronized(this) {
            evictExpiredParsedCacheLocked()
            parsedCacheKeys(request).forEach { key ->
                val entry = parsedCache[key]
                if (entry != null && entry.matches(request)) {
                    logger(
                        "[LyricsParsedCache] hit key=$key songKey=${request.key} " +
                            "lines=${entry.result.lineCount}"
                    )
                    return entry
                }
            }
            logger("[LyricsParsedCache] miss songKey=${request.key}")
            return null
        }
    }

    private fun parsedCachePut(
        request: LyricLoadRequest,
        result: LyricLoadResult
    ) {
        if (result.lines.isEmpty()) {
            return
        }
        synchronized(this) {
            evictExpiredParsedCacheLocked()
            val entry = LyricsParsedCacheEntry(
                request = request,
                result = result,
                cachedAtMs = System.currentTimeMillis(),
                hasWordTiming = result.lines.any { it.words.isNotEmpty() }
            )
            parsedCacheKeys(request).forEach { key ->
                parsedCache[key] = entry
            }
            logger(
                "[LyricsParsedCache] put songKey=${request.key} " +
                    "keys=${parsedCacheKeys(request).joinToString(",")} " +
                    "lines=${result.lineCount} hasWordTiming=${entry.hasWordTiming}"
            )
        }
    }

    private fun evictExpiredParsedCacheLocked() {
        val now = System.currentTimeMillis()
        val iterator = parsedCache.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (now - item.value.cachedAtMs > PARSED_CACHE_TTL_MS) {
                logger(
                    "[LyricsParsedCache] evict key=${item.key} " +
                        "reason=ttl songKey=${item.value.request.key}"
                )
                iterator.remove()
            }
        }
    }

    private fun parsedCacheKeys(request: LyricLoadRequest): List<String> {
        val keys = mutableListOf<String>()
        if (request.trackId.isNotBlank()) {
            keys += "track:${request.trackId}"
        }
        keys += "fallback:${normalizeCachePart(request.title)}|" +
            "${normalizeCachePart(request.artist)}|${request.durationMs / 2_000L}"
        keys += "song:${request.key}"
        return keys.distinct()
    }

    private fun buildLyricTraceId(trackId: String, songKey: String, timestampMs: Long): String {
        val base = trackId.ifBlank { songKey }
            .replace(Regex("""\s+"""), "_")
            .replace(Regex("""[^A-Za-z0-9_.|:-]"""), "")
            .take(48)
            .ifBlank { "unknown" }
        return "$base@$timestampMs"
    }

    private fun trace(id: String, stage: String, detail: String) {
        if (id.isBlank()) {
            return
        }
        LyricTraceLogger.legacy(id, stage, detail, logger)
    }

    private fun staleLyricResult(): LyricLoadResult {
        return LyricLoadResult(
            lines = emptyList(),
            lineCount = 0,
            source = LyricSource.NONE,
            retryable = false,
            failureReason = "stale task"
        )
    }

    private fun LyricsParsedCacheEntry.matches(request: LyricLoadRequest): Boolean {
        if (this.request.trackId.isNotBlank() && request.trackId.isNotBlank()) {
            return this.request.trackId == request.trackId
        }
        if (normalizeCachePart(this.request.title) != normalizeCachePart(request.title)) {
            return false
        }
        if (normalizeCachePart(this.request.artist) != normalizeCachePart(request.artist)) {
            return false
        }
        if (this.request.durationMs > 0L && request.durationMs > 0L) {
            return this.request.durationMs / 2_000L == request.durationMs / 2_000L
        }
        return true
    }

    @Synchronized
    private fun isLatestRequest(request: LyricLoadRequest): Boolean {
        return activeSongKey == request.key && request.taskId == activeLyricsTaskId
    }

    @Synchronized
    private fun isInFlightLocked(key: String): Boolean {
        return loadingSongKey == key ||
            foregroundLoadingSongKey == key ||
            pendingRequest?.key == key
    }

    @Synchronized
    private fun shouldCancelRequest(request: LyricLoadRequest, stage: String): Boolean {
        val cancelled = !isLatestRequest(request)
        if (cancelled) {
            logger(
                "[LyricAsync] cancelled stale stage=$stage old=${request.key} " +
                    "current=${activeSongKey.orEmpty()}"
            )
            trace(
                request.traceId,
                "cancelled",
                "stage=$stage old=${request.key} current=${activeSongKey.orEmpty()}"
            )
        }
        return cancelled
    }

    @Synchronized
    fun getCurrentLine(positionMs: Long): String {
        if (activeSongKey != loadedSongKey) {
            return ""
        }
        val safePosition = positionMs.coerceAtLeast(0L)
        val lines = cachedLines
        if (lines.isEmpty()) {
            return ""
        }

        var currentLine = ""
        for (line in lines) {
            if (line.timeMs <= safePosition) {
                currentLine = line.text
            } else {
                break
            }
        }

        if (currentLine != lastLoggedLine) {
            if (LogConfig.DEBUG_VERBOSE_LOG) {
                logger("[Lyric] current line=$currentLine")
            }
            if (currentLine.isNotBlank()) {
                logger("[LyricAsync] current lyric updated")
            }
            lastLoggedLine = currentLine
        }
        activeSongKey?.let { key ->
            CurrentTrackRuntimeCache.updateCurrentLine(
                songKey = key,
                positionMs = safePosition,
                currentLine = currentLine
            )
        }

        return currentLine
    }

    @Synchronized
    fun lyricLinesSnapshot(): List<LyricLine> {
        if (activeSongKey != loadedSongKey) {
            return emptyList()
        }
        if (cachedLines.isNotEmpty()) {
            return cachedLines.toList()
        }
        return emptyList()
    }

    @Synchronized
    fun currentTrackSnapshot(trackId: String): CurrentTrackSnapshot? {
        val activeKey = activeSongKey ?: return null
        if (activeKey.isBlank()) {
            return null
        }
        return CurrentTrackSnapshot(
            trackId = trackId,
            songKey = QrcLyricUtils.buildSongKey(activeTitle, activeArtist, activeAlbum),
            title = activeTitle,
            artist = activeArtist,
            album = activeAlbum,
            trackChangedAtMs = activeTrackChangedAtMs,
            hasLyrics = activeSongKey == loadedSongKey && cachedLines.isNotEmpty()
        )
    }

    @Synchronized
    fun applyIncrementalLyrics(ready: IncrementalLyricsReady): Boolean {
        val currentTrack = ready.currentTrack ?: return false
        if (!ready.matchedCurrentTrack) {
            return false
        }
        val activeKey = lyricKey(
            currentTrack.title,
            currentTrack.artist,
            currentTrack.album
        )
        if (activeSongKey != activeKey) {
            logger(
                "[Lyric] incremental lyrics ignored reason=track changed " +
                    "songKey=${currentTrack.songKey}"
            )
            return false
        }
        val lines = ready.parsed.lines.map {
            LyricLine(
                timeMs = it.timeMs,
                text = it.text,
                durationMs = it.durationMs,
                words = it.words,
                translation = it.translation,
                romanization = it.romanization
            )
        }
        if (lines.isEmpty()) {
            return false
        }
        cachedKey = activeKey
        cachedLines = lines
        loadedSongKey = activeKey
        lastSource = LyricSource.QRC
        lastLoggedLine = null
        qrcLyricManager.removeUncertainCooldown(
            currentTrack.songKey,
            "incremental lyrics ready"
        )
        recoveryEngine.onLyricLoaded(activeKey, lines.size)
        retryableFailureSongKey = null
        retryableFailureReason = ""
        retryableFailureAtMs = 0L
        finalEmptySongKey = null
        finalEmptyReason = ""
        logger(
            "[Lyric] incremental lyrics applied " +
                "songKey=${currentTrack.songKey} lines=${lines.size}"
        )
        predictiveLyricsPipeline.putLoadedTrack(
            track = PredictiveLyricsTrack(
                trackId = currentTrack.trackId,
                songKey = activeKey,
                title = currentTrack.title,
                artist = currentTrack.artist,
                album = currentTrack.album
            ),
            lines = lines,
            source = LyricSource.QRC.name,
            buildTimeMs = 0L
        )
        CurrentTrackRuntimeCache.updateLyrics(
            songKey = activeKey,
            lines = lines,
            lyricSource = LyricSource.QRC.name,
            logger = logger
        )
        lyricsReadyState = LyricsReadyState.READY
        logger(
            "[LyricsState] index ready trackId=${currentTrack.trackId} " +
                "songKey=$activeKey lines=${lines.size} " +
                "generation=${CurrentTrackRuntimeCache.currentGeneration()}"
        )
        logger("[LyricsState] lyricsReady=true")
        trace(
            activeLyricTraceId.ifBlank {
                buildLyricTraceId(currentTrack.trackId, activeKey, System.currentTimeMillis())
            },
            "lyricsReadyGateReady",
            "trackId=${currentTrack.trackId} " +
                "generation=${CurrentTrackRuntimeCache.currentGeneration()} lines=${lines.size}"
        )
        onLyricsReady(lyricsReadyGateSnapshotLocked())
        return true
    }

    @Synchronized
    fun predictiveMetricsSnapshot(): PredictiveLyricsMetrics {
        return predictiveLyricsPipeline.metricsSnapshot()
    }

    @Synchronized
    fun currentStatusText(): String {
        val key = activeSongKey ?: return "unknown"
        return when {
            key == loadedSongKey && cachedLines.isNotEmpty() -> "loaded"
            key == loadingSongKey -> "loading"
            recoveryEngine.isActiveFor(key) -> {
                val recovery = recoveryEngine.snapshot()
                "lyric recovery: ${recovery.state.name} retry=${recovery.retryCount}"
            }
            isWaitingForQqMusicCacheLocked(key) &&
                !isLazyWaitExpiredLocked(key, System.currentTimeMillis()) ->
                "waiting QQMusic lyric cache"
            retryableFailureSongKey == key -> retryableFailureReason.ifBlank {
                "cooldown retry pending"
            }
            finalEmptySongKey == key -> finalEmptyReason.ifBlank {
                "no safe qrc candidate"
            }
            else -> "loading"
        }
    }

    private fun suggestionForStatus(status: String, reason: String): String {
        return when (status) {
            "loaded" -> "loaded"
            "waiting_qqmusic_cache" -> "open_qqmusic_lyrics"
            "lyric_recovery_active" -> "open_qqmusic_lyrics"
            "retry_pending" -> "retry_later"
            "maintenance_busy" -> "maintenance_busy"
            "no_safe_candidate" -> "no_safe_candidate"
            "loading" -> "retry_later"
            else -> when {
                reason.contains("waiting qqmusic", ignoreCase = true) -> "open_qqmusic_lyrics"
                reason.contains("maintenance", ignoreCase = true) -> "maintenance_busy"
                reason.contains("safe", ignoreCase = true) -> "no_safe_candidate"
                else -> "refresh_current_lyric"
            }
        }
    }

    private fun isRecoveryNudgeReason(reason: String): Boolean {
        val normalized = reason.lowercase(Locale.ROOT)
        return normalized.contains("lyric recovery active") ||
            normalized.contains("waiting qqmusic lyric cache") ||
            normalized.contains("qrc cooldown retry pending") ||
            normalized.contains("lyrics retry pending") ||
            normalized.contains("no safe qrc candidate")
    }

    private fun isCooldownBlockedReason(reason: String): Boolean {
        val normalized = reason.lowercase(Locale.ROOT)
        return normalized.contains("cooldown") ||
            normalized.contains("retry pending") ||
            normalized.contains("waiting qqmusic lyric cache") ||
            normalized.contains("no safe qrc candidate")
    }

    private fun lyricsReadyGateSnapshotLocked(): LyricsReadyGateSnapshot {
        val activeKey = activeSongKey.orEmpty()
        val linesReady = activeKey.isNotBlank() &&
            activeKey == loadedSongKey &&
            cachedLines.isNotEmpty() &&
            lyricsReadyState == LyricsReadyState.READY
        return LyricsReadyGateSnapshot(
            state = lyricsReadyState,
            lyricsReady = linesReady,
            trackId = activeTrackId,
            songKey = activeKey,
            generation = CurrentTrackRuntimeCache.currentGeneration(),
            lineCount = if (linesReady) cachedLines.size else 0,
            reason = if (linesReady) "" else currentUnavailableReason()
        )
    }

    private fun startLazyWaitIfNeededLocked(key: String) {
        if (lazyWaitSongKey == key && lazyWaitStartedAtMs > 0L) {
            return
        }
        lazyWaitSongKey = key
        lazyWaitStartedAtMs = System.currentTimeMillis()
        logger("[LyricLazyLoad] current song waiting for QQMusic lyric cache songKey=$key")
        logger(
            "[LyricLazyLoad] wait window start " +
                "songKey=$key durationMs=$LAZY_WAIT_WINDOW_MS"
        )
    }

    private fun isWaitingForQqMusicCacheLocked(key: String): Boolean {
        return lazyWaitSongKey == key &&
            retryableFailureSongKey == key &&
            retryableFailureReason == WAITING_QQMUSIC_LYRIC_CACHE_REASON
    }

    private fun isLazyWaitExpiredLocked(key: String, now: Long): Boolean {
        return lazyWaitSongKey == key &&
            lazyWaitStartedAtMs > 0L &&
            now - lazyWaitStartedAtMs >= LAZY_WAIT_WINDOW_MS
    }

    private fun expireLazyWaitLocked(key: String) {
        if (lazyWaitSongKey == key) {
            logger("[LyricLazyLoad] wait window expired songKey=$key")
            lazyWaitSongKey = null
            lazyWaitStartedAtMs = 0L
        }
        retryableFailureSongKey = null
        retryableFailureReason = ""
        retryableFailureAtMs = 0L
        finalEmptySongKey = key
        finalEmptyReason = "no safe qrc candidate"
        loadedSongKey = key
        cachedLines = emptyList()
        recoveryEngine.onFinalFailure(key, finalEmptyReason)
    }

    @Synchronized
    private fun retryActiveSongFromRecovery(
        reason: String,
        bypassRetryableCooldown: Boolean
    ): Boolean {
        val key = activeSongKey ?: return false
        if (cachedLines.isNotEmpty() && loadedSongKey == key) {
            logger("[LyricRecovery] retry skipped reason=has lyrics songKey=$key")
            return false
        }
        if (isInFlightLocked(key)) {
            logger("[LyricRecovery] retry skipped reason=in flight songKey=$key")
            return false
        }
        if (QrcMaintenanceCoordinator.isRunning()) {
            logger("[LyricRecovery] retry during maintenance songKey=$key action=foreground_cache_only")
        }
        if (bypassRetryableCooldown) {
            qrcLyricManager.removeUncertainCooldown(key, "lyric recovery $reason")
        } else if (retryableFailureSongKey == key && retryableFailureAtMs > 0L) {
            val retryAfter = retryableFailureAtMs + RETRYABLE_RECHECK_INTERVAL_MS
            if (System.currentTimeMillis() < retryAfter) {
                logger("[LyricRecovery] respect cooldown retryAfter=$retryAfter")
                return false
            }
        }
        requestLyricLoadAsync(
            title = activeTitle,
            artist = activeArtist,
            album = activeAlbum,
            force = true,
            reason = "lyric recovery $reason"
        )
        return true
    }

    private fun resolveLyricDirectory(): File {
        val publicDirectory = File(
            Environment.getExternalStorageDirectory(),
            "Music/Lyrics"
        )
        val appDirectory = File(
            appContext.getExternalFilesDir(null),
            "Lyrics"
        )

        logger("[Lyric] app lyric directory=${appDirectory.absolutePath}")

        if (!publicDirectory.exists()) {
            val created = publicDirectory.mkdirs()
            logger("[Lyric] create public lyric directory result=$created")
        }

        if (publicDirectory.exists() && publicDirectory.canRead()) {
            return publicDirectory
        }

        if (!appDirectory.exists()) {
            val created = appDirectory.mkdirs()
            logger("[Lyric] create app lyric directory result=$created")
        }
        return appDirectory
    }

    private fun findMatchedFile(directory: File, title: String, artist: String): File? {
        val files = try {
            directory.listFiles { file ->
                file.isFile && file.extension.equals("lrc", ignoreCase = true)
            }?.toList().orEmpty()
        } catch (exception: Exception) {
            logger("[Lyric] list lyric files failed: ${exception.message}")
            emptyList()
        }

        val cleanTitle = sanitizeFileName(title)
        val cleanArtist = sanitizeFileName(artist)
        logger("[Lyric] cleanTitle=$cleanTitle")
        logger("[Lyric] cleanArtist=$cleanArtist")

        val candidates = listOf(
            "$cleanTitle - $cleanArtist.lrc",
            "$cleanArtist - $cleanTitle.lrc",
            "$cleanTitle.lrc"
        )

        files.forEach { file ->
            logger("[Lyric] file found: ${file.name}")
        }

        candidates.forEach { candidate ->
            logger("[Lyric] candidate=$candidate")
        }

        if (files.isEmpty()) {
            return null
        }

        for (candidate in candidates) {
            val normalizedCandidate = candidate.lowercase(Locale.ROOT)
            val exactMatch = files.firstOrNull {
                it.name.lowercase(Locale.ROOT) == normalizedCandidate
            }
            if (exactMatch != null) {
                return exactMatch
            }
        }

        if (cleanTitle.isBlank()) {
            return null
        }

        val lowerTitle = cleanTitle.lowercase(Locale.ROOT)
        return files.firstOrNull {
            it.nameWithoutExtension.lowercase(Locale.ROOT).contains(lowerTitle)
        }
    }

    fun parseLrc(file: File): List<LyricLine> {
        logger("[LyricParse] file=${file.absolutePath}")
        logger("[LyricParse] size=${file.length()}")

        val bytes = try {
            file.readBytes()
        } catch (exception: Exception) {
            logger("[LyricParse] read failed: ${exception.message}")
            return emptyList()
        }

        for (decodeAttempt in decodeAttempts()) {
            logger("[LyricParse] try charset=${decodeAttempt.name}")

            val content = try {
                String(bytes, decodeAttempt.charset)
            } catch (exception: Exception) {
                logger("[LyricParse] read failed: ${exception.message}")
                continue
            }

            val normalizedContent = if (decodeAttempt.stripBom) {
                content.removePrefix("\uFEFF")
            } else {
                content
            }

            val parsedLines = parseLrcContent(normalizedContent)
            logger("[LyricParse] parsed count=${parsedLines.size}")

            if (parsedLines.isNotEmpty()) {
                logger("[LyricParse] selected charset=${decodeAttempt.name}")
                return parsedLines.sortedBy { it.timeMs }
            }
        }

        logger("[LyricParse] no valid timestamp found")
        return emptyList()
    }

    private fun parseLrcContent(content: String): List<LyricLine> {
        val parsedLines = mutableListOf<LyricLine>()

        content.lineSequence().forEachIndexed { index, rawLine ->
            if (index < RAW_LINE_LOG_LIMIT) {
                logger("[LyricParse] raw line ${index + 1}=$rawLine")
            }

            val matches = TIME_TAG_REGEX.findAll(rawLine).toList()
            if (matches.isEmpty()) {
                return@forEachIndexed
            }

            val textStart = matches.last().range.last + 1
            val text = rawLine.substring(textStart).trim()

            for (match in matches) {
                val timeMs = parseTimeTag(match)
                if (timeMs != null) {
                    logger("[LyricParse] parsed timeMs=$timeMs text=$text")
                    parsedLines += LyricLine(timeMs, text)
                }
            }
        }

        return parsedLines
    }

    private fun scanDirectories(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOfNotNull(
            File(root, "Music"),
            File(root, "Music/Lyrics"),
            File(root, "Download"),
            File(root, "Downloads"),
            File(root, "Lyrics"),
            File(root, "QQMusic"),
            File(root, "Android/data"),
            appContext.getExternalFilesDir("Lyrics")
        )
    }

    private fun scanDirectory(
        directory: File,
        depth: Int,
        foundFiles: MutableList<File>,
        visitedCount: IntArray
    ) {
        if (depth > MAX_SCAN_DEPTH || foundFiles.size >= MAX_SCAN_FILES) {
            return
        }

        if (!directory.exists() || !directory.isDirectory) {
            return
        }

        val children = try {
            directory.listFiles()
        } catch (exception: Exception) {
            logger("[LyricScan] access failed: ${directory.absolutePath} ${exception.message}")
            logger("[LyricScan] public storage access denied, use app private Lyrics folder instead")
            return
        }

        if (children == null) {
            logger("[LyricScan] access failed: ${directory.absolutePath} listFiles returned null")
            logger("[LyricScan] public storage access denied, use app private Lyrics folder instead")
            return
        }

        for (child in children) {
            if (foundFiles.size >= MAX_SCAN_FILES || visitedCount[0] >= MAX_SCAN_FILES) {
                return
            }

            visitedCount[0] += 1

            if (child.isFile && child.extension.equals("lrc", ignoreCase = true)) {
                logger("[LyricScan] found: ${child.absolutePath}")
                foundFiles += child
            } else if (child.isDirectory) {
                scanDirectory(child, depth + 1, foundFiles, visitedCount)
            }
        }
    }

    private fun calculateScore(file: File, title: String, artist: String): Int {
        val cleanTitle = sanitizeFileName(title)
        val cleanArtist = sanitizeFileName(artist)
        val fileName = file.nameWithoutExtension.lowercase(Locale.ROOT)
        val lowerTitle = cleanTitle.lowercase(Locale.ROOT)
        val lowerArtist = cleanArtist.lowercase(Locale.ROOT)
        var score = 0

        if (lowerTitle.isNotBlank() && fileName.contains(lowerTitle)) {
            score += 10
        }
        if (lowerArtist.isNotBlank() && fileName.contains(lowerArtist)) {
            score += 5
        }

        val headerText = readHeaderText(file)
        if (lowerTitle.isNotBlank() && headerText.contains(lowerTitle)) {
            score += 10
        }
        if (lowerArtist.isNotBlank() && headerText.contains(lowerArtist)) {
            score += 5
        }

        val tagTitle = TI_TAG_REGEX.find(headerText)?.groupValues?.getOrNull(1).orEmpty()
        val tagArtist = AR_TAG_REGEX.find(headerText)?.groupValues?.getOrNull(1).orEmpty()
        if (tagTitle.isNotBlank()) {
            logger("[LyricScan] tag title=$tagTitle file=${file.absolutePath}")
        }
        if (tagArtist.isNotBlank()) {
            logger("[LyricScan] tag artist=$tagArtist file=${file.absolutePath}")
        }

        return score
    }

    private fun readHeaderText(file: File): String {
        val bytes = try {
            file.readBytes()
        } catch (exception: Exception) {
            logger("[LyricScan] read header failed: ${file.absolutePath} ${exception.message}")
            return ""
        }

        for (decodeAttempt in decodeAttempts()) {
            val content = try {
                String(bytes, decodeAttempt.charset)
            } catch (_: Exception) {
                continue
            }

            val normalizedContent = if (decodeAttempt.stripBom) {
                content.removePrefix("\uFEFF")
            } else {
                content
            }

            return normalizedContent
                .lineSequence()
                .take(30)
                .joinToString("\n")
                .lowercase(Locale.ROOT)
        }

        return ""
    }

    private fun applyScannedCacheIfAvailable(title: String, artist: String): Boolean {
        val cache = scannedCache ?: return false
        if (lyricKey(cache.title, cache.artist) != lyricKey(title, artist)) {
            return false
        }

        cachedKey = lyricKey(title, artist)
        cachedLines = cache.lines
        lastLoggedLine = null
        logger("[Lyric] use scanned cached file=${cache.file.absolutePath}")
        logger("[Lyric] parsed lines count=${cachedLines.size}")
        return cachedLines.isNotEmpty()
    }

    private fun parseTimeTag(match: MatchResult): Long? {
        val minutes = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return null
        val seconds = match.groupValues.getOrNull(2)?.toLongOrNull() ?: return null
        val fraction = match.groupValues.getOrNull(3).orEmpty()
        val milliseconds = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLongOrNull()?.times(100L)
            2 -> fraction.toLongOrNull()?.times(10L)
            else -> fraction.take(3).toLongOrNull()
        } ?: 0L

        return minutes * 60_000L + seconds * 1000L + milliseconds
    }

    private fun sanitizeFileName(value: String): String {
        return value
            .replace(INVALID_FILE_NAME_CHARS_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun lyricKey(
        title: String,
        artist: String,
        album: String = ""
    ): String {
        return "${title.trim()}|${artist.trim()}|${album.trim()}"
    }

    private fun activePredictiveTrackLocked(request: LyricLoadRequest): PredictiveLyricsTrack {
        return PredictiveLyricsTrack(
            trackId = activeTrackId,
            songKey = request.key,
            title = request.title,
            artist = request.artist,
            album = request.album,
            durationMs = activeDurationMs
        )
    }

    data class LyricLine(
        val timeMs: Long,
        val text: String,
        val durationMs: Long = 0L,
        val words: List<QrcLyricWord> = emptyList(),
        val translation: String? = null,
        val romanization: String? = null
    )

    data class LyricDiagnosticSnapshot(
        val trackId: String,
        val songKey: String,
        val title: String,
        val artist: String,
        val status: String,
        val source: String,
        val reason: String,
        val lines: Int,
        val lastAttemptAt: Long,
        val nextRetryAt: Long,
        val retryCount: Int,
        val cooldownUntil: Long,
        val fuzzyIndexReady: Boolean,
        val qrcIndexLoaded: Boolean,
        val maintenanceBusy: Boolean,
        val waitingQqMusicCache: Boolean,
        val suggestion: String,
        val recoveryState: String = LyricRecoveryState.IDLE.name,
        val recoveryRetryCount: Int = 0,
        val recoveryExpiresAt: Long = 0L,
        val lastRecoveryReason: String = "",
        val recentQrcCandidateCount: Int = 0
    )

    private data class LyricCandidate(
        val file: File,
        val score: Int
    )

    private data class ScannedLyricCache(
        val title: String,
        val artist: String,
        val file: File,
        val lines: List<LyricLine>
    )

    private data class LyricLoadRequest(
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val trackId: String = "",
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val taskId: Long = 0L,
        val traceId: String = "",
        val requestedAtMs: Long = 0L,
        val trackChangedAtMs: Long = 0L,
        val foreground: Boolean = false
    )

    private data class LyricLoadResult(
        val lines: List<LyricLine>,
        val lineCount: Int,
        val source: LyricSource,
        val retryable: Boolean = false,
        val failureReason: String = ""
    )

    private data class LyricsParsedCacheEntry(
        val request: LyricLoadRequest,
        val result: LyricLoadResult,
        val cachedAtMs: Long,
        val hasWordTiming: Boolean
    )

    private enum class LyricSource {
        NONE,
        LRC,
        QRC
    }

    private data class DecodeAttempt(
        val name: String,
        val charset: Charset,
        val stripBom: Boolean = false
    )

    companion object {
        private val TIME_TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:(?:\.|:)(\d{1,3}))?]""")
        private val TI_TAG_REGEX = Regex("""\[ti:(.*?)]""", RegexOption.IGNORE_CASE)
        private val AR_TAG_REGEX = Regex("""\[ar:(.*?)]""", RegexOption.IGNORE_CASE)
        private val INVALID_FILE_NAME_CHARS_REGEX = Regex("""[\\/:*?"<>|]""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private const val MAX_SCAN_FILES = 300
        private const val MAX_SCAN_DEPTH = 5
        private const val RAW_LINE_LOG_LIMIT = 20
        private const val REPEATED_LOADING_LOG_INTERVAL_MS = 10_000L
        private const val RETRY_RATE_WINDOW_MS = 30_000L
        private const val RETRYABLE_RECHECK_INTERVAL_MS = 30_000L
        private const val WATCHER_RETRY_MIN_INTERVAL_MS = 30_000L
        private const val FULL_LYRICS_RECOVERY_NUDGE_MIN_INTERVAL_MS = 8_000L
        private const val LAZY_WAIT_WINDOW_MS = 3 * 60_000L
        private const val MAX_RETRIES_PER_WINDOW = 2
        private const val PARSED_CACHE_MAX_KEYS = 40
        private const val PARSED_CACHE_TTL_MS = 30 * 60_000L
        private const val WAITING_QQMUSIC_LYRIC_CACHE_REASON = "waiting qqmusic lyric cache"
        @Volatile
        private var scannedCache: ScannedLyricCache? = null

        private fun normalizeCachePart(value: String): String {
            return value.trim().lowercase(Locale.ROOT).replace(WHITESPACE_REGEX, " ")
        }

        private fun decodeAttempts(): List<DecodeAttempt> {
            return listOf(
                DecodeAttempt("UTF-8", Charsets.UTF_8),
                DecodeAttempt("UTF-8-BOM", Charsets.UTF_8, stripBom = true),
                DecodeAttempt("GBK", Charset.forName("GBK")),
                DecodeAttempt("GB2312", Charset.forName("GB2312"))
            )
        }
    }
}
