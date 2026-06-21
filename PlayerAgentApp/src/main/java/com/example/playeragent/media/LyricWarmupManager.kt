package com.example.playeragent.media

import android.content.Context
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LyricWarmupManager(
    context: Context,
    private val logger: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "LyricWarmupThread").apply {
            priority = Thread.MIN_PRIORITY
        }
    }.apply {
        removeOnCancelPolicy = true
    }
    private val running = AtomicBoolean(false)
    private var scheduled: ScheduledFuture<*>? = null

    @Synchronized
    fun schedule(delayMs: Long = DEFAULT_DELAY_MS) {
        if (running.get() || scheduled?.isDone == false) {
            return
        }
        logger("[LyricWarmup] scheduled delayMs=$delayMs")
        scheduled = executor.schedule(
            ::runWarmupSafely,
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    fun cancel() {
        scheduled?.cancel(false)
        scheduled = null
        running.set(false)
    }

    fun warmupNow() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        executor.execute {
            try {
                runWarmup()
            } finally {
                running.set(false)
            }
        }
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }

    private fun runWarmupSafely() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        try {
            runWarmup()
        } finally {
            running.set(false)
        }
    }

    private fun runWarmup() {
        val totalStartedAt = System.currentTimeMillis()
        logger("[LyricWarmup] start")

        val aliasStartedAt = System.currentTimeMillis()
        val aliasItems = QrcAliasCacheManager(appContext, logger).warmup()
        logger(
            "[LyricWarmup] alias loaded items=$aliasItems " +
                "costMs=${System.currentTimeMillis() - aliasStartedAt}"
        )

        val negativeStartedAt = System.currentTimeMillis()
        val negativeItems = QrcNegativeCacheManager(appContext, logger).warmup()
        logger(
            "[LyricWarmup] negative loaded items=$negativeItems " +
                "costMs=${System.currentTimeMillis() - negativeStartedAt}"
        )

        val indexStartedAt = System.currentTimeMillis()
        val indexEntries = QrcPersistentIndexManager(appContext, logger).getIndex(
            forceRefresh = false
        )
        logger(
            "[LyricWarmup] qrcIndex loaded entries=${indexEntries.size} " +
                "costMs=${System.currentTimeMillis() - indexStartedAt}"
        )

        val fuzzyStartedAt = System.currentTimeMillis()
        val fuzzyStatus = QrcLyricCacheManager(appContext, logger).warmupFuzzyIndex()
        logger(
            "[LyricWarmup] fuzzyIndex loaded entries=${fuzzyStatus.entries} " +
                "files=${fuzzyStatus.files} " +
                "costMs=${System.currentTimeMillis() - fuzzyStartedAt}"
        )

        logger("[LyricWarmup] done totalCostMs=${System.currentTimeMillis() - totalStartedAt}")
    }

    companion object {
        private const val DEFAULT_DELAY_MS = 2_500L
    }
}
