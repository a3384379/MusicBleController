package com.example.playeragent.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompressedLyricsCacheTest {
    @Test
    fun evictsLeastRecentlyUsedEntryByCountAndBytes() {
        val cache = CompressedLyricsCache(maximumEntries = 2, maximumBytes = 8)
        val first = key("one", generation = 1)
        val second = key("two", generation = 1)
        val third = key("three", generation = 1)
        cache.put(first, entry(4))
        cache.put(second, entry(4))
        cache.get(first)
        cache.put(third, entry(4))

        assertEquals(2, cache.size())
        assertEquals(8, cache.bytes())
        assertNull(cache.get(second))
        assertEquals(4, cache.get(first)?.compressed?.size)
    }

    @Test
    fun generationAndFingerprintArePartOfIdentity() {
        val cache = CompressedLyricsCache()
        val original = key("song", generation = 7, fingerprint = 11)
        cache.put(original, entry(5))

        assertNull(cache.get(key("song", generation = 8, fingerprint = 11)))
        assertNull(cache.get(key("song", generation = 7, fingerprint = 12)))
        assertEquals(5, cache.get(original)?.compressed?.size)
    }

    private fun key(
        song: String,
        generation: Long,
        fingerprint: Long = 1
    ) = CompressedLyricsCache.Key(
        songKey = song,
        trackId = "track-$song",
        generation = generation,
        format = "zlib-json-v1",
        contentFingerprint = fingerprint,
        wordLineIndexes = "1,2"
    )

    private fun entry(bytes: Int) = CompressedLyricsCache.Entry(
        compressed = ByteArray(bytes),
        uncompressedSize = bytes * 2,
        crc32 = "00000000"
    )
}
