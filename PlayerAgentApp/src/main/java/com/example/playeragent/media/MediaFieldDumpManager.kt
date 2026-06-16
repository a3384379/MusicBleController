package com.example.playeragent.media

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import android.widget.RemoteViews
import com.example.playeragent.service.PlayerNotificationListenerService
import com.example.playeragent.service.QQMusicLyricAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaFieldDumpManager(context: Context) {

    private val appContext = context.applicationContext
    private val mediaSessionManager =
        appContext.getSystemService(MediaSessionManager::class.java)

    fun dumpAllFields(): String {
        val output = StringBuilder()
        output.appendLine("===== MEDIA FIELD DUMP START =====")
        output.appendLine()
        output.appendLine("[Current Time]")
        output.appendLine(
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS Z",
                Locale.US
            ).format(Date())
        )
        output.appendLine()

        appendMediaSessions(output)
        appendNotifications(output)
        appendAccessibilitySummary(output)

        output.appendLine("===== MEDIA FIELD DUMP END =====")
        return truncateUtf8(output.toString(), MAX_DUMP_BYTES)
    }

    private fun appendMediaSessions(output: StringBuilder) {
        val controllers = activeControllers()
        output.appendLine("[Active Sessions]")
        output.appendLine("count=${controllers.size}")
        output.appendLine()

        controllers.forEachIndexed { index, controller ->
            output.appendLine("[MediaSession #$index]")
            output.appendLine("package=${safeText(controller.packageName)}")
            output.appendLine()
            appendMetadata(output, controller.metadata)
            appendPlaybackState(output, controller)
        }
    }

    private fun activeControllers(): List<MediaController> {
        val manager = mediaSessionManager ?: return emptyList()
        return try {
            manager.getActiveSessions(
                ComponentName(
                    appContext,
                    PlayerNotificationListenerService::class.java
                )
            )
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun appendMetadata(
        output: StringBuilder,
        metadata: MediaMetadata?
    ) {
        output.appendLine("[Metadata]")
        if (metadata == null) {
            output.appendLine("null")
            output.appendLine()
            return
        }

        metadata.keySet().sorted().forEach { key ->
            val field = readMetadataField(metadata, key)
            appendField(output, key, field.type, field.value)
        }
    }

    private fun readMetadataField(
        metadata: MediaMetadata,
        key: String
    ): DumpField {
        val bitmap = tryValue { metadata.getBitmap(key) }
        if (bitmap != null) {
            return DumpField(
                type = "Bitmap",
                value = bitmapSummary(bitmap)
            )
        }

        val rating = tryValue { metadata.getRating(key) }
        if (rating != null) {
            return DumpField(
                type = "Rating",
                value = ratingSummary(rating)
            )
        }

        val string = tryValue { metadata.getString(key) }
        if (!string.isNullOrEmpty()) {
            return DumpField(type = "String", value = string)
        }

        val text = tryValue { metadata.getText(key) }
        if (text != null) {
            return DumpField(type = "Text", value = text.toString())
        }

        val longValue = tryValue { metadata.getLong(key) }
        if (longValue != null) {
            return DumpField(type = "Long", value = longValue.toString())
        }

        return DumpField(type = "Unknown", value = "unavailable")
    }

    private fun appendPlaybackState(
        output: StringBuilder,
        controller: MediaController
    ) {
        output.appendLine("[PlaybackState]")
        val state = tryValue { controller.playbackState }
        if (state == null) {
            output.appendLine("null")
            output.appendLine()
            return
        }

        output.appendLine("state=${state.state}")
        output.appendLine("position=${state.position}")
        output.appendLine("bufferedPosition=${state.bufferedPosition}")
        output.appendLine("speed=${state.playbackSpeed}")
        output.appendLine("actions=${state.actions}")
        output.appendLine("errorMessage=${safeText(state.errorMessage)}")
        output.appendLine("activeQueueItemId=${state.activeQueueItemId}")
        output.appendLine()
        output.appendLine("[PlaybackState Extras]")
        appendBundle(output, state.extras, depth = 0)
    }

    private fun appendNotifications(output: StringBuilder) {
        val notifications =
            PlayerNotificationListenerService.activeNotificationsSnapshot()
                .filter { it.packageName == QQ_MUSIC_PACKAGE }

        if (notifications.isEmpty()) {
            output.appendLine("[Notification]")
            output.appendLine("QQMusic notification not found")
            output.appendLine()
            return
        }

        notifications.forEachIndexed { index, statusBarNotification ->
            appendNotification(output, index, statusBarNotification)
        }
    }

    private fun appendNotification(
        output: StringBuilder,
        index: Int,
        statusBarNotification: StatusBarNotification
    ) {
        val notification = statusBarNotification.notification
        output.appendLine("[Notification #$index]")
        output.appendLine("package=${statusBarNotification.packageName}")
        output.appendLine("id=${statusBarNotification.id}")
        output.appendLine("tag=${safeText(statusBarNotification.tag)}")
        output.appendLine("postTime=${statusBarNotification.postTime}")
        output.appendLine("category=${safeText(notification.category)}")
        output.appendLine("tickerText=${safeText(notification.tickerText)}")
        output.appendLine("flags=${notification.flags}")
        output.appendLine("priority=${notification.priority}")
        output.appendLine("visibility=${notification.visibility}")
        output.appendLine()

        output.appendLine("[Notification Extras]")
        appendBundle(output, notification.extras, depth = 0)

        output.appendLine("[Notification Views]")
        appendRemoteViews(
            output,
            "contentView",
            @Suppress("DEPRECATION") notification.contentView
        )
        appendRemoteViews(
            output,
            "bigContentView",
            @Suppress("DEPRECATION") notification.bigContentView
        )
        appendRemoteViews(
            output,
            "headsUpContentView",
            @Suppress("DEPRECATION") notification.headsUpContentView
        )
        output.appendLine()
    }

    private fun appendBundle(
        output: StringBuilder,
        bundle: Bundle?,
        depth: Int
    ) {
        if (bundle == null) {
            output.appendLine("null")
            output.appendLine()
            return
        }
        if (bundle.isEmpty) {
            output.appendLine("empty")
            output.appendLine()
            return
        }

        bundle.keySet().sorted().forEach { key ->
            val value = tryValue { bundle.get(key) }
            val summary = summarizeValue(value)
            appendField(
                output = output,
                key = if (depth == 0) key else "bundle.$key",
                type = summary.type,
                value = summary.value
            )

            if (value is Bundle && depth == 0) {
                appendBundle(output, value, depth = 1)
            }
        }
    }

    private fun appendRemoteViews(
        output: StringBuilder,
        name: String,
        remoteViews: RemoteViews?
    ) {
        output.appendLine("$name exists=${remoteViews != null}")
        if (remoteViews == null) {
            return
        }

        try {
            val actionsField = remoteViews.javaClass.getDeclaredField("mActions")
            actionsField.isAccessible = true
            val actions = actionsField.get(remoteViews) as? Collection<*>
            output.appendLine("$name actionCount=${actions?.size ?: 0}")
            actions.orEmpty().take(MAX_REMOTE_VIEW_ACTIONS).forEachIndexed {
                    index,
                    action ->
                output.appendLine(
                    "$name action[$index]=" +
                        safeText(action?.javaClass?.name)
                )
            }
        } catch (throwable: Throwable) {
            output.appendLine(
                "$name RemoteViews reflection failed: " +
                    "${throwable.javaClass.simpleName}: " +
                    safeText(throwable.message)
            )
        }
    }

    private fun appendAccessibilitySummary(output: StringBuilder) {
        output.appendLine("[Accessibility Summary]")
        output.appendLine(
            "enabled=" +
                QQMusicLyricAccessibilityService.isEnabled(appContext)
        )
        output.appendLine(
            "connected=" +
                QQMusicLyricAccessibilityService.isConnected()
        )
        output.appendLine(
            "latestCandidate=" +
                safeText(
                    QQMusicLyricAccessibilityService.latestCandidateLyric
                )
        )
        output.appendLine(
            "lastTexts=" +
                QQMusicLyricAccessibilityService.latestTextsSnapshot()
                    .joinToString(" | ") { safeText(it) }
        )
        output.appendLine()
    }

    private fun appendField(
        output: StringBuilder,
        key: String,
        type: String,
        value: String
    ) {
        val safeKey = safeText(key)
        val safeValue = safeText(value)
        output.appendLine("key=$safeKey")
        output.appendLine("type=$type")
        output.appendLine("value=$safeValue")
        output.appendLine()

        if (containsKeyword(safeKey) || containsKeyword(safeValue)) {
            output.appendLine("[KEYWORD HIT]")
            output.appendLine("key=$safeKey")
            output.appendLine("value=$safeValue")
            output.appendLine()
        }
    }

    private fun summarizeValue(value: Any?): DumpField {
        return when (value) {
            null -> DumpField("null", "null")
            is String -> DumpField("String", value)
            is CharSequence -> DumpField(
                value.javaClass.simpleName.ifBlank { "CharSequence" },
                value.toString()
            )
            is Array<*> -> DumpField(
                value.javaClass.simpleName,
                value.take(MAX_COLLECTION_ITEMS)
                    .joinToString(prefix = "[", postfix = "]") {
                        safeText(it)
                    }
            )
            is IntArray -> DumpField("IntArray", value.contentToString())
            is LongArray -> DumpField("LongArray", value.contentToString())
            is BooleanArray -> DumpField(
                "BooleanArray",
                value.contentToString()
            )
            is ByteArray -> DumpField("ByteArray", "bytes=${value.size}")
            is ArrayList<*> -> DumpField(
                "ArrayList",
                value.take(MAX_COLLECTION_ITEMS)
                    .joinToString(prefix = "[", postfix = "]") {
                        safeText(it)
                    }
            )
            is Bundle -> DumpField(
                "Bundle",
                "keys=${value.keySet().sorted().joinToString()}"
            )
            is Icon -> iconSummary(value)
            is Bitmap -> DumpField("Bitmap", bitmapSummary(value))
            is Rating -> DumpField("Rating", ratingSummary(value))
            is RemoteInput -> DumpField(
                "RemoteInput",
                "resultKey=${value.resultKey} label=${safeText(value.label)}"
            )
            is Parcelable -> DumpField(
                value.javaClass.simpleName.ifBlank { "Parcelable" },
                safeText(value)
            )
            else -> DumpField(
                value.javaClass.simpleName.ifBlank { "Unknown" },
                safeText(value)
            )
        }
    }

    private fun iconSummary(icon: Icon): DumpField {
        val dimensions = try {
            val drawable = icon.loadDrawable(appContext)
            if (drawable == null) {
                "Icon exists"
            } else {
                "Icon exists width=${drawable.intrinsicWidth} " +
                    "height=${drawable.intrinsicHeight}"
            }
        } catch (throwable: Throwable) {
            "Icon exists load failed=${throwable.javaClass.simpleName}"
        }
        return DumpField("Icon", dimensions)
    }

    private fun bitmapSummary(bitmap: Bitmap): String {
        return "Bitmap width=${bitmap.width} height=${bitmap.height}"
    }

    private fun ratingSummary(rating: Rating): String {
        return "style=${rating.ratingStyle} rated=${rating.isRated} " +
            "value=${safeText(rating)}"
    }

    private fun containsKeyword(value: String): Boolean {
        val lowercase = value.lowercase(Locale.ROOT)
        return KEYWORDS.any { keyword ->
            lowercase.contains(keyword.lowercase(Locale.ROOT))
        }
    }

    private fun safeText(value: Any?): String {
        val text = value
            ?.toString()
            ?.replace('\u0000', ' ')
            ?: "null"
        return if (text.length <= MAX_VALUE_CHARS) {
            text
        } else {
            text.take(MAX_VALUE_CHARS) + "...<value truncated>"
        }
    }

    private fun truncateUtf8(value: String, maximumBytes: Int): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maximumBytes) {
            return value
        }

        val suffix =
            "\n[TRUNCATED]\n\n===== MEDIA FIELD DUMP END =====\n"
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
        var low = 0
        var high = value.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            val candidateSize =
                value.substring(0, middle)
                    .toByteArray(Charsets.UTF_8)
                    .size
            if (candidateSize + suffixBytes <= maximumBytes) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        return value.substring(0, low) + suffix
    }

    private inline fun <T> tryValue(block: () -> T): T? {
        return try {
            block()
        } catch (_: Throwable) {
            null
        }
    }

    private data class DumpField(
        val type: String,
        val value: String
    )

    companion object {
        private const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
        private const val MAX_DUMP_BYTES = 60 * 1024
        private const val MAX_VALUE_CHARS = 4000
        private const val MAX_COLLECTION_ITEMS = 100
        private const val MAX_REMOTE_VIEW_ACTIONS = 100

        private val KEYWORDS = listOf(
            "lyric",
            "lyrics",
            "lrc",
            "qrc",
            "current",
            "line",
            "text",
            "sentence",
            "word",
            "karaoke",
            "qqmusic",
            "song",
            "music",
            "歌词",
            "逐字",
            "当前歌词"
        )
    }
}
