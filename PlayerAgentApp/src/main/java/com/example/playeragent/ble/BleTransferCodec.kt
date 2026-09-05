package com.example.playeragent.ble

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Pure transfer helpers shared by the BLE service and local protocol tests. */
object BleTransferCodec {
    const val HEADER_BYTES = 6

    fun zlibCompress(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED, false)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream(input.size.coerceAtLeast(256))
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count <= 0 && deflater.needsInput()) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun crc32(value: ByteArray): Long {
        return CRC32().apply { update(value) }.value
    }

    fun crc32Hex(value: ByteArray): String = "%08x".format(crc32(value))

    fun binaryChunks(
        magic: Int,
        version: Int,
        body: ByteArray,
        maximumPayload: Int
    ): List<ByteArray> {
        val chunkBytes = maximumPayload - HEADER_BYTES
        require(chunkBytes > 0) { "maximumPayload too small" }
        val total = (body.size + chunkBytes - 1) / chunkBytes
        require(total in 1..0xffff) { "invalid chunk count=$total" }
        return (0 until total).map { index ->
            val start = index * chunkBytes
            val end = minOf(start + chunkBytes, body.size)
            ByteArray(HEADER_BYTES + end - start).also { packet ->
                packet[0] = magic.toByte()
                packet[1] = version.toByte()
                packet[2] = ((index ushr 8) and 0xff).toByte()
                packet[3] = (index and 0xff).toByte()
                packet[4] = ((total ushr 8) and 0xff).toByte()
                packet[5] = (total and 0xff).toByte()
                body.copyInto(packet, HEADER_BYTES, start, end)
            }
        }
    }

    fun retryChunkIndexes(
        totalChunks: Int,
        missing: Collection<Int>,
        retryAll: Boolean,
        maximumPartial: Int = 32
    ): List<Int> {
        val valid = missing.filter { it in 0 until totalChunks }.distinct().sorted()
        return if (retryAll || valid.isEmpty() || valid.size > maximumPartial) {
            (0 until totalChunks).toList()
        } else {
            valid
        }
    }

    /**
     * Validates a retained transfer against the authoritative media identity.
     *
     * Album-art state must never participate in this decision: a song can have
     * valid lyrics while artwork is unavailable, delayed, or disabled.
     */
    fun isCurrentTransfer(
        transferTrackId: String,
        transferGeneration: Long,
        currentTrackId: String,
        currentGeneration: Long,
        trackIdsMatch: (String, String) -> Boolean = { left, right -> left == right }
    ): Boolean {
        if (transferTrackId.isBlank() || currentTrackId.isBlank()) {
            return false
        }
        val generationMatches = transferGeneration <= 0L ||
            currentGeneration <= 0L ||
            transferGeneration == currentGeneration
        return generationMatches && trackIdsMatch(transferTrackId, currentTrackId)
    }
}
