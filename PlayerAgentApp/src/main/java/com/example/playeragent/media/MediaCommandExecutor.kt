package com.example.playeragent.media

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.view.KeyEvent
import com.example.playeragent.service.PlayerNotificationListenerService
import android.content.ComponentName
import android.media.session.PlaybackState
import org.json.JSONObject

class MediaCommandExecutor(
    context: Context,
    private val logger: (String) -> Unit,
    private val sendLine: (String) -> Unit = {}
) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val appContext = context.applicationContext
    private val mediaSessionManager = appContext.getSystemService(MediaSessionManager::class.java)

    fun execute(cmd: String) {
        logger("Execute command: $cmd")

        when (cmd) {
            "PLAY_PAUSE" -> playPause()
            "NEXT" -> next()
            "PREVIOUS" -> previous()
            "VOLUME_UP" -> volumeUp()
            "VOLUME_DOWN" -> volumeDown()
            "PING" -> logger("PING received")
            else -> logger("Unknown command: $cmd")
        }
    }

    fun playPause() {
        logger("Media key: PLAY_PAUSE")
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    fun next() {
        logger("Media key: NEXT")
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous() {
        logger("Media key: PREVIOUS")
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun volumeUp() {
        executeVolumeCommand(
            command = "VOLUME_UP",
            direction = AudioManager.ADJUST_RAISE
        )
    }

    fun volumeDown() {
        executeVolumeCommand(
            command = "VOLUME_DOWN",
            direction = AudioManager.ADJUST_LOWER
        )
    }

    fun seekTo(positionMs: Long) {
        logger("[Seek]\nreceived position=$positionMs")

        val controller = findActiveMediaController()
        if (controller == null) {
            logger("[Seek]\nno active MediaController")
            return
        }

        controller.transportControls.seekTo(positionMs.coerceAtLeast(0L))
        logger("[Seek]\ntransportControls.seekTo()")
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun executeVolumeCommand(command: String, direction: Int) {
        logger("[VolumeControl] command=$command")

        val before = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        logger("[VolumeControl] before=$before")

        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )

        val after = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        logger("[VolumeControl] after=$after")
        logger("[VolumeControl] max=$max")

        sendVolumeState()
    }

    fun sendVolumeState() {
        val state = createVolumeState()
        sendLine(state.toString())
        logger("[VolumeControl] sent volumeState")
    }

    fun createVolumeState(): JSONObject {
        return JSONObject()
            .put("type", "volumeState")
            .put("current", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("max", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    private fun findActiveMediaController(): android.media.session.MediaController? {
        val manager = mediaSessionManager ?: return null
        val listenerComponent = ComponentName(
            appContext,
            PlayerNotificationListenerService::class.java
        )

        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (exception: Exception) {
            logger("[Seek]\ngetActiveSessions failed=${exception.message}")
            return null
        }

        return controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()
    }
}
