package com.example.controllerapp.history

import android.util.Base64
import com.example.controllerapp.data.HistoryCursorEntity
import com.example.controllerapp.data.PlaybackHistoryDao
import com.example.controllerapp.data.PlaybackHistoryEntity
import com.example.controllerapp.model.*
import com.example.controllerapp.protocol.ControllerProtocolCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** History has its own request lifetime; a playing-track generation never owns it.
 *
 * Every action, including Room transactions and cache reads, passes through [operations].
 * Invalidation changes [epoch] immediately, even while a Room call is suspended. Clear
 * then waits behind that call and deletes both rows and cursors before accepting new work.
 */
class PlaybackHistoryCoordinator(
    private val dao: PlaybackHistoryDao,
    dispatcher: CoroutineDispatcher,
    private val timeoutMs: Long = 8_000L,
    private val send: (String, JSONObject) -> Boolean,
    private val log: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val operations = Channel<Operation>(Channel.UNLIMITED)
    private val entryGate = Any()
    private val epoch = AtomicLong()
    private val clearGeneration = AtomicLong()
    @Volatile private var closed = false
    private var active: Request? = null
    private var timeout: Job? = null
    private var transfer: Transfer? = null
    private val ranges = ArrayDeque<String>()
    private var historyFailureReason: String? = null
    private var earliestOpenId: Long? = null
    private var windowBefore = Long.MAX_VALUE
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()
    val currentEpoch: Long get() = epoch.get()

    init {
        scope.launch {
            for (operation in operations) {
                val token = operation.epoch
                if (token != null && !isCurrent(token)) continue
                try {
                    operation.action(token ?: epoch.get())
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (token != null && isCurrent(token)) {
                        active?.let { fail(it, "历史处理失败") } ?: run {
                            ranges.clear()
                            publish(token) { it.copy(loading = false, status = "本地历史读取失败，可重试") }
                        }
                        log("[History] operation failed: ${error.message}")
                    }
                }
            }
        }
        enqueue { token -> refreshWindow(token) }
    }

    fun sync() = enqueue { token ->
        if (_state.value.loading || _state.value.clearing) return@enqueue
        if (!publish(token) { it.copy(loading = true, status = "正在同步历史", failedRanges = emptySet()) }) return@enqueue
        historyFailureReason = null
        earliestOpenId = null
        ranges.clear()
        ranges.addAll(listOf("TODAY", "WEEK", "MONTH"))
        refreshWindow(token)
        if (!isCurrent(token)) return@enqueue
        val cursor = dao.cursor() ?: HistoryCursorEntity()
        requestSince(cursor.sinceSessionId, token)
    }

    fun loadMore() = enqueue { token ->
        if (_state.value.loading || _state.value.clearing || !_state.value.hasMore) return@enqueue
        if (!publish(token) { it.copy(loading = true, status = "正在读取更早历史") }) return@enqueue
        val before = _state.value.sessions.lastOrNull()?.sessionId ?: Long.MAX_VALUE
        val local = dao.readBefore(before, LOCAL_PAGE_SIZE)
        if (!isCurrent(token)) return@enqueue
        if (local.isNotEmpty()) {
            publishWindow(_state.value.sessions + local.map(PlaybackHistoryEntity::toModel), token)
            updateHasMore(token)
            publish(token) { it.copy(loading = false, status = "已加载本地历史") }
            return@enqueue
        }
        val cursor = dao.cursor() ?: HistoryCursorEntity()
        if (!isCurrent(token)) return@enqueue
        if (!cursor.olderHasMore) {
            publish(token) { it.copy(loading = false, hasMore = false, status = "已显示全部历史") }
            return@enqueue
        }
        // MAX is only a bootstrap sentinel. Once a page is committed, always reuse
        // Sony's exact nextBeforeSessionId, independent of the visible list window.
        val remoteBefore = if (cursor.olderBeforeSessionId == Long.MAX_VALUE) {
            dao.oldestSessionId() ?: Long.MAX_VALUE
        } else cursor.olderBeforeSessionId
        request(RequestKind.PAGE, remoteBefore, token = token)
    }

    fun showRecent() = enqueue { token ->
        if (_state.value.loading || _state.value.clearing) return@enqueue
        windowBefore = Long.MAX_VALUE
        refreshWindow(token, LOCAL_PAGE_SIZE, preserveAnchor = false)
        publish(token) { it.copy(status = "已返回最近播放") }
    }

    fun clear() = synchronized(entryGate) {
        if (closed) return@synchronized
        epoch.incrementAndGet()
        val clearId = clearGeneration.incrementAndGet()
        _state.update { it.copy(loading = false, clearing = true, status = "正在清理本地历史") }
        operations.trySend(Operation(null) {
            resetRequest()
            ranges.clear()
            try {
                // The single consumer covers the real commit and the real clear.
                dao.clearHistory()
                synchronized(entryGate) {
                    windowBefore = Long.MAX_VALUE
                    if (clearGeneration.get() == clearId) {
                        _state.value = HistoryState(status = "本地缓存已清理")
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                synchronized(entryGate) {
                    if (clearGeneration.get() == clearId) _state.update {
                        it.copy(clearing = false, status = "本地历史清理失败，可重试")
                    }
                }
                log("[History] clear failed: ${error.message}")
            }
        })
        Unit
    }

    fun invalidate(reason: String) = synchronized(entryGate) {
        if (closed) return@synchronized
        epoch.incrementAndGet()
        _state.update { it.copy(loading = false, status = "$reason，已保留本地历史，可重试") }
        enqueue { token ->
            synchronized(entryGate) {
                if (isCurrent(token)) { resetRequest(); ranges.clear() }
            }
            refreshWindow(token)
        }
    }

    fun receive(value: JSONObject, receivedEpoch: Long = epoch.get()) = enqueue { token ->
        if (receivedEpoch != token) return@enqueue
        val request = active ?: return@enqueue
        if (!isCurrent(request.epoch) || value.optString("requestId") != request.id) return@enqueue
        when (value.optString("type")) {
            "historyPayloadStart" -> startTransfer(request, value)
            "historyPayloadChunk" -> appendTransfer(request, value)
            "historyPayloadEnd" -> finishTransfer(request, value)
            "playHistoryError" -> {
                if (value.optString("responseType") != request.kind.responseType) {
                    fail(request, "历史错误响应类型不符")
                } else fail(request, value.optString("message", "Sony 历史请求失败"))
            }
            else -> applyPayload(request, value)
        }
    }

    fun onWriteFailed(requestId: String) = enqueue {
        active?.takeIf { it.id == requestId }?.let { fail(it, "历史命令写入失败") }
    }

    fun close() = synchronized(entryGate) {
        if (closed) return@synchronized
        closed = true
        epoch.incrementAndGet()
        _state.update { it.copy(loading = false, status = "历史同步已关闭") }
        // Let an already accepted clear finish before closing the persistence scope.
        operations.trySend(Operation(null) {
            resetRequest()
            ranges.clear()
            operations.close()
            scope.cancel()
        })
        Unit
    }

    private fun enqueue(action: suspend (Long) -> Unit) = synchronized(entryGate) {
        if (closed) return@synchronized
        operations.trySend(Operation(epoch.get(), action))
        Unit
    }

    private fun isCurrent(token: Long) = !closed && token == epoch.get()

    private fun publish(token: Long, transform: (HistoryState) -> HistoryState): Boolean = synchronized(entryGate) {
        if (!isCurrent(token)) return@synchronized false
        _state.update(transform)
        true
    }

    private fun requestSince(after: Long, token: Long) = request(RequestKind.SINCE, after, token = token)

    private fun request(kind: RequestKind, cursor: Long = 0L, range: String? = null, token: Long) = synchronized(entryGate) {
        if (!isCurrent(token)) return@synchronized
        resetRequest()
        val request = Request("h-${requestSequence.incrementAndGet().toString(36)}", kind, cursor, range, token)
        active = request
        val extra = JSONObject().put("requestId", request.id)
        when (kind) {
            RequestKind.SINCE -> extra.put("afterSessionId", cursor).put("limit", REMOTE_PAGE_SIZE)
            RequestKind.PAGE -> extra.put("beforeSessionId", cursor).put("limit", REMOTE_PAGE_SIZE)
            RequestKind.STATS -> extra.put("range", range)
        }
        _state.update { it.copy(status = if (range == null) "正在同步历史" else "正在同步 ${rangeLabel(range)} 统计") }
        if (!isCurrent(token)) return@synchronized
        val sent = runCatching { send(kind.command, extra) }.getOrDefault(false)
        if (!isCurrent(token)) return@synchronized
        if (!sent) {
            fail(request, "Sony 未连接或历史命令未发送")
            return@synchronized
        }
        // Absolute per-request deadline. Duplicate start/chunk traffic cannot extend it.
        timeout = scope.launch {
            delay(timeoutMs)
            enqueue {
                if (active === request && isCurrent(request.epoch)) {
                    timeout = null
                    fail(request, "历史请求超时，可重试")
                }
            }
        }
    }

    private fun startTransfer(request: Request, value: JSONObject) {
        if (value.optString("responseType") != request.kind.responseType) {
            fail(request, "历史响应类型不符")
            return
        }
        val size = value.optInt("size", -1)
        val chunks = value.optInt("chunks", -1)
        if (size !in 1..MAX_PAYLOAD_BYTES || chunks !in 1..MAX_PAYLOAD_CHUNKS) {
            fail(request, "历史分包参数无效")
            return
        }
        val old = transfer
        if (old != null) {
            if (old.size != size || old.count != chunks) fail(request, "历史分包参数冲突")
            return
        }
        transfer = Transfer(size, chunks)
    }

    private fun appendTransfer(request: Request, value: JSONObject) {
        val current = transfer ?: return fail(request, "历史分包缺少起始信息")
        val index = value.optInt("index", -1)
        if (index !in 0 until current.count) return fail(request, "历史分包序号无效")
        val encoded = value.optString("data")
        if (encoded.isEmpty() || encoded.length > MAX_PAYLOAD_BYTES * 2 ||
            encoded.length % 4 != 0 || !BASE64_PATTERN.matches(encoded)) {
            return fail(request, "历史分包编码无效")
        }
        val data = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return fail(request, "历史分包编码无效")
        val old = current.chunks[index]
        if (old != null && !old.contentEquals(data)) return fail(request, "历史分包内容冲突")
        if (old == null) {
            if (current.bytes + data.size > current.size) return fail(request, "历史分包长度超限")
            current.bytes += data.size
            current.chunks[index] = data
        }
    }

    private suspend fun finishTransfer(request: Request, value: JSONObject) {
        val current = transfer ?: return fail(request, "历史分包缺少起始信息")
        if (value.has("responseType") && value.optString("responseType") != request.kind.responseType) {
            return fail(request, "历史结束响应类型不符")
        }
        val data = ControllerProtocolCodec.reassemble(current.chunks, current.count)
        if (data == null || data.size != current.size) return fail(request, "历史数据分包不完整")
        val payload = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?: return fail(request, "历史数据解析失败")
        if (payload.optString("requestId") != request.id) return fail(request, "历史数据请求标识不符")
        applyPayload(request, payload)
    }

    private suspend fun applyPayload(request: Request, value: JSONObject) {
        if (value.optString("type") != request.kind.responseType) return fail(request, "历史响应类型不符")
        if (request.kind == RequestKind.STATS) {
            if (value.optString("range") != request.range) return fail(request, "历史统计范围不符")
            val stats = decodeStats(value) ?: return fail(request, "历史统计解析失败")
            resetRequest()
            publish(request.epoch) { it.copy(stats = it.stats + (stats.range to stats), failedRanges = it.failedRanges - stats.range) }
            requestNextStat(request.epoch)
            return
        }
        val array = value.optJSONArray("items") ?: return fail(request, "历史记录列表无效")
        val sessions = decodeHistorySessions(array)
        if (sessions.size != array.length() || sessions.size > REMOTE_PAGE_SIZE ||
            sessions.map { it.sessionId }.toSet().size != sessions.size || !value.has("hasMore")) {
            return fail(request, "历史记录内容无效")
        }
        val hasMore = value.getBoolean("hasMore")
        val cursor = dao.cursor() ?: HistoryCursorEntity()
        if (!isCurrent(request.epoch)) return
        val updated: HistoryCursorEntity
        val next: Long
        if (request.kind == RequestKind.SINCE) {
            next = value.getLong("lastSessionId")
            if (sessions.any { it.sessionId <= request.cursor } ||
                next != (sessions.maxOfOrNull { it.sessionId } ?: request.cursor) ||
                (hasMore && (sessions.isEmpty() || next <= request.cursor))) {
                return fail(request, "历史增量游标未推进")
            }
            sessions.filter { it.endedAt == null }.minOfOrNull { it.sessionId }?.let {
                earliestOpenId = minOf(earliestOpenId ?: it, it)
            }
            // Sony includes its current, mutable session. Revisit it on the next sync.
            val durableSince = minOf(next, earliestOpenId?.minus(1L) ?: next)
            updated = cursor.copy(sinceSessionId = durableSince)
        } else {
            val supplied = value.opt("nextBeforeSessionId")
            next = if (supplied == null || supplied == JSONObject.NULL) {
                sessions.minOfOrNull { it.sessionId } ?: request.cursor
            } else value.getLong("nextBeforeSessionId")
            if (sessions.any { it.sessionId >= request.cursor } ||
                (sessions.isNotEmpty() && (next != sessions.minOf { it.sessionId } || next <= 0L || next >= request.cursor)) ||
                (hasMore && (sessions.isEmpty() || next >= request.cursor))) {
                return fail(request, "更早历史游标未推进")
            }
            updated = cursor.copy(olderBeforeSessionId = next, olderHasMore = hasMore)
        }
        // Response validation completes the wire request. Persistence is serialized
        // with clear; UI completion is still fenced after every suspension.
        timeout?.cancel()
        timeout = null
        transfer = null
        dao.commitPage(sessions.map(PlaybackHistoryEntity::from), updated)
        if (!isCurrent(request.epoch)) return
        active = null
        if (request.kind == RequestKind.PAGE) {
            val previousIds = _state.value.sessions.map { it.sessionId }.toSet()
            val before = _state.value.sessions.lastOrNull()?.sessionId ?: Long.MAX_VALUE
            val local = dao.readBefore(before, LOCAL_PAGE_SIZE)
            if (!isCurrent(request.epoch)) return
            publishWindow(_state.value.sessions + local.map(PlaybackHistoryEntity::toModel), request.epoch)
            updateHasMore(request.epoch)
            if (!isCurrent(request.epoch)) return
            val addedVisibleRows = _state.value.sessions.any { it.sessionId !in previousIds }
            publish(request.epoch) {
                it.copy(loading = false, status = when {
                    !addedVisibleRows && hasMore -> "已检查缓存记录，可继续加载更早记录"
                    !addedVisibleRows -> "已显示全部可用历史"
                    hasMore -> "更早历史已加载"
                    else -> "历史已加载"
                })
            }
        } else {
            refreshWindow(request.epoch)
            if (!isCurrent(request.epoch)) return
            if (hasMore) requestSince(next, request.epoch) else requestNextStat(request.epoch)
        }
    }

    private fun fail(request: Request, reason: String): Unit = synchronized(entryGate) {
        if (active !== request || !isCurrent(request.epoch)) return@synchronized
        resetRequest()
        log("[History] failed type=${request.kind.responseType} range=${request.range} reason=$reason")
        if (request.kind == RequestKind.STATS) {
            _state.update { it.copy(failedRanges = it.failedRanges + requireNotNull(request.range)) }
            requestNextStat(request.epoch)
        } else if (request.kind == RequestKind.SINCE) {
            historyFailureReason = reason
            _state.update { it.copy(status = "$reason，已保留本地历史") }
            requestNextStat(request.epoch)
        } else {
            _state.update { it.copy(loading = false, status = "$reason，已保留本地历史，可重试") }
        }
    }

    private fun requestNextStat(token: Long): Unit = synchronized(entryGate) {
        if (!isCurrent(token)) return@synchronized
        val range = ranges.removeFirstOrNull()
        if (range != null) request(RequestKind.STATS, range = range, token = token) else {
            resetRequest()
            _state.update {
                it.copy(loading = false, status = when {
                    historyFailureReason != null -> "$historyFailureReason，已保留本地数据，可重试"
                    it.failedRanges.isNotEmpty() -> "历史已同步，部分统计失败，可重试"
                    else -> "同步完成"
                })
            }
        }
    }

    private fun resetRequest() {
        timeout?.cancel()
        timeout = null
        active = null
        transfer = null
    }

    private suspend fun refreshWindow(
        token: Long,
        count: Int = LOCAL_PAGE_SIZE,
        preserveAnchor: Boolean = true
    ) {
        if (!isCurrent(token)) return
        val previous = _state.value.sessions
        val preserve = preserveAnchor && previous.isNotEmpty()
        var rows = dao.readBefore(windowBefore, if (preserve) MAX_VISIBLE_SESSIONS else count)
        if (!isCurrent(token)) return
        if (preserve) {
            val bottom = previous.last().sessionId
            if (rows.size == MAX_VISIBLE_SESSIONS && rows.last().sessionId > bottom) {
                // A full window of new records must not evict the row being read.
                // Freeze the upper bound until the user explicitly returns to recent.
                synchronized(entryGate) {
                    if (isCurrent(token)) windowBefore = previous.first().sessionId + 1L
                }
                rows = dao.readBefore(windowBefore, MAX_VISIBLE_SESSIONS)
                if (!isCurrent(token)) return
            }
            rows = rows.takeWhile { it.sessionId >= bottom }
        }
        publish(token) { it.copy(sessions = rows.map(PlaybackHistoryEntity::toModel), showingOlderWindow = windowBefore != Long.MAX_VALUE) }
        updateHasMore(token)
    }

    private fun publishWindow(rows: List<PlaybackHistorySession>, token: Long) {
        val unique = rows.distinctBy { it.sessionId }.sortedByDescending { it.sessionId }
        val shown = unique.takeLast(MAX_VISIBLE_SESSIONS)
        synchronized(entryGate) {
            if (!isCurrent(token)) return@synchronized
            if (unique.size > MAX_VISIBLE_SESSIONS) windowBefore = shown.first().sessionId + 1L
            publish(token) { it.copy(sessions = shown, showingOlderWindow = windowBefore != Long.MAX_VALUE) }
        }
    }

    private suspend fun updateHasMore(token: Long) {
        if (!isCurrent(token)) return
        val bottom = _state.value.sessions.lastOrNull()?.sessionId ?: Long.MAX_VALUE
        val localMore = dao.readBefore(bottom, 1).isNotEmpty()
        val remoteMore = (dao.cursor() ?: HistoryCursorEntity()).olderHasMore
        publish(token) { it.copy(hasMore = localMore || remoteMore) }
    }

    private fun rangeLabel(range: String) = when (range) { "TODAY" -> "今日"; "WEEK" -> "本周"; else -> "本月" }

    private enum class RequestKind(val command: String, val responseType: String) {
        SINCE("GET_PLAY_HISTORY_SINCE", "playHistorySince"),
        PAGE("GET_PLAY_HISTORY_PAGE", "playHistoryPage"),
        STATS("GET_PLAY_STATS", "playStats")
    }
    private data class Request(val id: String, val kind: RequestKind, val cursor: Long, val range: String?, val epoch: Long)
    private data class Operation(val epoch: Long?, val action: suspend (Long) -> Unit)
    private class Transfer(val size: Int, val count: Int) {
        var bytes = 0
        val chunks = HashMap<Int, ByteArray>()
    }

    private fun decodeHistorySessions(array: JSONArray?): List<PlaybackHistorySession> =
        buildList {
            if (array == null) return@buildList
            repeat(array.length()) { index ->
                val value = array.optJSONObject(index) ?: return@repeat
                val sessionId = value.optLong("sessionId")
                if (sessionId <= 0L) return@repeat
                add(
                    PlaybackHistorySession(
                        sessionId = sessionId,
                        trackKey = value.optString("trackKey"),
                        title = value.optString("title"),
                        artist = value.optString("artist"),
                        album = value.optString("album"),
                        artworkId = value.opt("artworkId")
                            ?.takeUnless { it == JSONObject.NULL }
                            ?.toString()?.ifBlank { null },
                        startedAt = value.optLong("startedAt"),
                        endedAt = value.opt("endedAt")
                            ?.takeUnless { it == JSONObject.NULL }
                            ?.toString()
                            ?.toLongOrNull(),
                        listenedMs = value.optLong("listenedMs"),
                        durationMs = value.optLong("durationMs"),
                        completed = value.optBoolean("completed"),
                        skipped = value.optBoolean("skipped"),
                        countedPlay = value.optBoolean("countedPlay")
                    )
                )
            }
        }

    private fun decodeStats(value: JSONObject): PlaybackStats? {
        val range = value.optString("range")
        if (range.isBlank()) return null
        val totals = listOf("totalListenMs", "playCount", "uniqueTrackCount")
        if (totals.any {
            val number = value.opt(it) as? Number
            number == null || !number.toDouble().isFinite() || number.toDouble() < 0.0
        }) return null
        if (listOf("completionRate", "skipRate").any {
            val number = value.opt(it) as? Number
            number == null || !number.toDouble().isFinite() || number.toDouble() < 0.0
        }) return null
        if (listOf("topTracks", "topArtists", "dailyTrend").any { value.optJSONArray(it) == null }) return null
        return PlaybackStats(
            range = range,
            totalListenMs = value.optLong("totalListenMs"),
            playCount = value.optInt("playCount"),
            uniqueTrackCount = value.optInt("uniqueTrackCount"),
            completionRate = value.optDouble("completionRate"),
            skipRate = value.optDouble("skipRate"),
            topTracks = buildList {
                val array = value.optJSONArray("topTracks") ?: JSONArray()
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    add(
                        PlaybackTopTrack(
                            trackKey = item.optString("trackKey"),
                            title = item.optString("title"),
                            artist = item.optString("artist"),
                            listenedMs = item.optLong("listenedMs"),
                            playCount = item.optInt("playCount")
                        )
                    )
                }
            },
            topArtists = buildList {
                val array = value.optJSONArray("topArtists") ?: JSONArray()
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    add(
                        PlaybackTopArtist(
                            artist = item.optString("artist"),
                            listenedMs = item.optLong("listenedMs"),
                            playCount = item.optInt("playCount")
                        )
                    )
                }
            },
            dailyTrend = buildList {
                val array = value.optJSONArray("dailyTrend") ?: JSONArray()
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    add(
                        DailyListenStat(
                            dateKey = item.optString("dateKey"),
                            listenedMs = item.optLong("listenedMs"),
                            playCount = item.optInt("playCount")
                        )
                    )
                }
            }
        )
    }

    companion object {
        const val LOCAL_PAGE_SIZE = 500
        const val MAX_VISIBLE_SESSIONS = 1_000
        private const val REMOTE_PAGE_SIZE = 20
        private const val MAX_PAYLOAD_BYTES = 1_048_576
        private const val MAX_PAYLOAD_CHUNKS = 65_536
        private val BASE64_PATTERN = Regex("[A-Za-z0-9+/]*={0,2}")
        private val requestSequence = AtomicLong(System.currentTimeMillis())
    }
}
