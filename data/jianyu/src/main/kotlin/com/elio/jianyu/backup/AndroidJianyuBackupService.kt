package com.elio.jianyu.backup

import android.content.Context
import android.net.Uri

class AndroidJianyuBackupService(context: Context) : JianyuBackupService {
    private val context = context.applicationContext
    private val operations = com.elio.jianyu.backup.complete.AndroidJianyuCompleteBackup.forContext(this.context)
    override val complete: JianyuCompleteBackup = operations
    override fun listSnapshots() = SnapshotCatalog.list(context)
    override suspend fun createDeviceSnapshot() = operations.legacy { DeviceSnapshotOperations.createSnapshot(context).snapshotId }
    override fun deleteSnapshot(snapshotId: String) = SnapshotCatalog.delete(context, snapshotId)
    override fun updateSnapshotNote(snapshotId: String, note: String) = SnapshotCatalog.updateNote(context, snapshotId, note)
    override suspend fun exportPortable(password: String, uri: String) = operations.legacy { PortableBackupService(context).createToUri(password, Uri.parse(uri)) }
}
