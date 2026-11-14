package com.elio.jianyu.ui.lifecycle

import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.elio.jianyu.runtime.JianyuSessionTasks
import kotlinx.coroutines.Job

internal val LocalSessionTasks = staticCompositionLocalOf<JianyuSessionTasks?> { null }

@Composable
internal inline fun <reified VM : ViewModel> sessionViewModel(
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
    key: String? = null,
    factory: ViewModelProvider.Factory? = null,
    extras: CreationExtras = (viewModelStoreOwner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty,
): VM {
    val model = androidx.lifecycle.viewmodel.compose.viewModel<VM>(viewModelStoreOwner, key, factory, extras)
    val tasks = LocalSessionTasks.current
    DisposableEffect(model, tasks) {
        model.viewModelScope.coroutineContext[Job]?.let { tasks?.register(it) }
        onDispose { }
    }
    return model
}
