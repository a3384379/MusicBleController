package com.example.playeragent.service

import android.app.Notification
import android.service.notification.StatusBarNotification
import android.service.notification.NotificationListenerService
import com.example.playeragent.diagnostics.RealtimeTrace
import com.example.playeragent.diagnostics.TrackHandoffTraceCoordinator
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class PlayerNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        super.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        notifyQqMusicArtworkChanged(sbn, "posted")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        notifyQqMusicArtworkChanged(sbn, "removed")
    }

    companion object {
        @Volatile
        private var instance: PlayerNotificationListenerService? = null
        private val qqMusicArtworkListeners = CopyOnWriteArrayList<(String) -> Unit>()

        fun activeNotificationsSnapshot(): List<StatusBarNotification> {
            return try {
                instance?.activeNotifications?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun isConnected(): Boolean = instance != null

        fun addQqMusicArtworkListener(listener: (String) -> Unit) {
            qqMusicArtworkListeners += listener
        }

        fun removeQqMusicArtworkListener(listener: (String) -> Unit) {
            qqMusicArtworkListeners -= listener
        }

        private fun notifyQqMusicArtworkChanged(
            notification: StatusBarNotification?,
            event: String
        ) {
            if (notification?.packageName != QQ_MUSIC_PACKAGE) {
                return
            }
            if (event == "posted" && RealtimeTrace.enabled) {
                val extras = notification.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString()
                    .orEmpty()
                val artist = extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?.toString()
                    .orEmpty()
                val identityKey = MessageDigest.getInstance("SHA-256")
                    .digest("$title|$artist".toByteArray(Charsets.UTF_8))
                    .take(8)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                TrackHandoffTraceCoordinator.observeNotificationMetadata(identityKey)
            }
            qqMusicArtworkListeners.forEach { listener ->
                runCatching { listener(event) }
            }
        }

        private const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
    }
}
