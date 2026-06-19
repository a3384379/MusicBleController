package com.example.playeragent.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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
        lastTrackId = buildTrackId(title, artist, album)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0L && !durationMissingLogged) {
            durationMissingLogged = true
            logger("[PlaybackState] duration missing title=$title")
        } else if (duration > 0L) {
            durationMissingLogged = false
        }
        val lyricStartedAtMs = SystemClock.elapsedRealtime()
        val lyric = if (includeLyric) {
            lyricManager.requestLyricLoadAsync(title, artist, album)
            lyricManager.getCurrentLine(position)
        } else {
            ""
        }
        val cachedLyricCostMs = SystemClock.elapsedRealtime() - lyricStartedAtMs
        if (lyric != lastLoggedLyric) {
            lastLoggedLyric = lyric
            logger("[PlaybackState] lyric=$lyric")
        }
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

        return JSONObject()
            .put("type", "playbackState")
            .put("playing", playing)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("position", position)
            .put("duration", duration)
            .put("lyric", lyric)
    }

    fun lyricLinesSnapshot(): List<LyricManager.LyricLine> {
        return lyricManager.lyricLinesSnapshot()
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
        return lyricManager.currentTrackSnapshot(lastTrackId)
    }

    fun applyIncrementalLyrics(ready: IncrementalLyricsReady): Boolean {
        return lyricManager.applyIncrementalLyrics(ready)
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
    }
}
