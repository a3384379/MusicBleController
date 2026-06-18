package com.example.playeragent

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.playeragent.ble.ControllerScannerManager
import com.example.playeragent.classicbluetooth.RfcommClientManager
import com.example.playeragent.logging.LogBuffer
import com.example.playeragent.media.AlbumArtTestManager
import com.example.playeragent.media.CurrentLyricProbe
import com.example.playeragent.media.LrcDebugManager
import com.example.playeragent.media.LyricManager
import com.example.playeragent.media.LyricSourceTestManager
import com.example.playeragent.media.PlaybackStateReader
import com.example.playeragent.media.QrcDumpManager
import com.example.playeragent.media.QrcLyricCacheManager
import com.example.playeragent.media.QrcLyricPrebuildManager
import com.example.playeragent.media.QrcPersistentIndexManager
import com.example.playeragent.media.QrcPrebuildProgress
import com.example.playeragent.media.QrcWatcherStatus
import com.example.playeragent.service.PlayerAgentForegroundService
import com.example.playeragent.service.QQMusicLyricAccessibilityService

class MainActivity : Activity() {

    private lateinit var statusTextView: TextView
    private lateinit var accessibilityStatusTextView: TextView
    private lateinit var currentSongTextView: TextView
    private lateinit var currentLyricTextView: TextView
    private lateinit var currentAlbumArtImageView: ImageView
    private lateinit var currentAlbumArtTextView: TextView
    private lateinit var debugPanel: LinearLayout
    private lateinit var debugToggleButton: Button
    private lateinit var qrcCacheBuildTextView: TextView
    private lateinit var qrcWatcherStatusTextView: TextView
    private lateinit var lyricCacheStatsTextView: TextView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var logToggleButton: Button
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var controllerScannerManager: ControllerScannerManager? = null
    private var rfcommClientManager: RfcommClientManager? = null
    private var qrcLyricPrebuildManager: QrcLyricPrebuildManager? = null
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
                    statusTextView.text = "BLE 服务运行中"

                message.contains("Foreground service stopped") ->
                    statusTextView.text = "BLE 服务已停止"

                message.startsWith("[AccessibilityLyric] candidate=") -> {
                    val candidate =
                        QQMusicLyricAccessibilityService.latestCandidateLyric
                    if (candidate.isNotBlank()) {
                        currentLyricTextView.text = candidate
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()
        initializeBluetooth()
        requestRequiredPermissions()
        appendLog("[PlayerAgent] UI ready")
        refreshCurrentMedia()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(PlayerAgentForegroundService.ACTION_LOG)
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
        qrcLyricPrebuildManager?.stop()
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
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        content.addView(TextView(this).apply {
            text = "PlayerAgent"
            textSize = 26f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(12))
        })

        content.addView(actionButton("START PLAYERAGENT BLE SERVICE") {
            startPlayerAgentService()
        })
        content.addView(actionButton("STOP PLAYERAGENT BLE SERVICE") {
            stopPlayerAgentService()
        })

        statusTextView = valueSection(
            content,
            title = "Current Status",
            initialValue = "未启动"
        )
        accessibilityStatusTextView = valueSection(
            content,
            title = "Accessibility状态",
            initialValue = "disabled"
        )
        content.addView(
            actionButton(
                "OPEN ACCESSIBILITY SETTINGS",
                ::openAccessibilitySettings
            )
        )
        currentSongTextView = valueSection(
            content,
            title = "Current Song",
            initialValue = "未检测"
        )
        currentLyricTextView = valueSection(
            content,
            title = "Current Lyric",
            initialValue = "未检测"
        )

        content.addView(sectionTitle("Current Album Art"))
        currentAlbumArtImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(238, 238, 238))
            setImageResource(android.R.drawable.ic_media_play)
        }
        content.addView(
            currentAlbumArtImageView,
            LinearLayout.LayoutParams(dp(160), dp(160)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        currentAlbumArtTextView = TextView(this).apply {
            text = "未检测"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        }
        content.addView(currentAlbumArtTextView)

        debugToggleButton = actionButton("DEBUG TOOLS") {
            val expanded = debugPanel.visibility != View.VISIBLE
            debugPanel.visibility = if (expanded) View.VISIBLE else View.GONE
            debugToggleButton.text =
                if (expanded) "HIDE DEBUG TOOLS" else "DEBUG TOOLS"
        }
        content.addView(debugToggleButton)

        debugPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(245, 245, 245))
        }
        qrcCacheBuildTextView = TextView(this).apply {
            text = QrcPrebuildProgress(
                running = false,
                total = 0,
                processed = 0,
                success = 0,
                failed = 0,
                skipped = 0
            ).displayText()
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        debugPanel.addView(qrcCacheBuildTextView)
        qrcWatcherStatusTextView = TextView(this).apply {
            text = QrcWatcherStatus(
                watcherRunning = false,
                pendingGroups = 0,
                incrementalRunning = false,
                incrementalSuccess = 0,
                incrementalFailed = 0,
                incrementalSkipped = 0
            ).displayText()
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        debugPanel.addView(qrcWatcherStatusTextView)
        lyricCacheStatsTextView = TextView(this).apply {
            text = lyricCacheStatsText()
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        debugPanel.addView(lyricCacheStatsTextView)
        addDebugButtons(debugPanel)
        content.addView(debugPanel)

        logToggleButton = actionButton("SHOW LOGS") {
            val expanded = logScrollView.visibility != View.VISIBLE
            logScrollView.visibility = if (expanded) View.VISIBLE else View.GONE
            logToggleButton.text = if (expanded) "HIDE LOGS" else "SHOW LOGS"
        }
        content.addView(logToggleButton)

        logTextView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        logScrollView = ScrollView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.rgb(245, 245, 245))
            addView(logTextView)
        }
        content.addView(
            logScrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(260)
            )
        )

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun addDebugButtons(panel: LinearLayout) {
        panel.addView(actionButton("SCAN CONTROLLER", ::startControllerScan))
        panel.addView(actionButton("CONNECT RFCOMM SERVER", ::connectRfcommServer))
        panel.addView(actionButton("SCAN LRC FILES", ::scanLrcFiles))
        panel.addView(actionButton("TEST ALBUM ART", ::testAlbumArt))
        panel.addView(actionButton("TEST LYRIC SOURCE", ::testLyricSource))
        panel.addView(actionButton("TEST CURRENT LYRIC", ::testCurrentLyric))
        panel.addView(actionButton("TEST LRC DEBUG", ::testLrcDebug))
        panel.addView(actionButton("DUMP FIRST QRC", ::dumpFirstQrc))
        panel.addView(actionButton("START QRC CACHE BUILD", ::startQrcCacheBuild))
        panel.addView(actionButton("STOP QRC CACHE BUILD", ::stopQrcCacheBuild))
        panel.addView(actionButton("START QRC WATCHER", ::startQrcWatcher))
        panel.addView(actionButton("STOP QRC WATCHER", ::stopQrcWatcher))
        panel.addView(actionButton("REFRESH LYRIC STATS", ::refreshLyricStats))
        panel.addView(actionButton("RESET LYRIC STATS", ::resetLyricStats))
        panel.addView(
            actionButton(
                "FORCE REFRESH QRC INDEX",
                ::forceRefreshQrcIndex
            )
        )
        panel.addView(
            actionButton(
                "DUMP ACCESSIBILITY TREE",
                ::dumpAccessibilityTree
            )
        )
        panel.addView(
            actionButton(
                "OPEN NOTIFICATION ACCESS SETTINGS",
                ::openNotificationAccessSettings
            )
        )
        panel.addView(actionButton("EXPORT LOG", ::exportLog))
        panel.addView(actionButton("CLEAR LOG", ::clearLog))
    }

    private fun actionButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun valueSection(
        parent: LinearLayout,
        title: String,
        initialValue: String
    ): TextView {
        parent.addView(sectionTitle(title))
        return TextView(this).apply {
            text = initialValue
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(14))
            setBackgroundColor(Color.rgb(245, 245, 245))
            parent.addView(this)
        }
    }

    private fun sectionTitle(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(14), 0, dp(5))
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
        statusTextView.text = "BLE 服务启动请求已发送"
        appendLog("[PlayerAgent] Start service requested")
    }

    private fun stopPlayerAgentService() {
        stopService(Intent(this, PlayerAgentForegroundService::class.java))
        statusTextView.text = "BLE 服务已停止"
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
                    val title = state.optString("title")
                    val artist = state.optString("artist")
                    val album = state.optString("album")
                    currentSongTextView.text = listOf(title, artist, album)
                        .filter(String::isNotBlank)
                        .joinToString("\n")
                        .ifBlank { "未检测到当前歌曲" }
                    currentLyricTextView.text =
                        state.optString("lyric").ifBlank { "暂无歌词" }
                }

                if (albumArt != null) {
                    currentAlbumArtImageView.setImageBitmap(albumArt.bitmap)
                    currentAlbumArtTextView.text =
                        "${albumArt.bitmap.width} x ${albumArt.bitmap.height}"
                } else {
                    currentAlbumArtImageView
                        .setImageResource(android.R.drawable.ic_media_play)
                    currentAlbumArtTextView.text = "未找到"
                }
            }
        }.apply {
            name = "MainStatusRefreshThread"
            start()
        }
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
        currentLyricTextView.text = "探测中..."
        Thread {
            try {
                val result = CurrentLyricProbe.probeCurrentLyric(this)
                runOnUiThread {
                    currentLyricTextView.text =
                        result.lyric.ifBlank { "未找到" } +
                            "\nSource: ${result.source}\n${result.detail}"
                }
                appendThreadSafeLog(
                    "[LyricProbe] result lyric=${result.lyric.ifBlank { "未找到" }} " +
                        "source=${result.source}"
                )
            } catch (exception: Exception) {
                runOnUiThread {
                    currentLyricTextView.text = "未找到\n${exception.message}"
                }
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

    private fun startQrcCacheBuild() {
        if (!ensureLyricStorageAccess()) {
            return
        }
        val manager = qrcLyricPrebuildManager ?: QrcLyricPrebuildManager(
            context = this,
            logger = ::appendThreadSafeLog,
            progressListener = ::updateQrcCacheBuildProgress
        ).also {
            qrcLyricPrebuildManager = it
        }
        manager.start()
    }

    private fun stopQrcCacheBuild() {
        qrcLyricPrebuildManager?.stop()
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

    private fun updateQrcCacheBuildProgress(progress: QrcPrebuildProgress) {
        runOnUiThread {
            if (::qrcCacheBuildTextView.isInitialized) {
                qrcCacheBuildTextView.text = progress.displayText()
            }
        }
    }

    private fun updateQrcWatcherStatus(status: QrcWatcherStatus) {
        lastQrcWatcherStatus = status
        runOnUiThread {
            if (::qrcWatcherStatusTextView.isInitialized) {
                qrcWatcherStatusTextView.text = status.displayText()
            }
            refreshLyricStatsText()
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

    private fun refreshLyricStatsText() {
        if (::lyricCacheStatsTextView.isInitialized) {
            lyricCacheStatsTextView.text = lyricCacheStatsText()
        }
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
        return "Lyric Cache Stats\n" +
            "L1 hit: ${stats.l1Hit}\n" +
            "L2 hit: ${stats.l2Hit}\n" +
            "Fuzzy hit: ${stats.l2FuzzyHit}\n" +
            "Alias hit: ${stats.aliasHit}\n" +
            "Negative hit: ${stats.negativeHit}\n" +
            "QRC decrypt count: ${stats.qrcDecryptCount}\n" +
            "QRC decrypt success: ${stats.qrcDecryptSuccess}\n" +
            "QRC decrypt failed: ${stats.qrcDecryptFailed}\n" +
            "Last source: ${stats.lastSource}\n" +
            "QrcIndex loaded: ${indexStatus.loaded}\n" +
            "QrcIndex dirty: ${indexStatus.dirty}\n" +
            "QrcIndex entries: ${indexStatus.entries}\n" +
            "QrcIndex builtAt: ${indexStatus.builtAt}\n" +
            "QrcWatcher running: ${lastQrcWatcherStatus.watcherRunning}\n" +
            "QrcWatcher pending groups: ${lastQrcWatcherStatus.pendingGroups}\n" +
            "QrcIncremental success: ${lastQrcWatcherStatus.incrementalSuccess}\n" +
            "QrcIncremental failed: ${lastQrcWatcherStatus.incrementalFailed}"
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
            connected -> "enabled"
            enabled -> "enabled（等待服务连接）"
            else -> "disabled"
        }

        val candidate =
            QQMusicLyricAccessibilityService.latestCandidateLyric
        if (candidate.isNotBlank()) {
            currentLyricTextView.text = candidate
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

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_EXPORT_LOG = 1002
    }
}
