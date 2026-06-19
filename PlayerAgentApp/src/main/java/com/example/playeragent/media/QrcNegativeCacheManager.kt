package com.example.playeragent.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class QrcNegativeCacheManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private val items = linkedMapOf<String, NegativeEntry>()
    private var loaded = false

    @Synchronized
    fun isNegative(songKey: String): Boolean {
        ensureLoaded()
        val entry = items[songKey] ?: return false
        val now = System.currentTimeMillis()
        if (now - entry.time > NEGATIVE_TTL_MS) {
            items.remove(songKey)
            saveLocked()
            return false
        }
        logger("[QrcNegativeCache] hit songKey=$songKey")
        logger("[QrcNegativeCache] skip scan")
        return true
    }

    @Synchronized
    fun saveNegative(songKey: String, reason: String) {
        if (songKey.isBlank()) {
            return
        }
        ensureLoaded()
        items[songKey] = NegativeEntry(
            songKey = songKey,
            reason = reason,
            time = System.currentTimeMillis()
        )
        saveLocked()
        logger("[QrcNegativeCache] saved songKey=$songKey reason=$reason")
    }

    @Synchronized
    fun removeNegative(songKey: String, reason: String = "") {
        ensureLoaded()
        if (items.remove(songKey) != null) {
            saveLocked()
            val suffix = if (reason.isBlank()) "" else " reason=$reason"
            logger("[QrcNegativeCache] removed songKey=$songKey$suffix")
        }
    }

    @Synchronized
    fun clear() {
        ensureLoaded()
        items.clear()
        saveLocked()
        logger("[QrcNegativeCache] cleared")
    }

    private fun ensureLoaded() {
        if (loaded) {
            return
        }
        loaded = true
        val file = cacheFile()
        if (!file.exists()) {
            return
        }
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version") != CACHE_VERSION) {
                logger("[QrcNegativeCache] ignored old cache version")
                return
            }
            val now = System.currentTimeMillis()
            val array = root.optJSONArray("items") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val songKey = item.optString("songKey")
                val time = item.optLong("time")
                if (songKey.isBlank() || now - time > NEGATIVE_TTL_MS) {
                    continue
                }
                items[songKey] = NegativeEntry(
                    songKey = songKey,
                    reason = item.optString("reason"),
                    time = time
                )
            }
        } catch (_: Exception) {
            items.clear()
        }
    }

    private fun saveLocked() {
        try {
            val array = JSONArray()
            items.values.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("songKey", entry.songKey)
                        .put("reason", entry.reason)
                        .put("time", entry.time)
                )
            }
            val root = JSONObject()
                .put("version", CACHE_VERSION)
                .put("items", array)
            cacheFile().writeText(root.toString(), Charsets.UTF_8)
        } catch (exception: Exception) {
            logger("[QrcNegativeCache] save failed: ${exception.message}")
        }
    }

    private fun cacheFile(): File {
        return File(
            QrcLyricUtils.cacheDirectory(appContext),
            "QrcNegativeCache.json"
        )
    }

    private data class NegativeEntry(
        val songKey: String,
        val reason: String,
        val time: Long
    )

    companion object {
        private const val CACHE_VERSION = 2
        private const val NEGATIVE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
