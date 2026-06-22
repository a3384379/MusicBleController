package com.example.playeragent.media

import android.os.FileObserver
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class QrcDirectoryWatcher(
    private val incrementalPrebuildManager: QrcIncrementalPrebuildManager,
    private val logger: (String) -> Unit,
    private val statusListener: (QrcWatcherStatus) -> Unit
) {

    private val directory: File = QrcLyricUtils.qrcDirectory()
    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "QrcDirectoryWatcherThread").apply {
            priority = Thread.MIN_PRIORITY
        }
    }.apply {
        removeOnCancelPolicy = true
    }
    private val lock = Any()
    private val pendingGroups = linkedSetOf<String>()
    private var debounceFuture: ScheduledFuture<*>? = null
    private var observer: FileObserver? = null
    @Volatile
    private var running = false

    fun start() {
        synchronized(lock) {
            if (running) {
                publishStatusLocked()
                return
            }
            if (!directory.exists() || !directory.isDirectory || !directory.canRead()) {
                logger(
                    "[QrcWatcher] start failed reason=directory unavailable " +
                        "path=${directory.absolutePath}"
                )
                publishStatusLocked()
                return
            }
            observer = object : FileObserver(
                directory.absolutePath,
                EVENT_MASK
            ) {
                override fun onEvent(event: Int, path: String?) {
                    handleEvent(event, path)
                }
            }.also {
                it.startWatching()
            }
            running = true
            logger("[QrcWatcher] started dir=${directory.absolutePath}")
            publishStatusLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            observer?.stopWatching()
            observer = null
            debounceFuture?.cancel(false)
            debounceFuture = null
            pendingGroups.clear()
            running = false
            logger("[QrcWatcher] stopped")
            publishStatusLocked()
        }
    }

    fun isRunning(): Boolean = running

    fun pendingCount(): Int {
        synchronized(lock) {
            return pendingGroups.size
        }
    }

    private fun handleEvent(event: Int, path: String?) {
        val fileName = path ?: return
        val groupId = extractGroupId(fileName) ?: return
        val eventName = eventName(event)
        logger("[QrcWatcher] event=$eventName file=$fileName")
        synchronized(lock) {
            val firstPending = pendingGroups.add(groupId)
            if (firstPending) {
                val oldGeneration = QrcDirectoryGeneration.current()
                val newGeneration = QrcDirectoryGeneration.markChanged(groupId, eventName, logger)
                logger("[QrcWatcher] generation changed old=$oldGeneration new=$newGeneration")
            }
            logger("[QrcWatcher] group changed groupId=$groupId")
            logger("[QrcWatcher] pending groupId=$groupId")
            scheduleDebounceLocked()
            publishStatusLocked()
        }
    }

    private fun scheduleDebounceLocked() {
        debounceFuture?.cancel(false)
        debounceFuture = scheduler.schedule(
            ::processPendingGroups,
            DEBOUNCE_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun processPendingGroups() {
        val batch = synchronized(lock) {
            val selected = pendingGroups.take(MAX_BATCH_GROUPS).toSet()
            selected.forEach(pendingGroups::remove)
            logger("[QrcWatcher] debounce process groups=${selected.size}")
            publishStatusLocked()
            selected
        }
        if (batch.isNotEmpty()) {
            incrementalPrebuildManager.processGroups(batch)
        }
        synchronized(lock) {
            if (pendingGroups.isNotEmpty() && running) {
                scheduleDebounceLocked()
            }
            publishStatusLocked()
        }
    }

    private fun publishStatusLocked() {
        statusListener(
            incrementalPrebuildManager.currentStatus(
                watcherRunning = running,
                pendingGroups = pendingGroups.size
            )
        )
    }

    private fun extractGroupId(fileName: String): String? {
        return GROUP_FILE_REGEX.matchEntire(fileName)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun eventName(event: Int): String {
        val cleanEvent = event and FileObserver.ALL_EVENTS
        return when {
            cleanEvent and FileObserver.CREATE != 0 -> "CREATE"
            cleanEvent and FileObserver.MOVED_TO != 0 -> "MOVED_TO"
            cleanEvent and FileObserver.CLOSE_WRITE != 0 -> "CLOSE_WRITE"
            cleanEvent and FileObserver.DELETE != 0 -> "DELETE"
            cleanEvent and FileObserver.DELETE_SELF != 0 -> "DELETE_SELF"
            cleanEvent and FileObserver.MODIFY != 0 -> "MODIFY"
            else -> cleanEvent.toString()
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 5_000L
        private const val MAX_BATCH_GROUPS = 20
        private const val EVENT_MASK =
                FileObserver.CREATE or
                FileObserver.MOVED_TO or
                FileObserver.CLOSE_WRITE or
                FileObserver.DELETE or
                FileObserver.DELETE_SELF or
                FileObserver.MODIFY
        private val GROUP_FILE_REGEX =
            Regex("""^(-?\d+)\.(qrc|producer|ex|translrc|romaqrc|lrc)$""")
    }
}
