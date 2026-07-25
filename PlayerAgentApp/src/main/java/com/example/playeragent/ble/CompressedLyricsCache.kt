package com.example.playeragent.ble

import java.util.LinkedHashMap

internal class CompressedLyricsCache(
    private val maximumEntries: Int = 16,
    private val maximumBytes: Int = 512 * 1024
) {
    data class Key(
        val songKey: String,
        val trackId: String,
        val generation: Long,
        val format: String,
        val contentFingerprint: Long,
        val wordLineIndexes: String
    )

    data class Entry(
        val compressed: ByteArray,
        val uncompressedSize: Int,
        val crc32: String
    ) {
        val byteSize: Int
            get() = compressed.size
    }

    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var totalBytes = 0

    @Synchronized
    fun get(key: Key): Entry? = entries[key]

    @Synchronized
    fun put(key: Key, entry: Entry) {
        if (entry.byteSize <= 0 || entry.byteSize > maximumBytes) return
        entries.remove(key)?.let { totalBytes -= it.byteSize }
        entries[key] = entry
        totalBytes += entry.byteSize
        trim()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalBytes = 0
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun bytes(): Int = totalBytes

    private fun trim() {
        val iterator = entries.entries.iterator()
        while ((entries.size > maximumEntries || totalBytes > maximumBytes) &&
            iterator.hasNext()
        ) {
            totalBytes -= iterator.next().value.byteSize
            iterator.remove()
        }
    }
}
