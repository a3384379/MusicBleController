package com.example.playeragent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.playeragent.ble.ControllerScannerManager
import com.example.playeragent.ble.BleHealthState
import com.example.playeragent.classicbluetooth.RfcommClientManager
import com.example.playeragent.history.HistorySessionRow
import com.example.playeragent.history.PlaybackHistoryRepository
import com.example.playeragent.history.StatsRange
import com.example.playeragent.logging.LogBuffer
import com.example.playeragent.media.AlbumArtTestManager
import com.example.playeragent.media.CurrentLyricProbe
import com.example.playeragent.media.LrcDebugManager
import com.example.playeragent.media.LyricManager
import com.example.playeragent.media.LyricSourceTestManager
import com.example.playeragent.media.LyricWarmupManager
import com.example.playeragent.media.MaintenanceTaskType
import com.example.playeragent.media.PlaybackStateReader
import com.example.playeragent.media.QQMusicArtworkDiscoveryManager
import com.example.playeragent.media.ArtworkDiscoveryStatus
import com.example.playeragent.media.QrcAliasCacheManager
import com.example.playeragent.media.QrcDumpManager
import com.example.playeragent.media.QrcLyricCacheManager
import com.example.playeragent.media.QrcLyricV2RebuildManager
import com.example.playeragent.media.QrcMaintenanceCoordinator
import com.example.playeragent.media.QrcNegativeCacheManager
import com.example.playeragent.media.QrcPersistentIndexManager
import com.example.playeragent.media.QrcStaleCacheRebuildManager
import com.example.playeragent.media.QrcStaleCacheRebuildProgress
import com.example.playeragent.media.QrcV2RebuildProgress
import com.example.playeragent.media.QrcV2RebuildStatus
import com.example.playeragent.media.QrcWatcherStatus
import com.example.playeragent.service.PlayerAgentForegroundService
import com.example.playeragent.service.QQMusicLyricAccessibilityService
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusTextView: TextView
    private lateinit var bleStatusTextView: TextView
    private lateinit var accessibilityStatusTextView: TextView
    private lateinit var currentSongTextView: TextView
    private lateinit var currentLyricTextView: TextView
    private lateinit var currentAlbumArtImageView: ImageView
    private lateinit var currentAlbumArtTextView: TextView
    private lateinit var artworkDiscoveryStatusTextView: TextView
    private lateinit var qrcCacheBuildTextView: TextView
    private lateinit var qrcStaleCacheRebuildTextView: TextView
    private lateinit var qrcWatcherStatusTextView: TextView
    private lateinit var lyricCacheStatsTextView: TextView
    private lateinit var qrcCacheOverviewTextView: TextView
    private lateinit var maintenanceStatusTextView: TextView
    private lateinit var historyStatusTextView: TextView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var logSectionBody: LinearLayout
    private lateinit var buildTaskStatusTextView: TextView
    private lateinit var buildTaskBadgeTextView: TextView
    private lateinit var buildTaskPrimaryButton: Button
    private lateinit var buildTaskStopButton: Button
    private lateinit var qrcIndexStatusTextView: TextView
    private lateinit var qrcIndexBadgeTextView: TextView
    private lateinit var qrcIndexPrimaryButton: Button
    private lateinit var lyricsWarmupStatusTextView: TextView
    private lateinit var lyricsWarmupBadgeTextView: TextView
    private lateinit var lyricsWarmupPrimaryButton: Button
    private lateinit var cacheMaintenanceStatusTextView: TextView
    private lateinit var cacheMaintenanceBadgeTextView: TextView
    private lateinit var cacheMaintenancePrimaryButton: Button
    private lateinit var currentTrackDebugStatusTextView: TextView
    private lateinit var currentTrackBadgeTextView: TextView
    private lateinit var maintenanceSummaryTextView: TextView
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var controllerScannerManager: ControllerScannerManager? = null
    private var rfcommClientManager: RfcommClientManager? = null
    private var userStoppedControlServiceThisRun = false
    private var autoStartControlServiceRequestedThisRun = false
    private var autoStartControlServiceRequestedAtMs = 0L
    private var artworkDiscoveryManager: QQMusicArtworkDiscoveryManager? = null
    private var qrcV2RebuildManager: QrcLyricV2RebuildManager? = null
    private var qrcStaleCacheRebuildManager: QrcStaleCacheRebuildManager? = null
    private var lyricWarmupManager: LyricWarmupManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var debugStatsLoading = false
    @Volatile private var lastDebugStatsLoadAt = 0L
    private var debugStatsLoadRunnable: Runnable? = null
    private var lastPlayerUiSongKey: String = ""
    private var lastPlayerUiLyric: String = ""
    private var lastPlayerUiLyricStatus: String = ""
    private var lastPlayerUiAlbumArtStatus: String = "未检测"
    private var albumArtRefreshGeneration = 0L
    private var controlServiceStatusRefreshRunnable: Runnable? = null
    private var lastQrcV2BuildProgress = QrcV2RebuildProgress()
    private var lastQrcStaleCacheRebuildProgress = QrcStaleCacheRebuildProgress()
    private var lastMaintenanceUiState = MaintenanceUiState()
    private var lastLyricCacheStatsText = "歌词统计：loading..."
    private var lastQrcWatcherStatus = QrcWatcherStatus(
        watcherRunning = false,
        pendingGroups = 0,
        incrementalRunning = false,
        incrementalSuccess = 0,
        incrementalFailed = 0,
        incrementalSkipped = 0
    )

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlayerAgentForegroundService.ACTION_PLAYER_UI_STATE) {
                updatePlayerUiState(intent)
                return
            }
            if (intent?.action == PlayerAgentForegroundService.ACTION_QRC_WATCHER_STATUS) {
                updateQrcWatcherStatus(
                    QrcWatcherStatus(
                        watcherRunning = intent.getBooleanExtra(
                            PlayerAgentForegroundService.EXTRA_QRC_WATCHER_RUNNING,
                            false
                        ),
                        pendingGroups = intent.getIntExtra(
                            PlayerAgentForegroundService.EXTRA_QRC_WATCHER_PENDING,
                            0
                        ),
                        incrementalRunning = intent.getBooleanExtra(
                            PlayerAgentForegroundService.EXTRA_QRC_INCREMENTAL_RUNNING,
                            false
                        ),
                        incrementalSuccess = intent.getIntExtra(
                            PlayerAgentForegroundService.EXTRA_QRC_INCREMENTAL_SUCCESS,
                            0
                        ),
                        incrementalFailed = intent.getIntExtra(
                            PlayerAgentForegroundService.EXTRA_QRC_INCREMENTAL_FAILED,
                            0
                        ),
                        incrementalSkipped = intent.getIntExtra(
                            PlayerAgentForegroundService.EXTRA_QRC_INCREMENTAL_SKIPPED,
                            0
                        )
                    )
                )
                return
            }
            val message = intent
                ?.getStringExtra(PlayerAgentForegroundService.EXTRA_LOG_MESSAGE)
                ?: return
            appendLog(message, storeInBuffer = false)
            when {
                message.contains("Foreground service started") ->
                    setControlServiceStatus("运行中")

                message.contains("Foreground service stopped") ->
                    setControlServiceStatus("已停止")

                message.startsWith("[AccessibilityLyric] candidate=") ->
                    appendLog("[PlayerUI] 无障碍候选歌词已记录，当前歌词仍以播放状态为准")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startedAt = System.currentTimeMillis()
        super.onCreate(savedInstanceState)
        StartupGuard.markAppOnCreate(::appendLog)
        appendLog("[UI] MainActivity onCreate start")
        setupUi()
        window.decorView.post {
            StartupGuard.markFirstFrameDrawn(::appendLog)
        }
        appendLog("[UI] setContent complete costMs=${System.currentTimeMillis() - startedAt}")
        initializeBluetooth()
        requestRequiredPermissions()
        maybeAutoStartControlService("onCreate")
        refreshControlServiceStatus()
        appendLog("[PlayerAgent] UI ready")
        appendLog("[UI] first lightweight render ready costMs=${System.currentTimeMillis() - startedAt}")
        scheduleDebugStatsLoad()
        refreshCurrentMedia()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(PlayerAgentForegroundService.ACTION_LOG)
            addAction(PlayerAgentForegroundService.ACTION_PLAYER_UI_STATE)
            addAction(PlayerAgentForegroundService.ACTION_QRC_WATCHER_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        refreshControlServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        maybeAutoStartControlService("onResume")
        refreshControlServiceStatus()
        startControlServiceStatusRefresh()
        if (::currentSongTextView.isInitialized) {
            refreshCurrentMedia()
            refreshAccessibilityStatus()
        }
    }

    override fun onStop() {
        super.onStop()
        stopControlServiceStatusRefresh()
        unregisterReceiver(logReceiver)
    }

    override fun onDestroy() {
        qrcV2RebuildManager?.stop()
        qrcStaleCacheRebuildManager?.stop()
        artworkDiscoveryManager?.shutdown()
        controllerScannerManager?.stopScan()
        rfcommClientManager?.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_LOG || resultCode != RESULT_OK) {
            return
        }

        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(LogBuffer.getAllLogs().joinToString("\n"))
            }
            appendLog("[PlayerAgent] Log exported")
        } catch (exception: Exception) {
            appendLog("[PlayerAgent] Log export failed: ${exception.message}")
        }
    }

    private fun setupUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 20))
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        content.addView(TextView(this).apply {
            text = "PlayerAgent"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(2))
        })
        content.addView(TextView(this).apply {
            text = "索尼音乐播放器控制服务"
            textSize = 14f
            setTextColor(Color.rgb(165, 170, 178))
            setPadding(0, 0, 0, dp(14))
        })

        val serviceCard = collapsibleCard(content, "服务状态", expanded = true)
        statusTextView = statusValue("Service：未启动")
        serviceCard.addView(statusTextView)
        bleStatusTextView = statusValue("BLE：未启动")
        serviceCard.addView(bleStatusTextView)
        accessibilityStatusTextView = statusValue("通知读取权限：未知")
        serviceCard.addView(accessibilityStatusTextView)
        serviceCard.addView(buttonGrid(listOf(
            "启动控制服务" to ::startPlayerAgentService,
            "停止控制服务" to ::stopPlayerAgentService,
            "恢复蓝牙服务" to ::recoverBleStack,
            "打开通知读取权限" to ::openNotificationAccessSettings,
            "打开无障碍设置" to ::openAccessibilitySettings
        )))

        val playerCard = collapsibleCard(content, "当前播放", expanded = true)
        currentAlbumArtImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(Color.rgb(42, 44, 50), dp(16))
            setImageResource(android.R.drawable.ic_media_play)
        }
        playerCard.addView(
            currentAlbumArtImageView,
            LinearLayout.LayoutParams(dp(150), dp(150)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            }
        )
        currentAlbumArtTextView = statusValue("封面：未检测").also(playerCard::addView)
        currentSongTextView = statusValue("歌曲：未检测").also(playerCard::addView)
        currentLyricTextView = statusValue("当前歌词：暂无歌词").also(playerCard::addView)

        val historyCard = collapsibleCard(content, "播放历史与收听统计", expanded = false)
        historyStatusTextView = statusValue("播放历史：未刷新").also(historyCard::addView)
        historyCard.addView(buttonGrid(listOf(
            "刷新历史状态" to ::refreshHistoryStatus,
            "显示最近 10 条" to ::showRecentHistory,
            "显示今日统计" to ::showTodayHistoryStats,
            "清空播放历史" to { confirmAction("清空 Sony 本地播放历史？", ::clearPlayHistory) }
        )))

        val lyricCard = collapsibleCard(content, "歌词缓存与逐字时间", expanded = false)
        setupMaintenancePanel(lyricCard)

        val connectionCard = collapsibleCard(content, "连接与兼容", expanded = false)
        connectionCard.addView(buttonGrid(listOf(
            "扫描控制端" to ::startControllerScan,
            "连接经典蓝牙控制端" to ::connectRfcommServer,
            "恢复蓝牙服务" to ::recoverBleStack,
            "启动 QRC 监听器" to ::startQrcWatcher,
            "停止 QRC 监听器" to ::stopQrcWatcher
        )))

        val mediaCard = collapsibleCard(content, "媒体诊断", expanded = false)
        artworkDiscoveryStatusTextView = statusValue(
            ArtworkDiscoveryStatus().displayText()
        ).also(mediaCard::addView)
        mediaCard.addView(buttonGrid(listOf(
            "扫描歌词文件" to ::scanLrcFiles,
            "测试当前封面" to ::testAlbumArt,
            "发现高清封面" to ::discoverHqArtwork,
            "停止封面发现" to ::stopArtworkDiscovery,
            "检测歌词来源" to ::testLyricSource,
            "测试当前歌词" to ::testCurrentLyric,
            "歌词解析诊断" to ::testLrcDebug,
            "解析首个 QRC 文件" to ::dumpFirstQrc,
            "无障碍节点诊断" to ::dumpAccessibilityTree,
            "刷新歌词统计" to ::refreshLyricStats,
            "重置歌词统计" to ::resetLyricStats
        )))

        logSectionBody = collapsibleCard(content, "日志", expanded = false)
        logSectionBody.addView(buttonGrid(listOf(
            "显示/隐藏日志" to ::toggleLogs,
            "导出日志" to ::exportLog,
            "清空日志" to ::clearLog
        )))
        logTextView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(215, 218, 224))
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        logScrollView = ScrollView(this).apply {
            visibility = View.GONE
            background = roundedBackground(Color.rgb(24, 25, 29), dp(12))
            addView(logTextView)
        }
        logSectionBody.addView(
            logScrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(260)
            )
        )

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun setupMaintenancePanel(parent: LinearLayout) {
        val buildStateCard = stateCard(parent, "构建任务", "未开始")
        val buildCard = buildStateCard.body
        buildTaskBadgeTextView = buildStateCard.badge
        buildTaskStatusTextView = statusValue("")
        buildCard.addView(buildTaskStatusTextView)
        buildTaskPrimaryButton = fullWidthActionButton("开始构建") {
            handleBuildTaskPrimaryAction()
        }.also(buildCard::addView)
        buildCard.addView(buttonGrid(listOf(
            "停止任务" to {
                confirmAction(
                    "停止当前构建/维护任务？\n\n正在执行的任务会收到取消请求，未完成的临时结果会保留。"
                ) {
                    cancelCurrentMaintenance()
                }
            },
            "清空并重新构建" to {
                confirmAction(
                    "清空临时结果并重新构建？\n\n会删除当前临时构建结果，然后重新开始 V2 逐字歌词缓存构建。"
                ) {
                    startQrcV2Build(clear = true)
                }
            }
        )))
        buildTaskStopButton = buildCard.getChildAt(buildCard.childCount - 1)
            .let { it as GridLayout }
            .getChildAt(0) as Button

        val indexStateCard = stateCard(parent, "QRC 索引", "空闲")
        val indexCard = indexStateCard.body
        qrcIndexBadgeTextView = indexStateCard.badge
        qrcIndexStatusTextView = statusValue("索引统计：loading...")
        qrcCacheOverviewTextView = qrcIndexStatusTextView
        indexCard.addView(qrcIndexStatusTextView)
        qrcIndexPrimaryButton = fullWidthActionButton("构建索引") {
            handleQrcIndexPrimaryAction()
        }.also(indexCard::addView)
        indexCard.addView(buttonGrid(listOf(
            "重建模糊索引" to ::rebuildFuzzyLyricIndex
        )))

        val warmupStateCard = stateCard(parent, "歌词预热", "空闲")
        val warmupCard = warmupStateCard.body
        lyricsWarmupBadgeTextView = warmupStateCard.badge
        lyricsWarmupStatusTextView = statusValue("预热统计：loading...")
        lyricCacheStatsTextView = lyricsWarmupStatusTextView
        warmupCard.addView(lyricsWarmupStatusTextView)
        lyricsWarmupPrimaryButton = fullWidthActionButton("预热歌词缓存") {
            handleLyricsWarmupPrimaryAction()
        }.also(warmupCard::addView)

        val repairStateCard = stateCard(parent, "缓存维护", "空闲")
        val repairCard = repairStateCard.body
        cacheMaintenanceBadgeTextView = repairStateCard.badge
        cacheMaintenanceStatusTextView = statusValue("")
        qrcStaleCacheRebuildTextView = cacheMaintenanceStatusTextView
        repairCard.addView(cacheMaintenanceStatusTextView)
        cacheMaintenancePrimaryButton = fullWidthActionButton("修复旧缓存") {
            handleCacheMaintenancePrimaryAction()
        }.also(repairCard::addView)
        repairCard.addView(buttonGrid(listOf(
            "删除旧缓存备份" to {
                confirmAction(
                    "删除旧缓存备份？\n\n只删除旧缓存备份目录，不影响当前活动缓存。"
                ) {
                    deleteOldQrcBackup()
                }
            },
            "清空临时构建" to {
                confirmAction(
                    "清空临时构建结果？\n\n只清理 V2 构建中的临时结果。"
                ) {
                    clearQrcV2Building()
                }
            }
        )))

        val currentTrackStateCard = stateCard(parent, "当前歌曲", "实时")
        val currentTrackCard = currentTrackStateCard.body
        currentTrackBadgeTextView = currentTrackStateCard.badge
        currentTrackDebugStatusTextView = statusValue("当前歌曲：未检测")
        currentTrackCard.addView(currentTrackDebugStatusTextView)
        currentTrackCard.addView(fullWidthActionButton("刷新当前歌词", ::refreshCurrentLyric))
        currentTrackCard.addView(buttonGrid(listOf(
            "刷新缓存统计" to ::refreshLyricStatsText
        )))

        maintenanceSummaryTextView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(190, 196, 205))
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        parent.addView(maintenanceSummaryTextView)

        maintenanceStatusTextView = buildTaskStatusTextView
        qrcCacheBuildTextView = buildTaskStatusTextView
        qrcWatcherStatusTextView = maintenanceSummaryTextView
        refreshMaintenanceUiState()
    }

    private fun stateCard(
        parent: LinearLayout,
        title: String,
        badge: String
    ): StateCardViews {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.rgb(24, 25, 29), dp(14))
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val badgeView = TextView(this).apply {
            text = "状态：$badge"
            textSize = 12f
            setTextColor(Color.rgb(196, 202, 212))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBackground(Color.rgb(44, 47, 55), dp(10))
        }
        header.addView(badgeView)
        card.addView(header)
        parent.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )
        return StateCardViews(card, badgeView)
    }

    private fun fullWidthActionButton(label: String, action: () -> Unit): Button {
        return actionButton(label, action).apply {
            minHeight = dp(44)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
    }

    private fun refreshMaintenanceUiState() {
        if (!::buildTaskStatusTextView.isInitialized) {
            return
        }
        val state = buildMaintenanceUiState()
        lastMaintenanceUiState = state

        val build = state.buildProgress
        val buildPercent = if (build.total > 0) build.processed * 100 / build.total else 0
        val activeBuildTask = state.activeTask == MaintenanceTaskType.QRC_V2_REBUILD
        val canStopBuild = activeBuildTask || build.running ||
            build.status == QrcV2RebuildStatus.PAUSED ||
            build.status == QrcV2RebuildStatus.VALIDATING
        val activeTaskName = state.activeTask?.let(::maintenanceTaskDisplayName)
        val buildStatusLabel = when {
            activeBuildTask || build.running || build.status == QrcV2RebuildStatus.PAUSED ->
                state.taskStatus.displayName
            else -> "空闲"
        }
        val buildHint = when {
            state.cancelRequested && activeBuildTask -> "提示：停止请求已发送，正在等待 V2 构建退出"
            activeBuildTask || build.running -> "提示：可停止当前构建任务"
            state.activeTask != null ->
                "提示：后台正在执行 $activeTaskName，V2 构建暂不可用"
            else -> "提示：当前没有运行中的 V2 构建"
        }
        buildTaskBadgeTextView.setTextIfChanged("状态：$buildStatusLabel")
        buildTaskStatusTextView.setTextIfChanged(
            "当前构建：${if (activeBuildTask || build.running) "V2_BUILD" else "无"}\n" +
            "构建状态：$buildStatusLabel\n" +
            "全局维护：${activeTaskName ?: "无"}\n" +
            "进度：${build.processed} / ${build.total} ($buildPercent%)\n" +
            "成功：${build.successWithWords + build.successLineOnly} 失败：${build.failed}\n" +
            "跳过无 QRC：${build.skippedNoQrc} 重复：${build.duplicate}\n" +
            "已运行：${state.runningSeconds}s\n" +
            buildHint
        )

        val buildPrimary = buildPrimaryButtonText(state)
        setActionButtonState(
            buildTaskPrimaryButton,
            buildPrimary,
            canRunTask(MaintenanceTaskType.QRC_V2_REBUILD, state)
        )
        setActionButtonState(
            buildTaskStopButton,
            if (state.cancelRequested && activeBuildTask) "停止中..." else "停止构建",
            canStopBuild
        )

        val indexBusy = state.activeTask == MaintenanceTaskType.QRC_INDEX_REBUILD
        val incrementalBusy = state.activeTask == MaintenanceTaskType.QRC_INCREMENTAL_PREBUILD ||
            state.watcherStatus.incrementalRunning
        val indexStatusLabel = when {
            indexBusy -> "构建中"
            incrementalBusy -> "增量预构建"
            state.watcherStatus.watcherRunning -> "监听中"
            else -> "空闲"
        }
        qrcIndexBadgeTextView.setTextIfChanged("状态：$indexStatusLabel")
        setActionButtonState(
            qrcIndexPrimaryButton,
            when {
                indexBusy -> "停止索引任务"
                incrementalBusy -> "停止增量预构建"
                else -> "重建索引"
            },
            canRunTask(MaintenanceTaskType.QRC_INDEX_REBUILD, state) ||
                indexBusy || incrementalBusy
        )

        val warmupBusy = state.activeTask == MaintenanceTaskType.LYRIC_WARMUP
        val warmupStatus = when {
            warmupBusy -> "预热中"
            state.activeTask != null -> "等待"
            else -> "空闲"
        }
        lyricsWarmupBadgeTextView.setTextIfChanged("状态：$warmupStatus")
        lyricsWarmupStatusTextView.setTextIfChanged(
            "当前预热：$warmupStatus\n" +
            "全局维护：${activeTaskName ?: "无"}\n" +
            "已运行：${if (warmupBusy) state.runningSeconds else 0}s\n" +
            "提示：${when {
                warmupBusy -> "正在加载 Alias / Negative / QRC Index / Fuzzy Index"
                state.activeTask != null -> "其它维护任务运行中，预热暂不可用"
                else -> "未启动预热"
            }}\n" +
            lastLyricCacheStatsText
        )
        setActionButtonState(
            lyricsWarmupPrimaryButton,
            if (warmupBusy) "取消预热" else "预热歌词缓存",
            canRunTask(MaintenanceTaskType.LYRIC_WARMUP, state) || warmupBusy
        )

        val repair = state.oldCacheRepairStatus
        val repairPercent = if (repair.total > 0) repair.processed * 100 / repair.total else 0
        cacheMaintenanceBadgeTextView.setTextIfChanged(
            "状态：${if (repair.running) "修复中" else repair.status}"
        )
        cacheMaintenanceStatusTextView.setTextIfChanged(
            "旧歌词缓存修复：${repair.status}\n" +
            "进度：${repair.processed} / ${repair.total} ($repairPercent%)\n" +
            "重建：${repair.rebuilt} 跳过：${repair.skipped} 失败：${repair.failed}\n" +
            "当前：${repair.currentGroupId.ifBlank { "-" }}"
        )
        val repairBusy = repair.running || state.activeTask == MaintenanceTaskType.CACHE_REPAIR
        setActionButtonState(
            cacheMaintenancePrimaryButton,
            if (repairBusy) "停止修复" else "修复旧缓存",
            canRunTask(MaintenanceTaskType.CACHE_REPAIR, state) || repairBusy
        )

        currentTrackBadgeTextView.setTextIfChanged("状态：${state.currentLyricsStatus ?: "unknown"}")
        currentTrackDebugStatusTextView.setTextIfChanged(
            "当前歌曲：${state.currentTrackTitle ?: "未检测"}\n" +
            "歌词状态：${state.currentLyricsStatus ?: "unknown"}\n" +
            "封面状态：${state.currentAlbumArtStatus ?: "unknown"}"
        )

        maintenanceSummaryTextView.setTextIfChanged(
            "增量成功：${state.watcherStatus.incrementalSuccess}    " +
            "增量失败：${state.watcherStatus.incrementalFailed}\n" +
            "Watcher pending：${state.watcherStatus.pendingGroups}    " +
            "索引 builtAt：见 QRC 索引卡片"
        )
    }

    private fun buildMaintenanceUiState(): MaintenanceUiState {
        cleanupStaleMaintenanceToken()
        val maintenance = QrcMaintenanceCoordinator.snapshot()
        val current = maintenance.current
        val build = lastQrcV2BuildProgress
        val repair = qrcStaleCacheRebuildManager?.status()
            ?: lastQrcStaleCacheRebuildProgress
        val status = when {
            current?.cancelled == true -> MaintenanceTaskStatus.STOPPING
            build.running || current?.type == MaintenanceTaskType.QRC_V2_REBUILD ->
                MaintenanceTaskStatus.RUNNING
            build.status == QrcV2RebuildStatus.PAUSED -> MaintenanceTaskStatus.PAUSED
            build.status == QrcV2RebuildStatus.COMPLETED -> MaintenanceTaskStatus.COMPLETED
            build.status == QrcV2RebuildStatus.FAILED -> MaintenanceTaskStatus.FAILED
            else -> MaintenanceTaskStatus.IDLE
        }
        return MaintenanceUiState(
            activeTask = current?.type,
            taskStatus = status,
            reason = current?.reason,
            runningSeconds = current?.runningForMs?.div(1000L) ?: 0L,
            cancelRequested = current?.cancelled == true,
            buildProgress = build,
            oldCacheRepairStatus = repair,
            watcherStatus = lastQrcWatcherStatus,
            currentTrackTitle = lastPlayerUiSongKey
                .takeIf { it.isNotBlank() }
                ?.split("|")
                ?.firstOrNull()
                ?.takeIf { it != "-" },
            currentLyricsStatus = lastPlayerUiLyricStatus.ifBlank { "unknown" },
            currentAlbumArtStatus = lastPlayerUiAlbumArtStatus
        )
    }

    private fun cleanupStaleMaintenanceToken() {
        val current = QrcMaintenanceCoordinator.currentToken() ?: return
        if (current.type == MaintenanceTaskType.QRC_INCREMENTAL_PREBUILD &&
            !lastQrcWatcherStatus.incrementalRunning &&
            lastQrcWatcherStatus.pendingGroups == 0
        ) {
            QrcMaintenanceCoordinator.finishCurrentIf(
                MaintenanceTaskType.QRC_INCREMENTAL_PREBUILD,
                "ui detected incremental idle",
                ::appendThreadSafeLog
            )
        }
        if (current.type == MaintenanceTaskType.LYRIC_WARMUP &&
            lyricWarmupManager?.isRunningOrScheduled() != true
        ) {
            QrcMaintenanceCoordinator.finishCurrentIf(
                MaintenanceTaskType.LYRIC_WARMUP,
                "ui detected warmup idle",
                ::appendThreadSafeLog
            )
        }
    }

    private fun buildPrimaryButtonText(state: MaintenanceUiState): String {
        if (state.cancelRequested) {
            return "停止中..."
        }
        return when (state.buildProgress.status) {
            QrcV2RebuildStatus.NOT_STARTED -> "开始构建"
            QrcV2RebuildStatus.RUNNING,
            QrcV2RebuildStatus.VALIDATING -> "暂停构建"
            QrcV2RebuildStatus.PAUSED,
            QrcV2RebuildStatus.STOPPED -> "继续构建"
            QrcV2RebuildStatus.COMPLETED,
            QrcV2RebuildStatus.FAILED -> "重新构建"
        }
    }

    private fun handleBuildTaskPrimaryAction() {
        val state = lastMaintenanceUiState
        if (state.cancelRequested) {
            appendLog("[MaintenanceUI] action skipped reason=stopping")
            return
        }
        when (state.buildProgress.status) {
            QrcV2RebuildStatus.NOT_STARTED -> startQrcV2Build(clear = false)
            QrcV2RebuildStatus.RUNNING,
            QrcV2RebuildStatus.VALIDATING -> pauseQrcV2Build()
            QrcV2RebuildStatus.PAUSED,
            QrcV2RebuildStatus.STOPPED -> resumeQrcV2Build()
            QrcV2RebuildStatus.COMPLETED,
            QrcV2RebuildStatus.FAILED -> startQrcV2Build(clear = false)
        }
    }

    private fun handleQrcIndexPrimaryAction() {
        if (lastMaintenanceUiState.activeTask == MaintenanceTaskType.QRC_INDEX_REBUILD) {
            confirmAction(
                "停止 QRC 索引任务？\n\n当前索引重建会收到取消请求。"
            ) {
                cancelCurrentMaintenance()
            }
        } else if (
            lastMaintenanceUiState.activeTask == MaintenanceTaskType.QRC_INCREMENTAL_PREBUILD ||
            lastMaintenanceUiState.watcherStatus.incrementalRunning
        ) {
            confirmAction(
                "停止增量预构建？\n\n会停止 QRC 目录监听和当前增量缓存任务。"
            ) {
                stopQrcWatcher()
            }
        } else {
            forceRefreshQrcIndex()
        }
    }

    private fun handleLyricsWarmupPrimaryAction() {
        if (lastMaintenanceUiState.activeTask == MaintenanceTaskType.LYRIC_WARMUP) {
            cancelCurrentMaintenance()
        } else {
            warmupLyricCache()
        }
    }

    private fun handleCacheMaintenancePrimaryAction() {
        val repairing = lastMaintenanceUiState.oldCacheRepairStatus.running ||
            lastMaintenanceUiState.activeTask == MaintenanceTaskType.CACHE_REPAIR
        if (repairing) {
            confirmAction(
                "停止旧缓存修复？\n\n已完成的修复会保留，未处理项稍后可重新修复。"
            ) {
                stopQrcStaleCacheRebuild()
            }
        } else {
            startQrcStaleCacheRebuild()
        }
    }

    private fun canRunTask(
        task: MaintenanceTaskType,
        state: MaintenanceUiState
    ): Boolean {
        val active = state.activeTask ?: return true
        return active == task
    }

    private fun maintenanceTaskDisplayName(task: MaintenanceTaskType): String {
        return when (task) {
            MaintenanceTaskType.LYRIC_WARMUP -> "歌词预热"
            MaintenanceTaskType.QRC_INDEX_REBUILD -> "QRC 索引"
            MaintenanceTaskType.FUZZY_INDEX_REBUILD -> "模糊索引"
            MaintenanceTaskType.QRC_INCREMENTAL_PREBUILD -> "增量预构建"
            MaintenanceTaskType.CACHE_REPAIR -> "旧缓存修复"
            MaintenanceTaskType.QRC_V2_REBUILD -> "V2 构建"
            MaintenanceTaskType.ARTWORK_DISCOVERY -> "封面探测"
            MaintenanceTaskType.ARTWORK_ENHANCEMENT_DIAG -> "封面增强诊断"
        }
    }

    private fun setActionButtonState(
        button: Button,
        label: String,
        enabled: Boolean
    ) {
        button.setTextIfChanged(label)
        if (button.isEnabled != enabled) {
            button.isEnabled = enabled
        }
        val alpha = if (enabled) 1.0f else 0.48f
        if (button.alpha != alpha) {
            button.alpha = alpha
        }
        button.setTextColor(Color.WHITE)
    }

    private fun TextView.setTextIfChanged(value: String) {
        if (text.toString() != value) {
            text = value
        }
    }

    private fun actionButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(50, 78, 122), dp(12))
            setOnClickListener { action() }
        }
    }

    private fun collapsibleCard(
        parent: LinearLayout,
        title: String,
        expanded: Boolean
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.rgb(30, 31, 36), dp(18))
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        val header = TextView(this).apply {
            text = if (expanded) "▼ $title" else "▶ $title"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(10))
            setOnClickListener {
                val nowExpanded = body.visibility != View.VISIBLE
                body.visibility = if (nowExpanded) View.VISIBLE else View.GONE
                text = if (nowExpanded) "▼ $title" else "▶ $title"
            }
        }
        card.addView(header)
        card.addView(body)
        parent.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )
        return body
    }

    private fun buttonGrid(items: List<Pair<String, () -> Unit>>): GridLayout {
        return GridLayout(this).apply {
            columnCount = 2
            items.forEach { (label, action) ->
                addView(
                    actionButton(label, action),
                    GridLayout.LayoutParams().apply {
                        width = 0
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                )
            }
        }
    }

    private fun statusValue(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(Color.rgb(220, 224, 230))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedBackground(Color.rgb(38, 40, 46), dp(12))
        }
    }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun startPlayerAgentService() {
        userStoppedControlServiceThisRun = false
        autoStartControlServiceRequestedThisRun = false
        autoStartControlServiceRequestedAtMs = 0L
        if (!ensureRequiredPermissions()) {
            appendLog("[PlayerAgent] Please grant permissions, then start service again")
            return
        }
        requestStartPlayerAgentService()
        setControlServiceStatus("启动请求已发送")
        appendLog("[PlayerAgent] Start service requested")
    }

    private fun maybeAutoStartControlService(trigger: String) {
        val preferences = controlServicePreferences()
        if (!preferences.contains(KEY_AUTO_START_CONTROL_SERVICE)) {
            preferences.edit()
                .putBoolean(KEY_AUTO_START_CONTROL_SERVICE, true)
                .apply()
        }
        val enabled = preferences.getBoolean(KEY_AUTO_START_CONTROL_SERVICE, true)
        appendLog("[ControlServiceAutoStart] enabled=$enabled trigger=$trigger")
        if (!enabled) {
            appendLog("[ControlServiceAutoStart] skip reason=disabled")
            return
        }
        if (userStoppedControlServiceThisRun) {
            appendLog("[ControlServiceAutoStart] skip reason=user_stopped")
            return
        }
        if (PlayerAgentForegroundService.isRunning()) {
            appendLog("[ControlServiceAutoStart] skip reason=already_started")
            setControlServiceStatus("运行中")
            return
        }
        val now = System.currentTimeMillis()
        if (autoStartControlServiceRequestedThisRun &&
            now - autoStartControlServiceRequestedAtMs < AUTO_START_PENDING_GUARD_MS
        ) {
            appendLog("[ControlServiceAutoStart] skip reason=already_started")
            return
        }
        if (!hasRequiredPermissions()) {
            appendLog("[ControlServiceAutoStart] failed reason=permission_missing")
            return
        }

        appendLog("[ControlServiceAutoStart] start requested")
        autoStartControlServiceRequestedThisRun = true
        autoStartControlServiceRequestedAtMs = now
        requestStartPlayerAgentService()
        setControlServiceStatus("自动启动请求已发送")
        appendLog("[ControlServiceAutoStart] service started")
        mainHandler.postDelayed(
            ::refreshControlServiceStatus,
            CONTROL_SERVICE_STATUS_VERIFY_DELAY_MS
        )
    }

    private fun requestStartPlayerAgentService() {
        val intent = Intent(this, PlayerAgentForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopPlayerAgentService() {
        userStoppedControlServiceThisRun = true
        autoStartControlServiceRequestedThisRun = false
        autoStartControlServiceRequestedAtMs = 0L
        stopService(Intent(this, PlayerAgentForegroundService::class.java))
        setControlServiceStatus("已停止")
        appendLog("[PlayerAgent] Stop service requested")
        appendLog("[ControlServiceAutoStart] skip reason=user_stopped")
    }

    private fun refreshControlServiceStatus() {
        if (!::statusTextView.isInitialized) {
            return
        }
        val serviceRunning = PlayerAgentForegroundService.isRunning()
        setControlServiceStatus(if (serviceRunning) "运行中" else "未启动")
        setBleStatus(PlayerAgentForegroundService.bleHealthSnapshot())
    }

    private fun setControlServiceStatus(status: String) {
        if (::statusTextView.isInitialized) {
            statusTextView.setTextIfChanged("Service：$status")
        }
    }

    private fun setBleStatus(snapshot: com.example.playeragent.ble.BleHealthSnapshot) {
        if (!::bleStatusTextView.isInitialized) {
            return
        }
        val label = when (snapshot.healthState) {
            BleHealthState.SERVICE_STOPPED -> "未启动"
            BleHealthState.STARTING -> "启动中"
            BleHealthState.ADVERTISING -> "可发现，等待 iPhone 连接"
            BleHealthState.CONNECTED -> "已连接，等待订阅"
            BleHealthState.SUBSCRIBED -> "已订阅，等待控制心跳"
            BleHealthState.CONTROLLABLE -> "可控制"
            BleHealthState.SUSPECT -> "疑似断连，自动恢复中"
            BleHealthState.RECOVERING -> "疑似异常，正在恢复"
            BleHealthState.ERROR -> "异常，请点击恢复蓝牙服务"
        }
        val detail = "GATT=${snapshot.gattState} ADV=${snapshot.advertisingState} " +
            "连接=${snapshot.connectedCount} 订阅=${snapshot.subscribedCount} " +
            "队列=${snapshot.pendingJobs}"
        bleStatusTextView.setTextIfChanged("BLE：$label\n$detail")
    }

    private fun startControlServiceStatusRefresh() {
        if (controlServiceStatusRefreshRunnable != null) {
            return
        }
        val runnable = object : Runnable {
            override fun run() {
                refreshControlServiceStatus()
                mainHandler.postDelayed(this, CONTROL_SERVICE_STATUS_REFRESH_INTERVAL_MS)
            }
        }
        controlServiceStatusRefreshRunnable = runnable
        mainHandler.postDelayed(runnable, CONTROL_SERVICE_STATUS_REFRESH_INTERVAL_MS)
    }

    private fun stopControlServiceStatusRefresh() {
        controlServiceStatusRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        controlServiceStatusRefreshRunnable = null
    }

    private fun refreshCurrentMedia() {
        Thread {
            val state = try {
                PlaybackStateReader(
                    context = this,
                    logger = ::appendThreadSafeLog,
                    includeLyric = false
                ).readPlaybackState()
            } catch (exception: Exception) {
                appendThreadSafeLog(
                    "[PlayerAgent] Current media refresh failed: ${exception.message}"
                )
                null
            }

            val albumArt = try {
                AlbumArtTestManager(
                    context = this,
                    logger = ::appendThreadSafeLog
                ).readCurrentNotificationAlbumArt()
            } catch (_: Exception) {
                null
            }

            runOnUiThread {
                if (state != null) {
                    renderPlayerUiState(
                        title = state.optString("title"),
                        artist = state.optString("artist"),
                        album = state.optString("album"),
                        lyric = state.optString("lyric"),
                        positionMs = state.optLong("position"),
                        durationMs = state.optLong("duration"),
                        allowAlbumArtRefresh = false
                    )
                }

                if (albumArt != null) {
                    lastPlayerUiAlbumArtStatus =
                        "READY ${albumArt.bitmap.width}x${albumArt.bitmap.height}"
                    currentAlbumArtImageView.setImageBitmap(albumArt.bitmap)
                    currentAlbumArtTextView.text =
                        "封面：${albumArt.bitmap.width} x ${albumArt.bitmap.height}"
                } else {
                    lastPlayerUiAlbumArtStatus = "SOURCE_NOT_PROVIDED"
                    currentAlbumArtImageView
                        .setImageResource(android.R.drawable.ic_media_play)
                    currentAlbumArtTextView.text = "封面：未找到"
                }
                refreshMaintenanceUiState()
            }
        }.apply {
            name = "MainStatusRefreshThread"
            start()
        }
    }

    private fun updatePlayerUiState(intent: Intent) {
        renderPlayerUiState(
            title = intent.getStringExtra(PlayerAgentForegroundService.EXTRA_UI_TITLE)
                .orEmpty(),
            artist = intent.getStringExtra(PlayerAgentForegroundService.EXTRA_UI_ARTIST)
                .orEmpty(),
            album = intent.getStringExtra(PlayerAgentForegroundService.EXTRA_UI_ALBUM)
                .orEmpty(),
            lyric = intent.getStringExtra(PlayerAgentForegroundService.EXTRA_UI_LYRIC)
                .orEmpty(),
            lyricStatus = intent.getStringExtra(
                PlayerAgentForegroundService.EXTRA_UI_LYRIC_STATUS
            ).orEmpty(),
            positionMs = intent.getLongExtra(
                PlayerAgentForegroundService.EXTRA_UI_POSITION,
                0L
            ),
            durationMs = intent.getLongExtra(
                PlayerAgentForegroundService.EXTRA_UI_DURATION,
                0L
            ),
            allowAlbumArtRefresh = true
        )
    }

    private fun renderPlayerUiState(
        title: String,
        artist: String,
        album: String,
        lyric: String,
        lyricStatus: String = "",
        positionMs: Long,
        durationMs: Long,
        allowAlbumArtRefresh: Boolean
    ) {
        val safeTitle = title.ifBlank { "-" }
        val safeArtist = artist.ifBlank { "-" }
        val safeAlbum = album.ifBlank { "-" }
        val songKey = "$safeTitle|$safeArtist|$safeAlbum"
        val songChanged = songKey != lastPlayerUiSongKey
        if (songChanged) {
            lastPlayerUiSongKey = songKey
            appendLog(
                "[PlayerUI] track changed title=$safeTitle " +
                    "artist=$safeArtist album=$safeAlbum"
            )
            if (allowAlbumArtRefresh) {
                lastPlayerUiAlbumArtStatus = "LOADING"
                currentAlbumArtImageView.setImageResource(android.R.drawable.ic_media_play)
                    currentAlbumArtTextView.text = "封面：加载中..."
                refreshCurrentAlbumArtForSong(songKey)
            }
        }

        currentSongTextView.text = "歌曲名：$safeTitle\n歌手：$safeArtist\n专辑：$safeAlbum"
            .replace("歌曲名：-\n歌手：-\n专辑：-", "歌曲：未检测")
        val lyricText = lyric.ifBlank { "暂无歌词" }
        val statusText = lyricStatus.ifBlank { "unknown" }
        lastPlayerUiLyricStatus = statusText
        val hint = if (statusText == "waiting QQMusic lyric cache") {
            "\n提示：QQ音乐可能尚未生成歌词缓存，可在 QQ音乐中打开歌词/桌面歌词后稍等。"
        } else {
            ""
        }
        currentLyricTextView.text = "当前歌词：$lyricText\nLyric status：$statusText$hint"
        if (lyricText != lastPlayerUiLyric) {
            lastPlayerUiLyric = lyricText
            appendLog("[PlayerUI] lyric changed text=$lyricText")
        }
        refreshMaintenanceUiState()
    }

    private fun refreshCurrentAlbumArtForSong(songKey: String) {
        val generation = ++albumArtRefreshGeneration
        Thread {
            val albumArt = try {
                AlbumArtTestManager(
                    context = this,
                    logger = ::appendThreadSafeLog
                ).readCurrentNotificationAlbumArt()
            } catch (exception: Exception) {
                appendThreadSafeLog(
                    "[PlayerUI] album art refresh failed=${exception.message}"
                )
                null
            }
            runOnUiThread {
                if (generation != albumArtRefreshGeneration ||
                    songKey != lastPlayerUiSongKey
                ) {
                    return@runOnUiThread
                }
                if (albumArt != null) {
                    lastPlayerUiAlbumArtStatus =
                        "READY ${albumArt.bitmap.width}x${albumArt.bitmap.height}"
                    currentAlbumArtImageView.setImageBitmap(albumArt.bitmap)
                    currentAlbumArtTextView.text =
                        "封面：${albumArt.bitmap.width} x ${albumArt.bitmap.height}"
                    appendLog(
                        "[PlayerUI] album art changed exists=true " +
                            "width=${albumArt.bitmap.width} " +
                            "height=${albumArt.bitmap.height}"
                    )
                } else {
                    lastPlayerUiAlbumArtStatus = "SOURCE_NOT_PROVIDED"
                    currentAlbumArtImageView
                        .setImageResource(android.R.drawable.ic_media_play)
                    currentAlbumArtTextView.text = "封面：未找到"
                    appendLog("[PlayerUI] album art changed exists=false")
                }
                refreshMaintenanceUiState()
            }
        }.apply {
            name = "MainAlbumArtRefreshThread"
            start()
        }
    }

    private fun refreshHistoryStatus() {
        Thread {
            val text = try {
                val snapshot = PlaybackHistoryRepository(this).statusSnapshot()
                val monitor = snapshot.monitorStatus
                "Monitor running：${if (monitor.running) "是" else "否"}\n" +
                    "Active session id：${monitor.activeSessionId ?: "-"}\n" +
                    "Active title：${monitor.activeTitle.ifBlank { "-" }}\n" +
                    "Active listenedMs：${formatDuration(monitor.activeListenedMs)}\n" +
                    "Total tracks：${snapshot.totalTracks}\n" +
                    "Total sessions：${snapshot.totalSessions}\n" +
                    "Last session id：${snapshot.lastSessionId}\n" +
                    "Today listen time：${formatDuration(snapshot.todayListenMs)}\n" +
                    "Database path：${snapshot.databasePath}"
            } catch (exception: Exception) {
                "[History] status failed: ${exception.message}"
            }
            runOnUiThread {
                historyStatusTextView.text = text
                appendLog("[History] status refreshed")
            }
        }.apply {
            name = "HistoryStatusThread"
            start()
        }
    }

    private fun showRecentHistory() {
        Thread {
            val text = try {
                val rows = PlaybackHistoryRepository(this).getRecentSessions(limit = 10)
                recentHistoryText(rows)
            } catch (exception: Exception) {
                "[History] recent failed: ${exception.message}"
            }
            runOnUiThread {
                historyStatusTextView.text = text
                appendLog("[History] recent 10 refreshed")
            }
        }.apply {
            name = "HistoryRecentThread"
            start()
        }
    }

    private fun showTodayHistoryStats() {
        Thread {
            val text = try {
                val stats = PlaybackHistoryRepository(this).stats(StatsRange.TODAY)
                "今日统计\n" +
                    "收听时长：${formatDuration(stats.totalListenMs)}\n" +
                    "播放次数：${stats.playCount}\n" +
                    "歌曲数：${stats.uniqueTrackCount}\n" +
                    "完播数：${stats.completedCount}\n" +
                    "跳过数：${stats.skippedCount}\n" +
                    "完播率：${formatPercent(stats.completionRate)}\n" +
                    "跳过率：${formatPercent(stats.skipRate)}\n\n" +
                    "最常听歌曲\n" +
                    stats.topTracks.joinToString("\n") {
                        "${it.title.ifBlank { "-" }} · ${it.artist.ifBlank { "未知歌手" }} " +
                            "${formatDuration(it.listenedMs)}"
                    }.ifBlank { "暂无数据" }
            } catch (exception: Exception) {
                "[History] stats failed: ${exception.message}"
            }
            runOnUiThread {
                historyStatusTextView.text = text
                appendLog("[History] today stats refreshed")
            }
        }.apply {
            name = "HistoryStatsThread"
            start()
        }
    }

    private fun clearPlayHistory() {
        Thread {
            val message = try {
                PlaybackHistoryRepository(this).clearAll()
                "[History] local playback history cleared"
            } catch (exception: Exception) {
                "[History] clear failed: ${exception.message}"
            }
            runOnUiThread {
                historyStatusTextView.text = "播放历史：已清空"
                appendLog(message)
            }
        }.apply {
            name = "HistoryClearThread"
            start()
        }
    }

    private fun recentHistoryText(rows: List<HistorySessionRow>): String {
        if (rows.isEmpty()) {
            return "最近播放\n暂无记录"
        }
        return "最近播放\n" + rows.joinToString("\n\n") { row ->
            val title = row.title.ifBlank { "-" }
            val artist = row.artist.ifBlank { "未知歌手" }
            val album = row.album.ifBlank { "未知专辑" }
            val state = when {
                row.completed -> "已完播"
                row.skipped -> "已跳过"
                row.countedPlay -> "已计播放"
                else -> "未计播放"
            }
            "$title\n$artist · $album\n" +
                "${formatDateTime(row.startedAt)} · 听了 ${formatDuration(row.listenedMs)}\n" +
                state
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val safe = durationMs.coerceAtLeast(0L)
        val totalSeconds = safe / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d小时%02d分钟".format(hours, minutes)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun formatDateTime(timeMs: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timeMs))
    }

    private fun formatPercent(value: Double): String {
        return "%.0f%%".format(value * 100.0)
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter == null) {
            appendLog("[PlayerAgent] BluetoothAdapter unavailable")
            return
        }
        appendLog("[PlayerAgent] Bluetooth initialized for controller scan")
    }

    private fun startControllerScan() {
        if (!ensureRequiredPermissions()) {
            appendLog("[PlayerAgent] Please grant permissions, then scan again")
            return
        }
        val adapter = bluetoothAdapter ?: run {
            appendLog("[PlayerAgent] Cannot scan: BluetoothAdapter unavailable")
            return
        }
        controllerScannerManager?.stopScan()
        controllerScannerManager = ControllerScannerManager(
            context = this,
            bluetoothAdapter = adapter,
            logger = ::appendPlayerAgentLog
        )
        controllerScannerManager?.startScan()
    }

    private fun connectRfcommServer() {
        if (!ensureRequiredPermissions()) {
            appendLog("[PlayerAgent] Please grant permissions, then connect again")
            return
        }
        val adapter = bluetoothAdapter ?: run {
            appendLog("[PlayerAgent] Cannot connect RFCOMM: BluetoothAdapter unavailable")
            return
        }
        if (!adapter.isEnabled) {
            appendLog("[PlayerAgent] Cannot connect RFCOMM: Bluetooth disabled")
            return
        }
        rfcommClientManager?.close()
        rfcommClientManager = RfcommClientManager(
            context = this,
            bluetoothAdapter = adapter,
            logger = ::appendPlayerAgentLog
        )
        rfcommClientManager?.connectToServer()
    }

    private fun scanLrcFiles() {
        if (!ensureLyricStorageAccess()) {
            return
        }
        Thread {
            try {
                appendThreadSafeLog("[LyricScan] requested")
                val playbackState = PlaybackStateReader(
                    context = this,
                    logger = ::appendThreadSafeLog,
                    includeLyric = false
                ).readPlaybackState()
                LyricManager(
                    context = this,
                    logger = ::appendThreadSafeLog
                ).scanLrcFiles(
                    title = playbackState.optString("title"),
                    artist = playbackState.optString("artist"),
                    album = playbackState.optString("album")
                )
            } catch (exception: Exception) {
                appendThreadSafeLog("[LyricScan] failed: ${exception.message}")
            }
        }.apply {
            name = "LyricScanThread"
            start()
        }
    }

    private fun testAlbumArt() {
        val manager = AlbumArtTestManager(this, ::appendThreadSafeLog)
        Thread {
            try {
                manager.runTest()
                refreshCurrentMedia()
            } catch (exception: Exception) {
                appendThreadSafeLog("[AlbumArtTest] failed=${exception.message}")
            }
        }.apply {
            name = "AlbumArtTestThread"
            start()
        }
    }

    private fun discoverHqArtwork() {
        Thread {
            try {
                val playbackState = PlaybackStateReader(
                    context = this,
                    logger = ::appendThreadSafeLog,
                    includeLyric = false
                ).readPlaybackState()
                val title = playbackState.optString("title")
                val artist = playbackState.optString("artist")
                val album = playbackState.optString("album")
                val albumArt = AlbumArtTestManager(
                    this,
                    ::appendThreadSafeLog
                ).readCurrentNotificationAlbumArt()
                if (albumArt == null) {
                    appendThreadSafeLog("[ArtworkDiscovery] skipped reason=no notification artwork")
                    return@Thread
                }
                val albumArtId = buildArtworkDiscoveryId(title, artist, album)
                runOnUiThread {
                    artworkDiscoveryStatusTextView.text = ArtworkDiscoveryStatus(
                        status = "running",
                        currentTitle = title
                    ).displayText()
                }
                val manager = artworkDiscoveryManager ?: QQMusicArtworkDiscoveryManager(
                    context = this,
                    logger = ::appendThreadSafeLog,
                    onStatus = ::updateArtworkDiscoveryStatus
                ).also {
                    artworkDiscoveryManager = it
                }
                manager.discoverCurrentTrackArtwork(
                    title = title,
                    artist = artist,
                    album = album,
                    albumArtId = albumArtId,
                    referenceBitmap = albumArt.bitmap
                )
            } catch (exception: Exception) {
                appendThreadSafeLog("[ArtworkDiscovery] failed=${exception.message}")
            }
        }.apply {
            name = "ArtworkDiscoveryStartThread"
            start()
        }
    }

    private fun stopArtworkDiscovery() {
        artworkDiscoveryManager?.cancel()
        updateArtworkDiscoveryStatus(ArtworkDiscoveryStatus(status = "stopped"))
    }

    private fun updateArtworkDiscoveryStatus(status: ArtworkDiscoveryStatus) {
        runOnUiThread {
            if (::artworkDiscoveryStatusTextView.isInitialized) {
                artworkDiscoveryStatusTextView.text = status.displayText()
            }
        }
    }

    private fun buildArtworkDiscoveryId(
        title: String,
        artist: String,
        album: String
    ): String {
        val source = listOf(title, artist, album)
            .joinToString("|")
            .ifBlank { "unknown" }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun testLyricSource() {
        val manager = LyricSourceTestManager(this, ::appendThreadSafeLog)
        Thread {
            try {
                manager.runTest()
            } catch (exception: Exception) {
                appendThreadSafeLog("[LyricSourceTest] failed=${exception.message}")
            }
        }.apply {
            name = "LyricSourceTestThread"
            start()
        }
    }

    private fun testCurrentLyric() {
        appendLog("[LyricProbe] 探测当前歌词")
        Thread {
            try {
                val result = CurrentLyricProbe.probeCurrentLyric(this)
                appendThreadSafeLog(
                    "[LyricProbe] result lyric=${result.lyric.ifBlank { "未找到" }} " +
                        "source=${result.source} detail=${result.detail}"
                )
            } catch (exception: Exception) {
                appendThreadSafeLog("[LyricProbe] failed=${exception.message}")
            }
        }.apply {
            name = "CurrentLyricProbeThread"
            start()
        }
    }

    private fun testLrcDebug() {
        if (!ensureLyricStorageAccess()) {
            return
        }
        appendLog("[LyricDebug] button clicked")
        Thread {
            try {
                LrcDebugManager(
                    context = this,
                    logger = ::appendThreadSafeLocalOnlyLog,
                    summaryLogger = ::appendThreadSafeLog
                ).runDebug()
            } catch (exception: Exception) {
                appendThreadSafeLog("[LyricDebug] failed=${exception.message}")
            }
        }.apply {
            name = "LrcDebugThread"
            start()
        }
    }

    private fun dumpFirstQrc() {
        if (!ensureLyricStorageAccess()) {
            return
        }
        appendLog("[QrcDump] button clicked")
        Thread {
            try {
                QrcDumpManager(
                    context = this,
                    logger = ::appendThreadSafeLocalOnlyLog,
                    summaryLogger = ::appendThreadSafeLog
                ).dumpFirstQrc()
            } catch (exception: Exception) {
                appendThreadSafeLog("[QrcDump] failed=${exception.message}")
            }
        }.apply {
            name = "QrcDumpThread"
            start()
        }
    }

    private fun startQrcV2Build(clear: Boolean) {
        if (!ensureLyricStorageAccess()) {
            return
        }
        val manager = qrcV2RebuildManager ?: QrcLyricV2RebuildManager(
            context = this,
            logger = ::appendThreadSafeLog,
            progressListener = ::updateQrcV2BuildProgress
        ).also {
            qrcV2RebuildManager = it
        }
        manager.start(clearBuilding = clear)
    }

    private fun pauseQrcV2Build() {
        qrcV2RebuildManager?.pause()
    }

    private fun resumeQrcV2Build() {
        val manager = qrcV2RebuildManager ?: QrcLyricV2RebuildManager(
            context = this,
            logger = ::appendThreadSafeLog,
            progressListener = ::updateQrcV2BuildProgress
        ).also {
            qrcV2RebuildManager = it
        }
        manager.resume()
    }

    private fun stopQrcV2Build() {
        qrcV2RebuildManager?.stop()
    }

    private fun clearQrcV2Building() {
        val manager = qrcV2RebuildManager ?: QrcLyricV2RebuildManager(
            context = this,
            logger = ::appendThreadSafeLog,
            progressListener = ::updateQrcV2BuildProgress
        ).also {
            qrcV2RebuildManager = it
        }
        manager.clearBuilding()
    }

    private fun startQrcStaleCacheRebuild() {
        if (!ensureLyricStorageAccess()) {
            return
        }
        val manager = qrcStaleCacheRebuildManager ?: QrcStaleCacheRebuildManager(
            context = this,
            logger = ::appendThreadSafeLog,
            progressListener = ::updateQrcStaleCacheRebuildProgress
        ).also {
            qrcStaleCacheRebuildManager = it
        }
        manager.start()
    }

    private fun stopQrcStaleCacheRebuild() {
        qrcStaleCacheRebuildManager?.stop()
    }

    private fun cancelCurrentMaintenance() {
        val currentType = QrcMaintenanceCoordinator.currentToken()?.type
        val cancelled = QrcMaintenanceCoordinator.cancelCurrent(
            "debug button",
            ::appendThreadSafeLog
        )
        when (currentType) {
            MaintenanceTaskType.QRC_V2_REBUILD -> qrcV2RebuildManager?.stop()
            MaintenanceTaskType.CACHE_REPAIR -> qrcStaleCacheRebuildManager?.stop()
            MaintenanceTaskType.QRC_INCREMENTAL_PREBUILD -> stopQrcWatcher()
            MaintenanceTaskType.LYRIC_WARMUP -> lyricWarmupManager?.cancel()
            MaintenanceTaskType.ARTWORK_DISCOVERY -> artworkDiscoveryManager?.cancel()
            else -> {
                qrcV2RebuildManager?.stop()
                qrcStaleCacheRebuildManager?.stop()
            }
        }
        if (cancelled) {
            appendLog("[QrcMaintenance] cancel requested from UI")
        } else {
            appendLog("[QrcMaintenance] cancel skipped reason=no active task")
        }
        refreshMaintenanceStatus()
    }

    private fun deleteOldQrcBackup() {
        val backup = QrcLyricCacheManager(this, ::appendThreadSafeLog).backupCacheRoot()
        if (backup.exists()) {
            backup.deleteRecursively()
            appendLog("[QrcV2Rebuild] old backup deleted")
        } else {
            appendLog("[QrcV2Rebuild] old backup not found")
        }
    }

    private fun startQrcWatcher() {
        if (!ensureLyricStorageAccess()) {
            return
        }
        val intent = Intent(
            this,
            PlayerAgentForegroundService::class.java
        ).setAction(PlayerAgentForegroundService.ACTION_START_QRC_WATCHER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        appendLog("[QrcWatcher] start requested")
    }

    private fun stopQrcWatcher() {
        startService(
            Intent(this, PlayerAgentForegroundService::class.java)
                .setAction(PlayerAgentForegroundService.ACTION_STOP_QRC_WATCHER)
        )
        appendLog("[QrcWatcher] stop requested")
        QrcMaintenanceCoordinator.finishCurrentIf(
            MaintenanceTaskType.QRC_INCREMENTAL_PREBUILD,
            "ui stop watcher",
            ::appendThreadSafeLog
        )
        refreshMaintenanceUiState()
    }

    private fun refreshCurrentLyric() {
        val intent = Intent(
            this,
            PlayerAgentForegroundService::class.java
        ).setAction(PlayerAgentForegroundService.ACTION_REFRESH_CURRENT_LYRIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        appendLog("[LyricRetry] manual refresh requested")
    }

    private fun recoverBleStack() {
        val intent = Intent(this, PlayerAgentForegroundService::class.java)
            .setAction(PlayerAgentForegroundService.ACTION_RECOVER_BLE_STACK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        appendLog("[BLE-RECOVERY] manual recover requested")
    }

    private fun updateQrcV2BuildProgress(progress: QrcV2RebuildProgress) {
        runOnUiThread {
            lastQrcV2BuildProgress = progress
            refreshMaintenanceUiState()
            if (progress.status == QrcV2RebuildStatus.COMPLETED ||
                progress.status == QrcV2RebuildStatus.FAILED ||
                progress.status == QrcV2RebuildStatus.STOPPED
            ) {
                refreshQrcCacheOverview()
            }
        }
    }

    private fun updateQrcStaleCacheRebuildProgress(progress: QrcStaleCacheRebuildProgress) {
        runOnUiThread {
            lastQrcStaleCacheRebuildProgress = progress
            refreshMaintenanceUiState()
            if (!progress.running) {
                refreshQrcCacheOverview()
            }
        }
    }

    private fun updateQrcWatcherStatus(status: QrcWatcherStatus) {
        lastQrcWatcherStatus = status
        runOnUiThread {
            refreshMaintenanceUiState()
            refreshMaintenanceStatus()
        }
    }

    private fun refreshLyricStats() {
        refreshLyricStatsText()
        appendLog("[LyricStats] refresh requested")
    }

    private fun resetLyricStats() {
        QrcLyricCacheManager(
            context = this,
            logger = ::appendThreadSafeLog
        ).resetStats()
        refreshLyricStatsText()
        appendLog("[LyricStats] reset")
    }

    private fun forceRefreshQrcIndex() {
        if (!ensureLyricStorageAccess()) {
            return
        }
        appendLog("[QrcIndex] force refresh requested")
        val manager = QrcPersistentIndexManager(
            context = this,
            logger = ::appendThreadSafeLog
        )
        manager.markDirty("manual")
        Thread {
            manager.rebuildAsync()
            runOnUiThread {
                refreshLyricStatsText()
            }
        }.apply {
            name = "QrcIndexForceRefreshThread"
            start()
        }
    }

    private fun warmupLyricCache() {
        if (!ensureLyricStorageAccess()) {
            return
        }
        appendLog("[LyricWarmup] manual requested")
        val manager = lyricWarmupManager ?: LyricWarmupManager(
            context = this,
            logger = ::appendThreadSafeLog
        ).also {
            lyricWarmupManager = it
        }
        manager.warmupNow()
        Thread {
            var polls = 0
            while (polls < 45) {
                Thread.sleep(1_000L)
                runOnUiThread {
                    refreshMaintenanceUiState()
                }
                val current = QrcMaintenanceCoordinator.currentToken()
                if (current?.type != MaintenanceTaskType.LYRIC_WARMUP) {
                    break
                }
                polls += 1
            }
            runOnUiThread {
                refreshMaintenanceUiState()
                refreshLyricStatsText()
            }
        }.apply {
            name = "LyricWarmupUiRefreshThread"
            start()
        }
    }

    private fun rebuildFuzzyLyricIndex() {
        if (!ensureLyricStorageAccess()) {
            return
        }
        appendLog("[QrcCacheIndex] manual rebuild requested")
        QrcLyricCacheManager(this, ::appendThreadSafeLog)
            .preloadFuzzyIndexAsync(force = true)
        Thread {
            Thread.sleep(1_000L)
            runOnUiThread {
                refreshLyricStatsText()
            }
        }.apply {
            name = "QrcFuzzyIndexUiRefreshThread"
            start()
        }
    }

    private fun refreshLyricStatsText() {
        scheduleDebugStatsLoad(force = true)
    }

    private fun refreshQrcCacheOverview() {
        scheduleDebugStatsLoad(force = true)
    }

    private fun scheduleDebugStatsLoad(force: Boolean = false) {
        debugStatsLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        val delayMs = if (force) DEBUG_STATS_FORCE_DELAY_MS else DEBUG_STATS_INITIAL_DELAY_MS
        val runnable = Runnable {
            debugStatsLoadRunnable = null
            StartupGuard.runWhenHeavyTaskAllowed(
                taskName = "debug stats",
                handler = mainHandler,
                logger = ::appendLog
            ) {
                loadDebugStatsAsync(force)
            }
        }
        debugStatsLoadRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun loadDebugStatsAsync(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDebugStatsLoadAt < DEBUG_STATS_THROTTLE_MS) {
            appendLog("[DebugStats] skipped reason=throttled")
            return
        }
        if (debugStatsLoading) {
            appendLog("[DebugStats] skipped reason=already loading")
            return
        }
        debugStatsLoading = true
        lastDebugStatsLoadAt = now
        if (::lyricCacheStatsTextView.isInitialized && lastLyricCacheStatsText == "歌词统计：loading...") {
            lastLyricCacheStatsText = "歌词统计：loading..."
            refreshMaintenanceUiState()
        }
        Thread {
            val startedAt = System.currentTimeMillis()
            appendThreadSafeLog("[DebugStats] load start")
            val result = runCatching {
                DebugStatsText(
                    qrcOverview = qrcCacheOverviewText(),
                    lyricStats = lyricCacheStatsText()
                )
            }
            runOnUiThread {
                debugStatsLoading = false
                result.onSuccess { stats ->
                    if (::qrcCacheOverviewTextView.isInitialized) {
                        qrcCacheOverviewTextView.setTextIfChanged(stats.qrcOverview)
                    }
                    if (::lyricCacheStatsTextView.isInitialized) {
                        lastLyricCacheStatsText = stats.lyricStats
                    }
                    refreshMaintenanceUiState()
                    appendLog("[DebugStats] load done costMs=${System.currentTimeMillis() - startedAt}")
                }.onFailure { exception ->
                    if (::qrcCacheOverviewTextView.isInitialized) {
                        qrcCacheOverviewTextView.setTextIfChanged(
                            "缓存统计：加载失败 ${exception.message}"
                        )
                    }
                    if (::lyricCacheStatsTextView.isInitialized) {
                        lastLyricCacheStatsText = "歌词统计：加载失败 ${exception.message}"
                        refreshMaintenanceUiState()
                    }
                    appendLog("[DebugStats] failed error=${exception.message}")
                }
            }
        }.apply {
            name = "DebugStatsLoadThread"
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun refreshMaintenanceStatus() {
        refreshMaintenanceUiState()
    }

    private fun qrcCacheOverviewText(): String {
        val cacheRoot = QrcLyricCacheManager(this, ::appendThreadSafeLog).cacheRoot()
        val cacheManager = QrcLyricCacheManager(this, ::appendThreadSafeLog)
        val files = cacheRoot.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
        }.orEmpty()
        val indexStatus = QrcPersistentIndexManager(this, ::appendThreadSafeLog).status()
        val fuzzyStatus = cacheManager.fuzzyIndexStatus()
        val aliasItems = QrcAliasCacheManager(this, ::appendThreadSafeLog).itemCount()
        val negativeItems = QrcNegativeCacheManager(this, ::appendThreadSafeLog).itemCount()
        val rebuild = qrcV2RebuildManager?.status()
        return "缓存版本：${if (rebuild?.status == com.example.playeragent.media.QrcV2RebuildStatus.COMPLETED) "V2" else "V1 / 混合"}\n" +
            "活动缓存文件数：${files.size}\n" +
            "V2构建含逐字：${rebuild?.successWithWords ?: 0}\n" +
            "V2构建仅行级：${rebuild?.successLineOnly ?: 0}\n" +
            "索引条目数：${indexStatus.entries}\n" +
            "Alias 条目数：$aliasItems\n" +
            "Negative 条目数：$negativeItems\n" +
            "Fuzzy Index：${if (fuzzyStatus.ready) "就绪" else if (fuzzyStatus.warming) "预热中" else "未预热"} entries=${fuzzyStatus.entries} files=${fuzzyStatus.files}\n" +
            "Watcher：${if (lastQrcWatcherStatus.watcherRunning) "运行中" else "已停止"}"
    }

    private fun lyricCacheStatsText(): String {
        val stats = QrcLyricCacheManager(
            context = this,
            logger = ::appendThreadSafeLog
        ).getStats()
        val indexStatus = QrcPersistentIndexManager(
            context = this,
            logger = ::appendThreadSafeLog
        ).status()
        return "歌词缓存统计\n" +
            "L1 命中：${stats.l1Hit}\n" +
            "L2 命中：${stats.l2Hit}\n" +
            "模糊命中：${stats.l2FuzzyHit}\n" +
            "Alias 命中：${stats.aliasHit}\n" +
            "Negative 命中：${stats.negativeHit}\n" +
            "QRC 解密次数：${stats.qrcDecryptCount}\n" +
            "QRC 解密成功：${stats.qrcDecryptSuccess}\n" +
            "QRC 解密失败：${stats.qrcDecryptFailed}\n" +
            "最近来源：${stats.lastSource}\n" +
            "QrcIndex 已加载：${indexStatus.loaded}\n" +
            "QrcIndex dirty：${indexStatus.dirty}\n" +
            "QrcIndex builtAt：${indexStatus.builtAt}\n" +
            "Watcher pending：${lastQrcWatcherStatus.pendingGroups}\n" +
            "增量成功：${lastQrcWatcherStatus.incrementalSuccess}\n" +
            "增量失败：${lastQrcWatcherStatus.incrementalFailed}"
    }

    private fun confirmAction(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("确认操作")
            .setMessage(message)
            .setPositiveButton("确认") { _, _ -> action() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleLogs() {
        logScrollView.visibility = if (logScrollView.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (exception: Exception) {
            appendLog("[PlayerAgent] Cannot open Notification Access settings")
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (exception: Exception) {
            appendLog("[PlayerAgent] Cannot open Accessibility settings")
        }
    }

    private fun dumpAccessibilityTree() {
        if (!QQMusicLyricAccessibilityService.isEnabled(this)) {
            appendLog(
                "[AccessibilityTree] Accessibility service is disabled"
            )
            openAccessibilitySettings()
            return
        }

        if (!QQMusicLyricAccessibilityService.requestTreeDump()) {
            appendLog(
                "[AccessibilityTree] service not connected; reopen QQMusic and retry"
            )
            return
        }

        appendLog(
            "[AccessibilityTree] request accepted; switch to QQMusic lyric page"
        )
    }

    private fun refreshAccessibilityStatus() {
        if (!::accessibilityStatusTextView.isInitialized) {
            return
        }
        val enabled = QQMusicLyricAccessibilityService.isEnabled(this)
        val connected = QQMusicLyricAccessibilityService.isConnected()
        accessibilityStatusTextView.text = when {
            connected -> "通知读取权限：无障碍已连接"
            enabled -> "通知读取权限：无障碍已授权，等待连接"
            else -> "通知读取权限：未授权"
        }
    }

    private fun ensureLyricStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            appendLog(
                "[LyricDebug] All files access required for /storage/emulated/0/QQMusic"
            )
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            }
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_PERMISSIONS
            )
            appendLog("[LyricDebug] Storage permission required")
            return false
        }
        return true
    }

    private fun exportLog() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "PlayerAgent-log.txt")
        }
        startActivityForResult(intent, REQUEST_EXPORT_LOG)
    }

    private fun clearLog() {
        LogBuffer.clear()
        logTextView.text = ""
        appendLog("[PlayerAgent] Log cleared")
    }

    private fun ensureRequiredPermissions(): Boolean {
        val missingPermissions = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSIONS
            )
            return false
        }
        return true
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun controlServicePreferences() =
        getSharedPreferences(PREFS_CONTROL_SERVICE, MODE_PRIVATE)

    private fun requestRequiredPermissions() {
        ensureRequiredPermissions()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            maybeAutoStartControlService("permissions_result")
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions
    }

    private fun appendLog(
        message: String,
        storeInBuffer: Boolean = true
    ) {
        if (storeInBuffer) {
            LogBuffer.append(message)
        }
        if (!::logTextView.isInitialized || !::logScrollView.isInitialized) {
            android.util.Log.i("PlayerAgent", message)
            return
        }
        logTextView.append("$message\n")
        logScrollView.post {
            logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun appendPlayerAgentLog(message: String) {
        runOnUiThread { appendLog("[PlayerAgent] $message") }
    }

    private fun appendThreadSafeLog(message: String) {
        runOnUiThread { appendLog(message) }
    }

    private fun appendThreadSafeLocalOnlyLog(message: String) {
        runOnUiThread { appendLog(message, storeInBuffer = false) }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class DebugStatsText(
        val qrcOverview: String,
        val lyricStats: String
    )

    private data class StateCardViews(
        val body: LinearLayout,
        val badge: TextView
    )

    private data class MaintenanceUiState(
        val activeTask: MaintenanceTaskType? = null,
        val taskStatus: MaintenanceTaskStatus = MaintenanceTaskStatus.IDLE,
        val reason: String? = null,
        val runningSeconds: Long = 0L,
        val cancelRequested: Boolean = false,
        val buildProgress: QrcV2RebuildProgress = QrcV2RebuildProgress(),
        val oldCacheRepairStatus: QrcStaleCacheRebuildProgress =
            QrcStaleCacheRebuildProgress(),
        val watcherStatus: QrcWatcherStatus = QrcWatcherStatus(
            watcherRunning = false,
            pendingGroups = 0,
            incrementalRunning = false,
            incrementalSuccess = 0,
            incrementalFailed = 0,
            incrementalSkipped = 0
        ),
        val currentTrackTitle: String? = null,
        val currentLyricsStatus: String? = null,
        val currentAlbumArtStatus: String? = null
    )

    private enum class MaintenanceTaskStatus(val displayName: String) {
        IDLE("空闲"),
        RUNNING("运行中"),
        PAUSED("暂停中"),
        STOPPING("停止中"),
        COMPLETED("已完成"),
        FAILED("失败")
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_EXPORT_LOG = 1002
        private const val DEBUG_STATS_INITIAL_DELAY_MS = 700L
        private const val DEBUG_STATS_FORCE_DELAY_MS = 180L
        private const val DEBUG_STATS_THROTTLE_MS = 2_000L
        private const val PREFS_CONTROL_SERVICE = "control_service_preferences"
        private const val KEY_AUTO_START_CONTROL_SERVICE = "autoStartControlService"
        private const val AUTO_START_PENDING_GUARD_MS = 5_000L
        private const val CONTROL_SERVICE_STATUS_VERIFY_DELAY_MS = 800L
        private const val CONTROL_SERVICE_STATUS_REFRESH_INTERVAL_MS = 3_000L
    }
}
