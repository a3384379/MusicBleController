package com.example.controllerapp.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

internal data class SpectrumFrame(
    val trackSeed: String,
    val positionMs: Long,
    val playing: Boolean,
    val lyricProgress: Float,
    val wordSignature: String
)

/**
 * Deterministic pseudo spectrum driven by playback and lyric timing.
 *
 * Sony does not expose PCM/FFT data over BLE, so this deliberately avoids pretending to be a
 * real analyser. It uses stable per-track frequency bands, attack/release smoothing and small
 * word-boundary pulses to produce natural motion without making every song share one sine wave.
 */
internal class NaturalSpectrumEngine(
    private val barCount: Int = DEFAULT_BAR_COUNT
) {
    private var levels = FloatArray(barCount) { IDLE_BASELINE }
    private var stableSeed = 0L
    private var lastTrackSeed = ""
    private var lastPositionMs: Long? = null
    private var lastWordSignature = ""
    private var wordPulse = 0f

    fun levels(frame: SpectrumFrame): FloatArray {
        updateTrack(frame.trackSeed)
        val deltaSeconds = lastPositionMs
            ?.let { abs(frame.positionMs - it).coerceIn(16L, 200L) / 1_000f }
            ?: 0.05f
        lastPositionMs = frame.positionMs
        updateWordPulse(frame.wordSignature, frame.playing, deltaSeconds)

        val raw = rawTargets(frame)
        val smoothed = smoothBands(raw)
        if (!frame.playing) {
            levels = smoothed
            return levels.copyOf()
        }
        for (index in levels.indices) {
            val coefficient = when {
                smoothed[index] > levels[index] -> ATTACK
                frame.playing -> RELEASE
                else -> PAUSE_RELEASE
            }
            val adjusted = 1f - (1f - coefficient).pow(deltaSeconds * 60f)
            levels[index] += (smoothed[index] - levels[index]) * adjusted
        }
        return levels.copyOf()
    }

    private fun updateTrack(trackSeed: String) {
        if (trackSeed == lastTrackSeed) return
        lastTrackSeed = trackSeed
        stableSeed = stableHash(trackSeed)
        levels.fill(IDLE_BASELINE)
        lastPositionMs = null
        lastWordSignature = ""
        wordPulse = 0f
    }

    private fun updateWordPulse(signature: String, playing: Boolean, deltaSeconds: Float) {
        if (playing && signature.isNotBlank() && lastWordSignature.isNotBlank() &&
            signature != lastWordSignature
        ) {
            wordPulse = 1f
        }
        if (signature.isNotBlank()) lastWordSignature = signature
        wordPulse = max(0f, wordPulse - deltaSeconds / WORD_PULSE_SECONDS)
    }

    private fun rawTargets(frame: SpectrumFrame): FloatArray {
        val seconds = frame.positionMs.coerceAtLeast(0L) / 1_000.0
        val seedPhase = unitNoise((stableSeed and 0x7fff).toInt()) * Math.PI * 2.0
        val phase = seconds + seedPhase
        val global = if (frame.playing) 0.32 + 0.05 * sin(seconds * 1.17) else 0.08
        val motion = if (frame.playing) 1.0 else 0.12
        return FloatArray(barCount) { index ->
            val x = index.toDouble() / max(barCount - 1, 1).toDouble()
            val band = bandProfile(x)
            val noise = unitNoise(index + (stableSeed and 0x3ff).toInt())
            val localPhase = noise * Math.PI * 2.0
            val group = floor(x * 9.0)
            val low = sin(phase * band.lowRate + localPhase * 0.62 + group * 0.38)
            val mid = sin(
                phase * band.midRate + localPhase * 1.17 +
                    sin(phase * 0.62 + group) * 0.46
            )
            val high = sin(phase * band.highRate + localPhase * 2.30 + group * 1.15)
            val beat = ((sin(phase * band.beatRate + localPhase * 0.70) + 1.0) / 2.0)
                .pow(band.beatShape)
            val lyricAccent = if (frame.playing) {
                sin(frame.lyricProgress.coerceIn(0f, 1f) * Math.PI * 2.0 + localPhase * 0.25) * 0.025
            } else {
                0.0
            }
            val energy = IDLE_BASELINE +
                global * band.weight +
                motion * low * band.lowAmount +
                motion * mid * band.midAmount +
                motion * high * band.highAmount +
                motion * beat * band.beatAmount +
                wordPulse * band.wordAmount +
                lyricAccent
            energy.coerceIn(0.04, 1.0).toFloat()
        }
    }

    private fun smoothBands(raw: FloatArray): FloatArray {
        if (raw.size < 3) return raw
        return FloatArray(raw.size) { index ->
            val x = index.toFloat() / max(raw.lastIndex, 1).toFloat()
            val boundary = abs(x - 0.28f) < 0.035f || abs(x - 0.76f) < 0.035f
            val neighborWeight = if (boundary) 0.06f else 0.13f
            raw[index] * (1f - neighborWeight * 2f) +
                raw[max(0, index - 1)] * neighborWeight +
                raw[min(raw.lastIndex, index + 1)] * neighborWeight
        }
    }

    private fun bandProfile(x: Double): BandProfile = when {
        x < 0.28 -> BandProfile(
            weight = 0.90,
            lowRate = 1.45,
            lowAmount = 0.18,
            midRate = 2.25,
            midAmount = 0.12,
            highRate = 4.0,
            highAmount = 0.035,
            beatRate = 1.95,
            beatShape = 2.4,
            beatAmount = 0.16,
            wordAmount = 0.04
        )
        x < 0.76 -> BandProfile(
            weight = 0.72,
            lowRate = 1.10,
            lowAmount = 0.11,
            midRate = 3.25,
            midAmount = 0.18,
            highRate = 7.8,
            highAmount = 0.07,
            beatRate = 2.55,
            beatShape = 2.9,
            beatAmount = 0.12,
            wordAmount = 0.15
        )
        else -> BandProfile(
            weight = 0.46,
            lowRate = 0.85,
            lowAmount = 0.055,
            midRate = 3.8,
            midAmount = 0.09,
            highRate = 10.8,
            highAmount = 0.13,
            beatRate = 3.4,
            beatShape = 3.2,
            beatAmount = 0.08,
            wordAmount = 0.05
        )
    }

    private fun unitNoise(value: Int): Double {
        var x = value.toLong() xor stableSeed
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        x = x xor (x ushr 31)
        return (x and 0x7fff_ffffL).toDouble() / 0x7fff_ffffL.toDouble()
    }

    private fun stableHash(value: String): Long {
        var hash = -3750763034362895579L
        value.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 1099511628211L
        }
        return hash
    }

    private data class BandProfile(
        val weight: Double,
        val lowRate: Double,
        val lowAmount: Double,
        val midRate: Double,
        val midAmount: Double,
        val highRate: Double,
        val highAmount: Double,
        val beatRate: Double,
        val beatShape: Double,
        val beatAmount: Double,
        val wordAmount: Double
    )

    private companion object {
        const val DEFAULT_BAR_COUNT = 36
        const val IDLE_BASELINE = 0.09f
        const val ATTACK = 0.38f
        const val RELEASE = 0.13f
        const val PAUSE_RELEASE = 0.08f
        const val WORD_PULSE_SECONDS = 0.20f
    }
}
