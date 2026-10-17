package com.elio.lifearchive.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F67B2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9FA),
    onPrimaryContainer = Color(0xFF163B6A),
    secondary = Color(0xFF3975C6),
    onSecondary = Color.White,
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF20242A),
    surface = Color(0xFFF4F6F8),
    surfaceContainer = Color.White,
    onSurface = Color(0xFF20242A),
    onSurfaceVariant = Color(0xFF667085),
    outline = Color(0xFF667085),
    outlineVariant = Color(0xFFE1E5EA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC5FF),
    onPrimary = Color(0xFF12345B),
    primaryContainer = Color(0xFF254B79),
    onPrimaryContainer = Color(0xFFDCE9FA),
    secondary = Color(0xFFAAC7ED),
    onSecondary = Color(0xFF173352),
    background = Color(0xFF25292F),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF25292F),
    surfaceContainer = Color(0xFF30353D),
    onSurface = Color(0xFFE6E9EF),
    onSurfaceVariant = Color(0xFFB7C0CE),
    outline = Color(0xFF8993A2),
    outlineVariant = Color(0xFF454D59),
)

// 固定参考配色，避免系统动态配色覆盖本项目的视觉基线。
@Composable
fun LifeArchiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
