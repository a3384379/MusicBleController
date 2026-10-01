package com.example.controllerapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Remote acknowledgements may update playback while the local edit stays authoritative. */
internal class VolumeSliderState {
    var draggedValue by mutableStateOf<Float?>(null)
        private set

    fun displayedValue(current: Int, maximum: Int): Float =
        (draggedValue ?: current.toFloat()).coerceIn(0f, maximum.coerceAtLeast(0).toFloat())

    fun drag(value: Float) { draggedValue = value }

    fun finish(current: Int, maximum: Int): Int? {
        val finalValue = draggedValue?.let { displayedValue(current, maximum).toInt() }
        cancel()
        return finalValue
    }

    fun cancel() { draggedValue = null }
}
