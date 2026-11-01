package com.elio.lifearchive.feature.editor

import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SaveStatus(
    val label: String = "已保存",
    val error: String? = null,
    val draftProtected: Boolean = true,
    val conflict: Boolean = false,
    val indexPending: Boolean = false,
)

/** 输入只提供快照。串行提交更新版本令牌，但绝不把回执正文写回输入框。 */
class EditorPersistence(
    private val repository: NotesRepository,
    initial: NoteSnapshot,
    private val scope: CoroutineScope,
    initialRevision: Long = 0,
) {
    private val mutex = Mutex()
    private var version = initial.summary.version
    private var committedText = initial.document.text
    private var latestText = committedText
    private var revision = initialRevision
    private var writtenRevision = -1L
    private var stopped = false
    private var draftTimer: Job? = null
    private var saveTimer: Job? = null
    private val id = initial.summary.id
    private val mutableStatus = MutableStateFlow(SaveStatus(indexPending = initial.summary.indexPending))
    val status = mutableStatus.asStateFlow()
    val fullyCommitted: Boolean get() = latestText == committedText && !stopped
    val writing: Boolean get() = mutex.isLocked

    fun changed(text: String) {
        if (text == latestText) return
        latestText = text
        revision++
        mutableStatus.value = mutableStatus.value.copy(label = "未保存", draftProtected = false)
        draftTimer?.cancel()
        saveTimer?.cancel()
        // 只取消等待计时，不取消已经开始的持久化操作。
        draftTimer = scope.launch { delay(300); scope.launch { protectDraft() } }
        saveTimer = scope.launch { delay(800); scope.launch { flush() } }
    }

    suspend fun protectDraft(): Boolean = mutex.withLock { writeLatestDraft() }

    private suspend fun writeLatestDraft(): Boolean {
        if (latestText == committedText && writtenRevision < 0) {
            mutableStatus.value = SaveStatus(indexPending = mutableStatus.value.indexPending)
            return true
        }
        if (writtenRevision == revision) return true
        val capturedRevision = revision
        val result = repository.writeDraft(Draft(id, version, capturedRevision, RawDocument(latestText)))
        if (result is NoteResult.Success) {
            writtenRevision = capturedRevision
            mutableStatus.value = mutableStatus.value.copy(draftProtected = capturedRevision == revision)
            return true
        }
        mutableStatus.value = mutableStatus.value.copy(label = "保存失败", error = "草稿写入失败：${result.explanation()}", draftProtected = false)
        return false
    }

    suspend fun flush(): Boolean = mutex.withLock {
        if (stopped) return@withLock false
        if (latestText == committedText && writtenRevision < 0) {
            mutableStatus.value = SaveStatus(indexPending = mutableStatus.value.indexPending)
            return@withLock true
        }
        val capturedText = latestText
        val capturedRevision = revision
        val protected = writeLatestDraft()
        mutableStatus.value = mutableStatus.value.copy(label = "保存中")
        val result = repository.save(id, version, RawDocument(capturedText), if (protected) capturedRevision else null)
        when (result) {
            is NoteResult.Committed -> {
                version = result.value.summary.version
                committedText = capturedText
                writtenRevision = -1
                mutableStatus.value = SaveStatus(
                    label = if (latestText == capturedText) "已保存" else "未保存",
                    draftProtected = latestText == capturedText,
                    indexPending = result.indexPending,
                )
                if (latestText != capturedText) {
                    // 较新输入可能已写成基于旧版本的草稿，必须用新修订号重新保护。
                    revision++
                    writeLatestDraft()
                }
                latestText == capturedText
            }
            is NoteResult.Conflict -> {
                stopped = true
                mutableStatus.value = mutableStatus.value.copy(label = "版本冲突", error = "文件已有更新，当前内容已保留；请另存为新笔记。", conflict = true)
                false
            }
            else -> {
                if (result is NoteResult.StorageFailure && result.recoveryRequired) {
                    // 取消或提交后异常不代表未写入，先恢复并读取最终事实。
                    repository.initialize()
                    val actual = repository.read(id)
                    if (actual is NoteResult.Success) {
                        if (actual.value.document.text == capturedText) {
                            version = actual.value.summary.version
                            committedText = capturedText
                            writtenRevision = -1
                            if (latestText != capturedText) { revision++; writeLatestDraft() }
                            mutableStatus.value = SaveStatus(label = if (latestText == capturedText) "已保存" else "未保存", draftProtected = latestText == capturedText || writtenRevision == revision, indexPending = actual.value.summary.indexPending)
                            return@withLock latestText == capturedText
                        }
                        if (actual.value.summary.version != version) stopped = true
                    } else stopped = true
                }
                mutableStatus.value = mutableStatus.value.copy(label = "保存失败", error = result.explanation(), conflict = stopped)
                false
            }
        }
    }

    /** 冲突草稿未被确认前不触发自动保存。 */
    fun blockAutomaticSave() { stopped = true }
}

internal fun NoteResult<*>.explanation(): String = when (this) {
    is NoteResult.StorageFailure -> reason
    is NoteResult.InvalidPath -> reason
    is NoteResult.InvalidPosition -> reason
    NoteResult.PathConflict -> "名称已存在（含大小写冲突），请换一个名称"
    NoteResult.NotFound -> "笔记或附件不存在"
    is NoteResult.Conflict -> "正文版本冲突"
    NoteResult.DraftConflict -> "草稿修订冲突，未覆盖已有草稿"
    else -> "操作未完成"
}
