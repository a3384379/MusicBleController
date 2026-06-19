package com.example.playeragent.media

import android.content.Context
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
    private val persistentIndexManager = QrcPersistentIndexManager(
        context = appContext,
        logger = logger
    )
    private var cachedSongKey: String? = null
    private var cachedGroupId: String? = null
    private var cachedLines: List<LyricLine> = emptyList()
    private var lastLoggedLine: String? = null
    private val songGroupCache = mutableMapOf<String, String>()
    private val songLinesCache = mutableMapOf<String, List<LyricLine>>()
    private val parsedQrcCache = mutableMapOf<String, ParsedQrc?>()
    private val uncertainMissCooldown = mutableMapOf<String, QrcCooldownEntry>()

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
        clearUncertainCooldownIfIndexDirty()

        val startedAt = System.currentTimeMillis()
        logger("[QrcLyric] load title=$title artist=$artist album=$album")
        logger("[QrcLyric] findBestGroup start title=$title")

        cacheManager.get(title, artist, album)?.let { cached ->
            cachedLines = cached.lines.map {
                LyricLine(
                    timeMs = it.timeMs,
                    text = it.text,
                    durationMs = it.durationMs,
                    words = it.words,
                    translation = it.translation,
                    romanization = it.romanization
                )
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
        if (isUncertainCooldownActive(songKey)) {
            logger("[QrcLyric] best group=none score=0")
            return false
        }

        val entries = getIndexEntries(forceRefresh = false)
        logger("[QrcLyric] scan groups count=${entries.size}")
        if (entries.isEmpty()) {
            logger("[QrcLyric] best group=none score=0")
            logger("[QrcLyric] skip negative cache reason=no qrc entries")
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

        var searchResult = findBestGroup(
            entries = entries,
            normalizedTitle = normalizedTitle,
            normalizedArtist = normalizedArtist,
            normalizedAlbum = normalizedAlbum
        )
        if (searchResult.best == null && searchResult.producerCandidateCount == 0) {
            val refreshedEntries = refreshIndexIfChanged(
                reason = "no producer candidate"
            )
            if (refreshedEntries != null) {
                logger("[QrcLyric] retry after index refresh")
                searchResult = findBestGroup(
                    entries = refreshedEntries,
                    normalizedTitle = normalizedTitle,
                    normalizedArtist = normalizedArtist,
                    normalizedAlbum = normalizedAlbum
                )
            }
        }
        val best = searchResult.best
        if (best == null || best.score < MIN_MATCH_SCORE) {
            logger(
                "[QrcLyric] best group=${best?.entry?.groupId ?: "none"} " +
                    "score=${best?.score ?: 0}"
            )
            logger("[QrcLyric] skip negative cache reason=uncertain qrc match")
            saveUncertainCooldown(songKey)
            return false
        }

        val parsed = best.parsed ?: decryptAndParseQrc(best.entry)
        if (parsed == null || !parsedMatchesTitle(parsed, normalizedTitle)) {
            logger("[QrcLyric] best group=none score=0")
            logger("[QrcLyric] skip negative cache reason=decrypt unconfirmed")
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
    fun lyricLinesSnapshot(): List<LyricLine> {
        return cachedLines.toList()
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
        return persistentIndexManager.getIndex(forceRefresh = forceRefresh)
    }

    private fun refreshIndexIfChanged(reason: String): List<QrcGroupIndexEntry>? {
        logger("[QrcIndex] refresh check reason=$reason")
        val before = persistentIndexManager.status()
        val refreshed = persistentIndexManager.getIndex(forceRefresh = false)
        val after = persistentIndexManager.status()
        return if (before.entries == after.entries &&
            before.builtAt == after.builtAt &&
            !before.dirty
        ) {
            logger("[QrcIndex] refresh skipped no directory change")
            null
        } else {
            refreshed
        }
    }

    private fun findBestGroup(
        entries: List<QrcGroupIndexEntry>,
        normalizedTitle: String,
        normalizedArtist: String,
        normalizedAlbum: String
    ): SearchResult {
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
            val fallbackCount = if (
                normalizedTitle.isNotBlank() &&
                producerCandidates.isEmpty() &&
                hasRecentQrcFiles(entries)
            ) {
                RECENT_FALLBACK_DECRYPT_CANDIDATES
            } else {
                FALLBACK_DECRYPT_CANDIDATES
            }
            val fallbackCandidates = fallbackRecentCandidates(
                entries = entries,
                excludedGroupIds = attemptedGroupIds,
                limit = fallbackCount
            )
            if (fallbackCount == RECENT_FALLBACK_DECRYPT_CANDIDATES) {
                logger(
                    "[QrcLyric] recent fallback decrypt candidates=$fallbackCount " +
                        "reason=new qrc files"
                )
            }
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
        return SearchResult(
            best = confirmed,
            producerCandidateCount = producerCandidates.size
        )
    }

    private fun fallbackRecentCandidates(
        entries: List<QrcGroupIndexEntry>,
        excludedGroupIds: Set<String>,
        limit: Int
    ): List<ScoredGroup> {
        return entries
            .asSequence()
            .filter { it.groupId !in excludedGroupIds }
            .sortedByDescending(QrcGroupIndexEntry::lastModified)
            .take(limit)
            .map { entry ->
                ScoredGroup(entry = entry, score = 100)
            }
            .toList()
    }

    private fun hasRecentQrcFiles(entries: List<QrcGroupIndexEntry>): Boolean {
        val threshold = System.currentTimeMillis() - THIRTY_MINUTES_MS
        return entries.any { it.lastModified >= threshold }
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
        val cacheKey = listOf(
            entry.groupId,
            file.lastModified(),
            file.length(),
            entry.translrcFile?.lastModified() ?: 0L,
            entry.translrcFile?.length() ?: 0L,
            entry.romaqrcFile?.lastModified() ?: 0L,
            entry.romaqrcFile?.length() ?: 0L
        ).joinToString(":")
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
            parseQrc(decrypted, entry).also {
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
        removeUncertainCooldown(songKey, "qrc parsed")
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
                        QrcLyricLine(
                            timeMs = it.timeMs,
                            text = it.text,
                            durationMs = it.durationMs,
                            words = it.words,
                            translation = it.translation,
                            romanization = it.romanization
                        )
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
                            QrcLyricLine(
                                timeMs = it.timeMs,
                                text = it.text,
                                durationMs = it.durationMs,
                                words = it.words,
                                translation = it.translation,
                                romanization = it.romanization
                            )
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

    private fun parseQrc(decrypted: String, entry: QrcGroupIndexEntry): ParsedQrc {
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
                durationMs = match.groupValues[2].toLongOrNull() ?: 0L,
                bodyStart = match.range.last + 1,
                markerStart = match.range.first
            )
        }.toList()
        val baseLines = markers.mapIndexedNotNull { index, marker ->
            val bodyEnd = markers.getOrNull(index + 1)?.markerStart
                ?: lyricContent.length
            val parsedBody = QrcLyricUtils.parseQrcLineBody(
                lineStartMs = marker.timeMs,
                body = lyricContent.substring(marker.bodyStart, bodyEnd)
            )
            parsedBody.text.takeIf(String::isNotBlank)?.let {
                LyricLine(
                    timeMs = marker.timeMs,
                    text = it,
                    durationMs = marker.durationMs,
                    words = parsedBody.words
                )
            }
        }.distinctBy { it.timeMs to it.text }
            .sortedBy(LyricLine::timeMs)
        val lines = QrcLyricUtils.applyAuxiliaryLyrics(
            groupId = entry.groupId,
            lines = baseLines.map {
                QrcLyricLine(
                    timeMs = it.timeMs,
                    text = it.text,
                    durationMs = it.durationMs,
                    words = it.words
                )
            },
            translrcFile = entry.translrcFile,
            romaqrcFile = entry.romaqrcFile,
            logger = logger
        ).map {
            LyricLine(
                timeMs = it.timeMs,
                text = it.text,
                durationMs = it.durationMs,
                words = it.words,
                translation = it.translation,
                romanization = it.romanization
            )
        }
        lines.withIndex()
            .filter { it.value.words.isNotEmpty() }
            .take(3)
            .forEach { (index, line) ->
                val firstStart = line.words.firstOrNull()?.startMs ?: 0L
                val lastEnd = line.words.lastOrNull()?.let { it.startMs + it.durationMs } ?: 0L
                logger(
                    "[QrcWord] parsed line=$index text=${line.text} " +
                        "words=${line.words.size} firstStart=$firstStart lastEnd=$lastEnd"
                )
            }

        return ParsedQrc(
            title = QrcLyricUtils.sanitizeMetadataTitle(
                metadata["ti"].orEmpty(),
                entry.groupId,
                logger
            ),
            artist = metadata["ar"].orEmpty(),
            album = metadata["al"].orEmpty(),
            lines = lines,
            rawText = decrypted
        )
    }

    private fun trimParsedQrcCache() {
        if (parsedQrcCache.size <= MAX_PARSED_QRC_CACHE_SIZE) {
            return
        }
        val overflow = parsedQrcCache.size - MAX_PARSED_QRC_CACHE_SIZE
        parsedQrcCache.keys.take(overflow).forEach(parsedQrcCache::remove)
    }

    private fun clearUncertainCooldownIfIndexDirty() {
        if (uncertainMissCooldown.isEmpty()) {
            return
        }
        if (persistentIndexManager.status().dirty) {
            uncertainMissCooldown.clear()
            logger("[QrcCooldown] cleared reason=qrc directory changed")
        }
    }

    private fun isUncertainCooldownActive(songKey: String): Boolean {
        val entry = uncertainMissCooldown[songKey] ?: return false
        val currentGeneration = QrcDirectoryGeneration.current()
        if (entry.generation != currentGeneration) {
            uncertainMissCooldown.remove(songKey)
            logger(
                "[QrcCooldown] invalidated songKey=$songKey " +
                    "oldGeneration=${entry.generation} newGeneration=$currentGeneration"
            )
            return false
        }
        val now = System.currentTimeMillis()
        return if (now < entry.retryAfterMs) {
            logger("[QrcCooldown] hit songKey=$songKey generation=${entry.generation}")
            true
        } else {
            uncertainMissCooldown.remove(songKey)
            false
        }
    }

    private fun saveUncertainCooldown(songKey: String) {
        if (songKey.isBlank()) {
            return
        }
        val retryAfterMs = System.currentTimeMillis() + UNCERTAIN_MISS_COOLDOWN_MS
        val generation = QrcDirectoryGeneration.current()
        uncertainMissCooldown[songKey] = QrcCooldownEntry(
            retryAfterMs = retryAfterMs,
            generation = generation,
            reason = "uncertain qrc match"
        )
        logger(
            "[QrcCooldown] saved songKey=$songKey generation=$generation " +
                "retryAfter=$retryAfterMs"
        )
    }

    @Synchronized
    fun removeUncertainCooldown(songKey: String, reason: String) {
        if (uncertainMissCooldown.remove(songKey) != null) {
            logger("[QrcCooldown] removed songKey=$songKey reason=$reason")
        }
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
            .replace(VERSION_WORD_REGEX, "")
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
        val text: String,
        val durationMs: Long = 0L,
        val words: List<QrcLyricWord> = emptyList(),
        val translation: String? = null,
        val romanization: String? = null
    )

    private data class ParsedQrc(
        val title: String,
        val artist: String,
        val album: String,
        val lines: List<LyricLine>,
        val rawText: String
    )

    private data class QrcCooldownEntry(
        val retryAfterMs: Long,
        val generation: Long,
        val reason: String
    )

    private data class ScoredGroup(
        val entry: QrcGroupIndexEntry,
        val score: Int,
        val parsed: ParsedQrc? = null
    )

    private data class SearchResult(
        val best: ScoredGroup?,
        val producerCandidateCount: Int
    )

    private data class QrcLineMarker(
        val timeMs: Long,
        val durationMs: Long,
        val bodyStart: Int,
        val markerStart: Int
    )

    companion object {
        private const val FIVE_MINUTES_MS = 5 * 60_000L
        private const val THIRTY_MINUTES_MS = 30 * 60_000L
        private const val MIN_MATCH_SCORE = 100
        private const val DECRYPT_CONFIRM_CANDIDATES = 3
        private const val FALLBACK_DECRYPT_CANDIDATES = 3
        private const val RECENT_FALLBACK_DECRYPT_CANDIDATES = 10
        private const val MAX_PARSED_QRC_CACHE_SIZE = 80
        private const val MAX_SONG_CACHE_SIZE = 80
        private const val MIN_HEX_LENGTH = 128
        private const val UNCERTAIN_MISS_COOLDOWN_MS = 10 * 60_000L

        private val QRC_LYRIC_CONTENT_REGEX =
            Regex("""LyricContent\s*=\s*"([\s\S]*?)"""")
        private val QRC_METADATA_REGEX =
            Regex("""\[(\w+)\s*:\s*([^]]*)]""")
        private val QRC_LINE_REGEX =
            Regex("""\[(\d+)\s*,\s*(\d+)]""")
        private val HEX_REGEX = Regex("""[0-9A-Fa-f]+""")
        private val BRACKET_CONTENT_REGEX =
            Regex("""\([^)]*\)|（[^）]*）|\[[^]]*]|\【[^】]*】""")
        private val MATCH_PUNCTUATION_REGEX =
            Regex("""[-_.·・/\\:，,、&+]""")
        private val VERSION_WORD_REGEX =
            Regex(
                """\b(live|remix|cover|demo|version)\b|合唱版|独唱版|现场版|完整版|原版|新版|dj版|伴奏|纯音乐|翻唱""",
                RegexOption.IGNORE_CASE
            )
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
