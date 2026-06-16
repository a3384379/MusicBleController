package com.example.playeragent.media

import android.content.Context
import android.os.Environment
import android.util.Base64
import io.github.proify.qrckit.decrypt.QrcDecrypter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

class QrcDumpManager(
    context: Context,
    logger: (String) -> Unit,
    private val summaryLogger: (String) -> Unit = logger
) {

    private val appContext = context.applicationContext
    private val output = LimitedLogger(logger, MAX_TOTAL_OUTPUT_CHARACTERS)
    private val loggedKeywordHits = mutableSetOf<String>()

    fun dumpFirstQrc() {
        output.log("[QrcDump] ===== START =====")

        val primaryDirectory = File(
            Environment.getExternalStorageDirectory(),
            "QQMusic/qrc"
        )
        val primaryFiles = scanDirectory(primaryDirectory)
        val candidateFiles = if (primaryDirectory.canRead() &&
            primaryFiles.isNotEmpty()
        ) {
            primaryFiles
        } else {
            val fallbackDirectory = File(
                appContext.getExternalFilesDir(null),
                "Lyrics"
            )
            scanDirectory(fallbackDirectory)
        }

        candidateFiles.take(FILE_LIST_LIMIT).forEach { file ->
            output.log(
                "[QrcDump] file=${file.name} size=${file.length()}"
            )
        }

        val selected = selectFile(candidateFiles)
        if (selected == null) {
            output.log("[QrcDump] no qrc-like file found")
            summaryLogger("[QrcDump] result=no qrc-like file found")
            output.log("[QrcDump] ===== END =====")
            return
        }

        output.log("[QrcDump] selected file=${selected.absolutePath}")
        output.log("[QrcDump] size=${selected.length()}")

        val fileBytes = try {
            selected.readBytes()
        } catch (exception: Exception) {
            output.log("[QrcDump] read failed: ${exception.message}")
            summaryLogger(
                "[QrcDump] result=read failed file=${selected.name} " +
                    "reason=${exception.message}"
            )
            output.log("[QrcDump] ===== END =====")
            return
        }

        val header = fileBytes.copyOfRange(
            0,
            minOf(fileBytes.size, HEADER_BYTE_LIMIT)
        )
        logHexBlock(header, source = "file")
        logAsciiBlock(header)

        val decodedTexts = decodeTextPreviews(fileBytes, source = "file")
        val type = detectType(fileBytes, decodedTexts.values)
        output.log("[QrcDump] type=${type.label}")

        if (type != FileType.ENCRYPTED_QRC_HEX) {
            decodedTexts.forEach { (charsetName, text) ->
                logTextBlock(
                    charsetName = charsetName,
                    source = "file",
                    text = text
                )
            }
        }

        val decodedType = when (type) {
            FileType.ZIP -> inspectZip(fileBytes, source = "file")
            FileType.ZLIB -> inspectZlib(fileBytes, source = "file")
            FileType.ENCRYPTED_QRC_HEX ->
                inspectEncryptedQrc(fileBytes, selected.name)
            FileType.POSSIBLE_BASE64 ->
                inspectBase64(fileBytes, source = "file")

            else -> null
        }

        summaryLogger(
            "[QrcDump] result file=${selected.name} " +
                "size=${selected.length()} type=${type.label}" +
                (decodedType?.let { " decodedType=$it" } ?: "")
        )
        output.log("[QrcDump] ===== END =====")
    }

    private fun scanDirectory(directory: File): List<File> {
        output.log("[QrcDump] scan dir=${directory.absolutePath}")
        output.log("[QrcDump] exists=${directory.exists()}")

        val files = if (directory.exists() &&
            directory.isDirectory &&
            directory.canRead()
        ) {
            try {
                directory.listFiles()
                    ?.filter { it.isFile && isQrcLikeFile(it) }
                    ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                    .orEmpty()
            } catch (exception: Exception) {
                output.log(
                    "[QrcDump] scan failed=${exception.message}"
                )
                emptyList()
            }
        } else {
            emptyList()
        }

        output.log("[QrcDump] file count=${files.size}")
        return files
    }

    private fun isQrcLikeFile(file: File): Boolean {
        return file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS
    }

    private fun selectFile(files: List<File>): File? {
        EXTENSION_PRIORITY.forEach { extension ->
            files.firstOrNull {
                it.extension.equals(extension, ignoreCase = true)
            }?.let { return it }
        }
        return null
    }

    private fun logHexBlock(bytes: ByteArray, source: String) {
        output.log("[QrcDump] ===== HEX START =====")
        output.log(
            "[QrcDump] source=$source " +
                bytes.joinToString(" ") {
                    "%02X".format(it.toInt() and 0xFF)
                }
        )
        output.log("[QrcDump] ===== HEX END =====")
    }

    private fun logAsciiBlock(bytes: ByteArray) {
        val preview = buildString(bytes.size) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(if (value in 32..126) value.toChar() else '.')
            }
        }
        output.log("[QrcDump] ===== ASCII START =====")
        output.log("[QrcDump] $preview")
        output.log("[QrcDump] ===== ASCII END =====")
    }

    private fun decodeTextPreviews(
        bytes: ByteArray,
        source: String
    ): LinkedHashMap<String, String> {
        val previews = linkedMapOf<String, String>()
        CHARSETS.forEach { charsetSpec ->
            try {
                val text = decodeStrict(bytes, charsetSpec.charset)
                    .take(TEXT_PREVIEW_CHARACTER_LIMIT)
                previews[charsetSpec.label] = text
            } catch (exception: Exception) {
                output.log(
                    "[QrcDump] charset=${charsetSpec.label} failed: " +
                        "${exception.message ?: exception.javaClass.simpleName} " +
                        "source=$source"
                )
            }
        }
        return previews
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String {
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun logTextBlock(
        charsetName: String,
        source: String,
        text: String
    ) {
        detectKeywords(text, source)
        output.log(
            "[QrcDump] ===== TEXT $charsetName START ===== source=$source"
        )
        output.log(text.take(TEXT_PREVIEW_CHARACTER_LIMIT))
        output.log(
            "[QrcDump] ===== TEXT $charsetName END ===== source=$source"
        )
    }

    private fun detectKeywords(
        text: String,
        source: String
    ) {
        KEYWORDS.forEach { keyword ->
            val normalizedKeyword = keyword.lowercase(Locale.ROOT)
            if (text.contains(keyword, ignoreCase = true) &&
                loggedKeywordHits.add(normalizedKeyword)
            ) {
                output.log(
                    "[QrcDump] keyword hit=$keyword source=$source"
                )
            }
        }
    }

    private fun detectType(
        bytes: ByteArray,
        texts: Collection<String>
    ): FileType {
        if (isZip(bytes)) {
            return FileType.ZIP
        }
        if (isZlib(bytes)) {
            return FileType.ZLIB
        }
        if (isEncryptedQrcHex(bytes)) {
            return FileType.ENCRYPTED_QRC_HEX
        }

        val combinedText = texts.joinToString("\n")
        if (XML_MARKERS.any {
                combinedText.contains(it, ignoreCase = true)
            }
        ) {
            return FileType.XML_OR_QRC_XML
        }
        if (LRC_MARKERS.any { combinedText.contains(it) }) {
            return FileType.LRC_TEXT
        }
        if (findBase64Candidate(texts) != null) {
            return FileType.POSSIBLE_BASE64
        }
        return FileType.UNKNOWN_OR_ENCRYPTED
    }

    private fun inspectEncryptedQrc(
        fileBytes: ByteArray,
        fileName: String
    ): String {
        val encrypted = String(fileBytes, Charsets.US_ASCII)
            .filterNot(Char::isWhitespace)
        output.log(
            "[QrcDump] encrypted qrc hex characters=${encrypted.length}"
        )

        val decrypted = QrcDecrypter.decrypt(encrypted)
        if (decrypted.isNullOrBlank()) {
            output.log("[QrcDump] qrc decrypt failed")
            return "QRC_DECRYPT_FAILED"
        }

        val metadata = QRC_METADATA_REGEX.findAll(decrypted)
            .associate { match ->
                match.groupValues[1] to unescapeXml(match.groupValues[2])
            }
        val lyricContent = QRC_LYRIC_CONTENT_REGEX.find(decrypted)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::unescapeXml)
        val lines = lyricContent
            ?.let { content ->
                QRC_LINE_REGEX.findAll(content).map { match ->
                    val start = match.groupValues[1].toLongOrNull() ?: 0L
                    val duration = match.groupValues[2].toLongOrNull() ?: 0L
                    val bodyStart = match.range.last + 1
                    QrcLineMarker(start, duration, bodyStart)
                }.toList()
            }
            .orEmpty()

        output.log(
            "[QrcDump] qrc decrypt success xmlChars=${decrypted.length}"
        )
        output.log(
            "[QrcDump] metadata title=${metadata["ti"].orEmpty()} " +
                "artist=${metadata["ar"].orEmpty()} " +
                "album=${metadata["al"].orEmpty()}"
        )
        output.log("[QrcDump] parsed lyric lines=${lines.size}")
        output.log("[QrcDump] ===== QRC XML START =====")
        output.log(decrypted.take(TEXT_PREVIEW_CHARACTER_LIMIT))
        output.log("[QrcDump] ===== QRC XML END =====")

        if (lyricContent != null) {
            lines.take(QRC_LINE_PREVIEW_LIMIT).forEachIndexed { index, line ->
                val bodyEnd = lines.getOrNull(index + 1)?.bodyStart
                    ?: lyricContent.length
                val body = lyricContent
                    .substring(line.bodyStart, bodyEnd)
                    .trim()
                val text = body.replace(QRC_WORD_TIME_REGEX, "")
                output.log(
                    "[QrcDump] line start=${line.startMs} " +
                        "duration=${line.durationMs} text=$text"
                )
            }
        }

        summaryLogger(
            "[QrcDump] decrypt success file=$fileName " +
                "title=${metadata["ti"].orEmpty()} " +
                "artist=${metadata["ar"].orEmpty()} lines=${lines.size}"
        )
        return "QRC_XML"
    }

    private fun inspectZlib(bytes: ByteArray, source: String): String {
        try {
            val inflated = InflaterInputStream(
                ByteArrayInputStream(bytes)
            ).use { input ->
                readLimitedBytes(input::read)
            }
            output.log(
                "[QrcDump] zlib inflate success bytes=${inflated.size}"
            )
            val texts = decodeSelectedTextPreviews(
                inflated,
                source = "$source/zlib"
            )
            texts.forEach { (charsetName, text) ->
                output.log(
                    "[QrcDump] ===== INFLATED TEXT $charsetName START ====="
                )
                detectKeywords(text, "$source/zlib")
                output.log(text.take(TEXT_PREVIEW_CHARACTER_LIMIT))
                output.log(
                    "[QrcDump] ===== INFLATED TEXT $charsetName END ====="
                )
            }
            return "ZLIB"
        } catch (exception: Exception) {
            output.log(
                "[QrcDump] zlib inflate failed: ${exception.message}"
            )
            return "ZLIB_INFLATE_FAILED"
        }
    }

    private fun inspectZip(bytes: ByteArray, source: String): String {
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entryCount = 0
                while (entryCount < MAX_ZIP_ENTRIES) {
                    val entry = zip.nextEntry ?: break
                    output.log(
                        "[QrcDump] zip entry name=${entry.name} " +
                            "size=${entry.size}"
                    )
                    if (!entry.isDirectory) {
                        val entryBytes = readLimitedBytes(zip::read)
                        val entrySource = "$source/zip:${entry.name}"
                        val texts = decodeSelectedTextPreviews(
                            entryBytes,
                            entrySource
                        )
                        texts.forEach { (charsetName, text) ->
                            logTextBlock(
                                charsetName = charsetName,
                                source = entrySource,
                                text = text
                            )
                        }
                    }
                    zip.closeEntry()
                    entryCount += 1
                }
            }
            return "ZIP"
        } catch (exception: Exception) {
            output.log("[QrcDump] zip read failed: ${exception.message}")
            return "ZIP_READ_FAILED"
        }
    }

    private fun inspectBase64(
        fileBytes: ByteArray,
        source: String
    ): String {
        val candidate = String(fileBytes, Charsets.US_ASCII)
            .filterNot { character ->
                character == ' ' ||
                    character == '\n' ||
                    character == '\r' ||
                    character == '\t'
            }
        try {
            val decoded = Base64.decode(candidate, Base64.DEFAULT)
            output.log(
                "[QrcDump] base64 decode success bytes=${decoded.size}"
            )
            val decodedHeader = decoded.copyOfRange(
                0,
                minOf(decoded.size, HEADER_BYTE_LIMIT)
            )
            logDecodedHexBlock(decodedHeader)
            logDecodedAsciiBlock(decodedHeader)

            val decodedTexts = decodeSelectedTextPreviews(
                decoded,
                source = "$source/base64"
            )
            val decodedType = detectDecodedType(
                bytes = decoded,
                texts = decodedTexts.values
            )
            output.log("[QrcDump] decoded type=$decodedType")

            return when (decodedType) {
                "ZIP" -> inspectZip(
                    decoded,
                    source = "$source/base64"
                )
                "ZLIB" -> inspectZlib(
                    decoded,
                    source = "$source/base64"
                )
                "TEXT/XML/LRC" -> {
                    decodedTexts.forEach { (charsetName, text) ->
                        logTextBlock(
                            charsetName = charsetName,
                            source = "$source/base64",
                            text = text
                        )
                    }
                    decodedType
                }
                else -> decodedType
            }
        } catch (exception: Exception) {
            output.log(
                "[QrcDump] base64 decode failed: ${exception.message}"
            )
            return "BASE64_DECODE_FAILED"
        }
    }

    private fun logDecodedHexBlock(bytes: ByteArray) {
        output.log("[QrcDump] ===== DECODED HEX START =====")
        output.log(
            "[QrcDump] " + bytes.joinToString(" ") {
                "%02X".format(it.toInt() and 0xFF)
            }
        )
        output.log("[QrcDump] ===== DECODED HEX END =====")
    }

    private fun logDecodedAsciiBlock(bytes: ByteArray) {
        val preview = buildString(bytes.size) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(if (value in 32..126) value.toChar() else '.')
            }
        }
        output.log("[QrcDump] ===== DECODED ASCII START =====")
        output.log("[QrcDump] $preview")
        output.log("[QrcDump] ===== DECODED ASCII END =====")
    }

    private fun detectDecodedType(
        bytes: ByteArray,
        texts: Collection<String>
    ): String {
        if (isZlib(bytes)) {
            return "ZLIB"
        }
        if (isZip(bytes)) {
            return "ZIP"
        }
        val combinedText = texts.joinToString("\n")
        if (DECODED_TEXT_MARKERS.any {
                combinedText.contains(it, ignoreCase = true)
            }
        ) {
            return "TEXT/XML/LRC"
        }
        return "UNKNOWN_DECODED_BINARY"
    }

    private fun decodeSelectedTextPreviews(
        bytes: ByteArray,
        source: String
    ): LinkedHashMap<String, String> {
        val previews = linkedMapOf<String, String>()
        CHARSETS.filter {
            it.label == "UTF-8" ||
                it.label == "GBK" ||
                it.label == "GB18030"
        }.forEach { charsetSpec ->
            try {
                previews[charsetSpec.label] =
                    decodeStrict(bytes, charsetSpec.charset)
                        .take(TEXT_PREVIEW_CHARACTER_LIMIT)
            } catch (exception: CharacterCodingException) {
                output.log(
                    "[QrcDump] charset=${charsetSpec.label} failed: " +
                        "${exception.message ?: "invalid input"} source=$source"
                )
            }
        }
        return previews
    }

    private fun findBase64Candidate(
        texts: Collection<String>
    ): String? {
        return texts.asSequence()
            .map { text -> text.filterNot(Char::isWhitespace) }
            .firstOrNull { compact ->
                compact.length >= MIN_BASE64_LENGTH &&
                    compact.length % 4 == 0 &&
                    BASE64_REGEX.matches(compact)
            }
    }

    private fun isEncryptedQrcHex(bytes: ByteArray): Boolean {
        val compact = String(bytes, Charsets.US_ASCII)
            .filterNot(Char::isWhitespace)
        return compact.length >= MIN_HEX_QRC_LENGTH &&
            compact.length % 16 == 0 &&
            HEX_REGEX.matches(compact)
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

    private fun isZip(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()
    }

    private fun isZlib(bytes: ByteArray): Boolean {
        return bytes.size >= 2 &&
            bytes[0] == 0x78.toByte() &&
            ((bytes[1].toInt() and 0xFF) == 0x9C ||
                (bytes[1].toInt() and 0xFF) == 0xDA)
    }

    private fun readLimitedBytes(
        read: (ByteArray, Int, Int) -> Int
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (outputStream.size() < MAX_EXPANDED_BYTES) {
            val remaining = MAX_EXPANDED_BYTES - outputStream.size()
            val count = read(buffer, 0, minOf(buffer.size, remaining))
            if (count <= 0) {
                break
            }
            outputStream.write(buffer, 0, count)
        }
        return outputStream.toByteArray()
    }

    private data class CharsetSpec(
        val label: String,
        val charset: Charset
    )

    private data class QrcLineMarker(
        val startMs: Long,
        val durationMs: Long,
        val bodyStart: Int
    )

    private enum class FileType(val label: String) {
        ZIP("ZIP"),
        ZLIB("ZLIB"),
        XML_OR_QRC_XML("XML_OR_QRC_XML"),
        LRC_TEXT("LRC_TEXT"),
        ENCRYPTED_QRC_HEX("ENCRYPTED_QRC_HEX"),
        POSSIBLE_BASE64("POSSIBLE_BASE64"),
        UNKNOWN_OR_ENCRYPTED("UNKNOWN_OR_ENCRYPTED")
    }

    private class LimitedLogger(
        private val delegate: (String) -> Unit,
        private val maximumCharacters: Int
    ) {
        private var emittedCharacters = 0
        private var truncationReported = false

        fun log(message: String) {
            if (emittedCharacters >= maximumCharacters) {
                reportTruncation()
                return
            }

            val remaining = maximumCharacters - emittedCharacters
            val value = if (message.length <= remaining) {
                message
            } else {
                message.take(remaining)
            }
            delegate(value)
            emittedCharacters += value.length

            if (value.length < message.length) {
                reportTruncation()
            }
        }

        private fun reportTruncation() {
            if (!truncationReported) {
                truncationReported = true
                delegate("[QrcDump] output truncated at $maximumCharacters characters")
            }
        }
    }

    companion object {
        private const val HEADER_BYTE_LIMIT = 200
        private const val TEXT_PREVIEW_CHARACTER_LIMIT = 3000
        private const val FILE_LIST_LIMIT = 30
        private const val MAX_ZIP_ENTRIES = 8
        private const val MAX_EXPANDED_BYTES = 64 * 1024
        private const val MAX_TOTAL_OUTPUT_CHARACTERS = 30_000
        private const val MIN_BASE64_LENGTH = 128
        private const val MIN_HEX_QRC_LENGTH = 128
        private const val QRC_LINE_PREVIEW_LIMIT = 12

        private val SUPPORTED_EXTENSIONS = setOf(
            "qrc",
            "romaqrc",
            "translrc",
            "lrc"
        )
        private val EXTENSION_PRIORITY = listOf(
            "qrc",
            "romaqrc",
            "translrc",
            "lrc"
        )
        private val CHARSETS = listOf(
            CharsetSpec("UTF-8", Charsets.UTF_8),
            CharsetSpec("GBK", Charset.forName("GBK")),
            CharsetSpec("GB18030", Charset.forName("GB18030")),
            CharsetSpec("UTF-16LE", Charsets.UTF_16LE),
            CharsetSpec("UTF-16BE", Charsets.UTF_16BE)
        )
        private val XML_MARKERS = listOf(
            "<?xml",
            "<QrcInfos",
            "<Lyric"
        )
        private val LRC_MARKERS = listOf("[00:", "[0:")
        private val DECODED_TEXT_MARKERS = listOf(
            "<?xml",
            "<QrcInfos",
            "Lyric",
            "[00:"
        )
        private val KEYWORDS = listOf(
            "[00:",
            "Lyric",
            "Qrc",
            "lyric",
            "content",
            "五月天",
            "虹之间",
            "小手拉大手",
            "会呼吸的痛",
            "梁静茹"
        )
        private val BASE64_REGEX =
            Regex("""[A-Za-z0-9+/]+={0,2}""")
        private val HEX_REGEX = Regex("""[0-9A-Fa-f]+""")
        private val QRC_LYRIC_CONTENT_REGEX =
            Regex("""LyricContent\s*=\s*"([\s\S]*?)"""")
        private val QRC_METADATA_REGEX =
            Regex("""\[(\w+)\s*:\s*([^]]*)]""")
        private val QRC_LINE_REGEX =
            Regex("""\[(\d+)\s*,\s*(\d+)]""")
        private val QRC_WORD_TIME_REGEX =
            Regex("""\(\d+\s*,\s*\d+\)""")
    }
}
