package com.example.playeragent.media

import java.util.LinkedHashMap
import kotlin.math.max

enum class CapabilityStatus {
    READY_FAST,
    READY_SLOW,
    UNAVAILABLE,
    PARSE_FAILED,
    LOAD_FAILED,
    SOURCE_NOT_PROVIDED
}

data class TrackCapabilitySnapshot(
    val trackId: String,
    val protocolId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mediaId: String,
    val packageName: String,
    val sourceApp: String,
    val firstSeenAt: Long,
    val trackChangedAt: Long,
    val mediaSessionHasLyric: Boolean = false,
    val qrcLookupStarted: Boolean = false,
    val qrcLookupSuccess: Boolean = false,
    val qrcLookupFailedReason: String = "",
    val qrcLookupCostMs: Long = 0L,
    val rawLyricsFound: Boolean = false,
    val parsedLyricsSuccess: Boolean = false,
    val parsedLyricsFailedReason: String = "",
    val parseCostMs: Long = 0L,
    val lineCount: Int = 0,
    val hasWordTiming: Boolean = false,
    val firstLyricsReadyAt: Long = 0L,
    val trackChangedToLyricsReadyMs: Long = 0L,
    val firstCurrentWordAt: Long = 0L,
    val trackChangedToFirstCurrentWordMs: Long = 0L,
    val fullLyricsRequestedAt: Long = 0L,
    val fullLyricsSentAt: Long = 0L,
    val fullLyricsDelayMs: Long = 0L,
    val metadataHasBitmap: Boolean = false,
    val metadataHasIconUri: Boolean = false,
    val metadataHasAlbumArtUri: Boolean = false,
    val embeddedArtAttempted: Boolean = false,
    val embeddedArtSuccess: Boolean = false,
    val albumArtLoadStarted: Boolean = false,
    val albumArtLoadSuccess: Boolean = false,
    val albumArtFailedReason: String = "",
    val albumArtSource: String = "",
    val albumArtWidth: Int = 0,
    val albumArtHeight: Int = 0,
    val albumArtByteSize: Int = 0,
    val albumArtReadyAt: Long = 0L,
    val trackChangedToAlbumArtReadyMs: Long = 0L,
    val albumArtRequestedAt: Long = 0L,
    val albumArtSentAt: Long = 0L,
    val albumArtDelayMs: Long = 0L,
    val playbackStateFirstSentAt: Long = 0L,
    val currentWordPushCount: Long = 0L,
    val currentWordStaleBlockedCount: Long = 0L,
    val staleDiscardFromIOS: Long = 0L,
    val payloadTooLarge: Long = 0L,
    val mainStall: Long = 0L
) {
    val lyricsStatus: CapabilityStatus
        get() = when {
            parsedLyricsSuccess && trackChangedToLyricsReadyMs in 1..1000 ->
                CapabilityStatus.READY_FAST
            parsedLyricsSuccess -> CapabilityStatus.READY_SLOW
            parsedLyricsFailedReason.isNotBlank() -> CapabilityStatus.PARSE_FAILED
            qrcLookupStarted && !qrcLookupSuccess -> CapabilityStatus.UNAVAILABLE
            else -> CapabilityStatus.SOURCE_NOT_PROVIDED
        }

    val albumArtStatus: CapabilityStatus
        get() = when {
            albumArtLoadSuccess && trackChangedToAlbumArtReadyMs in 1..1000 ->
                CapabilityStatus.READY_FAST
            albumArtLoadSuccess -> CapabilityStatus.READY_SLOW
            albumArtFailedReason.isNotBlank() -> CapabilityStatus.LOAD_FAILED
            albumArtLoadStarted -> CapabilityStatus.UNAVAILABLE
            else -> CapabilityStatus.SOURCE_NOT_PROVIDED
        }
}

object TrackCapabilityTracker {
    private const val MAX_TRACKS = 24
    private val lock = Any()
    private var logger: ((String) -> Unit)? = null
    private val snapshots = object :
        LinkedHashMap<String, TrackCapabilitySnapshot>(MAX_TRACKS, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, TrackCapabilitySnapshot>?
        ): Boolean = size > MAX_TRACKS
    }

    fun setLogger(logger: (String) -> Unit) {
        synchronized(lock) {
            this.logger = logger
        }
    }

    fun onTrackSeen(
        trackId: String,
        protocolId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mediaId: String,
        packageName: String,
        sourceApp: String
    ) {
        if (trackId.isBlank() && protocolId.isBlank()) {
            return
        }
        val key = key(trackId, protocolId)
        val now = System.currentTimeMillis()
        var shouldLogStart = false
        update(key) { previous ->
            val trackChanged = previous == null ||
                previous.trackId != trackId ||
                previous.title != title ||
                previous.artist != artist
            shouldLogStart = trackChanged
            val snapshot = previous ?: TrackCapabilitySnapshot(
                trackId = trackId,
                protocolId = protocolId,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                mediaId = mediaId,
                packageName = packageName,
                sourceApp = sourceApp,
                firstSeenAt = now,
                trackChangedAt = now
            )
            snapshot.copy(
                trackId = trackId,
                protocolId = protocolId,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                mediaId = mediaId,
                packageName = packageName,
                sourceApp = sourceApp,
                trackChangedAt = if (trackChanged) now else snapshot.trackChangedAt
            )
        }
        if (shouldLogStart) {
            log(
                "[TrackCapability] start track=$trackId protocolId=$protocolId " +
                    "title=${title.take(48)} artist=${artist.take(48)} sourceApp=$sourceApp"
            )
        }
    }

    fun onMediaMetadata(
        trackId: String,
        protocolId: String,
        hasBitmap: Boolean,
        hasIconUri: Boolean,
        hasAlbumArtUri: Boolean
    ) {
        update(key(trackId, protocolId)) { previous ->
            previous?.copy(
                metadataHasBitmap = hasBitmap,
                metadataHasIconUri = hasIconUri,
                metadataHasAlbumArtUri = hasAlbumArtUri
            )
        }
        log(
            "[TrackCapability] media metadata bitmap=$hasBitmap " +
                "iconUri=$hasIconUri albumArtUri=$hasAlbumArtUri"
        )
    }

    fun onLyricLookupStart(songKey: String, trackId: String = "") {
        updateBySongKeyOrTrack(songKey, trackId) { previous ->
            previous.copy(qrcLookupStarted = true)
        }
        log("[TrackCapability] lyric qrc lookup start songKey=$songKey track=$trackId")
    }

    fun onLyricLookupDone(
        songKey: String,
        trackId: String = "",
        success: Boolean,
        reason: String,
        costMs: Long,
        lineCount: Int
    ) {
        updateBySongKeyOrTrack(songKey, trackId) { previous ->
            previous.copy(
                qrcLookupStarted = true,
                qrcLookupSuccess = success,
                qrcLookupFailedReason = if (success) "" else reason,
                qrcLookupCostMs = costMs,
                rawLyricsFound = success && lineCount > 0
            )
        }
        log(
            "[TrackCapability] lyric qrc lookup done success=$success " +
                "costMs=$costMs lineCount=$lineCount reason=$reason"
        )
    }

    fun onLyricParseDone(
        songKey: String,
        trackId: String = "",
        success: Boolean,
        lineCount: Int,
        hasWordTiming: Boolean,
        parseCostMs: Long,
        reason: String = ""
    ) {
        val now = System.currentTimeMillis()
        var latency = 0L
        updateBySongKeyOrTrack(songKey, trackId) { previous ->
            latency = if (success) max(0L, now - previous.trackChangedAt) else 0L
            previous.copy(
                parsedLyricsSuccess = success,
                parsedLyricsFailedReason = if (success) "" else reason,
                parseCostMs = parseCostMs,
                lineCount = lineCount,
                hasWordTiming = hasWordTiming,
                firstLyricsReadyAt = if (success && previous.firstLyricsReadyAt == 0L) {
                    now
                } else {
                    previous.firstLyricsReadyAt
                },
                trackChangedToLyricsReadyMs = if (success) {
                    latency
                } else {
                    previous.trackChangedToLyricsReadyMs
                }
            )
        }
        log(
            "[TrackCapability] lyric parse done success=$success " +
                "lineCount=$lineCount hasWordTiming=$hasWordTiming costMs=$parseCostMs " +
                "reason=$reason"
        )
        if (success) {
            log("[TrackCapability] lyric ready latencyMs=$latency")
        }
        logLatestSummary(songKey = songKey, trackId = trackId, protocolId = "")
    }

    fun onFullLyricsRequested(trackId: String, protocolId: String = "") {
        update(key(trackId, protocolId)) { previous ->
            previous?.copy(fullLyricsRequestedAt = System.currentTimeMillis())
        }
    }

    fun onFullLyricsSent(trackId: String, protocolId: String = "", lines: Int) {
        val now = System.currentTimeMillis()
        var delay = 0L
        update(key(trackId, protocolId)) { previous ->
            val requestedAt = previous?.fullLyricsRequestedAt ?: 0L
            delay = if (requestedAt > 0L) now - requestedAt else 0L
            previous?.copy(
                fullLyricsSentAt = now,
                fullLyricsDelayMs = delay,
                lineCount = if (lines > 0) lines else previous.lineCount,
                parsedLyricsSuccess = if (lines > 0) true else previous.parsedLyricsSuccess,
                firstLyricsReadyAt = if (lines > 0 && previous.firstLyricsReadyAt == 0L) {
                    now
                } else {
                    previous.firstLyricsReadyAt
                },
                trackChangedToLyricsReadyMs = if (lines > 0 && previous.trackChangedToLyricsReadyMs == 0L) {
                    max(0L, now - previous.trackChangedAt)
                } else {
                    previous.trackChangedToLyricsReadyMs
                }
            )
        }
        log("[TrackCapability] fullLyrics sent lines=$lines delayMs=$delay")
        logLatestSummary(songKey = "", trackId = trackId, protocolId = protocolId)
    }

    fun onAlbumArtLoadStart(protocolId: String, trackId: String = "") {
        update(key(trackId, protocolId)) { previous ->
            previous?.copy(albumArtLoadStarted = true)
        }
        log("[TrackCapability] albumArt metadata source=loading protocolId=$protocolId")
    }

    fun onAlbumArtLoadDone(
        protocolId: String,
        trackId: String = "",
        success: Boolean,
        source: String,
        width: Int,
        height: Int,
        byteSize: Int,
        reason: String = ""
    ) {
        val now = System.currentTimeMillis()
        var latency = 0L
        update(key(trackId, protocolId)) { previous ->
            latency = if (success && previous != null) {
                max(0L, now - previous.trackChangedAt)
            } else {
                0L
            }
            previous?.copy(
                albumArtLoadStarted = true,
                albumArtLoadSuccess = success,
                albumArtFailedReason = if (success) "" else reason,
                albumArtSource = source,
                albumArtWidth = width,
                albumArtHeight = height,
                albumArtByteSize = byteSize,
                albumArtReadyAt = if (success) now else previous.albumArtReadyAt,
                trackChangedToAlbumArtReadyMs = if (success) {
                    latency
                } else {
                    previous.trackChangedToAlbumArtReadyMs
                }
            )
        }
        if (success) {
            log(
                "[TrackCapability] albumArt load done success=true source=$source " +
                    "size=${width}x$height bytes=$byteSize latencyMs=$latency"
            )
        } else {
            log("[TrackCapability] albumArt unavailable reason=$reason source=$source")
        }
        logLatestSummary(songKey = "", trackId = trackId, protocolId = protocolId)
    }

    fun onAlbumArtRequested(protocolId: String) {
        update(key("", protocolId)) { previous ->
            previous?.copy(albumArtRequestedAt = System.currentTimeMillis())
        }
    }

    fun onAlbumArtSent(protocolId: String) {
        val now = System.currentTimeMillis()
        var delay = 0L
        update(key("", protocolId)) { previous ->
            val requestedAt = previous?.albumArtRequestedAt ?: 0L
            delay = if (requestedAt > 0L) now - requestedAt else 0L
            previous?.copy(albumArtSentAt = now, albumArtDelayMs = delay)
        }
        log("[TrackCapability] albumArt sent protocolId=$protocolId delayMs=$delay")
        logLatestSummary(songKey = "", trackId = "", protocolId = protocolId)
    }

    fun onPlaybackStateSent(trackId: String, protocolId: String = "") {
        update(key(trackId, protocolId)) { previous ->
            previous?.copy(
                playbackStateFirstSentAt = if (previous.playbackStateFirstSentAt == 0L) {
                    System.currentTimeMillis()
                } else {
                    previous.playbackStateFirstSentAt
                }
            )
        }
    }

    fun onCurrentWordPushed(trackId: String, protocolId: String = "") {
        val now = System.currentTimeMillis()
        update(key(trackId, protocolId)) { previous ->
            previous?.copy(
                currentWordPushCount = previous.currentWordPushCount + 1,
                firstCurrentWordAt = if (previous.firstCurrentWordAt == 0L) {
                    now
                } else {
                    previous.firstCurrentWordAt
                },
                trackChangedToFirstCurrentWordMs = if (previous.firstCurrentWordAt == 0L) {
                    max(0L, now - previous.trackChangedAt)
                } else {
                    previous.trackChangedToFirstCurrentWordMs
                }
            )
        }
    }

    fun onCurrentWordStaleBlocked(trackId: String, protocolId: String = "") {
        update(key(trackId, protocolId)) { previous ->
            previous?.copy(
                currentWordStaleBlockedCount = previous.currentWordStaleBlockedCount + 1
            )
        }
    }

    fun onPayloadTooLarge(trackId: String = "", protocolId: String = "") {
        update(key(trackId, protocolId)) { previous ->
            previous?.copy(payloadTooLarge = previous.payloadTooLarge + 1)
        }
    }

    fun snapshot(): List<TrackCapabilitySnapshot> {
        synchronized(lock) {
            return snapshots.values.toList()
        }
    }

    fun logSummary() {
        snapshot().forEach { item ->
            val lyricReason = when (item.lyricsStatus) {
                CapabilityStatus.READY_FAST,
                CapabilityStatus.READY_SLOW -> "lines=${item.lineCount}"
                CapabilityStatus.PARSE_FAILED -> item.parsedLyricsFailedReason
                CapabilityStatus.UNAVAILABLE -> item.qrcLookupFailedReason
                CapabilityStatus.LOAD_FAILED -> item.parsedLyricsFailedReason
                CapabilityStatus.SOURCE_NOT_PROVIDED -> "source_app_not_provided"
            }
            val artReason = when (item.albumArtStatus) {
                CapabilityStatus.READY_FAST,
                CapabilityStatus.READY_SLOW -> "source=${item.albumArtSource}"
                CapabilityStatus.LOAD_FAILED -> item.albumArtFailedReason
                CapabilityStatus.UNAVAILABLE -> item.albumArtFailedReason
                CapabilityStatus.PARSE_FAILED -> item.albumArtFailedReason
                CapabilityStatus.SOURCE_NOT_PROVIDED -> "source_app_not_provided"
            }
            log(
                "[TrackCapability] summary lyrics=${item.lyricsStatus} " +
                    "albumArt=${item.albumArtStatus} title=${item.title.take(48)} " +
                    "artist=${item.artist.take(48)} lyricReason=$lyricReason " +
                    "albumArtReason=$artReason lyricLatencyMs=${item.trackChangedToLyricsReadyMs} " +
                    "albumArtLatencyMs=${item.trackChangedToAlbumArtReadyMs}"
            )
        }
    }

    private fun update(
        key: String,
        block: (TrackCapabilitySnapshot?) -> TrackCapabilitySnapshot?
    ) {
        if (key.isBlank()) {
            return
        }
        synchronized(lock) {
            val updated = block(snapshots[key]) ?: return
            snapshots[key(updated.trackId, updated.protocolId)] = updated
        }
    }

    private fun updateBySongKeyOrTrack(
        songKey: String,
        trackId: String,
        block: (TrackCapabilitySnapshot) -> TrackCapabilitySnapshot
    ) {
        synchronized(lock) {
            val entry = snapshots.entries.lastOrNull { (_, snapshot) ->
                (trackId.isNotBlank() && sameId(snapshot.trackId, trackId)) ||
                    songKey == "${snapshot.title.trim()}|${snapshot.artist.trim()}|${snapshot.album.trim()}"
            } ?: return
            snapshots[entry.key] = block(entry.value)
        }
    }

    private fun key(trackId: String, protocolId: String): String {
        return normalizeId(trackId.ifBlank { protocolId })
    }

    private fun normalizeId(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length > 12) trimmed.take(12) else trimmed
    }

    private fun sameId(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) {
            return false
        }
        return left == right || normalizeId(left) == normalizeId(right)
    }

    private fun log(message: String) {
        synchronized(lock) {
            logger?.invoke(message)
        }
    }

    private fun logLatestSummary(songKey: String, trackId: String, protocolId: String) {
        val item = synchronized(lock) {
            snapshots.values.lastOrNull { snapshot ->
                (trackId.isNotBlank() && snapshot.trackId == trackId) ||
                    (trackId.isNotBlank() && sameId(snapshot.trackId, trackId)) ||
                    (protocolId.isNotBlank() && sameId(snapshot.protocolId, protocolId)) ||
                    (songKey.isNotBlank() &&
                        songKey == "${snapshot.title.trim()}|${snapshot.artist.trim()}|${snapshot.album.trim()}")
            }
        } ?: return
        val lyricReason = when (item.lyricsStatus) {
            CapabilityStatus.READY_FAST,
            CapabilityStatus.READY_SLOW -> "lines=${item.lineCount}"
            CapabilityStatus.PARSE_FAILED -> item.parsedLyricsFailedReason
            CapabilityStatus.UNAVAILABLE -> item.qrcLookupFailedReason
            CapabilityStatus.LOAD_FAILED -> item.parsedLyricsFailedReason
            CapabilityStatus.SOURCE_NOT_PROVIDED -> "source_app_not_provided"
        }
        val artReason = when (item.albumArtStatus) {
            CapabilityStatus.READY_FAST,
            CapabilityStatus.READY_SLOW -> "source=${item.albumArtSource}"
            CapabilityStatus.LOAD_FAILED -> item.albumArtFailedReason
            CapabilityStatus.UNAVAILABLE -> item.albumArtFailedReason
            CapabilityStatus.PARSE_FAILED -> item.albumArtFailedReason
            CapabilityStatus.SOURCE_NOT_PROVIDED -> "source_app_not_provided"
        }
        log(
            "[TrackCapability] summary lyrics=${item.lyricsStatus} " +
                "albumArt=${item.albumArtStatus} title=${item.title.take(48)} " +
                "artist=${item.artist.take(48)} lyricReason=$lyricReason " +
                "albumArtReason=$artReason lyricLatencyMs=${item.trackChangedToLyricsReadyMs} " +
                "albumArtLatencyMs=${item.trackChangedToAlbumArtReadyMs}"
        )
    }
}
