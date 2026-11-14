package com.elio.jianyu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.jianyu.preferences.FontSizeMode
import com.elio.jianyu.preferences.ThemeMode
import com.elio.jianyu.runtime.JianyuHostSession
import com.elio.jianyu.ui.lifecycle.LocalSessionTasks
import com.elio.jianyu.ui.theme.SkillRoundtableTheme
import com.elio.jianyu.ui.theme.spacingForContentDensity

@Composable
fun JianyuEntry(
    session: JianyuHostSession,
    active: Boolean,
    onTopLevelChanged: (Boolean) -> Unit,
    onBottomBarMeasured: (Int) -> Unit,
    onDarkThemeChanged: (Boolean) -> Unit,
) {
    val preferences by session.services.preferences.state.collectAsStateWithLifecycle()
    val dark = when (preferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    if (active) SideEffect { onDarkThemeChanged(dark) }
    val base = LocalDensity.current
    val scale = when (preferences.fontSizeMode) { FontSizeMode.SYSTEM -> 1f; FontSizeMode.SMALL -> .9f; FontSizeMode.LARGE -> 1.15f }
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * scale), LocalSessionTasks provides session.tasks) {
        SkillRoundtableTheme(darkTheme = dark, reducedMotion = preferences.reducedMotion,
            highContrastText = preferences.highContrastText, spacing = spacingForContentDensity(preferences.contentDensityMode)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                JianyuContent(session.conversation, session.services, onTopLevelChanged = onTopLevelChanged,
                    onBottomBarMeasured = onBottomBarMeasured)
            }
        }
    }
}
