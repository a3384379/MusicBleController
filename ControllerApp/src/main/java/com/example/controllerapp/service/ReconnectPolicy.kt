package com.example.controllerapp.service

object ReconnectPolicy {
    fun delayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 5)
        return (1_000L shl exponent).coerceAtMost(30_000L)
    }

    fun shouldForceScan(attempt: Int, savedAddress: String): Boolean =
        savedAddress.isBlank() || attempt.coerceAtLeast(1) % 3 == 0
}
