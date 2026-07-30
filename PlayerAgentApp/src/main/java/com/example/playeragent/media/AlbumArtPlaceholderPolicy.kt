package com.example.playeragent.media

import android.graphics.Bitmap

/**
 * Identifies QQ Music's temporary fallback artwork without rejecting ordinary
 * monochrome album covers. On the Sony player the fallback is a 228 px square
 * with two dominant gray tones; real notification artwork is normally 280 px.
 *
 * Keep the pixel-only overload free of Android behavior so the thresholds can
 * be covered by local JVM unit tests.
 */
internal object AlbumArtPlaceholderPolicy {
    fun isLikelyPlaceholder(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return true
        }
        val sampleWidth = minOf(SAMPLE_SIZE, bitmap.width)
        val sampleHeight = minOf(SAMPLE_SIZE, bitmap.height)
        val sampled = if (sampleWidth == bitmap.width &&
            sampleHeight == bitmap.height
        ) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        }
        return try {
            val pixels = IntArray(sampleWidth * sampleHeight)
            sampled.getPixels(
                pixels,
                0,
                sampleWidth,
                0,
                0,
                sampleWidth,
                sampleHeight
            )
            isLikelyPlaceholder(
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                pixels = pixels
            )
        } finally {
            if (sampled !== bitmap) {
                sampled.recycle()
            }
        }
    }

    fun isLikelyPlaceholder(
        sourceWidth: Int,
        sourceHeight: Int,
        pixels: IntArray
    ): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || pixels.isEmpty()) {
            return true
        }
        if (sourceWidth <= MIN_VALID_ARTWORK_SIZE &&
            sourceHeight <= MIN_VALID_ARTWORK_SIZE
        ) {
            return true
        }

        val colorBuckets = HashMap<Int, Int>()
        var colorfulPixels = 0
        var visiblePixels = 0
        pixels.forEach { pixel ->
            val alpha = pixel ushr 24
            if (alpha < MIN_VISIBLE_ALPHA) {
                return@forEach
            }
            val red = (pixel ushr 16) and 0xff
            val green = (pixel ushr 8) and 0xff
            val blue = pixel and 0xff
            val maximum = maxOf(red, green, blue)
            val minimum = minOf(red, green, blue)
            if (maximum > 0 &&
                (maximum - minimum).toFloat() / maximum.toFloat() >
                COLORFUL_SATURATION_THRESHOLD
            ) {
                colorfulPixels += 1
            }
            val bucket =
                ((red / COLOR_BUCKET_SIZE) shl 10) or
                    ((green / COLOR_BUCKET_SIZE) shl 5) or
                    (blue / COLOR_BUCKET_SIZE)
            colorBuckets[bucket] = (colorBuckets[bucket] ?: 0) + 1
            visiblePixels += 1
        }
        if (visiblePixels == 0) {
            return true
        }

        val nearlySquare =
            kotlin.math.abs(sourceWidth - sourceHeight) <=
                maxOf(sourceWidth, sourceHeight) / 20
        val knownFallbackSize =
            sourceWidth in QQ_FALLBACK_MIN_SIZE..QQ_FALLBACK_MAX_SIZE &&
                sourceHeight in QQ_FALLBACK_MIN_SIZE..QQ_FALLBACK_MAX_SIZE
        val dominantPixels = colorBuckets.values.maxOrNull() ?: 0
        val extremelyLowInformation =
            colorBuckets.size <= QQ_FALLBACK_MAX_COLOR_BUCKETS &&
                colorfulPixels * 100 <= visiblePixels * QQ_FALLBACK_MAX_COLORFUL_PERCENT &&
                dominantPixels * 100 >= visiblePixels * QQ_FALLBACK_MIN_DOMINANT_PERCENT

        return nearlySquare && knownFallbackSize && extremelyLowInformation
    }

    private const val SAMPLE_SIZE = 24
    private const val MIN_VALID_ARTWORK_SIZE = 16
    private const val MIN_VISIBLE_ALPHA = 24
    private const val COLOR_BUCKET_SIZE = 32
    private const val COLORFUL_SATURATION_THRESHOLD = 0.12f
    private const val QQ_FALLBACK_MIN_SIZE = 200
    private const val QQ_FALLBACK_MAX_SIZE = 240
    private const val QQ_FALLBACK_MAX_COLOR_BUCKETS = 8
    private const val QQ_FALLBACK_MAX_COLORFUL_PERCENT = 1
    private const val QQ_FALLBACK_MIN_DOMINANT_PERCENT = 70
}
