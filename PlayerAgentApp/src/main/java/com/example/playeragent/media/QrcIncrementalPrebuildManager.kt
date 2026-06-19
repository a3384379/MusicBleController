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
    private val onIncrementalLyricsReady: (IncrementalLyricsReady) -> Unit = {}
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
                    processGroup(groupId, cleanGroupIds.size)
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

    private fun processGroup(groupId: String, batchSize: Int) {
        logger("[QrcIncremental] start groupId=$groupId")
        val group = findGroup(groupId)
        if (group.qrcFile == null) {
            logger("[QrcIncremental] skip no qrc groupId=$groupId")
            persistentIndexManager.markDirty(groupId)
            incrementSkipped()
            return
        }
        val currentTrack = currentTrackProvider()
        val shouldTryCurrentTrack = currentTrack != null &&
            !currentTrack.hasLyrics &&
            isRecentForCurrentTrack(group, currentTrack)
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
        val parsed = try {
            QrcLyricUtils.decryptAndParseGroup(group, logger)
        } catch (exception: Exception) {
            logger("[QrcIncremental] failed groupId=$groupId reason=${exception.message}")
            null
        }
        if (parsed == null || parsed.lines.isEmpty()) {
            logger("[QrcIncremental] failed groupId=$groupId reason=parse empty")
            persistentIndexManager.markDirty(groupId)
            incrementFailed()
            return
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

    private fun isRecentForCurrentTrack(
        group: QrcFileGroup,
        currentTrack: CurrentTrackSnapshot
    ): Boolean {
        val qrcModifiedAt = group.qrcFile?.lastModified() ?: group.lastModified
        return qrcModifiedAt >= currentTrack.trackChangedAtMs ||
            kotlin.math.abs(qrcModifiedAt - currentTrack.trackChangedAtMs) <= CURRENT_TRACK_MATCH_WINDOW_MS
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
        return "QRC 监听器：${if (watcherRunning) "运行中" else "已停止"}\n" +
            "待处理 group：$pendingGroups\n" +
            "增量解析：${if (incrementalRunning) "运行中" else "空闲"}\n" +
            "成功：$incrementalSuccess\n" +
            "失败：$incrementalFailed\n" +
            "跳过：$incrementalSkipped"
    }
}
