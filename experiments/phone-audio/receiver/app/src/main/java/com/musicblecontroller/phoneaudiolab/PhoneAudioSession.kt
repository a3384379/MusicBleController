// SPDX-License-Identifier: GPL-3.0-only
package com.musicblecontroller.phoneaudiolab

enum class AudioPhase { OFF, PREPARING, WAITING, CONNECTED, PROCESSING, SILENT, STOPPING, FAILED, INTERRUPTED }

data class AudioMetrics(
    val backlogMs: Int = 0,
    val cushionMs: Int = 0,
    val decodeMeanUs: Int = 0,
    val decodeErrors: Int = 0,
)

data class AudioSnapshot(
    val generation: Long = 0,
    val phase: AudioPhase = AudioPhase.OFF,
    val pin: String? = null,
    val metrics: AudioMetrics? = null,
    val failure: String? = null,
)

fun interface AudioTaskQueue { fun submit(task: () -> Unit) }

interface AudioReceiverDriver {
    fun start()
    /** Release is idempotent, including before start or after a partial start. */
    fun close()
}

class AudioSessionToken(val generation: Long, private val check: () -> Boolean) {
    fun isCurrent(): Boolean = check()
}

interface AudioReceiverEvents {
    fun ready()
    fun pin(code: String)
    fun connected()
    fun audioReady()
    fun metrics(value: AudioMetrics)
    fun silent()
    fun disconnected()
    fun failed(reason: String)
}

/** Serial driver effects; generation checks also fence callbacks arriving on native/NSD threads. */
class PhoneAudioSession(
    private val queue: AudioTaskQueue,
    private val factory: (AudioSessionToken, AudioReceiverEvents) -> AudioReceiverDriver,
    private val changed: () -> Unit,
) {
    private val lock = Any()
    private var generation = 0L
    private var desired = false
    private var driver: AudioReceiverDriver? = null
    private var value = AudioSnapshot()

    fun snapshot(): AudioSnapshot = synchronized(lock) { value }
    fun isEnabled(): Boolean = synchronized(lock) { desired }

    fun start() {
        val epoch = synchronized(lock) {
            if (desired) return
            desired = true
            generation += 1
            value = AudioSnapshot(generation, AudioPhase.PREPARING)
            generation
        }
        changed()
        val token = AudioSessionToken(epoch) { synchronized(lock) { desired && generation == epoch } }
        val events = object : AudioReceiverEvents {
            override fun ready() = update(epoch) {
                if (it.phase == AudioPhase.PREPARING) it.copy(phase = AudioPhase.WAITING) else it
            }
            override fun pin(code: String) = update(epoch) { it.copy(pin = code) }
            override fun connected() = update(epoch) {
                if (it.phase in setOf(AudioPhase.PREPARING, AudioPhase.WAITING))
                    it.copy(phase = AudioPhase.CONNECTED) else it
            }
            override fun audioReady() = update(epoch) {
                it.copy(phase = AudioPhase.CONNECTED, pin = null, metrics = null)
            }
            override fun metrics(value: AudioMetrics) = update(epoch) {
                // Upstream timing counters show decoder activity, not audible output or lip sync.
                val processing = value.decodeMeanUs > 0 && it.phase != AudioPhase.SILENT
                it.copy(phase = if (processing) AudioPhase.PROCESSING else it.phase,
                    pin = if (processing) null else it.pin, metrics = value)
            }
            override fun silent() = update(epoch) { it.copy(phase = AudioPhase.SILENT) }
            override fun disconnected() = update(epoch) {
                it.copy(phase = AudioPhase.WAITING, pin = null, metrics = null)
            }
            override fun failed(reason: String) { fail(epoch, reason) }
        }
        queue.submit {
            if (!token.isCurrent()) return@submit
            var created: AudioReceiverDriver? = null
            try {
                created = factory(token, events)
                synchronized(lock) { if (token.isCurrent()) driver = created }
                if (token.isCurrent()) created.start()
                if (!token.isCurrent()) created.close()
            } catch (_: Exception) {
                created?.close()
                fail(epoch, "receiver_start_failed")
            } catch (_: LinkageError) {
                created?.close()
                fail(epoch, "native_engine_unavailable")
            }
        }
    }

    /** No pending start/connection/pairing callback may turn the session on after this returns. */
    fun stop(reason: String? = null) {
        val (stopEpoch, closing) = synchronized(lock) {
            if (!desired && value.phase == AudioPhase.OFF && reason == null) return
            desired = false
            generation += 1
            value = AudioSnapshot(generation, AudioPhase.STOPPING, failure = reason)
            generation to driver
        }
        changed()
        queue.submit {
            closing?.close()
            synchronized(lock) {
                if (driver === closing) driver = null
                if (!desired && generation == stopEpoch) {
                    value = AudioSnapshot(stopEpoch,
                        if (reason == null) AudioPhase.OFF else AudioPhase.INTERRUPTED,
                        failure = reason)
                }
            }
            changed()
        }
    }

    private fun update(epoch: Long, transform: (AudioSnapshot) -> AudioSnapshot) {
        synchronized(lock) {
            if (!desired || generation != epoch) return
            value = transform(value)
        }
        changed()
    }

    private fun fail(epoch: Long, reason: String) {
        val closing = synchronized(lock) {
            if (!desired || generation != epoch) return
            desired = false
            generation += 1
            value = AudioSnapshot(generation, AudioPhase.FAILED, failure = reason)
            driver
        }
        changed()
        // Cleanup is independent of the failed publication epoch.
        queue.submit {
            closing?.close()
            synchronized(lock) { if (driver === closing) driver = null }
        }
    }
}
