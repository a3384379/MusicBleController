package com.example.playeragent.media

import android.content.Context
import android.os.Environment
import com.example.playeragent.logging.LogConfig
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

class LyricManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private var cachedKey: String? = null
    private var cachedLines: List<LyricLine> = emptyList()
    private var lastLoggedLine: String? = null

    fun scanLrcFiles(title: String, artist: String, album: String) {
        logger("[LyricScan] start")

        val foundFiles = mutableListOf<File>()
        val visitedCount = intArrayOf(0)
        val scanDirectories = scanDirectories()

        scanDirectories.forEach { directory ->
            logger("[LyricScan] scan directory=${directory.absolutePath}")
            logger("[LyricScan] directory exists=${directory.exists()}")

            try {
                scanDirectory(
                    directory = directory,
                    depth = 0,
                    foundFiles = foundFiles,
                    visitedCount = visitedCount
                )
            } catch (exception: Exception) {
                logger("[LyricScan] scan failed: ${directory.absolutePath} ${exception.message}")
            }
        }

        logger("[LyricScan] total lrc files=${foundFiles.size}")
        logger("[LyricScan] current title=$title")
        logger("[LyricScan] current artist=$artist")
        logger("[LyricScan] current album=$album")

        val candidates = foundFiles
            .map { file -> LyricCandidate(file = file, score = calculateScore(file, title, artist)) }
            .sortedByDescending { it.score }

        candidates.take(10).forEach { candidate ->
            logger("[LyricScan] candidate score=${candidate.score} file=${candidate.file.absolutePath}")
        }

        val bestMatch = candidates.firstOrNull()
        if (bestMatch == null) {
            logger("[LyricScan] best match=")
            return
        }

        logger("[LyricScan] best match=${bestMatch.file.absolutePath}")
        val parsedLines = parseLrc(bestMatch.file)
        if (parsedLines.isNotEmpty()) {
            scannedCache = ScannedLyricCache(
                title = title.trim(),
                artist = artist.trim(),
                file = bestMatch.file,
                lines = parsedLines
            )
            cachedKey = lyricKey(title, artist)
            cachedLines = parsedLines
            lastLoggedLine = null
            logger("[LyricScan] cached best match lyrics")
        }
    }

    fun loadLyric(title: String, artist: String) {
        val key = lyricKey(title, artist)
        if (key == cachedKey) {
            if (cachedLines.isEmpty()) {
                applyScannedCacheIfAvailable(title, artist)
            }
            return
        }

        cachedKey = key
        cachedLines = emptyList()
        lastLoggedLine = null

        if (applyScannedCacheIfAvailable(title, artist)) {
            return
        }

        logger("[Lyric] load title=$title artist=$artist")
        logger("[Lyric] title=$title")
        logger("[Lyric] artist=$artist")

        val directory = resolveLyricDirectory()
        logger("[Lyric] lyric directory=${directory.absolutePath}")
        logger("[Lyric] directory exists=${directory.exists()}")

        val matchedFile = findMatchedFile(directory, title, artist)
        if (matchedFile == null) {
            logger("[Lyric] no lyric file matched")
            return
        }

        logger("[Lyric] matched file=${matchedFile.absolutePath}")
        cachedLines = parseLrc(matchedFile)
        logger("[Lyric] parsed lines count=${cachedLines.size}")
    }

    fun getCurrentLine(positionMs: Long): String {
        val safePosition = positionMs.coerceAtLeast(0L)
        val lines = cachedLines
        if (lines.isEmpty()) {
            return ""
        }

        var currentLine = ""
        for (line in lines) {
            if (line.timeMs <= safePosition) {
                currentLine = line.text
            } else {
                break
            }
        }

        if (currentLine != lastLoggedLine) {
            if (LogConfig.DEBUG_VERBOSE_LOG) {
                logger("[Lyric] current line=$currentLine")
            }
            lastLoggedLine = currentLine
        }

        return currentLine
    }

    private fun resolveLyricDirectory(): File {
        val publicDirectory = File(
            Environment.getExternalStorageDirectory(),
            "Music/Lyrics"
        )
        val appDirectory = File(
            appContext.getExternalFilesDir(null),
            "Lyrics"
        )

        logger("[Lyric] app lyric directory=${appDirectory.absolutePath}")

        if (!publicDirectory.exists()) {
            val created = publicDirectory.mkdirs()
            logger("[Lyric] create public lyric directory result=$created")
        }

        if (publicDirectory.exists() && publicDirectory.canRead()) {
            return publicDirectory
        }

        if (!appDirectory.exists()) {
            val created = appDirectory.mkdirs()
            logger("[Lyric] create app lyric directory result=$created")
        }
        return appDirectory
    }

    private fun findMatchedFile(directory: File, title: String, artist: String): File? {
        val files = try {
            directory.listFiles { file ->
                file.isFile && file.extension.equals("lrc", ignoreCase = true)
            }?.toList().orEmpty()
        } catch (exception: Exception) {
            logger("[Lyric] list lyric files failed: ${exception.message}")
            emptyList()
        }

        val cleanTitle = sanitizeFileName(title)
        val cleanArtist = sanitizeFileName(artist)
        logger("[Lyric] cleanTitle=$cleanTitle")
        logger("[Lyric] cleanArtist=$cleanArtist")

        val candidates = listOf(
            "$cleanTitle - $cleanArtist.lrc",
            "$cleanArtist - $cleanTitle.lrc",
            "$cleanTitle.lrc"
        )

        files.forEach { file ->
            logger("[Lyric] file found: ${file.name}")
        }

        candidates.forEach { candidate ->
            logger("[Lyric] candidate=$candidate")
        }

        if (files.isEmpty()) {
            return null
        }

        for (candidate in candidates) {
            val normalizedCandidate = candidate.lowercase(Locale.ROOT)
            val exactMatch = files.firstOrNull {
                it.name.lowercase(Locale.ROOT) == normalizedCandidate
            }
            if (exactMatch != null) {
                return exactMatch
            }
        }

        if (cleanTitle.isBlank()) {
            return null
        }

        val lowerTitle = cleanTitle.lowercase(Locale.ROOT)
        return files.firstOrNull {
            it.nameWithoutExtension.lowercase(Locale.ROOT).contains(lowerTitle)
        }
    }

    fun parseLrc(file: File): List<LyricLine> {
        logger("[LyricParse] file=${file.absolutePath}")
        logger("[LyricParse] size=${file.length()}")

        val bytes = try {
            file.readBytes()
        } catch (exception: Exception) {
            logger("[LyricParse] read failed: ${exception.message}")
            return emptyList()
        }

        for (decodeAttempt in decodeAttempts()) {
            logger("[LyricParse] try charset=${decodeAttempt.name}")

            val content = try {
                String(bytes, decodeAttempt.charset)
            } catch (exception: Exception) {
                logger("[LyricParse] read failed: ${exception.message}")
                continue
            }

            val normalizedContent = if (decodeAttempt.stripBom) {
                content.removePrefix("\uFEFF")
            } else {
                content
            }

            val parsedLines = parseLrcContent(normalizedContent)
            logger("[LyricParse] parsed count=${parsedLines.size}")

            if (parsedLines.isNotEmpty()) {
                logger("[LyricParse] selected charset=${decodeAttempt.name}")
                return parsedLines.sortedBy { it.timeMs }
            }
        }

        logger("[LyricParse] no valid timestamp found")
        return emptyList()
    }

    private fun parseLrcContent(content: String): List<LyricLine> {
        val parsedLines = mutableListOf<LyricLine>()

        content.lineSequence().forEachIndexed { index, rawLine ->
            if (index < RAW_LINE_LOG_LIMIT) {
                logger("[LyricParse] raw line ${index + 1}=$rawLine")
            }

            val matches = TIME_TAG_REGEX.findAll(rawLine).toList()
            if (matches.isEmpty()) {
                return@forEachIndexed
            }

            val textStart = matches.last().range.last + 1
            val text = rawLine.substring(textStart).trim()

            for (match in matches) {
                val timeMs = parseTimeTag(match)
                if (timeMs != null) {
                    logger("[LyricParse] parsed timeMs=$timeMs text=$text")
                    parsedLines += LyricLine(timeMs, text)
                }
            }
        }

        return parsedLines
    }

    private fun scanDirectories(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOfNotNull(
            File(root, "Music"),
            File(root, "Music/Lyrics"),
            File(root, "Download"),
            File(root, "Downloads"),
            File(root, "Lyrics"),
            File(root, "QQMusic"),
            File(root, "Android/data"),
            appContext.getExternalFilesDir("Lyrics")
        )
    }

    private fun scanDirectory(
        directory: File,
        depth: Int,
        foundFiles: MutableList<File>,
        visitedCount: IntArray
    ) {
        if (depth > MAX_SCAN_DEPTH || foundFiles.size >= MAX_SCAN_FILES) {
            return
        }

        if (!directory.exists() || !directory.isDirectory) {
            return
        }

        val children = try {
            directory.listFiles()
        } catch (exception: Exception) {
            logger("[LyricScan] access failed: ${directory.absolutePath} ${exception.message}")
            logger("[LyricScan] public storage access denied, use app private Lyrics folder instead")
            return
        }

        if (children == null) {
            logger("[LyricScan] access failed: ${directory.absolutePath} listFiles returned null")
            logger("[LyricScan] public storage access denied, use app private Lyrics folder instead")
            return
        }

        for (child in children) {
            if (foundFiles.size >= MAX_SCAN_FILES || visitedCount[0] >= MAX_SCAN_FILES) {
                return
            }

            visitedCount[0] += 1

            if (child.isFile && child.extension.equals("lrc", ignoreCase = true)) {
                logger("[LyricScan] found: ${child.absolutePath}")
                foundFiles += child
            } else if (child.isDirectory) {
                scanDirectory(child, depth + 1, foundFiles, visitedCount)
            }
        }
    }

    private fun calculateScore(file: File, title: String, artist: String): Int {
        val cleanTitle = sanitizeFileName(title)
        val cleanArtist = sanitizeFileName(artist)
        val fileName = file.nameWithoutExtension.lowercase(Locale.ROOT)
        val lowerTitle = cleanTitle.lowercase(Locale.ROOT)
        val lowerArtist = cleanArtist.lowercase(Locale.ROOT)
        var score = 0

        if (lowerTitle.isNotBlank() && fileName.contains(lowerTitle)) {
            score += 10
        }
        if (lowerArtist.isNotBlank() && fileName.contains(lowerArtist)) {
            score += 5
        }

        val headerText = readHeaderText(file)
        if (lowerTitle.isNotBlank() && headerText.contains(lowerTitle)) {
            score += 10
        }
        if (lowerArtist.isNotBlank() && headerText.contains(lowerArtist)) {
            score += 5
        }

        val tagTitle = TI_TAG_REGEX.find(headerText)?.groupValues?.getOrNull(1).orEmpty()
        val tagArtist = AR_TAG_REGEX.find(headerText)?.groupValues?.getOrNull(1).orEmpty()
        if (tagTitle.isNotBlank()) {
            logger("[LyricScan] tag title=$tagTitle file=${file.absolutePath}")
        }
        if (tagArtist.isNotBlank()) {
            logger("[LyricScan] tag artist=$tagArtist file=${file.absolutePath}")
        }

        return score
    }

    private fun readHeaderText(file: File): String {
        val bytes = try {
            file.readBytes()
        } catch (exception: Exception) {
            logger("[LyricScan] read header failed: ${file.absolutePath} ${exception.message}")
            return ""
        }

        for (decodeAttempt in decodeAttempts()) {
            val content = try {
                String(bytes, decodeAttempt.charset)
            } catch (_: Exception) {
                continue
            }

            val normalizedContent = if (decodeAttempt.stripBom) {
                content.removePrefix("\uFEFF")
            } else {
                content
            }

            return normalizedContent
                .lineSequence()
                .take(30)
                .joinToString("\n")
                .lowercase(Locale.ROOT)
        }

        return ""
    }

    private fun applyScannedCacheIfAvailable(title: String, artist: String): Boolean {
        val cache = scannedCache ?: return false
        if (lyricKey(cache.title, cache.artist) != lyricKey(title, artist)) {
            return false
        }

        cachedKey = lyricKey(title, artist)
        cachedLines = cache.lines
        lastLoggedLine = null
        logger("[Lyric] use scanned cached file=${cache.file.absolutePath}")
        logger("[Lyric] parsed lines count=${cachedLines.size}")
        return cachedLines.isNotEmpty()
    }

    private fun parseTimeTag(match: MatchResult): Long? {
        val minutes = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return null
        val seconds = match.groupValues.getOrNull(2)?.toLongOrNull() ?: return null
        val fraction = match.groupValues.getOrNull(3).orEmpty()
        val milliseconds = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLongOrNull()?.times(100L)
            2 -> fraction.toLongOrNull()?.times(10L)
            else -> fraction.take(3).toLongOrNull()
        } ?: 0L

        return minutes * 60_000L + seconds * 1000L + milliseconds
    }

    private fun sanitizeFileName(value: String): String {
        return value
            .replace(INVALID_FILE_NAME_CHARS_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun lyricKey(title: String, artist: String): String {
        return "${title.trim()}|${artist.trim()}"
    }

    data class LyricLine(
        val timeMs: Long,
        val text: String
    )

    private data class LyricCandidate(
        val file: File,
        val score: Int
    )

    private data class ScannedLyricCache(
        val title: String,
        val artist: String,
        val file: File,
        val lines: List<LyricLine>
    )

    private data class DecodeAttempt(
        val name: String,
        val charset: Charset,
        val stripBom: Boolean = false
    )

    companion object {
        private val TIME_TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:(?:\.|:)(\d{1,3}))?]""")
        private val TI_TAG_REGEX = Regex("""\[ti:(.*?)]""", RegexOption.IGNORE_CASE)
        private val AR_TAG_REGEX = Regex("""\[ar:(.*?)]""", RegexOption.IGNORE_CASE)
        private val INVALID_FILE_NAME_CHARS_REGEX = Regex("""[\\/:*?"<>|]""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private const val MAX_SCAN_FILES = 300
        private const val MAX_SCAN_DEPTH = 5
        private const val RAW_LINE_LOG_LIMIT = 20
        @Volatile
        private var scannedCache: ScannedLyricCache? = null

        private fun decodeAttempts(): List<DecodeAttempt> {
            return listOf(
                DecodeAttempt("UTF-8", Charsets.UTF_8),
                DecodeAttempt("UTF-8-BOM", Charsets.UTF_8, stripBom = true),
                DecodeAttempt("GBK", Charset.forName("GBK")),
                DecodeAttempt("GB2312", Charset.forName("GB2312"))
            )
        }
    }
}
