package com.example.controllerapp.media

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.controllerapp.model.ArtworkQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

data class CachedArtwork(
    val bitmap: Bitmap,
    val quality: ArtworkQuality,
    val createdAtMs: Long,
    val requiresRefresh: Boolean
)

class ArtworkCache(context: Context) {
    private val directory = File(context.filesDir, "album_art_cache").apply { mkdirs() }
    private val memory = object : LruCache<String, CachedArtwork>(32 * 1024) {
        override fun sizeOf(key: String, value: CachedArtwork): Int =
            (value.bitmap.allocationByteCount / 1024).coerceAtLeast(1)
    }

    suspend fun load(
        artworkId: String,
        quality: ArtworkQuality,
        maximumPixelSize: Int
    ): CachedArtwork? = withContext(Dispatchers.IO) {
        loadOnIo(artworkId, quality, maximumPixelSize)
    }

    /**
     * The player always prefers HQ, then Preview.  Keeping this selection in
     * the cache prevents the controller from doing two sequential disk reads
     * every time a known song becomes active.
     */
    suspend fun loadBest(artworkId: String, maximumPixelSize: Int): CachedArtwork? =
        withContext(Dispatchers.IO) {
            peekBest(artworkId, maximumPixelSize)
                ?: loadOnIo(artworkId, ArtworkQuality.HQ, maximumPixelSize)
                ?: loadOnIo(artworkId, ArtworkQuality.PREVIEW, maximumPixelSize)
        }

    /** Fast path for the media reducer; no file I/O or bitmap decoding. */
    fun peekBest(artworkId: String, maximumPixelSize: Int): CachedArtwork? =
        ArtworkQuality.entries
            .asSequence()
            .filter { it == ArtworkQuality.HQ || it == ArtworkQuality.PREVIEW }
            .sortedByDescending(ArtworkQuality::rank)
            .mapNotNull { quality -> memory.get(key(artworkId, quality, maximumPixelSize)) }
            .firstOrNull()

    private fun loadOnIo(
        artworkId: String,
        quality: ArtworkQuality,
        maximumPixelSize: Int
    ): CachedArtwork? {
        val key = key(artworkId, quality, maximumPixelSize)
        memory.get(key)?.let {
            return it
        }
        val imageFile = imageFile(artworkId, quality)
        val metadataFile = metadataFile(artworkId, quality)
        if (!imageFile.isFile || !metadataFile.isFile) return null
        val metadata = runCatching { JSONObject(metadataFile.readText()) }.getOrNull()
            ?: return null
        val createdAt = metadata.optLong("createdAtMs")
        val ageMs = System.currentTimeMillis() - createdAt
        if (createdAt <= 0L || ageMs < 0L || ageMs > HARD_TTL_MS) {
            imageFile.delete()
            metadataFile.delete()
            return null
        }
        val bitmap = decodeDownsampled(imageFile.readBytes(), maximumPixelSize)
            ?: return null
        if (ArtworkPlaceholderPolicy.isLikelyPlaceholder(bitmap)) {
            bitmap.recycle()
            imageFile.delete()
            metadataFile.delete()
            return null
        }
        val cached = CachedArtwork(
            bitmap = bitmap,
            quality = quality,
            createdAtMs = createdAt,
            requiresRefresh = ageMs > metadata.optLong(
                "refreshAfterMs",
                STABLE_REFRESH_MS
            )
        )
        memory.put(key, cached)
        trimDecodedEntries()
        return cached
    }

    suspend fun store(
        artworkId: String,
        quality: ArtworkQuality,
        data: ByteArray,
        bitmap: Bitmap,
        maximumPixelSize: Int,
        source: String = "ble"
    ) = withContext(Dispatchers.IO) {
        if (ArtworkPlaceholderPolicy.isLikelyPlaceholder(bitmap)) return@withContext
        directory.mkdirs()
        val image = imageFile(artworkId, quality)
        val metadata = metadataFile(artworkId, quality)
        val imageTemp = File(image.parentFile, "${image.name}.tmp")
        val metadataTemp = File(metadata.parentFile, "${metadata.name}.tmp")
        FileOutputStream(imageTemp).use { it.write(data) }
        val refreshAfter = if (source == "notificationLargeIcon") {
            TRANSIENT_REFRESH_MS
        } else {
            STABLE_REFRESH_MS
        }
        metadataTemp.writeText(
            JSONObject()
                .put("version", 1)
                .put("artworkId", artworkId)
                .put("quality", quality.name)
                .put("source", source)
                .put("createdAtMs", System.currentTimeMillis())
                .put("refreshAfterMs", refreshAfter)
                .put("width", bitmap.width)
                .put("height", bitmap.height)
                .put("bytes", data.size)
                .toString()
        )
        if (!imageTemp.renameTo(image)) {
            image.delete()
            imageTemp.renameTo(image)
        }
        if (!metadataTemp.renameTo(metadata)) {
            metadata.delete()
            metadataTemp.renameTo(metadata)
        }
        memory.put(
            key(artworkId, quality, maximumPixelSize),
            CachedArtwork(
                bitmap = bitmap,
                quality = quality,
                createdAtMs = System.currentTimeMillis(),
                requiresRefresh = false
            )
        )
        trimDecodedEntries()
        trimDisk()
    }

    suspend fun remove(artworkId: String) = withContext(Dispatchers.IO) {
        ArtworkQuality.entries.forEach { quality ->
            imageFile(artworkId, quality).delete()
            metadataFile(artworkId, quality).delete()
        }
        memory.evictAll()
    }

    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            memory.evictAll()
        }
    }

    fun clearDecoded() = memory.evictAll()

    fun cacheStats(): Pair<Int, Long> {
        val files = directory.listFiles { file -> file.extension == "jpg" }.orEmpty()
        return files.size to files.sumOf(File::length)
    }

    private fun trimDecodedEntries() {
        while (memory.snapshot().size > MAX_MEMORY_ENTRIES) {
            val eldest = memory.snapshot().keys.firstOrNull() ?: return
            memory.remove(eldest)
        }
    }

    private fun trimDisk() {
        val files = directory.listFiles { file -> file.extension == "jpg" }
            ?.sortedBy(File::lastModified)
            .orEmpty()
        var bytes = files.sumOf(File::length)
        var count = files.size
        files.forEach { file ->
            if (count <= MAX_DISK_FILES && bytes <= MAX_DISK_BYTES) return
            bytes -= file.length()
            count -= 1
            file.delete()
            File(file.parentFile, "${file.nameWithoutExtension}.json").delete()
        }
    }

    private fun imageFile(artworkId: String, quality: ArtworkQuality) =
        File(directory, "${hash(artworkId)}_${quality.name.lowercase()}.jpg")

    private fun metadataFile(artworkId: String, quality: ArtworkQuality) =
        File(directory, "${hash(artworkId)}_${quality.name.lowercase()}.json")

    private fun key(artworkId: String, quality: ArtworkQuality, maximumPixelSize: Int) =
        "$artworkId|${quality.name}|$maximumPixelSize"

    companion object {
        private const val TRANSIENT_REFRESH_MS = 30 * 60 * 1_000L
        private const val STABLE_REFRESH_MS = 24 * 60 * 60 * 1_000L
        private const val HARD_TTL_MS = 30 * 24 * 60 * 60 * 1_000L
        private const val MAX_DISK_FILES = 500
        private const val MAX_DISK_BYTES = 300L * 1024 * 1024
        private const val MAX_MEMORY_ENTRIES = 40

        fun decodeDownsampled(data: ByteArray, maximumPixelSize: Int): Bitmap? {
            if (data.isEmpty() || maximumPixelSize <= 0) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maximumPixelSize) {
                sample *= 2
            }
            return BitmapFactory.decodeByteArray(
                data,
                0,
                data.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }

        private fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

object ArtworkPlaceholderPolicy {
    fun isLikelyPlaceholder(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 16 && bitmap.height <= 16) return true
        val sampleWidth = minOf(24, bitmap.width)
        val sampleHeight = minOf(24, bitmap.height)
        val sample = if (sampleWidth == bitmap.width && sampleHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        }
        return try {
            val pixels = IntArray(sampleWidth * sampleHeight)
            sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
            isLikelyPlaceholder(bitmap.width, bitmap.height, pixels)
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    fun isLikelyPlaceholder(width: Int, height: Int, pixels: IntArray): Boolean {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return true
        val buckets = HashMap<Int, Int>()
        var visible = 0
        var colorful = 0
        pixels.forEach { pixel ->
            val alpha = pixel ushr 24
            if (alpha < 24) return@forEach
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            val maximum = max(red, max(green, blue))
            val minimum = minOf(red, green, blue)
            if (maximum > 0 && (maximum - minimum).toFloat() / maximum > 0.12f) {
                colorful += 1
            }
            val bucket = ((red / 32) shl 10) or ((green / 32) shl 5) or (blue / 32)
            buckets[bucket] = (buckets[bucket] ?: 0) + 1
            visible += 1
        }
        if (visible == 0) return true
        val knownSize = width in 200..240 && height in 200..240
        val nearlySquare = abs(width - height) <= max(width, height) / 20
        val dominant = buckets.values.maxOrNull() ?: 0
        return knownSize &&
            nearlySquare &&
            buckets.size <= 8 &&
            colorful * 100 <= visible &&
            dominant * 100 >= visible * 70
    }
}

object ArtworkEnhancer {
    suspend fun enhance(bitmap: Bitmap, targetSize: Int = 780): Bitmap =
        withContext(Dispatchers.Default) {
            if (bitmap.width >= targetSize && bitmap.height >= targetSize) {
                return@withContext bitmap
            }
            val scaled = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
            val width = scaled.width
            val height = scaled.height
            val source = IntArray(width * height)
            scaled.getPixels(source, 0, width, 0, 0, width, height)
            val output = source.copyOf()
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val index = y * width + x
                    val center = source[index]
                    fun channel(shift: Int): Int {
                        val value =
                            5 * (center ushr shift and 0xff) -
                                (source[index - 1] ushr shift and 0xff) -
                                (source[index + 1] ushr shift and 0xff) -
                                (source[index - width] ushr shift and 0xff) -
                                (source[index + width] ushr shift and 0xff)
                        return value.coerceIn(0, 255)
                    }
                    output[index] =
                        (center ushr 24 shl 24) or
                            (channel(16) shl 16) or
                            (channel(8) shl 8) or
                            channel(0)
                }
            }
            Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
        }
}
