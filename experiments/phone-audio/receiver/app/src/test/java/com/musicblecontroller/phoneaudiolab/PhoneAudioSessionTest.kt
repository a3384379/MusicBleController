// SPDX-License-Identifier: GPL-3.0-only
package com.musicblecontroller.phoneaudiolab

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class PhoneAudioSessionTest {
    private class Harness {
        val pending = ArrayDeque<() -> Unit>()
        val drivers = mutableListOf<Driver>()
        val queue = AudioTaskQueue { pending.add(it) }
        var onStart: (() -> Unit)? = null
        val session = PhoneAudioSession(queue, { token, events ->
            Driver(token, events, { onStart?.invoke() }).also { drivers.add(it) }
        }, {})
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().invoke() }
    }
    private class Driver(val token: AudioSessionToken, val events: AudioReceiverEvents,
                         private val duringStart: () -> Unit) : AudioReceiverDriver {
        var starts = 0
        var closes = 0
        private var closed = false
        override fun start() { starts++; duringStart() }
        override fun close() { if (!closed) { closed = true; closes++ } }
    }

    @Test fun defaultOffHasNoDriverOrWork() {
        val h = Harness()
        assertEquals(AudioPhase.OFF, h.session.snapshot().phase)
        assertFalse(h.session.isEnabled())
        assertTrue(h.pending.isEmpty())
        assertTrue(h.drivers.isEmpty())
    }
    @Test fun startThenImmediateStopNeverStartsReceiver() {
        val h = Harness()
        h.session.start(); h.session.stop(); h.drain()
        assertTrue(h.drivers.isEmpty())
        assertEquals(AudioPhase.OFF, h.session.snapshot().phase)
    }
    @Test fun repeatedStartDoesNotCreateSecondReceiver() {
        val h = Harness()
        repeat(20) { h.session.start() }; h.drain()
        assertEquals(1, h.drivers.size)
        assertEquals(1, h.drivers.single().starts)
    }
    @Test fun stopDuringNativeStartStillClosesPreparedResources() {
        val h = Harness()
        h.onStart = { h.session.stop() }
        h.session.start(); h.drain()
        assertEquals(1, h.drivers.single().closes)
        assertEquals(AudioPhase.OFF, h.session.snapshot().phase)
    }
    @Test fun lateReadyPairingAndMetricsCannotReactivateStoppedSession() {
        val h = Harness()
        h.session.start(); h.drain()
        val old = h.drivers.single()
        h.session.stop(); h.drain()
        old.events.ready(); old.events.connected(); old.events.pin("1234")
        old.events.audioReady(); old.events.metrics(AudioMetrics(decodeMeanUs = 100))
        assertEquals(AudioSnapshot(h.session.snapshot().generation), h.session.snapshot())
    }
    @Test fun offOnBeforeCleanupKeepsNewestSession() {
        val h = Harness()
        h.session.start(); h.drain()
        val old = h.drivers.single()
        h.session.stop(); h.session.start(); h.drain()
        assertEquals(1, old.closes)
        assertFalse(old.token.isCurrent())
        val current = h.drivers.last()
        assertTrue(current.token.isCurrent())
        current.events.ready()
        old.events.failed("old_failure"); old.events.ready(); old.events.silent()
        assertTrue(h.session.isEnabled())
        assertEquals(AudioPhase.WAITING, h.session.snapshot().phase)
        assertEquals(0, current.closes)
    }
    @Test fun connectionAndCodecReadinessDoNotClaimReceivedAudio() {
        val h = Harness()
        h.session.start(); h.drain()
        val events = h.drivers.single().events
        events.ready(); events.connected(); events.audioReady()
        events.metrics(AudioMetrics())
        assertEquals(AudioPhase.CONNECTED, h.session.snapshot().phase)
        events.metrics(AudioMetrics(decodeMeanUs = 50))
        assertEquals(AudioPhase.PROCESSING, h.session.snapshot().phase)
    }
    @Test fun lateDiscoveryReadyDoesNotOverwriteProcessingState() {
        val h = Harness()
        h.session.start(); h.drain()
        val events = h.drivers.single().events
        events.audioReady(); events.metrics(AudioMetrics(decodeMeanUs = 50)); events.ready()
        assertEquals(AudioPhase.PROCESSING, h.session.snapshot().phase)
    }
    @Test fun sourceSilenceKeepsSessionAndDoesNotResumeAnything() {
        val h = Harness()
        h.session.start(); h.drain()
        val events = h.drivers.single().events
        events.audioReady(); events.metrics(AudioMetrics(decodeMeanUs = 50)); events.silent()
        events.metrics(AudioMetrics(decodeMeanUs = 50))
        assertTrue(h.session.isEnabled())
        assertEquals(AudioPhase.SILENT, h.session.snapshot().phase)
        assertEquals(0, h.drivers.single().closes)
        events.audioReady(); events.metrics(AudioMetrics(decodeMeanUs = 50))
        assertEquals(AudioPhase.PROCESSING, h.session.snapshot().phase)
    }
    @Test fun receiverFailureClosesOnceAndRejectsOldCallbacks() {
        val h = Harness()
        h.session.start(); h.drain()
        val old = h.drivers.single()
        old.events.failed("network_lost"); h.drain()
        old.events.metrics(AudioMetrics(decodeMeanUs = 50)); old.events.pin("1234"); h.drain()
        assertEquals(AudioPhase.FAILED, h.session.snapshot().phase)
        assertEquals(1, old.closes)
        assertNull(h.session.snapshot().pin)
        assertFalse(h.session.isEnabled())
    }
    @Test fun failedStartCanOnlyBeRetriedByNewExplicitStart() {
        val h = Harness()
        h.onStart = { throw IllegalStateException() }
        h.session.start(); h.drain()
        assertEquals(AudioPhase.FAILED, h.session.snapshot().phase)
        assertEquals(1, h.drivers.single().closes)
        h.onStart = null
        h.session.start(); h.drain()
        h.drivers.last().events.ready()
        assertEquals(2, h.drivers.size)
        assertEquals(AudioPhase.WAITING, h.session.snapshot().phase)
    }
    @Test fun interruptionCleanupDoesNotCancelNextManualStart() {
        val h = Harness()
        h.session.start(); h.drain()
        h.session.stop("network_lost"); h.session.start(); h.drain()
        h.drivers.last().events.ready()
        assertEquals(AudioPhase.WAITING, h.session.snapshot().phase)
        assertNull(h.session.snapshot().failure)
        assertEquals(1, h.drivers.first().closes)
    }
    @Test fun activePairingCodeIsRemovedAfterAudioFormatReady() {
        val h = Harness()
        h.session.start(); h.drain()
        val events = h.drivers.single().events
        events.pin("1234")
        assertEquals("1234", h.session.snapshot().pin)
        events.audioReady(); events.metrics(AudioMetrics(decodeMeanUs = 50))
        assertNull(h.session.snapshot().pin)
    }
    @Test fun disconnectClearsMetricsWithoutStartingAnotherDriver() {
        val h = Harness()
        h.session.start(); h.drain()
        val events = h.drivers.single().events
        events.audioReady(); events.metrics(AudioMetrics(decodeMeanUs = 50)); events.disconnected()
        assertEquals(AudioPhase.WAITING, h.session.snapshot().phase)
        assertNull(h.session.snapshot().metrics)
        assertEquals(1, h.drivers.size)
        assertEquals(0, h.drivers.single().closes)
    }
}
