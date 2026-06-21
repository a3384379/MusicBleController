package com.example.playeragent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class QQMusicArtworkDiscoveryManager(
    context: Context,
    private val logger: (String) -> Unit,
    private val onStatus: (ArtworkDiscoveryStatus) -> Unit
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QQMusicArtworkDiscoveryThread")
    }
    private val cancelled = AtomicBoolean(false)
    @Volatile
    private var runningTask: Future<*>? = null
    @Volatile
    private var generation = 0L

    fun discoverCurrentTrackArtwork(
        title: String,
        artist: String,
        album: String,
        albumArtId: String,
        referenceBitmap: Bitmap
    ) {
        cancel()
        val taskGeneration = generation + 1
        generation = taskGeneration
        cancelled.set(false)
        val token = QrcMaintenanceCoordinator.tryStart(
            MaintenanceTaskType.ARTWORK_DISCOVERY,
            "title=$title",
            logger
        )
        if (token == null) {
            onStatus(ArtworkDiscoveryStatus(status = "busy", currentTitle = title))
            return
        }
        runningTask = executor.submit {
            try {
                if (!token.cancelled) {
                    runDiscovery(
                        generation = taskGeneration,
                        title = title,
                        artist = artist,
                        album = album,
                        albumArtId = albumArtId,
                        referenceBitmap = referenceBitmap
                    )
                }
            } catch (exception: Exception) {
                QrcMaintenanceCoordinator.fail(token, exception, logger)
                return@submit
            } finally {
                QrcMaintenanceCoordinator.finish(token, logger)
            }
        }
    }

    fun cancel() {
        generation += 1
        cancelled.set(true)
        if (QrcMaintenanceCoordinator.currentToken()?.type == MaintenanceTaskType.ARTWORK_DISCOVERY) {
            QrcMaintenanceCoordinator.cancelCurrent("artwork discovery cancelled", logger)
        }
        runningTask?.cancel(true)
        runningTask = null
        onStatus(ArtworkDiscoveryStatus(status = "stopped"))
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }

    private fun runDiscovery(
        generation: Long,
        title: String,
        artist: String,
        album: String,
        albumArtId: String,
        referenceBitmap: Bitmap
    ) {
        val startMs = System.currentTimeMillis()
        val outputDir = appContext.getExternalFilesDir("ArtworkDiscovery") ?: return
        outputDir.mkdirs()
        logger("[ArtworkDiscovery] start title=$title artist=$artist id=$albumArtId")
        onStatus(
            ArtworkDiscoveryStatus(
                status = "running",
                currentTitle = title
            )
        )

        val referenceFile = File(outputDir, "${albumArtId}_reference_notification_280.png")
        referenceBitmap.compress(Bitmap.CompressFormat.PNG, 100, referenceFile.outputStream())
        val referenceVector = fingerprint(referenceBitmap)
        val roots = scanRoots()
        val rootReports = JSONArray()
        roots.forEach {
            rootReports.put(
                JSONObject()
                    .put("path", it.absolutePath)
                    .put("exists", it.exists())
                    .put("canRead", it.canRead())
            )
        }

        var scannedFiles = 0
        var imageCandidates = 0
        var similarCandidates = 0
        var best: CandidateScore? = null
        val candidates = mutableListOf<ImageCandidate>()
        roots.forEach { root ->
            if (shouldStop(generation, startMs)) return@forEach
            collectCandidates(root, candidates, startMs) {
                scannedFiles += 1
                scannedFiles % 50 == 0
            }
        }

        val limitedCandidates = candidates
            .sortedByDescending { it.lastModified }
            .take(MAX_CANDIDATES_TO_ANALYZE)
        imageCandidates = limitedCandidates.size
        limitedCandidates.forEachIndexed { index, candidate ->
            if (shouldStop(generation, startMs)) return@forEachIndexed
            val score = scoreCandidate(candidate, referenceVector) ?: return@forEachIndexed
            if (score.similarity >= SAFE_SIMILARITY_THRESHOLD) {
                similarCandidates += 1
            }
            if (best == null || score.similarity > best!!.similarity) {
                best = score
            }
            if ((index + 1) % 50 == 0) {
                onStatus(
                    ArtworkDiscoveryStatus(
                        status = "running",
                        scannedFiles = scannedFiles,
                        imageCandidates = imageCandidates,
                        similarCandidates = similarCandidates,
                        bestPath = best?.candidate?.file?.absolutePath.orEmpty(),
                        bestSize = best?.let { "${it.candidate.width}x${it.candidate.height}" }.orEmpty(),
                        bestSimilarity = best?.similarity ?: 0.0,
                        currentTitle = title
                    )
                )
            }
        }

        val bestScore = best
        val result = if (bestScore != null &&
            bestScore.similarity >= SAFE_SIMILARITY_THRESHOLD &&
            (bestScore.candidate.width > referenceBitmap.width ||
                bestScore.candidate.height > referenceBitmap.height)
        ) {
            exportBestCandidate(outputDir, albumArtId, bestScore)
            "FOUND_HIGH_RES"
        } else {
            "NO_HIGH_RES_FOUND"
        }
        val report = JSONObject()
            .put("trackId", albumArtId)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("referenceWidth", referenceBitmap.width)
            .put("referenceHeight", referenceBitmap.height)
            .put("referenceSha256", sha256(referenceFile.readBytes()))
            .put("scannedFileCount", scannedFiles)
            .put("candidateCount", imageCandidates)
            .put("similarCandidates", similarCandidates)
            .put("roots", rootReports)
            .put("bestPath", bestScore?.candidate?.file?.absolutePath.orEmpty())
            .put("bestWidth", bestScore?.candidate?.width ?: 0)
            .put("bestHeight", bestScore?.candidate?.height ?: 0)
            .put("bestBytes", bestScore?.candidate?.bytes ?: 0L)
            .put("similarity", bestScore?.similarity ?: 0.0)
            .put("result", result)
            .put("costMs", System.currentTimeMillis() - startMs)
        File(outputDir, "${albumArtId}_artwork_discovery_report.json")
            .writeText(report.toString(2), Charsets.UTF_8)
        File(outputDir, "latest_artwork_discovery_report.json")
            .writeText(report.toString(2), Charsets.UTF_8)
        logger(
            "[ArtworkDiscovery] finished result=$result title=$title " +
                "scanned=$scannedFiles candidates=$imageCandidates " +
                "similar=$similarCandidates best=${bestScore?.candidate?.width ?: 0}x" +
                "${bestScore?.candidate?.height ?: 0} " +
                "similarity=${formatScore(bestScore?.similarity ?: 0.0)}"
        )
        onStatus(
            ArtworkDiscoveryStatus(
                status = "stopped",
                scannedFiles = scannedFiles,
                imageCandidates = imageCandidates,
                similarCandidates = similarCandidates,
                bestPath = bestScore?.candidate?.file?.absolutePath.orEmpty(),
                bestSize = bestScore?.let { "${it.candidate.width}x${it.candidate.height}" }.orEmpty(),
                bestSimilarity = bestScore?.similarity ?: 0.0,
                result = result,
                currentTitle = title
            )
        )
    }

    private fun collectCandidates(
        root: File,
        candidates: MutableList<ImageCandidate>,
        startMs: Long,
        onProgress: () -> Boolean
    ) {
        if (!root.exists() || !root.canRead()) {
            logger("[ArtworkDiscovery] root unavailable path=${root.absolutePath}")
            return
        }
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            if (cancelled.get() || System.currentTimeMillis() - startMs > MAX_SCAN_MS) return
            val current = stack.removeLast()
            val files = try {
                current.listFiles()
            } catch (exception: Exception) {
                logger(
                    "[ArtworkDiscovery] list failed path=${current.absolutePath} " +
                        "error=${exception.message}"
                )
                null
            } ?: continue
            files.forEach { file ->
                if (cancelled.get()) return
                if (file.isDirectory) {
                    if (shouldDescend(file)) stack.add(file)
                    return@forEach
                }
                if (!isImageFile(file)) return@forEach
                onProgress()
                if (file.length() <= 0L || file.length() > MAX_FILE_BYTES) return@forEach
                val bounds = readBounds(file) ?: return@forEach
                if (bounds.first <= 280 && bounds.second <= 280) return@forEach
                candidates += ImageCandidate(
                    file = file,
                    width = bounds.first,
                    height = bounds.second,
                    bytes = file.length(),
                    lastModified = file.lastModified(),
                    extension = file.extension.lowercase(Locale.US)
                )
            }
        }
    }

    private fun shouldDescend(file: File): Boolean {
        val path = file.absolutePath.lowercase(Locale.US)
        if (path.contains("/qrc")) return false
        if (path.contains("/log")) return false
        if (path.contains("/diagnosis")) return false
        return true
    }

    private fun scoreCandidate(
        candidate: ImageCandidate,
        referenceVector: IntArray
    ): CandidateScore? {
        val bitmap = decodeSampled(candidate.file, SAMPLE_SIZE, SAMPLE_SIZE) ?: return null
        return try {
            val vector = fingerprint(bitmap)
            val mse = referenceVector.indices.sumOf { index ->
                val diff = referenceVector[index] - vector[index]
                diff * diff
            }.toDouble() / referenceVector.size.toDouble()
            val rmse = sqrt(mse)
            val similarity = (1.0 - (rmse / 255.0)).coerceIn(0.0, 1.0)
            CandidateScore(candidate, similarity)
        } finally {
            bitmap.recycle()
        }
    }

    private fun exportBestCandidate(
        outputDir: File,
        albumArtId: String,
        score: CandidateScore
    ) {
        val original = score.candidate.file
        val extension = original.extension.ifBlank { "img" }
        original.copyTo(
            target = File(outputDir, "${albumArtId}_best_candidate_original.$extension"),
            overwrite = true
        )
        decodeSampled(original, 320, 320)?.let { bitmap ->
            try {
                bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    File(outputDir, "${albumArtId}_best_candidate_preview.png").outputStream()
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun readBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    private fun decodeSampled(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        var halfHeight = options.outHeight / 2
        var halfWidth = options.outWidth / 2
        while (halfHeight / inSampleSize >= reqHeight &&
            halfWidth / inSampleSize >= reqWidth
        ) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun fingerprint(source: Bitmap): IntArray {
        val scaled = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        canvas.drawBitmap(
            source,
            null,
            android.graphics.Rect(0, 0, SAMPLE_SIZE, SAMPLE_SIZE),
            null
        )
        val values = IntArray(SAMPLE_SIZE * SAMPLE_SIZE * 3)
        var offset = 0
        for (y in 0 until SAMPLE_SIZE) {
            for (x in 0 until SAMPLE_SIZE) {
                val pixel = scaled.getPixel(x, y)
                values[offset++] = Color.red(pixel)
                values[offset++] = Color.green(pixel)
                values[offset++] = Color.blue(pixel)
            }
        }
        scaled.recycle()
        return values
    }

    private fun scanRoots(): List<File> {
        val storage = android.os.Environment.getExternalStorageDirectory()
        return listOf(
            File(storage, "QQMusic"),
            File(storage, "QQMusic/ImageCache"),
            File(storage, "QQMusic/portrait"),
            File(storage, "Music"),
            File(storage, "Music/qqmusic"),
            File(storage, "Pictures"),
            File(storage, "Android/data/com.tencent.qqmusic/cache"),
            File(storage, "Android/data/com.tencent.qqmusic/files")
        ).distinctBy { it.absolutePath }
    }

    private fun isImageFile(file: File): Boolean {
        return when (file.extension.lowercase(Locale.US)) {
            "jpg", "jpeg", "png", "webp" -> true
            else -> false
        }
    }

    private fun shouldStop(generation: Long, startMs: Long): Boolean {
        return cancelled.get() ||
            generation != this.generation ||
            System.currentTimeMillis() - startMs > MAX_SCAN_MS
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun formatScore(value: Double): String {
        return "%.4f".format(Locale.US, value)
    }

    private data class ImageCandidate(
        val file: File,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val lastModified: Long,
        val extension: String
    )

    private data class CandidateScore(
        val candidate: ImageCandidate,
        val similarity: Double
    )

    companion object {
        private const val MAX_SCAN_MS = 30_000L
        private const val MAX_FILE_BYTES = 10L * 1024L * 1024L
        private const val MAX_CANDIDATES_TO_ANALYZE = 500
        private const val SAMPLE_SIZE = 32
        private const val SAFE_SIMILARITY_THRESHOLD = 0.88
    }
}

data class ArtworkDiscoveryStatus(
    val status: String = "stopped",
    val scannedFiles: Int = 0,
    val imageCandidates: Int = 0,
    val similarCandidates: Int = 0,
    val bestPath: String = "",
    val bestSize: String = "",
    val bestSimilarity: Double = 0.0,
    val result: String = "",
    val currentTitle: String = ""
) {
    fun displayText(): String {
        return buildString {
            append("Artwork discovery:\n")
            append("status: $status\n")
            if (currentTitle.isNotBlank()) append("title: $currentTitle\n")
            append("scanned files: $scannedFiles\n")
            append("image candidates: $imageCandidates\n")
            append("similar candidates: $similarCandidates\n")
            if (bestPath.isNotBlank()) append("best candidate: $bestPath\n")
            if (bestSize.isNotBlank()) append("best size: $bestSize\n")
            append("similarity: ${"%.4f".format(Locale.US, bestSimilarity)}\n")
            if (result.isNotBlank()) append("result: $result")
        }
    }
}
