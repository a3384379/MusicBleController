package com.example.controllerapp.protocol

import com.example.controllerapp.model.LyricLine
import com.example.controllerapp.model.LyricWord
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

data class BinaryChunk(
    val magic: Int,
    val kindCode: Int,
    val index: Int,
    val total: Int,
    val payload: ByteArray
)

object ControllerProtocolCodec {
    const val HEADER_SIZE = 6
    const val ALBUM_ART_MAGIC = 0xA1
    const val FULL_LYRICS_MAGIC = 0xA2

    fun decodeBinaryChunk(data: ByteArray, expectedMagic: Int): BinaryChunk? {
        if (data.size <= HEADER_SIZE || (data[0].toInt() and 0xff) != expectedMagic) {
            return null
        }
        val kind = data[1].toInt() and 0xff
        val index = ((data[2].toInt() and 0xff) shl 8) or
            (data[3].toInt() and 0xff)
        val total = ((data[4].toInt() and 0xff) shl 8) or
            (data[5].toInt() and 0xff)
        if (total <= 0 || index !in 0 until total) return null
        return BinaryChunk(
            magic = expectedMagic,
            kindCode = kind,
            index = index,
            total = total,
            payload = data.copyOfRange(HEADER_SIZE, data.size)
        )
    }

    fun missingIndexes(chunks: Map<Int, ByteArray>, expectedCount: Int): List<Int> =
        if (expectedCount <= 0) {
            emptyList()
        } else {
            (0 until expectedCount).filterNot(chunks::containsKey)
        }

    fun reassemble(chunks: Map<Int, ByteArray>, expectedCount: Int): ByteArray? {
        if (expectedCount <= 0 || missingIndexes(chunks, expectedCount).isNotEmpty()) {
            return null
        }
        val output = ByteArrayOutputStream()
        repeat(expectedCount) { index -> output.write(chunks[index] ?: return null) }
        return output.toByteArray()
    }

    fun crc32(value: ByteArray): Long = CRC32().apply { update(value) }.value

    fun crc32Hex(value: ByteArray): String = "%08x".format(crc32(value))

    fun parseHexCrc(value: String?): Long? =
        value?.trim()?.takeIf(String::isNotEmpty)?.toLongOrNull(16)

    fun zlibDecompress(input: ByteArray, expectedSize: Int): ByteArray? {
        if (input.isEmpty() || expectedSize <= 0 || expectedSize > 512 * 1024) {
            return null
        }
        val inflater = Inflater(false)
        return try {
            inflater.setInput(input)
            val output = ByteArray(expectedSize)
            val count = inflater.inflate(output)
            if (!inflater.finished() || count != expectedSize) null else output
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    fun decodeLyricLine(value: JSONObject): LyricLine? {
        val index = value.optInt("index", -1)
        if (index < 0) return null
        val wordsArray = value.optJSONArray("words")
        val words = buildList {
            if (wordsArray != null) {
                repeat(wordsArray.length()) { wordIndex ->
                    val word = wordsArray.optJSONObject(wordIndex) ?: return@repeat
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
        }
        return LyricLine(
            index = index,
            timeMs = value.optLong("timeMs"),
            durationMs = value.optLong("durationMs"),
            text = value.optString("text"),
            translation = sanitizeSecondary(
                value.takeIf { it.has("translation") && !it.isNull("translation") }
                    ?.optString("translation")
            ),
            romanization = sanitizeSecondary(
                value.takeIf { it.has("romanization") && !it.isNull("romanization") }
                    ?.optString("romanization")
            ),
            words = words
        )
    }

    fun decodeLyricPayload(data: ByteArray): Triple<String, Long, List<LyricLine>>? {
        val value = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?: return null
        val trackId = value.optString("trackId")
        val generation = value.optLong("generation")
        val lineValues = value.optJSONArray("lines") ?: return null
        val lines = buildList {
            repeat(lineValues.length()) { index ->
                decodeLyricLine(lineValues.optJSONObject(index) ?: return@repeat)?.let(::add)
            }
        }
        return Triple(trackId, generation, lines)
    }

    fun missingArray(indexes: Collection<Int>): JSONArray =
        JSONArray().also { array -> indexes.forEach(array::put) }

    fun sanitizeSecondary(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty() || text.all { it == '/' }) return null
        return text.takeUnless {
            it.lowercase() in setOf(
                "--",
                "---",
                "null",
                "nil",
                "none",
                "暂无",
                "暂无翻译",
                "暂无罗马音"
            )
        }
    }
}

data class CurrentWordOrderingFence(
    val generation: Long = -1L,
    val sequence: Long = -1L,
    val positionMs: Long = -1L
) {
    fun accept(
        incomingGeneration: Long,
        incomingSequence: Long,
        incomingPositionMs: Long
    ): CurrentWordOrderingFence? {
        val hasOrdering = incomingGeneration > 0L && incomingSequence > 0L
        if (hasOrdering) {
            if (incomingGeneration < generation ||
                (incomingGeneration == generation && incomingSequence <= sequence)
            ) {
                return null
            }
        }
        if (!hasOrdering &&
            positionMs >= 0L &&
            incomingPositionMs < positionMs &&
            positionMs - incomingPositionMs <= 1_500L
        ) {
            return null
        }
        return CurrentWordOrderingFence(
            generation = if (incomingGeneration > 0L) incomingGeneration else generation,
            sequence = if (incomingSequence > 0L) incomingSequence else sequence,
            positionMs = incomingPositionMs
        )
    }
}

data class ServerCapabilities(
    val negotiated: Boolean = false,
    val protocolVersion: Int = 1,
    val albumArtBinary: Boolean = false,
    val fullLyricsZlib: Boolean = false,
    val lyricWindow: Boolean = false,
    val ping: Boolean = false,
    val transferRetry: Boolean = false
)

object CapabilityPolicy {
    const val ACK_TIMEOUT_MS = 300L

    fun fallbackIfUnacknowledged(value: ServerCapabilities): ServerCapabilities =
        if (value.negotiated) value else ServerCapabilities()
}
