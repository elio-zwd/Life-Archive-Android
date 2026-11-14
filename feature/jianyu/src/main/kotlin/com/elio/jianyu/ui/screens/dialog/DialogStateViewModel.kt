package com.elio.jianyu.ui.screens.dialog

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** 输入和临时交互随导航运行实例保留，避免把整份消息列表写进 Android SavedState Bundle。 */
internal class DialogStateViewModel(initial: DialogUiState) : ViewModel() {
    val state = mutableStateOf(initial)

    class Factory(private val initial: DialogUiState) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass == DialogStateViewModel::class.java)
            return DialogStateViewModel(initial) as T
        }
    }
}
