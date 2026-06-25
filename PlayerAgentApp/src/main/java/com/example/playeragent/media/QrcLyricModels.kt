package com.example.playeragent.media

import android.content.Context
import android.os.Environment
import io.github.proify.qrckit.decrypt.QrcDecrypter
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale

data class QrcLyricLine(
    val timeMs: Long,
    val text: String,
    val durationMs: Long = 0L,
    val words: List<QrcLyricWord> = emptyList(),
    val translation: String? = null,
    val romanization: String? = null
)

data class QrcLyricWord(
    val startMs: Long,
    val durationMs: Long,
    val text: String
)

data class QrcParsedLineBody(
    val text: String,
    val words: List<QrcLyricWord>
)

data class QrcAuxiliaryLyricLine(
    val timeMs: Long,
    val text: String
)

data class QrcAuxiliaryParseResult(
    val lines: List<QrcAuxiliaryLyricLine> = emptyList(),
    val failed: Boolean = false,
    val reason: String? = null
)

data class QrcGroupFingerprint(
    val qrcLastModified: Long = 0L,
    val qrcSize: Long = 0L,
    val producerLastModified: Long = 0L,
    val producerSize: Long = 0L,
    val exLastModified: Long = 0L,
    val exSize: Long = 0L,
    val translrcLastModified: Long = 0L,
    val translrcSize: Long = 0L,
    val romaqrcLastModified: Long = 0L,
    val romaqrcSize: Long = 0L,
    val hasQrc: Boolean = false,
    val hasProducer: Boolean = false,
    val hasEx: Boolean = false,
    val hasTranslrc: Boolean = false,
    val hasRomaqrc: Boolean = false
)

data class ParsedLyric(
    val songKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val groupId: String,
    val qrcLastModified: Long,
    val lines: List<QrcLyricLine>,
    val schemaVersion: Int = QRC_CACHE_SCHEMA_V2,
    val qrcPath: String = "",
    val wordTimingStatus: QrcWordTimingStatus = QrcWordTimingStatus.fromLines(lines),
    val rawText: String = "",
    val groupFingerprint: QrcGroupFingerprint? = null,
    val cacheBuildVersion: Int = QRC_CACHE_BUILD_VERSION,
    val translationParseFailed: Boolean = false,
    val translationParseFailedReason: String? = null,
    val translationSourceLastModified: Long = 0L,
    val translationSourceSize: Long = 0L,
    val romanizationParseFailed: Boolean = false,
    val romanizationParseFailedReason: String? = null,
    val romanizationSourceLastModified: Long = 0L,
    val romanizationSourceSize: Long = 0L
)

enum class QrcWordTimingStatus {
    AVAILABLE,
    UNAVAILABLE,
    FAILED;

    companion object {
        fun fromLines(lines: List<QrcLyricLine>): QrcWordTimingStatus {
            return if (lines.any { it.words.isNotEmpty() }) {
                AVAILABLE
            } else {
                UNAVAILABLE
            }
        }

        fun fromValue(value: String): QrcWordTimingStatus {
            return values().firstOrNull { it.name == value } ?: UNAVAILABLE
        }
    }
}

const val QRC_CACHE_SCHEMA_V1 = 1
const val QRC_CACHE_SCHEMA_V2 = 2
const val QRC_CACHE_BUILD_VERSION = 3

data class LyricCacheStats(
    val l1Hit: Long = 0,
    val l2Hit: Long = 0,
    val l2FuzzyHit: Long = 0,
    val aliasHit: Long = 0,
    val negativeHit: Long = 0,
    val qrcDecryptCount: Long = 0,
    val qrcDecryptSuccess: Long = 0,
    val qrcDecryptFailed: Long = 0,
    val negativeSaved: Long = 0,
    val aliasSaved: Long = 0,
    val lastSource: String = "NONE"
)

data class QrcFileGroup(
    val groupId: String,
    val qrcFile: File?,
    val producerFile: File?,
    val exFile: File?,
    val translrcFile: File?,
    val romaqrcFile: File?,
    val lastModified: Long
)

data class QrcGroupIndexEntry(
    val groupId: String,
    val qrcFile: File?,
    val producerFile: File?,
    val exFile: File?,
    val translrcFile: File?,
    val romaqrcFile: File?,
    val lastModified: Long,
    val title: String,
    val artist: String,
    val album: String,
    val normalizedTitle: String,
    val normalizedArtist: String,
    val normalizedAlbum: String,
    val normalizedProducerText: String,
    val producerTextLoaded: Boolean,
    val hasQrc: Boolean,
    val hasProducer: Boolean,
    val hasTranslrc: Boolean,
    val hasRomaqrc: Boolean
) {
    fun toGroup(): QrcLyricManager.QrcGroup {
        return QrcLyricManager.QrcGroup(
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

data class QrcPersistentIndexStatus(
    val loaded: Boolean,
    val dirty: Boolean,
    val entries: Int,
    val builtAt: Long
)

data class QrcFuzzyIndexStatus(
    val ready: Boolean,
    val warming: Boolean,
    val entries: Int,
    val files: Int,
    val builtAt: Long
)

data class CurrentTrackSnapshot(
    val trackId: String,
    val songKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val trackChangedAtMs: Long,
    val hasLyrics: Boolean,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val albumArtId: String? = null,
    val lyricSource: String = "NONE",
    val lyricLines: List<RuntimeLyricLine> = emptyList(),
    val translationLines: List<String?> = emptyList(),
    val romanizationLines: List<String?> = emptyList(),
    val currentLine: String = "",
    val currentWord: RuntimeLyricWord? = null,
    val lastPlaybackState: String = "",
    val lastUpdatedAtMs: Long = 0L,
    val recoveryState: String = "",
    val albumArtState: String = "",
    val diagnosticSnapshot: String = ""
)

data class IncrementalLyricsReady(
    val groupId: String,
    val parsed: ParsedLyric,
    val currentTrack: CurrentTrackSnapshot?,
    val matchedCurrentTrack: Boolean
)

object QrcLyricUtils {

    fun cacheDirectory(context: Context): File {
        return File(context.getExternalFilesDir(null), "QrcLyricCache").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    fun qrcDirectory(): File {
        return File(Environment.getExternalStorageDirectory(), "QQMusic/qrc")
    }

    fun scanGroups(): List<QrcFileGroup> {
        val files = try {
            qrcDirectory().listFiles()?.filter(File::isFile).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val builders = linkedMapOf<String, QrcGroupBuilder>()
        files.forEach { file ->
            val match = GROUP_FILE_REGEX.matchEntire(file.name) ?: return@forEach
            val groupId = match.groupValues[1]
            val suffix = match.groupValues[2].lowercase(Locale.ROOT)
            builders.getOrPut(groupId) { QrcGroupBuilder(groupId) }
                .add(suffix, file)
        }
        return builders.values
            .map(QrcGroupBuilder::build)
            .filter { it.qrcFile != null }
    }

    fun decryptAndParseGroup(
        group: QrcFileGroup,
        logger: ((String) -> Unit)? = null
    ): ParsedLyric? {
        val qrcFile = group.qrcFile ?: return null
        val encrypted = try {
            qrcFile.readText(Charsets.US_ASCII).filterNot(Char::isWhitespace)
        } catch (_: Exception) {
            return null
        }
        if (!isEncryptedQrcHex(encrypted)) {
            return null
        }
        val decrypted = QrcDecrypter.decrypt(encrypted) ?: return null
        val parsed = parseDecryptedQrc(
            decrypted = decrypted,
            group = group,
            logger = logger
        )
        if (parsed.lines.isEmpty()) {
            return null
        }
        return parsed
    }

    fun normalizeForMatch(text: String): String {
        return text
            .lowercase(Locale.ROOT)
            .replace(BRACKET_CONTENT_REGEX, "")
            .replace(VERSION_WORD_REGEX, "")
            .replace(MATCH_PUNCTUATION_REGEX, "")
            .replace(WHITESPACE_REGEX, "")
            .trim()
    }

    fun isInvalidMetadataTitle(value: String): Boolean {
        val normalized = normalizeForMatch(value)
        return normalized.isNotBlank() && normalized in INVALID_METADATA_TITLE_SET
    }

    fun sanitizeMetadataTitle(
        value: String,
        groupId: String,
        logger: ((String) -> Unit)? = null
    ): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return ""
        }
        if (isInvalidMetadataTitle(trimmed)) {
            logger?.invoke("[QrcMetadata] invalid title ignored value=$trimmed groupId=$groupId")
            return ""
        }
        return trimmed
    }

    fun splitArtists(value: String): Set<String> {
        val normalizedValue = value
            .lowercase(Locale.ROOT)
            .replace(BRACKET_CONTENT_REGEX, "")
            .replace(VERSION_WORD_REGEX, "")
        return ARTIST_SPLIT_REGEX.split(normalizedValue)
            .map(::normalizeForMatch)
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun buildSongKey(
        title: String,
        artist: String,
        album: String
    ): String {
        return listOf(
            normalizeForMatch(title),
            normalizeForMatch(artist),
            normalizeForMatch(album)
        ).joinToString("|")
    }

    fun cacheKey(songKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(songKey.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun findGroupById(groupId: String): QrcFileGroup? {
        return scanGroups().firstOrNull { it.groupId == groupId }
    }

    fun buildFingerprint(group: QrcFileGroup): QrcGroupFingerprint {
        return QrcGroupFingerprint(
            qrcLastModified = fileLastModified(group.qrcFile),
            qrcSize = fileSize(group.qrcFile),
            producerLastModified = fileLastModified(group.producerFile),
            producerSize = fileSize(group.producerFile),
            exLastModified = fileLastModified(group.exFile),
            exSize = fileSize(group.exFile),
            translrcLastModified = fileLastModified(group.translrcFile),
            translrcSize = fileSize(group.translrcFile),
            romaqrcLastModified = fileLastModified(group.romaqrcFile),
            romaqrcSize = fileSize(group.romaqrcFile),
            hasQrc = group.qrcFile?.isFile == true,
            hasProducer = group.producerFile?.isFile == true,
            hasEx = group.exFile?.isFile == true,
            hasTranslrc = group.translrcFile?.isFile == true,
            hasRomaqrc = group.romaqrcFile?.isFile == true
        )
    }

    fun parseProducerMetadata(file: File?): Triple<String, String, String> {
        if (file == null || !file.canRead()) {
            return Triple("", "", "")
        }
        val text = try {
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(MAX_PRODUCER_BYTES)
                val count = input.read(bytes)
                if (count <= 0) "" else String(bytes, 0, count, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
        val title = PRODUCER_TITLE_REGEX.find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val artist = PRODUCER_NAME_REGEX.find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        return Triple(title, artist, "")
    }

    fun parseAuxiliaryLyricFile(
        file: File?,
        groupId: String,
        tag: String,
        logger: ((String) -> Unit)? = null
    ): List<QrcAuxiliaryLyricLine> {
        return parseAuxiliaryLyricFileResult(file, groupId, tag, logger).lines
    }

    fun parseAuxiliaryLyricFileResult(
        file: File?,
        groupId: String,
        tag: String,
        logger: ((String) -> Unit)? = null
    ): QrcAuxiliaryParseResult {
        if (file == null || !file.canRead()) {
            return QrcAuxiliaryParseResult()
        }
        logger?.invoke("[$tag] file found groupId=$groupId")
        return try {
            val text = readAuxiliaryLyricText(file)
            val lines = parseAuxiliaryLyricText(text)
            logger?.invoke("[$tag] parsed lines=${lines.size}")
            if (lines.isEmpty()) {
                QrcAuxiliaryParseResult(failed = true, reason = "parse empty")
            } else {
                QrcAuxiliaryParseResult(lines = lines)
            }
        } catch (exception: Exception) {
            logger?.invoke("[$tag] failed reason=${exception.message}")
            QrcAuxiliaryParseResult(
                failed = true,
                reason = exception.message ?: exception::class.java.simpleName
            )
        }
    }

    fun alignAuxiliaryLyrics(
        lines: List<QrcLyricLine>,
        auxiliaryLines: List<QrcAuxiliaryLyricLine>
    ): Map<Int, String> {
        if (lines.isEmpty() || auxiliaryLines.isEmpty()) {
            return emptyMap()
        }
        val matched = mutableMapOf<Int, Pair<Long, String>>()
        auxiliaryLines.forEach { auxiliary ->
            var bestIndex = -1
            var bestDelta = Long.MAX_VALUE
            lines.forEachIndexed { index, line ->
                val delta = kotlin.math.abs(line.timeMs - auxiliary.timeMs)
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestIndex = index
                }
            }
            if (bestIndex >= 0 && bestDelta <= AUXILIARY_ALIGN_TOLERANCE_MS) {
                val existing = matched[bestIndex]
                if (existing == null || bestDelta < existing.first) {
                    matched[bestIndex] = bestDelta to auxiliary.text
                }
            }
        }
        return matched.mapValues { it.value.second }
    }

    private fun parseDecryptedQrc(
        decrypted: String,
        group: QrcFileGroup,
        logger: ((String) -> Unit)? = null
    ): ParsedLyric {
        val lyricContent = extractLyricContent(decrypted)
        val metadata = QRC_METADATA_REGEX.findAll(lyricContent)
            .associate { match ->
                match.groupValues[1].lowercase(Locale.ROOT) to
                    match.groupValues[2].trim()
        }
        val producerMetadata = parseProducerMetadata(group.producerFile)
        val title = sanitizeMetadataTitle(
            metadata["ti"].orEmpty().ifBlank { producerMetadata.first },
            group.groupId,
            logger
        )
        val artist = metadata["ar"].orEmpty().ifBlank { producerMetadata.second }
        val album = metadata["al"].orEmpty().ifBlank { producerMetadata.third }
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
            val parsedBody = parseQrcLineBody(
                lineStartMs = marker.timeMs,
                body = lyricContent.substring(marker.bodyStart, bodyEnd)
            )
            parsedBody.text.takeIf(String::isNotBlank)?.let {
                QrcLyricLine(
                    timeMs = marker.timeMs,
                    text = it,
                    durationMs = marker.durationMs,
                    words = parsedBody.words
                )
            }
        }.distinctBy { it.timeMs to it.text }
            .sortedBy(QrcLyricLine::timeMs)
        val auxiliary = applyAuxiliaryLyricsDetailed(
            groupId = group.groupId,
            lines = baseLines,
            translrcFile = group.translrcFile,
            romaqrcFile = group.romaqrcFile,
            logger = logger
        )

        val songKey = buildSongKey(title, artist, album)
        return ParsedLyric(
            songKey = songKey,
            title = title,
            artist = artist,
            album = album,
            groupId = group.groupId,
            qrcLastModified = group.qrcFile?.lastModified() ?: 0L,
            lines = auxiliary.lines,
            schemaVersion = QRC_CACHE_SCHEMA_V2,
            qrcPath = group.qrcFile?.absolutePath.orEmpty(),
            wordTimingStatus = QrcWordTimingStatus.fromLines(auxiliary.lines),
            rawText = decrypted,
            groupFingerprint = buildFingerprint(group),
            translationParseFailed = auxiliary.translationFailed,
            translationParseFailedReason = auxiliary.translationFailedReason,
            translationSourceLastModified = fileLastModified(group.translrcFile),
            translationSourceSize = fileSize(group.translrcFile),
            romanizationParseFailed = auxiliary.romanizationFailed,
            romanizationParseFailedReason = auxiliary.romanizationFailedReason,
            romanizationSourceLastModified = fileLastModified(group.romaqrcFile),
            romanizationSourceSize = fileSize(group.romaqrcFile)
        )
    }

    fun applyAuxiliaryLyrics(
        groupId: String,
        lines: List<QrcLyricLine>,
        translrcFile: File?,
        romaqrcFile: File?,
        logger: ((String) -> Unit)? = null
    ): List<QrcLyricLine> {
        return applyAuxiliaryLyricsDetailed(
            groupId = groupId,
            lines = lines,
            translrcFile = translrcFile,
            romaqrcFile = romaqrcFile,
            logger = logger
        ).lines
    }

    private fun applyAuxiliaryLyricsDetailed(
        groupId: String,
        lines: List<QrcLyricLine>,
        translrcFile: File?,
        romaqrcFile: File?,
        logger: ((String) -> Unit)? = null
    ): AuxiliaryApplyResult {
        if (lines.isEmpty()) {
            return AuxiliaryApplyResult(lines = lines)
        }
        val translationResult = parseAuxiliaryLyricFileResult(
            translrcFile,
            groupId,
            "TransLyric",
            logger
        )
        val romanizationResult = parseAuxiliaryLyricFileResult(
            romaqrcFile,
            groupId,
            "RomaLyric",
            logger
        )
        val translations = translationResult.lines
        val romanizations = romanizationResult.lines
        val translationMap = alignAuxiliaryLyrics(lines, translations)
        val romanizationMap = alignAuxiliaryLyrics(lines, romanizations)
        if (translations.isNotEmpty()) {
            logger?.invoke(
                "[LyricAlign] translation matched=${translationMap.size} " +
                    "unmatched=${translations.size - translationMap.size}"
            )
        }
        if (romanizations.isNotEmpty()) {
            logger?.invoke(
                "[LyricAlign] romanization matched=${romanizationMap.size} " +
                    "unmatched=${romanizations.size - romanizationMap.size}"
            )
        }
        val translationFailed = translrcFile?.isFile == true &&
            (translationResult.failed || translationMap.isEmpty())
        val romanizationFailed = romaqrcFile?.isFile == true &&
            (romanizationResult.failed || romanizationMap.isEmpty())
        val translationReason = when {
            translationResult.failed -> translationResult.reason
            translrcFile?.isFile == true && translationMap.isEmpty() -> "no aligned lines"
            else -> null
        }
        val romanizationReason = when {
            romanizationResult.failed -> romanizationResult.reason
            romaqrcFile?.isFile == true && romanizationMap.isEmpty() -> "no aligned lines"
            else -> null
        }
        if (translationMap.isEmpty() && romanizationMap.isEmpty()) {
            return AuxiliaryApplyResult(
                lines = lines,
                translationFailed = translationFailed,
                translationFailedReason = translationReason,
                romanizationFailed = romanizationFailed,
                romanizationFailedReason = romanizationReason
            )
        }
        return AuxiliaryApplyResult(
            lines = lines.mapIndexed { index, line ->
                line.copy(
                    translation = translationMap[index],
                    romanization = romanizationMap[index]
                )
            },
            translationFailed = translationFailed,
            translationFailedReason = translationReason,
            romanizationFailed = romanizationFailed,
            romanizationFailedReason = romanizationReason
        )
    }

    private fun isEncryptedQrcHex(value: String): Boolean {
        return value.length >= MIN_HEX_LENGTH &&
            value.length % 16 == 0 &&
            HEX_REGEX.matches(value)
    }

    private fun readAuxiliaryLyricText(file: File): String {
        val bytes = file.readBytes()
        val ascii = bytes.toString(Charsets.US_ASCII).filterNot(Char::isWhitespace)
        if (isEncryptedQrcHex(ascii)) {
            QrcDecrypter.decrypt(ascii)?.let { return it }
        }
        val charsets = listOf(
            Charsets.UTF_8,
            Charset.forName("GB18030"),
            Charset.forName("GBK")
        )
        return charsets
            .asSequence()
            .mapNotNull { charset ->
                runCatching { String(bytes, charset) }.getOrNull()
            }
            .firstOrNull { it.isNotBlank() }
            ?: ""
    }

    private fun parseAuxiliaryLyricText(text: String): List<QrcAuxiliaryLyricLine> {
        val content = extractLyricContent(text).ifBlank { text }
        val qrcLines = parseQrcAuxiliaryLines(content)
        if (qrcLines.isNotEmpty()) {
            return qrcLines
        }
        return parseLrcAuxiliaryLines(content)
    }

    private fun parseQrcAuxiliaryLines(content: String): List<QrcAuxiliaryLyricLine> {
        val markers = QRC_LINE_REGEX.findAll(content).map { match ->
            QrcLineMarker(
                timeMs = match.groupValues[1].toLongOrNull() ?: 0L,
                durationMs = match.groupValues[2].toLongOrNull() ?: 0L,
                bodyStart = match.range.last + 1,
                markerStart = match.range.first
            )
        }.toList()
        return markers.mapIndexedNotNull { index, marker ->
            val bodyEnd = markers.getOrNull(index + 1)?.markerStart ?: content.length
            val body = content.substring(marker.bodyStart, bodyEnd)
            val parsedBody = parseQrcLineBody(marker.timeMs, body)
            val text = cleanAuxiliaryText(parsedBody.text)
            text.takeIf(String::isNotBlank)?.let {
                QrcAuxiliaryLyricLine(marker.timeMs, it)
            }
        }.distinctBy { it.timeMs to it.text }
            .sortedBy(QrcAuxiliaryLyricLine::timeMs)
            .toList()
    }

    private fun parseLrcAuxiliaryLines(content: String): List<QrcAuxiliaryLyricLine> {
        return LRC_LINE_REGEX.findAll(content).mapNotNull { match ->
            val minute = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val second = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val fraction = match.groupValues[3].orEmpty()
            val fractionMs = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLongOrNull()?.times(100) ?: 0L
                2 -> fraction.toLongOrNull()?.times(10) ?: 0L
                else -> fraction.take(3).toLongOrNull() ?: 0L
            }
            val text = cleanAuxiliaryText(match.groupValues[4])
            if (text.isBlank()) {
                null
            } else {
                QrcAuxiliaryLyricLine(
                    timeMs = minute * 60_000L + second * 1_000L + fractionMs,
                    text = text
                )
            }
        }.distinctBy { it.timeMs to it.text }
            .sortedBy(QrcAuxiliaryLyricLine::timeMs)
            .toList()
    }

    private fun cleanAuxiliaryText(value: String): String {
        return value
            .replace(QRC_WORD_TIME_REGEX, "")
            .replace(QRC_METADATA_REGEX, "")
            .trim()
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

    private fun extractLyricContent(text: String): String {
        val contentStart = text.indexOf(LYRIC_CONTENT_PREFIX)
            .takeIf { it >= 0 }
            ?.plus(LYRIC_CONTENT_PREFIX.length)
            ?: return text
        val close = LYRIC_CONTENT_CLOSE_REGEX.find(text, contentStart)?.range?.first
            ?: text.lastIndexOf('"').takeIf { it > contentStart }
            ?: return ""
        return unescapeXml(text.substring(contentStart, close))
    }

    fun parseQrcLineBody(lineStartMs: Long, body: String): QrcParsedLineBody {
        val matches = QRC_WORD_TIME_REGEX.findAll(body).toList()
        val text = body.replace(QRC_WORD_TIME_REGEX, "").trim()
        if (matches.isEmpty()) {
            return QrcParsedLineBody(text = text, words = emptyList())
        }

        val words = matches.mapIndexedNotNull { index, match ->
            val relativeStartMs = match.groupValues.getOrNull(1)?.toLongOrNull()
                ?: return@mapIndexedNotNull null
            val durationMs = match.groupValues.getOrNull(2)?.toLongOrNull()
                ?: return@mapIndexedNotNull null
            val textStart = match.range.last + 1
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: body.length
            val wordText = body.substring(textStart, textEnd)
                .replace(QRC_WORD_TIME_REGEX, "")
            if (wordText.isBlank()) {
                null
            } else {
                QrcLyricWord(
                    startMs = lineStartMs + relativeStartMs,
                    durationMs = durationMs,
                    text = wordText
                )
            }
        }
        return QrcParsedLineBody(text = text, words = words)
    }

    private data class QrcLineMarker(
        val timeMs: Long,
        val durationMs: Long,
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

    private data class AuxiliaryApplyResult(
        val lines: List<QrcLyricLine>,
        val translationFailed: Boolean = false,
        val translationFailedReason: String? = null,
        val romanizationFailed: Boolean = false,
        val romanizationFailedReason: String? = null
    )

    private fun fileLastModified(file: File?): Long {
        return if (file?.isFile == true) file.lastModified() else 0L
    }

    private fun fileSize(file: File?): Long {
        return if (file?.isFile == true) file.length() else 0L
    }

    private const val MAX_PRODUCER_BYTES = 8 * 1024
    private const val MIN_HEX_LENGTH = 128
    private const val AUXILIARY_ALIGN_TOLERANCE_MS = 800L
    private val GROUP_FILE_REGEX =
        Regex("""^(-?\d+)\.(qrc|producer|ex|translrc|romaqrc)$""")
    private const val LYRIC_CONTENT_PREFIX = "LyricContent=\""
    private val LYRIC_CONTENT_CLOSE_REGEX =
        Regex(""""\\s*/?>""")
    private val QRC_METADATA_REGEX =
        Regex("""\[(\w+)\s*:\s*([^]]*)]""")
    private val QRC_LINE_REGEX =
        Regex("""\[(\d+)\s*,\s*(\d+)]""")
    private val QRC_WORD_TIME_REGEX =
        Regex("""\((\d+)\s*,\s*(\d+)\)""")
    private val LRC_LINE_REGEX =
        Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?](.*)""")
    private val HEX_REGEX = Regex("""[0-9A-Fa-f]+""")
    private val BRACKET_CONTENT_REGEX =
        Regex("""\([^)]*\)|（[^）]*）|\[[^]]*]|\【[^】]*】""")
    private val MATCH_PUNCTUATION_REGEX =
        Regex("""[-_.·・/\\:，,、&+]""")
    private val VERSION_WORD_REGEX =
        Regex(
            """\b(live|remaster|remastered|version|demo|remix|cover)\b|合唱版|独唱版|现场版|完整版|原版|新版|dj版|伴奏|纯音乐|翻唱|电视剧|电影|片尾曲|主题曲""",
            RegexOption.IGNORE_CASE
        )
    private val ARTIST_SPLIT_REGEX =
        Regex("""\s+|/|、|,|&|\+|feat\.|ft\.|和|与""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val INVALID_METADATA_TITLE_SET = setOf(
        "演唱",
        "歌手",
        "作词",
        "作曲",
        "编曲",
        "制作",
        "制作人",
        "混音",
        "录音",
        "歌词",
        "歌曲",
        "music",
        "singer",
        "artist",
        "composer",
        "lyricist"
    ).map(::normalizeForMatch).toSet()
    private val PRODUCER_TITLE_REGEX =
        Regex(""""Title"\s*:\s*"([^"]*)"""")
    private val PRODUCER_NAME_REGEX =
        Regex(""""Name"\s*:\s*"([^"]*)"""")
}
