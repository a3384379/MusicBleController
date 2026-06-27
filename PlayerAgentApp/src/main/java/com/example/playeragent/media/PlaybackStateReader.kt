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
import com.example.playeragent.history.FastPlaybackSnapshot
import com.example.playeragent.logging.LogConfig
import com.example.playeragent.service.PlayerNotificationListenerService
import org.json.JSONObject
import java.security.MessageDigest

class PlaybackStateReader(
    context: Context,
    private val logger: (String) -> Unit,
    private val includeLyric: Boolean = true
) {

    private val appContext = context.applicationContext
    private val mediaSessionManager =
        appContext.getSystemService(MediaSessionManager::class.java)
    private val lyricManager = LyricManager(
        context = appContext,
        logger = logger
    )
    private var metadataMissingLogged = false
    private var durationMissingLogged = false
    private var lastLoggedLyric: String? = null
    private var lastTrackId: String = ""
    private var lastObservedTrack: PredictiveLyricsTrack? = null
    private var lastCandidateDiagnosticKey: String = ""
    private var lastCandidateDiagnosticAtMs: Long = 0L
    private val transitionStats = mutableMapOf<String, TransitionStat>()

    fun readPlaybackState(): JSONObject {
        val startedAtMs = SystemClock.elapsedRealtime()
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

        val selected = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.first()

        val metadata = selected.metadata
        if (metadata == null && !metadataMissingLogged) {
            metadataMissingLogged = true
            logger("[PlaybackState] metadata null package=${selected.packageName}")
        } else if (metadata != null) {
            metadataMissingLogged = false
        }
        val playbackState = selected.playbackState
        val playing = playbackState?.state == PlaybackState.STATE_PLAYING
        val position = calculatePosition(playbackState)

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
            durationMs = duration
        )
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
        if (duration <= 0L && !durationMissingLogged) {
            durationMissingLogged = true
            logger("[PlaybackState] duration missing title=$title")
        } else if (duration > 0L) {
            durationMissingLogged = false
        }
        val lyricStartedAtMs = SystemClock.elapsedRealtime()
        val lyric = if (includeLyric) {
            lyricManager.requestLyricLoadAsync(
                title = title,
                artist = artist,
                album = album,
                trackId = lastTrackId,
                durationMs = duration,
                positionMs = position
            )
            lyricManager.getCurrentLine(position)
        } else {
            ""
        }
        val cachedLyricCostMs = SystemClock.elapsedRealtime() - lyricStartedAtMs
        if (lyric != lastLoggedLyric) {
            lastLoggedLyric = lyric
            logger("[PlaybackState] lyric=$lyric")
        }
        val lyricStatus = lyricManager.currentStatusText()
        val lyricReason = lyricManager.currentUnavailableReason()
        val diagnostic = lyricManager.diagnosticSnapshot(lastTrackId)
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
            .put("duration", duration)
            .put("lyric", lyric)
            .put("lyricStatus", lyricStatus)
            .put("lyricReason", lyricReason)
            .put("lyricSuggestion", diagnostic.suggestion)
        val songKey = buildLyricSongKey(title, artist, album)
        CurrentTrackRuntimeCache.updatePlaybackState(
            trackId = lastTrackId,
            songKey = songKey,
            title = title,
            artist = artist,
            album = album,
            positionMs = position,
            durationMs = duration,
            isPlaying = playing,
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
            predictiveCandidate(selected, currentTrack, PredictiveCandidateMode.AUTO)?.let {
                lyricManager.preloadPredictiveLyrics(it)
            }
        }
        return response
    }

    fun notifyManualNextHint(seq: String? = null) {
        logger("[PredictiveLyricsCandidate] manual next requested seq=${seq.orEmpty()}")
        val selected = selectedControllerForPrediction()
        if (selected == null) {
            logCandidateUnavailable(
                source = PredictiveLyricsPipeline.SOURCE_MANUAL_NEXT_WITH_QUEUE,
                reason = "no_active_session",
                detail = "seq=${seq.orEmpty()}"
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
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        )
        val candidate = predictiveCandidate(selected, current, PredictiveCandidateMode.MANUAL_NEXT)
        if (candidate == null) {
            logger(
                "[PredictiveLyricsCandidate] rejected source=manual_next_with_queue " +
                    "reason=no_safe_queue_candidate seq=${seq.orEmpty()}"
            )
            return
        }
        logger(
            "[PredictiveLyricsCandidate] manual next hint seq=${seq.orEmpty()} " +
                "title=${candidate.track.title} artist=${candidate.track.artist}"
        )
        lyricManager.preloadPredictiveLyrics(candidate)
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
        val selected = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.first()
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
        current: PredictiveLyricsTrack,
        mode: PredictiveCandidateMode
    ): PredictiveLyricsCandidate? {
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
        current: PredictiveLyricsTrack,
        mode: PredictiveCandidateMode
    ): PredictiveLyricsCandidate? {
        logMediaSessionQueueDiagnostics(controller, current, mode)
        val queue = controller.queue
        if (queue == null) {
            logCandidateUnavailable(mode.source, "queue_null")
            return null
        }
        if (queue.isEmpty()) {
            logCandidateUnavailable(mode.source, "queue_empty")
            return null
        }
        val activeQueueId = controller.playbackState?.activeQueueItemId ?: -1L
        val currentIndexByQueueId = if (activeQueueId >= 0) {
            queue.indexOfFirst { it.queueId == activeQueueId }
        } else {
            -1
        }
        val currentIndex = if (currentIndexByQueueId >= 0) {
            currentIndexByQueueId
        } else {
            queue.indexOfFirst { item ->
                val track = trackFromDescription(item.description)
                sameTrackIdentity(track, current, allowMissingDuration = true)
            }
        }
        if (currentIndex < 0) {
            logCandidateUnavailable(mode.source, "active_queue_id_unknown")
            return null
        }
        if (currentIndex + 1 >= queue.size) {
            logCandidateUnavailable(mode.source, "no_next_queue_item")
            return null
        }
        val nextItem = queue[currentIndex + 1]
        val nextTrack = trackFromDescription(nextItem.description)
        if (nextTrack.title.isBlank()) {
            logCandidateUnavailable(mode.source, "metadata_missing")
            return null
        }
        return PredictiveLyricsCandidate(
            source = mode.source,
            confidence = 1.0,
            track = nextTrack,
            mediaId = nextItem.description.mediaId.orEmpty(),
            queueId = nextItem.queueId,
            reason = if (mode == PredictiveCandidateMode.MANUAL_NEXT) {
                "manual next with visible queue"
            } else {
                "next queue item"
            }
        )
    }

    private fun historyPredictiveCandidate(current: PredictiveLyricsTrack): PredictiveLyricsCandidate? {
        val transition = transitionStats[current.fallbackKey] ?: return null
        if (transition.count < HISTORY_TRANSITION_MIN_COUNT) {
            return null
        }
        logger(
            "[PredictiveLyricsCandidate] history transition candidate " +
                "confidence=0.7 title=${transition.next.title} " +
                "artist=${transition.next.artist} reason=repeated_transition " +
                "count=${transition.count}"
        )
        return PredictiveLyricsCandidate(
            source = PredictiveLyricsPipeline.SOURCE_HISTORY_TRANSITION,
            confidence = 0.7,
            track = transition.next,
            reason = "repeated transition count=${transition.count}"
        )
    }

    private fun observeTrackTransition(current: PredictiveLyricsTrack) {
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
                "[PredictiveLyricsCandidate] history transition learned " +
                    "from=${previous.title}|${previous.artist} " +
                    "to=${current.title}|${current.artist} count=$nextCount"
            )
        }
        lastObservedTrack = current
    }

    private fun logMediaSessionQueueDiagnostics(
        controller: MediaController,
        current: PredictiveLyricsTrack,
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
        val currentQueueItem = queue?.firstOrNull { it.queueId == activeQueueId }
        val currentQueueId = currentQueueItem?.queueId ?: -1L
        val queueHead = queue?.take(3)?.joinToString(";") { item ->
            val desc = item.description
            "id=${item.queueId},title=${desc.title?.toString().orEmpty()}," +
                "artist=${desc.subtitle?.toString().orEmpty()}," +
                "mediaId=${desc.mediaId.orEmpty()}"
        }.orEmpty()
        logger(
            "[PredictiveLyricsCandidate] queue diagnostic source=${mode.source} " +
                "hasQueue=${queue != null} queueSize=${queue?.size ?: -1} " +
                "activeQueueId=$activeQueueId currentQueueId=$currentQueueId " +
                "currentTitle=${current.title} currentArtist=${current.artist} " +
                "metadataTitlePresent=${metadataTitle.isNotBlank()} " +
                "metadataArtistPresent=${metadataArtist.isNotBlank()} " +
                "metadataAlbumPresent=${metadataAlbum.isNotBlank()} " +
                "metadataDurationMs=$metadataDuration " +
                "metadataMediaId=${metadataMediaId.ifBlank { "none" }} " +
                "queueHead=$queueHead"
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
            "[PredictiveLyricsCandidate] source=$source unavailable reason=$reason" +
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
            logger("[PredictiveLyricsCandidate] source=manual_next_with_queue unavailable reason=getActiveSessions_failed")
            return null
        }
        return controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()
    }

    private fun trackFromDescription(description: MediaDescription): PredictiveLyricsTrack {
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
            durationMs = duration
        )
    }

    private fun predictiveTrack(
        trackId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): PredictiveLyricsTrack {
        val safeAlbum = album.trim()
        return PredictiveLyricsTrack(
            trackId = trackId,
            songKey = buildLyricSongKey(title, artist, safeAlbum),
            title = title.trim(),
            artist = artist.trim(),
            album = safeAlbum,
            durationMs = durationMs
        )
    }

    private fun sameTrackIdentity(
        left: PredictiveLyricsTrack,
        right: PredictiveLyricsTrack,
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
        left: PredictiveLyricsTrack,
        right: PredictiveLyricsTrack
    ): Boolean {
        if (!left.title.equals(right.title, ignoreCase = true)) {
            return false
        }
        return left.artist.isBlank() ||
            right.artist.isBlank() ||
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
                val value = extras.get(key)
                when (value) {
                    is Number -> return value.toLong()
                    is String -> value.toLongOrNull()?.let { return it }
                }
            }
        }
        return 0L
    }

    private fun calculatePosition(playbackState: PlaybackState?): Long {
        if (playbackState == null) {
            return 0L
        }

        val basePosition = playbackState.position.coerceAtLeast(0L)
        if (playbackState.state != PlaybackState.STATE_PLAYING) {
            return basePosition
        }

        val elapsedSinceUpdate = SystemClock.elapsedRealtime() - playbackState.lastPositionUpdateTime
        val adjustedPosition = basePosition + (elapsedSinceUpdate * playbackState.playbackSpeed).toLong()
        return adjustedPosition.coerceAtLeast(0L)
    }

    private companion object {
        private const val SLOW_PLAYBACK_READ_MS = 200L
        private const val TRACK_ID_HASH_BYTES = 12
        private const val HISTORY_TRANSITION_MIN_COUNT = 2
    }

    private enum class PredictiveCandidateMode(val source: String) {
        AUTO(PredictiveLyricsPipeline.SOURCE_MEDIA_SESSION_QUEUE),
        MANUAL_NEXT(PredictiveLyricsPipeline.SOURCE_MANUAL_NEXT_WITH_QUEUE)
    }

    private data class TransitionStat(
        val next: PredictiveLyricsTrack,
        val count: Int
    )
}
