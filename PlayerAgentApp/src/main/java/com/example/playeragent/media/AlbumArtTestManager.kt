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
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

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

    fun readCurrentNotificationAlbumArt(
        expectedTitle: String = "",
        expectedArtist: String = ""
    ): NotificationAlbumArt? {
        val selectedNotification = currentNotification(expectedTitle, expectedArtist)
        val notificationFingerprint = selectedNotification?.let {
            notificationFingerprint(it, expectedTitle, expectedArtist)
        }
        synchronized(sharedArtworkReadLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            val cached = sharedArtworkReadCache
            if (notificationFingerprint != null &&
                cached != null &&
                cached.notificationFingerprint == notificationFingerprint &&
                now - cached.createdAtElapsedMs <= SHARED_READ_CACHE_TTL_MS
            ) {
                logger("[AlbumArtSource] shared cache hit source=${cached.result.source}")
                return cached.result
            }

            val candidates = mutableListOf<AlbumArtCandidate>()
            candidates += readMediaMetadataCandidates(expectedTitle, expectedArtist)
            candidates += readNotificationCandidates(selectedNotification)
            logger("[AlbumArtSource] candidates count=${candidates.size}")
            if (candidates.isEmpty()) {
                logger("[AlbumArtDebug] unavailable reason=no album art candidate")
                logger("[AlbumArt][BLE] unavailable")
                return null
            }

            val valid = candidates.filter { candidate ->
                if (AlbumArtPlaceholderPolicy.isLikelyPlaceholder(candidate.bitmap)) {
                    logger("[AlbumArtSource] rejected placeholder source=${candidate.source}")
                    false
                } else {
                    true
                }
            }
            if (valid.isEmpty()) {
                logger("[AlbumArtDebug] unavailable reason=all candidates placeholder")
                logger("[AlbumArt][BLE] unavailable")
                return null
            }

            val selected = valid.maxWithOrNull(
                compareBy<AlbumArtCandidate> {
                    it.bitmap.width.toLong() * it.bitmap.height.toLong()
                }.thenBy { it.priority }
            ) ?: return null
            valid.filter { it !== selected }.forEach { candidate ->
                logger(
                    "[AlbumArtSource] rejected smaller source=${candidate.source} " +
                        "width=${candidate.bitmap.width} height=${candidate.bitmap.height}"
                )
            }
            logger(
                "[AlbumArtSource] selected source=${selected.source} " +
                    "width=${selected.bitmap.width} height=${selected.bitmap.height}"
            )
            logger("[AlbumArtDebug] source ${selected.source} exists=true")
            logger(
                "[AlbumArtDebug] bitmap width=${selected.bitmap.width} " +
                    "height=${selected.bitmap.height}"
            )
            val result = NotificationAlbumArt(
                bitmap = selected.bitmap,
                source = selected.source
            )
            if (notificationFingerprint != null) {
                sharedArtworkReadCache = SharedArtworkReadCache(
                    notificationFingerprint = notificationFingerprint,
                    result = result,
                    createdAtElapsedMs = now
                )
            }
            return result
        }
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

        val controller = selectQqMusicController(controllers)
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

    private fun readMediaMetadataCandidates(
        expectedTitle: String,
        expectedArtist: String
    ): List<AlbumArtCandidate> {
        val manager = mediaSessionManager ?: run {
            logger("[AlbumArtSource] metadata unavailable reason=MediaSessionManager")
            return emptyList()
        }
        val listenerComponent = ComponentName(
            appContext,
            PlayerNotificationListenerService::class.java
        )
        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (securityException: SecurityException) {
            logger("[AlbumArtSource] metadata unavailable reason=notification access")
            return emptyList()
        } catch (exception: Exception) {
            logger("[AlbumArtSource] metadata unavailable reason=${exception.message}")
            return emptyList()
        }
        val controller = selectQqMusicController(controllers)
        val metadata = controller?.metadata
        val metadataTitle = metadataString(
            metadata,
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE
        )
        val metadataArtist = metadataString(
            metadata,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE
        )
        if (!AlbumArtIdentityPolicy.matches(
                expectedTitle = expectedTitle,
                expectedArtist = expectedArtist,
                actualTitle = metadataTitle,
                actualArtist = metadataArtist
            )
        ) {
            logger("[AlbumArtSource] metadata rejected reason=identity_mismatch")
            return emptyList()
        }
        return listOfNotNull(
            albumArtCandidate(
                source = "metadataAlbumArt",
                bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                priority = SOURCE_PRIORITY_METADATA
            ),
            albumArtCandidate(
                source = "metadataArt",
                bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                priority = SOURCE_PRIORITY_METADATA
            ),
            albumArtCandidate(
                source = "displayIcon",
                bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                priority = SOURCE_PRIORITY_METADATA
            )
        )
    }

    private fun currentNotification(
        expectedTitle: String,
        expectedArtist: String
    ): StatusBarNotification? {
        if (!isNotificationAccessEnabled() ||
            !PlayerNotificationListenerService.isConnected()
        ) {
            return null
        }
        return selectMusicNotification(
            PlayerNotificationListenerService.activeNotificationsSnapshot(),
            expectedTitle,
            expectedArtist
        )
    }

    private fun readNotificationCandidates(
        selected: StatusBarNotification?
    ): List<AlbumArtCandidate> {
        if (selected == null) {
            logger("[AlbumArtSource] notificationLargeIcon size=missing")
            return emptyList()
        }
        val notification = selected.notification
        return listOfNotNull(
            albumArtCandidate(
                source = "notificationLargeIcon",
                bitmap = valueToBitmap(notification.extras?.get(Notification.EXTRA_LARGE_ICON)),
                priority = SOURCE_PRIORITY_NOTIFICATION
            ),
            albumArtCandidate(
                source = "notificationLargeIcon",
                bitmap = valueToBitmap(notification.getLargeIcon()),
                priority = SOURCE_PRIORITY_NOTIFICATION
            ),
            albumArtCandidate(
                source = "notificationPicture",
                bitmap = valueToBitmap(notification.extras?.get(Notification.EXTRA_PICTURE)),
                priority = SOURCE_PRIORITY_NOTIFICATION
            )
        )
    }

    private fun notificationFingerprint(
        notification: StatusBarNotification,
        expectedTitle: String,
        expectedArtist: String
    ): String {
        val extras = notification.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val artist = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val expectedScope =
            "${expectedTitle.trim().lowercase()}|${expectedArtist.trim().lowercase()}"
        return "${notification.key}|${notification.postTime}|$title|$artist|$expectedScope"
    }

    private fun albumArtCandidate(
        source: String,
        bitmap: Bitmap?,
        priority: Int
    ): AlbumArtCandidate? {
        if (bitmap == null) {
            logger("[AlbumArtSource] $source size=missing")
            return null
        }
        logger(
            "[AlbumArtSource] $source " +
                "size=${bitmap.width}x${bitmap.height} " +
                "config=${bitmap.config} allocationBytes=${bitmap.allocationByteCount}" +
                if (DEBUG_ART_DIAGNOSTICS) " sha256=${bitmapSha256(bitmap)}" else ""
        )
        return AlbumArtCandidate(
            source = source,
            bitmap = bitmap,
            priority = priority
        )
    }

    private fun bitmapSha256(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        return MessageDigest.getInstance("SHA-256")
            .digest(output.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
        notifications: List<StatusBarNotification>,
        expectedTitle: String = "",
        expectedArtist: String = ""
    ): StatusBarNotification? {
        val qqNotifications = notifications.filter { it.packageName == QQ_MUSIC_PACKAGE }
        if (expectedTitle.isBlank()) {
            return qqNotifications.firstOrNull()
        }
        return qqNotifications.firstOrNull { notification ->
            val extras = notification.notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val artist = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            AlbumArtIdentityPolicy.matches(
                expectedTitle = expectedTitle,
                expectedArtist = expectedArtist,
                actualTitle = title,
                actualArtist = artist
            )
        }
    }

    private fun metadataString(metadata: MediaMetadata?, vararg keys: String): String {
        keys.forEach { key ->
            val value = metadata?.getString(key)
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return ""
    }

    private fun selectQqMusicController(
        controllers: List<android.media.session.MediaController>
    ): android.media.session.MediaController? {
        val qqControllers = controllers.filter { it.packageName == QQ_MUSIC_PACKAGE }
        return qqControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: qqControllers.firstOrNull()
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

    private data class AlbumArtCandidate(
        val source: String,
        val bitmap: Bitmap,
        val priority: Int
    )

    data class NotificationAlbumArt(
        val bitmap: Bitmap,
        val source: String
    )

    companion object {
        private val sharedArtworkReadLock = Any()
        private var sharedArtworkReadCache: SharedArtworkReadCache? = null
        private const val SHARED_READ_CACHE_TTL_MS = 750L
        private const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
        private const val SOURCE_PRIORITY_METADATA = 2
        private const val SOURCE_PRIORITY_NOTIFICATION = 1
        private const val DEBUG_ART_DIAGNOSTICS = false
    }

    private data class SharedArtworkReadCache(
        val notificationFingerprint: String,
        val result: NotificationAlbumArt,
        val createdAtElapsedMs: Long
    )
}
