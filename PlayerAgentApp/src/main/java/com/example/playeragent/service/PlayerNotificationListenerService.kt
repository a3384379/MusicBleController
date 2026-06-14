package com.example.playeragent.service

import android.service.notification.StatusBarNotification
import android.service.notification.NotificationListenerService

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

    companion object {
        @Volatile
        private var instance: PlayerNotificationListenerService? = null

        fun activeNotificationsSnapshot(): List<StatusBarNotification> {
            return try {
                instance?.activeNotifications?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun isConnected(): Boolean = instance != null
    }
}
