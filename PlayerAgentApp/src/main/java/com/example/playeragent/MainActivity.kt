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
import com.example.playeragent.media.QrcWatcherStatus
import com.example.playeragent.service.PlayerAgentForegroundService
import com.example.playeragent.service.QQMusicLyricAccessibilityService
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusTextView: TextView
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
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var controllerScannerManager: ControllerScannerManager? = null
    private var rfcommClientManager: RfcommClientManager? = null
    private var artworkDiscoveryManager: QQMusicArtworkDiscoveryManager? = null
    private var qrcV2RebuildManager: QrcLyricV2RebuildManager? = null
    private var qrcStaleCacheRebuildManager: QrcStaleCacheRebuildManager? = null
    private var lyricWarmupManager: LyricWarmupManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var debugStatsLoading = false
    @Volatile private var lastDebugStatsLoadAt = 0L
    private var lastPlayerUiSongKey: String = ""
    private var lastPlayerUiLyric: String = ""
    private var albumArtRefreshGeneration = 0L
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
                    statusTextView.text = "控制服务：运行中"

                message.contains("Foreground service stopped") ->
                    statusTextView.text = "控制服务：已停止"

                message.startsWith("[AccessibilityLyric] candidate=") ->
                    appendLog("[PlayerUI] 无障碍候选歌词已记录，当前歌词仍以播放状态为准")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startedAt = System.currentTimeMillis()
        super.onCreate(savedInstanceState)
        appendLog("[UI] MainActivity onCreate start")
        setupUi()
        appendLog("[UI] setContent complete costMs=${System.currentTimeMillis() - startedAt}")
        initializeBluetooth()
        requestRequiredPermissions()
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
    }

    override fun onResume() {
        super.onResume()
        if (::currentSongTextView.isInitialized) {
            refreshCurrentMedia()
            refreshAccessibilityStatus()
        }
    }

    override fun onStop() {
        super.onStop()
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
        statusTextView = statusValue("控制服务：未启动")
        serviceCard.addView(statusTextView)
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
        maintenanceStatusTextView = statusValue(
            QrcMaintenanceCoordinator.snapshot().displayText()
        ).also(lyricCard::addView)
        qrcCacheOverviewTextView = statusValue("缓存统计：loading...").also(lyricCard::addView)
        qrcCacheBuildTextView = statusValue(QrcV2RebuildProgress().displayText()).also(lyricCard::addView)
        qrcStaleCacheRebuildTextView = statusValue(
            QrcStaleCacheRebuildProgress().displayText()
        ).also(lyricCard::addView)
        qrcWatcherStatusTextView = statusValue(lastQrcWatcherStatus.displayText()).also(lyricCard::addView)
        lyricCacheStatsTextView = statusValue("歌词统计：loading...").also(lyricCard::addView)
        lyricCard.addView(buttonGrid(listOf(
            "构建 V2 逐字歌词缓存" to { startQrcV2Build(clear = false) },
            "暂停构建" to ::pauseQrcV2Build,
            "继续构建" to ::resumeQrcV2Build,
            "停止构建" to ::stopQrcV2Build,
            "清空临时构建" to { confirmAction("清空临时构建结果？", ::clearQrcV2Building) },
            "清空并重新构建" to { confirmAction("清空临时结果并重新构建？") { startQrcV2Build(clear = true) } },
            "重建 QRC 索引" to ::forceRefreshQrcIndex,
            "预热歌词缓存" to ::warmupLyricCache,
            "重建歌词模糊索引" to ::rebuildFuzzyLyricIndex,
            "刷新当前歌词" to ::refreshCurrentLyric,
            "刷新缓存统计" to ::refreshLyricStatsText,
            "修复旧歌词缓存" to ::startQrcStaleCacheRebuild,
            "停止旧缓存修复" to ::stopQrcStaleCacheRebuild,
            "取消当前维护任务" to ::cancelCurrentMaintenance,
            "删除旧缓存备份" to { confirmAction("删除旧缓存备份？", ::deleteOldQrcBackup) }
        )))

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
        if (!ensureRequiredPermissions()) {
            appendLog("[PlayerAgent] Please grant permissions, then start service again")
            return
        }
        val intent = Intent(this, PlayerAgentForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusTextView.text = "控制服务：启动请求已发送"
        appendLog("[PlayerAgent] Start service requested")
    }

    private fun stopPlayerAgentService() {
        stopService(Intent(this, PlayerAgentForegroundService::class.java))
        statusTextView.text = "控制服务：已停止"
        appendLog("[PlayerAgent] Stop service requested")
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
                    currentAlbumArtImageView.setImageBitmap(albumArt.bitmap)
                    currentAlbumArtTextView.text =
                        "封面：${albumArt.bitmap.width} x ${albumArt.bitmap.height}"
                } else {
                    currentAlbumArtImageView
                        .setImageResource(android.R.drawable.ic_media_play)
                    currentAlbumArtTextView.text = "封面：未找到"
                }
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
                currentAlbumArtImageView.setImageResource(android.R.drawable.ic_media_play)
                    currentAlbumArtTextView.text = "封面：加载中..."
                refreshCurrentAlbumArtForSong(songKey)
            }
        }

        currentSongTextView.text = "歌曲名：$safeTitle\n歌手：$safeArtist\n专辑：$safeAlbum"
            .replace("歌曲名：-\n歌手：-\n专辑：-", "歌曲：未检测")
        val lyricText = lyric.ifBlank { "暂无歌词" }
        val statusText = lyricStatus.ifBlank { "unknown" }
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
                    currentAlbumArtImageView.setImageBitmap(albumArt.bitmap)
                    currentAlbumArtTextView.text =
                        "封面：${albumArt.bitmap.width} x ${albumArt.bitmap.height}"
                    appendLog(
                        "[PlayerUI] album art changed exists=true " +
                            "width=${albumArt.bitmap.width} " +
                            "height=${albumArt.bitmap.height}"
                    )
                } else {
                    currentAlbumArtImageView
                        .setImageResource(android.R.drawable.ic_media_play)
                    currentAlbumArtTextView.text = "封面：未找到"
                    appendLog("[PlayerUI] album art changed exists=false")
                }
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
        val cancelled = QrcMaintenanceCoordinator.cancelCurrent(
            "debug button",
            ::appendThreadSafeLog
        )
        qrcV2RebuildManager?.stop()
        qrcStaleCacheRebuildManager?.stop()
        if (QrcMaintenanceCoordinator.currentToken()?.type == com.example.playeragent.media.MaintenanceTaskType.ARTWORK_DISCOVERY) {
            artworkDiscoveryManager?.cancel()
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
            if (::qrcCacheBuildTextView.isInitialized) {
                qrcCacheBuildTextView.text = progress.displayText()
            }
            refreshQrcCacheOverview()
        }
    }

    private fun updateQrcStaleCacheRebuildProgress(progress: QrcStaleCacheRebuildProgress) {
        runOnUiThread {
            if (::qrcStaleCacheRebuildTextView.isInitialized) {
                qrcStaleCacheRebuildTextView.text = progress.displayText()
            }
            refreshQrcCacheOverview()
        }
    }

    private fun updateQrcWatcherStatus(status: QrcWatcherStatus) {
        lastQrcWatcherStatus = status
        runOnUiThread {
            if (::qrcWatcherStatusTextView.isInitialized) {
                qrcWatcherStatusTextView.text = status.displayText()
            }
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
            Thread.sleep(1_000L)
            runOnUiThread {
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
        appendLog("[UI] debug stats load scheduled")
        mainHandler.postDelayed({
            loadDebugStatsAsync(force)
        }, DEBUG_STATS_INITIAL_DELAY_MS)
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
        if (::qrcCacheOverviewTextView.isInitialized) {
            qrcCacheOverviewTextView.text = "缓存统计：loading..."
        }
        if (::lyricCacheStatsTextView.isInitialized) {
            lyricCacheStatsTextView.text = "歌词统计：loading..."
        }
        Thread {
            val startedAt = System.currentTimeMillis()
            appendThreadSafeLog("[DebugStats] load start")
            val result = runCatching {
                DebugStatsText(
                    qrcOverview = qrcCacheOverviewText(),
                    lyricStats = lyricCacheStatsText(),
                    maintenance = QrcMaintenanceCoordinator.snapshot().displayText()
                )
            }
            runOnUiThread {
                debugStatsLoading = false
                result.onSuccess { stats ->
                    if (::qrcCacheOverviewTextView.isInitialized) {
                        qrcCacheOverviewTextView.text = stats.qrcOverview
                    }
                    if (::lyricCacheStatsTextView.isInitialized) {
                        lyricCacheStatsTextView.text = stats.lyricStats
                    }
                    if (::maintenanceStatusTextView.isInitialized) {
                        maintenanceStatusTextView.text = stats.maintenance
                    }
                    appendLog("[DebugStats] load done costMs=${System.currentTimeMillis() - startedAt}")
                }.onFailure { exception ->
                    if (::qrcCacheOverviewTextView.isInitialized) {
                        qrcCacheOverviewTextView.text = "缓存统计：加载失败 ${exception.message}"
                    }
                    if (::lyricCacheStatsTextView.isInitialized) {
                        lyricCacheStatsTextView.text = "歌词统计：加载失败 ${exception.message}"
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
        if (::maintenanceStatusTextView.isInitialized) {
            maintenanceStatusTextView.text = QrcMaintenanceCoordinator.snapshot().displayText()
        }
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

    private fun requestRequiredPermissions() {
        ensureRequiredPermissions()
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
        val lyricStats: String,
        val maintenance: String
    )

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_EXPORT_LOG = 1002
        private const val DEBUG_STATS_INITIAL_DELAY_MS = 700L
        private const val DEBUG_STATS_THROTTLE_MS = 2_000L
    }
}
