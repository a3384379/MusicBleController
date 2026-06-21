package com.example.playeragent.media

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

class QrcStaleCacheRebuildManager(
    context: Context,
    private val logger: (String) -> Unit,
    private val progressListener: (QrcStaleCacheRebuildProgress) -> Unit
) {

    private val appContext = context.applicationContext
    private val cacheManager = QrcLyricCacheManager(appContext, logger)
    private val running = AtomicBoolean(false)
    @Volatile private var stopRequested = false
    @Volatile private var progress = QrcStaleCacheRebuildProgress()

    fun start() {
        if (!running.compareAndSet(false, true)) {
            logger("[QrcStaleRebuild] already running")
            return
        }
        val token = QrcMaintenanceCoordinator.tryStart(
            MaintenanceTaskType.CACHE_REPAIR,
            "stale cache rebuild",
            logger
        )
        if (token == null) {
            running.set(false)
            return
        }
        stopRequested = false
        Thread({
            try {
                runRebuild(token)
            } catch (exception: Exception) {
                QrcMaintenanceCoordinator.fail(token, exception, logger)
                return@Thread
            } finally {
                QrcMaintenanceCoordinator.finish(token, logger)
            }
        }, "QrcStaleCacheRebuildThread").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun stop() {
        stopRequested = true
        if (running.get()) {
            logger("[QrcStaleRebuild] stopped")
        }
    }

    fun status(): QrcStaleCacheRebuildProgress {
        return progress.copy(running = running.get())
    }

    private fun runRebuild(token: MaintenanceToken) {
        val groupIds = cacheManager.groupCacheFiles()
            .mapNotNull { file ->
                GROUP_CACHE_FILE_REGEX.matchEntire(file.name)
                    ?.groupValues
                    ?.getOrNull(1)
            }
            .distinct()
            .sorted()
        val groupsById = QrcLyricUtils.scanGroups().associateBy(QrcFileGroup::groupId)
        progress = QrcStaleCacheRebuildProgress(
            running = true,
            status = "running",
            total = groupIds.size
        )
        publish()
        logger("[QrcStaleRebuild] start total=${groupIds.size}")

        try {
            groupIds.forEachIndexed { index, groupId ->
                if (stopRequested || token.cancelled) {
                    progress = progress.copy(status = "stopped")
                    publish()
                    return@forEachIndexed
                }
                processGroup(groupId, groupsById)
                if ((index + 1) % PROGRESS_LOG_INTERVAL == 0) {
                    logger(
                        "[QrcStaleRebuild] progress processed=${progress.processed} " +
                            "rebuilt=${progress.rebuilt} skipped=${progress.skipped} " +
                            "failed=${progress.failed}"
                    )
                    Thread.sleep(THROTTLE_SLEEP_MS)
                }
            }
        } finally {
            running.set(false)
            if (progress.status != "stopped") {
                progress = progress.copy(status = "finished")
                logger(
                    "[QrcStaleRebuild] finished processed=${progress.processed} " +
                        "rebuilt=${progress.rebuilt} skipped=${progress.skipped} " +
                        "failed=${progress.failed}"
                )
            }
            publish()
        }
    }

    private fun processGroup(
        groupId: String,
        groupsById: Map<String, QrcFileGroup>
    ) {
        progress = progress.copy(
            currentGroupId = groupId,
            processed = progress.processed + 1
        )
        val group = groupsById[groupId]
        if (group == null || group.qrcFile == null) {
            progress = progress.copy(failed = progress.failed + 1)
            logger("[QrcStaleRebuild] failed groupId=$groupId reason=group not found")
            publish()
            return
        }

        val validation = cacheManager.validateGroupCache(group, requireComplete = true)
        if (validation.valid) {
            progress = progress.copy(skipped = progress.skipped + 1)
            publish()
            return
        }

        logger("[QrcStaleRebuild] rebuild groupId=$groupId reason=${validation.reason}")
        val parsed = try {
            QrcLyricUtils.decryptAndParseGroup(group, logger)
        } catch (exception: Exception) {
            logger("[QrcStaleRebuild] failed groupId=$groupId reason=${exception.message}")
            null
        }
        if (parsed == null || parsed.lines.isEmpty()) {
            progress = progress.copy(failed = progress.failed + 1)
            logger("[QrcStaleRebuild] failed groupId=$groupId reason=parse empty")
            publish()
            return
        }

        val normalized = if (parsed.title.isBlank()) {
            parsed.copy(songKey = "group|${parsed.groupId}")
        } else {
            parsed
        }
        cacheManager.save(normalized)
        progress = progress.copy(rebuilt = progress.rebuilt + 1)
        logger(
            "[QrcStaleRebuild] success groupId=$groupId " +
                "lines=${normalized.lines.size} " +
                "trans=${normalized.lines.count { !it.translation.isNullOrBlank() }} " +
                "roma=${normalized.lines.count { !it.romanization.isNullOrBlank() }}"
        )
        publish()
    }

    private fun publish() {
        progressListener(progress.copy(running = running.get()))
    }

    companion object {
        private const val PROGRESS_LOG_INTERVAL = 20
        private const val THROTTLE_SLEEP_MS = 100L
        private val GROUP_CACHE_FILE_REGEX = Regex("""group_(.+)\.json""")
    }
}

data class QrcStaleCacheRebuildProgress(
    val running: Boolean = false,
    val status: String = "stopped",
    val total: Int = 0,
    val processed: Int = 0,
    val rebuilt: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val currentGroupId: String = ""
) {
    fun displayText(): String {
        return "旧歌词缓存修复：$status\n" +
            "processed: $processed / $total\n" +
            "rebuilt: $rebuilt\n" +
            "skipped: $skipped\n" +
            "failed: $failed\n" +
            "current: ${currentGroupId.ifBlank { "-" }}"
    }
}
