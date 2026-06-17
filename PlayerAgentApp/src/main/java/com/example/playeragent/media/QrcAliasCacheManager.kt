package com.example.playeragent.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class QrcAliasCacheManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private val aliases = linkedMapOf<String, AliasEntry>()
    private var loaded = false

    @Synchronized
    fun getAlias(sourceSongKey: String): String? {
        ensureLoaded()
        val entry = aliases[sourceSongKey] ?: return null
        logger("[QrcAlias] hit source=$sourceSongKey")
        logger("[QrcAlias] redirect target=${entry.targetSongKey}")
        return entry.targetSongKey
    }

    @Synchronized
    fun saveAlias(sourceSongKey: String, targetSongKey: String) {
        if (sourceSongKey.isBlank() ||
            targetSongKey.isBlank() ||
            sourceSongKey == targetSongKey
        ) {
            return
        }
        ensureLoaded()
        aliases[sourceSongKey] = AliasEntry(
            sourceSongKey = sourceSongKey,
            targetSongKey = targetSongKey,
            createdAt = System.currentTimeMillis()
        )
        saveLocked()
        logger("[QrcAlias] saved source=$sourceSongKey target=$targetSongKey")
    }

    @Synchronized
    fun removeAlias(sourceSongKey: String) {
        ensureLoaded()
        if (aliases.remove(sourceSongKey) != null) {
            saveLocked()
            logger("[QrcAlias] removed invalid source=$sourceSongKey")
        }
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
                return
            }
            val array = root.optJSONArray("items") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val source = item.optString("sourceSongKey")
                val target = item.optString("targetSongKey")
                if (source.isBlank() || target.isBlank() || source == target) {
                    continue
                }
                aliases[source] = AliasEntry(
                    sourceSongKey = source,
                    targetSongKey = target,
                    createdAt = item.optLong("createdAt")
                )
            }
        } catch (_: Exception) {
            aliases.clear()
        }
    }

    private fun saveLocked() {
        try {
            val array = JSONArray()
            aliases.values.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("sourceSongKey", entry.sourceSongKey)
                        .put("targetSongKey", entry.targetSongKey)
                        .put("createdAt", entry.createdAt)
                )
            }
            val root = JSONObject()
                .put("version", CACHE_VERSION)
                .put("items", array)
            cacheFile().writeText(root.toString(), Charsets.UTF_8)
        } catch (exception: Exception) {
            logger("[QrcAlias] save failed: ${exception.message}")
        }
    }

    private fun cacheFile(): File {
        return File(
            QrcLyricUtils.cacheDirectory(appContext),
            "SongAliasCache.json"
        )
    }

    private data class AliasEntry(
        val sourceSongKey: String,
        val targetSongKey: String,
        val createdAt: Long
    )

    companion object {
        private const val CACHE_VERSION = 1
    }
}
