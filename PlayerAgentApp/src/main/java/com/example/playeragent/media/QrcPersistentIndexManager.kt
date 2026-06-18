package com.example.playeragent.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrcPersistentIndexManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private val cacheDirectory = QrcLyricUtils.cacheDirectory(appContext)
    private val indexFile = File(cacheDirectory, INDEX_FILE_NAME)
    private val directory = QrcLyricUtils.qrcDirectory()
    private val rebuildExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "QrcPersistentIndexThread").apply {
                priority = Thread.MIN_PRIORITY
            }
        }
    private val rebuilding = AtomicBoolean(false)
    private val lock = Any()
    private var entries: List<QrcGroupIndexEntry> = emptyList()
    private var metadata: IndexMetadata? = null
    private var dirty = false

    fun getIndex(forceRefresh: Boolean = false): List<QrcGroupIndexEntry> {
        val snapshot = directorySnapshot()
        synchronized(lock) {
            if (!forceRefresh &&
                !isDirtyLocked() &&
                entries.isNotEmpty() &&
                metadata?.matches(snapshot) == true
            ) {
                logger("[QrcIndex] cache hit entries=${entries.size}")
                return entries
            }
        }

        val startedAt = System.currentTimeMillis()
        val loaded = if (!forceRefresh && !isDirty()) loadFromDisk(snapshot) else null
        if (loaded != null) {
            synchronized(lock) {
                entries = loaded.entries
                metadata = loaded.metadata
                clearDirtyLocked()
            }
            logger(
                "[QrcIndex] loaded entries=${loaded.entries.size} " +
                    "costMs=${System.currentTimeMillis() - startedAt}"
            )
            return loaded.entries
        }

        val reason = when {
            forceRefresh -> "force refresh"
            isDirty() -> "dirty"
            !indexFile.exists() -> "index missing"
            !directory.exists() -> "qrc directory missing"
            else -> "snapshot changed"
        }
        logger("[QrcIndex] rebuild required reason=$reason")
        return rebuild(snapshot)
    }

    fun markDirty(groupId: String? = null) {
        synchronized(lock) {
            dirty = true
            globalDirty.set(true)
        }
        logger("[QrcIndex] marked dirty groupId=${groupId.orEmpty()}")
    }

    fun rebuildAsync() {
        if (!rebuilding.compareAndSet(false, true)) {
            return
        }
        rebuildExecutor.execute {
            try {
                getIndex(forceRefresh = true)
            } finally {
                rebuilding.set(false)
            }
        }
    }

    fun status(): QrcPersistentIndexStatus {
        synchronized(lock) {
            if (entries.isEmpty() && indexFile.exists()) {
                val summary = readIndexSummary()
                if (summary != null) {
                    return QrcPersistentIndexStatus(
                        loaded = true,
                        dirty = isDirtyLocked(),
                        entries = summary.entries,
                        builtAt = summary.builtAt
                    )
                }
            }
            return QrcPersistentIndexStatus(
                loaded = entries.isNotEmpty(),
                dirty = isDirtyLocked(),
                entries = entries.size,
                builtAt = metadata?.builtAt ?: 0L
            )
        }
    }

    private fun readIndexSummary(): IndexSummary? {
        return try {
            val objectValue = JSONObject(indexFile.readText(Charsets.UTF_8))
            if (objectValue.optInt("version") != INDEX_VERSION) {
                return null
            }
            IndexSummary(
                entries = objectValue.optJSONArray("entries")?.length() ?: 0,
                builtAt = objectValue.optLong("builtAt")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun loadFromDisk(snapshot: DirectorySnapshot): LoadedIndex? {
        if (!indexFile.exists()) {
            return null
        }
        if (!directory.exists()) {
            logger("[QrcIndex] rebuild required reason=qrc directory missing")
            return null
        }
        return try {
            val objectValue = JSONObject(indexFile.readText(Charsets.UTF_8))
            if (objectValue.optInt("version") != INDEX_VERSION) {
                logger("[QrcIndex] rebuild required reason=version mismatch")
                return null
            }
            val fileCount = objectValue.optInt("fileCount")
            val latestFileModified = objectValue.optLong("latestFileModified")
            if (fileCount != snapshot.fileCount) {
                logger(
                    "[QrcIndex] rebuild required reason=fileCount changed " +
                        "old=$fileCount new=${snapshot.fileCount}"
                )
                return null
            }
            if (latestFileModified != snapshot.latestFileModified) {
                logger(
                    "[QrcIndex] rebuild required reason=latestFileModified changed"
                )
                return null
            }
            val entriesArray = objectValue.optJSONArray("entries") ?: JSONArray()
            val parsedEntries = (0 until entriesArray.length()).mapNotNull { index ->
                entriesArray.optJSONObject(index)?.toEntry()
            }
            LoadedIndex(
                metadata = IndexMetadata(
                    dirPath = objectValue.optString("dirPath"),
                    dirLastModified = objectValue.optLong("dirLastModified"),
                    latestFileModified = latestFileModified,
                    fileCount = fileCount,
                    builtAt = objectValue.optLong("builtAt")
                ),
                entries = parsedEntries
            )
        } catch (exception: Exception) {
            logger("[QrcIndex] rebuild required reason=read failed ${exception.message}")
            null
        }
    }

    private fun rebuild(snapshot: DirectorySnapshot): List<QrcGroupIndexEntry> {
        val startedAt = System.currentTimeMillis()
        logger("[QrcIndex] rebuild start")
        val files = try {
            directory.listFiles()?.filter(File::isFile).orEmpty()
        } catch (exception: Exception) {
            logger("[QrcIndex] rebuild failed error=${exception.message}")
            emptyList()
        }
        var normalizedTextBytes = 0
        var producerLimitLogged = false
        val builders = linkedMapOf<String, QrcGroupBuilder>()
        files.forEach { file ->
            val match = GROUP_FILE_REGEX.matchEntire(file.name) ?: return@forEach
            val groupId = match.groupValues[1]
            val suffix = match.groupValues[2].lowercase(Locale.ROOT)
            builders.getOrPut(groupId) { QrcGroupBuilder(groupId) }
                .add(suffix, file)
        }
        val builtEntries = builders.values
            .map(QrcGroupBuilder::build)
            .filter { it.qrcFile != null }
            .map { group ->
                val rawProducerText = if (normalizedTextBytes < MAX_TOTAL_PRODUCER_TEXT_BYTES) {
                    readProducerText(group.producerFile)
                } else {
                    if (!producerLimitLogged) {
                        producerLimitLogged = true
                        logger("[QrcIndex] producer text cache limit reached")
                    }
                    ""
                }
                val normalizedProducerText =
                    QrcLyricUtils.normalizeForMatch(rawProducerText)
                normalizedTextBytes += normalizedProducerText
                    .toByteArray(Charsets.UTF_8)
                    .size
                val metadata = QrcLyricUtils.parseProducerMetadata(group.producerFile)
                QrcGroupIndexEntry(
                    groupId = group.groupId,
                    qrcFile = group.qrcFile,
                    producerFile = group.producerFile,
                    exFile = group.exFile,
                    translrcFile = group.translrcFile,
                    romaqrcFile = group.romaqrcFile,
                    lastModified = group.lastModified,
                    title = metadata.first,
                    artist = metadata.second,
                    album = metadata.third,
                    normalizedTitle = QrcLyricUtils.normalizeForMatch(metadata.first),
                    normalizedArtist = QrcLyricUtils.normalizeForMatch(metadata.second),
                    normalizedAlbum = QrcLyricUtils.normalizeForMatch(metadata.third),
                    normalizedProducerText = normalizedProducerText,
                    producerTextLoaded = rawProducerText.isNotBlank(),
                    hasQrc = group.qrcFile != null,
                    hasProducer = group.producerFile != null,
                    hasTranslrc = group.translrcFile != null,
                    hasRomaqrc = group.romaqrcFile != null
                )
            }
        val metadata = IndexMetadata(
            dirPath = directory.absolutePath,
            dirLastModified = snapshot.dirLastModified,
            latestFileModified = snapshot.latestFileModified,
            fileCount = snapshot.fileCount,
            builtAt = System.currentTimeMillis()
        )
        synchronized(lock) {
            entries = builtEntries
            this.metadata = metadata
            clearDirtyLocked()
        }
        saveIndex(metadata, builtEntries)
        logger(
            "[QrcIndex] saved entries=${builtEntries.size} " +
                "costMs=${System.currentTimeMillis() - startedAt}"
        )
        return builtEntries
    }

    private fun saveIndex(
        metadata: IndexMetadata,
        entries: List<QrcGroupIndexEntry>
    ) {
        try {
            val objectValue = JSONObject()
                .put("version", INDEX_VERSION)
                .put("dirPath", metadata.dirPath)
                .put("dirLastModified", metadata.dirLastModified)
                .put("latestFileModified", metadata.latestFileModified)
                .put("fileCount", metadata.fileCount)
                .put("builtAt", metadata.builtAt)
                .put("entries", JSONArray().also { array ->
                    entries.forEach { entry ->
                        array.put(entry.toJson())
                    }
                })
            indexFile.writeText(objectValue.toString(), Charsets.UTF_8)
        } catch (exception: Exception) {
            logger("[QrcIndex] save failed error=${exception.message}")
        }
    }

    private fun directorySnapshot(): DirectorySnapshot {
        val files = try {
            directory.listFiles()?.filter(File::isFile).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return DirectorySnapshot(
            dirLastModified = directory.lastModified(),
            latestFileModified = files.maxOfOrNull(File::lastModified) ?: 0L,
            fileCount = files.size
        )
    }

    private fun readProducerText(file: File?): String {
        if (file == null || !file.canRead()) {
            return ""
        }
        return try {
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(MAX_PRODUCER_BYTES)
                val count = input.read(buffer)
                if (count <= 0) "" else String(buffer, 0, count, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun JSONObject.toEntry(): QrcGroupIndexEntry {
        val qrcPath = optString("qrcPath")
        val producerPath = optString("producerPath")
        val exPath = optString("exPath")
        val translrcPath = optString("translrcPath")
        val romaqrcPath = optString("romaqrcPath")
        return QrcGroupIndexEntry(
            groupId = optString("groupId"),
            qrcFile = qrcPath.toFileOrNull(),
            producerFile = producerPath.toFileOrNull(),
            exFile = exPath.toFileOrNull(),
            translrcFile = translrcPath.toFileOrNull(),
            romaqrcFile = romaqrcPath.toFileOrNull(),
            lastModified = optLong("lastModified"),
            title = optString("title"),
            artist = optString("artist"),
            album = optString("album"),
            normalizedTitle = optString("normalizedTitle"),
            normalizedArtist = optString("normalizedArtist"),
            normalizedAlbum = optString("normalizedAlbum"),
            normalizedProducerText = optString("normalizedProducerText"),
            producerTextLoaded = optBoolean("producerTextLoaded"),
            hasQrc = optBoolean("hasQrc"),
            hasProducer = optBoolean("hasProducer"),
            hasTranslrc = optBoolean("hasTranslrc"),
            hasRomaqrc = optBoolean("hasRomaqrc")
        )
    }

    private fun QrcGroupIndexEntry.toJson(): JSONObject {
        return JSONObject()
            .put("groupId", groupId)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("qrcPath", qrcFile?.absolutePath.orEmpty())
            .put("producerPath", producerFile?.absolutePath.orEmpty())
            .put("exPath", exFile?.absolutePath.orEmpty())
            .put("translrcPath", translrcFile?.absolutePath.orEmpty())
            .put("romaqrcPath", romaqrcFile?.absolutePath.orEmpty())
            .put("lastModified", lastModified)
            .put("hasQrc", hasQrc)
            .put("hasProducer", hasProducer)
            .put("hasTranslrc", hasTranslrc)
            .put("hasRomaqrc", hasRomaqrc)
            .put("normalizedTitle", normalizedTitle)
            .put("normalizedArtist", normalizedArtist)
            .put("normalizedAlbum", normalizedAlbum)
            .put("normalizedProducerText", normalizedProducerText)
            .put("producerTextLoaded", producerTextLoaded)
    }

    private fun String.toFileOrNull(): File? {
        return if (isBlank()) null else File(this)
    }

    private data class LoadedIndex(
        val metadata: IndexMetadata,
        val entries: List<QrcGroupIndexEntry>
    )

    private data class IndexMetadata(
        val dirPath: String,
        val dirLastModified: Long,
        val latestFileModified: Long,
        val fileCount: Int,
        val builtAt: Long
    ) {
        fun matches(snapshot: DirectorySnapshot): Boolean {
            return latestFileModified == snapshot.latestFileModified &&
                fileCount == snapshot.fileCount
        }
    }

    private data class DirectorySnapshot(
        val dirLastModified: Long,
        val latestFileModified: Long,
        val fileCount: Int
    )

    private data class IndexSummary(
        val entries: Int,
        val builtAt: Long
    )

    private class QrcGroupBuilder(
        private val groupId: String
    ) {
        private var qrcFile: File? = null
        private var producerFile: File? = null
        private var exFile: File? = null
        private var translrcFile: File? = null
        private var romaqrcFile: File? = null

        fun add(suffix: String, file: File) {
            when (suffix) {
                "qrc" -> qrcFile = file
                "producer" -> producerFile = file
                "ex" -> exFile = file
                "translrc" -> translrcFile = file
                "romaqrc" -> romaqrcFile = file
            }
        }

        fun build(): QrcFileGroup {
            val files = listOfNotNull(
                qrcFile,
                producerFile,
                exFile,
                translrcFile,
                romaqrcFile
            )
            return QrcFileGroup(
                groupId = groupId,
                qrcFile = qrcFile,
                producerFile = producerFile,
                exFile = exFile,
                translrcFile = translrcFile,
                romaqrcFile = romaqrcFile,
                lastModified = files.maxOfOrNull(File::lastModified) ?: 0L
            )
        }
    }

    companion object {
        private const val INDEX_VERSION = 1
        private const val INDEX_FILE_NAME = "QrcIndex.json"
        private const val MAX_PRODUCER_BYTES = 8 * 1024
        private const val MAX_TOTAL_PRODUCER_TEXT_BYTES = 3 * 1024 * 1024
        private val globalDirty = AtomicBoolean(false)
        private val GROUP_FILE_REGEX =
            Regex("""^(-?\d+)\.(qrc|producer|ex|translrc|romaqrc)$""")
    }

    private fun isDirty(): Boolean {
        synchronized(lock) {
            return isDirtyLocked()
        }
    }

    private fun isDirtyLocked(): Boolean {
        return dirty || globalDirty.get()
    }

    private fun clearDirtyLocked() {
        dirty = false
        globalDirty.set(false)
    }
}
