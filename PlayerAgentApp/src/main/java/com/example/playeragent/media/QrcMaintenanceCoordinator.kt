package com.example.playeragent.media

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

enum class MaintenanceTaskType {
    LYRIC_WARMUP,
    QRC_INDEX_REBUILD,
    FUZZY_INDEX_REBUILD,
    QRC_INCREMENTAL_PREBUILD,
    CACHE_REPAIR,
    QRC_V2_REBUILD,
    ARTWORK_DISCOVERY,
    ARTWORK_ENHANCEMENT_DIAG
}

data class MaintenanceToken(
    val id: Long,
    val type: MaintenanceTaskType,
    val reason: String,
    val startedAt: Long,
    @Volatile var cancelled: Boolean = false
)

data class MaintenanceTaskSnapshot(
    val id: Long,
    val type: MaintenanceTaskType,
    val reason: String,
    val startedAt: Long,
    val runningForMs: Long,
    val cancelled: Boolean
) {
    fun displayText(): String {
        return "当前维护任务：${type.name}\n" +
            "原因：$reason\n" +
            "运行：${runningForMs / 1000}s\n" +
            "取消请求：${if (cancelled) "是" else "否"}"
    }
}

data class MaintenanceSnapshot(
    val running: Boolean,
    val current: MaintenanceTaskSnapshot?
) {
    fun displayText(): String {
        return current?.displayText() ?: "当前维护任务：无"
    }
}

object QrcMaintenanceCoordinator {
    private val nextId = AtomicLong(1L)
    private val current = AtomicReference<MaintenanceToken?>(null)
    private val finishListeners = CopyOnWriteArrayList<(MaintenanceToken) -> Unit>()

    fun tryStart(
        type: MaintenanceTaskType,
        reason: String,
        logger: (String) -> Unit
    ): MaintenanceToken? {
        val token = MaintenanceToken(
            id = nextId.getAndIncrement(),
            type = type,
            reason = reason,
            startedAt = System.currentTimeMillis()
        )
        if (!current.compareAndSet(null, token)) {
            val active = current.get()
            logger(
                "[QrcMaintenance] reject type=$type reason=busy " +
                    "current=${active?.type} currentReason=${active?.reason.orEmpty()}"
            )
            return null
        }
        logger("[QrcMaintenance] start type=$type reason=$reason")
        return token
    }

    fun finish(token: MaintenanceToken, logger: (String) -> Unit) {
        if (current.compareAndSet(token, null)) {
            logger(
                "[QrcMaintenance] finish type=${token.type} " +
                    "costMs=${System.currentTimeMillis() - token.startedAt}"
            )
            finishListeners.forEach { listener ->
                runCatching { listener(token) }
            }
        }
    }

    fun finishCurrentIf(
        type: MaintenanceTaskType,
        reason: String,
        logger: (String) -> Unit
    ): Boolean {
        val token = current.get() ?: return false
        if (token.type != type) {
            return false
        }
        if (!current.compareAndSet(token, null)) {
            return false
        }
        logger(
            "[QrcMaintenance] force finish type=${token.type} reason=$reason " +
                "costMs=${System.currentTimeMillis() - token.startedAt} " +
                "cancelled=${token.cancelled}"
        )
        finishListeners.forEach { listener ->
            runCatching { listener(token) }
        }
        return true
    }

    fun fail(token: MaintenanceToken, error: Throwable, logger: (String) -> Unit) {
        logger("[QrcMaintenance] failed type=${token.type} error=${error.message}")
        finish(token, logger)
    }

    fun cancelCurrent(reason: String, logger: (String) -> Unit): Boolean {
        val token = current.get() ?: return false
        token.cancelled = true
        logger("[QrcMaintenance] cancel requested reason=$reason type=${token.type}")
        return true
    }

    fun isRunning(): Boolean = current.get() != null

    fun currentToken(): MaintenanceToken? = current.get()

    fun addFinishListener(listener: (MaintenanceToken) -> Unit) {
        finishListeners += listener
    }

    fun snapshot(): MaintenanceSnapshot {
        val token = current.get()
        val now = System.currentTimeMillis()
        return MaintenanceSnapshot(
            running = token != null,
            current = token?.let {
                MaintenanceTaskSnapshot(
                    id = it.id,
                    type = it.type,
                    reason = it.reason,
                    startedAt = it.startedAt,
                    runningForMs = now - it.startedAt,
                    cancelled = it.cancelled
                )
            }
        )
    }
}
