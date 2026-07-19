package com.example.playeragent.ble

internal data class AlbumArtCompressionProfile(
    val width: Int,
    val height: Int,
    val quality: Int
)

/**
 * Ordered album-art compression fallbacks. The transport byte and packet caps
 * remain enforced by [BleGattServerManager]; these profiles only ensure that a
 * high-entropy source can keep scaling down until it fits those existing caps.
 */
internal object AlbumArtCompressionPolicy {
    fun previewProfiles(): List<AlbumArtCompressionProfile> = listOf(
        AlbumArtCompressionProfile(112, 112, 48),
        AlbumArtCompressionProfile(104, 104, 45),
        AlbumArtCompressionProfile(96, 96, 42),
        AlbumArtCompressionProfile(88, 88, 40),
        AlbumArtCompressionProfile(80, 80, 38),
        AlbumArtCompressionProfile(72, 72, 35),
        AlbumArtCompressionProfile(64, 64, 32),
        AlbumArtCompressionProfile(56, 56, 28),
        AlbumArtCompressionProfile(48, 48, 24),
        AlbumArtCompressionProfile(40, 40, 20)
    )

    fun hqProfiles(sourceWidth: Int, sourceHeight: Int): List<AlbumArtCompressionProfile> =
        listOf(
            AlbumArtCompressionProfile(sourceWidth, sourceHeight, 80),
            AlbumArtCompressionProfile(sourceWidth, sourceHeight, 76),
            cappedProfile(sourceWidth, sourceHeight, 280, 76),
            cappedProfile(sourceWidth, sourceHeight, 256, 74),
            cappedProfile(sourceWidth, sourceHeight, 240, 72),
            cappedProfile(sourceWidth, sourceHeight, 224, 70),
            cappedProfile(sourceWidth, sourceHeight, 208, 68),
            cappedProfile(sourceWidth, sourceHeight, 192, 66),
            cappedProfile(sourceWidth, sourceHeight, 176, 62),
            cappedProfile(sourceWidth, sourceHeight, 160, 58),
            cappedProfile(sourceWidth, sourceHeight, 144, 54),
            cappedProfile(sourceWidth, sourceHeight, 128, 50)
        ).distinctBy { "${it.width}x${it.height}@${it.quality}" }

    private fun cappedProfile(
        sourceWidth: Int,
        sourceHeight: Int,
        maximumEdge: Int,
        quality: Int
    ) = AlbumArtCompressionProfile(
        width = minOf(sourceWidth, maximumEdge),
        height = minOf(sourceHeight, maximumEdge),
        quality = quality
    )
}
