package com.elio.jianyu.ui.screens.dialog

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** 输入和临时交互随导航运行实例保留，避免把整份消息列表写进 Android SavedState Bundle。 */
internal class DialogStateViewModel(initial: DialogUiState) : ViewModel() {
    val state = mutableStateOf(initial)

    class Factory(private val initial: DialogUiState, private val epoch: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass == DialogStateViewModel::class.java)
            return DialogStateViewModel(initial.copy(composerState = DialogDraftMemory.read(epoch) ?: initial.composerState)) as T
        }
    }
}

/** 完整快照会关闭运行世代；同进程、同内容世代的未发送输入仍保留，替换数据后立即失效。
 * 只保存小型编辑器状态，不保留消息列表、Repository、Activity 或运行任务，也不写 Android Bundle。 */
internal object DialogDraftMemory {
    private var epoch: String? = null
    private var draft: DialogComposerState? = null
    @Synchronized fun read(expected: String): DialogComposerState? = draft.takeIf { epoch == expected }
    @Synchronized fun save(current: String, value: DialogComposerState) {
        epoch = current
        draft = value.copy(isGenerating = false)
    }
}
