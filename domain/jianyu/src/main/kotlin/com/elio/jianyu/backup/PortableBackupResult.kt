package com.elio.jianyu.backup

import java.io.IOException
import kotlinx.serialization.Serializable

data class PortableBackupResult(
    val bytesWritten: Long,
    val destination: String,
)

/** Public export facade. Import remains intentionally unavailable until PR09-14A/14B. */
