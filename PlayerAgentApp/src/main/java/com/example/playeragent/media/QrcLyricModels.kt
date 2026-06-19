package com.example.playeragent.media

import android.content.Context
import android.os.Environment
import io.github.proify.qrckit.decrypt.QrcDecrypter
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class QrcLyricLine(
    val timeMs: Long,
    val text: String,
    val durationMs: Long = 0L,
    val words: List<QrcLyricWord> = emptyList()
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

data class ParsedLyric(
    val songKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val groupId: String,
    val qrcLastModified: Long,
    val lines: List<QrcLyricLine>
)

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

    fun decryptAndParseGroup(group: QrcFileGroup): ParsedLyric? {
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
            group = group
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

    private fun parseDecryptedQrc(
        decrypted: String,
        group: QrcFileGroup
    ): ParsedLyric {
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
        val producerMetadata = parseProducerMetadata(group.producerFile)
        val title = metadata["ti"].orEmpty().ifBlank { producerMetadata.first }
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
        val lines = markers.mapIndexedNotNull { index, marker ->
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

        val songKey = buildSongKey(title, artist, album)
        return ParsedLyric(
            songKey = songKey,
            title = title,
            artist = artist,
            album = album,
            groupId = group.groupId,
            qrcLastModified = group.qrcFile?.lastModified() ?: 0L,
            lines = lines
        )
    }

    private fun isEncryptedQrcHex(value: String): Boolean {
        return value.length >= MIN_HEX_LENGTH &&
            value.length % 16 == 0 &&
            HEX_REGEX.matches(value)
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
        Regex("""\((\d+)\s*,\s*(\d+)\)""")
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
    private val PRODUCER_TITLE_REGEX =
        Regex(""""Title"\s*:\s*"([^"]*)"""")
    private val PRODUCER_NAME_REGEX =
        Regex(""""Name"\s*:\s*"([^"]*)"""")
}
