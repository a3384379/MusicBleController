package com.example.playeragent.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Environment
import com.example.playeragent.service.PlayerNotificationListenerService
import java.io.File
import java.io.FileInputStream
import java.util.Locale

class LrcDebugManager(
    context: Context,
    private val logger: (String) -> Unit,
    private val summaryLogger: (String) -> Unit = logger
) {

    private val appContext = context.applicationContext
    private val mediaSessionManager =
        appContext.getSystemService(MediaSessionManager::class.java)

    fun runDebug() {
        logger("[LyricDebug] ===== START =====")

        val track = readCurrentTrack()
        logger("[LyricDebug] title=${track.title}")
        logger("[LyricDebug] artist=${track.artist}")
        logger("[LyricDebug] album=${track.album}")
        logger("[LyricDebug] package=${track.packageName}")

        val allCandidates = linkedMapOf<String, File>()
        scanDirectories().forEach { directory ->
            val files = listFilesRecursively(directory)
            logger(
                "[LyricDebug] directory=${directory.absolutePath} " +
                    "exists=${directory.exists()} files=${files.size}"
            )
            files
                .filter(::isLikelyLyricFile)
                .forEach { file ->
                    allCandidates[file.absolutePath] = file
                }
        }

        val files = allCandidates.values.toList()
        logger("[LyricDebug] lyricFileCount=${files.size}")
        files.take(FILE_LIST_LIMIT).forEach { file ->
            logger("[LyricDebug] file=${file.name} size=${file.length()}")
        }

        val scored = files
            .map { file ->
                ScoredFile(
                    file = file,
                    score = calculateScore(file, track)
                )
            }
            .sortedWith(
                compareByDescending<ScoredFile> { it.score }
                    .thenByDescending { it.file.lastModified() }
                    .thenBy { it.file.absolutePath }
            )

        scored.forEach { candidate ->
            logger(
                "[LyricDebug] score=${candidate.score} " +
                    "file=${candidate.file.absolutePath}"
            )
        }

        val bestMatch = scored.firstOrNull()?.file
        if (bestMatch == null) {
            logger("[LyricDebug] bestMatch=")
            logger("[LyricDebug] no lyric candidate found")
            summaryLogger(
                "[LyricDebug] result=no match title=${track.title} " +
                    "files=${files.size}"
            )
            logger("[LyricDebug] ===== END =====")
            return
        }

        logger("[LyricDebug] bestMatch=${bestMatch.absolutePath}")
        val leadingBytes = readLeadingBytes(bestMatch, CONTENT_READ_LIMIT_BYTES)
        val content = String(leadingBytes, Charsets.UTF_8)
            .take(FILE_CONTENT_CHARACTER_LIMIT)

        logger("===== FILE CONTENT START =====")
        logger(content)
        logger("===== FILE CONTENT END =====")

        val hexBytes = leadingBytes.take(HEX_BYTE_LIMIT)
            .joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        logger("[LyricDebug] first100BytesHex=$hexBytes")
        val detectedFormat = detectFormat(leadingBytes, content)
        logger("[LyricDebug] detectedFormat=$detectedFormat")
        summaryLogger(
            "[LyricDebug] result file=${bestMatch.name} " +
                "score=${scored.first().score} format=$detectedFormat"
        )
        logger("[LyricDebug] ===== END =====")
    }

    private fun readCurrentTrack(): TrackInfo {
        val controllers = try {
            mediaSessionManager?.getActiveSessions(
                ComponentName(
                    appContext,
                    PlayerNotificationListenerService::class.java
                )
            ).orEmpty()
        } catch (exception: Exception) {
            logger(
                "[LyricDebug] active sessions unavailable: " +
                    "${exception.message}"
            )
            emptyList()
        }

        val controller = selectController(controllers)
        val metadata = controller?.metadata
        val notificationPackage = PlayerNotificationListenerService
            .activeNotificationsSnapshot()
            .firstOrNull { it.packageName == QQ_MUSIC_PACKAGE }
            ?.packageName
            .orEmpty()
        return TrackInfo(
            title = metadata
                ?.getString(MediaMetadata.METADATA_KEY_TITLE)
                .orEmpty(),
            artist = metadata
                ?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                .orEmpty(),
            album = metadata
                ?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                .orEmpty(),
            packageName = controller?.packageName
                ?: notificationPackage
        )
    }

    private fun selectController(
        controllers: List<MediaController>
    ): MediaController? {
        val qqMusicControllers = controllers.filter {
            it.packageName == QQ_MUSIC_PACKAGE
        }
        return qqMusicControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: qqMusicControllers.firstOrNull()
            ?: controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            ?: controllers.firstOrNull()
    }

    private fun scanDirectories(): List<File> {
        val storageRoot = Environment.getExternalStorageDirectory()
        return listOf(
            File(storageRoot, "QQMusic"),
            File(storageRoot, "QQMusic/Lyric"),
            File(storageRoot, "QQMusic/qrc")
        )
    }

    private fun listFilesRecursively(root: File): List<File> {
        if (!root.exists() || !root.isDirectory) {
            return emptyList()
        }

        val result = mutableListOf<File>()
        val pending = ArrayDeque<File>()
        pending.add(root)

        while (pending.isNotEmpty() && result.size < MAX_SCANNED_FILES) {
            val directory = pending.removeFirst()
            val children = try {
                directory.listFiles()
            } catch (exception: Exception) {
                logger(
                    "[LyricDebug] access failed=${directory.absolutePath} " +
                        "reason=${exception.message}"
                )
                null
            } ?: continue

            children.forEach { child ->
                when {
                    child.isDirectory -> pending.addLast(child)
                    child.isFile -> result += child
                }
            }
        }
        return result
    }

    private fun isLikelyLyricFile(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.ROOT)
        val path = file.absolutePath.lowercase(Locale.ROOT)
        return extension in LYRIC_EXTENSIONS ||
            path.contains("/qqmusic/lyric/") ||
            path.contains("/qqmusic/qrc/")
    }

    private fun calculateScore(file: File, track: TrackInfo): Int {
        val fileName = normalize(file.nameWithoutExtension)
        val preview = normalize(
            String(
                readLeadingBytes(file, SCORE_READ_LIMIT_BYTES),
                Charsets.UTF_8
            )
        )
        val title = normalize(track.title)
        val artist = normalize(track.artist)
        val album = normalize(track.album)
        var score = 0

        if (title.isNotEmpty() && fileName.contains(title)) score += 100
        if (artist.isNotEmpty() && fileName.contains(artist)) score += 60
        if (album.isNotEmpty() && fileName.contains(album)) score += 30
        if (title.isNotEmpty() && preview.contains(title)) score += 80
        if (artist.isNotEmpty() && preview.contains(artist)) score += 40
        if (album.isNotEmpty() && preview.contains(album)) score += 20
        return score
    }

    private fun readLeadingBytes(file: File, limit: Int): ByteArray {
        return try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(limit)
                val count = input.read(buffer)
                if (count <= 0) {
                    ByteArray(0)
                } else {
                    buffer.copyOf(count)
                }
            }
        } catch (exception: Exception) {
            logger(
                "[LyricDebug] read failed=${file.absolutePath} " +
                    "reason=${exception.message}"
            )
            ByteArray(0)
        }
    }

    private fun detectFormat(bytes: ByteArray, content: String): String {
        if (bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()
        ) {
            return "ZIP"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 'Q'.code.toByte() &&
            bytes[1] == 'R'.code.toByte() &&
            bytes[2] == 'C'.code.toByte()
        ) {
            return "QRC"
        }
        if (bytes.size >= 2 &&
            bytes[0] == 0x1F.toByte() &&
            bytes[1] == 0x8B.toByte()
        ) {
            return "GZIP compressed"
        }
        if (bytes.size >= 2 &&
            bytes[0] == 0x78.toByte() &&
            (bytes[1].toInt() and 0xFF) in ZLIB_SECOND_BYTES
        ) {
            return "ZLIB compressed"
        }

        val trimmed = content.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        if (trimmed.startsWith("<")) {
            return "XML"
        }
        if (QRC_MARKERS.any { trimmed.contains(it, ignoreCase = true) }) {
            return "QRC"
        }
        if (LRC_TIME_TAG.containsMatchIn(trimmed)) {
            return "LRC"
        }

        val compact = trimmed
            .take(BASE64_SAMPLE_LENGTH)
            .filterNot(Char::isWhitespace)
        if (compact.length >= 32 &&
            compact.length % 4 == 0 &&
            BASE64_REGEX.matches(compact)
        ) {
            return "Base64"
        }

        val printableCount = bytes.count { byte ->
            val value = byte.toInt() and 0xFF
            value == 9 || value == 10 || value == 13 || value in 32..126 ||
                value >= 0xC0
        }
        return if (bytes.isNotEmpty() &&
            printableCount * 100 / bytes.size < 70
        ) {
            "Binary/Encrypted"
        } else {
            "Unknown text"
        }
    }

    private fun normalize(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(NORMALIZE_REGEX, "")
    }

    private data class TrackInfo(
        val title: String,
        val artist: String,
        val album: String,
        val packageName: String
    )

    private data class ScoredFile(
        val file: File,
        val score: Int
    )

    companion object {
        private const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
        private const val FILE_LIST_LIMIT = 20
        private const val FILE_CONTENT_CHARACTER_LIMIT = 3000
        private const val CONTENT_READ_LIMIT_BYTES = 12_000
        private const val SCORE_READ_LIMIT_BYTES = 4096
        private const val HEX_BYTE_LIMIT = 100
        private const val MAX_SCANNED_FILES = 10_000
        private const val BASE64_SAMPLE_LENGTH = 512
        private val LYRIC_EXTENSIONS = setOf("lrc", "qrc", "lyric")
        private val QRC_MARKERS = listOf("[offset:", "QrcInfos", "Lyric_1")
        private val LRC_TIME_TAG =
            Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")
        private val BASE64_REGEX = Regex("""[A-Za-z0-9+/]+={0,2}""")
        private val NORMALIZE_REGEX = Regex("""[\s\-_.·—–()\[\]{}]+""")
        private val ZLIB_SECOND_BYTES = setOf(0x01, 0x5E, 0x9C, 0xDA)
    }
}
