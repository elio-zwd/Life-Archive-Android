package com.elio.lifearchive

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.lifearchive.domain.pet.*
import com.elio.lifearchive.feature.pet.CompanionDock
import javax.inject.Provider
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 仅在用户已进入伙伴且开启显示后装配；阅读和编辑页面不承载小窗。 */
@Composable
internal fun InAppCompanion(provider: Provider<CompanionRepository>, visible: (Boolean) -> Unit, open: () -> Unit) {
    val repo = remember { provider.get() }
    val state by repo.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.profile.showInApp) { visible(state.profile.showInApp) }
    if (!state.profile.showInApp) return
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    var expanded by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val safeLeft = WindowInsets.safeDrawing.getLeft(density, direction) + with(density) { 8.dp.toPx() }
    val safeTop = WindowInsets.safeDrawing.getTop(density) + with(density) { 8.dp.toPx() }
    var minX by remember { mutableFloatStateOf(0f) }
    var minY by remember { mutableFloatStateOf(0f) }
    Column(Modifier.testTag("in-app-companion").offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(if (expanded) 320.dp else 164.dp).onGloballyPositioned { coordinates ->
        val position = coordinates.positionInRoot()
        minX = (safeLeft - (position.x - x.roundToInt())).coerceAtMost(0f)
        minY = (safeTop - (position.y - y.roundToInt())).coerceAtMost(0f)
        x = x.coerceIn(minX, 0f); y = y.coerceIn(minY, 0f)
    }) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
            Text(if (expanded) "贴边对话 · 拖动" else "拖动伙伴", Modifier.testTag("in-app-companion-drag").padding(10.dp).pointerInput(minX, minY, expanded) {
                detectDragGestures(onDragEnd = { if (expanded) x = if (x - minX < -x) minX else 0f }) { change, delta ->
                    change.consume(); x = (x + delta.x).coerceIn(minX, 0f); y = (y + delta.y).coerceIn(minY, 0f)
                }
            }, style = MaterialTheme.typography.labelMedium)
        }
        CompanionDock(repo, expanded, onExpand = { expanded = it; x = 0f; y = 0f; if (!it) { focus.clearFocus(); keyboard?.hide() } },
            onOpen = { focus.clearFocus(); keyboard?.hide(); open() },
            onHide = { scope.launch { repo.setInAppVisible(false) } })
    }
}
