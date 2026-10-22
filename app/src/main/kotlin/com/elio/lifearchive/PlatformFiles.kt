package com.elio.lifearchive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.domain.notes.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun displayName(context: Context, uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "资料-${UUID.randomUUID()}"
internal fun selectedEntries(context: Context, uris: List<Uri>): List<ImportEntry> = uris.map { uri -> ImportEntry(displayName(context, uri)) { context.contentResolver.openInputStream(uri) ?: error("无法打开所选文件") } }
internal suspend fun directoryEntries(context: Context, tree: Uri): List<ImportEntry> = withContext(Dispatchers.IO) {
    val entries = mutableListOf<ImportEntry>(); val visited = mutableSetOf<String>()
    fun visit(id: String, parent: String) {
        require(visited.add(id) && visited.size <= 20000) { "目录循环或文件过多" }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val child = cursor.getString(0); val name = cursor.getString(1); val type = cursor.getString(2)
                if (name in setOf(".obsidian", ".git", ".trash")) continue
                val path = if (parent.isEmpty()) name else "$parent/$name"
                require(LibraryPath.isValid(path)) { "文件名不兼容 Windows/Android：$path" }
                if (type == DocumentsContract.Document.MIME_TYPE_DIR) visit(child, path)
                else { val uri = DocumentsContract.buildDocumentUriUsingTree(tree, child); entries += ImportEntry(path) { context.contentResolver.openInputStream(uri) ?: error("无法读取：$path") } }
            }
        } ?: error("目录不可读取")
    }
    visit(DocumentsContract.getTreeDocumentId(tree), "")
    entries
}
internal suspend fun openAttachment(context: Context, library: LibraryRepository, path: LibraryPath) {
    val (file, mime) = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "shared").also { check(it.isDirectory || it.mkdirs()) }
        cleanSharedCopies(directory)
        val resource = library.openResource(path)
        check(resource is NoteResult.Success) { "附件不可用：$resource" }
        val file = File(directory, "${UUID.randomUUID()}-${path.value.substringAfterLast('/')}")
        try { resource.value.stream.use { input -> file.outputStream().use { input.copyTo(it, 65536) } } }
        catch (e: Exception) { file.delete(); throw e }
        file to resource.value.info.mime
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
internal fun cleanSharedCopies(directory: File, now: Long = System.currentTimeMillis()) {
    // 外部阅读器可能仍持有授权；仅清理一天前的副本。
    val cutoff = now - 24L * 60 * 60 * 1000
    directory.listFiles().orEmpty().filter { it.isFile && it.lastModified() < cutoff }.forEach { it.delete() }
}
