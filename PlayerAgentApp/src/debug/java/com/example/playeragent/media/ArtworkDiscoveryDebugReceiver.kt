package com.example.playeragent.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.security.MessageDigest

class ArtworkDiscoveryDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DISCOVER_HQ_ARTWORK) return
        val appContext = context.applicationContext
        Thread {
            val logger: (String) -> Unit = { message ->
                android.util.Log.i("PlayerAgent", "[PlayerAgent] $message")
            }
            try {
                val playbackState = PlaybackStateReader(
                    context = appContext,
                    logger = logger,
                    includeLyric = false
                ).readPlaybackState()
                val title = playbackState.optString("title")
                val artist = playbackState.optString("artist")
                val album = playbackState.optString("album")
                val albumArt = AlbumArtTestManager(
                    appContext,
                    logger
                ).readCurrentNotificationAlbumArt()
                if (albumArt == null) {
                    logger("[ArtworkDiscovery] receiver skipped reason=no notification artwork")
                    return@Thread
                }
                val trackId = buildTrackId(title, artist, album)
                val manager = QQMusicArtworkDiscoveryManager(
                    context = appContext,
                    logger = logger,
                    onStatus = { status ->
                        logger("[ArtworkDiscovery] status ${status.displayText().replace('\n', ' ')}")
                    }
                )
                manager.discoverCurrentTrackArtwork(
                    title = title,
                    artist = artist,
                    album = album,
                    albumArtId = trackId,
                    referenceBitmap = albumArt.bitmap
                )
            } catch (exception: Exception) {
                logger("[ArtworkDiscovery] receiver failed=${exception.message}")
            }
        }.apply {
            name = "ArtworkDiscoveryReceiverThread"
            start()
        }
    }

    private fun buildTrackId(title: String, artist: String, album: String): String {
        val source = listOf(title, artist, album)
            .joinToString("|")
            .ifBlank { "unknown" }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        const val ACTION_DISCOVER_HQ_ARTWORK =
            "com.example.playeragent.action.DISCOVER_HQ_ARTWORK"
    }
}
