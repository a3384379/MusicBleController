package com.example.playeragent.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.example.playeragent.logging.LogConfig
import com.example.playeragent.service.PlayerNotificationListenerService
import org.json.JSONObject

class PlaybackStateReader(
    context: Context,
    private val logger: (String) -> Unit
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

    fun readPlaybackState(): JSONObject {
        verbose("[PlaybackState] GET_PLAYBACK_STATE received")

        if (mediaSessionManager == null) {
            logger("[PlaybackState] MediaSessionManager unavailable")
            return emptyResponse("MediaSessionManager unavailable")
        }

        val listenerComponent = ComponentName(
            appContext,
            PlayerNotificationListenerService::class.java
        )

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
        if (duration <= 0L && !durationMissingLogged) {
            durationMissingLogged = true
            logger("[PlaybackState] duration missing title=$title")
        } else if (duration > 0L) {
            durationMissingLogged = false
        }
        lyricManager.loadLyric(title, artist, album)
        val lyric = lyricManager.getCurrentLine(position)

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
}
