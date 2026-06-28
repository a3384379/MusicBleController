package com.example.playeragent.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrcLyricV2RebuildManager(
    context: Context,
    private val logger: (String) -> Unit,
    private val progressListener: (QrcV2RebuildProgress) -> Unit
) {

    private val appContext = context.applicationContext
    private val cacheManager = QrcLyricCacheManager(appContext, logger)
    private val indexManager = QrcPersistentIndexManager(appContext, logger)
    private val negativeCacheManager = QrcNegativeCacheManager(appContext, logger)
    private val aliasCacheManager = QrcAliasCacheManager(appContext, logger)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QrcLyricV2RebuildThread").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private val running = AtomicBoolean(false)
    @Volatile private var stopRequested = false
    @Volatile private var pauseRequested = false
    @Volatile private var progress = loadState()

    fun start(clearBuilding: Boolean = false) {
        if (!running.compareAndSet(false, true)) {
            logger("[QrcV2Rebuild] already running")
            return
        }
        val token = QrcMaintenanceCoordinator.tryStart(
            MaintenanceTaskType.QRC_V2_REBUILD,
            if (clearBuilding) "clear rebuild" else "build",
            logger
        )
        if (token == null) {
            running.set(false)
            publish(progress.copy(running = false))
            return
        }
        stopRequested = false
        pauseRequested = false
        executor.execute {
            try {
                if (clearBuilding) {
                    clearBuildingDirectory()
                }
                if (!token.cancelled) {
                    runRebuild(token)
                }
            } catch (exception: Exception) {
                QrcMaintenanceCoordinator.fail(token, exception, logger)
                return@execute
            } finally {
                QrcMaintenanceCoordinator.finish(token, logger)
                running.set(false)
                publish(progress.copy(running = false))
            }
        }
    }

    fun pause() {
        if (running.get()) {
            pauseRequested = true
            progress = progress.copy(status = QrcV2RebuildStatus.PAUSED)
            saveState(progress)
            publish(progress)
            logger("[QrcV2Rebuild] paused")
        }
    }

    fun resume() {
        if (running.get()) {
            pauseRequested = false
            progress = progress.copy(status = QrcV2RebuildStatus.RUNNING)
            saveState(progress)
            publish(progress)
            logger("[QrcV2Rebuild] resumed")
        } else {
            start(clearBuilding = false)
        }
    }

    fun stop() {
        stopRequested = true
        pauseRequested = false
        if (running.get()) {
            logger("[QrcV2Rebuild] stopped")
        }
    }

    fun clearBuilding() {
        if (running.get()) {
            logger("[QrcV2Rebuild] clear skipped reason=running")
            return
        }
        clearBuildingDirectory()
        progress = QrcV2RebuildProgress()
        publish(progress)
    }

    fun status(): QrcV2RebuildProgress {
        return progress.copy(running = running.get())
    }

    private fun runRebuild(token: MaintenanceToken) {
        val startedAt = System.currentTimeMillis()
        val buildingDir = cacheManager.buildingCacheRoot().apply { mkdirs() }
        val entries = indexManager.getIndex(forceRefresh = true)
            .filter { it.qrcFile != null }
            .sortedBy { it.groupId }
        val completed = progress.completedGroupIds.toMutableSet()
        progress = progress.copy(
            status = QrcV2RebuildStatus.RUNNING,
            running = true,
            total = entries.size,
            startedAt = progress.startedAt.takeIf { it > 0L } ?: startedAt,
            updatedAt = startedAt,
            completedGroupIds = completed
        )
        saveState(progress)
        publish(progress)
        logger("[QrcV2Rebuild] start total=${entries.size}")

        entries.forEach { entry ->
            if (stopRequested) {
                progress = progress.copy(status = QrcV2RebuildStatus.STOPPED)
                saveState(progress)
                publish(progress)
                return
            }
            if (!MaintenanceGuard.yieldIfRealtimeWindow(
                    MaintenanceTaskType.QRC_V2_REBUILD,
                    token,
                    logger
                )
            ) {
                progress = progress.copy(status = QrcV2RebuildStatus.STOPPED)
                saveState(progress)
                publish(progress)
                return
            }
            while (pauseRequested && !stopRequested) {
                Thread.sleep(PAUSE_POLL_MS)
            }
            if (entry.groupId in completed) {
                return@forEach
            }

            val updated = processEntry(entry, buildingDir)
            completed += entry.groupId
            progress = updated.copy(
                processed = updated.processed + 1,
                currentGroupId = entry.groupId,
                updatedAt = System.currentTimeMillis(),
                completedGroupIds = completed
            )
            if (progress.processed % STATE_SAVE_INTERVAL == 0) {
                saveState(progress)
            }
            if (progress.processed % PROGRESS_LOG_INTERVAL == 0) {
                logger(
                    "[QrcV2Rebuild] progress processed=${progress.processed} " +
                        "words=${progress.successWithWords} " +
                        "lineOnly=${progress.successLineOnly} failed=${progress.failed}"
                )
                publish(progress)
                Thread.sleep(THROTTLE_SLEEP_MS)
            }
        }

        if (stopRequested) {
            progress = progress.copy(status = QrcV2RebuildStatus.STOPPED)
            saveState(progress)
            publish(progress)
            return
        }

        progress = progress.copy(status = QrcV2RebuildStatus.VALIDATING)
        saveState(progress)
        publish(progress)
        logger("[QrcV2Rebuild] validation start")
        val validation = validateBuildingDirectory(buildingDir)
        if (validation.cancelled) {
            progress = progress.copy(status = QrcV2RebuildStatus.STOPPED)
            saveState(progress)
            publish(progress)
            logger("[QrcV2Rebuild] validation cancelled")
            return
        }
        if (!validation.ok) {
            progress = progress.copy(status = QrcV2RebuildStatus.FAILED)
            saveState(progress)
            publish(progress)
            logger("[QrcV2Rebuild] validation failed reason=${validation.reason}")
            return
        }
        logger("[QrcV2Rebuild] validation success files=${validation.files}")

        if (swapDirectories()) {
            cacheManager.clearMemory()
            negativeCacheManager.clear()
            aliasCacheManager.removeInvalidTargets { songKey ->
                cacheManager.readBySongKey(songKey) != null
            }
            progress = progress.copy(status = QrcV2RebuildStatus.COMPLETED)
            saveState(progress)
            publish(progress)
            logger("[QrcV2Rebuild] swap success")
        } else {
            progress = progress.copy(status = QrcV2RebuildStatus.FAILED)
            saveState(progress)
            publish(progress)
            logger("[QrcV2Rebuild] failed reason=swap failed")
        }
    }

    private fun processEntry(
        entry: QrcGroupIndexEntry,
        buildingDir: File
    ): QrcV2RebuildProgress {
        val group = QrcFileGroup(
            groupId = entry.groupId,
            qrcFile = entry.qrcFile,
            producerFile = entry.producerFile,
            exFile = entry.exFile,
            translrcFile = entry.translrcFile,
            romaqrcFile = entry.romaqrcFile,
            lastModified = entry.lastModified
        )
        if (group.qrcFile == null) {
            return progress.copy(skippedNoQrc = progress.skippedNoQrc + 1)
        }
        val parsed = try {
            QrcLyricUtils.decryptAndParseGroup(group)
        } catch (exception: Exception) {
            logger("[QrcV2Rebuild] failed groupId=${entry.groupId} reason=${exception.message}")
            null
        }
        if (parsed == null || parsed.lines.isEmpty()) {
            return progress.copy(failed = progress.failed + 1)
        }
        val normalized = if (parsed.title.isBlank()) {
            parsed.copy(songKey = "group|${parsed.groupId}")
        } else {
            parsed
        }
        val wordStatus = QrcWordTimingStatus.fromLines(normalized.lines)
        val v2 = normalized.copy(
            schemaVersion = QRC_CACHE_SCHEMA_V2,
            qrcPath = group.qrcFile?.absolutePath.orEmpty(),
            wordTimingStatus = wordStatus
        )
        val before = cacheManager.readBySongKeyFromDirectory(v2.songKey, buildingDir)
        cacheManager.saveToDirectory(v2, buildingDir, updateMemory = false)
        val after = cacheManager.readBySongKeyFromDirectory(v2.songKey, buildingDir)
        val duplicate = before != null && after?.qrcLastModified != v2.qrcLastModified
        return when {
            duplicate -> progress.copy(duplicate = progress.duplicate + 1)
            wordStatus == QrcWordTimingStatus.AVAILABLE ->
                progress.copy(successWithWords = progress.successWithWords + 1)
            else -> progress.copy(successLineOnly = progress.successLineOnly + 1)
        }
    }

    private fun validateBuildingDirectory(directory: File): ValidationResult {
        val files = directory.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true) &&
                file.name != STATE_FILE_NAME
        }.orEmpty().toList()
        if (files.isEmpty()) {
            return ValidationResult(false, "no cache files", 0)
        }
        val sample = files.shuffled().take(VALIDATION_SAMPLE_SIZE)
        files.forEach { file ->
            if (stopRequested) {
                return ValidationResult(false, "cancelled", files.size, cancelled = true)
            }
            val parsed = try {
                JSONObject(file.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                return ValidationResult(false, "json parse failed ${file.name}", files.size)
            }
            if (parsed.optInt("schemaVersion") != QRC_CACHE_SCHEMA_V2) {
                return ValidationResult(false, "schema mismatch ${file.name}", files.size)
            }
            if (parsed.optString("songKey").isBlank()) {
                return ValidationResult(false, "empty songKey ${file.name}", files.size)
            }
            val lines = parsed.optJSONArray("lines") ?: JSONArray()
            if (lines.length() <= 0) {
                return ValidationResult(false, "empty lines ${file.name}", files.size)
            }
            if (parsed.optString("wordTimingStatus") == QrcWordTimingStatus.AVAILABLE.name) {
                val hasWords = (0 until lines.length()).any { index ->
                    val words = lines.optJSONObject(index)?.optJSONArray("words")
                    words != null && words.length() > 0
                }
                if (!hasWords) {
                    return ValidationResult(false, "available without words ${file.name}", files.size)
                }
            }
        }
        sample.forEach { file: File ->
            if (stopRequested) {
                return ValidationResult(false, "cancelled", files.size, cancelled = true)
            }
            if (!file.canRead() || file.length() <= 0L) {
                return ValidationResult(false, "sample unreadable ${file.name}", files.size)
            }
        }
        return ValidationResult(true, "", files.size)
    }

    private fun swapDirectories(): Boolean {
        val active = cacheManager.cacheRoot()
        val building = cacheManager.buildingCacheRoot()
        val backup = cacheManager.backupCacheRoot()
        return try {
            if (backup.exists()) {
                backup.deleteRecursively()
            }
            if (active.exists() && !active.renameTo(backup)) {
                return false
            }
            if (!building.renameTo(active)) {
                if (backup.exists()) {
                    backup.renameTo(active)
                }
                return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun clearBuildingDirectory() {
        cacheManager.buildingCacheRoot().deleteRecursively()
        cacheManager.buildingCacheRoot().mkdirs()
    }

    private fun stateFile(): File {
        return File(cacheManager.buildingCacheRoot(), STATE_FILE_NAME)
    }

    private fun loadState(): QrcV2RebuildProgress {
        val file = stateFile()
        if (!file.exists()) {
            return QrcV2RebuildProgress()
        }
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            QrcV2RebuildProgress(
                status = QrcV2RebuildStatus.fromValue(root.optString("status")),
                total = root.optInt("total"),
                processed = root.optInt("processed"),
                successWithWords = root.optInt("successWithWords"),
                successLineOnly = root.optInt("successLineOnly"),
                failed = root.optInt("failed"),
                skippedNoQrc = root.optInt("skippedNoQrc"),
                duplicate = root.optInt("duplicate"),
                currentGroupId = root.optString("currentGroupId"),
                startedAt = root.optLong("startedAt"),
                updatedAt = root.optLong("updatedAt"),
                completedGroupIds = (root.optJSONArray("completedGroupIds") ?: JSONArray())
                    .let { array ->
                        (0 until array.length()).mapNotNull { array.optString(it) }.toSet()
                    }
            )
        } catch (_: Exception) {
            QrcV2RebuildProgress()
        }
    }

    private fun saveState(value: QrcV2RebuildProgress) {
        val root = JSONObject()
            .put("status", value.status.name)
            .put("total", value.total)
            .put("processed", value.processed)
            .put("successWithWords", value.successWithWords)
            .put("successLineOnly", value.successLineOnly)
            .put("failed", value.failed)
            .put("skippedNoQrc", value.skippedNoQrc)
            .put("duplicate", value.duplicate)
            .put("currentGroupId", value.currentGroupId)
            .put("startedAt", value.startedAt)
            .put("updatedAt", value.updatedAt)
            .put("completedGroupIds", JSONArray().also { array ->
                value.completedGroupIds.forEach(array::put)
            })
        val file = stateFile()
        file.parentFile?.mkdirs()
        file.writeText(root.toString(), Charsets.UTF_8)
    }

    private fun publish(value: QrcV2RebuildProgress) {
        progressListener(value.copy(running = running.get()))
    }

    companion object {
        private const val STATE_FILE_NAME = "rebuild_state.json"
        private const val STATE_SAVE_INTERVAL = 10
        private const val PROGRESS_LOG_INTERVAL = 20
        private const val THROTTLE_SLEEP_MS = 80L
        private const val PAUSE_POLL_MS = 200L
        private const val VALIDATION_SAMPLE_SIZE = 20
    }

    private data class ValidationResult(
        val ok: Boolean,
        val reason: String,
        val files: Int,
        val cancelled: Boolean = false
    )
}

enum class QrcV2RebuildStatus {
    NOT_STARTED,
    RUNNING,
    PAUSED,
    STOPPED,
    VALIDATING,
    COMPLETED,
    FAILED;

    companion object {
        fun fromValue(value: String): QrcV2RebuildStatus {
            return values().firstOrNull { it.name == value } ?: NOT_STARTED
        }
    }
}

data class QrcV2RebuildProgress(
    val status: QrcV2RebuildStatus = QrcV2RebuildStatus.NOT_STARTED,
    val running: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val successWithWords: Int = 0,
    val successLineOnly: Int = 0,
    val failed: Int = 0,
    val skippedNoQrc: Int = 0,
    val duplicate: Int = 0,
    val currentGroupId: String = "",
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedGroupIds: Set<String> = emptySet()
) {
    fun displayText(): String {
        val percent = if (total > 0) processed * 100 / total else 0
        return "构建状态：${status.displayName()}\n" +
            "进度：$processed / $total ($percent%)\n" +
            "含逐字时间：$successWithWords\n" +
            "仅行级歌词：$successLineOnly\n" +
            "失败：$failed\n" +
            "跳过无 QRC：$skippedNoQrc\n" +
            "重复：$duplicate\n" +
            "当前 groupId：${currentGroupId.ifBlank { "-" }}"
    }
}

fun QrcV2RebuildStatus.displayName(): String {
    return when (this) {
        QrcV2RebuildStatus.NOT_STARTED -> "未开始"
        QrcV2RebuildStatus.RUNNING -> "构建中"
        QrcV2RebuildStatus.PAUSED -> "已暂停"
        QrcV2RebuildStatus.STOPPED -> "已停止"
        QrcV2RebuildStatus.VALIDATING -> "校验中"
        QrcV2RebuildStatus.COMPLETED -> "已完成"
        QrcV2RebuildStatus.FAILED -> "失败"
    }
}
