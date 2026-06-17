package com.example.playeragent.media

import android.content.Context
import android.os.Environment
import com.example.playeragent.logging.LogConfig
import io.github.proify.qrckit.decrypt.QrcDecrypter
import java.io.File
import java.util.Locale

class QrcLyricManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private val cacheManager = QrcLyricCacheManager(
        context = appContext,
        logger = logger
    )
    private val negativeCacheManager = QrcNegativeCacheManager(
        context = appContext,
        logger = logger
    )
    private var indexEntries: List<QrcGroupIndexEntry> = emptyList()
    private var indexBuiltAt = 0L
    private var indexDirectoryModifiedAt = 0L
    private var indexFileCount = 0
    private var cachedSongKey: String? = null
    private var cachedGroupId: String? = null
    private var cachedLines: List<LyricLine> = emptyList()
    private var lastLoggedLine: String? = null
    private val songGroupCache = mutableMapOf<String, String>()
    private val songLinesCache = mutableMapOf<String, List<LyricLine>>()
    private val parsedQrcCache = mutableMapOf<String, ParsedQrc?>()

    @Synchronized
    fun load(title: String, artist: String, album: String): Boolean {
        val normalizedTitle = normalizeForMatch(title)
        val normalizedArtist = normalizeForMatch(artist)
        val normalizedAlbum = normalizeForMatch(album)
        val songKey = buildSongKey(
            normalizedTitle,
            normalizedArtist,
            normalizedAlbum
        )
        if (songKey == cachedSongKey && cachedLines.isNotEmpty()) {
            return true
        }

        if (songKey != cachedSongKey) {
            clearCacheIfSongChanged(title, artist, album)
        }
        if (normalizedTitle.isBlank() && normalizedArtist.isBlank()) {
            return false
        }

        val startedAt = System.currentTimeMillis()
        logger("[QrcLyric] load title=$title artist=$artist album=$album")
        logger("[QrcLyric] findBestGroup start title=$title")

        cacheManager.get(title, artist, album)?.let { cached ->
            cachedLines = cached.lines.map {
                LyricLine(timeMs = it.timeMs, text = it.text)
            }
            cachedGroupId = cached.groupId
            logger(
                "[QrcLyric] parsed lines=${cachedLines.size} " +
                    "totalCostMs=${System.currentTimeMillis() - startedAt}"
            )
            return cachedLines.isNotEmpty()
        }

        songLinesCache[songKey]?.let { lines ->
            cachedLines = lines
            cachedGroupId = songGroupCache[songKey]
            logger("[QrcLyric] cache hit songKey=$songKey")
            logger(
                "[QrcLyric] parsed lines=${lines.size} " +
                    "totalCostMs=${System.currentTimeMillis() - startedAt}"
            )
            return lines.isNotEmpty()
        }

        if (negativeCacheManager.isNegative(songKey)) {
            cacheManager.recordNegativeHit()
            logger("[QrcLyric] best group=none score=0")
            return false
        }

        val entries = getIndexEntries(forceRefresh = false)
        logger("[QrcLyric] scan groups count=${entries.size}")
        if (entries.isEmpty()) {
            logger("[QrcLyric] best group=none score=0")
            saveNegative(songKey)
            return false
        }

        songGroupCache[songKey]?.let { groupId ->
            entries.firstOrNull { it.groupId == groupId }?.let { entry ->
                val parsed = decryptAndParseQrc(entry)
                if (parsed != null &&
                    parsedMatchesTitle(parsed, normalizedTitle)
                ) {
                    applyParsedResult(songKey, entry, parsed)
                    logger("[QrcLyric] cache hit songKey=$songKey")
                    logger(
                        "[QrcLyric] parsed lines=${cachedLines.size} " +
                            "totalCostMs=${System.currentTimeMillis() - startedAt}"
                    )
                    return cachedLines.isNotEmpty()
                }
            }
        }

        val best = findBestGroup(
            entries = entries,
            normalizedTitle = normalizedTitle,
            normalizedArtist = normalizedArtist,
            normalizedAlbum = normalizedAlbum
        )
        if (best == null || best.score < MIN_MATCH_SCORE) {
            logger(
                "[QrcLyric] best group=${best?.entry?.groupId ?: "none"} " +
                    "score=${best?.score ?: 0}"
            )
            saveNegative(songKey)
            return false
        }

        val parsed = best.parsed ?: decryptAndParseQrc(best.entry)
        if (parsed == null || !parsedMatchesTitle(parsed, normalizedTitle)) {
            logger("[QrcLyric] best group=none score=0")
            saveNegative(songKey)
            return false
        }

        applyParsedResult(songKey, best.entry, parsed)
        negativeCacheManager.removeNegative(songKey)
        logger(
            "[QrcLyric] best group=${best.entry.groupId} score=${best.score}"
        )
        logger("[QrcLyric] decrypted lines=${cachedLines.size}")
        cachedLines.firstOrNull()?.let {
            logger("[QrcLyric] first line=${it.text}")
        }
        logger(
            "[QrcLyric] parsed lines=${cachedLines.size} " +
                "totalCostMs=${System.currentTimeMillis() - startedAt}"
        )
        return cachedLines.isNotEmpty()
    }

    @Synchronized
    fun getCurrentLine(positionMs: Long): String {
        if (cachedLines.isEmpty()) {
            return ""
        }

        val position = positionMs.coerceAtLeast(0L)
        var low = 0
        var high = cachedLines.lastIndex
        var resultIndex = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (cachedLines[middle].timeMs <= position) {
                resultIndex = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        val line = cachedLines.getOrNull(resultIndex)?.text.orEmpty()
        if (line != lastLoggedLine) {
            lastLoggedLine = line
            if (line.isNotBlank()) {
                logger("[QrcLyric] line changed=$line")
            }
        }
        return line
    }

    @Synchronized
    fun cachedLineCount(): Int {
        return cachedLines.size
    }

    @Synchronized
    fun clearCacheIfSongChanged(
        title: String,
        artist: String,
        album: String
    ) {
        val songKey = buildSongKey(
            normalizeForMatch(title),
            normalizeForMatch(artist),
            normalizeForMatch(album)
        )
        if (songKey == cachedSongKey) {
            return
        }
        cachedSongKey = songKey
        cachedGroupId = null
        cachedLines = emptyList()
        lastLoggedLine = null
    }

    @Synchronized
    fun scanQrcGroups(forceRefresh: Boolean = false): List<QrcGroup> {
        return getIndexEntries(forceRefresh).map(QrcGroupIndexEntry::toGroup)
    }

    private fun getIndexEntries(forceRefresh: Boolean): List<QrcGroupIndexEntry> {
        val now = System.currentTimeMillis()
        val directory = File(
            Environment.getExternalStorageDirectory(),
            "QQMusic/qrc"
        )
        val directoryModifiedAt = directory.lastModified()
        val files = try {
            directory.listFiles()?.filter(File::isFile).orEmpty()
        } catch (exception: Exception) {
            logger("[QrcIndex] build failed error=${exception.message}")
            emptyList()
        }
        val fileCount = files.size

        if (!forceRefresh &&
            indexEntries.isNotEmpty() &&
            now - indexBuiltAt < INDEX_CACHE_DURATION_MS &&
            directoryModifiedAt == indexDirectoryModifiedAt &&
            fileCount == indexFileCount
        ) {
            logger("[QrcIndex] cache hit groups=${indexEntries.size}")
            return indexEntries
        }

        return buildIndex(
            files = files,
            directoryModifiedAt = directoryModifiedAt,
            fileCount = fileCount
        )
    }

    private fun buildIndex(
        files: List<File>,
        directoryModifiedAt: Long,
        fileCount: Int
    ): List<QrcGroupIndexEntry> {
        val startedAt = System.currentTimeMillis()
        logger("[QrcIndex] build start")
        var normalizedTextBytes = 0
        var producerCacheLimitLogged = false

        val builders = linkedMapOf<String, QrcGroupBuilder>()
        files.forEach { file ->
            val match = GROUP_FILE_REGEX.matchEntire(file.name) ?: return@forEach
            val groupId = match.groupValues[1]
            val suffix = match.groupValues[2].lowercase(Locale.ROOT)
            val builder = builders.getOrPut(groupId) {
                QrcGroupBuilder(groupId)
            }
            builder.add(suffix, file)
        }

        val entries = builders.values
            .map(QrcGroupBuilder::build)
            .filter { it.qrcFile != null }
            .map { group ->
                val rawProducerText = if (normalizedTextBytes < MAX_TOTAL_PRODUCER_TEXT_BYTES) {
                    readProducerText(group.producerFile)
                } else {
                    if (!producerCacheLimitLogged) {
                        producerCacheLimitLogged = true
                        logger("[QrcIndex] producer text cache limit reached")
                    }
                    ""
                }
                val normalizedProducerText = normalizeForMatch(rawProducerText)
                normalizedTextBytes += normalizedProducerText
                    .toByteArray(Charsets.UTF_8)
                    .size
                QrcGroupIndexEntry(
                    groupId = group.groupId,
                    qrcFile = group.qrcFile,
                    producerFile = group.producerFile,
                    exFile = group.exFile,
                    translrcFile = group.translrcFile,
                    romaqrcFile = group.romaqrcFile,
                    lastModified = group.lastModified,
                    normalizedProducerText = normalizedProducerText,
                    producerTextLoaded = rawProducerText.isNotBlank(),
                    hasQrc = group.qrcFile != null,
                    hasProducer = group.producerFile != null,
                    hasTranslrc = group.translrcFile != null,
                    hasRomaqrc = group.romaqrcFile != null
                )
            }

        indexEntries = entries
        indexBuiltAt = System.currentTimeMillis()
        indexDirectoryModifiedAt = directoryModifiedAt
        indexFileCount = fileCount
        logger(
            "[QrcIndex] build end groups=${entries.size} " +
                "costMs=${indexBuiltAt - startedAt}"
        )
        return entries
    }

    private fun findBestGroup(
        entries: List<QrcGroupIndexEntry>,
        normalizedTitle: String,
        normalizedArtist: String,
        normalizedAlbum: String
    ): ScoredGroup? {
        val startedAt = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val producerCandidates = entries.mapNotNull { entry ->
            val producerText = entry.normalizedProducerText
            if (!containsNormalized(producerText, normalizedTitle)) {
                return@mapNotNull null
            }

            var score = 100
            if (containsNormalized(producerText, normalizedArtist)) score += 50
            if (containsNormalized(producerText, normalizedAlbum)) score += 20

            val age = (now - entry.lastModified).coerceAtLeast(0L)
            score += when {
                age <= FIVE_MINUTES_MS -> 30
                age <= THIRTY_MINUTES_MS -> 15
                else -> 0
            }
            if (entry.hasQrc) score += 10
            if (entry.hasTranslrc) score += 5
            if (entry.hasRomaqrc) score += 5
            ScoredGroup(entry = entry, score = score)
        }.sortedWith(
            compareByDescending<ScoredGroup> { it.score }
                .thenByDescending { it.entry.lastModified }
        )

        val producerCostMs = System.currentTimeMillis() - startedAt
        logger(
            "[QrcLyric] producer match candidates=${producerCandidates.size} " +
                "costMs=$producerCostMs"
        )

        val confirmStartedAt = System.currentTimeMillis()
        val producerConfirmCandidates = producerCandidates
            .take(DECRYPT_CONFIRM_CANDIDATES)
        var confirmed = confirmCandidates(
            candidates = producerConfirmCandidates,
            normalizedTitle = normalizedTitle,
            normalizedArtist = normalizedArtist
        )
        logger(
            "[QrcLyric] decrypt confirm candidates=${producerConfirmCandidates.size} " +
                "costMs=${System.currentTimeMillis() - confirmStartedAt}"
        )

        if (confirmed == null) {
            val fallbackStartedAt = System.currentTimeMillis()
            val attemptedGroupIds = producerConfirmCandidates
                .map { it.entry.groupId }
                .toSet()
            val fallbackCandidates = fallbackRecentCandidates(
                entries = entries,
                excludedGroupIds = attemptedGroupIds
            )
            confirmed = confirmCandidates(
                candidates = fallbackCandidates,
                normalizedTitle = normalizedTitle,
                normalizedArtist = normalizedArtist
            )
            logger(
                "[QrcLyric] fallback decrypt candidates=${fallbackCandidates.size} " +
                    "costMs=${System.currentTimeMillis() - fallbackStartedAt}"
            )
        }
        return confirmed
    }

    private fun fallbackRecentCandidates(
        entries: List<QrcGroupIndexEntry>,
        excludedGroupIds: Set<String>
    ): List<ScoredGroup> {
        return entries
            .asSequence()
            .filter { it.groupId !in excludedGroupIds }
            .sortedByDescending(QrcGroupIndexEntry::lastModified)
            .take(FALLBACK_DECRYPT_CANDIDATES)
            .map { entry ->
                ScoredGroup(entry = entry, score = 100)
            }
            .toList()
    }

    private fun confirmCandidates(
        candidates: List<ScoredGroup>,
        normalizedTitle: String,
        normalizedArtist: String
    ): ScoredGroup? {
        candidates.forEach { candidate ->
            val parsed = decryptAndParseQrc(candidate.entry) ?: return@forEach
            if (!parsedMatchesTitle(parsed, normalizedTitle)) {
                return@forEach
            }
            val artistBonus = if (
                normalizedArtist.isBlank() ||
                parsedMatchesArtist(parsed, normalizedArtist)
            ) {
                50
            } else {
                0
            }
            val confirmed = candidate.copy(
                score = candidate.score + artistBonus,
                parsed = parsed
            )
            if (LogConfig.DEBUG_VERBOSE_LOG) {
                logger(
                    "[QrcLyric] candidate score=${confirmed.score} " +
                        "groupId=${confirmed.entry.groupId}"
                )
            }
            return confirmed
        }
        return null
    }

    private fun decryptAndParseQrc(entry: QrcGroupIndexEntry): ParsedQrc? {
        val file = entry.qrcFile ?: return null
        val cacheKey = "${entry.groupId}:${file.lastModified()}:${file.length()}"
        if (parsedQrcCache.containsKey(cacheKey)) {
            return parsedQrcCache[cacheKey]
        }
        return try {
            val encrypted = file.readText(Charsets.US_ASCII)
                .filterNot(Char::isWhitespace)
            if (!isEncryptedQrcHex(encrypted)) {
                cacheManager.recordQrcDecrypt(success = false)
                logger(
                    "[QrcLyric] decrypt failed groupId=${entry.groupId} " +
                        "error=not encrypted QRC hex"
                )
                parsedQrcCache[cacheKey] = null
                return null
            }
            val decrypted = QrcDecrypter.decrypt(encrypted)
                ?: run {
                    cacheManager.recordQrcDecrypt(success = false)
                    logger(
                        "[QrcLyric] decrypt failed groupId=${entry.groupId} " +
                            "error=decrypt returned empty"
                    )
                    parsedQrcCache[cacheKey] = null
                    return null
                }
            parseQrc(decrypted).also {
                cacheManager.recordQrcDecrypt(success = it.lines.isNotEmpty())
                parsedQrcCache[cacheKey] = it
                trimParsedQrcCache()
            }
        } catch (exception: Exception) {
            cacheManager.recordQrcDecrypt(success = false)
            logger(
                "[QrcLyric] decrypt failed groupId=${entry.groupId} " +
                    "error=${exception.message}"
            )
            parsedQrcCache[cacheKey] = null
            null
        }
    }

    private fun applyParsedResult(
        songKey: String,
        entry: QrcGroupIndexEntry,
        parsed: ParsedQrc
    ) {
        cachedGroupId = entry.groupId
        cachedLines = parsed.lines
        songGroupCache[songKey] = entry.groupId
        if (parsed.lines.isNotEmpty()) {
            songLinesCache[songKey] = parsed.lines
            cacheManager.save(
                ParsedLyric(
                    songKey = songKey,
                    title = parsed.title,
                    artist = parsed.artist,
                    album = parsed.album,
                    groupId = entry.groupId,
                    qrcLastModified = entry.qrcFile?.lastModified() ?: 0L,
                    lines = parsed.lines.map {
                        QrcLyricLine(timeMs = it.timeMs, text = it.text)
                    }
                )
            )
            val parsedSongKey = QrcLyricUtils.buildSongKey(
                parsed.title,
                parsed.artist,
                parsed.album
            )
            if (shouldSaveAlias(
                    sourceSongKey = songKey,
                    targetSongKey = parsedSongKey,
                    parsed = parsed
                )
            ) {
                cacheManager.save(
                    ParsedLyric(
                        songKey = parsedSongKey,
                        title = parsed.title,
                        artist = parsed.artist,
                        album = parsed.album,
                        groupId = entry.groupId,
                        qrcLastModified = entry.qrcFile?.lastModified() ?: 0L,
                        lines = parsed.lines.map {
                            QrcLyricLine(timeMs = it.timeMs, text = it.text)
                        }
                    )
                )
                cacheManager.saveAlias(songKey, parsedSongKey)
            }
            trimSongCaches()
        }
    }

    private fun saveNegative(songKey: String) {
        negativeCacheManager.saveNegative(songKey, "NO_QRC")
        cacheManager.recordNegativeSaved()
    }

    private fun shouldSaveAlias(
        sourceSongKey: String,
        targetSongKey: String,
        parsed: ParsedQrc
    ): Boolean {
        if (sourceSongKey == targetSongKey || targetSongKey.isBlank()) {
            return false
        }
        val sourceParts = sourceSongKey.split("|")
        val sourceTitle = sourceParts.getOrNull(0).orEmpty()
        val sourceArtist = sourceParts.getOrNull(1).orEmpty()
        val parsedTitle = normalizeForMatch(parsed.title)
        val parsedArtist = normalizeForMatch(parsed.artist)
        val titleMatches = sourceTitle.isNotBlank() &&
            parsedTitle.isNotBlank() &&
            (sourceTitle == parsedTitle ||
                sourceTitle.contains(parsedTitle) ||
                parsedTitle.contains(sourceTitle))
        val artistMatches = sourceArtist.isBlank() ||
            parsedArtist.isBlank() ||
            sourceArtist == parsedArtist ||
            sourceArtist.contains(parsedArtist) ||
            parsedArtist.contains(sourceArtist)
        return titleMatches && artistMatches
    }

    private fun trimSongCaches() {
        if (songLinesCache.size <= MAX_SONG_CACHE_SIZE) {
            return
        }
        val overflow = songLinesCache.size - MAX_SONG_CACHE_SIZE
        songLinesCache.keys.take(overflow).forEach { key ->
            songLinesCache.remove(key)
            songGroupCache.remove(key)
        }
    }

    private fun parseQrc(decrypted: String): ParsedQrc {
        val lyricContent = QRC_LYRIC_CONTENT_REGEX.find(decrypted)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::unescapeXml)
            .orEmpty()
        val metadata = QRC_METADATA_REGEX.findAll(lyricContent)
            .associate { match ->
                match.groupValues[1].lowercase(Locale.ROOT) to
                    match.groupValues[2].trim()
            }
        val markers = QRC_LINE_REGEX.findAll(lyricContent).map { match ->
            QrcLineMarker(
                timeMs = match.groupValues[1].toLongOrNull() ?: 0L,
                bodyStart = match.range.last + 1,
                markerStart = match.range.first
            )
        }.toList()
        val lines = markers.mapIndexedNotNull { index, marker ->
            val bodyEnd = markers.getOrNull(index + 1)?.markerStart
                ?: lyricContent.length
            val text = lyricContent
                .substring(marker.bodyStart, bodyEnd)
                .replace(QRC_WORD_TIME_REGEX, "")
                .trim()
            text.takeIf(String::isNotBlank)?.let {
                LyricLine(marker.timeMs, it)
            }
        }.distinctBy { it.timeMs to it.text }
            .sortedBy(LyricLine::timeMs)

        return ParsedQrc(
            title = metadata["ti"].orEmpty(),
            artist = metadata["ar"].orEmpty(),
            album = metadata["al"].orEmpty(),
            lines = lines,
            rawText = decrypted
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

    private fun trimParsedQrcCache() {
        if (parsedQrcCache.size <= MAX_PARSED_QRC_CACHE_SIZE) {
            return
        }
        val overflow = parsedQrcCache.size - MAX_PARSED_QRC_CACHE_SIZE
        parsedQrcCache.keys.take(overflow).forEach(parsedQrcCache::remove)
    }

    private fun isEncryptedQrcHex(value: String): Boolean {
        return value.length >= MIN_HEX_LENGTH &&
            value.length % 16 == 0 &&
            HEX_REGEX.matches(value)
    }

    private fun parsedMatchesTitle(
        parsed: ParsedQrc,
        normalizedTitle: String
    ): Boolean {
        val normalizedRaw = normalizeForMatch(parsed.rawText)
        return containsNormalized(normalizedRaw, normalizedTitle) ||
            containsNormalized(normalizeForMatch(parsed.title), normalizedTitle)
    }

    private fun parsedMatchesArtist(
        parsed: ParsedQrc,
        normalizedArtist: String
    ): Boolean {
        val normalizedRaw = normalizeForMatch(parsed.rawText)
        return containsNormalized(normalizedRaw, normalizedArtist) ||
            containsNormalized(normalizeForMatch(parsed.artist), normalizedArtist)
    }

    private fun containsNormalized(value: String, expected: String): Boolean {
        return expected.isNotBlank() && value.contains(expected)
    }

    private fun normalizeForMatch(text: String): String {
        return text
            .lowercase(Locale.ROOT)
            .replace(BRACKET_CONTENT_REGEX, "")
            .replace(MATCH_PUNCTUATION_REGEX, "")
            .replace(WHITESPACE_REGEX, "")
            .trim()
    }

    private fun buildSongKey(
        normalizedTitle: String,
        normalizedArtist: String,
        normalizedAlbum: String
    ): String {
        return "$normalizedTitle|$normalizedArtist|$normalizedAlbum"
    }

    private fun unescapeXml(value: String): String {
        return value
            .replace("&#10;", "\n")
            .replace("&#13;", "\r")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    data class QrcGroup(
        val groupId: String,
        val qrcFile: File?,
        val producerFile: File?,
        val exFile: File?,
        val translrcFile: File?,
        val romaqrcFile: File?,
        val lastModified: Long
    )

    data class LyricLine(
        val timeMs: Long,
        val text: String
    )

    data class QrcGroupIndexEntry(
        val groupId: String,
        val qrcFile: File?,
        val producerFile: File?,
        val exFile: File?,
        val translrcFile: File?,
        val romaqrcFile: File?,
        val lastModified: Long,
        val normalizedProducerText: String,
        val producerTextLoaded: Boolean,
        val hasQrc: Boolean,
        val hasProducer: Boolean,
        val hasTranslrc: Boolean,
        val hasRomaqrc: Boolean
    ) {
        fun toGroup(): QrcGroup {
            return QrcGroup(
                groupId = groupId,
                qrcFile = qrcFile,
                producerFile = producerFile,
                exFile = exFile,
                translrcFile = translrcFile,
                romaqrcFile = romaqrcFile,
                lastModified = lastModified
            )
        }
    }

    private data class ParsedQrc(
        val title: String,
        val artist: String,
        val album: String,
        val lines: List<LyricLine>,
        val rawText: String
    )

    private data class ScoredGroup(
        val entry: QrcGroupIndexEntry,
        val score: Int,
        val parsed: ParsedQrc? = null
    )

    private data class QrcLineMarker(
        val timeMs: Long,
        val bodyStart: Int,
        val markerStart: Int
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

        fun build(): QrcGroup {
            val files = listOfNotNull(
                qrcFile,
                producerFile,
                exFile,
                translrcFile,
                romaqrcFile
            )
            return QrcGroup(
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
        private const val INDEX_CACHE_DURATION_MS = 5 * 60_000L
        private const val FIVE_MINUTES_MS = 5 * 60_000L
        private const val THIRTY_MINUTES_MS = 30 * 60_000L
        private const val MIN_MATCH_SCORE = 100
        private const val DECRYPT_CONFIRM_CANDIDATES = 3
        private const val FALLBACK_DECRYPT_CANDIDATES = 3
        private const val MAX_PRODUCER_BYTES = 8 * 1024
        private const val MAX_TOTAL_PRODUCER_TEXT_BYTES = 3 * 1024 * 1024
        private const val MAX_PARSED_QRC_CACHE_SIZE = 80
        private const val MAX_SONG_CACHE_SIZE = 80
        private const val MIN_HEX_LENGTH = 128

        private val GROUP_FILE_REGEX =
            Regex("""^(-?\d+)\.(qrc|producer|ex|translrc|romaqrc)$""")
        private val QRC_LYRIC_CONTENT_REGEX =
            Regex("""LyricContent\s*=\s*"([\s\S]*?)"""")
        private val QRC_METADATA_REGEX =
            Regex("""\[(\w+)\s*:\s*([^]]*)]""")
        private val QRC_LINE_REGEX =
            Regex("""\[(\d+)\s*,\s*(\d+)]""")
        private val QRC_WORD_TIME_REGEX =
            Regex("""\(\d+\s*,\s*\d+\)""")
        private val HEX_REGEX = Regex("""[0-9A-Fa-f]+""")
        private val BRACKET_CONTENT_REGEX =
            Regex("""\([^)]*\)|（[^）]*）|\[[^]]*]|\【[^】]*】""")
        private val MATCH_PUNCTUATION_REGEX =
            Regex("""[-_.·・/\\:]""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
