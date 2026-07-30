package com.example.controllerapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ControllerColors = darkColorScheme(
    primary = Color(0xffb9c7ff),
    onPrimary = Color(0xff102158),
    secondary = Color(0xffc3c6dd),
    background = Color(0xff090b10),
    surface = Color(0xff11141c),
    surfaceVariant = Color(0xff20232d),
    onSurface = Color(0xfff0f1f8),
    onSurfaceVariant = Color(0xffc5c6d0),
    error = Color(0xffffb4ab)
)

@Composable
fun ControllerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ControllerColors,
        content = content
    )
}
