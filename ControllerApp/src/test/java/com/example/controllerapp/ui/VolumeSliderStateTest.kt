package com.example.controllerapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeSliderStateTest {
    @Test
    fun delayedRemoteAckDoesNotOverwriteDragOrFinalCommand() {
        val state = VolumeSliderState()
        assertEquals(5f, state.displayedValue(5, 15), 0.001f)
        state.drag(8f)
        state.drag(12f)
        assertEquals(12f, state.displayedValue(8, 15), 0.001f)
        assertEquals(12, state.finish(8, 15))
        assertNull(state.draggedValue)
        assertEquals(12f, state.displayedValue(12, 15), 0.001f)
        assertNull(state.finish(12, 15))
    }

    @Test
    fun changedMaximumClampsFinalValueAndDisconnectCancelsEdit() {
        val state = VolumeSliderState()
        state.drag(14f)
        assertEquals(7f, state.displayedValue(3, 7), 0.001f)
        assertEquals(7, state.finish(3, 7))
        state.drag(9f)
        state.cancel()
        assertEquals(3f, state.displayedValue(3, 15), 0.001f)
        assertNull(state.finish(3, 15))
        assertEquals(0f, state.displayedValue(3, 0), 0.001f)
    }
}
