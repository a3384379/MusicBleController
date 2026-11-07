// SPDX-License-Identifier: GPL-3.0-only
package com.musicblecontroller.phoneaudiolab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class PhoneAudioReceiverService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "phone-audio-receiver").apply { isDaemon = true }
    }
    private val queue = AudioTaskQueue { task ->
        try { worker.execute(task) } catch (_: RejectedExecutionException) { /* disposed epoch */ }
    }
    private val observers = mutableSetOf<() -> Unit>()
    private val session = PhoneAudioSession(queue,
        { token, events -> NativeAirPlayReceiver(this, token, events, queue) },
        { main.post { publish() } })
    private var foreground = false

    inner class LocalBinder : Binder() {
        fun snapshot(): AudioSnapshot = session.snapshot()
        fun observe(observer: () -> Unit) { observers.add(observer); observer() }
        fun removeObserver(observer: () -> Unit) { observers.remove(observer) }
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE -> {
                try {
                    val notification = notification()
                    startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    foreground = true
                    session.start()
                } catch (_: Exception) {
                    session.stop("foreground_service_unavailable")
                }
            }
            ACTION_DISABLE -> session.stop()
        }
        // A killed/restarted process never recreates a receiving session.
        return START_NOT_STICKY
    }

    private fun publish() {
        observers.toList().forEach { it() }
        val snapshot = session.snapshot()
        if (snapshot.phase in setOf(AudioPhase.OFF, AudioPhase.FAILED, AudioPhase.INTERRUPTED)) {
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, PhoneAudioLabActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Bring the user to the pause-before-stop confirmation rather than silently dropping output.
        val builder = Notification.Builder(this, CHANNEL)
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification))
            .setContentIntent(open).setOngoing(true).build()
    }

    override fun onDestroy() {
        session.stop()
        observers.clear()
        // Shutdown drains the independently queued cleanup; callbacks are already fenced.
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ENABLE = "com.musicblecontroller.phoneaudiolab.ENABLE"
        const val ACTION_DISABLE = "com.musicblecontroller.phoneaudiolab.DISABLE"
        private const val CHANNEL = "phone_audio_lab"
        private const val NOTIFICATION_ID = 1
    }
}
