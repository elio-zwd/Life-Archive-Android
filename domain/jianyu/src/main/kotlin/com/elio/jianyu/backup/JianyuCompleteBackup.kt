package com.elio.jianyu.backup

import kotlinx.coroutines.flow.StateFlow

data class JianyuCompletePreview(
    val token: String,
    val sourcePackage: String,
    val sourceVersion: String,
    val createdAt: Long,
    val recordCounts: Map<String, Long>,
    val attachmentCount: Int,
    val attachmentBytes: Long,
    val notices: List<String>,
    val targetHasData: Boolean,
    val targetBackupReady: Boolean = !targetHasData,
)

sealed interface JianyuCompleteOperation {
    data object Idle : JianyuCompleteOperation
    data class Busy(val message: String) : JianyuCompleteOperation
    data class Finished(val message: String) : JianyuCompleteOperation
    data class Failed(val message: String) : JianyuCompleteOperation
}

/** 导出／提交独立于页面作用域；预览没有写入当前业务数据的权限。 */
interface JianyuCompleteBackup {
    val operation: StateFlow<JianyuCompleteOperation>
    val preview: StateFlow<JianyuCompletePreview?>
    suspend fun inspect(uri: String, password: String)
    suspend fun dismissPreview()
    fun startExport(uri: String, password: String)
    fun startRestore(token: String, replaceExisting: Boolean)
}
