package com.example.playeragent.service

import android.service.notification.StatusBarNotification
import android.service.notification.NotificationListenerService
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
            qqMusicArtworkListeners.forEach { listener ->
                runCatching { listener(event) }
            }
        }

        private const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
    }
}
