package com.example.controllerapp.data

import android.content.Context
import com.example.controllerapp.model.LyricLine
import com.example.controllerapp.model.LyricWord
import com.example.controllerapp.model.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RestoredNowPlayingSnapshot(
    val playback: PlaybackState,
    val lines: List<LyricLine>,
    val artworkId: String
)

class NowPlayingSnapshotStore(context: Context) {
    private val file = File(context.filesDir, "LastNowPlayingSnapshot-v1.json")

    suspend fun load(nowMs: Long = System.currentTimeMillis()): RestoredNowPlayingSnapshot? =
        withContext(Dispatchers.IO) {
            val value = runCatching { JSONObject(file.readText()) }.getOrNull()
                ?: return@withContext null
            val savedAt = value.optLong("savedAt")
            if (value.optInt("version") != 1 ||
                savedAt <= 0L ||
                nowMs - savedAt !in 0..MAXIMUM_AGE_MS
            ) {
                return@withContext null
            }
            val trackId = value.optString("trackId")
            val title = value.optString("title")
            if (trackId.isBlank() || title.isBlank()) return@withContext null
            val lines = buildList {
                val array = value.optJSONArray("lines") ?: JSONArray()
                repeat(array.length()) lineLoop@{ index ->
                    val line = array.optJSONObject(index) ?: return@lineLoop
                    add(
                        LyricLine(
                            index = line.optInt("index"),
                            timeMs = line.optLong("timeMs"),
                            durationMs = line.optLong("durationMs"),
                            text = line.optString("text"),
                            translation = line.optString("translation").ifBlank { null },
                            romanization = line.optString("romanization").ifBlank { null },
                            words = buildList {
                                val words = line.optJSONArray("words") ?: JSONArray()
                                repeat(words.length()) wordLoop@{ wordIndex ->
                                    val word = words.optJSONObject(wordIndex) ?: return@wordLoop
                                    add(
                                        LyricWord(
                                            index = wordIndex,
                                            startMs = word.optLong("startMs"),
                                            durationMs = word.optLong("durationMs"),
                                            text = word.optString("text")
                                        )
                                    )
                                }
                            }
                        )
                    )
                }
            }
            RestoredNowPlayingSnapshot(
                playback = PlaybackState(
                    trackId = trackId,
                    generation = value.optLong("generation"),
                    title = title,
                    artist = value.optString("artist"),
                    album = value.optString("album"),
                    isPlaying = false,
                    positionMs = value.optLong("positionMs"),
                    durationMs = value.optLong("durationMs"),
                    restoredSnapshot = true
                ),
                lines = lines,
                artworkId = value.optString("artworkId")
            )
        }

    suspend fun save(
        playback: PlaybackState,
        lines: List<LyricLine>,
        artworkId: String
    ) = withContext(Dispatchers.IO) {
        if (playback.trackId.isBlank() || playback.title.isBlank() || playback.title == "-") {
            return@withContext
        }
        val value = JSONObject()
            .put("version", 1)
            .put("trackId", playback.trackId)
            .put("generation", playback.generation)
            .put("title", playback.title)
            .put("artist", playback.artist)
            .put("album", playback.album)
            .put("positionMs", playback.positionMs)
            .put("durationMs", playback.durationMs)
            .put("artworkId", artworkId)
            .put("savedAt", System.currentTimeMillis())
            .put(
                "lines",
                JSONArray().also { array ->
                    lines.take(5).forEach { line ->
                        array.put(
                            JSONObject()
                                .put("index", line.index)
                                .put("timeMs", line.timeMs)
                                .put("durationMs", line.durationMs)
                                .put("text", line.text)
                                .put("translation", line.translation.orEmpty())
                                .put("romanization", line.romanization.orEmpty())
                                .put(
                                    "words",
                                    JSONArray().also { words ->
                                        line.words.forEach { word ->
                                            words.put(
                                                JSONObject()
                                                    .put("startMs", word.startMs)
                                                    .put("durationMs", word.durationMs)
                                                    .put("text", word.text)
                                            )
                                        }
                                    }
                                )
                        )
                    }
                }
            )
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(value.toString())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
    }

    companion object {
        const val MAXIMUM_AGE_MS = 24 * 60 * 60 * 1_000L
    }
}
