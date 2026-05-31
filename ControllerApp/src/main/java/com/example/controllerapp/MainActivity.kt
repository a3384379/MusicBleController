package com.example.controllerapp

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.example.controllerapp.classicbluetooth.RfcommServerManager
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var connectionStateTextView: TextView
    private lateinit var albumArtImageView: ImageView
    private lateinit var titleTextView: TextView
    private lateinit var artistTextView: TextView
    private lateinit var albumTextView: TextView
    private lateinit var playbackStateTextView: TextView
    private lateinit var progressTextView: TextView
    private lateinit var lyricTextView: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var volumeTextView: TextView
    private lateinit var volumeProgressBar: ProgressBar
    private lateinit var drawerToggleButton: Button
    private lateinit var drawerLayout: LinearLayout
    private lateinit var logToggleButton: Button
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var rfcommServerManager: RfcommServerManager? = null
    private lateinit var albumArtReceiver: AlbumArtReceiver
    private val recentLogs = ArrayDeque<String>()
    private var currentDurationMs: Long = 0L
    private var isUserSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()
        initializeBluetooth()
        requestRequiredPermissions()
        updateConnectionState(CONNECTION_DISCONNECTED)
        log("UI ready")
        autoStartRfcommServer()
    }

    override fun onDestroy() {
        rfcommServerManager?.stopServer()
        super.onDestroy()
    }

    private fun setupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val contentScrollView = ScrollView(this).apply {
            addView(content)
        }

        connectionStateTextView = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            text = CONNECTION_DISCONNECTED
        }
        content.addView(connectionStateTextView, matchWrapParams())

        albumArtImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(createDefaultAlbumArt())
        }
        content.addView(
            albumArtImageView,
            LinearLayout.LayoutParams(dp(ALBUM_ART_SIZE_DP), dp(ALBUM_ART_SIZE_DP)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(20)
            }
        )

        titleTextView = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            maxLines = 2
            text = "Title"
        }
        content.addView(titleTextView, topMarginParams(dp(24)))

        artistTextView = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 1
            text = "Artist"
        }
        content.addView(artistTextView, topMarginParams(dp(8)))

        albumTextView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
            text = "Album"
        }
        content.addView(albumTextView, topMarginParams(dp(4)))

        playbackStateTextView = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            text = "Paused"
        }
        content.addView(playbackStateTextView, topMarginParams(dp(20)))

        progressTextView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            text = "00:00 / 00:00"
        }
        content.addView(progressTextView, topMarginParams(dp(8)))

        progressBar = SeekBar(this).apply {
            max = 1
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        progressTextView.text = "${formatMs(progress.toLong())} / ${formatMs(currentDurationMs)}"
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val targetPosition = seekBar?.progress?.toLong() ?: 0L
                    progressTextView.text = "${formatMs(targetPosition)} / ${formatMs(currentDurationMs)}"
                    isUserSeeking = false
                    sendSeekToCommand(targetPosition)
                }
            })
        }
        content.addView(progressBar, topMarginParams(dp(8)))

        lyricTextView = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            maxLines = 2
            text = NO_LYRIC_TEXT
        }
        content.addView(lyricTextView, topMarginParams(dp(18)))

        volumeTextView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            text = "Volume: -- / --"
        }
        content.addView(volumeTextView, topMarginParams(dp(14)))

        volumeProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1
            progress = 0
        }
        content.addView(volumeProgressBar, topMarginParams(dp(6)))

        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controlRow.addView(createButton("上一首") { sendRfcommCommand("PREVIOUS") }, rowButtonParams())
        controlRow.addView(createButton("播放/暂停") { sendRfcommCommand("PLAY_PAUSE") }, rowButtonParams())
        controlRow.addView(createButton("下一首") { sendRfcommCommand("NEXT") }, rowButtonParams())
        content.addView(controlRow, topMarginParams(dp(28)))

        val volumeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        volumeRow.addView(createButton("音量-") { sendRfcommCommand("VOLUME_DOWN") }, rowButtonParams())
        volumeRow.addView(createButton("音量+") { sendRfcommCommand("VOLUME_UP") }, rowButtonParams())
        content.addView(volumeRow, topMarginParams(dp(8)))

        drawerToggleButton = createButton("更多控制") { toggleDrawer() }

        drawerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val serverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        serverRow.addView(createButton("启动服务") { startRfcommServer() }, rowButtonParams())
        serverRow.addView(createButton("停止服务") { stopRfcommServer() }, rowButtonParams())
        drawerLayout.addView(serverRow, topMarginParams(dp(8)))

        val auxTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        auxTopRow.addView(createButton("获取播放状态") { sendPlaybackStateCommand() }, rowButtonParams())
        auxTopRow.addView(createButton("获取音量") { sendGetVolumeCommand() }, rowButtonParams())
        drawerLayout.addView(auxTopRow, topMarginParams(dp(8)))

        val auxBottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        auxBottomRow.addView(createButton("诊断音频") { sendAudioStreamDiagnosticCommand() }, rowButtonParams())
        drawerLayout.addView(auxBottomRow, topMarginParams(dp(8)))

        logTextView = TextView(this).apply {
            textSize = 12f
            text = ""
        }
        logScrollView = ScrollView(this).apply {
            addView(logTextView)
        }

        logToggleButton = createButton("隐藏日志") { toggleLogArea() }

        albumArtReceiver = AlbumArtReceiver(
            logger = ::log,
            onBitmapReady = ::showAlbumArt,
            onUnavailable = ::showDefaultAlbumArt
        )

        root.addView(
            contentScrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                weight = 1f
            }
        )
        root.addView(
            drawerToggleButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )
        root.addView(
            drawerLayout,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            logToggleButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )
        root.addView(
            logScrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(132)
            ).apply {
                topMargin = dp(12)
            }
        )

        setContentView(root)
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            log("BluetoothAdapter unavailable")
            return
        }

        log("Bluetooth initialized")
    }

    private fun startRfcommServer() {
        if (!ensureRequiredPermissions()) {
            log("Please grant Bluetooth permissions, then start RFCOMM server again")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            log("Cannot start RFCOMM server: Bluetooth unavailable")
            return
        }

        if (!adapter.isEnabled) {
            log("Cannot start RFCOMM server: Bluetooth is disabled")
            return
        }

        updateConnectionState(CONNECTION_WAITING)
        rfcommServerManager?.stopServer()
        rfcommServerManager = RfcommServerManager(
            bluetoothAdapter = adapter,
            logger = ::log,
            onMessageReceived = ::handleRfcommMessage
        )
        rfcommServerManager?.startServer()
    }

    private fun stopRfcommServer() {
        rfcommServerManager?.stopServer()
        rfcommServerManager = null
        updateConnectionState(CONNECTION_DISCONNECTED)
        log("RFCOMM server stopped")
    }

    private fun autoStartRfcommServer() {
        if (!hasRequiredPermissions()) {
            log("Auto start waiting for Bluetooth permissions")
            return
        }

        if (rfcommServerManager != null) {
            return
        }

        log("Auto start RFCOMM server")
        startRfcommServer()
    }

    private fun handleRfcommMessage(message: String) {
        try {
            val jsonObject = JSONObject(message)
            when (jsonObject.optString("type")) {
                "playbackState" -> updatePlaybackState(jsonObject)
                "volumeState" -> updateVolumeState(jsonObject)
                "audioStreamDiagnostic" -> logAudioStreamDiagnostic(jsonObject)
                "albumArtStart",
                "albumArtChunk",
                "albumArtEnd",
                "albumArtUnavailable" -> albumArtReceiver.handle(jsonObject)
            }
        } catch (exception: Exception) {
            log("Failed to parse RFCOMM message: ${exception.message}")
        }
    }

    private fun updateVolumeState(jsonObject: JSONObject) {
        runOnUiThread {
            val current = jsonObject.optInt("current")
            val max = jsonObject.optInt("max")
            volumeTextView.text = "Volume: $current / $max"
            volumeProgressBar.max = max.coerceAtLeast(1)
            volumeProgressBar.progress = current.coerceIn(0, volumeProgressBar.max)
            log("volumeState current=$current max=$max")
        }
    }

    private fun logAudioStreamDiagnostic(jsonObject: JSONObject) {
        val streams = jsonObject.optJSONArray("streams")
        if (streams == null) {
            log("Audio stream diagnostic response missing streams")
            return
        }

        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
            val name = stream.optString("stream")
            val error = stream.optString("error")

            if (error.isNotBlank()) {
                log("[AudioStreamDiagnostic] stream=$name error=$error")
            } else {
                log(
                    "[AudioStreamDiagnostic] stream=$name " +
                        "before=${stream.optInt("before")} " +
                        "max=${stream.optInt("max")} " +
                        "afterRaise=${stream.optInt("afterRaise")} " +
                        "afterLower=${stream.optInt("afterLower")}"
                )
            }
        }
    }

    private fun updatePlaybackState(jsonObject: JSONObject) {
        runOnUiThread {
            val title = jsonObject.optString("title")
            val artist = jsonObject.optString("artist")
            val album = jsonObject.optString("album")
            val playing = jsonObject.optBoolean("playing")
            val position = jsonObject.optLong("position")
            val duration = jsonObject.optLong("duration")
            val lyric = jsonObject.optString("lyric")
            currentDurationMs = duration.coerceAtLeast(0L)

            titleTextView.text = title.ifBlank { "Title" }
            artistTextView.text = artist.ifBlank { "Artist" }
            albumTextView.text = album.ifBlank { "Album" }
            playbackStateTextView.text = if (playing) "Playing" else "Paused"
            if (!isUserSeeking) {
                progressTextView.text = "${formatMs(position)} / ${formatMs(duration)}"
                progressBar.max = duration.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                progressBar.progress = position.coerceIn(0L, duration.coerceAtLeast(0L))
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            }
            lyricTextView.text = lyric.ifBlank { NO_LYRIC_TEXT }
        }
    }

    private fun showAlbumArt(bitmap: Bitmap) {
        runOnUiThread {
            albumArtImageView.setImageBitmap(bitmap)
        }
    }

    private fun showDefaultAlbumArt() {
        runOnUiThread {
            albumArtImageView.setImageBitmap(createDefaultAlbumArt())
        }
    }

    private fun sendRfcommCommand(command: String) {
        val manager = rfcommServerManager
        if (manager == null) {
            log("RFCOMM not connected")
            return
        }

        manager.sendCommand(command)
    }

    private fun sendGetVolumeCommand() {
        val manager = rfcommServerManager
        if (manager == null) {
            log("RFCOMM not connected")
            return
        }

        manager.sendGetVolume()
    }

    private fun sendAudioStreamDiagnosticCommand() {
        val manager = rfcommServerManager
        if (manager == null) {
            log("RFCOMM not connected")
            return
        }

        manager.sendAudioStreamDiagnostic()
    }

    private fun sendPlaybackStateCommand() {
        val manager = rfcommServerManager
        if (manager == null) {
            log("RFCOMM not connected")
            return
        }

        manager.sendPlaybackStateRequest()
    }

    private fun sendSeekToCommand(positionMs: Long) {
        val manager = rfcommServerManager
        if (manager == null) {
            log("RFCOMM not connected")
            return
        }

        val safePosition = positionMs.coerceAtLeast(0L)
        log("[Seek]\nuser seek to=$safePosition")
        manager.sendSeekTo(safePosition)
        log("[Seek]\ncommand sent")
    }

    private fun toggleDrawer() {
        val isOpen = drawerLayout.visibility == View.VISIBLE
        drawerLayout.visibility = if (isOpen) View.GONE else View.VISIBLE
        drawerToggleButton.text = if (isOpen) "更多控制" else "收起控制"
    }

    private fun toggleLogArea() {
        val isVisible = logScrollView.visibility == View.VISIBLE
        logScrollView.visibility = if (isVisible) View.GONE else View.VISIBLE
        logToggleButton.text = if (isVisible) "显示日志" else "隐藏日志"
    }

    private fun updateConnectionState(state: String) {
        runOnUiThread {
            connectionStateTextView.text = state
        }
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

    private fun requestRequiredPermissions() {
        ensureRequiredPermissions()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && hasRequiredPermissions()) {
            autoStartRfcommServer()
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }

        return permissions
    }

    private fun log(message: String) {
        when {
            message == "RFCOMM server listening" -> updateConnectionState(CONNECTION_WAITING)
            message == "Client connected" -> updateConnectionState(CONNECTION_CONNECTED)
            message.startsWith("RFCOMM server failed") -> updateConnectionState(CONNECTION_DISCONNECTED)
            message.startsWith("RFCOMM read stopped") -> updateConnectionState(CONNECTION_DISCONNECTED)
        }

        val fullMessage = "[ControllerApp] $message"
        Log.i(TAG, fullMessage)
        runOnUiThread {
            recentLogs.addLast(fullMessage)
            while (recentLogs.size > MAX_LOG_LINES) {
                recentLogs.removeFirst()
            }
            logTextView.text = recentLogs.joinToString("\n")
            logScrollView.postDelayed({
                logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }, LOG_SCROLL_DELAY_MS)
        }
    }

    private fun createButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener {
                onClick()
            }
        }
    }

    private fun matchWrapParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun topMarginParams(topMargin: Int): LinearLayout.LayoutParams {
        return matchWrapParams().apply {
            this.topMargin = topMargin
        }
    }

    private fun rowButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            weight = 1f
            leftMargin = dp(3)
            rightMargin = dp(3)
        }
    }

    private fun formatMs(value: Long): String {
        val safeValue = value.coerceAtLeast(0L)
        val totalSeconds = safeValue / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun createDefaultAlbumArt(): Bitmap {
        val size = dp(ALBUM_ART_SIZE_DP)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(72, 72, 72)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), backgroundPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = dp(18).toFloat()
        }
        val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("Album Art", size / 2f, y, textPaint)
        return bitmap
    }

    companion object {
        private const val TAG = "ControllerApp"
        private const val REQUEST_PERMISSIONS = 2001
        private const val MAX_LOG_LINES = 40
        private const val LOG_SCROLL_DELAY_MS = 50L
        private const val ALBUM_ART_SIZE_DP = 110
        private const val NO_LYRIC_TEXT = "暂无歌词"

        private const val CONNECTION_DISCONNECTED = "\u672a\u8fde\u63a5"
        private const val CONNECTION_WAITING = "\u7b49\u5f85\u8fde\u63a5"
        private const val CONNECTION_CONNECTED = "\u5df2\u8fde\u63a5"
    }
}
