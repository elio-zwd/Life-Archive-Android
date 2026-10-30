package com.elio.lifearchive.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class DocumentPalette { BLUE, WARM, GRAPHITE }

@Immutable
data class DocumentColors(
    val background: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val soft: Color,
    val tool: Color,
    val code: Color,
)

private val BlueDocumentColors = DocumentColors(
    background = Color(0xFFFAFBFD),
    ink = Color(0xFF243044),
    muted = Color(0xFF7B889B),
    line = Color(0xFFE7ECF3),
    accent = Color(0xFF2569E8),
    soft = Color(0xFFE9F1FF),
    tool = Color(0xFFF0F3F8),
    code = Color(0xFF8B72AD),
)

private val WarmDocumentColors = DocumentColors(
    background = Color(0xFFFAF7EF),
    ink = Color(0xFF343F36),
    muted = Color(0xFF899084),
    line = Color(0xFFE9E6DB),
    accent = Color(0xFF607C66),
    soft = Color(0xFFE6EDDF),
    tool = Color(0xFFF1EEE5),
    code = Color(0xFF937B60),
)

private val GraphiteDocumentColors = DocumentColors(
    background = Color(0xFF23272D),
    ink = Color(0xFFE5E7EB),
    muted = Color(0xFFA1AAB6),
    line = Color(0xFF353C46),
    accent = Color(0xFF9ABFF0),
    soft = Color(0xFF30445D),
    tool = Color(0xFF2D333C),
    code = Color(0xFFBAA4E0),
)

val DocumentPalette.colors: DocumentColors
    get() = when (this) {
        DocumentPalette.BLUE -> BlueDocumentColors
        DocumentPalette.WARM -> WarmDocumentColors
        DocumentPalette.GRAPHITE -> GraphiteDocumentColors
    }

val LocalDocumentTheme = staticCompositionLocalOf { DocumentPalette.BLUE }
val LocalDocumentColors = staticCompositionLocalOf { DocumentPalette.BLUE.colors }

@Composable
fun DocumentPageTheme(theme: DocumentPalette, content: @Composable () -> Unit) {
    val colors = theme.colors
    val dark = theme == DocumentPalette.GRAPHITE
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val onAccent = if (dark) colors.background else Color.White
    val scheme = base.copy(
        primary = colors.accent,
        onPrimary = onAccent,
        primaryContainer = colors.soft,
        onPrimaryContainer = colors.ink,
        secondary = colors.accent,
        onSecondary = onAccent,
        secondaryContainer = colors.soft,
        onSecondaryContainer = colors.accent,
        tertiaryContainer = colors.soft,
        onTertiaryContainer = colors.ink,
        background = colors.background,
        onBackground = colors.ink,
        surface = colors.background,
        surfaceTint = colors.accent,
        onSurface = colors.ink,
        surfaceVariant = colors.tool,
        onSurfaceVariant = colors.muted,
        surfaceContainer = colors.tool,
        surfaceContainerLowest = colors.background,
        surfaceContainerLow = colors.background,
        surfaceContainerHigh = colors.tool,
        surfaceContainerHighest = colors.tool,
        outline = colors.muted,
        outlineVariant = colors.line,
        inverseSurface = colors.ink,
        inverseOnSurface = colors.background,
    )
    CompositionLocalProvider(
        LocalDocumentTheme provides theme,
        LocalDocumentColors provides colors,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
