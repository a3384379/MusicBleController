package com.example.playeragent.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTraversalPolicyTest {
    @Test
    fun remainsPassiveUntilExplicitDiagnosticCapture() {
        assertFalse(
            AccessibilityTraversalPolicy.shouldTraverse(
                nowElapsedMs = 10_000L,
                lastTraversalAtElapsedMs = 0L,
                treeDumpRequested = false,
                captureUntilElapsedMs = 0L,
                minimumIntervalMs = 1_500L
            )
        )
        assertTrue(
            AccessibilityTraversalPolicy.shouldTraverse(
                nowElapsedMs = 10_000L,
                lastTraversalAtElapsedMs = 0L,
                treeDumpRequested = false,
                captureUntilElapsedMs = 20_000L,
                minimumIntervalMs = 1_500L
            )
        )
        assertFalse(
            AccessibilityTraversalPolicy.shouldTraverse(
                nowElapsedMs = 10_500L,
                lastTraversalAtElapsedMs = 10_000L,
                treeDumpRequested = false,
                captureUntilElapsedMs = 20_000L,
                minimumIntervalMs = 1_500L
            )
        )
        assertTrue(
            AccessibilityTraversalPolicy.shouldTraverse(
                nowElapsedMs = 10_500L,
                lastTraversalAtElapsedMs = 10_000L,
                treeDumpRequested = true,
                captureUntilElapsedMs = 0L,
                minimumIntervalMs = 1_500L
            )
        )
    }
}
