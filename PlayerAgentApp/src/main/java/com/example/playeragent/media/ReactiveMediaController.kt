package com.example.playeragent.media

import android.os.SystemClock

enum class MediaEventType {
    MEDIA_METADATA_CHANGED,
    PLAYBACK_STATE_CHANGED,
    NOTIFICATION_CHANGED,
    TRACK_CHANGED,
    SEEK,
    NEXT,
    PREVIOUS,
    PLAYBACK_STARTED
}

enum class MediaTaskPriority(val wireName: String) {
    P0_COMMAND("P0 command"),
    P1_PLAYBACK_STATE("P1 playbackState"),
    P2_CURRENT_WORD("P2 currentWord"),
    P3_LYRICS_PARSE("P3 lyrics parse"),
    P4_ALBUM_ART("P4 albumArt"),
    P5_DIAGNOSTICS("P5 diagnostics")
}

enum class MediaPipelineState {
    EMPTY,
    SCHEDULED,
    RUNNING,
    READY,
    FAILED
}

data class MediaStateSnapshot(
    val trackId: String,
    val generation: Long,
    val playbackState: String,
    val currentWord: String,
    val currentLine: String,
    val lyricsState: MediaPipelineState,
    val albumArtState: MediaPipelineState,
    val lyricsReady: Boolean,
    val albumArtReady: Boolean,
    val lastUpdatedTime: Long
)

data class ReactiveMediaDecision(
    val trackChanged: Boolean,
    val generation: Long,
    val shouldScheduleLyrics: Boolean,
    val shouldScheduleAlbumArt: Boolean,
    val reason: String
)

class ReactiveMediaController(
    private val logger: (String) -> Unit,
    private val debounceWindowMs: Long = DEBOUNCE_WINDOW_MS
) {
    private val lock = Any()

    private var currentTrackId: String = ""
    private var currentSongKey: String = ""
    private var generation: Long = 0L
    private var lastEventAtMs: Long = 0L
    private var lastEventWindowStartedAtMs: Long = 0L
    private var eventsInWindow: Int = 0
    private var lastStormLogAtMs: Long = 0L

    private var metadataStableSinceMs: Long = 0L
    private var lyricsScheduledGeneration: Long = -1L
    private var lyricsInFlightTrackId: String = ""
    private var lyricsInFlightGeneration: Long = -1L
    private var lyricsState: MediaPipelineState = MediaPipelineState.EMPTY
    private var lyricsReady: Boolean = false

    private var albumArtScheduledGeneration: Long = -1L
    private var albumArtInFlightCount: Int = 0
    private var albumArtState: MediaPipelineState = MediaPipelineState.EMPTY
    private var albumArtReady: Boolean = false

    private var lastPlaybackSummary: String = ""
    private var lastCurrentLine: String = ""
    private var lastCurrentWordKey: String = ""

    fun onPlaybackObserved(
        trackId: String,
        songKey: String,
        title: String,
        artist: String,
        album: String,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ): ReactiveMediaDecision {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            recordEventLocked(MediaEventType.PLAYBACK_STATE_CHANGED, now)
            val stableTrackId = trackId.ifBlank { songKey }
            val trackChanged = stableTrackId.isNotBlank() &&
                (stableTrackId != currentTrackId || songKey != currentSongKey)
            if (trackChanged) {
                generation += 1L
                currentTrackId = stableTrackId
                currentSongKey = songKey
                metadataStableSinceMs = now
                lyricsScheduledGeneration = -1L
                lyricsInFlightTrackId = ""
                lyricsInFlightGeneration = -1L
                lyricsState = MediaPipelineState.EMPTY
                lyricsReady = false
                albumArtScheduledGeneration = -1L
                albumArtInFlightCount = 0
                albumArtState = MediaPipelineState.EMPTY
                albumArtReady = false
                lastCurrentLine = ""
                lastCurrentWordKey = ""
                logger(
                    "[Engine] event received type=${MediaEventType.TRACK_CHANGED} " +
                        "trackId=$stableTrackId generation=$generation title=${title.take(48)}"
                )
            }

            val playbackSummary = "$stableTrackId|$songKey|$title|$artist|$album|$durationMs|$isPlaying"
            if (playbackSummary != lastPlaybackSummary) {
                metadataStableSinceMs = now
                lastPlaybackSummary = playbackSummary
                logger(
                    "[Engine] event received type=${MediaEventType.MEDIA_METADATA_CHANGED} " +
                        "trackId=$stableTrackId generation=$generation"
                )
            }

            val stableForMs = now - metadataStableSinceMs
            val metadataStable = stableForMs >= debounceWindowMs || trackChanged
            val playbackStarted = isPlaying && positionMs <= PLAYBACK_START_POSITION_WINDOW_MS
            val canScheduleLyrics = metadataStable &&
                lyricsScheduledGeneration != generation &&
                lyricsInFlightGeneration != generation &&
                stableTrackId.isNotBlank() &&
                title.isNotBlank()
            val canScheduleAlbumArt = metadataStable &&
                albumArtScheduledGeneration != generation &&
                albumArtInFlightCount < MAX_ALBUM_ART_LOADS &&
                stableTrackId.isNotBlank() &&
                title.isNotBlank()

            val shouldScheduleLyrics = canScheduleLyrics &&
                (trackChanged || playbackStarted || stableForMs >= debounceWindowMs)
            val shouldScheduleAlbumArt = canScheduleAlbumArt &&
                (trackChanged || stableForMs >= debounceWindowMs)

            if (!metadataStable && !trackChanged) {
                logger(
                    "[Engine] debounce merged trackId=$stableTrackId " +
                        "ageMs=$stableForMs windowMs=$debounceWindowMs"
                )
            }
            if (shouldScheduleLyrics) {
                lyricsScheduledGeneration = generation
                lyricsState = MediaPipelineState.SCHEDULED
                logger(
                    "[Engine] task scheduled priority=${MediaTaskPriority.P3_LYRICS_PARSE.wireName} " +
                        "trackId=$stableTrackId generation=$generation reason=metadata_stable"
                )
            }
            if (shouldScheduleAlbumArt) {
                albumArtScheduledGeneration = generation
                albumArtState = MediaPipelineState.SCHEDULED
                logger(
                    "[Engine] task scheduled priority=${MediaTaskPriority.P4_ALBUM_ART.wireName} " +
                        "trackId=$stableTrackId generation=$generation reason=metadata_stable"
                )
            }

            return ReactiveMediaDecision(
                trackChanged = trackChanged,
                generation = generation,
                shouldScheduleLyrics = shouldScheduleLyrics,
                shouldScheduleAlbumArt = shouldScheduleAlbumArt,
                reason = if (metadataStable) "metadata_stable" else "debouncing"
            )
        }
    }

    fun markLyricsTaskStarted(trackId: String, generation: Long): Boolean {
        synchronized(lock) {
            if (generation != this.generation || trackIdMatchesLocked(trackId).not()) {
                logger(
                    "[Lyrics] drop generation mismatch trackId=$trackId " +
                        "generation=$generation current=${this.generation}"
                )
                return false
            }
            if (lyricsInFlightGeneration == generation) {
                logger("[Lyrics] skip inflight trackId=$trackId generation=$generation")
                return false
            }
            lyricsInFlightTrackId = trackId
            lyricsInFlightGeneration = generation
            lyricsState = MediaPipelineState.RUNNING
            logger("[Lyrics] parse start trackId=$trackId generation=$generation")
            return true
        }
    }

    fun markLyricsTaskFinished(
        trackId: String,
        generation: Long,
        ready: Boolean,
        reason: String
    ) {
        synchronized(lock) {
            if (generation != this.generation || trackIdMatchesLocked(trackId).not()) {
                logger(
                    "[Lyrics] drop generation mismatch trackId=$trackId " +
                        "generation=$generation current=${this.generation} reason=$reason"
                )
                return
            }
            lyricsInFlightTrackId = ""
            lyricsInFlightGeneration = -1L
            lyricsReady = ready
            lyricsState = if (ready) MediaPipelineState.READY else MediaPipelineState.FAILED
            if (ready) {
                logger("[Lyrics] index built trackId=$trackId generation=$generation")
                logger("[Lyrics] ready=true trackId=$trackId generation=$generation")
            } else {
                logger("[Lyrics] ready=false trackId=$trackId generation=$generation reason=$reason")
            }
        }
    }

    fun tryStartAlbumArtTask(trackId: String, generation: Long): Boolean {
        synchronized(lock) {
            if (generation != this.generation || trackIdMatchesLocked(trackId).not()) {
                logger(
                    "[AlbumArt] skip reason=generation_mismatch trackId=$trackId " +
                        "generation=$generation current=${this.generation}"
                )
                return false
            }
            if (albumArtInFlightCount >= MAX_ALBUM_ART_LOADS) {
                logger("[AlbumArt] skip reason=inflight_limit count=$albumArtInFlightCount")
                return false
            }
            albumArtInFlightCount += 1
            albumArtState = MediaPipelineState.RUNNING
            return true
        }
    }

    fun markAlbumArtFinished(trackId: String, generation: Long, ready: Boolean, reason: String) {
        synchronized(lock) {
            if (albumArtInFlightCount > 0) {
                albumArtInFlightCount -= 1
            }
            if (generation != this.generation || trackIdMatchesLocked(trackId).not()) {
                logger(
                    "[AlbumArt] drop generation mismatch trackId=$trackId " +
                        "generation=$generation current=${this.generation} reason=$reason"
                )
                return
            }
            albumArtReady = ready
            albumArtState = if (ready) MediaPipelineState.READY else MediaPipelineState.FAILED
            logger("[AlbumArt] loaded trackId=$trackId ready=$ready reason=$reason")
        }
    }

    fun updateCurrentLine(line: String) {
        synchronized(lock) {
            lastCurrentLine = line
        }
    }

    fun updateCurrentWord(wordKey: String) {
        synchronized(lock) {
            lastCurrentWordKey = wordKey
        }
    }

    fun generation(): Long = synchronized(lock) { generation }

    fun snapshot(): MediaStateSnapshot {
        synchronized(lock) {
            return MediaStateSnapshot(
                trackId = currentTrackId,
                generation = generation,
                playbackState = lastPlaybackSummary,
                currentWord = lastCurrentWordKey,
                currentLine = lastCurrentLine,
                lyricsState = lyricsState,
                albumArtState = albumArtState,
                lyricsReady = lyricsReady,
                albumArtReady = albumArtReady,
                lastUpdatedTime = lastEventAtMs
            )
        }
    }

    private fun recordEventLocked(type: MediaEventType, now: Long) {
        lastEventAtMs = now
        if (now - lastEventWindowStartedAtMs > EVENT_STORM_WINDOW_MS) {
            lastEventWindowStartedAtMs = now
            eventsInWindow = 0
        }
        eventsInWindow += 1
        if (eventsInWindow > EVENT_STORM_THRESHOLD &&
            now - lastStormLogAtMs > EVENT_STORM_LOG_INTERVAL_MS
        ) {
            lastStormLogAtMs = now
            logger("[Engine] debounce merged reason=event_storm type=$type count=$eventsInWindow")
        }
    }

    private fun trackIdMatchesLocked(trackId: String): Boolean {
        if (trackId.isBlank()) {
            return true
        }
        return trackId == currentTrackId || trackId.startsWith(currentTrackId) ||
            currentTrackId.startsWith(trackId)
    }

    companion object {
        private const val DEBOUNCE_WINDOW_MS = 500L
        private const val PLAYBACK_START_POSITION_WINDOW_MS = 1_500L
        private const val EVENT_STORM_WINDOW_MS = 1_000L
        private const val EVENT_STORM_THRESHOLD = 5
        private const val EVENT_STORM_LOG_INTERVAL_MS = 5_000L
        private const val MAX_ALBUM_ART_LOADS = 2
    }
}
