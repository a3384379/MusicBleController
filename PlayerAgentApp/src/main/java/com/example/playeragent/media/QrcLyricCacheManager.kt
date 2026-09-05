package com.example.playeragent.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class QrcLyricCacheManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private val aliasCacheManager = QrcAliasCacheManager(
        context = appContext,
        logger = logger
    )
    private val parsedIndexStore = sharedParsedIndexStores.computeIfAbsent(
        QrcLyricUtils.cacheDirectory(appContext).absolutePath
    ) { path ->
        QrcParsedCacheIndexStore(
            cacheDirectory = File(path),
            logger = logger
        )
    }
    private val memoryCache =
        object : LinkedHashMap<String, ParsedLyric>(MAX_MEMORY_CACHE, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ParsedLyric>?
            ): Boolean {
                return size > MAX_MEMORY_CACHE
            }
        }
    init {
        installPersistedIndexIfAvailable()
        if (!parsedIndexStore.loaded) {
            preloadFuzzyIndexAsync(force = true)
        }
    }

    @Synchronized
    fun get(
        title: String,
        artist: String,
        album: String,
        traceId: String = "",
        shouldCancel: () -> Boolean = { false }
    ): ParsedLyric? {
        val startedAt = System.currentTimeMillis()
        sharedQueryCount += 1
        val songKey = QrcLyricUtils.buildSongKey(title, artist, album)
        if (shouldCancel()) {
            trace(traceId, "qrcCache", "result=cancelled stage=start")
            return null
        }
        memoryCache[songKey]?.let {
            sharedStats.l1Hit += 1
            sharedStats.lastSource = "L1"
            maybeLogStats()
            logger("[QrcCache] L1 hit songKey=$songKey")
            trace(traceId, "qrcL1", "result=hit songKey=$songKey costMs=${System.currentTimeMillis() - startedAt}")
            return it
        }

        val aliasStartedAt = System.currentTimeMillis()
        if (shouldCancel()) {
            trace(traceId, "qrcCache", "result=cancelled stage=alias")
            return null
        }
        val aliasTarget = aliasCacheManager.getAlias(songKey)
        if (!aliasTarget.isNullOrBlank()) {
            trace(
                traceId,
                "alias",
                "result=hit source=$songKey target=$aliasTarget " +
                    "costMs=${System.currentTimeMillis() - aliasStartedAt}"
            )
            memoryCache[aliasTarget]?.let { aliased ->
                val copy = aliased.copy(songKey = songKey)
                memoryCache[songKey] = copy
                sharedStats.aliasHit += 1
                sharedStats.lastSource = "ALIAS"
                maybeLogStats()
                logger("[QrcCache] L1 hit songKey=$aliasTarget")
                trace(
                    traceId,
                    "qrcL1",
                    "result=hit reason=alias target=$aliasTarget " +
                        "costMs=${System.currentTimeMillis() - startedAt}"
                )
                return copy
            }
            val aliasedDisk = readBySongKey(aliasTarget)
            if (aliasedDisk != null) {
                val copy = aliasedDisk.copy(songKey = songKey)
                memoryCache[songKey] = copy
                sharedStats.aliasHit += 1
                sharedStats.lastSource = "ALIAS"
                maybeLogStats()
                logger("[QrcCache] L2 hit songKey=$aliasTarget")
                trace(
                    traceId,
                    "qrcL2",
                    "result=hit reason=alias target=$aliasTarget " +
                        "costMs=${System.currentTimeMillis() - startedAt}"
                )
                return copy
            }
            aliasCacheManager.removeAlias(songKey)
            trace(traceId, "alias", "result=miss reason=invalid target=$aliasTarget")
        } else {
            trace(
                traceId,
                "alias",
                "result=miss costMs=${System.currentTimeMillis() - aliasStartedAt}"
            )
        }

        val diskStartedAt = System.currentTimeMillis()
        if (shouldCancel()) {
            trace(traceId, "qrcCache", "result=cancelled stage=L2")
            return null
        }
        val disk = readBySongKey(songKey)
        if (disk != null) {
            memoryCache[songKey] = disk
            sharedStats.l2Hit += 1
            sharedStats.lastSource = "L2"
            maybeLogStats()
            logger("[QrcCache] L2 hit songKey=$songKey")
            trace(
                traceId,
                "qrcL2",
                "result=hit songKey=$songKey costMs=${System.currentTimeMillis() - diskStartedAt}"
            )
            return disk
        }
        trace(
            traceId,
            "qrcL2",
            "result=miss songKey=$songKey costMs=${System.currentTimeMillis() - diskStartedAt}"
        )

        val fuzzyStartedAt = System.currentTimeMillis()
        val fuzzy = findFuzzy(title, artist, album, songKey, traceId, shouldCancel)
        if (fuzzy != null) {
            val alias = fuzzy.parsed.copy(
                songKey = songKey,
                title = title,
                artist = artist,
                album = album
            )
            memoryCache[songKey] = alias
            aliasCacheManager.saveAlias(songKey, fuzzy.parsed.songKey)
            sharedStats.l2FuzzyHit += 1
            sharedStats.aliasSaved += 1
            sharedStats.lastSource = "FUZZY"
            maybeLogStats()
            logger("[QrcCache] L2 fuzzy hit currentSongKey=$songKey")
            logger("[QrcCache] matched cachedSongKey=${fuzzy.parsed.songKey}")
            logger("[QrcCache] score=${fuzzy.score}")
            logger(
                "[QrcCache] title=${fuzzy.parsed.title} " +
                    "artist=${fuzzy.parsed.artist} album=${fuzzy.parsed.album}"
            )
            logger("[QrcCache] lines=${fuzzy.parsed.lines.size}")
            trace(
                traceId,
                "fuzzy",
                "result=hit score=${fuzzy.score} matched=${fuzzy.parsed.songKey} " +
                    "lines=${fuzzy.parsed.lines.size} costMs=${System.currentTimeMillis() - fuzzyStartedAt}"
            )
            return alias
        }

        logger("[QrcCache] miss songKey=$songKey")
        sharedStats.lastSource = "NONE"
        maybeLogStats()
        trace(
            traceId,
            "fuzzy",
            "result=miss songKey=$songKey costMs=${System.currentTimeMillis() - fuzzyStartedAt}"
        )
        return null
    }

    @Synchronized
    fun getExactOrAlias(
        title: String,
        artist: String,
        album: String,
        traceId: String = "",
        shouldCancel: () -> Boolean = { false }
    ): ParsedLyric? {
        val startedAt = System.currentTimeMillis()
        val songKey = QrcLyricUtils.buildSongKey(title, artist, album)
        if (shouldCancel()) {
            trace(traceId, "qrcCache", "result=cancelled stage=exact_start")
            return null
        }
        memoryCache[songKey]?.let {
            sharedStats.l1Hit += 1
            sharedStats.lastSource = "L1"
            maybeLogStats()
            logger("[QrcCache] L1 hit songKey=$songKey")
            trace(traceId, "qrcL1", "result=hit songKey=$songKey costMs=${System.currentTimeMillis() - startedAt}")
            return it
        }

        val aliasTarget = aliasCacheManager.getAlias(songKey)
        if (!aliasTarget.isNullOrBlank()) {
            trace(traceId, "alias", "result=hit source=$songKey target=$aliasTarget")
            memoryCache[aliasTarget]?.let { aliased ->
                val copy = aliased.copy(songKey = songKey)
                memoryCache[songKey] = copy
                sharedStats.aliasHit += 1
                sharedStats.lastSource = "ALIAS"
                maybeLogStats()
                logger("[QrcCache] L1 hit songKey=$aliasTarget")
                trace(traceId, "qrcL1", "result=hit reason=alias target=$aliasTarget")
                return copy
            }
            if (shouldCancel()) {
                trace(traceId, "qrcCache", "result=cancelled stage=alias_L2")
                return null
            }
            val aliasedDisk = readBySongKey(aliasTarget)
            if (aliasedDisk != null) {
                val copy = aliasedDisk.copy(songKey = songKey)
                memoryCache[songKey] = copy
                sharedStats.aliasHit += 1
                sharedStats.lastSource = "ALIAS"
                maybeLogStats()
                logger("[QrcCache] L2 hit songKey=$aliasTarget")
                trace(traceId, "qrcL2", "result=hit reason=alias target=$aliasTarget")
                return copy
            }
            aliasCacheManager.removeAlias(songKey)
            trace(traceId, "alias", "result=miss reason=invalid target=$aliasTarget")
        } else {
            trace(traceId, "alias", "result=miss")
        }

        if (shouldCancel()) {
            trace(traceId, "qrcCache", "result=cancelled stage=exact_L2")
            return null
        }
        val disk = readBySongKey(songKey)
        if (disk != null) {
            memoryCache[songKey] = disk
            sharedStats.l2Hit += 1
            sharedStats.lastSource = "L2"
            maybeLogStats()
            logger("[QrcCache] L2 hit songKey=$songKey")
            trace(traceId, "qrcL2", "result=hit songKey=$songKey costMs=${System.currentTimeMillis() - startedAt}")
            return disk
        }
        trace(traceId, "qrcL2", "result=miss songKey=$songKey costMs=${System.currentTimeMillis() - startedAt}")
        return null
    }

    @Synchronized
    fun readBySongKey(songKey: String): ParsedLyric? {
        val canonicalSongKey = canonicalSongKey(songKey)
        val file = songCacheFile(canonicalSongKey)
        if (!file.exists()) {
            return null
        }
        return readCacheFile(file, expectedSongKey = canonicalSongKey)
    }

    @Synchronized
    fun readByGroupId(groupId: String): ParsedLyric? {
        val file = groupCacheFile(groupId)
        if (!file.exists()) {
            return null
        }
        return readCacheFile(file, expectedSongKey = null)
    }

    fun save(parsed: ParsedLyric) {
        if (parsed.lines.isEmpty()) {
            return
        }
        val canonicalParsed = parsed.withCanonicalSongKey()
        synchronized(this) {
            memoryCache[canonicalParsed.songKey] = canonicalParsed
        }
        pendingWrites[canonicalParsed.songKey] = canonicalParsed
        cacheWriteExecutor.execute {
            val latest = pendingWrites.remove(canonicalParsed.songKey) ?: return@execute
            synchronized(cacheWriteLock) {
                runCatching {
                    saveToDirectory(latest, cacheRoot(), updateMemory = false)
                }.onFailure { exception ->
                    logger(
                        "[QrcCache] async save failed songKey=${latest.songKey} " +
                            "error=${exception.message}"
                    )
                }
            }
        }
    }

    @Synchronized
    fun saveToDirectory(
        parsed: ParsedLyric,
        directory: File,
        updateMemory: Boolean = false
    ) {
        if (parsed.lines.isEmpty()) {
            return
        }
        val canonicalParsed = parsed.withCanonicalSongKey()
        directory.mkdirs()
        val existing = readBySongKeyFromDirectory(canonicalParsed.songKey, directory)
        if (existing != null && shouldKeepExisting(existing, canonicalParsed)) {
            if (runCatching { directory.canonicalPath == cacheRoot().canonicalPath }
                    .getOrDefault(false)
            ) {
                registerValidatedCache(existing)
            }
            logger("[QrcPrebuild] duplicate songKey=${canonicalParsed.songKey} keep=existing")
            return
        }

        val objectValue = toJson(canonicalParsed)
        val text = objectValue.toString()
        val songFile = songCacheFile(canonicalParsed.songKey, directory)
        val groupFile = groupCacheFile(canonicalParsed.groupId, directory)
        writeAtomic(songFile, text)
        writeAtomic(groupFile, text)
        if (runCatching { directory.canonicalPath == cacheRoot().canonicalPath }
                .getOrDefault(false)
        ) {
            upsertParsedIndex(canonicalParsed, songFile)
        }
        if (updateMemory) {
            memoryCache[canonicalParsed.songKey] = canonicalParsed
        }
        logger("[QrcCache] saved songKey=${canonicalParsed.songKey} lines=${canonicalParsed.lines.size}")
    }

    /**
     * Makes an already-validated group cache immediately queryable without
     * decrypting or rewriting it. This is important during an app upgrade: the
     * parsed cache can be valid while the versioned index is still rebuilding
     * behind the real-time playback guard.
     */
    @Synchronized
    fun registerValidatedCache(parsed: ParsedLyric): ParsedLyric? {
        if (parsed.lines.isEmpty()) return null
        val safeTitle = parsed.title.ifBlank {
            QrcCurrentTrackMatchPolicy.extractTitleCandidate(
                parsed.lines.map(QrcLyricLine::text)
            )
        }
        if (safeTitle.isBlank()) {
            logger("[QrcCacheIndex] validated cache ignored reason=title missing groupId=${parsed.groupId}")
            return null
        }
        val indexed = parsed.copy(
            songKey = QrcLyricUtils.buildSongKey(safeTitle, parsed.artist, parsed.album),
            title = safeTitle
        )
        val songFile = songCacheFile(indexed.songKey)
        val groupFile = groupCacheFile(indexed.groupId)
        val sourceFile = when {
            songFile.isFile -> songFile
            groupFile.isFile -> groupFile
            else -> return null
        }
        memoryCache[indexed.songKey] = indexed
        upsertParsedIndex(indexed, sourceFile)
        logger(
            "[QrcCacheIndex] validated cache registered groupId=${indexed.groupId} " +
                "title=${indexed.title} lines=${indexed.lines.size}"
        )
        return indexed
    }

    @Synchronized
    fun clearMemory() {
        memoryCache.clear()
    }

    @Synchronized
    fun saveAlias(sourceSongKey: String, targetSongKey: String) {
        aliasCacheManager.saveAlias(
            canonicalSongKey(sourceSongKey),
            canonicalSongKey(targetSongKey)
        )
        sharedStats.aliasSaved += 1
        maybeLogStats()
    }

    @Synchronized
    fun getStats(): LyricCacheStats {
        return sharedStats.toImmutable()
    }

    @Synchronized
    fun resetStats() {
        sharedStats = MutableLyricCacheStats()
        sharedQueryCount = 0L
    }

    @Synchronized
    fun recordNegativeHit() {
        sharedStats.negativeHit += 1
        sharedStats.lastSource = "NEGATIVE"
        maybeLogStats()
    }

    @Synchronized
    fun recordNegativeSaved() {
        sharedStats.negativeSaved += 1
        maybeLogStats()
    }

    @Synchronized
    fun recordQrcDecrypt(success: Boolean) {
        sharedStats.qrcDecryptCount += 1
        if (success) {
            sharedStats.qrcDecryptSuccess += 1
            sharedStats.lastSource = "QRC"
        } else {
            sharedStats.qrcDecryptFailed += 1
        }
        maybeLogStats()
    }

    fun isGroupCacheValid(group: QrcFileGroup): Boolean {
        return validateGroupCache(group, requireComplete = true).valid
    }

    @Synchronized
    fun validateGroupCache(
        group: QrcFileGroup,
        requireComplete: Boolean = true
    ): GroupCacheValidation {
        val cached = readByGroupId(group.groupId)
            ?: return GroupCacheValidation(valid = false, reason = "cache missing")
        val reason = groupInvalidReason(
            group = group,
            cached = cached,
            requireComplete = requireComplete
        )
        return if (reason == null) {
            logger("[QrcCache] group valid groupId=${group.groupId}")
            GroupCacheValidation(valid = true, cached = cached)
        } else {
            logger("[QrcCache] group invalid groupId=${group.groupId} reason=$reason")
            GroupCacheValidation(valid = false, reason = reason, cached = cached)
        }
    }

    fun groupCacheFiles(): List<File> {
        return cacheRoot().listFiles { file ->
            file.isFile &&
                file.name.startsWith("group_") &&
                file.extension.equals("json", ignoreCase = true)
        }.orEmpty().sortedBy(File::getName)
    }

    fun warmupFuzzyIndex(force: Boolean = false): QrcFuzzyIndexStatus {
        val now = System.currentTimeMillis()
        synchronized(sharedIndexLock) {
            if (!force &&
                sharedIndexEntries.isNotEmpty() &&
                now - sharedIndexBuiltAt < INDEX_TTL_MS
            ) {
                return QrcFuzzyIndexStatus(
                    ready = true,
                    warming = sharedIndexWarming.get(),
                    entries = sharedIndexEntries.size,
                    files = sharedIndexFileCount,
                    builtAt = sharedIndexBuiltAt
                )
            }
        }

        val files = cacheJsonFiles()
        val startedAt = System.currentTimeMillis()
        logger("[QrcCacheIndex] warmup start files=${files.size}")
        val entries = buildFuzzyIndex(files)
        synchronized(sharedIndexLock) {
            sharedIndexEntries = entries
            sharedTitleEntries = buildTitleIndex(entries)
            sharedTitleArtistEntries = buildTitleArtistIndex(entries)
            sharedIndexBuiltAt = System.currentTimeMillis()
            sharedIndexFileCount = files.size
        }
        parsedIndexStore.replace(entries.map(::toPersistentIndexEntry))
        logger(
            "[QrcCacheIndex] warmup done entries=${entries.size} " +
                "costMs=${System.currentTimeMillis() - startedAt}"
        )
        notifyFuzzyIndexReady(entries.size)
        return QrcFuzzyIndexStatus(
            ready = entries.isNotEmpty(),
            warming = sharedIndexWarming.get(),
            entries = entries.size,
            files = files.size,
            builtAt = sharedIndexBuiltAt
        )
    }

    fun preloadFuzzyIndexAsync(force: Boolean = false) {
        val status = fuzzyIndexStatus()
        if (!force && status.ready && !status.warming) {
            return
        }
        if (!sharedIndexWarming.compareAndSet(false, true)) {
            return
        }
        val token = QrcMaintenanceCoordinator.tryStart(
            MaintenanceTaskType.FUZZY_INDEX_REBUILD,
            if (force) "force" else "preload",
            logger
        )
        if (token == null) {
            sharedIndexWarming.set(false)
            return
        }
        fuzzyIndexExecutor.execute {
            try {
                if (!token.cancelled &&
                    MaintenanceGuard.yieldIfRealtimeWindow(
                        MaintenanceTaskType.FUZZY_INDEX_REBUILD,
                        token,
                        logger
                    )
                ) {
                    warmupFuzzyIndex(force = force)
                }
            } catch (exception: Exception) {
                QrcMaintenanceCoordinator.fail(token, exception, logger)
                return@execute
            } finally {
                QrcMaintenanceCoordinator.finish(token, logger)
                sharedIndexWarming.set(false)
            }
        }
    }

    fun isFuzzyIndexWarming(): Boolean {
        return sharedIndexWarming.get()
    }

    fun fuzzyIndexStatus(): QrcFuzzyIndexStatus {
        synchronized(sharedIndexLock) {
            return QrcFuzzyIndexStatus(
                ready = sharedIndexEntries.isNotEmpty(),
                warming = sharedIndexWarming.get(),
                entries = sharedIndexEntries.size,
                files = sharedIndexFileCount.coerceAtLeast(0),
                builtAt = sharedIndexBuiltAt
            )
        }
    }

    fun cacheRoot(): File {
        return QrcLyricUtils.cacheDirectory(appContext)
    }

    fun buildingCacheRoot(): File {
        return File(appContext.getExternalFilesDir(null), "QrcLyricCacheV2_building")
    }

    fun backupCacheRoot(): File {
        return File(appContext.getExternalFilesDir(null), "QrcLyricCacheV1_backup")
    }

    fun readBySongKeyFromDirectory(songKey: String, directory: File): ParsedLyric? {
        val canonicalSongKey = canonicalSongKey(songKey)
        val file = songCacheFile(canonicalSongKey, directory)
        if (!file.exists()) {
            return null
        }
        return readCacheFileDetailed(
            file = file,
            expectedSongKey = canonicalSongKey,
            allowStale = true
        ).parsed
    }

    private fun readCacheFile(
        file: File,
        expectedSongKey: String?
    ): ParsedLyric? {
        val result = readCacheFileDetailed(
            file = file,
            expectedSongKey = expectedSongKey,
            allowStale = false
        )
        if (result.parsed == null) {
            logger("[QrcCache] invalid songKey=${expectedSongKey.orEmpty()}")
        }
        return result.parsed
    }

    private fun findFuzzy(
        title: String,
        artist: String,
        album: String,
        currentSongKey: String,
        traceId: String = "",
        shouldCancel: () -> Boolean = { false }
    ): FuzzyMatch? {
        val startedAt = System.currentTimeMillis()
        if (shouldCancel()) {
            trace(traceId, "fuzzy", "result=cancelled stage=start")
            return null
        }
        val currentTitle = QrcLyricUtils.normalizeForMatch(title)
        if (currentTitle.isBlank() || GENERIC_TITLES.contains(currentTitle)) {
            logger("[QrcCache] fuzzy rejected reason=bad title")
            trace(traceId, "fuzzy", "result=skipped reason=bad_title costMs=${System.currentTimeMillis() - startedAt}")
            return null
        }
        val currentArtistTokens = QrcLyricUtils.splitArtists(artist)
        val currentArtist = QrcLyricUtils.normalizeForMatch(artist)
        val currentAlbum = QrcLyricUtils.normalizeForMatch(album)
        val entries = ensureIndex()
        if (shouldCancel()) {
            trace(traceId, "fuzzy", "result=cancelled stage=index")
            return null
        }
        if (entries.isEmpty()) {
            trace(
                traceId,
                "fuzzy",
                "result=skipped reason=index_unavailable costMs=${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        val directCandidates = if (currentArtist.isNotBlank()) {
            synchronized(sharedIndexLock) {
                sharedTitleArtistEntries[titleArtistIndexKey(currentTitle, currentArtist)]
                    .orEmpty()
                    .toList()
            }
        } else {
            emptyList()
        }
        val direct = when {
            directCandidates.size == 1 -> directCandidates.first()
            directCandidates.size > 1 && currentAlbum.isNotBlank() ->
                directCandidates
                    .filter { it.normalizedAlbum == currentAlbum }
                    .singleOrNull()
            else -> null
        }
        val exactTitleCandidates = if (direct == null) {
            synchronized(sharedIndexLock) {
                sharedTitleEntries[currentTitle].orEmpty().toList()
            }
        } else {
            emptyList()
        }
        val candidates = if (direct != null) {
            trace(
                traceId,
                "titleArtistIndex",
                "result=hit matched=${direct.songKey} " +
                    "costMs=${System.currentTimeMillis() - startedAt}"
            )
            listOf(direct to DIRECT_TITLE_ARTIST_SCORE)
        } else {
            // A one-character CJK title is unsafe for a broad fuzzy scan, but a
            // unique exact title+artist row above is deterministic and should
            // still use the parsed cache (for example QQ Music's 《当》).
            if (currentTitle.length < MIN_FUZZY_TITLE_LENGTH) {
                logger("[QrcCache] fuzzy rejected reason=short title without exact artist")
                trace(
                    traceId,
                    "fuzzy",
                    "result=skipped reason=short_title_without_exact_artist " +
                        "costMs=${System.currentTimeMillis() - startedAt}"
                )
                return null
            }
            val scoredCandidates = mutableListOf<Pair<CacheIndexEntry, Int>>()
            // Most alias cases differ only in artist spelling. Restrict those
            // lookups to exact-title rows instead of scoring the whole parsed
            // cache index on Sony's slow CPU. scoreEntry still enforces artist
            // and ambiguity checks before any cache is accepted.
            val scoringEntries = exactTitleCandidates.takeIf { it.isNotEmpty() }
                ?: entries
            for (entry in scoringEntries) {
                if (shouldCancel()) {
                    trace(traceId, "fuzzy", "result=cancelled stage=scoring costMs=${System.currentTimeMillis() - startedAt}")
                    return null
                }
                scoreEntry(
                    entry = entry,
                    currentTitle = currentTitle,
                    currentArtistTokens = currentArtistTokens,
                    currentAlbum = currentAlbum
                )?.let { score -> scoredCandidates += entry to score }
            }
            scoredCandidates.sortedWith(
                compareByDescending<Pair<CacheIndexEntry, Int>> { it.second }
                    .thenByDescending { it.first.linesCount }
                    .thenByDescending { it.first.createdAt }
            )
        }

        val best = candidates.firstOrNull()
        if (best == null) {
            logger("[QrcCache] fuzzy rejected reason=no candidate")
            trace(traceId, "fuzzy", "result=miss reason=no_candidate costMs=${System.currentTimeMillis() - startedAt}")
            return null
        }
        val second = candidates.getOrNull(1)
        if (second != null && best.second - second.second < MIN_SCORE_GAP) {
            logger("[QrcCache] fuzzy rejected reason=ambiguous")
            trace(
                traceId,
                "fuzzy",
                "result=miss reason=ambiguous best=${best.second} second=${second.second} " +
                    "costMs=${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        if (best.second < MIN_FUZZY_SCORE) {
            logger("[QrcCache] fuzzy rejected reason=low score")
            trace(
                traceId,
                "fuzzy",
                "result=miss reason=low_score score=${best.second} " +
                    "costMs=${System.currentTimeMillis() - startedAt}"
            )
            return null
        }

        val artistMatched = currentArtistTokens.isEmpty() ||
            artistTokenHitCount(currentArtistTokens, best.first.artistTokens) > 0
        val titleExact = best.first.normalizedTitle == currentTitle
        if (!artistMatched && !(candidates.size == 1 && titleExact)) {
            logger("[QrcCache] fuzzy rejected reason=artist mismatch")
            trace(
                traceId,
                "fuzzy",
                "result=miss reason=artist_mismatch score=${best.second} " +
                    "costMs=${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        if (!artistMatched) {
            logger("[QrcCache] fuzzy warning artist not matched currentSongKey=$currentSongKey")
        }

        if (shouldCancel()) {
            trace(traceId, "fuzzy", "result=cancelled stage=read costMs=${System.currentTimeMillis() - startedAt}")
            return null
        }
        val readResult = readCacheFileDetailed(
            file = best.first.file,
            expectedSongKey = null,
            allowStale = true
        )
        val parsed = readResult.parsed ?: run {
            logFuzzyRejected(
                reason = readResult.rejectReason ?: "unknown exception",
                file = best.first.file,
                cachedSongKey = best.first.songKey,
                exceptionSummary = readResult.exceptionSummary
            )
            trace(
                traceId,
                "fuzzy",
                "result=miss reason=read_failed score=${best.second} " +
                    "costMs=${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        if (parsed.lines.isEmpty()) {
            logFuzzyRejected("lines empty", best.first.file, best.first.songKey)
            trace(traceId, "fuzzy", "result=miss reason=lines_empty costMs=${System.currentTimeMillis() - startedAt}")
            return null
        }
        val parsedTitle = QrcLyricUtils.normalizeForMatch(parsed.title)
        val titleMatches = parsedTitle.isNotBlank() &&
            (parsedTitle == currentTitle ||
                parsedTitle.contains(currentTitle) ||
                currentTitle.contains(parsedTitle))
        if (!titleMatches) {
            logFuzzyRejected("title mismatch after read", best.first.file, parsed.songKey)
            trace(traceId, "fuzzy", "result=miss reason=title_mismatch_after_read costMs=${System.currentTimeMillis() - startedAt}")
            return null
        }
        val parsedArtistTokens = QrcLyricUtils.splitArtists(parsed.artist)
        val parsedArtistMatched = currentArtistTokens.isEmpty() ||
            artistTokenHitCount(currentArtistTokens, parsedArtistTokens) > 0
        if (!parsedArtistMatched && !(candidates.size == 1 && titleExact)) {
            logFuzzyRejected("artist mismatch after read", best.first.file, parsed.songKey)
            trace(traceId, "fuzzy", "result=miss reason=artist_mismatch_after_read costMs=${System.currentTimeMillis() - startedAt}")
            return null
        }
        if (readResult.staleReason != null) {
            logger(
                "[QrcCache] fuzzy stale hit reason=${readResult.staleReason} " +
                    "currentSongKey=$currentSongKey matched=${parsed.songKey}"
            )
        }
        return FuzzyMatch(parsed, best.second)
    }

    private fun trace(id: String, stage: String, detail: String) {
        if (id.isBlank()) {
            return
        }
        LyricTraceLogger.legacy(id, stage, detail, logger)
    }

    private fun readCacheFileDetailed(
        file: File,
        expectedSongKey: String?,
        allowStale: Boolean
    ): CacheReadResult {
        if (!file.exists()) {
            return CacheReadResult(rejectReason = "cache file not exists")
        }
        return try {
            val objectValue = JSONObject(file.readText(Charsets.UTF_8))
            val schemaVersion = readSchemaVersion(objectValue)
            if (schemaVersion !in QRC_CACHE_SCHEMA_V1..QRC_CACHE_SCHEMA_V2) {
                return CacheReadResult(rejectReason = "version mismatch")
            }
            val songKey = objectValue.optString("songKey")
            if (expectedSongKey != null && songKey != expectedSongKey) {
                return CacheReadResult(rejectReason = "songKey mismatch")
            }
            val groupId = objectValue.optString("groupId")
            val qrcLastModified = objectValue.optLong("qrcLastModified")
            val cacheBuildVersion = objectValue.optInt("cacheBuildVersion", 0)
            val cachedLines = readLines(objectValue.optJSONArray("lines") ?: JSONArray())
            val lines = if (cacheBuildVersion < QRC_CACHE_BUILD_VERSION) {
                if (cachedLines.any { it.words.isNotEmpty() }) {
                    logger(
                        "[QrcCache] discard legacy word timing build=$cacheBuildVersion " +
                            "required=$QRC_CACHE_BUILD_VERSION groupId=$groupId"
                    )
                }
                cachedLines.map { it.copy(words = emptyList()) }
            } else {
                cachedLines
            }
            if (lines.isEmpty()) {
                return CacheReadResult(rejectReason = "lines empty")
            }

            val source = if (groupId.isBlank()) {
                null
            } else {
                File(QrcLyricUtils.qrcDirectory(), "$groupId.qrc")
            }
            val staleReason = when {
                source == null -> "source qrc path missing"
                !source.exists() -> "source qrc missing"
                source.lastModified() != qrcLastModified -> "qrcLastModified mismatch"
                else -> null
            }
            if (staleReason != null && !allowStale && staleReason != "source qrc missing") {
                return CacheReadResult(rejectReason = staleReason)
            }
            if (staleReason == "source qrc missing") {
                logger("[QrcCache] source qrc missing, use stale cache")
            }

            CacheReadResult(
                parsed = ParsedLyric(
                    songKey = songKey,
                    title = objectValue.optString("title"),
                    artist = objectValue.optString("artist"),
                    album = objectValue.optString("album"),
                    groupId = groupId,
                    qrcLastModified = qrcLastModified,
                    lines = lines,
                    schemaVersion = schemaVersion,
                    qrcPath = objectValue.optString("qrcPath"),
                    wordTimingStatus = if (cacheBuildVersion < QRC_CACHE_BUILD_VERSION) {
                        QrcWordTimingStatus.fromLines(lines)
                    } else {
                        QrcWordTimingStatus.fromValue(
                            objectValue.optString(
                                "wordTimingStatus",
                                QrcWordTimingStatus.fromLines(lines).name
                            )
                        )
                    },
                    groupFingerprint = readFingerprint(objectValue),
                    cacheBuildVersion = cacheBuildVersion,
                    translationParseFailed = objectValue.optBoolean(
                        "translationParseFailed",
                        false
                    ),
                    translationParseFailedReason = objectValue
                        .optString("translationParseFailedReason")
                        .takeIf(String::isNotBlank),
                    translationSourceLastModified = objectValue.optLong(
                        "translationSourceLastModified"
                    ),
                    translationSourceSize = objectValue.optLong("translationSourceSize"),
                    romanizationParseFailed = objectValue.optBoolean(
                        "romanizationParseFailed",
                        false
                    ),
                    romanizationParseFailedReason = objectValue
                        .optString("romanizationParseFailedReason")
                        .takeIf(String::isNotBlank),
                    romanizationSourceLastModified = objectValue.optLong(
                        "romanizationSourceLastModified"
                    ),
                    romanizationSourceSize = objectValue.optLong("romanizationSourceSize")
                ),
                staleReason = staleReason
            )
        } catch (exception: org.json.JSONException) {
            CacheReadResult(
                rejectReason = "json parse failed",
                exceptionSummary = "${exception::class.java.simpleName}: ${exception.message.orEmpty()}"
            )
        } catch (exception: Exception) {
            CacheReadResult(
                rejectReason = "unknown exception",
                exceptionSummary = "${exception::class.java.simpleName}: ${exception.message.orEmpty()}"
            )
        }
    }

    private fun readLines(linesArray: JSONArray): List<QrcLyricLine> {
        return (0 until linesArray.length()).mapNotNull { index ->
            val lineObject = linesArray.optJSONObject(index) ?: return@mapNotNull null
            val text = lineObject.optString("text")
            if (text.isBlank()) {
                null
            } else {
                QrcLyricLine(
                    timeMs = lineObject.optLong("timeMs"),
                    text = text,
                    durationMs = lineObject.optLong("durationMs"),
                    words = readWords(lineObject.optJSONArray("words") ?: JSONArray()),
                    translation = lineObject.optString("translation")
                        .takeIf(String::isNotBlank),
                    romanization = lineObject.optString("romanization")
                        .takeIf(String::isNotBlank)
                )
            }
        }.sortedBy(QrcLyricLine::timeMs)
    }

    private fun readWords(wordsArray: JSONArray): List<QrcLyricWord> {
        return (0 until wordsArray.length()).mapNotNull { index ->
            val wordObject = wordsArray.optJSONObject(index) ?: return@mapNotNull null
            val text = wordObject.optString("text")
            if (text.isBlank()) {
                null
            } else {
                QrcLyricWord(
                    startMs = wordObject.optLong("startMs"),
                    durationMs = wordObject.optLong("durationMs"),
                    text = text
                )
            }
        }
    }

    private fun logFuzzyRejected(
        reason: String,
        file: File,
        cachedSongKey: String,
        exceptionSummary: String? = null
    ) {
        val suffix = if (exceptionSummary.isNullOrBlank()) {
            ""
        } else {
            " exception=$exceptionSummary"
        }
        logger(
            "[QrcCache] fuzzy rejected reason=$reason " +
                "file=${file.name} cachedSongKey=$cachedSongKey$suffix"
        )
    }

    private fun scoreEntry(
        entry: CacheIndexEntry,
        currentTitle: String,
        currentArtistTokens: Set<String>,
        currentAlbum: String
    ): Int? {
        if (entry.linesCount <= 0) {
            return null
        }
        if (QrcLyricUtils.isInvalidMetadataTitle(entry.normalizedTitle)) {
            logger("[QrcCache] invalid metadata cache ignored songKey=${entry.songKey}")
            return null
        }
        val titleExact = entry.normalizedTitle == currentTitle
        val titleContains = !titleExact &&
            (entry.normalizedTitle.contains(currentTitle) ||
                currentTitle.contains(entry.normalizedTitle))
        if (!titleExact && !titleContains) {
            return null
        }

        var score = if (titleExact) 100 else 80
        val artistHits = artistTokenHitCount(currentArtistTokens, entry.artistTokens)
        if (artistHits >= 2) {
            score += 60
        } else if (artistHits == 1) {
            score += 30
        }
        if (currentAlbum.isNotBlank() && entry.normalizedAlbum.isNotBlank()) {
            score += when {
                entry.normalizedAlbum == currentAlbum -> 20
                entry.normalizedAlbum.contains(currentAlbum) ||
                    currentAlbum.contains(entry.normalizedAlbum) -> 10
                else -> 0
            }
        }
        score += 10
        score += recencyScore(entry.createdAt)
        return score
    }

    private fun artistTokenHitCount(
        currentTokens: Set<String>,
        entryTokens: Set<String>
    ): Int {
        if (currentTokens.isEmpty() || entryTokens.isEmpty()) {
            return 0
        }
        return currentTokens.count { current ->
            entryTokens.any { entry ->
                entry == current || entry.contains(current) || current.contains(entry)
            }
        }
    }

    private fun recencyScore(createdAt: Long): Int {
        val ageMs = System.currentTimeMillis() - createdAt
        return when {
            ageMs < 24L * 60L * 60L * 1000L -> 5
            ageMs < 7L * 24L * 60L * 60L * 1000L -> 3
            createdAt > 0L -> 1
            else -> 0
        }
    }

    private fun ensureIndex(): List<CacheIndexEntry> {
        synchronized(sharedIndexLock) {
            if (sharedIndexEntries.isNotEmpty()) {
                return sharedIndexEntries
            }
        }

        installPersistedIndexIfAvailable()
        synchronized(sharedIndexLock) {
            if (sharedIndexEntries.isNotEmpty()) {
                return sharedIndexEntries
            }
        }

        if (!sharedIndexWarming.compareAndSet(false, true)) {
            logger("[QrcCacheIndex] fuzzy skipped reason=index warming")
            return emptyList()
        }
        // This is the prerequisite for the foreground lyric lookup, not optional
        // maintenance. Deferring it because that lookup is active creates a
        // self-lock: every request sees an empty index and no request can warm it.
        logger("[QrcCacheIndex] foreground bootstrap scheduled")
        fuzzyIndexExecutor.execute {
            try {
                warmupFuzzyIndex(force = true)
            } finally {
                sharedIndexWarming.set(false)
            }
        }
        return emptyList()
    }

    private fun buildFuzzyIndex(files: List<File>): List<CacheIndexEntry> {
        val deduped = linkedMapOf<String, CacheIndexEntry>()
        files.forEach { file ->
            val entry = readIndexEntry(file) ?: return@forEach
            val existing = deduped[entry.songKey]
            if (existing == null ||
                entry.linesCount > existing.linesCount ||
                (entry.linesCount == existing.linesCount &&
                    entry.createdAt > existing.createdAt)
            ) {
                deduped[entry.songKey] = entry
            }
        }
        val entries = deduped.values.toList()
        logger("[QrcCache] fuzzy index built entries=${entries.size} files=${files.size}")
        return entries
    }

    private fun cacheJsonFiles(): List<File> {
        return cacheRoot().listFiles { file ->
            file.isFile &&
                file.extension.equals("json", ignoreCase = true) &&
                file.name != QrcParsedCacheIndexStore.INDEX_FILE_NAME &&
                file.name != "QrcIndex.json"
        }.orEmpty().toList()
    }

    private fun installPersistedIndexIfAvailable() {
        if (!parsedIndexStore.loaded) return
        val root = cacheRoot()
        val persisted = parsedIndexStore.snapshot().map { entry ->
            val file = File(root, entry.fileName)
            CacheIndexEntry(
                songKey = entry.songKey,
                normalizedTitle = entry.normalizedTitle,
                normalizedArtist = entry.normalizedArtist,
                normalizedAlbum = entry.normalizedAlbum,
                artistTokens = QrcLyricUtils.splitArtists(entry.artist),
                title = entry.title,
                artist = entry.artist,
                album = entry.album,
                file = file,
                linesCount = entry.lines,
                createdAt = entry.createdAt,
                groupId = entry.groupId,
                fingerprint = entry.fingerprint
            )
        }
        if (persisted.isEmpty()) return
        synchronized(sharedIndexLock) {
            if (sharedIndexEntries.isEmpty() || persisted.size > sharedIndexEntries.size) {
                sharedIndexEntries = persisted
                sharedTitleEntries = buildTitleIndex(persisted)
                sharedTitleArtistEntries = buildTitleArtistIndex(persisted)
                sharedIndexBuiltAt = System.currentTimeMillis()
                sharedIndexFileCount = persisted.size
            }
        }
    }

    private fun upsertParsedIndex(parsed: ParsedLyric, songFile: File) {
        val entry = CacheIndexEntry(
            songKey = parsed.songKey,
            normalizedTitle = QrcLyricUtils.normalizeForMatch(parsed.title),
            normalizedArtist = QrcLyricUtils.normalizeForMatch(parsed.artist),
            normalizedAlbum = QrcLyricUtils.normalizeForMatch(parsed.album),
            artistTokens = QrcLyricUtils.splitArtists(parsed.artist),
            title = parsed.title,
            artist = parsed.artist,
            album = parsed.album,
            file = songFile,
            linesCount = parsed.lines.size,
            createdAt = System.currentTimeMillis(),
            groupId = parsed.groupId.takeIf(String::isNotBlank),
            fingerprint = fingerprintKey(parsed.groupFingerprint)
        )
        synchronized(sharedIndexLock) {
            val updated = sharedIndexEntries
                .filterNot { it.songKey == entry.songKey }
                .toMutableList()
                .apply { add(entry) }
            sharedIndexEntries = updated
            sharedTitleEntries = buildTitleIndex(updated)
            sharedTitleArtistEntries = buildTitleArtistIndex(updated)
            sharedIndexBuiltAt = System.currentTimeMillis()
            sharedIndexFileCount = updated.size
        }
        parsedIndexStore.upsert(toPersistentIndexEntry(entry))
    }

    private fun toPersistentIndexEntry(entry: CacheIndexEntry): QrcParsedCacheIndexStore.Entry {
        return QrcParsedCacheIndexStore.Entry(
            songKey = entry.songKey,
            normalizedTitle = entry.normalizedTitle,
            normalizedArtist = entry.normalizedArtist,
            normalizedAlbum = entry.normalizedAlbum,
            title = entry.title,
            artist = entry.artist,
            album = entry.album,
            groupId = entry.groupId,
            fileName = entry.file.name,
            lines = entry.linesCount,
            createdAt = entry.createdAt,
            fingerprint = entry.fingerprint
        )
    }

    private fun fingerprintKey(value: QrcGroupFingerprint?): String? {
        value ?: return null
        return listOf(
            value.qrcLastModified,
            value.qrcSize,
            value.producerLastModified,
            value.producerSize,
            value.exLastModified,
            value.exSize,
            value.translrcLastModified,
            value.translrcSize,
            value.romaqrcLastModified,
            value.romaqrcSize
        ).joinToString(":")
    }

    private fun readIndexEntry(file: File): CacheIndexEntry? {
        return try {
            val objectValue = JSONObject(file.readText(Charsets.UTF_8))
            if (readSchemaVersion(objectValue) !in QRC_CACHE_SCHEMA_V1..QRC_CACHE_SCHEMA_V2) {
                return null
            }
            val songKey = objectValue.optString("songKey")
            val title = objectValue.optString("title")
            val artist = objectValue.optString("artist")
            val album = objectValue.optString("album")
            val linesCount = objectValue.optJSONArray("lines")?.length() ?: 0
            if (songKey.isBlank() || title.isBlank() || linesCount <= 0) {
                return null
            }
            val groupIdValue = objectValue.optString("groupId")
            CacheIndexEntry(
                songKey = songKey,
                normalizedTitle = QrcLyricUtils.normalizeForMatch(title),
                normalizedArtist = QrcLyricUtils.normalizeForMatch(artist),
                normalizedAlbum = QrcLyricUtils.normalizeForMatch(album),
                artistTokens = QrcLyricUtils.splitArtists(artist),
                title = title,
                artist = artist,
                album = album,
                file = file,
                linesCount = linesCount,
                createdAt = objectValue.optLong("createdAt"),
                groupId = if (groupIdValue.isBlank()) null else groupIdValue,
                fingerprint = fingerprintKey(readFingerprint(objectValue))
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun shouldKeepExisting(
        existing: ParsedLyric,
        candidate: ParsedLyric
    ): Boolean {
        val existingWordCount = existing.lines.sumOf { it.words.size }
        val candidateWordCount = candidate.lines.sumOf { it.words.size }
        val existingTranslationCount = existing.lines.count { !it.translation.isNullOrBlank() }
        val candidateTranslationCount = candidate.lines.count { !it.translation.isNullOrBlank() }
        val existingRomanizationCount = existing.lines.count { !it.romanization.isNullOrBlank() }
        val candidateRomanizationCount = candidate.lines.count { !it.romanization.isNullOrBlank() }
        if (candidateTranslationCount > existingTranslationCount) {
            return false
        }
        if (candidateRomanizationCount > existingRomanizationCount) {
            return false
        }
        if (candidateWordCount > existingWordCount) {
            return false
        }
        return existing.lines.size > candidate.lines.size ||
            (existing.lines.size == candidate.lines.size &&
                existing.qrcLastModified >= candidate.qrcLastModified)
    }

    private fun songCacheFile(songKey: String): File {
        return songCacheFile(songKey, cacheRoot())
    }

    private fun groupCacheFile(groupId: String): File {
        return groupCacheFile(groupId, cacheRoot())
    }

    private fun songCacheFile(songKey: String, directory: File): File {
        return File(directory, "${QrcLyricUtils.cacheKey(songKey)}.json")
    }

    private fun canonicalSongKey(songKey: String): String {
        val parts = songKey.split("|", limit = 3)
        return if (parts.size == 3) {
            QrcLyricUtils.buildSongKey(parts[0], parts[1], parts[2])
        } else {
            QrcLyricUtils.normalizeForMatch(songKey)
        }
    }

    private fun ParsedLyric.withCanonicalSongKey(): ParsedLyric {
        val canonicalSongKey = canonicalSongKey(songKey)
        return if (canonicalSongKey == songKey) this else copy(songKey = canonicalSongKey)
    }

    private fun groupCacheFile(groupId: String, directory: File): File {
        return File(directory, "group_$groupId.json")
    }

    private fun toJson(parsed: ParsedLyric): JSONObject {
        val fingerprint = parsed.groupFingerprint
        val parsedTranslationCount = parsed.lines.count { !it.translation.isNullOrBlank() }
        val parsedRomanizationCount = parsed.lines.count { !it.romanization.isNullOrBlank() }
        val parsedWordLineCount = parsed.lines.count { it.words.isNotEmpty() }
        return JSONObject()
            .put("schemaVersion", QRC_CACHE_SCHEMA_V2)
            .put("version", QRC_CACHE_SCHEMA_V2)
            .put("cacheBuildVersion", parsed.cacheBuildVersion)
            .put("songKey", parsed.songKey)
            .put("title", parsed.title)
            .put("artist", parsed.artist)
            .put("album", parsed.album)
            .put("groupId", parsed.groupId)
            .put("qrcPath", parsed.qrcPath)
            .put("qrcLastModified", fingerprint?.qrcLastModified ?: parsed.qrcLastModified)
            .put("qrcSize", fingerprint?.qrcSize ?: 0L)
            .put("producerLastModified", fingerprint?.producerLastModified ?: 0L)
            .put("producerSize", fingerprint?.producerSize ?: 0L)
            .put("exLastModified", fingerprint?.exLastModified ?: 0L)
            .put("exSize", fingerprint?.exSize ?: 0L)
            .put("translrcLastModified", fingerprint?.translrcLastModified ?: 0L)
            .put("translrcSize", fingerprint?.translrcSize ?: 0L)
            .put("romaqrcLastModified", fingerprint?.romaqrcLastModified ?: 0L)
            .put("romaqrcSize", fingerprint?.romaqrcSize ?: 0L)
            .put("hasQrc", fingerprint?.hasQrc ?: false)
            .put("hasProducer", fingerprint?.hasProducer ?: false)
            .put("hasEx", fingerprint?.hasEx ?: false)
            .put("hasTranslrc", fingerprint?.hasTranslrc ?: false)
            .put("hasRomaqrc", fingerprint?.hasRomaqrc ?: false)
            .put("parsedTranslationCount", parsedTranslationCount)
            .put("parsedRomanizationCount", parsedRomanizationCount)
            .put("parsedWordLineCount", parsedWordLineCount)
            .put("parsedLineCount", parsed.lines.count { it.text.isNotBlank() })
            .put("translationParseFailed", parsed.translationParseFailed)
            .put("translationSourceLastModified", parsed.translationSourceLastModified)
            .put("translationSourceSize", parsed.translationSourceSize)
            .put("romanizationParseFailed", parsed.romanizationParseFailed)
            .put("romanizationSourceLastModified", parsed.romanizationSourceLastModified)
            .put("romanizationSourceSize", parsed.romanizationSourceSize)
            .put("createdAt", System.currentTimeMillis())
            .put("wordTimingStatus", parsed.wordTimingStatus.name)
            .also { root ->
                parsed.translationParseFailedReason
                    ?.takeIf(String::isNotBlank)
                    ?.let { root.put("translationParseFailedReason", it) }
                parsed.romanizationParseFailedReason
                    ?.takeIf(String::isNotBlank)
                    ?.let { root.put("romanizationParseFailedReason", it) }
            }
            .put("lines", JSONArray().also { array ->
                parsed.lines
                    .filter { it.text.isNotBlank() }
                    .sortedBy(QrcLyricLine::timeMs)
                    .forEach { line ->
                        array.put(
                            JSONObject()
                                .put("timeMs", line.timeMs)
                                .put("text", line.text)
                                .put("durationMs", line.durationMs)
                                .also { lineObject ->
                                    line.translation
                                        ?.takeIf(String::isNotBlank)
                                        ?.let { lineObject.put("translation", it) }
                                    line.romanization
                                        ?.takeIf(String::isNotBlank)
                                        ?.let { lineObject.put("romanization", it) }
                                    if (line.words.isNotEmpty()) {
                                        lineObject.put(
                                            "words",
                                            JSONArray().also { wordsArray ->
                                                line.words
                                                    .filter { it.text.isNotBlank() }
                                                    .sortedBy(QrcLyricWord::startMs)
                                                    .forEach { word ->
                                                        wordsArray.put(
                                                            JSONObject()
                                                                .put("startMs", word.startMs)
                                                                .put("durationMs", word.durationMs)
                                                                .put("text", word.text)
                                                        )
                                                    }
                                            }
                                        )
                                    }
                                }
                        )
                    }
            })
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(
            file.parentFile,
            ".${file.name}.${System.nanoTime()}.${Thread.currentThread().id}.tmp"
        )
        FileOutputStream(temp).use { output ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            output.write(bytes)
            output.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("failed to delete old cache file ${file.name}")
        }
        if (!temp.renameTo(file)) {
            throw IllegalStateException("failed to rename cache file ${file.name}")
        }
    }

    private fun readSchemaVersion(objectValue: JSONObject): Int {
        val schema = objectValue.optInt("schemaVersion", 0)
        return if (schema > 0) schema else objectValue.optInt("version")
    }

    private fun readFingerprint(objectValue: JSONObject): QrcGroupFingerprint? {
        if (!objectValue.has("cacheBuildVersion") ||
            !objectValue.has("qrcSize") ||
            !objectValue.has("hasQrc")
        ) {
            return null
        }
        return QrcGroupFingerprint(
            qrcLastModified = objectValue.optLong("qrcLastModified"),
            qrcSize = objectValue.optLong("qrcSize"),
            producerLastModified = objectValue.optLong("producerLastModified"),
            producerSize = objectValue.optLong("producerSize"),
            exLastModified = objectValue.optLong("exLastModified"),
            exSize = objectValue.optLong("exSize"),
            translrcLastModified = objectValue.optLong("translrcLastModified"),
            translrcSize = objectValue.optLong("translrcSize"),
            romaqrcLastModified = objectValue.optLong("romaqrcLastModified"),
            romaqrcSize = objectValue.optLong("romaqrcSize"),
            hasQrc = objectValue.optBoolean("hasQrc"),
            hasProducer = objectValue.optBoolean("hasProducer"),
            hasEx = objectValue.optBoolean("hasEx"),
            hasTranslrc = objectValue.optBoolean("hasTranslrc"),
            hasRomaqrc = objectValue.optBoolean("hasRomaqrc")
        )
    }

    private fun groupInvalidReason(
        group: QrcFileGroup,
        cached: ParsedLyric,
        requireComplete: Boolean
    ): String? {
        if (cached.lines.isEmpty()) {
            return "lines empty"
        }
        if (requireComplete && cached.cacheBuildVersion < QRC_CACHE_BUILD_VERSION) {
            return "cacheBuildVersion ${cached.cacheBuildVersion} < $QRC_CACHE_BUILD_VERSION"
        }
        val currentFingerprint = QrcLyricUtils.buildFingerprint(group)
        val cachedFingerprint = cached.groupFingerprint
            ?: return "fingerprint missing"
        if (cachedFingerprint != currentFingerprint) {
            logger("[QrcCache] fingerprint changed groupId=${group.groupId}")
            return "fingerprint changed"
        }
        val translationCount = cached.lines.count { !it.translation.isNullOrBlank() }
        if (currentFingerprint.hasTranslrc &&
            translationCount == 0 &&
            !hasParseFailureForSameTranslation(cached, currentFingerprint)
        ) {
            logger("[QrcCache] translation missing groupId=${group.groupId}")
            return "translation missing"
        }
        val romanizationCount = cached.lines.count { !it.romanization.isNullOrBlank() }
        if (currentFingerprint.hasRomaqrc &&
            romanizationCount == 0 &&
            !hasParseFailureForSameRomanization(cached, currentFingerprint)
        ) {
            logger("[QrcCache] romanization missing groupId=${group.groupId}")
            return "romanization missing"
        }
        if (requireComplete && cached.lines.size <= SUSPICIOUS_MIN_LINES) {
            logger(
                "[QrcCache] suspicious too few lines groupId=${group.groupId} " +
                    "lines=${cached.lines.size}"
            )
            return "suspicious too few lines"
        }
        return null
    }

    private fun hasParseFailureForSameTranslation(
        cached: ParsedLyric,
        fingerprint: QrcGroupFingerprint
    ): Boolean {
        return cached.translationParseFailed &&
            cached.translationSourceLastModified == fingerprint.translrcLastModified &&
            cached.translationSourceSize == fingerprint.translrcSize
    }

    private fun hasParseFailureForSameRomanization(
        cached: ParsedLyric,
        fingerprint: QrcGroupFingerprint
    ): Boolean {
        return cached.romanizationParseFailed &&
            cached.romanizationSourceLastModified == fingerprint.romaqrcLastModified &&
            cached.romanizationSourceSize == fingerprint.romaqrcSize
    }

    companion object {
        private const val MAX_MEMORY_CACHE = 120
        private const val INDEX_TTL_MS = 5L * 60L * 1000L
        private const val MIN_FUZZY_TITLE_LENGTH = 2
        private const val MIN_FUZZY_SCORE = 120
        private const val DIRECT_TITLE_ARTIST_SCORE = 1_000
        private const val MIN_SCORE_GAP = 20
        private const val SUSPICIOUS_MIN_LINES = 5
        private const val STATS_LOG_INTERVAL = 50L
        private var sharedStats = MutableLyricCacheStats()
        private var sharedQueryCount = 0L
        private val sharedIndexLock = Any()
        private var sharedIndexEntries: List<CacheIndexEntry> = emptyList()
        private var sharedTitleEntries: Map<String, List<CacheIndexEntry>> = emptyMap()
        private var sharedTitleArtistEntries: Map<String, List<CacheIndexEntry>> = emptyMap()
        private var sharedIndexBuiltAt: Long = 0L
        private var sharedIndexFileCount: Int = -1
        private val sharedIndexWarming = AtomicBoolean(false)
        private val fuzzyIndexReadyListeners = CopyOnWriteArrayList<(QrcFuzzyIndexStatus) -> Unit>()
        private val cacheWriteLock = Any()
        private val pendingWrites = ConcurrentHashMap<String, ParsedLyric>()
        private val sharedParsedIndexStores =
            ConcurrentHashMap<String, QrcParsedCacheIndexStore>()
        private val cacheWriteExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "QrcCacheWriteThread").apply {
                priority = Thread.MIN_PRIORITY
            }
        }

        fun addFuzzyIndexReadyListener(listener: (QrcFuzzyIndexStatus) -> Unit) {
            fuzzyIndexReadyListeners += listener
        }

        fun removeFuzzyIndexReadyListener(listener: (QrcFuzzyIndexStatus) -> Unit) {
            fuzzyIndexReadyListeners -= listener
        }

        private fun notifyFuzzyIndexReady(entries: Int) {
            val status = synchronized(sharedIndexLock) {
                QrcFuzzyIndexStatus(
                    ready = entries > 0,
                    warming = sharedIndexWarming.get(),
                    entries = entries,
                    files = sharedIndexFileCount.coerceAtLeast(0),
                    builtAt = sharedIndexBuiltAt
                )
            }
            fuzzyIndexReadyListeners.forEach { listener ->
                runCatching { listener(status) }
            }
        }
        private val fuzzyIndexExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "QrcCacheFuzzyIndexWarmupThread").apply {
                priority = Thread.MIN_PRIORITY
            }
        }
        private val GENERIC_TITLES = setOf(
            "intro",
            "outro",
            "interlude",
            "remix",
            "live"
        )

        private fun clearSharedFuzzyIndex() {
            synchronized(sharedIndexLock) {
                sharedIndexEntries = emptyList()
                sharedTitleEntries = emptyMap()
                sharedTitleArtistEntries = emptyMap()
                sharedIndexBuiltAt = 0L
                sharedIndexFileCount = -1
            }
        }

        private fun titleArtistIndexKey(title: String, artist: String): String =
            "$title\u0000$artist"

        private fun buildTitleIndex(
            entries: List<CacheIndexEntry>
        ): Map<String, List<CacheIndexEntry>> {
            return entries
                .asSequence()
                .filter { it.normalizedTitle.isNotBlank() }
                .groupBy(CacheIndexEntry::normalizedTitle)
        }

        private fun buildTitleArtistIndex(
            entries: List<CacheIndexEntry>
        ): Map<String, List<CacheIndexEntry>> {
            return entries
                .asSequence()
                .filter { it.normalizedTitle.isNotBlank() && it.normalizedArtist.isNotBlank() }
                .groupBy {
                    titleArtistIndexKey(it.normalizedTitle, it.normalizedArtist)
                }
        }
    }

    private data class FuzzyMatch(
        val parsed: ParsedLyric,
        val score: Int
    )

    private data class CacheReadResult(
        val parsed: ParsedLyric? = null,
        val rejectReason: String? = null,
        val staleReason: String? = null,
        val exceptionSummary: String? = null
    )

    data class CacheIndexEntry(
        val songKey: String,
        val normalizedTitle: String,
        val normalizedArtist: String,
        val normalizedAlbum: String,
        val artistTokens: Set<String>,
        val title: String,
        val artist: String,
        val album: String,
        val file: File,
        val linesCount: Int,
        val createdAt: Long,
        val groupId: String?,
        val fingerprint: String? = null
    )

    data class GroupCacheValidation(
        val valid: Boolean,
        val reason: String = "",
        val cached: ParsedLyric? = null
    )

    private fun maybeLogStats() {
        if (sharedQueryCount > 0L &&
            sharedQueryCount % STATS_LOG_INTERVAL == 0L
        ) {
            logger(
                "[LyricStats] l1=${sharedStats.l1Hit} " +
                    "l2=${sharedStats.l2Hit} " +
                    "fuzzy=${sharedStats.l2FuzzyHit} " +
                    "alias=${sharedStats.aliasHit} " +
                    "negative=${sharedStats.negativeHit} " +
                    "qrc=${sharedStats.qrcDecryptCount} " +
                    "success=${sharedStats.qrcDecryptSuccess} " +
                    "failed=${sharedStats.qrcDecryptFailed}"
            )
        }
    }

    private data class MutableLyricCacheStats(
        var l1Hit: Long = 0,
        var l2Hit: Long = 0,
        var l2FuzzyHit: Long = 0,
        var aliasHit: Long = 0,
        var negativeHit: Long = 0,
        var qrcDecryptCount: Long = 0,
        var qrcDecryptSuccess: Long = 0,
        var qrcDecryptFailed: Long = 0,
        var negativeSaved: Long = 0,
        var aliasSaved: Long = 0,
        var lastSource: String = "NONE"
    ) {
        fun toImmutable(): LyricCacheStats {
            return LyricCacheStats(
                l1Hit = l1Hit,
                l2Hit = l2Hit,
                l2FuzzyHit = l2FuzzyHit,
                aliasHit = aliasHit,
                negativeHit = negativeHit,
                qrcDecryptCount = qrcDecryptCount,
                qrcDecryptSuccess = qrcDecryptSuccess,
                qrcDecryptFailed = qrcDecryptFailed,
                negativeSaved = negativeSaved,
                aliasSaved = aliasSaved,
                lastSource = lastSource
            )
        }
    }
}
