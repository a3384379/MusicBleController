package com.example.controllerapp.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Deflater
import kotlin.random.Random

class ControllerProtocolCodecTest {
    @Test
    fun `A1 and A2 headers decode unsigned indexes`() {
        val a1 = byteArrayOf(
            0xA1.toByte(), 3, 0, 2, 0, 4, 9, 8
        )
        val art = ControllerProtocolCodec.decodeBinaryChunk(
            a1,
            ControllerProtocolCodec.ALBUM_ART_MAGIC
        )!!
        assertEquals(3, art.kindCode)
        assertEquals(2, art.index)
        assertEquals(4, art.total)
        assertArrayEquals(byteArrayOf(9, 8), art.payload)

        val a2 = byteArrayOf(
            0xA2.toByte(), 1, 0x01, 0x02, 0x01, 0x03, 7
        )
        val lyric = ControllerProtocolCodec.decodeBinaryChunk(
            a2,
            ControllerProtocolCodec.FULL_LYRICS_MAGIC
        )!!
        assertEquals(258, lyric.index)
        assertEquals(259, lyric.total)
        assertNull(
            ControllerProtocolCodec.decodeBinaryChunk(
                a2,
                ControllerProtocolCodec.ALBUM_ART_MAGIC
            )
        )
    }

    @Test
    fun `zlib vector validates CRC and decodes lyrics`() {
        val raw = JSONObject()
            .put("format", "zlib-json-v1")
            .put("trackId", "track-1")
            .put("generation", 42)
            .put(
                "lines",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("index", 0)
                            .put("timeMs", 100)
                            .put("durationMs", 900)
                            .put("text", "第一行")
                    )
                    .put(
                        JSONObject()
                            .put("index", 1)
                            .put("timeMs", 1_000)
                            .put("durationMs", 900)
                            .put("text", "第二行")
                    )
            )
            .toString()
            .toByteArray()
        val compressed = compress(raw)
        assertEquals(
            ControllerProtocolCodec.crc32Hex(compressed).toLong(16),
            ControllerProtocolCodec.crc32(compressed)
        )
        val decoded = ControllerProtocolCodec.zlibDecompress(compressed, raw.size)!!
        assertArrayEquals(raw, decoded)
        val lyrics = ControllerProtocolCodec.decodeLyricPayload(decoded)!!
        assertEquals("track-1", lyrics.first)
        assertEquals(42L, lyrics.second)
        assertEquals(listOf("第一行", "第二行"), lyrics.third.map { it.text })
    }

    @Test
    fun `reassembly survives duplicate and out of order chunks and detects loss`() {
        val random = Random(20260730L)
        repeat(100) {
            val source = ByteArray(1_024 + it) { random.nextInt(256).toByte() }
            val chunks = source.toList().chunked(37).map { part -> part.toByteArray() }
            val shuffled = chunks.indices.shuffled(random)
            val received = HashMap<Int, ByteArray>()
            shuffled.forEach { index ->
                received[index] = chunks[index]
                if (index % 3 == 0) received[index] = chunks[index]
            }
            assertArrayEquals(
                source,
                ControllerProtocolCodec.reassemble(received, chunks.size)
            )
            val missingIndex = chunks.lastIndex / 2
            received.remove(missingIndex)
            assertEquals(
                listOf(missingIndex),
                ControllerProtocolCodec.missingIndexes(received, chunks.size)
            )
            assertNull(ControllerProtocolCodec.reassemble(received, chunks.size))
        }
    }

    @Test
    fun `damaged compressed body is rejected`() {
        val raw = "fixed-vector".repeat(50).toByteArray()
        val compressed = compress(raw)
        compressed[compressed.lastIndex / 2] =
            (compressed[compressed.lastIndex / 2].toInt() xor 0x40).toByte()
        assertTrue(ControllerProtocolCodec.zlibDecompress(compressed, raw.size) == null)
    }

    private fun compress(value: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(value)
        deflater.finish()
        val output = ByteArray(value.size * 2)
        val count = deflater.deflate(output)
        deflater.end()
        return output.copyOf(count)
    }
}
