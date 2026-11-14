package com.elio.jianyu.backup

object JianyuBackupFormat {
    const val portableMime = "application/vnd.jianyu.backup"
    const val portableExtension = ".jybak"
}

interface JianyuBackupService {
    val complete: JianyuCompleteBackup
    fun listSnapshots(): List<SnapshotCatalogEntry>
    suspend fun createDeviceSnapshot(): String
    fun deleteSnapshot(snapshotId: String)
    fun updateSnapshotNote(snapshotId: String, note: String)
    suspend fun exportPortable(password: String, uri: String): PortableBackupResult
}
