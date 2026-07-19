package com.example.playeragent.media

import android.content.Context
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrcIncrementalPrebuildManager(
    context: Context,
    private val logger: (String) -> Unit,
    private val statusListener: (QrcWatcherStatus) -> Unit,
    private val currentTrackProvider: () -> CurrentTrackSnapshot? = { null },
    private val onIncrementalLyricsReady: (IncrementalLyricsReady) -> Unit = {},
    private val onBatchProcessed: (Set<String>) -> Unit = {}
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
            // A QRC group that QQ Music has just written for the song currently
            // playing is part of the foreground lyric path, not background cache
            // maintenance.  Do not let a pending full-lyrics/album-art request
            // postpone it: doing so leaves QQ Music showing lyrics while the
            // receiver waits for the cache warm-up to finish.
            val foregroundGroups = cleanGroupIds.filter(::isForegroundCurrentTrackGroup).toSet()
            val token = QrcMaintenanceCoordinator.tryStart(
                MaintenanceTaskType.QRC_INCREMENTAL_PREBUILD,
                "groups=${cleanGroupIds.size}",
                logger
            )
            if (token == null && foregroundGroups.isEmpty()) {
                logger("[QrcIncremental] skipped reason=maintenance busy groups=${cleanGroupIds.size}")
                cleanGroupIds.forEach { incrementSkipped() }
                return@execute
            }
            setRunning(true)
            val handledGroups = linkedSetOf<String>()
            try {
                cleanGroupIds.forEachIndexed { index, groupId ->
                    if (stopped.get() || token?.cancelled == true) {
                        return@forEachIndexed
                    }
                    val isForegroundGroup = groupId in foregroundGroups
                    if (token == null && !isForegroundGroup) {
                        logger("[QrcIncremental] skipped background groupId=$groupId reason=maintenance busy")
                        incrementSkipped()
                        return@forEachIndexed
                    }
                    if (!isForegroundGroup && !MaintenanceGuard.yieldIfRealtimeWindow(
                            MaintenanceTaskType.QRC_INCREMENTAL_PREBUILD,
                            token!!,
                            logger
                        )
                    ) {
                        return@forEachIndexed
                    }
                    if (isForegroundGroup) {
                        logger("[QrcIncremental] foreground bypass maintenance groupId=$groupId")
                    }
                    processGroup(groupId, cleanGroupIds.size)
                    handledGroups += groupId
                    if ((index + 1) % THROTTLE_INTERVAL == 0) {
                        Thread.sleep(THROTTLE_SLEEP_MS)
                    }
                }
            } catch (exception: Exception) {
                logger("[QrcIncremental] failed reason=${exception.message}")
                incrementFailed()
                token?.let { QrcMaintenanceCoordinator.fail(it, exception, logger) }
                return@execute
            } finally {
                token?.let { QrcMaintenanceCoordinator.finish(it, logger) }
                setRunning(false)
                if (handledGroups.isNotEmpty()) {
                    onBatchProcessed(handledGroups)
                }
            }
        }
    }

    fun stop() {
        stopped.set(true)
        executor.shutdownNow()
        setRunning(false)
        QrcMaintenanceCoordinator.finishCurrentIf(
            MaintenanceTaskType.QRC_INCREMENTAL_PREBUILD,
            "incremental manager stopped",
            logger
        )
        logger("[QrcIncremental] stopped")
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

    private fun processGroup(groupId: String, batchSize: Int) {
        logger("[QrcIncremental] start groupId=$groupId")
        val group = findGroup(groupId)
        if (group.qrcFile == null) {
            logger("[QrcIncremental] skip no qrc groupId=$groupId")
            persistentIndexManager.markDirty(groupId)
            incrementSkipped()
            return
        }
        val currentTrackAtStart = currentTrackProvider()
        val shouldTryCurrentTrack = currentTrackAtStart != null &&
            !currentTrackAtStart.hasLyrics &&
            isRecentForCurrentTrack(group, currentTrackAtStart)
        if (!shouldTryCurrentTrack) {
            val validation = cacheManager.validateGroupCache(group, requireComplete = true)
            if (validation.valid) {
                logger("[QrcIncremental] skip cached groupId=$groupId")
                logger("[QrcIndex] not marked dirty for skip cached groupId=$groupId")
                incrementSkipped()
                return
            }
            if (validation.cached != null) {
                logger(
                    "[QrcIncremental] rebuild stale cache groupId=$groupId " +
                        "reason=${validation.reason}"
                )
            }
        }
        val parsed = parseGroupWithRetry(group, foreground = shouldTryCurrentTrack)
        if (parsed == null || parsed.lines.isEmpty()) {
            logger("[QrcIncremental] failed groupId=$groupId reason=parse empty")
            persistentIndexManager.markDirty(groupId)
            incrementFailed()
            return
        }

        // QQ Music writes the sidecar before its MediaSession metadata changes.
        // Parsing can overlap that transition, so refresh the snapshot here
        // instead of matching the new group against the previous song.
        val currentTrack = currentTrackProvider() ?: currentTrackAtStart
        if (currentTrackAtStart?.trackId != currentTrack?.trackId) {
            logger(
                "[QrcIncremental] current track refreshed after parse " +
                    "from=${currentTrackAtStart?.trackId.orEmpty()} " +
                    "to=${currentTrack?.trackId.orEmpty()}"
            )
        }

        var savedAny = false
        if (parsed.title.isNotBlank()) {
            cacheManager.save(parsed)
            negativeCacheManager.removeNegative(parsed.songKey)
            savedAny = true
        } else {
            logger("[QrcIncremental] skip parsed songKey reason=unreliable title groupId=$groupId")
        }

        val matchedCurrent = evaluateCurrentTrackMatch(
            group = group,
            parsed = parsed,
            currentTrack = currentTrack,
            batchSize = batchSize
        )
        val ready = if (matchedCurrent && currentTrack != null) {
            val currentParsed = parsed.copy(
                songKey = currentTrack.songKey,
                title = currentTrack.title,
                artist = currentTrack.artist,
                album = currentTrack.album,
                qrcLastModified = group.qrcFile?.lastModified() ?: parsed.qrcLastModified,
                qrcPath = group.qrcFile?.absolutePath.orEmpty()
            )
            cacheManager.save(currentParsed)
            negativeCacheManager.removeNegative(
                currentTrack.songKey,
                "incremental lyrics ready"
            )
            logger(
                "[QrcCache] incremental current-track saved " +
                    "songKey=${currentTrack.songKey} groupId=$groupId lines=${parsed.lines.size}"
            )
            savedAny = true
            IncrementalLyricsReady(
                groupId = groupId,
                parsed = currentParsed,
                currentTrack = currentTrack,
                matchedCurrentTrack = true
            )
        } else {
            IncrementalLyricsReady(
                groupId = groupId,
                parsed = parsed,
                currentTrack = currentTrack,
                matchedCurrentTrack = false
            )
        }
        persistentIndexManager.markDirty(groupId)
        if (savedAny) {
            onIncrementalLyricsReady(ready)
        }
        logger(
            "[QrcIncremental] success groupId=$groupId " +
                "title=${parsed.title} lines=${parsed.lines.size} " +
                "trans=${parsed.lines.count { !it.translation.isNullOrBlank() }} " +
                "roma=${parsed.lines.count { !it.romanization.isNullOrBlank() }}"
        )
        incrementSuccess()
    }

    private fun parseGroupWithRetry(
        group: QrcFileGroup,
        foreground: Boolean
    ): ParsedLyric? {
        val delays = if (foreground) CURRENT_TRACK_PARSE_RETRY_DELAYS_MS else longArrayOf(0L)
        delays.forEachIndexed { attempt, delayMs ->
            if (delayMs > 0L) {
                Thread.sleep(delayMs)
            }
            if (foreground && !isGroupStable(group)) {
                logger(
                    "[QrcIncremental] current file not stable " +
                        "groupId=${group.groupId} attempt=${attempt + 1}"
                )
                return@forEachIndexed
            }
            val parsed = try {
                QrcLyricUtils.decryptAndParseGroup(group, logger)
            } catch (exception: Exception) {
                logger(
                    "[QrcIncremental] parse attempt failed groupId=${group.groupId} " +
                        "attempt=${attempt + 1} reason=${exception.message}"
                )
                null
            }
            if (parsed != null && parsed.lines.isNotEmpty()) {
                return parsed
            }
        }
        return null
    }

    private fun isGroupStable(group: QrcFileGroup): Boolean {
        val files = listOfNotNull(
            group.qrcFile,
            group.producerFile,
            group.exFile,
            group.translrcFile,
            group.romaqrcFile
        )
        val first = files.associate { it.absolutePath to (it.length() to it.lastModified()) }
        Thread.sleep(CURRENT_TRACK_STABILITY_SAMPLE_MS)
        return files.all { file ->
            first[file.absolutePath] == (file.length() to file.lastModified())
        }
    }

    private fun isRecentForCurrentTrack(
        group: QrcFileGroup,
        currentTrack: CurrentTrackSnapshot
    ): Boolean {
        return isQrcGroupRecentForCurrentTrack(
            group = group,
            trackChangedAtMs = currentTrack.trackChangedAtMs,
            matchWindowMs = CURRENT_TRACK_MATCH_WINDOW_MS
        )
    }

    private fun isForegroundCurrentTrackGroup(groupId: String): Boolean {
        val currentTrack = currentTrackProvider() ?: return false
        if (currentTrack.hasLyrics) {
            return false
        }
        return isRecentForCurrentTrack(findGroup(groupId), currentTrack)
    }

    private fun evaluateCurrentTrackMatch(
        group: QrcFileGroup,
        parsed: ParsedLyric,
        currentTrack: CurrentTrackSnapshot?,
        batchSize: Int
    ): Boolean {
        logger(
            "[QrcIncrementalMatch] evaluate groupId=${group.groupId} " +
                "currentSongKey=${currentTrack?.songKey.orEmpty()}"
        )
        if (currentTrack == null) {
            logger("[QrcIncrementalMatch] rejected reason=no current track")
            return false
        }
        if (currentTrack.hasLyrics) {
            logger("[QrcIncrementalMatch] rejected reason=current lyrics already available")
            return false
        }
        if (!isRecentForCurrentTrack(group, currentTrack)) {
            logger("[QrcIncrementalMatch] rejected reason=not recent for current track")
            return false
        }

        val currentArtistTokens = QrcLyricUtils.splitArtists(currentTrack.artist)
        val parsedArtistTokens = QrcLyricUtils.splitArtists(parsed.artist)
        val rawNormalized = QrcLyricUtils.normalizeForMatch(parsed.rawText)
        val artistMatches = currentArtistTokens.isNotEmpty() &&
            (currentArtistTokens.any(parsedArtistTokens::contains) ||
                currentArtistTokens.any { rawNormalized.contains(it) })
        if (!artistMatches) {
            logger("[QrcIncrementalMatch] rejected reason=artist mismatch")
            return false
        }

        val currentTitle = QrcLyricUtils.normalizeForMatch(currentTrack.title)
        val parsedTitle = QrcLyricUtils.normalizeForMatch(parsed.title)
        if (currentTitle.isNotBlank() && parsedTitle == currentTitle) {
            logger("[QrcIncrementalMatch] matched reason=title_exact")
            return true
        }
        if (currentTitle.isNotBlank() && rawNormalized.contains(currentTitle)) {
            logger("[QrcIncrementalMatch] matched reason=raw_contains_current_title")
            return true
        }
        val parsedTitleUnreliable = parsed.title.isBlank() ||
            QrcLyricUtils.isInvalidMetadataTitle(parsed.title)
        if (parsedTitleUnreliable && batchSize == 1) {
            logger("[QrcIncrementalMatch] matched reason=recent_single_group_artist_match")
            return true
        }
        if (parsedTitleUnreliable && batchSize > 1) {
            logger("[QrcIncrementalMatch] ambiguous groups=$batchSize skip alias")
        } else {
            logger("[QrcIncrementalMatch] rejected reason=title mismatch")
        }
        return false
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
        private const val CURRENT_TRACK_MATCH_WINDOW_MS = 90_000L
        private const val CURRENT_TRACK_STABILITY_SAMPLE_MS = 80L
        private val CURRENT_TRACK_PARSE_RETRY_DELAYS_MS = longArrayOf(0L, 200L, 500L, 1_000L)
        private val SUPPORTED_SUFFIXES = listOf(
            "qrc",
            "producer",
            "ex",
            "translrc",
            "romaqrc"
        )
    }
}

/**
 * QQ Music may reuse an existing encrypted .qrc and only rewrite its .ex sidecar
 * when that lyric becomes active for the current song. Use the newest timestamp
 * from the whole group so that this foreground signal is not hidden by an older
 * .qrc timestamp.
 */
internal fun isQrcGroupRecentForCurrentTrack(
    group: QrcFileGroup,
    trackChangedAtMs: Long,
    matchWindowMs: Long
): Boolean {
    val newestModifiedAt = listOfNotNull(
        group.qrcFile,
        group.producerFile,
        group.exFile,
        group.translrcFile,
        group.romaqrcFile
    ).maxOfOrNull(File::lastModified)
        ?.coerceAtLeast(group.lastModified)
        ?: group.lastModified
    return newestModifiedAt >= trackChangedAtMs ||
        kotlin.math.abs(newestModifiedAt - trackChangedAtMs) <= matchWindowMs
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
        return "QRC 监听器：${if (watcherRunning) "运行中" else "已停止"}\n" +
            "待处理 group：$pendingGroups\n" +
            "增量解析：${if (incrementalRunning) "运行中" else "空闲"}\n" +
            "成功：$incrementalSuccess\n" +
            "失败：$incrementalFailed\n" +
            "跳过：$incrementalSkipped"
    }
}
