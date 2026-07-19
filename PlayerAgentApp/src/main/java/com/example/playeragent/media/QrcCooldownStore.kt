package com.example.playeragent.media

import java.util.concurrent.ConcurrentHashMap

/**
 * Cooldown state intentionally independent from QrcLyricManager's coarse
 * lookup monitor. Incremental lyric application can clear a cooldown while
 * holding LyricManager's state lock without creating a reverse lock edge.
 */
internal class QrcCooldownStore {
    private val entries = ConcurrentHashMap<String, Entry>()

    fun isEmpty(): Boolean = entries.isEmpty()

    operator fun get(songKey: String): Entry? = entries[songKey]

    fun put(songKey: String, entry: Entry) {
        entries[songKey] = entry
    }

    fun remove(songKey: String): Entry? = entries.remove(songKey)

    fun clear(): Boolean {
        val hadEntries = entries.isNotEmpty()
        entries.clear()
        return hadEntries
    }

    data class Entry(
        val retryAfterMs: Long,
        val generation: Long,
        val reason: String
    )
}
