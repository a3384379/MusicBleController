package com.example.playeragent.media

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

class QrcLyricPrebuildManager(
    context: Context,
    private val logger: (String) -> Unit,
    private val progressListener: (QrcPrebuildProgress) -> Unit
) {

    private val appContext = context.applicationContext
    private val cacheManager = QrcLyricCacheManager(
        context = appContext,
        logger = logger
    )
    private val running = AtomicBoolean(false)
    @Volatile
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            logger("[QrcPrebuild] already running")
            return
        }
        worker = Thread(::runBuild, "QrcLyricPrebuildThread").also {
            it.priority = Thread.MIN_PRIORITY
            it.start()
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            logger("[QrcPrebuild] stopped")
        }
    }

    fun isRunning(): Boolean = running.get()

    private fun runBuild() {
        var processed = 0
        var success = 0
        var failed = 0
        var skipped = 0
        val groups = QrcLyricUtils.scanGroups()
        logger("[QrcPrebuild] start total=${groups.size}")
        publish(
            total = groups.size,
            processed = processed,
            success = success,
            failed = failed,
            skipped = skipped
        )

        try {
            for (group in groups) {
                if (!running.get()) {
                    break
                }
                processed += 1
                try {
                    val validation = cacheManager.validateGroupCache(
                        group = group,
                        requireComplete = true
                    )
                    if (validation.valid) {
                        skipped += 1
                    } else {
                        if (validation.cached != null) {
                            logger(
                                "[QrcPrebuild] rebuild stale cache " +
                                    "groupId=${group.groupId} reason=${validation.reason}"
                            )
                        }
                        val parsed = QrcLyricUtils.decryptAndParseGroup(group)
                        if (parsed != null &&
                            parsed.title.isNotBlank() &&
                            parsed.artist.isNotBlank()
                        ) {
                            cacheManager.save(parsed)
                            success += 1
                        } else if (parsed != null) {
                            cacheManager.save(
                                parsed.copy(
                                    songKey = "group|${parsed.groupId}"
                                )
                            )
                            success += 1
                        } else {
                            failed += 1
                        }
                    }
                } catch (_: Exception) {
                    failed += 1
                }

                if (processed % PROGRESS_INTERVAL == 0) {
                    logger(
                        "[QrcPrebuild] progress processed=$processed " +
                            "success=$success failed=$failed skipped=$skipped"
                    )
                    publish(groups.size, processed, success, failed, skipped)
                    Thread.sleep(THROTTLE_SLEEP_MS)
                }
            }
        } finally {
            val stoppedByUser = !running.get() && processed < groups.size
            running.set(false)
            publish(groups.size, processed, success, failed, skipped)
            if (stoppedByUser) {
                logger("[QrcPrebuild] stopped")
            } else {
                logger(
                    "[QrcPrebuild] finished success=$success " +
                        "failed=$failed skipped=$skipped"
                )
            }
        }
    }

    private fun publish(
        total: Int,
        processed: Int,
        success: Int,
        failed: Int,
        skipped: Int
    ) {
        progressListener(
            QrcPrebuildProgress(
                running = running.get(),
                total = total,
                processed = processed,
                success = success,
                failed = failed,
                skipped = skipped
            )
        )
    }

    companion object {
        private const val PROGRESS_INTERVAL = 20
        private const val THROTTLE_SLEEP_MS = 200L
    }
}

data class QrcPrebuildProgress(
    val running: Boolean,
    val total: Int,
    val processed: Int,
    val success: Int,
    val failed: Int,
    val skipped: Int
) {
    fun displayText(): String {
        return "QRC Cache Build:\n" +
            (if (running) "running" else "stopped") +
            "\nprocessed: $processed / $total" +
            "\nsuccess: $success" +
            "\nfailed: $failed" +
            "\nskipped: $skipped"
    }
}
