package com.example.playeragent.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

class BleTransferCodecTest {
    @Test
    fun zlibAndCrcMatchFixedVector() {
        val input = "{\"trackId\":\"qq-1\",\"lines\":[\"第一行\",\"second\"]}"
            .toByteArray(Charsets.UTF_8)
        val compressed = BleTransferCodec.zlibCompress(input)

        assertTrue(compressed.isNotEmpty())
        assertArrayEquals(input, inflate(compressed))
        assertEquals("cbf43926", BleTransferCodec.crc32Hex("123456789".toByteArray()))
    }

    @Test
    fun a2ChunksSurviveReorderAndDuplicateDetection() {
        val body = ByteArray(83) { it.toByte() }
        val chunks = BleTransferCodec.binaryChunks(0xA2, 1, body, 19)
        assertEquals(7, chunks.size)
        chunks.forEachIndexed { index, packet ->
            assertEquals(0xA2, packet[0].toInt() and 0xff)
            assertEquals(1, packet[1].toInt() and 0xff)
            assertEquals(index, ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff))
            assertEquals(chunks.size, ((packet[4].toInt() and 0xff) shl 8) or (packet[5].toInt() and 0xff))
        }

        val received = linkedMapOf<Int, ByteArray>()
        listOf(6, 0, 2, 2, 1, 4, 5, 3).forEach { index ->
            received[index] = chunks[index].copyOfRange(BleTransferCodec.HEADER_BYTES, chunks[index].size)
        }
        val assembled = (chunks.indices)
            .flatMap { received.getValue(it).asIterable() }
            .toByteArray()
        assertArrayEquals(body, assembled)
    }

    @Test
    fun retrySelectionUsesPartialThenFullFallback() {
        assertEquals(
            listOf(1, 3),
            BleTransferCodec.retryChunkIndexes(8, listOf(3, 1, 3, -1, 99), false)
        )
        assertEquals(
            (0 until 8).toList(),
            BleTransferCodec.retryChunkIndexes(8, emptyList(), false)
        )
        assertEquals(
            (0 until 40).toList(),
            BleTransferCodec.retryChunkIndexes(40, (0 until 33).toList(), false)
        )
    }

    @Test
    fun lyricRetryIdentityDoesNotDependOnArtworkAvailability() {
        assertTrue(
            BleTransferCodec.isCurrentTransfer(
                transferTrackId = "qq-track-1",
                transferGeneration = 7,
                currentTrackId = "qq-track-1",
                currentGeneration = 7
            )
        )
        assertTrue(
            BleTransferCodec.isCurrentTransfer(
                transferTrackId = "qq-track-1",
                transferGeneration = 0,
                currentTrackId = "qq-track-1",
                currentGeneration = 9
            )
        )
        assertFalse(
            BleTransferCodec.isCurrentTransfer(
                transferTrackId = "qq-track-1",
                transferGeneration = 7,
                currentTrackId = "qq-track-2",
                currentGeneration = 8
            )
        )
        assertFalse(
            BleTransferCodec.isCurrentTransfer(
                transferTrackId = "qq-track-1",
                transferGeneration = 7,
                currentTrackId = "qq-track-1",
                currentGeneration = 8
            )
        )
    }

    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater(false)
        return try {
            inflater.setInput(input)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            while (!inflater.finished()) {
                output.write(buffer, 0, inflater.inflate(buffer))
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
