package com.example.playeragent.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.StatusBarNotification
import com.example.playeragent.service.PlayerNotificationListenerService

class AlbumArtTestManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private val mediaSessionManager =
        appContext.getSystemService(MediaSessionManager::class.java)

    fun runTest() {
        logger("[AlbumArtTest] started")
        testMediaMetadata()
        testNotification()
        logger("[AlbumArtTest] finished")
    }

    fun readCurrentNotificationAlbumArt(): NotificationAlbumArt? {
        if (!isNotificationAccessEnabled() ||
            !PlayerNotificationListenerService.isConnected()
        ) {
            logger("[AlbumArtDebug] unavailable reason=notification access unavailable")
            logger("[AlbumArt][BLE] unavailable")
            return null
        }

        val selected = selectMusicNotification(
            PlayerNotificationListenerService.activeNotificationsSnapshot()
        ) ?: run {
            logger("[AlbumArtDebug] unavailable reason=no QQMusic notification")
            logger("[AlbumArt][BLE] unavailable")
            return null
        }

        val notification = selected.notification
        val sources = listOf(
            "notification largeIcon" to
                notification.extras?.get(Notification.EXTRA_LARGE_ICON),
            "notification largeIcon" to notification.getLargeIcon(),
            "notification EXTRA_PICTURE" to
                notification.extras?.get(Notification.EXTRA_PICTURE)
        )

        sources.forEach { (source, value) ->
            val bitmap = valueToBitmap(value)
            logger("[AlbumArtDebug] source $source exists=${bitmap != null}")
            if (bitmap != null) {
                logger(
                    "[AlbumArtDebug] bitmap width=${bitmap.width} height=${bitmap.height}"
                )
                return NotificationAlbumArt(
                    bitmap = scaleToMaximum(bitmap, MAX_ALBUM_ART_SIZE),
                    source = source
                )
            }
        }

        logger("[AlbumArtDebug] unavailable reason=no notification largeIcon")
        logger("[AlbumArt][BLE] unavailable")
        return null
    }

    fun readCurrentNotificationLargeIcon(): Bitmap? {
        return readCurrentNotificationAlbumArt()?.bitmap
    }

    fun isNotificationAccessEnabled(): Boolean {
        val component = ComponentName(
            appContext,
            PlayerNotificationListenerService::class.java
        )
        val enabledListeners = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()

        return enabledListeners
            .split(":")
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == component }
    }

    private fun testMediaMetadata() {
        val manager = mediaSessionManager
        if (manager == null) {
            logger("[AlbumArtTest][MediaMetadata] MediaSessionManager unavailable")
            return
        }

        val listenerComponent = ComponentName(
            appContext,
            PlayerNotificationListenerService::class.java
        )
        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (securityException: SecurityException) {
            logger("[AlbumArtTest] Notification Access not enabled")
            return
        } catch (exception: Exception) {
            logger("[AlbumArtTest][MediaMetadata] getActiveSessions failed=${exception.message}")
            return
        }

        val controller = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()
        val metadata = controller?.metadata

        logBitmap(
            source = "MediaMetadata",
            key = "ALBUM_ART",
            bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        )
        logBitmap(
            source = "MediaMetadata",
            key = "ART",
            bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        )
        logBitmap(
            source = "MediaMetadata",
            key = "DISPLAY_ICON",
            bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        )
    }

    private fun testNotification() {
        if (!isNotificationAccessEnabled()) {
            logger("[AlbumArtTest] Notification Access not enabled")
            return
        }

        if (!PlayerNotificationListenerService.isConnected()) {
            logger("[AlbumArtTest][Notification] listener service not connected")
            logNotificationResult(null)
            return
        }

        val notifications = PlayerNotificationListenerService.activeNotificationsSnapshot()
        val selected = selectMusicNotification(notifications)
        if (selected == null) {
            logger("[AlbumArtTest][Notification] QQ Music notification not found")
            logNotificationResult(null)
            return
        }

        logger("[AlbumArtTest][Notification] package=${selected.packageName}")
        logNotificationResult(selected.notification)
    }

    private fun selectMusicNotification(
        notifications: List<StatusBarNotification>
    ): StatusBarNotification? {
        return notifications.firstOrNull {
            it.packageName == QQ_MUSIC_PACKAGE
        }
    }

    private fun scaleToMaximum(bitmap: Bitmap, maximumSize: Int): Bitmap {
        if (bitmap.width <= maximumSize && bitmap.height <= maximumSize) {
            return bitmap
        }

        val scale = minOf(
            maximumSize.toFloat() / bitmap.width,
            maximumSize.toFloat() / bitmap.height
        )
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun logNotificationResult(notification: Notification?) {
        val extras = notification?.extras
        val extraLargeIconValue = extras?.get(Notification.EXTRA_LARGE_ICON)
        val extraPictureValue = extras?.get(Notification.EXTRA_PICTURE)
        val largeIcon = notification?.getLargeIcon()

        logNotificationArtwork(
            key = "EXTRA_LARGE_ICON",
            value = extraLargeIconValue
        )
        logNotificationArtwork(
            key = "EXTRA_PICTURE",
            value = extraPictureValue
        )
        logNotificationArtwork(
            key = "largeIcon",
            value = largeIcon
        )
    }

    private fun logNotificationArtwork(key: String, value: Any?) {
        val bitmap = valueToBitmap(value)
        logger("[AlbumArtTest][Notification] $key exists=${value != null}")
        if (bitmap != null) {
            logger(
                "[AlbumArtTest][Notification] $key " +
                    "width=${bitmap.width} height=${bitmap.height}"
            )
        }
    }

    private fun logBitmap(source: String, key: String, bitmap: Bitmap?) {
        logger("[AlbumArtTest][$source] $key exists=${bitmap != null}")
        if (bitmap != null) {
            logger(
                "[AlbumArtTest][$source] $key " +
                    "width=${bitmap.width} height=${bitmap.height}"
            )
        }
    }

    private fun valueToBitmap(value: Any?): Bitmap? {
        return when (value) {
            is Bitmap -> value
            is Icon -> iconToBitmap(value)
            is BitmapDrawable -> value.bitmap
            is Drawable -> drawableToBitmap(value)
            else -> null
        }
    }

    private fun iconToBitmap(icon: Icon): Bitmap? {
        return try {
            icon.loadDrawable(appContext)?.let(::drawableToBitmap)
        } catch (exception: Exception) {
            logger("[AlbumArtTest][Notification] Icon conversion failed=${exception.message}")
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }

        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    data class NotificationAlbumArt(
        val bitmap: Bitmap,
        val source: String
    )

    companion object {
        private const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
        private const val MAX_ALBUM_ART_SIZE = 280
    }
}
