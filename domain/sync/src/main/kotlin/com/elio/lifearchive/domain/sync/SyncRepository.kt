package com.elio.lifearchive.domain.sync

import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.flow.StateFlow

enum class SyncChoice { PHONE, COMPUTER, SKIP }
data class SyncConflict(val key: String, val phone: SyncEntry?, val computer: SyncEntry?)
data class SyncState(
    val address: String = "", val paired: Boolean = false, val busy: Boolean = false,
    val message: String = "尚未连接电脑", val completed: Int = 0, val total: Int = 0,
    val conflicts: List<SyncConflict> = emptyList(), val issues: List<SyncIssue> = emptyList(),
    val uploaded: Int = 0, val downloaded: Int = 0, val transferredBytes: Long = 0,
    val trash: List<SyncTrashItem> = emptyList(),
)
interface SyncRepository {
    val state: StateFlow<SyncState>
    suspend fun connect(address: String, code: String)
    suspend fun testConnection()
    suspend fun synchronize(choices: Map<String, SyncChoice> = emptyMap())
    suspend fun refreshTrash()
    suspend fun restoreAttachment(token: String)
}

/** 首次没有基线时只有双方不同且都存在才冲突；不能推导历史删除。 */
enum class SyncDirection { SAME, TO_PHONE, TO_COMPUTER, CONFLICT }
fun direction(base: SyncEntry?, phone: SyncEntry?, computer: SyncEntry?): SyncDirection = when {
    phone == computer -> SyncDirection.SAME
    base == null -> when { phone == null -> SyncDirection.TO_PHONE; computer == null -> SyncDirection.TO_COMPUTER; else -> SyncDirection.CONFLICT }
    phone == base -> SyncDirection.TO_PHONE
    computer == base -> SyncDirection.TO_COMPUTER
    else -> SyncDirection.CONFLICT
}
