// SPDX-License-Identifier: GPL-3.0-only
package com.musicblecontroller.phoneaudiolab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Native control and disposal run on the same queue; PCM remains entirely in the native engine. */
class NativeAirPlayReceiver(
    private val context: Context,
    private val token: AudioSessionToken,
    private val events: AudioReceiverEvents,
    private val queue: AudioTaskQueue,
) : AudioReceiverDriver {
    private val closed = AtomicBoolean(false)
    private var handle = 0L
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var network: Network? = null
    private var networkRegistered = false
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private val registrationCount = AtomicInteger(0)
    private val connections = AtomicInteger(0)
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    @Volatile private var audioRunning = false
    private val rejectedVideo = AtomicBoolean(false)
    private val debugBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)

    private fun valid(): Boolean = !closed.get() && token.isCurrent()
    private fun effect(action: () -> Unit) = queue.submit {
        if (valid() && handle != 0L) action()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // No implicit resume after a call/another player takes focus.
        if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (valid()) events.failed("audio_focus_lost")
        }
    }
    private val networkListener = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) {
            if (lost == network && valid()) events.failed("network_lost")
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            effect {
                if (audioRunning && NativeBridge.nativeServerAudioDebug(handle, debugBuffer)) {
                    debugBuffer.rewind()
                    val backlog = debugBuffer.short.toInt() and 0xffff
                    val cushion = debugBuffer.short.toInt() and 0xffff
                    debugBuffer.position(20)
                    val mean = debugBuffer.int
                    debugBuffer.position(34)
                    val errors = debugBuffer.int
                    events.metrics(AudioMetrics(backlog, cushion, mean, errors))
                }
            }
            if (valid() && audioRunning) main.postDelayed(this, 1000)
        }
    }
    private val deadline = Runnable { if (valid()) events.failed("experiment_time_limit") }

    override fun start() {
        if (!valid()) return
        val selected = connectivity.activeNetwork ?: error("no_local_network")
        val caps = connectivity.getNetworkCapabilities(selected) ?: error("no_local_network")
        check(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) { "local_network_required" }
        network = selected
        if (!valid()) return
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        multicast = wifi.createMulticastLock("phone_audio_lab").apply {
            setReferenceCounted(false)
            acquire()
        }
        wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phoneaudiolab:receiver")
            .apply { acquire(SESSION_LIMIT_MS + 10_000) }
        if (!valid()) return
        NativeBridge.nativeSetDefaultStreamValues(
            audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0)
        handle = NativeBridge.nativeInit(callbacks, address(), DEVICE_NAME,
            context.filesDir.resolve("airplay.pem").absolutePath, false, true)
        check(handle != 0L) { "native_init_failed" }
        if (!valid()) return
        NativeBridge.nativeSetH265Enabled(handle, false)
        NativeBridge.nativeSetCodecs(handle, true, true)
        NativeBridge.nativeSetHlsEnabled(handle, false)
        NativeBridge.nativeSetAudioEnabled(handle, true)
        check(NativeBridge.nativeServerAudioConfigure(handle, 40, 95, 0, true, true, true, false)) {
            "audio_configuration_failed"
        }
        if (!valid()) return
        val port = NativeBridge.nativeStart(handle, 7000)
        check(port > 0) { "native_start_failed" }
        if (!valid()) return
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_ETHERNET)
            .build(), networkListener)
        networkRegistered = true
        register("_raop._tcp", NativeBridge.nativeGetRaopServiceName(handle) ?: error("raop_name_missing"),
            port, NativeBridge.nativeGetRaopTxtRecords(handle) ?: error("raop_txt_missing"))
        if (!valid()) return
        register("_airplay._tcp", DEVICE_NAME, port,
            NativeBridge.nativeGetAirplayTxtRecords(handle) ?: error("airplay_txt_missing"))
        main.postDelayed(deadline, SESSION_LIMIT_MS)
    }

    private fun register(type: String, name: String, port: Int, txt: Map<String, String>) {
        if (!valid()) return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            txt.forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Registration may finish after close/unregister; remove that ghost advertisement too.
                if (!valid()) {
                    runCatching { nsd.unregisterService(this) }
                    return
                }
                if (registrationCount.incrementAndGet() == 2) events.ready()
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                if (valid()) events.failed("discovery_failed")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registrations.add(listener)
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun acquireFocus(): Boolean {
        if (focusHeld) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusListener, main).build()
        focusRequest = request
        val result = audio.requestAudioFocus(request)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        main.removeCallbacks(poll)
        main.removeCallbacks(deadline)
        registrations.forEach { runCatching { nsd.unregisterService(it) } }
        registrations.clear()
        if (networkRegistered) runCatching { connectivity.unregisterNetworkCallback(networkListener) }
        networkRegistered = false
        try {
            if (handle != 0L) {
                // Stop joins native RAOP threads before destroy frees the decoder and callback reference.
                try { NativeBridge.nativeStop(handle) }
                finally { NativeBridge.nativeDestroy(handle); handle = 0L }
            }
        } finally {
            audioRunning = false
            if (focusHeld) {
                focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            }
            focusHeld = false
            if (multicast?.isHeld == true) multicast?.release()
            multicast = null
            if (wake?.isHeld == true) wake?.release()
            wake = null
        }
    }

    private fun address(): ByteArray {
        val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
        val existing = prefs.getString("address", null)
        if (existing != null && existing.matches(Regex("[0-9a-f]{12}"))) {
            return existing.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 2).toByte()
        prefs.edit().putString("address", bytes.joinToString("") { "%02x".format(it) }).apply()
        return bytes
    }

    private fun rejectVideo() {
        if (valid() && rejectedVideo.compareAndSet(false, true))
            events.failed("screen_mirroring_not_supported")
    }

    private val callbacks = object : RaopCallbackHandler, LogListener {
        override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
            if (usingScreen) { rejectVideo(); return }
            effect {
                if (!acquireFocus()) { events.failed("audio_focus_unavailable"); return@effect }
                NativeBridge.nativeServerAudioFormat(handle, ct, spf)
                if (!NativeBridge.nativeServerAudioStart(handle)) {
                    events.failed("audio_output_failed"); return@effect
                }
                audioRunning = true
                events.audioReady()
                main.removeCallbacks(poll)
                main.post(poll)
            }
        }
        override fun onAudioTeardown() = effect {
            audioRunning = false
            main.removeCallbacks(poll)
            NativeBridge.nativeServerAudioStop(handle)
            events.silent()
        }
        override fun onConnectionInit() {
            if (valid()) { connections.incrementAndGet(); events.connected() }
        }
        override fun onConnectionDestroy() {
            if (!valid()) return
            if (connections.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) effect {
                audioRunning = false
                main.removeCallbacks(poll)
                NativeBridge.nativeServerAudioStop(handle)
                events.disconnected()
            }
        }
        override fun onConnectionReset(reason: Int) {
            if (valid()) events.failed("sender_connection_reset")
        }
        override fun onDisplayPin(pin: String) { if (valid()) events.pin(pin) }
        // Keep Sony's existing output and hardware volume; do not alter QQ or global media volume.
        override fun onVolumeChange(volume: Float) {}
        override fun onClientVolume(): Float {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            return if (volume == 0) -144f else -30f + 30f * volume / max
        }
        override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) = rejectVideo()
        override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) = rejectVideo()
        override fun onMirrorRunning(running: Boolean) { if (running) rejectVideo() }
        override fun onVideoPlay(location: String, startPositionSeconds: Float) = rejectVideo()
        override fun onVideoScrub(positionSeconds: Float) {}
        override fun onVideoRate(rate: Float) {}
        override fun onVideoStop() {}
        override fun onVideoSessionPoll() {}
        override fun onMetadata(data: ByteArray) {}
        override fun onCoverArt(data: ByteArray) {}
        override fun onProgress(start: Long, curr: Long, end: Long) {}
        override fun onDacpId(dacpId: String, activeRemote: String) {}
        // Do not retain native logs, media titles, URLs, or samples in app files.
        override fun onLog(msg: String) {}
    }

    companion object {
        const val DEVICE_NAME = "Sony Phone Audio Lab"
        private const val SESSION_LIMIT_MS = 15 * 60 * 1000L
    }
}
