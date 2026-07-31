package com.example.controllerapp

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controllerapp.data.ControllerLogStore
import com.example.controllerapp.data.ControllerPreferences
import com.example.controllerapp.data.PlaybackHistoryDatabase
import com.example.controllerapp.protocol.ControllerProtocolCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.zip.Deflater

@RunWith(AndroidJUnit4::class)
class ControllerRepositoryProtocolTest {
    private lateinit var database: PlaybackHistoryDatabase
    private lateinit var repository: ControllerRepository
    private lateinit var logStore: ControllerLogStore
    private lateinit var root: File
    private val commands = Collections.synchronizedList(mutableListOf<JSONObject>())

    @Before
    fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "repository-protocol-test-${System.nanoTime()}").apply {
            mkdirs()
        }
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        database = Room.inMemoryDatabaseBuilder(
            base,
            PlaybackHistoryDatabase::class.java
        ).build()
        logStore = ControllerLogStore(isolated)
        repository = ControllerRepository(
            isolated,
            ControllerPreferences(isolated),
            database.historyDao(),
            logStore
        )
        repository.attachTransport { bytes ->
            commands += JSONObject(String(bytes, Charsets.UTF_8))
            true
        }
    }

    @After
    fun tearDown() {
        repository.close()
        database.close()
        logStore.close()
        root.deleteRecursively()
    }

    @Test
    fun fullLyricsAdoptsNewerSonyGenerationAndPublishesAllLines() = runBlocking {
        repository.onTransportReady("Sony", "00:11:22:33:44:55", 517)
        repository.handleNotification(
            json(
                "type" to "trackInfo",
                "trackId" to "song-1",
                "generation" to 1L,
                "title" to "测试歌曲",
                "artist" to "QQ 音乐"
            )
        )
        repository.handleNotification(
            json(
                "type" to "clientCapabilitiesAck",
                "protocolVersion" to 2,
                "albumArtBinary" to true,
                "fullLyricsZlib" to true,
                "lyricWindow" to true,
                "ping" to true,
                "transferRetry" to true
            )
        )
        await { commands.any { it.optString("cmd") == "GET_FULL_LYRICS" } }

        val windowId = "window-1"
        repository.handleNotification(
            json(
                "type" to "lyricWindowStart",
                "trackId" to "song-1",
                "transferId" to windowId,
                "generation" to 2L,
                "count" to 5
            )
        )
        repeat(5) { index ->
            repository.handleNotification(
                json(
                    "type" to "lyricWindowChunk",
                    "trackId" to "song-1",
                    "transferId" to windowId,
                    "index" to index + 10,
                    "timeMs" to index * 1_000L,
                    "durationMs" to 900L,
                    "text" to "窗口 ${index + 1}"
                )
            )
        }
        repository.handleNotification(
            json(
                "type" to "lyricWindowEnd",
                "trackId" to "song-1",
                "transferId" to windowId,
                "generation" to 2L
            )
        )
        await { repository.lyrics.value.windowLines.size == 5 }
        assertEquals(2L, repository.playback.value.generation)

        val lines = JSONArray().apply {
            repeat(60) { index ->
                put(
                    JSONObject()
                        .put("index", index)
                        .put("timeMs", index * 1_000L)
                        .put("durationMs", 900L)
                        .put("text", "完整歌词 $index")
                )
            }
        }
        val raw = JSONObject()
            .put("format", "zlib-json-v1")
            .put("trackId", "song-1")
            .put("generation", 2L)
            .put("lines", lines)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val compressed = deflate(raw)
        val chunks = compressed.toList().chunked(220).map { it.toByteArray() }
        val transferId = "lyrics-1"
        repository.handleNotification(
            json(
                "type" to "fullLyricsBinaryStart",
                "trackId" to "song-1",
                "transferId" to transferId,
                "generation" to 2L,
                "size" to compressed.size,
                "uncompressedSize" to raw.size,
                "chunks" to chunks.size,
                "count" to 60,
                "crc32" to ControllerProtocolCodec.crc32Hex(compressed)
            )
        )
        chunks.indices.reversed().forEach { index ->
            repository.handleNotification(binaryChunk(index, chunks, chunks[index]))
        }
        repository.handleNotification(
            json(
                "type" to "fullLyricsBinaryEnd",
                "trackId" to "song-1",
                "transferId" to transferId,
                "generation" to 2L
            )
        )

        await { repository.lyrics.value.isFinal }
        assertEquals(60, repository.lyrics.value.fullLines.size)
        assertEquals("zlib-json-v1", repository.lyrics.value.protocolFormat)
        assertTrue(repository.lyrics.value.partialFullLines.isEmpty())
    }

    @Test
    fun missingBinaryLyricChunkRequestsOnlyTheMissingPacket() = runBlocking {
        prepareV2Track("song-retry", generation = 1L)
        val lines = JSONArray().apply {
            repeat(60) { index ->
                put(
                    JSONObject()
                        .put("index", index)
                        .put("timeMs", index * 1_000L)
                        .put("durationMs", 900L)
                        .put("text", "缺包测试 $index ${index * 7919L}")
                )
            }
        }
        val raw = JSONObject()
            .put("format", "zlib-json-v1")
            .put("trackId", "song-retry")
            .put("generation", 2L)
            .put("lines", lines)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val compressed = deflate(raw)
        val chunks = compressed.toList().chunked(80).map { it.toByteArray() }
        assertTrue(chunks.size > 2)
        val transferId = "lyrics-retry"
        repository.handleNotification(
            json(
                "type" to "fullLyricsBinaryStart",
                "trackId" to "song-retry",
                "transferId" to transferId,
                "generation" to 2L,
                "size" to compressed.size,
                "uncompressedSize" to raw.size,
                "chunks" to chunks.size,
                "count" to 60,
                "crc32" to ControllerProtocolCodec.crc32Hex(compressed)
            )
        )
        chunks.indices.filter { it != 1 }.reversed().forEach { index ->
            repository.handleNotification(binaryChunk(index, chunks, chunks[index]))
        }
        repository.handleNotification(
            json(
                "type" to "fullLyricsBinaryEnd",
                "trackId" to "song-retry",
                "transferId" to transferId,
                "generation" to 2L
            )
        )

        await {
            commands.any {
                it.optString("cmd") == "RETRY_TRANSFER" &&
                    it.optString("transferId") == transferId
            }
        }
        val retry = commands.last {
            it.optString("cmd") == "RETRY_TRANSFER" &&
                it.optString("transferId") == transferId
        }
        assertFalse(retry.optBoolean("retryAll"))
        assertEquals(1, retry.getJSONArray("missing").length())
        assertEquals(1, retry.getJSONArray("missing").getInt(0))
    }

    @Test
    fun staleMediaErrorsDoNotOverwriteCurrentSongState() = runBlocking {
        prepareV2Track("song-current", generation = 5L)
        repository.handleNotification(
            json(
                "type" to "fullLyricsUnavailable",
                "trackId" to "song-current",
                "generation" to 4L,
                "reason" to "stale lyric failure"
            )
        )
        repository.handleNotification(
            json(
                "type" to "lyricWindowUnavailable",
                "trackId" to "song-current",
                "generation" to 4L,
                "reason" to "stale window failure"
            )
        )
        repository.handleNotification(
            json(
                "type" to "albumArtBinaryError",
                "id" to "song-current",
                "generation" to 4L,
                "reason" to "stale artwork failure"
            )
        )
        delay(100L)

        assertTrue(repository.lyrics.value.failureReason.isBlank())
        assertTrue(repository.artwork.value.failureReason.isBlank())
    }

    private suspend fun prepareV2Track(trackId: String, generation: Long) {
        repository.onTransportReady("Sony", "00:11:22:33:44:55", 517)
        repository.handleNotification(
            json(
                "type" to "trackInfo",
                "trackId" to trackId,
                "generation" to generation,
                "title" to "测试歌曲",
                "artist" to "QQ 音乐"
            )
        )
        repository.handleNotification(
            json(
                "type" to "clientCapabilitiesAck",
                "protocolVersion" to 2,
                "albumArtBinary" to true,
                "fullLyricsZlib" to true,
                "lyricWindow" to true,
                "ping" to true,
                "transferRetry" to true
            )
        )
        await { repository.playback.value.trackId == trackId }
        await { commands.any { it.optString("cmd") == "GET_FULL_LYRICS" } }
    }

    private fun json(vararg values: Pair<String, Any>): ByteArray =
        JSONObject().apply { values.forEach { (key, value) -> put(key, value) } }
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun binaryChunk(
        index: Int,
        chunks: List<ByteArray>,
        payload: ByteArray
    ): ByteArray = ByteArray(6 + payload.size).also { packet ->
        packet[0] = ControllerProtocolCodec.FULL_LYRICS_MAGIC.toByte()
        packet[1] = 1
        packet[2] = (index ushr 8).toByte()
        packet[3] = index.toByte()
        packet[4] = (chunks.size ushr 8).toByte()
        packet[5] = chunks.size.toByte()
        payload.copyInto(packet, destinationOffset = 6)
    }

    private fun deflate(value: ByteArray): ByteArray {
        val deflater = Deflater().apply {
            setInput(value)
            finish()
        }
        return ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(1_024)
            while (!deflater.finished()) {
                output.write(buffer, 0, deflater.deflate(buffer))
            }
            deflater.end()
            output.toByteArray()
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(20L)
        }
        assertTrue("condition did not become true", condition())
    }
}
