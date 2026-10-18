package com.elio.lifearchive

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.domain.notes.NoteResult
import com.elio.lifearchive.domain.notes.NoteSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// NavKeySerializer 通过 JVM 反射取得序列化器，顶层 private 会阻断状态保存。
@Serializable internal data object Overview : NavKey
@Serializable internal data object StorageExample : NavKey

@Singleton
internal class VariantRuntime @Inject constructor(@ApplicationContext context: Context) {
    private val runtime = FoundationRuntime(context, File(context.filesDir, "foundation-demo"))
    private val factory = viewModelFactory { initializer { ExampleViewModel(runtime) } }

    @Composable fun Content() {
        val backStack = rememberNavBackStack(Overview)
        NavDisplay(
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<Overview> {
                    Column {
                        Text("架构底层已就绪，功能层待接入")
                        Button(onClick = { backStack.add(StorageExample) }) { Text("打开底层接入示例") }
                    }
                }
                entry<StorageExample> {
                    val model: ExampleViewModel = viewModel(factory = factory)
                    val state by model.state.collectAsStateWithLifecycle()
                    ExampleScreen(state, model::roundTrip, { backStack.removeLastOrNull() })
                }
            },
        )
    }
}

private data class ExampleState(val busy: Boolean = false, val message: String = "示例使用独立资料库")

private class ExampleViewModel(private val runtime: FoundationRuntime) : ViewModel() {
    private val mutableState = MutableStateFlow(ExampleState())
    val state = mutableState.asStateFlow()
    fun roundTrip() {
        if (mutableState.value.busy) return
        mutableState.value = ExampleState(true, "正在创建、保存并重新读取…")
        viewModelScope.launch {
            try {
                val created = runtime.notes.create("示例-${java.util.UUID.randomUUID()}.md", RawDocument("---\nunknown: 保留\n---\n# 示例\n"))
                check(created is NoteResult.Committed<NoteSnapshot>) { created.toString() }
                val saved = runtime.notes.save(created.value.summary.id, created.value.summary.version, RawDocument(created.value.document.text + "真实文件保存验证\n"))
                check(saved is NoteResult.Committed<NoteSnapshot>) { saved.toString() }
                val read = runtime.notes.read(saved.value.summary.id)
                check(read is NoteResult.Success<NoteSnapshot> && read.value.document == saved.value.document)
                mutableState.value = ExampleState(message = "保存与重读一致；索引待更新：${saved.indexPending}")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                mutableState.value = ExampleState(message = "未通过：${e.message}")
            }
        }
    }
}

@Composable
private fun ExampleScreen(state: ExampleState, onRun: () -> Unit, onBack: () -> Unit) {
    Column {
        Text(state.message)
        Button(onClick = onRun, enabled = !state.busy) { Text("验证一次保存与重读") }
        Button(onClick = onBack) { Text("返回") }
    }
}
