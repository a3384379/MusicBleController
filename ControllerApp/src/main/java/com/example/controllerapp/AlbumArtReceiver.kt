package com.example.controllerapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject

class AlbumArtReceiver(
    private val logger: (String) -> Unit,
    private val onBitmapReady: (Bitmap) -> Unit,
    private val onUnavailable: () -> Unit
) {

    private val chunks = mutableMapOf<Int, String>()
    private var totalChunks = 0

    fun handle(jsonObject: JSONObject) {
        when (jsonObject.optString("type")) {
            "albumArtStart" -> handleStart(jsonObject)
            "albumArtChunk" -> handleChunk(jsonObject)
            "albumArtEnd" -> handleEnd()
            "albumArtUnavailable" -> handleUnavailable()
        }
    }

    private fun handleStart(jsonObject: JSONObject) {
        chunks.clear()
        totalChunks = jsonObject.optInt("totalChunks", 0)
        logger("albumArtStart totalChunks=$totalChunks")
    }

    private fun handleChunk(jsonObject: JSONObject) {
        val index = jsonObject.optInt("index", -1)
        val data = jsonObject.optString("data")
        if (index < 0 || data.isBlank()) {
            logger("chunk ignored index=$index")
            return
        }

        chunks[index] = data
        logger("chunk received index=$index")
    }

    private fun handleEnd() {
        try {
            if (totalChunks <= 0 || chunks.size < totalChunks) {
                logger("decode failed: missing chunks ${chunks.size}/$totalChunks")
                return
            }

            val base64 = buildString {
                for (index in 0 until totalChunks) {
                    append(chunks[index] ?: error("missing chunk $index"))
                }
            }
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                logger("decode failed: BitmapFactory returned null")
                return
            }

            logger("albumArtEnd decode success")
            onBitmapReady(bitmap)
        } catch (exception: Exception) {
            logger("decode failed: ${exception.message}")
        } finally {
            chunks.clear()
            totalChunks = 0
        }
    }

    private fun handleUnavailable() {
        chunks.clear()
        totalChunks = 0
        logger("albumArtUnavailable")
        onUnavailable()
    }
}
