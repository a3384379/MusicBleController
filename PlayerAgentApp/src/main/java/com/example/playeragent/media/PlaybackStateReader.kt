package com.example.playeragent.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import com.example.playeragent.diagnostics.RealtimeTrace
import com.example.playeragent.diagnostics.TrackHandoffTraceCoordinator
import com.example.playeragent.history.FastPlaybackSnapshot
import com.example.playeragent.logging.LogConfig
import com.example.playeragent.service.PlayerNotificationListenerService
import org.json.JSONObject
import java.security.MessageDigest

class PlaybackStateReader(
    context: Context,
    private val logger: (String) -> Unit,
    private val includeLyric: Boolean = true,
    private val reactiveMediaController: ReactiveMediaController = ReactiveMediaController(logger),
    private val onLyricsReady: (LyricsReadyGateSnapshot) -> Unit = {},
    private val executionHub: PlayerAgentExecutionHub? = null
) {

    private val appContext = context.applicationContext
    private val mediaSessionManager =
        appContext.getSystemService(MediaSessionManager::class.java)
    private val lyricManagerHolder = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LyricManager(
            context = appContext,
            logger = logger,
            executionHub = executionHub,
            onLyricsReady = { snapshot ->
                reactiveMediaController.markLyricsTaskFinished(
                    trackId = snapshot.trackId,
                    generation = snapshot.generation,
                    ready = snapshot.lyricsReady,
                    reason = snapshot.reason
                )
                onLyricsReady(snapshot)
            }
        )
    }
    private val lyricManager: LyricManager
        get() = lyricManagerHolder.value
    private var metadataMissingLogged = false
    private var durationMissingLogged = false
    private var lastLoggedLyric: String? = null
    private var lastTrackId: String = ""
    private var lastObservedTrack: PredictiveMediaTrack? = null
    private var lastCandidateDiagnosticKey: String = ""
    private var lastCandidateDiagnosticAtMs: Long = 0L
    private var lastReactiveTraceKey: String = ""
    private val transitionStats = mutableMapOf<String, TransitionStat>()
    private val predictionSourceResolver = PredictionSourceResolver()

    fun close() {
        if (lyricManagerHolder.isInitialized()) {
            lyricManagerHolder.value.close()
        }
        transitionStats.clear()
    }

    fun readPlaybackState(): JSONObject {
        val startedAtMs = SystemClock.elapsedRealtime()
        val pendingHandoff = TrackHandoffTraceCoordinator.pendingContext(startedAtMs)
        RealtimeTrace.record(
            stage = "playbackReadStart",
            monoMs = startedAtMs,
            payloadType = "playbackState",
            result = "started",
            commandSeq = pendingHandoff?.commandSeq,
            commandType = pendingHandoff?.commandType,
            handoffId = pendingHandoff?.handoffId,
            triggerType = pendingHandoff?.triggerType?.name
        )
        verbose("[PlaybackState] GET_PLAYBACK_STATE received")

        if (mediaSessionManager == null) {
            logger("[PlaybackState] MediaSessionManager unavailable")
            return emptyResponse("MediaSessionManager unavailable")
        }

        val listenerComponent = ComponentName(
            appContext,
            PlayerNotificationListenerService::class.java
        )

        val mediaStateStartedAtMs = SystemClock.elapsedRealtime()
        val controllers = try {
            mediaSessionManager.getActiveSessions(listenerComponent)
        } catch (securityException: SecurityException) {
            logger(
                "[PlaybackState] getActiveSessions failed: Notification Access is not enabled " +
                    "or permission denied. ${securityException.message}"
            )
            return emptyResponse("Notification Access required")
        } catch (exception: Exception) {
            logger("[PlaybackState] getActiveSessions failed: ${exception.message}")
            return emptyResponse("getActiveSessions failed")
        }
        val mediaStateCostMs = SystemClock.elapsedRealtime() - mediaStateStartedAtMs

        verbose("[PlaybackState] activeSessions count=${controllers.size}")

        if (controllers.isEmpty()) {
            logger("[PlaybackState] no active media sessions")
            return emptyResponse("No active media sessions")
        }

        if (LogConfig.DEBUG_VERBOSE_LOG) {
            controllers.forEachIndexed { index, controller ->
                logController(index, controller)
            }
        }

        val selected = selectQqMusicController(controllers)
            ?: return emptyResponse("QQ Music is not active")

        val metadata = selected.metadata
        if (metadata == null && !metadataMissingLogged) {
            metadataMissingLogged = true
            logger("[PlaybackState] metadata null package=${selected.packageName}")
        } else if (metadata != null) {
            metadataMissingLogged = false
        }
        val playbackState = selected.playbackState
        val playing = playbackState?.state == PlaybackState.STATE_PLAYING
        val positionSampleElapsedMs = SystemClock.elapsedRealtime()
        val positionSampleUnixMs = System.currentTimeMillis()
        val playbackSpeed = playbackState?.playbackSpeed
            ?.takeIf { it.isFinite() && it > 0f }
            ?: 1f
        val position = calculatePosition(playbackState, positionSampleElapsedMs)

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val currentTrack = predictiveTrack(
            trackId = buildTrackId(title, artist, album),
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            mediaId = mediaId
        )
        val observedHandoff = TrackHandoffTraceCoordinator.observeMediaSessionMetadata(
            trackId = currentTrack.trackId,
            positionAnchorMs = positionSampleElapsedMs
        )
        if (observedHandoff != null && pendingHandoff == null) {
            RealtimeTrace.record(
                stage = "playbackReadStart",
                monoMs = startedAtMs,
                trackId = currentTrack.trackId,
                payloadType = "playbackState",
                result = "started",
                handoffId = observedHandoff.handoffId,
                triggerType = observedHandoff.triggerType.name,
                positionAnchorMs = positionSampleElapsedMs
            )
        }
        lastTrackId = currentTrack.trackId
        TrackCapabilityTracker.onTrackSeen(
            trackId = lastTrackId,
            protocolId = lastTrackId,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            mediaId = mediaId,
            packageName = selected.packageName.orEmpty(),
            sourceApp = sourceAppName(selected.packageName.orEmpty())
        )
        TrackCapabilityTracker.onMediaMetadata(
            trackId = lastTrackId,
            protocolId = lastTrackId,
            hasBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null ||
                metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null ||
                metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) != null,
            hasIconUri = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                .orEmpty()
                .isNotBlank(),
            hasAlbumArtUri = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                .orEmpty()
                .isNotBlank() ||
                metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    .orEmpty()
                    .isNotBlank()
        )
        observeTrackTransition(currentTrack)
        val songKey = buildLyricSongKey(title, artist, album)
        val mediaDecision = reactiveMediaController.onPlaybackObserved(
            trackId = lastTrackId,
            songKey = songKey,
            title = title,
            artist = artist,
            album = album,
            positionMs = position,
            durationMs = duration,
            isPlaying = playing
        )
        if (mediaDecision.trackChanged) {
            LyricTraceLogger.stage(
                runId = "unknown",
                trackId = lastTrackId,
                songKey = songKey,
                generation = mediaDecision.generation,
                stage = "trackChanged",
                reason = mediaDecision.reason,
                extra = mapOf(
                    "title" to title.take(48),
                    "artist" to artist.take(48),
                    "durationMs" to duration.toString()
                ),
                sink = logger
            )
        }
        val reactiveTraceKey = listOf(
            mediaDecision.generation.toString(),
            mediaDecision.trackChanged.toString(),
            mediaDecision.shouldScheduleLyrics.toString(),
            mediaDecision.reason
        ).joinToString("|")
        if ((mediaDecision.trackChanged || mediaDecision.shouldScheduleLyrics) &&
            reactiveTraceKey != lastReactiveTraceKey
        ) {
            lastReactiveTraceKey = reactiveTraceKey
            LyricTraceLogger.stage(
                runId = "unknown",
                trackId = lastTrackId,
                songKey = songKey,
                generation = mediaDecision.generation,
                stage = "reactiveEvent",
                reason = mediaDecision.reason,
                extra = mapOf(
                    "trackChanged" to mediaDecision.trackChanged.toString(),
                    "playing" to playing.toString(),
                    "positionMs" to position.toString()
                ),
                sink = logger
            )
        }
        if (duration <= 0L && !durationMissingLogged) {
            durationMissingLogged = true
            logger("[PlaybackState] duration missing title=$title")
        } else if (duration > 0L) {
            durationMissingLogged = false
        }
        val lyricStartedAtMs = SystemClock.elapsedRealtime()
        val lyric = if (includeLyric) {
            if (mediaDecision.shouldScheduleLyrics) {
                LyricTraceLogger.stage(
                    runId = "unknown",
                    trackId = lastTrackId,
                    songKey = songKey,
                    generation = mediaDecision.generation,
                    stage = "debounceFlush",
                    reason = mediaDecision.reason,
                    sink = logger
                )
                if (reactiveMediaController.markLyricsTaskStarted(
                        trackId = lastTrackId,
                        generation = mediaDecision.generation
                    )
                ) {
                    LyricTraceLogger.stage(
                        runId = "unknown",
                        trackId = lastTrackId,
                        songKey = songKey,
                        generation = mediaDecision.generation,
                        stage = "parseScheduled",
                        reason = mediaDecision.reason,
                        sink = logger
                    )
                    lyricManager.requestLyricLoadAsync(
                        title = title,
                        artist = artist,
                        album = album,
                        trackId = lastTrackId,
                        durationMs = duration,
                        positionMs = position,
                        reason = mediaDecision.reason
                    )
                }
            }
            lyricManager.getCurrentLine(position)
        } else {
            ""
        }
        reactiveMediaController.updateCurrentLine(lyric)
        val cachedLyricCostMs = SystemClock.elapsedRealtime() - lyricStartedAtMs
        if (lyric != lastLoggedLyric) {
            lastLoggedLyric = lyric
            logger("[PlaybackState] lyric=$lyric")
        }
        val lyricState = lyricManager.playbackLyricStatusSnapshot(lastTrackId)
        val lyricStatus = lyricState.statusText
        val lyricReason = lyricState.reason
        val diagnostic = lyricState.diagnostic
        val totalCostMs = SystemClock.elapsedRealtime() - startedAtMs
        if (LogConfig.DEBUG_VERBOSE_LOG || totalCostMs > SLOW_PLAYBACK_READ_MS) {
            logger(
                "[PlaybackFast] mediaStateCostMs=$mediaStateCostMs " +
                    "cachedLyricCostMs=$cachedLyricCostMs " +
                    "totalCostMs=$totalCostMs"
            )
        }

        verbose(
            "[PlaybackState] selected package=${selected.packageName}\n" +
                "playing=$playing\n" +
                "title=$title\n" +
                "artist=$artist\n" +
                "album=$album\n" +
                "position=$position\n" +
                "duration=$duration\n" +
                "lyric=$lyric"
        )

        val response = JSONObject()
            .put("type", "playbackState")
            .put("playing", playing)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("position", position)
            .put("positionSampleElapsedMs", positionSampleElapsedMs)
            .put("positionSampleUnixMs", positionSampleUnixMs)
            .put("speed", playbackSpeed.toDouble())
            .put("duration", duration)
            .put("lyric", lyric)
            .put("lyricStatus", lyricStatus)
            .put("lyricReason", lyricReason)
            .put("lyricSuggestion", diagnostic.suggestion)
        val runtimeTrack = CurrentTrackRuntimeCache.updatePlaybackState(
            trackId = lastTrackId,
            songKey = songKey,
            title = title,
            artist = artist,
            album = album,
            positionMs = position,
            positionSampleElapsedMs = positionSampleElapsedMs,
            durationMs = duration,
            isPlaying = playing,
            playbackSpeed = playbackSpeed,
            currentLine = lyric,
            lyricSource = diagnostic.source,
            lastPlaybackState = response,
            diagnosticSnapshot = diagnostic.status,
            logger = logger
        )
        if (includeLyric) {
            val loadedLines = lyricManager.lyricLinesSnapshot()
            if (loadedLines.isNotEmpty()) {
                CurrentTrackRuntimeCache.updateLyrics(
                    songKey = songKey,
                    lines = loadedLines,
                    lyricSource = diagnostic.source
                )
            }
        }
        observeQueueCandidates(selected, currentTrack)
        val readyAtMs = SystemClock.elapsedRealtime()
        val handoffTrace = TrackHandoffTraceCoordinator.contextFor(runtimeTrack.trackId)
        if (mediaDecision.trackChanged) {
            RealtimeTrace.record(
                stage = "trackIdentityAccepted",
                monoMs = readyAtMs,
                trackId = runtimeTrack.trackId,
                generation = runtimeTrack.currentTrackGeneration,
                payloadType = "mediaSession",
                result = "accepted",
                handoffId = handoffTrace?.handoffId,
                triggerType = handoffTrace?.triggerType?.name,
                positionAnchorMs = runtimeTrack.positionAnchorElapsedMs
            )
            RealtimeTrace.record(
                stage = "mediaGenerationCreated",
                monoMs = readyAtMs,
                trackId = runtimeTrack.trackId,
                generation = runtimeTrack.currentTrackGeneration,
                payloadType = "trackIdentity",
                result = "created",
                handoffId = handoffTrace?.handoffId,
                triggerType = handoffTrace?.triggerType?.name,
                positionAnchorMs = runtimeTrack.positionAnchorElapsedMs
            )
        }
        RealtimeTrace.record(
            stage = "playbackReady",
            monoMs = readyAtMs,
            trackId = runtimeTrack.trackId,
            generation = runtimeTrack.currentTrackGeneration,
            payloadType = "playbackState",
            processingMs = (readyAtMs - startedAtMs).coerceAtLeast(0L),
            result = "ready",
            reason = if (runtimeTrack.isPlaying) "playing" else "paused",
            commandSeq = handoffTrace?.commandSeq,
            commandType = handoffTrace?.commandType,
            handoffId = handoffTrace?.handoffId,
            triggerType = handoffTrace?.triggerType?.name,
            positionAnchorMs = runtimeTrack.positionAnchorElapsedMs
        )
        return response
    }

    fun notifyManualNextHint(seq: String? = null) {
        notifyManualTrackHint(PredictionDirection.NEXT, seq)
    }

    fun notifyManualPreviousHint(seq: String? = null) {
        notifyManualTrackHint(PredictionDirection.PREVIOUS, seq)
    }

    private fun notifyManualTrackHint(direction: PredictionDirection, seq: String?) {
        val mode = if (direction == PredictionDirection.NEXT) {
            PredictiveCandidateMode.MANUAL_NEXT
        } else {
            PredictiveCandidateMode.MANUAL_PREVIOUS
        }
        logger(
            "[PredictionSource] manual direction=${direction.name} " +
                "seqPresent=${!seq.isNullOrBlank()}"
        )
        val selected = selectedControllerForPrediction()
        if (selected == null) {
            logCandidateUnavailable(
                source = mode.source.wireName,
                reason = "no_active_session",
                detail = "direction=${direction.name}"
            )
            return
        }
        val metadata = selected.metadata
        val current = predictiveTrack(
            trackId = buildTrackId(
                metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
            ),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        )
        val candidate = predictiveCandidate(selected, current, mode)
        if (candidate == null) {
            logger(
                "[PredictionSource] rejected source=${mode.source.wireName} " +
                    "reason=no_safe_queue_candidate direction=${direction.name}"
            )
            return
        }
        logger(
            "[PredictionSource] selected candidateId=${candidate.candidateKey} " +
                "identityDigest=${candidate.track.identityDigest} " +
                "source=${candidate.source.wireName} confidence=${candidate.confidence.name}"
        )
        lyricManager.preloadPredictiveMedia(candidate)
    }

    fun lyricLinesSnapshot(): List<LyricManager.LyricLine> {
        return lyricManager.lyricLinesSnapshot()
    }

    fun runtimeLyricLinesSnapshot(): List<LyricManager.LyricLine> {
        val runtimeLines = CurrentTrackRuntimeCache.lyricLinesSnapshot()
        if (runtimeLines.isNotEmpty()) {
            return runtimeLines
        }
        return lyricManager.lyricLinesSnapshot()
    }

    fun lyricUnavailableReason(): String {
        return lyricManager.currentUnavailableReason()
    }

    fun lyricStatusText(): String {
        return lyricManager.currentStatusText()
    }

    fun lyricDiagnosticSnapshot(): LyricManager.LyricDiagnosticSnapshot {
        return lyricManager.diagnosticSnapshot(lastTrackId)
    }

    fun readFastPlaybackSnapshot(): FastPlaybackSnapshot? {
        val manager = mediaSessionManager ?: return null
        val listenerComponent = ComponentName(
            appContext,
            PlayerNotificationListenerService::class.java
        )
        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (securityException: SecurityException) {
            logger(
                "[History] getActiveSessions failed: Notification Access is not enabled " +
                    "or permission denied. ${securityException.message}"
            )
            return null
        } catch (exception: Exception) {
            logger("[History] getActiveSessions failed: ${exception.message}")
            return null
        }
        if (controllers.isEmpty()) {
            return null
        }
        val selected = selectQqMusicController(controllers) ?: return null
        val metadata = selected.metadata ?: return FastPlaybackSnapshot(
            packageName = selected.packageName.orEmpty(),
            title = "",
            artist = "",
            album = "",
            playing = false,
            stopped = selected.playbackState?.state == PlaybackState.STATE_STOPPED,
            positionMs = 0L,
            durationMs = 0L
        )
        val playbackState = selected.playbackState
        val state = playbackState?.state
        return FastPlaybackSnapshot(
            packageName = selected.packageName.orEmpty(),
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            playing = state == PlaybackState.STATE_PLAYING,
            stopped = state == PlaybackState.STATE_STOPPED,
            positionMs = calculatePosition(playbackState),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        )
    }

    fun currentTrackSnapshot(): CurrentTrackSnapshot? {
        return CurrentTrackRuntimeCache.trackSnapshot()
            ?: lyricManager.currentTrackSnapshot(lastTrackId)
    }

    fun runtimeCacheSnapshot(): CurrentTrackRuntimeCacheSnapshot {
        return CurrentTrackRuntimeCache.snapshot()
    }

    fun applyIncrementalLyrics(ready: IncrementalLyricsReady): Boolean {
        return lyricManager.applyIncrementalLyrics(ready)
    }

    fun retryActiveLyricsFromWatcher(reason: String): Boolean {
        return lyricManager.retryActiveSongFromWatcher(reason)
    }

    fun notifyLyricIncrementalBatchDone(groupIds: Collection<String>) {
        lyricManager.notifyIncrementalBatchDone(groupIds)
    }

    fun lyricRecoverySnapshot(): LyricRecoverySnapshot {
        return lyricManager.recoverySnapshot()
    }

    fun lyricsReadyGateSnapshot(): LyricsReadyGateSnapshot {
        return lyricManager.lyricsReadyGateSnapshot()
    }

    fun predictiveLyricsMetricsSnapshot(): PredictiveLyricsMetrics {
        return lyricManager.predictiveMetricsSnapshot()
    }

    fun manualRefreshCurrentLyric(): Boolean {
        return lyricManager.manualRefreshCurrentLyric()
    }

    fun nudgeLyricRecoveryFromFullLyricsRequest(): Boolean {
        return lyricManager.nudgeRecoveryFromFullLyricsRequest()
    }

    private fun logController(index: Int, controller: MediaController) {
        val state = controller.playbackState?.state
        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()

        logger(
            "[PlaybackState] session[$index]\n" +
                "package=${controller.packageName}\n" +
                "state=$state\n" +
                "title=$title\n" +
                "artist=$artist\n" +
                "album=$album"
        )
    }

    private fun verbose(message: String) {
        if (LogConfig.DEBUG_VERBOSE_LOG) {
            logger(message)
        }
    }

    private fun emptyResponse(reason: String): JSONObject {
        logger("[PlaybackState] returning empty response: $reason")
        return JSONObject()
            .put("type", "playbackState")
            .put("playing", false)
            .put("title", "")
            .put("artist", "")
            .put("album", "")
            .put("position", 0L)
            .put("duration", 0L)
            .put("lyric", "")
    }

    private fun buildTrackId(
        title: String,
        artist: String,
        album: String
    ): String {
        val source = listOf(title, artist, album).joinToString("|").ifBlank { "unknown" }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .take(TRACK_ID_HASH_BYTES)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sourceAppName(packageName: String): String {
        return when {
            packageName.contains("qqmusic", ignoreCase = true) -> "QQ音乐"
            packageName.isBlank() -> "unknown"
            else -> packageName
        }
    }

    private fun buildLyricSongKey(
        title: String,
        artist: String,
        album: String
    ): String {
        return "${title.trim()}|${artist.trim()}|${album.trim()}"
    }

    private fun predictiveCandidate(
        controller: MediaController,
        current: PredictiveMediaTrack,
        mode: PredictiveCandidateMode
    ): PredictionCandidate? {
        val queueCandidate = queuePredictiveCandidate(controller, current, mode)
        if (queueCandidate != null) {
            return queueCandidate
        }
        if (mode == PredictiveCandidateMode.AUTO) {
            return historyPredictiveCandidate(current)
        }
        return null
    }

    private fun queuePredictiveCandidate(
        controller: MediaController,
        current: PredictiveMediaTrack,
        mode: PredictiveCandidateMode
    ): PredictionCandidate? {
        logMediaSessionQueueDiagnostics(controller, current, mode)
        val queue = controller.queue
        if (queue == null) {
            logCandidateUnavailable(mode.source.wireName, "queue_null")
            return null
        }
        if (queue.isEmpty()) {
            logCandidateUnavailable(mode.source.wireName, "queue_empty")
            return null
        }
        val snapshot = PredictionQueueSnapshot(
            activeQueueItemId = controller.playbackState?.activeQueueItemId ?: -1L,
            items = queue.map { item ->
                PredictionQueueItem(
                    queueItemId = item.queueId,
                    mediaId = item.description.mediaId.orEmpty(),
                    track = trackFromDescription(item.description)
                )
            }
        )
        return predictionSourceResolver.resolve(
            snapshot = snapshot,
            current = current,
            direction = mode.direction,
            source = mode.source
        ) ?: run {
            logCandidateUnavailable(mode.source.wireName, "no_directional_queue_item")
            null
        }
    }

    private fun historyPredictiveCandidate(current: PredictiveMediaTrack): PredictionCandidate? {
        val transition = transitionStats[current.fallbackKey] ?: return null
        if (transition.count < HISTORY_TRANSITION_MIN_COUNT) {
            return null
        }
        val createdAt = SystemClock.elapsedRealtime()
        val candidateKey = PredictionIdentity.candidateKey(
            transition.next,
            -1L
        )
        logger(
            "[PredictionSource] history candidateId=$candidateKey " +
                "identityDigest=${transition.next.identityDigest} confidence=WEAK " +
                "count=${transition.count}"
        )
        return PredictionCandidate(
            candidateKey = candidateKey,
            source = PredictionSource.HISTORY_TRANSITION,
            confidence = PredictionConfidence.WEAK,
            direction = PredictionDirection.NEXT,
            track = transition.next,
            createdElapsedMs = createdAt,
            expiresElapsedMs = createdAt + PredictionSourceResolver.DEFAULT_CANDIDATE_TTL_MS
        )
    }

    private fun observeTrackTransition(current: PredictiveMediaTrack) {
        if (current.title.isBlank()) {
            return
        }
        val previous = lastObservedTrack
        if (previous != null &&
            !sameTrackTitleArtist(previous, current) &&
            !sameTrackIdentity(previous, current, allowMissingDuration = true)
        ) {
            val key = previous.fallbackKey
            val existing = transitionStats[key]
            val nextCount = if (existing != null &&
                sameTrackIdentity(existing.next, current, allowMissingDuration = true)
            ) {
                existing.count + 1
            } else {
                1
            }
            transitionStats[key] = TransitionStat(current, nextCount)
            logger(
                "[PredictionSource] history transition learned " +
                    "fromDigest=${previous.identityDigest} " +
                    "toDigest=${current.identityDigest} count=$nextCount"
            )
        }
        lastObservedTrack = current
    }

    private fun observeQueueCandidates(
        controller: MediaController,
        current: PredictiveMediaTrack
    ) {
        val next = predictiveCandidate(controller, current, PredictiveCandidateMode.AUTO)
        if (next != null) {
            lyricManager.preloadPredictiveMedia(next)
        }
        val previous = queuePredictiveCandidate(
            controller,
            current,
            PredictiveCandidateMode.AUTO_PREVIOUS
        )
        if (previous != null) {
            lyricManager.preloadPredictiveMedia(previous)
        }
    }

    private fun logMediaSessionQueueDiagnostics(
        controller: MediaController,
        current: PredictiveMediaTrack,
        mode: PredictiveCandidateMode
    ) {
        val metadata = controller.metadata
        val queue = controller.queue
        val activeQueueId = controller.playbackState?.activeQueueItemId ?: -1L
        val metadataTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val metadataArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val metadataAlbum = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val metadataDuration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val metadataMediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val currentQueueId = queue?.firstOrNull { it.queueId == activeQueueId }?.queueId ?: -1L
        val completeQueueIdentityCount = queue?.count { item ->
            !item.description.mediaId.isNullOrBlank() &&
                !item.description.title.isNullOrBlank() &&
                !item.description.subtitle.isNullOrBlank()
        } ?: 0
        logger(
            "[PredictionSource] queue diagnostic source=${mode.source.wireName} " +
                "hasQueue=${queue != null} queueSize=${queue?.size ?: -1} " +
                "activeQueueId=$activeQueueId currentQueueId=$currentQueueId " +
                "currentIdentityDigest=${current.identityDigest} " +
                "metadataTitlePresent=${metadataTitle.isNotBlank()} " +
                "metadataArtistPresent=${metadataArtist.isNotBlank()} " +
                "metadataAlbumPresent=${metadataAlbum.isNotBlank()} " +
                "metadataDurationMs=$metadataDuration " +
                "metadataMediaIdPresent=${metadataMediaId.isNotBlank()} " +
                "completeQueueIdentityCount=$completeQueueIdentityCount"
        )
    }

    private fun logCandidateUnavailable(
        source: String,
        reason: String,
        detail: String = ""
    ) {
        val now = SystemClock.elapsedRealtime()
        val key = "$source|$reason|$detail"
        if (key == lastCandidateDiagnosticKey && now - lastCandidateDiagnosticAtMs < 10_000L) {
            return
        }
        lastCandidateDiagnosticKey = key
        lastCandidateDiagnosticAtMs = now
        logger(
            "[PredictionSource] source=$source unavailable reason=$reason" +
                if (detail.isNotBlank()) " $detail" else ""
        )
    }

    private fun selectedControllerForPrediction(): MediaController? {
        val manager = mediaSessionManager ?: return null
        val listenerComponent = ComponentName(
            appContext,
            PlayerNotificationListenerService::class.java
        )
        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (exception: Exception) {
            logger("[PredictionSource] source=manual_queue unavailable reason=getActiveSessions_failed")
            return null
        }
        return selectQqMusicController(controllers)
    }

    private fun selectQqMusicController(
        controllers: List<MediaController>
    ): MediaController? {
        val qqControllers = controllers.filter { it.packageName == QQ_MUSIC_PACKAGE }
        return qqControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: qqControllers.firstOrNull()
    }

    private fun trackFromDescription(description: MediaDescription): PredictiveMediaTrack {
        val title = description.title?.toString().orEmpty().trim()
        val artist = description.subtitle?.toString().orEmpty().trim()
        val album = description.description?.toString().orEmpty().trim()
        val duration = durationFromExtras(description.extras)
        val mediaId = description.mediaId.orEmpty()
        val trackId = if (mediaId.isNotBlank()) {
            buildTrackId(title, artist, mediaId)
        } else {
            buildTrackId(title, artist, album)
        }
        return predictiveTrack(
            trackId = trackId,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            mediaId = mediaId
        )
    }

    private fun predictiveTrack(
        trackId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mediaId: String = ""
    ): PredictiveMediaTrack {
        val safeAlbum = album.trim()
        return PredictiveMediaTrack(
            trackId = trackId,
            songKey = buildLyricSongKey(title, artist, safeAlbum),
            title = title.trim(),
            artist = artist.trim(),
            album = safeAlbum,
            durationMs = durationMs,
            mediaId = mediaId
        )
    }

    private fun sameTrackIdentity(
        left: PredictiveMediaTrack,
        right: PredictiveMediaTrack,
        allowMissingDuration: Boolean
    ): Boolean {
        if (!sameTrackTitleArtist(left, right)) {
            return false
        }
        if (allowMissingDuration && (left.durationMs <= 0L || right.durationMs <= 0L)) {
            return true
        }
        return left.durationMs / 2_000L == right.durationMs / 2_000L
    }

    private fun sameTrackTitleArtist(
        left: PredictiveMediaTrack,
        right: PredictiveMediaTrack
    ): Boolean {
        if (!left.title.equals(right.title, ignoreCase = true)) {
            return false
        }
        return left.artist.isNotBlank() &&
            right.artist.isNotBlank() &&
            left.artist.equals(right.artist, ignoreCase = true)
    }

    private fun durationFromExtras(extras: Bundle?): Long {
        if (extras == null) {
            return 0L
        }
        val keys = listOf(
            MediaMetadata.METADATA_KEY_DURATION,
            "android.media.metadata.DURATION",
            "duration",
            "durationMs"
        )
        keys.forEach { key ->
            if (extras.containsKey(key)) {
                val longValue = extras.getLong(key, Long.MIN_VALUE)
                if (longValue != Long.MIN_VALUE) return longValue
                val intValue = extras.getInt(key, Int.MIN_VALUE)
                if (intValue != Int.MIN_VALUE) return intValue.toLong()
                extras.getString(key)?.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    private fun calculatePosition(
        playbackState: PlaybackState?,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        if (playbackState == null) {
            return 0L
        }

        val basePosition = playbackState.position.coerceAtLeast(0L)
        if (playbackState.state != PlaybackState.STATE_PLAYING) {
            return basePosition
        }

        val elapsedSinceUpdate = nowElapsedMs - playbackState.lastPositionUpdateTime
        val adjustedPosition = basePosition + (elapsedSinceUpdate * playbackState.playbackSpeed).toLong()
        return adjustedPosition.coerceAtLeast(0L)
    }

    private companion object {
        private const val SLOW_PLAYBACK_READ_MS = 200L
        private const val TRACK_ID_HASH_BYTES = 12
        private const val HISTORY_TRANSITION_MIN_COUNT = 2
        private const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
    }

    private enum class PredictiveCandidateMode(
        val source: PredictionSource,
        val direction: PredictionDirection
    ) {
        AUTO(PredictionSource.MEDIA_SESSION_QUEUE, PredictionDirection.NEXT),
        AUTO_PREVIOUS(PredictionSource.MEDIA_SESSION_QUEUE, PredictionDirection.PREVIOUS),
        MANUAL_NEXT(
            PredictionSource.MANUAL_NEXT_WITH_QUEUE,
            PredictionDirection.NEXT
        ),
        MANUAL_PREVIOUS(
            PredictionSource.MANUAL_PREVIOUS_WITH_QUEUE,
            PredictionDirection.PREVIOUS
        )
    }

    private data class TransitionStat(
        val next: PredictiveMediaTrack,
        val count: Int
    )
}
