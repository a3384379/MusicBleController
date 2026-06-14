package com.example.playeragent.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.playeragent.service.PlayerNotificationListenerService
import java.util.Locale

data class LyricProbeResult(
    val lyric: String,
    val source: String,
    val detail: String
)

object CurrentLyricProbe {

    fun probeCurrentLyric(context: Context): LyricProbeResult {
        val appContext = context.applicationContext
        val mediaSessionManager =
            appContext.getSystemService(MediaSessionManager::class.java)
        val controllers = readActiveControllers(appContext, mediaSessionManager)
        val controller = selectQqMusicController(controllers)
        val track = readTrackInfo(controller)
        val summary = mutableListOf<String>()

        val notification = selectQqMusicNotification(
            PlayerNotificationListenerService.activeNotificationsSnapshot()
        )
        val remoteViewsState = inspectRemoteViews(notification)
        val notificationCandidates = inspectNotificationExtras(
            notification = notification,
            track = track,
            summary = summary
        )
        selectBestCandidate(notificationCandidates)?.let { selected ->
            return LyricProbeResult(
                lyric = selected.value,
                source = "Notification extras: ${selected.key}",
                detail = buildDetail(track, summary)
            )
        }

        val metadataCandidates = inspectMediaMetadata(
            controller = controller,
            track = track,
            summary = summary
        )
        selectBestCandidate(metadataCandidates)?.let { selected ->
            return LyricProbeResult(
                lyric = selected.value,
                source = "MediaMetadata: key=${selected.key}",
                detail = buildDetail(track, summary)
            )
        }

        if (remoteViewsState.anyExists) {
            return LyricProbeResult(
                lyric = "",
                source = "RemoteViews exists, but not parsed",
                detail =
                    "${track.detailLine()}\n" +
                        "QQMusic may render lyric inside custom notification RemoteViews. " +
                        "Need AccessibilityService or RemoteViews reflection in next step.\n" +
                        candidateSummary(summary)
            )
        }

        return LyricProbeResult(
            lyric = "",
            source = "none",
            detail = buildDetail(track, summary)
        )
    }

    private fun readActiveControllers(
        context: Context,
        manager: MediaSessionManager?
    ): List<MediaController> {
        if (manager == null) {
            log("[LyricProbe][MediaMetadata] MediaSessionManager unavailable")
            return emptyList()
        }

        return try {
            manager.getActiveSessions(
                ComponentName(
                    context,
                    PlayerNotificationListenerService::class.java
                )
            )
        } catch (exception: Exception) {
            log(
                "[LyricProbe][MediaMetadata] " +
                    "getActiveSessions failed=${exception.message}"
            )
            emptyList()
        }
    }

    private fun selectQqMusicController(
        controllers: List<MediaController>
    ): MediaController? {
        val qqMusicControllers = controllers.filter {
            it.packageName == QQ_MUSIC_PACKAGE
        }
        return qqMusicControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: qqMusicControllers.firstOrNull()
    }

    private fun selectQqMusicNotification(
        notifications: List<StatusBarNotification>
    ): StatusBarNotification? {
        return notifications.firstOrNull {
            it.packageName == QQ_MUSIC_PACKAGE
        }
    }

    private fun readTrackInfo(controller: MediaController?): TrackInfo {
        val metadata = controller?.metadata
        val playbackState = controller?.playbackState
        return TrackInfo(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            position = calculatePosition(playbackState),
            duration =
                metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        ).also {
            log("[LyricProbe][Track] ${it.detailLine()}")
        }
    }

    private fun inspectNotificationExtras(
        notification: StatusBarNotification?,
        track: TrackInfo,
        summary: MutableList<String>
    ): List<Candidate> {
        if (notification == null) {
            log("[LyricProbe][Notification] QQ Music notification not found")
            summary += "Notification: QQ Music notification not found"
            return emptyList()
        }

        log("[LyricProbe][Notification] package=${notification.packageName}")
        val extras = notification.notification.extras ?: Bundle.EMPTY
        val priorityKeys = setOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG
        )
        val candidates = mutableListOf<Candidate>()

        extras.keySet().sorted().forEach { key ->
            val value = extras.get(key)
            if (value is String || value is CharSequence) {
                val text = value.toString().trim()
                log("[LyricProbe][Notification]\nkey=$key value=$text")
                val accepted = isLyricCandidate(text, track)
                summary += summarize("Notification", key, text, accepted)
                if (accepted) {
                    candidates += Candidate(
                        key = key,
                        value = text,
                        score = scoreCandidate(
                            key = key,
                            value = text,
                            preferredKey = key in priorityKeys ||
                                key == Notification.EXTRA_TEXT ||
                                key == Notification.EXTRA_BIG_TEXT,
                            metadataKeyword = false
                        )
                    )
                }
            }
        }

        return candidates
    }

    private fun inspectMediaMetadata(
        controller: MediaController?,
        track: TrackInfo,
        summary: MutableList<String>
    ): List<Candidate> {
        val metadata = controller?.metadata
        if (metadata == null) {
            log("[LyricProbe][MediaMetadata] QQ Music metadata unavailable")
            summary += "MediaMetadata: unavailable"
            return emptyList()
        }

        val candidates = mutableListOf<Candidate>()
        metadata.keySet().sorted().forEach { key ->
            val text = try {
                metadata.getText(key)?.toString()?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (text.isEmpty()) {
                return@forEach
            }

            val highPriority =
                containsMetadataKeyword(key) || containsMetadataKeyword(text)
            log("[LyricProbe][MediaMetadata]\nkey=$key value=$text")
            if (highPriority) {
                log("[LyricProbe][MediaMetadata][HIGH] key=$key value=$text")
            }

            val accepted = isLyricCandidate(text, track)
            summary += summarize("MediaMetadata", key, text, accepted)
            if (accepted) {
                candidates += Candidate(
                    key = key,
                    value = text,
                    score = scoreCandidate(
                        key = key,
                        value = text,
                        preferredKey = false,
                        metadataKeyword = highPriority
                    )
                )
            }
        }
        return candidates
    }

    private fun inspectRemoteViews(
        notification: StatusBarNotification?
    ): RemoteViewsState {
        val state = RemoteViewsState(
            contentView = notification?.notification?.contentView != null,
            bigContentView = notification?.notification?.bigContentView != null,
            headsUpContentView =
                notification?.notification?.headsUpContentView != null
        )
        log("[LyricProbe][RemoteViews]")
        log("[LyricProbe][RemoteViews] contentView exists=${state.contentView}")
        log(
            "[LyricProbe][RemoteViews] " +
                "bigContentView exists=${state.bigContentView}"
        )
        log(
            "[LyricProbe][RemoteViews] " +
                "headsUpContentView exists=${state.headsUpContentView}"
        )
        return state
    }

    private fun isLyricCandidate(value: String, track: TrackInfo): Boolean {
        val normalized = value.trim()
        if (normalized.length < 2 || normalized.all(Char::isDigit)) {
            return false
        }

        val excludedValues = listOf(
            track.title,
            track.artist,
            track.album,
            "QQ音乐",
            "播放中",
            "已暂停",
            QQ_MUSIC_PACKAGE
        )
        if (excludedValues.any {
                it.isNotBlank() && normalized.equals(it.trim(), ignoreCase = true)
            }
        ) {
            return false
        }

        val lowercase = normalized.lowercase(Locale.ROOT)
        if (lowercase.contains("com.tencent") ||
            lowercase == "qqmusic" ||
            lowercase == "qq music"
        ) {
            return false
        }
        return true
    }

    private fun scoreCandidate(
        key: String,
        value: String,
        preferredKey: Boolean,
        metadataKeyword: Boolean
    ): Int {
        var score = 0
        if (containsChinese(value)) {
            score += 100
        }
        if (value.length in 4..80) {
            score += 40
        }
        if (preferredKey) {
            score += 30
        }
        if (key == Notification.EXTRA_TEXT ||
            key == Notification.EXTRA_BIG_TEXT
        ) {
            score += 20
        }
        if (metadataKeyword) {
            score += 80
        }
        return score
    }

    private fun selectBestCandidate(candidates: List<Candidate>): Candidate? {
        return candidates.maxWithOrNull(
            compareBy<Candidate> { it.score }
                .thenBy { it.value.length.coerceAtMost(80) }
        )
    }

    private fun containsChinese(value: String): Boolean {
        return value.any { character ->
            Character.UnicodeScript.of(character.code) ==
                Character.UnicodeScript.HAN
        }
    }

    private fun containsMetadataKeyword(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
        return METADATA_KEYWORDS.any(normalized::contains)
    }

    private fun summarize(
        source: String,
        key: String,
        value: String,
        accepted: Boolean
    ): String {
        val displayValue = value.replace("\n", " ").take(MAX_SUMMARY_VALUE_LENGTH)
        return "$source key=$key accepted=$accepted value=$displayValue"
    }

    private fun buildDetail(
        track: TrackInfo,
        summary: List<String>
    ): String {
        return "${track.detailLine()}\n${candidateSummary(summary)}"
    }

    private fun candidateSummary(summary: List<String>): String {
        if (summary.isEmpty()) {
            return "Candidates: none"
        }
        return "Candidates:\n" + summary
            .distinct()
            .joinToString("\n")
    }

    private fun calculatePosition(playbackState: PlaybackState?): Long {
        if (playbackState == null) {
            return 0L
        }
        val basePosition = playbackState.position.coerceAtLeast(0L)
        if (playbackState.state != PlaybackState.STATE_PLAYING) {
            return basePosition
        }
        val elapsed =
            SystemClock.elapsedRealtime() - playbackState.lastPositionUpdateTime
        return (
            basePosition +
                (elapsed * playbackState.playbackSpeed).toLong()
            ).coerceAtLeast(0L)
    }

    private fun log(message: String) {
        Log.i(LOG_TAG, message)
    }

    private data class Candidate(
        val key: String,
        val value: String,
        val score: Int
    )

    private data class TrackInfo(
        val title: String,
        val artist: String,
        val album: String,
        val position: Long,
        val duration: Long
    ) {
        fun detailLine(): String {
            return "title=$title artist=$artist album=$album " +
                "position=$position duration=$duration"
        }
    }

    private data class RemoteViewsState(
        val contentView: Boolean,
        val bigContentView: Boolean,
        val headsUpContentView: Boolean
    ) {
        val anyExists: Boolean
            get() = contentView || bigContentView || headsUpContentView
    }

    private const val LOG_TAG = "CurrentLyricProbe"
    private const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
    private const val MAX_SUMMARY_VALUE_LENGTH = 120
    private val METADATA_KEYWORDS = listOf(
        "lyric",
        "lrc",
        "currentlyric",
        "qqmusic",
        "line"
    )
}
