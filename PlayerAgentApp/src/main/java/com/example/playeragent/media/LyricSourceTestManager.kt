package com.example.playeragent.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import com.example.playeragent.service.PlayerNotificationListenerService
import java.util.Locale

class LyricSourceTestManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private val mediaSessionManager =
        appContext.getSystemService(MediaSessionManager::class.java)

    fun runTest() {
        logger("[LyricSourceTest] ===== START =====")
        testMediaMetadata()
        testNotificationExtras()
        logger("[LyricSourceTest] ===== END =====")
    }

    private fun testMediaMetadata() {
        logger("[LyricSourceTest][MediaMetadata] ----- keySet START -----")

        val manager = mediaSessionManager
        if (manager == null) {
            logger("[LyricSourceTest][MediaMetadata] MediaSessionManager unavailable")
            logger("[LyricSourceTest][MediaMetadata] ----- keySet END -----")
            return
        }

        val controllers = try {
            manager.getActiveSessions(notificationListenerComponent())
        } catch (securityException: SecurityException) {
            logger("[LyricSourceTest] Notification Access not enabled")
            logger(
                "[LyricSourceTest][MediaMetadata] " +
                    "getActiveSessions SecurityException=${securityException.message}"
            )
            logger("[LyricSourceTest][MediaMetadata] ----- keySet END -----")
            return
        } catch (exception: Exception) {
            logger(
                "[LyricSourceTest][MediaMetadata] " +
                    "getActiveSessions failed=${exception.message}"
            )
            logger("[LyricSourceTest][MediaMetadata] ----- keySet END -----")
            return
        }

        val controller = selectQqMusicController(controllers)
        if (controller == null) {
            logger("[LyricSourceTest][MediaMetadata] QQ Music controller not found")
            controllers.forEach {
                logger(
                    "[LyricSourceTest][MediaMetadata] " +
                        "activeController package=${it.packageName}"
                )
            }
            logger("[LyricSourceTest][MediaMetadata] ----- keySet END -----")
            return
        }

        logger(
            "[LyricSourceTest][MediaMetadata] " +
                "controllerPackage=${controller.packageName}"
        )

        val metadata = controller.metadata
        if (metadata == null) {
            logger("[LyricSourceTest][MediaMetadata] metadata=null")
            logger("[LyricSourceTest][MediaMetadata] ----- keySet END -----")
            return
        }

        val keys = metadata.keySet().sorted()
        logger("[LyricSourceTest][MediaMetadata] keyCount=${keys.size}")
        keys.forEach { key ->
            logger("[LyricSourceTest][MediaMetadata] key=$key")
            if (containsLyricKeyword(key)) {
                logger("[LyricSourceTest][MATCH][MediaMetadata] key=$key")
            }
        }

        logger("[LyricSourceTest][MediaMetadata] ----- keySet END -----")
    }

    private fun testNotificationExtras() {
        logger("[LyricSourceTest][Notification] ----- extras START -----")

        if (!isNotificationAccessEnabled()) {
            logger("[LyricSourceTest] Notification Access not enabled")
            logger("[LyricSourceTest][Notification] ----- extras END -----")
            return
        }

        if (!PlayerNotificationListenerService.isConnected()) {
            logger("[LyricSourceTest][Notification] listener service not connected")
            logger("[LyricSourceTest][Notification] ----- extras END -----")
            return
        }

        val notifications =
            PlayerNotificationListenerService.activeNotificationsSnapshot()
        val selected = selectQqMusicNotification(notifications)
        if (selected == null) {
            logger("[LyricSourceTest][Notification] QQ Music notification not found")
            notifications.forEach {
                logger(
                    "[LyricSourceTest][Notification] " +
                        "activeNotification package=${it.packageName}"
                )
            }
            logger("[LyricSourceTest][Notification] ----- extras END -----")
            return
        }

        logger("[LyricSourceTest][Notification] package=${selected.packageName}")
        val extras = selected.notification.extras ?: Bundle.EMPTY
        val keys = extras.keySet().sorted()

        logger("[LyricSourceTest][Notification] keyCount=${keys.size}")
        keys.forEach { key ->
            logger("[LyricSourceTest][Notification] key=$key")
            if (containsLyricKeyword(key)) {
                logger("[LyricSourceTest][MATCH][NotificationKey] key=$key")
            }
        }

        logNamedExtra(extras, Notification.EXTRA_TEXT, "EXTRA_TEXT")
        logNamedExtra(extras, Notification.EXTRA_BIG_TEXT, "EXTRA_BIG_TEXT")
        logNamedExtra(extras, Notification.EXTRA_SUB_TEXT, "EXTRA_SUB_TEXT")
        logNamedExtra(
            extras,
            Notification.EXTRA_SUMMARY_TEXT,
            "EXTRA_SUMMARY_TEXT"
        )

        logger("[LyricSourceTest][Notification] ----- String extras START -----")
        keys.forEach { key ->
            val value = extras.get(key)
            if (value is String) {
                logger("[LyricSourceTest][Notification][String] key=$key")
                logger("[LyricSourceTest][Notification][String] value=$value")
                if (containsLyricKeyword(key) || containsLyricKeyword(value)) {
                    logger(
                        "[LyricSourceTest][MATCH][StringExtra] " +
                            "key=$key value=$value"
                    )
                }
            }
        }
        logger("[LyricSourceTest][Notification] ----- String extras END -----")
        logger("[LyricSourceTest][Notification] ----- extras END -----")
    }

    private fun logNamedExtra(extras: Bundle, key: String, label: String) {
        val value = extras.get(key)
        logger(
            "[LyricSourceTest][Notification] " +
                "$label key=$key exists=${extras.containsKey(key)}"
        )
        logger(
            "[LyricSourceTest][Notification] " +
                "$label type=${value?.javaClass?.name ?: "null"} value=${value ?: "null"}"
        )
        if (containsLyricKeyword(key) ||
            containsLyricKeyword(value?.toString().orEmpty())
        ) {
            logger(
                "[LyricSourceTest][MATCH][$label] " +
                    "key=$key value=${value ?: "null"}"
            )
        }
    }

    private fun selectQqMusicController(
        controllers: List<MediaController>
    ): MediaController? {
        val qqMusicControllers = controllers.filter {
            it.packageName == QQ_MUSIC_PACKAGE
        }
        return qqMusicControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: qqMusicControllers.firstOrNull()
    }

    private fun selectQqMusicNotification(
        notifications: List<StatusBarNotification>
    ): StatusBarNotification? {
        return notifications.firstOrNull {
            it.packageName == QQ_MUSIC_PACKAGE
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()

        return enabledListeners
            .split(":")
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == notificationListenerComponent() }
    }

    private fun notificationListenerComponent(): ComponentName {
        return ComponentName(
            appContext,
            PlayerNotificationListenerService::class.java
        )
    }

    private fun containsLyricKeyword(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
        return LYRIC_KEYWORDS.any { normalized.contains(it) }
    }

    companion object {
        private const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
        private val LYRIC_KEYWORDS = listOf(
            "歌词",
            "lyric",
            "lrc",
            "currentlyric",
            "line"
        )
    }
}
