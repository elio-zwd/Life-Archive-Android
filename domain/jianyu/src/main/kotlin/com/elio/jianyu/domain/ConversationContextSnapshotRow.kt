package com.elio.jianyu.domain

/** 复用已有使用快照，避免另存正文而绕过资料的彻底删除。 */
data class ConversationContextSnapshotRow(
    val id: String,
    val sourceType: String,
    val sourceId: String?,
    val title: String,
    val sourceKind: String,
    val sourceLocator: String?,
    val content: String?,
    val contentHash: String,
    val contentState: String,
    val confirmedAt: Long,
    val networkAllowed: Boolean,
    val sensitive: Boolean,
)
