package com.elio.jianyu.backup

import java.io.IOException
import kotlinx.serialization.Serializable

@Serializable
data class SnapshotCatalogEntry(
    val snapshotId: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val note: String = "",
)

/** Non-sensitive index; an entry is published only after full envelope verification. */
