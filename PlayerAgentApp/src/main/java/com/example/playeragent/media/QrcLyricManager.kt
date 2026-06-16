package com.example.playeragent.media

import android.content.Context
import android.os.Environment
import io.github.proify.qrckit.decrypt.QrcDecrypter
import java.io.File
import java.util.Locale

class QrcLyricManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private var cachedGroups: List<QrcGroup> = emptyList()
    private var groupsCachedAt = 0L
    private var cachedSongKey: String? = null
    private var cachedGroupId: String? = null
    private var cachedLines: List<LyricLine> = emptyList()
    private var lastLoggedLine: String? = null

    @Synchronized
    fun load(title: String, artist: String, album: String) {
        val songKey = buildSongKey(title, artist, album)
        if (songKey == cachedSongKey) {
            return
        }

        clearCacheIfSongChanged(title, artist, album)
        if (title.isBlank() && artist.isBlank()) {
            return
        }

        logger("[QrcLyric] load title=$title artist=$artist album=$album")
        val groups = scanQrcGroups()
        logger("[QrcLyric] scan groups count=${groups.size}")
        if (groups.isEmpty()) {
            logger("[QrcLyric] best group=none score=0")
            return
        }

        val best = findBestGroup(groups, title, artist, album)
        if (best == null || best.score < MIN_MATCH_SCORE) {
            logger(
                "[QrcLyric] best group=${best?.group?.groupId ?: "none"} " +
                    "score=${best?.score ?: 0}"
            )
            return
        }

        cachedGroupId = best.group.groupId
        cachedLines = best.parsed?.lines
            ?: decryptAndParseQrc(best.group)?.lines.orEmpty()
        logger(
            "[QrcLyric] best group=${best.group.groupId} score=${best.score}"
        )
        logger("[QrcLyric] decrypted lines=${cachedLines.size}")
        cachedLines.firstOrNull()?.let {
            logger("[QrcLyric] first line=${it.text}")
        }
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
    fun clearCacheIfSongChanged(
        title: String,
        artist: String,
        album: String
    ) {
        val songKey = buildSongKey(title, artist, album)
        if (songKey == cachedSongKey) {
            return
        }
        cachedSongKey = songKey
        cachedGroupId = null
        cachedLines = emptyList()
        lastLoggedLine = null
    }

    @Synchronized
    fun scanQrcGroups(): List<QrcGroup> {
        val now = System.currentTimeMillis()
        if (cachedGroups.isNotEmpty() &&
            now - groupsCachedAt < GROUP_CACHE_DURATION_MS
        ) {
            return cachedGroups
        }

        val directory = File(
            Environment.getExternalStorageDirectory(),
            "QQMusic/qrc"
        )
        val files = try {
            directory.listFiles()?.filter(File::isFile).orEmpty()
        } catch (exception: Exception) {
            logger("[QrcLyric] scan failed error=${exception.message}")
            emptyList()
        }

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

        cachedGroups = builders.values
            .map(QrcGroupBuilder::build)
            .filter { it.qrcFile != null }
        groupsCachedAt = now
        return cachedGroups
    }

    private fun findBestGroup(
        groups: List<QrcGroup>,
        title: String,
        artist: String,
        album: String
    ): ScoredGroup? {
        val now = System.currentTimeMillis()
        val normalizedTitle = normalize(title)
        val normalizedArtist = normalize(artist)
        val normalizedAlbum = normalize(album)

        val preliminary = groups.map { group ->
            var score = 0
            val producerText = readSmallText(group.producerFile)
            if (containsNormalized(producerText, normalizedTitle)) score += 80
            if (containsNormalized(producerText, normalizedArtist)) score += 50
            if (containsNormalized(producerText, normalizedAlbum)) score += 30

            val age = (now - group.lastModified).coerceAtLeast(0L)
            score += when {
                age <= FIVE_MINUTES_MS -> 40
                age <= THIRTY_MINUTES_MS -> 20
                else -> 0
            }
            if (group.qrcFile != null) score += 10
            if (group.producerFile != null) score += 5
            ScoredGroup(group = group, score = score)
        }

        val decryptCandidates = preliminary
            .filter {
                it.score > BASE_FILE_SCORE ||
                    it.group.groupId == cachedGroupId
            }
            .sortedWith(
                compareByDescending<ScoredGroup> { it.score }
                    .thenByDescending { it.group.lastModified }
            )
            .take(MAX_DECRYPT_CANDIDATES)

        val scored = decryptCandidates.map { candidate ->
            val parsed = decryptAndParseQrc(candidate.group)
            var score = candidate.score
            if (parsed != null) {
                val decryptedText = normalize(parsed.rawText)
                if (containsNormalized(decryptedText, normalizedTitle)) {
                    score += 80
                }
                if (containsNormalized(decryptedText, normalizedArtist)) {
                    score += 50
                }
                if (containsNormalized(parsed.title, normalizedTitle)) {
                    score += 80
                }
                if (containsNormalized(parsed.artist, normalizedArtist)) {
                    score += 50
                }
            }
            ScoredGroup(candidate.group, score, parsed)
        }.sortedByDescending(ScoredGroup::score)

        scored.take(CANDIDATE_LOG_LIMIT).forEach {
            logger(
                "[QrcLyric] candidate score=${it.score} " +
                    "groupId=${it.group.groupId}"
            )
        }
        return scored.firstOrNull()
    }

    private fun decryptAndParseQrc(group: QrcGroup): ParsedQrc? {
        val file = group.qrcFile ?: return null
        return try {
            val encrypted = file.readText(Charsets.US_ASCII)
                .filterNot(Char::isWhitespace)
            if (!isEncryptedQrcHex(encrypted)) {
                logger(
                    "[QrcLyric] decrypt failed groupId=${group.groupId} " +
                        "error=not encrypted QRC hex"
                )
                return null
            }
            val decrypted = QrcDecrypter.decrypt(encrypted)
                ?: run {
                    logger(
                        "[QrcLyric] decrypt failed groupId=${group.groupId} " +
                            "error=decrypt returned empty"
                    )
                    return null
                }
            parseQrc(decrypted)
        } catch (exception: Exception) {
            logger(
                "[QrcLyric] decrypt failed groupId=${group.groupId} " +
                    "error=${exception.message}"
            )
            null
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

    private fun readSmallText(file: File?): String {
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

    private fun isEncryptedQrcHex(value: String): Boolean {
        return value.length >= MIN_HEX_LENGTH &&
            value.length % 16 == 0 &&
            HEX_REGEX.matches(value)
    }

    private fun containsNormalized(value: String, expected: String): Boolean {
        return expected.isNotBlank() && normalize(value).contains(expected)
    }

    private fun normalize(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(NORMALIZE_REGEX, "")
    }

    private fun buildSongKey(
        title: String,
        artist: String,
        album: String
    ): String {
        return "${title.trim()}|${artist.trim()}|${album.trim()}"
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

    private data class ParsedQrc(
        val title: String,
        val artist: String,
        val album: String,
        val lines: List<LyricLine>,
        val rawText: String
    )

    private data class ScoredGroup(
        val group: QrcGroup,
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
        private const val GROUP_CACHE_DURATION_MS = 60_000L
        private const val FIVE_MINUTES_MS = 5 * 60_000L
        private const val THIRTY_MINUTES_MS = 30 * 60_000L
        private const val MIN_MATCH_SCORE = 50
        private const val BASE_FILE_SCORE = 15
        private const val MAX_DECRYPT_CANDIDATES = 12
        private const val CANDIDATE_LOG_LIMIT = 8
        private const val MAX_PRODUCER_BYTES = 8 * 1024
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
        private val NORMALIZE_REGEX = Regex("""[\s\p{P}\p{S}]+""")
    }
}
