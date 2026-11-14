package com.elio.jianyu.ui.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

@Composable
internal fun sessionCoroutineScope(): CoroutineScope {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tasks = LocalSessionTasks.current
    DisposableEffect(scope, tasks) {
        scope.coroutineContext[Job]?.let { tasks?.register(it) }
        onDispose { }
    }
    return scope
}
