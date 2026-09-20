package com.rokidmirror.sender.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(primary = Color(0xFF22D3EE), secondary = Color(0xFF94A3B8), background = Color(0xFF0F172A), surface = Color(0xFF1E293B))
private val Light = lightColorScheme(primary = Color(0xFF0E7490), secondary = Color(0xFF475569))

@Composable
fun RokidMirrorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
