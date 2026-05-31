package com.example.playeragent.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Base64
import com.example.playeragent.service.PlayerNotificationListenerService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.min

class AlbumArtManager(
    context: Context,
    private val logger: (String) -> Unit,
    private val sendLine: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private val mediaSessionManager =
        appContext.getSystemService(MediaSessionManager::class.java)
    private var lastArtworkKey: String? = null

    fun sendAlbumArtIfNeeded(playbackState: JSONObject) {
        val title = playbackState.optString("title")
        val artist = playbackState.optString("artist")
        val album = playbackState.optString("album")
        val artworkKey = "$title|$artist|$album"

        if (artworkKey == lastArtworkKey) {
            return
        }

        lastArtworkKey = artworkKey
        logger("[AlbumArt] artwork changed: $artworkKey")

        val bitmap = readCurrentAlbumArt()
        if (bitmap == null) {
            logger("[AlbumArt] unavailable")
            sendLine(JSONObject().put("type", "albumArtUnavailable").toString())
            return
        }

        val scaledBitmap = scaleBitmap(bitmap, MAX_IMAGE_SIZE)
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val bytes = outputStream.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val totalChunks = (base64.length + CHUNK_SIZE - 1) / CHUNK_SIZE

        logger("[AlbumArt] sending chunks=$totalChunks bytes=${bytes.size}")
        sendLine(
            JSONObject()
                .put("type", "albumArtStart")
                .put("title", title)
                .put("totalChunks", totalChunks)
                .put("chunkSize", CHUNK_SIZE)
                .toString()
        )

        for (index in 0 until totalChunks) {
            val start = index * CHUNK_SIZE
            val end = min(start + CHUNK_SIZE, base64.length)
            sendLine(
                JSONObject()
                    .put("type", "albumArtChunk")
                    .put("index", index)
                    .put("data", base64.substring(start, end))
                    .toString()
            )
        }

        sendLine(JSONObject().put("type", "albumArtEnd").toString())
    }

    private fun readCurrentAlbumArt(): Bitmap? {
        val manager = mediaSessionManager ?: run {
            logger("[AlbumArt] MediaSessionManager unavailable")
            return null
        }

        val listenerComponent = ComponentName(
            appContext,
            PlayerNotificationListenerService::class.java
        )

        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (securityException: SecurityException) {
            logger("[AlbumArt] getActiveSessions failed: ${securityException.message}")
            return null
        } catch (exception: Exception) {
            logger("[AlbumArt] getActiveSessions failed: ${exception.message}")
            return null
        }

        val selected = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        val metadata = selected?.metadata ?: run {
            logger("[AlbumArt] metadata unavailable")
            return null
        }

        val albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val displayIcon = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        logger("[AlbumArt] albumArt exists=${albumArt != null}")
        logger("[AlbumArt] art exists=${art != null}")
        logger("[AlbumArt] displayIcon exists=${displayIcon != null}")

        if (albumArt != null) {
            logger("[AlbumArt] key=ALBUM_ART width=${albumArt.width} height=${albumArt.height}")
        }
        if (art != null) {
            logger("[AlbumArt] key=ART width=${art.width} height=${art.height}")
        }
        if (displayIcon != null) {
            logger("[AlbumArt] key=DISPLAY_ICON width=${displayIcon.width} height=${displayIcon.height}")
        }

        if (albumArt == null && art == null && displayIcon == null) {
            logger("[AlbumArt] no bitmap found in MediaMetadata")
        }

        return albumArt ?: art ?: displayIcon
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val ratio = min(maxSize.toFloat() / width.toFloat(), maxSize.toFloat() / height.toFloat())
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    companion object {
        private const val MAX_IMAGE_SIZE = 300
        private const val JPEG_QUALITY = 75
        private const val CHUNK_SIZE = 2048
    }
}
