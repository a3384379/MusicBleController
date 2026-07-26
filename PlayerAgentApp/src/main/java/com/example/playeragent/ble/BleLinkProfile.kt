package com.example.playeragent.ble

/**
 * Per-connection pacing state. The profile is owned by BleNotifyQueue's serial
 * thread and is reset whenever a device reconnects or negotiates a new MTU.
 */
internal class BleLinkProfile(initialMtu: Int) {
    enum class PayloadKind {
        JSON_LYRIC,
        BINARY_LYRIC,
        BINARY_ARTWORK,
        OTHER
    }

    var mtu: Int = initialMtu
        private set
    var jsonDelayMs: Long = JSON_INITIAL_DELAY_MS
        private set
    var binaryDelayMs: Long = BINARY_INITIAL_DELAY_MS
        private set
    var artworkDelayMs: Long = ARTWORK_INITIAL_DELAY_MS
        private set
    var ewmaCallbackRttMs: Double = 0.0
        private set
    var successCount: Long = 0
        private set
    var failureCount: Long = 0
        private set

    private var jsonSuccessWindow = 0
    private var binarySuccessWindow = 0
    private var artworkSuccessWindow = 0

    fun reset(newMtu: Int = mtu) {
        mtu = newMtu
        jsonDelayMs = JSON_INITIAL_DELAY_MS
        binaryDelayMs = BINARY_INITIAL_DELAY_MS
        artworkDelayMs = ARTWORK_INITIAL_DELAY_MS
        ewmaCallbackRttMs = 0.0
        successCount = 0
        failureCount = 0
        jsonSuccessWindow = 0
        binarySuccessWindow = 0
        artworkSuccessWindow = 0
    }

    fun updateMtu(newMtu: Int) {
        if (newMtu > 0 && newMtu != mtu) {
            reset(newMtu)
        }
    }

    fun delayFor(kind: PayloadKind, fallbackMs: Long): Long {
        return when (kind) {
            PayloadKind.JSON_LYRIC -> jsonDelayMs
            PayloadKind.BINARY_LYRIC -> binaryDelayMs
            PayloadKind.BINARY_ARTWORK -> artworkDelayMs
            PayloadKind.OTHER -> fallbackMs
        }
    }

    fun recordSuccess(kind: PayloadKind, callbackRttMs: Long) {
        successCount += 1
        val rtt = callbackRttMs.coerceAtLeast(0L).toDouble()
        ewmaCallbackRttMs = if (ewmaCallbackRttMs <= 0.0) {
            rtt
        } else {
            EWMA_ALPHA * rtt + (1.0 - EWMA_ALPHA) * ewmaCallbackRttMs
        }

        if (callbackRttMs > CONGESTED_CALLBACK_RTT_MS) {
            increaseDelay(kind)
            resetSuccessWindow(kind)
            return
        }

        when (kind) {
            PayloadKind.JSON_LYRIC -> {
                jsonSuccessWindow += 1
                if (jsonSuccessWindow >= SUCCESS_WINDOW &&
                    ewmaCallbackRttMs < FAST_CALLBACK_RTT_MS
                ) {
                    jsonDelayMs = (jsonDelayMs - 1L).coerceAtLeast(JSON_MIN_DELAY_MS)
                    jsonSuccessWindow = 0
                }
            }
            PayloadKind.BINARY_LYRIC -> {
                binarySuccessWindow += 1
                if (binarySuccessWindow >= SUCCESS_WINDOW &&
                    ewmaCallbackRttMs < FAST_CALLBACK_RTT_MS
                ) {
                    binaryDelayMs = (binaryDelayMs - 1L)
                        .coerceAtLeast(BINARY_MIN_DELAY_MS)
                    binarySuccessWindow = 0
                }
            }
            PayloadKind.BINARY_ARTWORK -> {
                artworkSuccessWindow += 1
                if (artworkSuccessWindow >= SUCCESS_WINDOW &&
                    ewmaCallbackRttMs < FAST_CALLBACK_RTT_MS
                ) {
                    artworkDelayMs = (artworkDelayMs - 1L)
                        .coerceAtLeast(ARTWORK_MIN_DELAY_MS)
                    artworkSuccessWindow = 0
                }
            }
            PayloadKind.OTHER -> Unit
        }
    }

    fun recordFailure(kind: PayloadKind) {
        failureCount += 1
        increaseDelay(kind)
        resetSuccessWindow(kind)
    }

    private fun increaseDelay(kind: PayloadKind) {
        when (kind) {
            PayloadKind.JSON_LYRIC -> {
                jsonDelayMs = (jsonDelayMs + FAILURE_STEP_MS).coerceAtMost(MAX_DELAY_MS)
            }
            PayloadKind.BINARY_LYRIC -> {
                binaryDelayMs = (binaryDelayMs + FAILURE_STEP_MS).coerceAtMost(MAX_DELAY_MS)
            }
            PayloadKind.BINARY_ARTWORK -> {
                artworkDelayMs = (artworkDelayMs + FAILURE_STEP_MS).coerceAtMost(MAX_DELAY_MS)
            }
            PayloadKind.OTHER -> Unit
        }
    }

    private fun resetSuccessWindow(kind: PayloadKind) {
        when (kind) {
            PayloadKind.JSON_LYRIC -> jsonSuccessWindow = 0
            PayloadKind.BINARY_LYRIC -> binarySuccessWindow = 0
            PayloadKind.BINARY_ARTWORK -> artworkSuccessWindow = 0
            PayloadKind.OTHER -> Unit
        }
    }

    companion object {
        private const val JSON_INITIAL_DELAY_MS = 5L
        private const val BINARY_INITIAL_DELAY_MS = 2L
        private const val ARTWORK_INITIAL_DELAY_MS = 3L
        private const val JSON_MIN_DELAY_MS = 2L
        private const val BINARY_MIN_DELAY_MS = 1L
        private const val ARTWORK_MIN_DELAY_MS = 1L
        private const val FAILURE_STEP_MS = 5L
        private const val MAX_DELAY_MS = 30L
        private const val SUCCESS_WINDOW = 20
        private const val CONGESTED_CALLBACK_RTT_MS = 120L
        private const val FAST_CALLBACK_RTT_MS = 60.0
        private const val EWMA_ALPHA = 0.2
    }
}
