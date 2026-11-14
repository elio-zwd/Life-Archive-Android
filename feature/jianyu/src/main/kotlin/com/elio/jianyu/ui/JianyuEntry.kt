package com.elio.jianyu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.elio.jianyu.backup.JianyuCompleteOperation
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

internal val LocalJianyuContentEpoch = staticCompositionLocalOf { "preview" }

@Composable
fun JianyuEntry(
    session: JianyuHostSession,
    active: Boolean,
    onTopLevelChanged: (Boolean) -> Unit,
    onBottomBarMeasured: (Int) -> Unit,
    onDarkThemeChanged: (Boolean) -> Unit,
) {
    val preferences by session.services.preferences.state.collectAsStateWithLifecycle()
    val backupOperation by session.services.backup.complete.operation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var lastNotice by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(active, backupOperation) {
        if (backupOperation is JianyuCompleteOperation.Busy) lastNotice = null
        val message = when (val status = backupOperation) {
            is JianyuCompleteOperation.Finished -> status.message
            is JianyuCompleteOperation.Failed -> status.message
            else -> null
        }
        if (active && message != null && lastNotice != message) {
            lastNotice = message
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val dark = when (preferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    if (active) SideEffect { onDarkThemeChanged(dark) }
    val base = LocalDensity.current
    val scale = when (preferences.fontSizeMode) { FontSizeMode.SYSTEM -> 1f; FontSizeMode.SMALL -> .9f; FontSizeMode.LARGE -> 1.15f }
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * scale), LocalSessionTasks provides session.tasks,
        LocalJianyuContentEpoch provides session.contentEpoch) {
        SkillRoundtableTheme(darkTheme = dark, reducedMotion = preferences.reducedMotion,
            highContrastText = preferences.highContrastText, spacing = spacingForContentDensity(preferences.contentDensityMode)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                JianyuContent(session.conversation, session.services, onTopLevelChanged = onTopLevelChanged,
                    onBottomBarMeasured = onBottomBarMeasured)
            }
        }
    }
}
