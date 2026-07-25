package com.example.playeragent.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BleProtocolChaosTest {
    @Test
    fun randomLossDuplicateReorderAndRetryAlwaysReassembles() {
        val random = Random(0x51514cL)
        repeat(250) { iteration ->
            val body = ByteArray(64 + random.nextInt(4_096))
            random.nextBytes(body)
            val mtuPayload = 20 + random.nextInt(220)
            val packets = BleTransferCodec.binaryChunks(
                magic = 0xA2,
                version = 1,
                body = body,
                maximumPayload = mtuPayload
            )
            val order = packets.indices.shuffled(random)
            val received = linkedMapOf<Int, ByteArray>()
            order.forEach { index ->
                if (random.nextDouble() >= 0.18) {
                    received[index] = payload(packets[index])
                    if (random.nextDouble() < 0.20) {
                        received[index] = payload(packets[index])
                    }
                }
            }
            val missing = packets.indices.filter { received[it] == null }
            val selected = BleTransferCodec.retryChunkIndexes(
                totalChunks = packets.size,
                missing = missing,
                retryAll = missing.size > 32
            )
            selected.forEach { index ->
                received[index] = payload(packets[index])
            }

            assertEquals(
                "iteration=$iteration",
                packets.size,
                received.size
            )
            val assembled = packets.indices
                .flatMap { received.getValue(it).asIterable() }
                .toByteArray()
            assertArrayEquals("iteration=$iteration", body, assembled)
            assertEquals(
                BleTransferCodec.crc32(body),
                BleTransferCodec.crc32(assembled)
            )
        }
    }

    @Test
    fun crcCorruptionAndRapidGenerationChangesAreRejected() {
        val original = ByteArray(512) { it.toByte() }
        val corrupt = original.copyOf().also { it[211] = (it[211].toInt() xor 0x5a).toByte() }
        assertFalse(BleTransferCodec.crc32(original) == BleTransferCodec.crc32(corrupt))

        repeat(100) { generation ->
            assertTrue(
                BleTransferCodec.isCurrentTransfer(
                    transferTrackId = "qq-$generation",
                    transferGeneration = generation.toLong(),
                    currentTrackId = "qq-$generation",
                    currentGeneration = generation.toLong()
                )
            )
            assertFalse(
                BleTransferCodec.isCurrentTransfer(
                    transferTrackId = "qq-$generation",
                    transferGeneration = generation.toLong(),
                    currentTrackId = "qq-${generation + 1}",
                    currentGeneration = generation.toLong() + 1
                )
            )
        }
    }

    private fun payload(packet: ByteArray): ByteArray {
        return packet.copyOfRange(BleTransferCodec.HEADER_BYTES, packet.size)
    }

    private fun IntRange.shuffled(random: Random): List<Int> {
        return toMutableList().also { values ->
            for (index in values.lastIndex downTo 1) {
                val swap = random.nextInt(index + 1)
                val value = values[index]
                values[index] = values[swap]
                values[swap] = value
            }
        }
    }
}
