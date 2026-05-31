package com.example.playeragent.media

import android.content.Context
import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject

class VolumeDiagnosticManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun runDiagnostic(): JSONObject {
        val isVolumeFixed = audioManager.isVolumeFixed
        val before = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI
        )
        val afterRaise = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        val afterLower = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        logger(
            "[VolumeDiagnostic]\n" +
                "isVolumeFixed=$isVolumeFixed\n" +
                "before=$before\n" +
                "max=$max\n" +
                "afterRaise=$afterRaise\n" +
                "afterLower=$afterLower"
        )

        return JSONObject()
            .put("type", "volumeInfo")
            .put("isVolumeFixed", isVolumeFixed)
            .put("current", before)
            .put("max", max)
            .put("afterRaise", afterRaise)
            .put("afterLower", afterLower)
    }

    fun runAudioStreamDiagnostic(): JSONObject {
        val streams = listOf(
            AudioStream("MUSIC", AudioManager.STREAM_MUSIC),
            AudioStream("SYSTEM", AudioManager.STREAM_SYSTEM),
            AudioStream("RING", AudioManager.STREAM_RING),
            AudioStream("ALARM", AudioManager.STREAM_ALARM),
            AudioStream("NOTIFICATION", AudioManager.STREAM_NOTIFICATION),
            AudioStream("VOICE_CALL", AudioManager.STREAM_VOICE_CALL),
            AudioStream("DTMF", AudioManager.STREAM_DTMF)
        )

        val results = JSONArray()

        for (stream in streams) {
            val result = diagnoseStream(stream)
            results.put(result)
        }

        return JSONObject()
            .put("type", "audioStreamDiagnostic")
            .put("streams", results)
    }

    private fun diagnoseStream(stream: AudioStream): JSONObject {
        return try {
            val before = audioManager.getStreamVolume(stream.type)
            val max = audioManager.getStreamMaxVolume(stream.type)

            audioManager.adjustStreamVolume(
                stream.type,
                AudioManager.ADJUST_RAISE,
                0
            )
            val afterRaise = audioManager.getStreamVolume(stream.type)

            audioManager.adjustStreamVolume(
                stream.type,
                AudioManager.ADJUST_LOWER,
                0
            )
            val afterLower = audioManager.getStreamVolume(stream.type)

            logger(
                "[AudioStreamDiagnostic]\n" +
                    "stream=${stream.name} before=$before max=$max " +
                    "afterRaise=$afterRaise afterLower=$afterLower"
            )

            JSONObject()
                .put("stream", stream.name)
                .put("before", before)
                .put("max", max)
                .put("afterRaise", afterRaise)
                .put("afterLower", afterLower)
        } catch (exception: Exception) {
            logger("[AudioStreamDiagnostic]\nstream=${stream.name} failed=${exception.message}")
            JSONObject()
                .put("stream", stream.name)
                .put("error", exception.message.orEmpty())
        }
    }

    private data class AudioStream(
        val name: String,
        val type: Int
    )
}
