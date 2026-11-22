package com.elio.lifearchive.feature.pet

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.elio.lifearchive.core.live2d.Live2DView
import com.elio.lifearchive.domain.pet.*

@Composable
fun CompanionAvatar(state: CompanionState, modifier: Modifier = Modifier, onTap: () -> Unit = {}) {
    val owner = LocalLifecycleOwner.current
    val currentTap by rememberUpdatedState(onTap)
    val message = state.messages.lastOrNull { it.role == MessageRole.COMPANION }
    val mood = if (state.generating) "THINKING" else message?.emotion?.name ?: "CALM"
    val token = "${message?.id}:${message?.status}:$mood"
    var status by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember(context) { Live2DView(context) }
    DisposableEffect(owner, view) {
        fun active() = view.setActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> active() }
        owner.lifecycle.addObserver(observer); active()
        onDispose { owner.lifecycle.removeObserver(observer); view.setActive(false) }
    }
    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize(), update = {
            it.onStatus = { error -> status = error }
            it.onTap = { currentTap() }
            it.setCue(token, mood)
        }, onRelease = { it.setActive(false); it.onStatus = {}; it.onTap = {} })
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
