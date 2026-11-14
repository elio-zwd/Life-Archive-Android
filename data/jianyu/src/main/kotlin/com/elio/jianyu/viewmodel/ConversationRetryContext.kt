package com.elio.jianyu.viewmodel

import com.elio.jianyu.domain.ContextContentHasher
import com.elio.jianyu.domain.ContextSourceType
import com.elio.jianyu.domain.ConversationContextSnapshotRow
import kotlinx.serialization.json.Json

/** 仅恢复原回复绑定的快照；null 表示无法完整恢复，空列表表示原请求无需参考内容。 */
internal fun restoreConversationRetryContext(
    snapshotIdsJson: String?,
    snapshots: List<ConversationContextSnapshotRow>,
): List<ConversationContextSelection>? {
    // 旧记录没有绑定信息时，只有不存在任何参考使用记录，才能确定无需恢复资料。
    if (snapshotIdsJson == null) return if (snapshots.isEmpty()) emptyList() else null
    val ids = try {
        Json.decodeFromString<List<String>>(snapshotIdsJson)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (ids.distinct().size != ids.size) return null
    val byId = snapshots.associateBy { it.id }
    return ids.mapIndexed { index, id ->
        val snapshot = byId[id] ?: return null
        val sourceType = ContextSourceType.entries.firstOrNull { it.storageValue == snapshot.sourceType }
            ?: return null
        val content = snapshot.content?.takeIf { it.isNotBlank() } ?: return null
        if (
            snapshot.contentState != "available" || !snapshot.networkAllowed ||
            snapshot.contentHash != ContextContentHasher.hash(content)
        ) return null
        ConversationContextSelection(
            sourceType = sourceType,
            sourceId = snapshot.sourceId ?: return null,
            title = snapshot.title,
            content = content,
            sourceKind = snapshot.sourceKind,
            sourceLocator = snapshot.sourceLocator,
            expectedSourceHash = snapshot.contentHash,
            expectedSourceUpdatedAt = 0L,
            confirmationOrder = index,
            networkAllowed = snapshot.networkAllowed,
            sensitive = snapshot.sensitive,
            sensitiveConfirmed = snapshot.sensitive,
            confirmedAt = snapshot.confirmedAt,
        )
    }
}
