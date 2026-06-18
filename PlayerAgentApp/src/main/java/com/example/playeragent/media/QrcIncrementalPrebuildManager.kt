package com.example.playeragent.media

import android.content.Context
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrcIncrementalPrebuildManager(
    context: Context,
    private val logger: (String) -> Unit,
    private val statusListener: (QrcWatcherStatus) -> Unit
) {

    private val appContext = context.applicationContext
    private val cacheManager = QrcLyricCacheManager(
        context = appContext,
        logger = logger
    )
    private val negativeCacheManager = QrcNegativeCacheManager(
        context = appContext,
        logger = logger
    )
    private val persistentIndexManager = QrcPersistentIndexManager(
        context = appContext,
        logger = logger
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QrcIncrementalPrebuildThread").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private val stopped = AtomicBoolean(false)
    private val statusLock = Any()
    private var running = false
    private var success = 0
    private var failed = 0
    private var skipped = 0

    fun processGroups(groupIds: Set<String>) {
        val cleanGroupIds = groupIds.filter { it.isNotBlank() }.distinct()
        if (cleanGroupIds.isEmpty() || stopped.get()) {
            return
        }
        executor.execute {
            if (stopped.get()) {
                return@execute
            }
            setRunning(true)
            try {
                cleanGroupIds.forEachIndexed { index, groupId ->
                    if (stopped.get()) {
                        return@forEachIndexed
                    }
                    processGroup(groupId)
                    if ((index + 1) % THROTTLE_INTERVAL == 0) {
                        Thread.sleep(THROTTLE_SLEEP_MS)
                    }
                }
            } catch (exception: Exception) {
                logger("[QrcIncremental] failed reason=${exception.message}")
                incrementFailed()
            } finally {
                setRunning(false)
            }
        }
    }

    fun stop() {
        stopped.set(true)
        executor.shutdownNow()
        setRunning(false)
    }

    fun currentStatus(
        watcherRunning: Boolean,
        pendingGroups: Int
    ): QrcWatcherStatus {
        synchronized(statusLock) {
            return QrcWatcherStatus(
                watcherRunning = watcherRunning,
                pendingGroups = pendingGroups,
                incrementalRunning = running,
                incrementalSuccess = success,
                incrementalFailed = failed,
                incrementalSkipped = skipped
            )
        }
    }

    private fun processGroup(groupId: String) {
        logger("[QrcIncremental] start groupId=$groupId")
        val group = findGroup(groupId)
        if (group.qrcFile == null) {
            logger("[QrcIncremental] skip no qrc groupId=$groupId")
            persistentIndexManager.markDirty(groupId)
            incrementSkipped()
            return
        }
        if (cacheManager.isGroupCacheValid(group)) {
            logger("[QrcIncremental] skip cached groupId=$groupId")
            persistentIndexManager.markDirty(groupId)
            incrementSkipped()
            return
        }
        val parsed = try {
            QrcLyricUtils.decryptAndParseGroup(group)
        } catch (exception: Exception) {
            logger("[QrcIncremental] failed groupId=$groupId reason=${exception.message}")
            null
        }
        if (parsed == null || parsed.title.isBlank() || parsed.lines.isEmpty()) {
            logger("[QrcIncremental] failed groupId=$groupId reason=parse empty")
            persistentIndexManager.markDirty(groupId)
            incrementFailed()
            return
        }
        cacheManager.save(parsed)
        negativeCacheManager.removeNegative(parsed.songKey)
        persistentIndexManager.markDirty(groupId)
        logger(
            "[QrcIncremental] success groupId=$groupId " +
                "title=${parsed.title} lines=${parsed.lines.size}"
        )
        incrementSuccess()
    }

    private fun findGroup(groupId: String): QrcFileGroup {
        val directory = QrcLyricUtils.qrcDirectory()
        var qrcFile: File? = null
        var producerFile: File? = null
        var exFile: File? = null
        var translrcFile: File? = null
        var romaqrcFile: File? = null
        SUPPORTED_SUFFIXES.forEach { suffix ->
            val file = File(directory, "$groupId.$suffix")
            if (file.exists() && file.isFile) {
                when (suffix) {
                    "qrc" -> qrcFile = file
                    "producer" -> producerFile = file
                    "ex" -> exFile = file
                    "translrc" -> translrcFile = file
                    "romaqrc" -> romaqrcFile = file
                }
            }
        }
        val files = listOfNotNull(
            qrcFile,
            producerFile,
            exFile,
            translrcFile,
            romaqrcFile
        )
        return QrcFileGroup(
            groupId = groupId,
            qrcFile = qrcFile,
            producerFile = producerFile,
            exFile = exFile,
            translrcFile = translrcFile,
            romaqrcFile = romaqrcFile,
            lastModified = files.maxOfOrNull(File::lastModified) ?: 0L
        )
    }

    private fun setRunning(value: Boolean) {
        synchronized(statusLock) {
            running = value
            publishLocked()
        }
    }

    private fun incrementSuccess() {
        synchronized(statusLock) {
            success += 1
            publishLocked()
        }
    }

    private fun incrementFailed() {
        synchronized(statusLock) {
            failed += 1
            publishLocked()
        }
    }

    private fun incrementSkipped() {
        synchronized(statusLock) {
            skipped += 1
            publishLocked()
        }
    }

    private fun publishLocked() {
        statusListener(
            QrcWatcherStatus(
                watcherRunning = true,
                pendingGroups = 0,
                incrementalRunning = running,
                incrementalSuccess = success,
                incrementalFailed = failed,
                incrementalSkipped = skipped
            )
        )
    }

    companion object {
        private const val THROTTLE_INTERVAL = 10
        private const val THROTTLE_SLEEP_MS = 200L
        private val SUPPORTED_SUFFIXES = listOf(
            "qrc",
            "producer",
            "ex",
            "translrc",
            "romaqrc"
        )
    }
}

data class QrcWatcherStatus(
    val watcherRunning: Boolean,
    val pendingGroups: Int,
    val incrementalRunning: Boolean,
    val incrementalSuccess: Int,
    val incrementalFailed: Int,
    val incrementalSkipped: Int
) {
    fun displayText(): String {
        return "QRC Watcher:\n" +
            (if (watcherRunning) "running" else "stopped") +
            "\npending groups: $pendingGroups" +
            "\nincremental: ${if (incrementalRunning) "running" else "idle"}" +
            "\nsuccess: $incrementalSuccess" +
            "\nfailed: $incrementalFailed" +
            "\nskipped: $incrementalSkipped"
    }
}
