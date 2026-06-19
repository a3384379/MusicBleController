package com.example.playeragent.media

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.SystemClock
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

    fun execute(cmd: String, seq: String? = null) {
        logger("Execute command: $cmd")

        when (cmd) {
            "PLAY_PAUSE" -> playPause(seq)
            "NEXT" -> next(seq)
            "PREVIOUS" -> previous(seq)
            "VOLUME_UP" -> volumeUp(seq)
            "VOLUME_DOWN" -> volumeDown(seq)
            "PING" -> logger("PING received")
            else -> logger("Unknown command: $cmd")
        }
    }

    fun playPause(seq: String? = null) {
        val start = SystemClock.elapsedRealtime()
        logger("[CTRL-Sony] media key begin seq=${seq.orUnknown()} key=PLAY_PAUSE")
        logger("Media key: PLAY_PAUSE")
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        logger(
            "[CTRL-Sony] media key end seq=${seq.orUnknown()} " +
                "key=PLAY_PAUSE costMs=${SystemClock.elapsedRealtime() - start}"
        )
    }

    fun next(seq: String? = null) {
        val start = SystemClock.elapsedRealtime()
        logger("[CTRL-Sony] media key begin seq=${seq.orUnknown()} key=NEXT")
        logger("Media key: NEXT")
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        logger(
            "[CTRL-Sony] media key end seq=${seq.orUnknown()} " +
                "key=NEXT costMs=${SystemClock.elapsedRealtime() - start}"
        )
    }

    fun previous(seq: String? = null) {
        val start = SystemClock.elapsedRealtime()
        logger("[CTRL-Sony] media key begin seq=${seq.orUnknown()} key=PREVIOUS")
        logger("Media key: PREVIOUS")
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        logger(
            "[CTRL-Sony] media key end seq=${seq.orUnknown()} " +
                "key=PREVIOUS costMs=${SystemClock.elapsedRealtime() - start}"
        )
    }

    fun volumeUp(seq: String? = null) {
        executeVolumeCommand(
            command = "VOLUME_UP",
            direction = AudioManager.ADJUST_RAISE,
            seq = seq
        )
    }

    fun volumeDown(seq: String? = null) {
        executeVolumeCommand(
            command = "VOLUME_DOWN",
            direction = AudioManager.ADJUST_LOWER,
            seq = seq
        )
    }

    fun setVolume(requestedVolume: Int): JSONObject {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clampedVolume = requestedVolume.coerceIn(0, maxVolume)

        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            clampedVolume,
            AudioManager.FLAG_SHOW_UI
        )

        return createVolumeState()
    }

    fun seekTo(positionMs: Long, seq: String? = null) {
        val start = SystemClock.elapsedRealtime()
        logger("[CTRL-Sony] seek begin seq=${seq.orUnknown()} positionMs=$positionMs")
        logger("[Seek]\nreceived position=$positionMs")

        val controller = findActiveMediaController()
        if (controller == null) {
            logger("[Seek]\nno active MediaController")
            logger(
                "[CTRL-Sony] seek end seq=${seq.orUnknown()} " +
                    "costMs=${SystemClock.elapsedRealtime() - start}"
            )
            return
        }

        controller.transportControls.seekTo(positionMs.coerceAtLeast(0L))
        logger("[Seek]\ntransportControls.seekTo()")
        logger(
            "[CTRL-Sony] seek end seq=${seq.orUnknown()} " +
                "costMs=${SystemClock.elapsedRealtime() - start}"
        )
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun executeVolumeCommand(
        command: String,
        direction: Int,
        seq: String? = null
    ) {
        val start = SystemClock.elapsedRealtime()
        logger("[CTRL-Sony] volume begin seq=${seq.orUnknown()} cmd=$command")
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
        logger(
            "[CTRL-Sony] volume end seq=${seq.orUnknown()} " +
                "cmd=$command costMs=${SystemClock.elapsedRealtime() - start}"
        )
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

    private fun String?.orUnknown(): String = this?.takeIf { it.isNotBlank() } ?: "unknown"
}
