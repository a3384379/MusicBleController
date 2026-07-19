package com.example.playeragent.media

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Small metadata-only index for parsed QRC caches; lyric bodies stay in their own files. */
class QrcParsedCacheIndexStore(
    private val cacheDirectory: File,
    private val logger: (String) -> Unit
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "QrcParsedIndexWriteThread").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private val indexFile = File(cacheDirectory, INDEX_FILE_NAME)
    private var pendingWrite: ScheduledFuture<*>? = null
    @Volatile
    var loaded: Boolean = false
        private set

    init {
        load()
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.values.toList() }

    fun replace(values: Collection<Entry>) {
        synchronized(lock) {
            entries.clear()
            values.forEach { entries[it.songKey] = it }
            loaded = true
            scheduleWriteLocked()
        }
    }

    fun upsert(value: Entry) {
        synchronized(lock) {
            entries[value.songKey] = value
            loaded = true
            scheduleWriteLocked()
        }
    }

    fun remove(songKey: String) {
        synchronized(lock) {
            if (entries.remove(songKey) != null) {
                scheduleWriteLocked()
            }
        }
    }

    fun flushNow() {
        synchronized(lock) {
            pendingWrite?.cancel(false)
            pendingWrite = null
        }
        writeSnapshot()
    }

    fun close() {
        flushNow()
        executor.shutdownNow()
    }

    private fun load() {
        if (!indexFile.isFile) {
            logger("[QrcParsedIndex] missing; background rebuild required")
            return
        }
        try {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            if (root.optInt("version") != INDEX_VERSION) {
                logger("[QrcParsedIndex] version mismatch; background rebuild required")
                return
            }
            val array = root.optJSONArray("entries") ?: JSONArray()
            val loadedEntries = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toEntry()
            }
            synchronized(lock) {
                entries.clear()
                loadedEntries.forEach { entries[it.songKey] = it }
                loaded = true
            }
            logger("[QrcParsedIndex] loaded entries=${loadedEntries.size}")
        } catch (exception: Exception) {
            logger(
                "[QrcParsedIndex] damaged; background rebuild required " +
                    "error=${exception.message}"
            )
        }
    }

    private fun scheduleWriteLocked() {
        pendingWrite?.cancel(false)
        pendingWrite = executor.schedule(
            { writeSnapshot() },
            WRITE_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun writeSnapshot() {
        val snapshot = synchronized(lock) { entries.values.toList() }
        try {
            cacheDirectory.mkdirs()
            val value = JSONObject()
                .put("version", INDEX_VERSION)
                .put("builtAt", System.currentTimeMillis())
                .put(
                    "entries",
                    JSONArray().also { array ->
                        snapshot.forEach { array.put(it.toJson()) }
                    }
                )
                .toString()
            val temp = File(cacheDirectory, ".$INDEX_FILE_NAME.${System.nanoTime()}.tmp")
            FileOutputStream(temp).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temp.toPath(),
                    indexFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.recoverCatching {
                Files.move(
                    temp.toPath(),
                    indexFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrThrow()
            logger("[QrcParsedIndex] saved entries=${snapshot.size}")
        } catch (exception: Exception) {
            logger("[QrcParsedIndex] save failed error=${exception.message}")
        }
    }

    private fun JSONObject.toEntry(): Entry? {
        val songKey = optString("songKey")
        val title = optString("title")
        val fileName = optString("fileName")
        val lines = optInt("lines")
        if (songKey.isBlank() || title.isBlank() || fileName.isBlank() || lines <= 0) {
            return null
        }
        return Entry(
            songKey = songKey,
            normalizedTitle = optString("normalizedTitle"),
            normalizedArtist = optString("normalizedArtist"),
            normalizedAlbum = optString("normalizedAlbum"),
            title = title,
            artist = optString("artist"),
            album = optString("album"),
            groupId = optString("groupId").takeIf(String::isNotBlank),
            fileName = fileName,
            lines = lines,
            createdAt = optLong("createdAt"),
            fingerprint = optString("fingerprint").takeIf(String::isNotBlank)
        )
    }

    private fun Entry.toJson(): JSONObject {
        return JSONObject()
            .put("songKey", songKey)
            .put("normalizedTitle", normalizedTitle)
            .put("normalizedArtist", normalizedArtist)
            .put("normalizedAlbum", normalizedAlbum)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("groupId", groupId.orEmpty())
            .put("fileName", fileName)
            .put("lines", lines)
            .put("createdAt", createdAt)
            .put("fingerprint", fingerprint.orEmpty())
    }

    data class Entry(
        val songKey: String,
        val normalizedTitle: String,
        val normalizedArtist: String,
        val normalizedAlbum: String,
        val title: String,
        val artist: String,
        val album: String,
        val groupId: String?,
        val fileName: String,
        val lines: Int,
        val createdAt: Long,
        val fingerprint: String? = null
    )

    companion object {
        const val INDEX_FILE_NAME = "QrcParsedCacheIndex.json"
        const val INDEX_VERSION = 1
        private const val WRITE_DEBOUNCE_MS = 500L
    }
}
