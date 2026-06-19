package com.example.playeragent.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashMap

class QrcLyricCacheManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private val aliasCacheManager = QrcAliasCacheManager(
        context = appContext,
        logger = logger
    )
    private val memoryCache =
        object : LinkedHashMap<String, ParsedLyric>(MAX_MEMORY_CACHE, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ParsedLyric>?
            ): Boolean {
                return size > MAX_MEMORY_CACHE
            }
        }
    private var indexEntries: List<CacheIndexEntry> = emptyList()
    private var indexBuiltAt: Long = 0L
    private var indexFileCount: Int = -1

    @Synchronized
    fun get(title: String, artist: String, album: String): ParsedLyric? {
        sharedQueryCount += 1
        val songKey = QrcLyricUtils.buildSongKey(title, artist, album)
        memoryCache[songKey]?.let {
            sharedStats.l1Hit += 1
            sharedStats.lastSource = "L1"
            maybeLogStats()
            logger("[QrcCache] L1 hit songKey=$songKey")
            return it
        }

        val aliasTarget = aliasCacheManager.getAlias(songKey)
        if (!aliasTarget.isNullOrBlank()) {
            memoryCache[aliasTarget]?.let { aliased ->
                val copy = aliased.copy(songKey = songKey)
                memoryCache[songKey] = copy
                sharedStats.aliasHit += 1
                sharedStats.lastSource = "ALIAS"
                maybeLogStats()
                logger("[QrcCache] L1 hit songKey=$aliasTarget")
                return copy
            }
            val aliasedDisk = readBySongKey(aliasTarget)
            if (aliasedDisk != null) {
                val copy = aliasedDisk.copy(songKey = songKey)
                memoryCache[songKey] = copy
                sharedStats.aliasHit += 1
                sharedStats.lastSource = "ALIAS"
                maybeLogStats()
                logger("[QrcCache] L2 hit songKey=$aliasTarget")
                return copy
            }
            aliasCacheManager.removeAlias(songKey)
        }

        val disk = readBySongKey(songKey)
        if (disk != null) {
            memoryCache[songKey] = disk
            sharedStats.l2Hit += 1
            sharedStats.lastSource = "L2"
            maybeLogStats()
            logger("[QrcCache] L2 hit songKey=$songKey")
            return disk
        }

        val fuzzy = findFuzzy(title, artist, album, songKey)
        if (fuzzy != null) {
            val alias = fuzzy.parsed.copy(
                songKey = songKey,
                title = title,
                artist = artist,
                album = album
            )
            memoryCache[songKey] = alias
            aliasCacheManager.saveAlias(songKey, fuzzy.parsed.songKey)
            sharedStats.l2FuzzyHit += 1
            sharedStats.aliasSaved += 1
            sharedStats.lastSource = "FUZZY"
            maybeLogStats()
            logger("[QrcCache] L2 fuzzy hit currentSongKey=$songKey")
            logger("[QrcCache] matched cachedSongKey=${fuzzy.parsed.songKey}")
            logger("[QrcCache] score=${fuzzy.score}")
            logger(
                "[QrcCache] title=${fuzzy.parsed.title} " +
                    "artist=${fuzzy.parsed.artist} album=${fuzzy.parsed.album}"
            )
            logger("[QrcCache] lines=${fuzzy.parsed.lines.size}")
            return alias
        }

        logger("[QrcCache] miss songKey=$songKey")
        sharedStats.lastSource = "NONE"
        maybeLogStats()
        return null
    }

    @Synchronized
    fun readBySongKey(songKey: String): ParsedLyric? {
        val file = songCacheFile(songKey)
        if (!file.exists()) {
            return null
        }
        return readCacheFile(file, expectedSongKey = songKey)
    }

    @Synchronized
    fun readByGroupId(groupId: String): ParsedLyric? {
        val file = groupCacheFile(groupId)
        if (!file.exists()) {
            return null
        }
        return readCacheFile(file, expectedSongKey = null)
    }

    @Synchronized
    fun save(parsed: ParsedLyric) {
        saveToDirectory(parsed, cacheRoot(), updateMemory = true)
    }

    @Synchronized
    fun saveToDirectory(
        parsed: ParsedLyric,
        directory: File,
        updateMemory: Boolean = false
    ) {
        if (parsed.lines.isEmpty()) {
            return
        }
        directory.mkdirs()
        val existing = readBySongKeyFromDirectory(parsed.songKey, directory)
        if (existing != null && shouldKeepExisting(existing, parsed)) {
            logger("[QrcPrebuild] duplicate songKey=${parsed.songKey} keep=existing")
            return
        }

        val objectValue = toJson(parsed)
        val text = objectValue.toString()
        val songFile = songCacheFile(parsed.songKey, directory)
        val groupFile = groupCacheFile(parsed.groupId, directory)
        writeAtomic(songFile, text)
        writeAtomic(groupFile, text)
        if (updateMemory) {
            memoryCache[parsed.songKey] = parsed
            indexEntries = emptyList()
            indexFileCount = -1
        }
        logger("[QrcCache] saved songKey=${parsed.songKey} lines=${parsed.lines.size}")
    }

    @Synchronized
    fun clearMemory() {
        memoryCache.clear()
        indexEntries = emptyList()
        indexBuiltAt = 0L
        indexFileCount = -1
    }

    @Synchronized
    fun saveAlias(sourceSongKey: String, targetSongKey: String) {
        aliasCacheManager.saveAlias(sourceSongKey, targetSongKey)
        sharedStats.aliasSaved += 1
        maybeLogStats()
    }

    @Synchronized
    fun getStats(): LyricCacheStats {
        return sharedStats.toImmutable()
    }

    @Synchronized
    fun resetStats() {
        sharedStats = MutableLyricCacheStats()
        sharedQueryCount = 0L
    }

    @Synchronized
    fun recordNegativeHit() {
        sharedStats.negativeHit += 1
        sharedStats.lastSource = "NEGATIVE"
        maybeLogStats()
    }

    @Synchronized
    fun recordNegativeSaved() {
        sharedStats.negativeSaved += 1
        maybeLogStats()
    }

    @Synchronized
    fun recordQrcDecrypt(success: Boolean) {
        sharedStats.qrcDecryptCount += 1
        if (success) {
            sharedStats.qrcDecryptSuccess += 1
            sharedStats.lastSource = "QRC"
        } else {
            sharedStats.qrcDecryptFailed += 1
        }
        maybeLogStats()
    }

    fun isGroupCacheValid(group: QrcFileGroup): Boolean {
        val cached = readByGroupId(group.groupId) ?: return false
        val qrc = group.qrcFile ?: return cached.lines.isNotEmpty()
        return cached.qrcLastModified == qrc.lastModified() &&
            cached.lines.isNotEmpty() &&
            cached.schemaVersion >= QRC_CACHE_SCHEMA_V2
    }

    fun cacheRoot(): File {
        return QrcLyricUtils.cacheDirectory(appContext)
    }

    fun buildingCacheRoot(): File {
        return File(appContext.getExternalFilesDir(null), "QrcLyricCacheV2_building")
    }

    fun backupCacheRoot(): File {
        return File(appContext.getExternalFilesDir(null), "QrcLyricCacheV1_backup")
    }

    fun readBySongKeyFromDirectory(songKey: String, directory: File): ParsedLyric? {
        val file = songCacheFile(songKey, directory)
        if (!file.exists()) {
            return null
        }
        return readCacheFileDetailed(
            file = file,
            expectedSongKey = songKey,
            allowStale = true
        ).parsed
    }

    private fun readCacheFile(
        file: File,
        expectedSongKey: String?
    ): ParsedLyric? {
        val result = readCacheFileDetailed(
            file = file,
            expectedSongKey = expectedSongKey,
            allowStale = false
        )
        if (result.parsed == null) {
            logger("[QrcCache] invalid songKey=${expectedSongKey.orEmpty()}")
        }
        return result.parsed
    }

    private fun findFuzzy(
        title: String,
        artist: String,
        album: String,
        currentSongKey: String
    ): FuzzyMatch? {
        val currentTitle = QrcLyricUtils.normalizeForMatch(title)
        if (currentTitle.length < MIN_FUZZY_TITLE_LENGTH ||
            GENERIC_TITLES.contains(currentTitle)
        ) {
            logger("[QrcCache] fuzzy rejected reason=bad title")
            return null
        }
        val currentArtistTokens = QrcLyricUtils.splitArtists(artist)
        val currentAlbum = QrcLyricUtils.normalizeForMatch(album)
        val entries = ensureIndex()
        val candidates = entries.mapNotNull { entry ->
            scoreEntry(
                entry = entry,
                currentTitle = currentTitle,
                currentArtistTokens = currentArtistTokens,
                currentAlbum = currentAlbum
            )?.let { score -> entry to score }
        }.sortedWith(
            compareByDescending<Pair<CacheIndexEntry, Int>> { it.second }
                .thenByDescending { it.first.linesCount }
                .thenByDescending { it.first.createdAt }
        )

        val best = candidates.firstOrNull()
        if (best == null) {
            logger("[QrcCache] fuzzy rejected reason=no candidate")
            return null
        }
        val second = candidates.getOrNull(1)
        if (second != null && best.second - second.second < MIN_SCORE_GAP) {
            logger("[QrcCache] fuzzy rejected reason=ambiguous")
            return null
        }
        if (best.second < MIN_FUZZY_SCORE) {
            logger("[QrcCache] fuzzy rejected reason=low score")
            return null
        }

        val artistMatched = currentArtistTokens.isEmpty() ||
            artistTokenHitCount(currentArtistTokens, best.first.artistTokens) > 0
        val titleExact = best.first.normalizedTitle == currentTitle
        if (!artistMatched && !(candidates.size == 1 && titleExact)) {
            logger("[QrcCache] fuzzy rejected reason=artist mismatch")
            return null
        }
        if (!artistMatched) {
            logger("[QrcCache] fuzzy warning artist not matched currentSongKey=$currentSongKey")
        }

        val readResult = readCacheFileDetailed(
            file = best.first.file,
            expectedSongKey = null,
            allowStale = true
        )
        val parsed = readResult.parsed ?: run {
            logFuzzyRejected(
                reason = readResult.rejectReason ?: "unknown exception",
                file = best.first.file,
                cachedSongKey = best.first.songKey,
                exceptionSummary = readResult.exceptionSummary
            )
            return null
        }
        if (parsed.lines.isEmpty()) {
            logFuzzyRejected("lines empty", best.first.file, best.first.songKey)
            return null
        }
        val parsedTitle = QrcLyricUtils.normalizeForMatch(parsed.title)
        val titleMatches = parsedTitle.isNotBlank() &&
            (parsedTitle == currentTitle ||
                parsedTitle.contains(currentTitle) ||
                currentTitle.contains(parsedTitle))
        if (!titleMatches) {
            logFuzzyRejected("title mismatch after read", best.first.file, parsed.songKey)
            return null
        }
        val parsedArtistTokens = QrcLyricUtils.splitArtists(parsed.artist)
        val parsedArtistMatched = currentArtistTokens.isEmpty() ||
            artistTokenHitCount(currentArtistTokens, parsedArtistTokens) > 0
        if (!parsedArtistMatched && !(candidates.size == 1 && titleExact)) {
            logFuzzyRejected("artist mismatch after read", best.first.file, parsed.songKey)
            return null
        }
        if (readResult.staleReason != null) {
            logger(
                "[QrcCache] fuzzy stale hit reason=${readResult.staleReason} " +
                    "currentSongKey=$currentSongKey matched=${parsed.songKey}"
            )
        }
        return FuzzyMatch(parsed, best.second)
    }

    private fun readCacheFileDetailed(
        file: File,
        expectedSongKey: String?,
        allowStale: Boolean
    ): CacheReadResult {
        if (!file.exists()) {
            return CacheReadResult(rejectReason = "cache file not exists")
        }
        return try {
            val objectValue = JSONObject(file.readText(Charsets.UTF_8))
            val schemaVersion = readSchemaVersion(objectValue)
            if (schemaVersion !in QRC_CACHE_SCHEMA_V1..QRC_CACHE_SCHEMA_V2) {
                return CacheReadResult(rejectReason = "version mismatch")
            }
            val songKey = objectValue.optString("songKey")
            if (expectedSongKey != null && songKey != expectedSongKey) {
                return CacheReadResult(rejectReason = "songKey mismatch")
            }
            val groupId = objectValue.optString("groupId")
            val qrcLastModified = objectValue.optLong("qrcLastModified")
            val lines = readLines(objectValue.optJSONArray("lines") ?: JSONArray())
            if (lines.isEmpty()) {
                return CacheReadResult(rejectReason = "lines empty")
            }

            val source = if (groupId.isBlank()) {
                null
            } else {
                File(QrcLyricUtils.qrcDirectory(), "$groupId.qrc")
            }
            val staleReason = when {
                source == null -> "source qrc path missing"
                !source.exists() -> "source qrc missing"
                source.lastModified() != qrcLastModified -> "qrcLastModified mismatch"
                else -> null
            }
            if (staleReason != null && !allowStale && staleReason != "source qrc missing") {
                return CacheReadResult(rejectReason = staleReason)
            }
            if (staleReason == "source qrc missing") {
                logger("[QrcCache] source qrc missing, use stale cache")
            }

            CacheReadResult(
                parsed = ParsedLyric(
                    songKey = songKey,
                    title = objectValue.optString("title"),
                    artist = objectValue.optString("artist"),
                    album = objectValue.optString("album"),
                    groupId = groupId,
                    qrcLastModified = qrcLastModified,
                    lines = lines,
                    schemaVersion = schemaVersion,
                    qrcPath = objectValue.optString("qrcPath"),
                    wordTimingStatus = QrcWordTimingStatus.fromValue(
                        objectValue.optString(
                            "wordTimingStatus",
                            QrcWordTimingStatus.fromLines(lines).name
                        )
                    )
                ),
                staleReason = staleReason
            )
        } catch (exception: org.json.JSONException) {
            CacheReadResult(
                rejectReason = "json parse failed",
                exceptionSummary = "${exception::class.java.simpleName}: ${exception.message.orEmpty()}"
            )
        } catch (exception: Exception) {
            CacheReadResult(
                rejectReason = "unknown exception",
                exceptionSummary = "${exception::class.java.simpleName}: ${exception.message.orEmpty()}"
            )
        }
    }

    private fun readLines(linesArray: JSONArray): List<QrcLyricLine> {
        return (0 until linesArray.length()).mapNotNull { index ->
            val lineObject = linesArray.optJSONObject(index) ?: return@mapNotNull null
            val text = lineObject.optString("text")
            if (text.isBlank()) {
                null
            } else {
                QrcLyricLine(
                    timeMs = lineObject.optLong("timeMs"),
                    text = text,
                    durationMs = lineObject.optLong("durationMs"),
                    words = readWords(lineObject.optJSONArray("words") ?: JSONArray())
                )
            }
        }.sortedBy(QrcLyricLine::timeMs)
    }

    private fun readWords(wordsArray: JSONArray): List<QrcLyricWord> {
        return (0 until wordsArray.length()).mapNotNull { index ->
            val wordObject = wordsArray.optJSONObject(index) ?: return@mapNotNull null
            val text = wordObject.optString("text")
            if (text.isBlank()) {
                null
            } else {
                QrcLyricWord(
                    startMs = wordObject.optLong("startMs"),
                    durationMs = wordObject.optLong("durationMs"),
                    text = text
                )
            }
        }
    }

    private fun logFuzzyRejected(
        reason: String,
        file: File,
        cachedSongKey: String,
        exceptionSummary: String? = null
    ) {
        val suffix = if (exceptionSummary.isNullOrBlank()) {
            ""
        } else {
            " exception=$exceptionSummary"
        }
        logger(
            "[QrcCache] fuzzy rejected reason=$reason " +
                "file=${file.name} cachedSongKey=$cachedSongKey$suffix"
        )
    }

    private fun scoreEntry(
        entry: CacheIndexEntry,
        currentTitle: String,
        currentArtistTokens: Set<String>,
        currentAlbum: String
    ): Int? {
        if (entry.linesCount <= 0) {
            return null
        }
        val titleExact = entry.normalizedTitle == currentTitle
        val titleContains = !titleExact &&
            (entry.normalizedTitle.contains(currentTitle) ||
                currentTitle.contains(entry.normalizedTitle))
        if (!titleExact && !titleContains) {
            return null
        }

        var score = if (titleExact) 100 else 80
        val artistHits = artistTokenHitCount(currentArtistTokens, entry.artistTokens)
        if (artistHits >= 2) {
            score += 60
        } else if (artistHits == 1) {
            score += 30
        }
        if (currentAlbum.isNotBlank() && entry.normalizedAlbum.isNotBlank()) {
            score += when {
                entry.normalizedAlbum == currentAlbum -> 20
                entry.normalizedAlbum.contains(currentAlbum) ||
                    currentAlbum.contains(entry.normalizedAlbum) -> 10
                else -> 0
            }
        }
        score += 10
        score += recencyScore(entry.createdAt)
        return score
    }

    private fun artistTokenHitCount(
        currentTokens: Set<String>,
        entryTokens: Set<String>
    ): Int {
        if (currentTokens.isEmpty() || entryTokens.isEmpty()) {
            return 0
        }
        return currentTokens.count { current ->
            entryTokens.any { entry ->
                entry == current || entry.contains(current) || current.contains(entry)
            }
        }
    }

    private fun recencyScore(createdAt: Long): Int {
        val ageMs = System.currentTimeMillis() - createdAt
        return when {
            ageMs < 24L * 60L * 60L * 1000L -> 5
            ageMs < 7L * 24L * 60L * 60L * 1000L -> 3
            createdAt > 0L -> 1
            else -> 0
        }
    }

    private fun ensureIndex(): List<CacheIndexEntry> {
        val files = cacheRoot().listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
        }.orEmpty()
        val now = System.currentTimeMillis()
        if (indexEntries.isNotEmpty() &&
            now - indexBuiltAt < INDEX_TTL_MS &&
            files.size == indexFileCount
        ) {
            return indexEntries
        }

        val deduped = linkedMapOf<String, CacheIndexEntry>()
        files.forEach { file ->
            val entry = readIndexEntry(file) ?: return@forEach
            val existing = deduped[entry.songKey]
            if (existing == null ||
                entry.linesCount > existing.linesCount ||
                (entry.linesCount == existing.linesCount &&
                    entry.createdAt > existing.createdAt)
            ) {
                deduped[entry.songKey] = entry
            }
        }
        indexEntries = deduped.values.toList()
        indexBuiltAt = now
        indexFileCount = files.size
        logger("[QrcCache] fuzzy index built entries=${indexEntries.size} files=${files.size}")
        return indexEntries
    }

    private fun readIndexEntry(file: File): CacheIndexEntry? {
        return try {
            val objectValue = JSONObject(file.readText(Charsets.UTF_8))
            if (readSchemaVersion(objectValue) !in QRC_CACHE_SCHEMA_V1..QRC_CACHE_SCHEMA_V2) {
                return null
            }
            val songKey = objectValue.optString("songKey")
            val title = objectValue.optString("title")
            val artist = objectValue.optString("artist")
            val album = objectValue.optString("album")
            val linesCount = objectValue.optJSONArray("lines")?.length() ?: 0
            if (songKey.isBlank() || title.isBlank() || linesCount <= 0) {
                return null
            }
            val groupIdValue = objectValue.optString("groupId")
            CacheIndexEntry(
                songKey = songKey,
                normalizedTitle = QrcLyricUtils.normalizeForMatch(title),
                normalizedArtist = QrcLyricUtils.normalizeForMatch(artist),
                normalizedAlbum = QrcLyricUtils.normalizeForMatch(album),
                artistTokens = QrcLyricUtils.splitArtists(artist),
                title = title,
                artist = artist,
                album = album,
                file = file,
                linesCount = linesCount,
                createdAt = objectValue.optLong("createdAt"),
                groupId = if (groupIdValue.isBlank()) null else groupIdValue
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun shouldKeepExisting(
        existing: ParsedLyric,
        candidate: ParsedLyric
    ): Boolean {
        val existingWordCount = existing.lines.sumOf { it.words.size }
        val candidateWordCount = candidate.lines.sumOf { it.words.size }
        if (candidateWordCount > existingWordCount) {
            return false
        }
        return existing.lines.size > candidate.lines.size ||
            (existing.lines.size == candidate.lines.size &&
                existing.qrcLastModified >= candidate.qrcLastModified)
    }

    private fun songCacheFile(songKey: String): File {
        return songCacheFile(songKey, cacheRoot())
    }

    private fun groupCacheFile(groupId: String): File {
        return groupCacheFile(groupId, cacheRoot())
    }

    private fun songCacheFile(songKey: String, directory: File): File {
        return File(directory, "${QrcLyricUtils.cacheKey(songKey)}.json")
    }

    private fun groupCacheFile(groupId: String, directory: File): File {
        return File(directory, "group_$groupId.json")
    }

    private fun toJson(parsed: ParsedLyric): JSONObject {
        return JSONObject()
            .put("schemaVersion", QRC_CACHE_SCHEMA_V2)
            .put("version", QRC_CACHE_SCHEMA_V2)
            .put("songKey", parsed.songKey)
            .put("title", parsed.title)
            .put("artist", parsed.artist)
            .put("album", parsed.album)
            .put("groupId", parsed.groupId)
            .put("qrcPath", parsed.qrcPath)
            .put("qrcLastModified", parsed.qrcLastModified)
            .put("createdAt", System.currentTimeMillis())
            .put("wordTimingStatus", parsed.wordTimingStatus.name)
            .put("lines", JSONArray().also { array ->
                parsed.lines
                    .filter { it.text.isNotBlank() }
                    .sortedBy(QrcLyricLine::timeMs)
                    .forEach { line ->
                        array.put(
                            JSONObject()
                                .put("timeMs", line.timeMs)
                                .put("text", line.text)
                                .put("durationMs", line.durationMs)
                                .also { lineObject ->
                                    if (line.words.isNotEmpty()) {
                                        lineObject.put(
                                            "words",
                                            JSONArray().also { wordsArray ->
                                                line.words
                                                    .filter { it.text.isNotBlank() }
                                                    .sortedBy(QrcLyricWord::startMs)
                                                    .forEach { word ->
                                                        wordsArray.put(
                                                            JSONObject()
                                                                .put("startMs", word.startMs)
                                                                .put("durationMs", word.durationMs)
                                                                .put("text", word.text)
                                                        )
                                                    }
                                            }
                                        )
                                    }
                                }
                        )
                    }
            })
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { output ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            output.write(bytes)
            output.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("failed to delete old cache file ${file.name}")
        }
        if (!temp.renameTo(file)) {
            throw IllegalStateException("failed to rename cache file ${file.name}")
        }
    }

    private fun readSchemaVersion(objectValue: JSONObject): Int {
        val schema = objectValue.optInt("schemaVersion", 0)
        return if (schema > 0) schema else objectValue.optInt("version")
    }

    companion object {
        private const val MAX_MEMORY_CACHE = 120
        private const val INDEX_TTL_MS = 5L * 60L * 1000L
        private const val MIN_FUZZY_TITLE_LENGTH = 2
        private const val MIN_FUZZY_SCORE = 120
        private const val MIN_SCORE_GAP = 20
        private const val STATS_LOG_INTERVAL = 50L
        private var sharedStats = MutableLyricCacheStats()
        private var sharedQueryCount = 0L
        private val GENERIC_TITLES = setOf(
            "intro",
            "outro",
            "interlude",
            "remix",
            "live"
        )
    }

    private data class FuzzyMatch(
        val parsed: ParsedLyric,
        val score: Int
    )

    private data class CacheReadResult(
        val parsed: ParsedLyric? = null,
        val rejectReason: String? = null,
        val staleReason: String? = null,
        val exceptionSummary: String? = null
    )

    data class CacheIndexEntry(
        val songKey: String,
        val normalizedTitle: String,
        val normalizedArtist: String,
        val normalizedAlbum: String,
        val artistTokens: Set<String>,
        val title: String,
        val artist: String,
        val album: String,
        val file: File,
        val linesCount: Int,
        val createdAt: Long,
        val groupId: String?
    )

    private fun maybeLogStats() {
        if (sharedQueryCount > 0L &&
            sharedQueryCount % STATS_LOG_INTERVAL == 0L
        ) {
            logger(
                "[LyricStats] l1=${sharedStats.l1Hit} " +
                    "l2=${sharedStats.l2Hit} " +
                    "fuzzy=${sharedStats.l2FuzzyHit} " +
                    "alias=${sharedStats.aliasHit} " +
                    "negative=${sharedStats.negativeHit} " +
                    "qrc=${sharedStats.qrcDecryptCount} " +
                    "success=${sharedStats.qrcDecryptSuccess} " +
                    "failed=${sharedStats.qrcDecryptFailed}"
            )
        }
    }

    private data class MutableLyricCacheStats(
        var l1Hit: Long = 0,
        var l2Hit: Long = 0,
        var l2FuzzyHit: Long = 0,
        var aliasHit: Long = 0,
        var negativeHit: Long = 0,
        var qrcDecryptCount: Long = 0,
        var qrcDecryptSuccess: Long = 0,
        var qrcDecryptFailed: Long = 0,
        var negativeSaved: Long = 0,
        var aliasSaved: Long = 0,
        var lastSource: String = "NONE"
    ) {
        fun toImmutable(): LyricCacheStats {
            return LyricCacheStats(
                l1Hit = l1Hit,
                l2Hit = l2Hit,
                l2FuzzyHit = l2FuzzyHit,
                aliasHit = aliasHit,
                negativeHit = negativeHit,
                qrcDecryptCount = qrcDecryptCount,
                qrcDecryptSuccess = qrcDecryptSuccess,
                qrcDecryptFailed = qrcDecryptFailed,
                negativeSaved = negativeSaved,
                aliasSaved = aliasSaved,
                lastSource = lastSource
            )
        }
    }
}
