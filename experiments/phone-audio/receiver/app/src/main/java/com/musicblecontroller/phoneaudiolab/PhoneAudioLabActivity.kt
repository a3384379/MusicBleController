// SPDX-License-Identifier: GPL-3.0-only
package com.musicblecontroller.phoneaudiolab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class PhoneAudioLabActivity : Activity() {
    private var binder: PhoneAudioReceiverService.LocalBinder? = null
    private var bound = false
    private var permissionStartPending = false
    private lateinit var status: TextView
    private lateinit var pairing: TextView
    private lateinit var metrics: TextView
    private lateinit var failure: TextView
    private lateinit var paused: CheckBox
    private lateinit var start: Button
    private lateinit var stop: Button
    private val observer: () -> Unit = { render() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? PhoneAudioReceiverService.LocalBinder
            binder?.observe(observer)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(resource: Int, size: Float = 16f): TextView = TextView(this).apply {
            setText(resource); textSize = size; setPadding(0, 8, 0, 16)
            content.addView(this)
        }
        text(R.string.title, 24f)
        text(R.string.instructions)
        status = text(R.string.off, 20f)
        pairing = text(R.string.off).apply { visibility = View.GONE }
        metrics = text(R.string.off).apply { visibility = View.GONE }
        failure = text(R.string.off).apply { visibility = View.GONE }
        paused = CheckBox(this).apply {
            setText(R.string.music_paused)
            setOnCheckedChangeListener { _, _ -> render() }
        }
        content.addView(paused)
        start = Button(this).apply {
            setText(R.string.start)
            isEnabled = false
            setOnClickListener { requestStart() }
        }
        content.addView(start)
        stop = Button(this).apply {
            setText(R.string.stop)
            isEnabled = false
            setOnClickListener {
                permissionStartPending = false
                AlertDialog.Builder(this@PhoneAudioLabActivity)
                    .setTitle(R.string.stop_title).setMessage(R.string.stop_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.confirm_stop) { _, _ ->
                        startService(Intent(this@PhoneAudioLabActivity, PhoneAudioReceiverService::class.java)
                            .setAction(PhoneAudioReceiverService.ACTION_DISABLE))
                        paused.isChecked = false
                    }.show()
            }
        }
        content.addView(stop)
        text(R.string.boundary)
        val scroll = ScrollView(this).apply {
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        scroll.post {
            scroll.requestApplyInsets()
            val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(appearance, appearance)
        }
        bound = bindService(Intent(this, PhoneAudioReceiverService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun requestStart() {
        if (binder == null || !paused.isChecked) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionStartPending = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            return
        }
        launchReceiver()
    }

    private fun launchReceiver() {
        try {
            val intent = Intent(this, PhoneAudioReceiverService::class.java)
                .setAction(PhoneAudioReceiverService.ACTION_ENABLE)
            startForegroundService(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != NOTIFICATION_REQUEST) return
        val requested = permissionStartPending
        permissionStartPending = false
        if (requested && !isFinishing && paused.isChecked && binder != null &&
            results.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchReceiver()
    }

    private fun render() {
        if (!::start.isInitialized) return
        val snapshot = binder?.snapshot() ?: AudioSnapshot()
        val idle = snapshot.phase in setOf(AudioPhase.OFF, AudioPhase.FAILED, AudioPhase.INTERRUPTED)
        start.isEnabled = binder != null && idle && paused.isChecked
        stop.isEnabled = binder != null && !idle
        paused.isEnabled = idle
        status.setText(when (snapshot.phase) {
            AudioPhase.OFF -> R.string.off
            AudioPhase.PREPARING -> R.string.preparing
            AudioPhase.WAITING -> R.string.waiting
            AudioPhase.CONNECTED -> R.string.connected
            AudioPhase.PROCESSING -> R.string.processing
            AudioPhase.SILENT -> R.string.silent
            AudioPhase.STOPPING -> R.string.stopping
            AudioPhase.FAILED -> R.string.failed
            AudioPhase.INTERRUPTED -> R.string.interrupted
        })
        pairing.visibility = if (snapshot.pin == null) View.GONE else View.VISIBLE
        pairing.text = getString(R.string.pairing_code, snapshot.pin ?: "")
        val value = snapshot.metrics
        metrics.visibility = if (value == null) View.GONE else View.VISIBLE
        if (value != null) metrics.text = getString(R.string.metrics, value.backlogMs, value.cushionMs, value.decodeErrors)
        failure.visibility = if (snapshot.failure == null) View.GONE else View.VISIBLE
        // A readable recovery message, with internal error codes kept out of the user flow.
        failure.setText(when (snapshot.failure) {
            "audio_focus_lost", "sender_connection_reset", "network_lost" -> R.string.failure_interruption
            "audio_focus_unavailable" -> R.string.failure_focus
            "screen_mirroring_not_supported" -> R.string.failure_mirroring
            "experiment_time_limit" -> R.string.failure_deadline
            else -> R.string.failure_start
        })
    }

    override fun onDestroy() {
        permissionStartPending = false
        binder?.removeObserver(observer)
        if (bound) unbindService(connection)
        binder = null
        super.onDestroy()
    }

    companion object { private const val NOTIFICATION_REQUEST = 1 }
}
