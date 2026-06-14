package com.example.playeragent.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.playeragent.logging.LogBuffer
import java.util.ArrayDeque
import java.util.LinkedHashSet

class QQMusicLyricAccessibilityService : AccessibilityService() {

    private var previousExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private val accessibilityExceptionHandler:
        Thread.UncaughtExceptionHandler =
        object : Thread.UncaughtExceptionHandler {
            override fun uncaughtException(
                thread: Thread,
                throwable: Throwable
            ) {
            recordThrowable(
                prefix = "[AccessibilityFatal] thread=${thread.name}",
                throwable = throwable
            )
            val previous = previousExceptionHandler
            if (previous != null && previous !== this) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                accessibilityExceptionHandler
            )
            logImportant("[AccessibilityLyric] service onCreate")
        } catch (throwable: Throwable) {
            recordThrowable("[AccessibilityCrash] onCreate", throwable)
        }
    }

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            activeInstance = this
            serviceConnected = true
            logImportant("[AccessibilityLyric] service connected")
        } catch (throwable: Throwable) {
            recordThrowable(
                "[AccessibilityCrash] onServiceConnected",
                throwable
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) {
                logImportant("[AccessibilityLyric] event null")
                return
            }
            if (event.packageName?.toString() != QQ_MUSIC_PACKAGE) {
                return
            }

            val eventType =
                AccessibilityEvent.eventTypeToString(event.eventType)
            val sourceState = try {
                if (event.source == null) "null" else "present"
            } catch (throwable: Throwable) {
                "error=${throwable.javaClass.simpleName}"
            }
            val eventText = try {
                event.text?.joinToString(" | ").orEmpty()
            } catch (throwable: Throwable) {
                "error=${throwable.javaClass.simpleName}"
            }
            logDiagnostic(
                "[AccessibilityLyric] event type=$eventType " +
                    "packageName=${event.packageName} " +
                    "source=$sourceState text=${field(eventText)}"
            )

            val root = try {
                rootInActiveWindow
            } catch (throwable: Throwable) {
                recordThrowable(
                    "[AccessibilityCrash] rootInActiveWindow",
                    throwable
                )
                null
            }
            if (root == null) {
                logImportant("[AccessibilityLyric] root null")
                return
            }

            if (treeDumpRequested) {
                treeDumpRequested = false
                dumpAccessibilityTree(root)
            }
            val currentMedia = readCurrentMedia()
            val traversal = collectTextViewTexts(root)
            logDiagnostic(
                "[AccessibilityLyric] node count=${traversal.nodeCount} " +
                    "text count=${traversal.texts.size}"
            )
            traversal.texts.forEach { text ->
                logLocalOnly("[AccessibilityLyric] text=$text")
            }

            traversal.texts.asSequence()
                .filter { isLyricCandidate(it, currentMedia) }
                .distinct()
                .forEach { candidate ->
                    latestCandidateLyric = candidate
                    if (candidate != lastLoggedCandidate) {
                        lastLoggedCandidate = candidate
                        logImportant("[AccessibilityLyric] candidate=$candidate")
                    }
                }
        } catch (throwable: Throwable) {
            recordThrowable(
                "[AccessibilityCrash] onAccessibilityEvent",
                throwable
            )
        }
    }

    override fun onInterrupt() {
        try {
            logImportant("[AccessibilityLyric] service interrupted")
        } catch (throwable: Throwable) {
            recordThrowable("[AccessibilityCrash] onInterrupt", throwable)
        }
    }

    override fun onDestroy() {
        try {
            serviceConnected = false
            activeInstance = null
            treeDumpRequested = false
            logImportant("[AccessibilityLyric] service destroyed")
            if (Thread.getDefaultUncaughtExceptionHandler() ===
                accessibilityExceptionHandler
            ) {
                Thread.setDefaultUncaughtExceptionHandler(
                    previousExceptionHandler
                )
            }
            super.onDestroy()
        } catch (throwable: Throwable) {
            recordThrowable("[AccessibilityCrash] onDestroy", throwable)
        }
    }

    private fun requestAccessibilityTreeDump() {
        val root = rootInActiveWindow
        if (root?.packageName?.toString() == QQ_MUSIC_PACKAGE) {
            dumpAccessibilityTree(root)
            return
        }

        treeDumpRequested = true
        logImportant(
            "[AccessibilityTree] dump armed; switch to QQMusic to capture next window"
        )
    }

    private fun dumpAccessibilityTree(root: AccessibilityNodeInfo) {
        val queue = ArrayDeque<NodeAtDepth>()
        queue.add(NodeAtDepth(root, 0))
        var nodeCount = 0

        logImportant(
            "[AccessibilityTree] ===== TREE START package=" +
                "${root.packageName} ====="
        )

        try {
            while (queue.isNotEmpty() && nodeCount < MAX_TREE_NODE_COUNT) {
                val current = queue.removeFirst()
                val node = current.node
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                logImportant(
                    "[AccessibilityTree] depth=${current.depth} " +
                        "class=${field(node.className)} " +
                        "text=${field(node.text)} " +
                        "desc=${field(node.contentDescription)} " +
                        "id=${field(node.viewIdResourceName)} " +
                        "bounds=$bounds " +
                        "visibleToUser=${node.isVisibleToUser} " +
                        "clickable=${node.isClickable} " +
                        "enabled=${node.isEnabled} " +
                        "childCount=${node.childCount}"
                )
                nodeCount += 1

                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let {
                        queue.addLast(NodeAtDepth(it, current.depth + 1))
                    }
                }
            }

            logImportant(
                "[AccessibilityTree] ===== TREE END nodes=$nodeCount " +
                    "truncated=${queue.isNotEmpty()} ====="
            )
        } catch (throwable: Throwable) {
            recordThrowable(
                "[AccessibilityCrash] dumpAccessibilityTree nodes=$nodeCount",
                throwable
            )
        }
    }

    private fun field(value: CharSequence?): String {
        val normalized = value
            ?.toString()
            ?.replace('\r', ' ')
            ?.replace('\n', ' ')
            ?.replace('\t', ' ')
            ?.trim()
            ?.ifBlank { "null" }
            ?: "null"
        return if (normalized.length <= MAX_TREE_FIELD_LENGTH) {
            normalized
        } else {
            normalized.take(MAX_TREE_FIELD_LENGTH - TRUNCATED_SUFFIX.length) +
                TRUNCATED_SUFFIX
        }
    }

    private fun collectTextViewTexts(
        root: AccessibilityNodeInfo
    ): TraversalResult {
        val results = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var nodeCount = 0

        while (queue.isNotEmpty() && nodeCount < MAX_TREE_NODE_COUNT) {
            val node = queue.removeFirst()
            nodeCount += 1
            val className = node.className?.toString().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            if (className.contains("TextView", ignoreCase = true) &&
                text.isNotEmpty() &&
                results.size < MAX_TEXT_COUNT
            ) {
                results += text
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return TraversalResult(
            texts = results.toList(),
            nodeCount = nodeCount
        )
    }

    private fun isLyricCandidate(
        text: String,
        currentMedia: CurrentMedia
    ): Boolean {
        val normalized = text.trim()
        if (normalized.length !in MIN_CANDIDATE_LENGTH..MAX_CANDIDATE_LENGTH) {
            return false
        }

        return listOf(
            currentMedia.title,
            currentMedia.artist,
            currentMedia.album
        ).none {
            it.isNotBlank() && normalized.equals(it.trim(), ignoreCase = true)
        }
    }

    private fun readCurrentMedia(): CurrentMedia {
        val manager = getSystemService(MediaSessionManager::class.java)
            ?: return CurrentMedia()
        val listenerComponent = ComponentName(
            this,
            PlayerNotificationListenerService::class.java
        )
        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (throwable: Throwable) {
            logLocalOnly(
                "[AccessibilityLyric] current media unavailable: " +
                    "${throwable.message}"
            )
            return CurrentMedia()
        }

        val controller = controllers.firstOrNull {
            it.packageName == QQ_MUSIC_PACKAGE &&
                it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            it.packageName == QQ_MUSIC_PACKAGE
        } ?: return CurrentMedia()

        val metadata = controller.metadata
        return CurrentMedia(
            title = metadata
                ?.getString(MediaMetadata.METADATA_KEY_TITLE)
                .orEmpty(),
            artist = metadata
                ?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                .orEmpty(),
            album = metadata
                ?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                .orEmpty()
        )
    }

    private fun logImportant(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
        }
        try {
            LogBuffer.append(message)
        } catch (_: Throwable) {
        }
        try {
            publishLog(message)
        } catch (_: Throwable) {
        }
    }

    private fun logDiagnostic(message: String) {
        logImportant(message)
    }

    private fun logLocalOnly(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
        }
        try {
            publishLog(message)
        } catch (_: Throwable) {
        }
    }

    private fun publishLog(message: String) {
        try {
            sendBroadcast(
                Intent(PlayerAgentForegroundService.ACTION_LOG)
                    .setPackage(packageName)
                    .putExtra(
                        PlayerAgentForegroundService.EXTRA_LOG_MESSAGE,
                        message
                    )
            )
        } catch (throwable: Throwable) {
            Log.e(TAG, "publishLog failed", throwable)
        }
    }

    private fun recordThrowable(prefix: String, throwable: Throwable) {
        try {
            Log.e(TAG, prefix, throwable)
        } catch (_: Throwable) {
        }

        val stackTrace = try {
            Log.getStackTraceString(throwable)
        } catch (_: Throwable) {
            "${throwable.javaClass.name}: ${throwable.message}"
        }
        val chunks = ("$prefix\n$stackTrace")
            .chunked(CRASH_LOG_CHUNK_LENGTH)
        chunks.forEachIndexed { index, chunk ->
            logImportant("$prefix part=${index + 1}/${chunks.size} $chunk")
        }
    }

    private data class CurrentMedia(
        val title: String = "",
        val artist: String = "",
        val album: String = ""
    )

    private data class NodeAtDepth(
        val node: AccessibilityNodeInfo,
        val depth: Int
    )

    private data class TraversalResult(
        val texts: List<String>,
        val nodeCount: Int
    )

    companion object {
        @Volatile
        var latestCandidateLyric: String = ""
            private set

        @Volatile
        private var serviceConnected = false

        @Volatile
        private var activeInstance: QQMusicLyricAccessibilityService? = null

        @Volatile
        private var treeDumpRequested = false

        @Volatile
        private var lastLoggedCandidate = ""

        fun isConnected(): Boolean = serviceConnected

        fun requestTreeDump(): Boolean {
            val service = activeInstance ?: return false
            service.requestAccessibilityTreeDump()
            return true
        }

        fun isEnabled(context: Context): Boolean {
            val expectedComponent = ComponentName(
                context,
                QQMusicLyricAccessibilityService::class.java
            )
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            return enabledServices
                .split(':')
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == expectedComponent }
        }

        private const val TAG = "AccessibilityLyric"
        private const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
        private const val MAX_TEXT_COUNT = 30
        private const val MIN_CANDIDATE_LENGTH = 2
        private const val MAX_CANDIDATE_LENGTH = 80
        private const val MAX_TREE_NODE_COUNT = 300
        private const val MAX_TREE_FIELD_LENGTH = 100
        private const val TRUNCATED_SUFFIX = "...<truncated>"
        private const val CRASH_LOG_CHUNK_LENGTH = 220
    }
}
