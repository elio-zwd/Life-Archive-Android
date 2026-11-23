package com.elio.lifearchive.data.pet

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.elio.lifearchive.domain.pet.ImportedLive2DModel
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * 将用户通过系统目录选择器（SAF）或本地目录提供的 Live2D 模型复制到应用私有目录，
 * 校验 .model3.json、.moc3 与纹理文件完整性，确保页面与悬浮窗服务可脱离外部权限稳定读取。
 */
internal class LocalLive2DModelStore(
    private val modelsRoot: File,
    private val resolver: ContentResolver? = null,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "companion/models"),
        context.applicationContext.contentResolver,
    )

    private data class SourceEntry(val relativePath: String, val open: () -> InputStream)

    fun list(): List<ImportedLive2DModel> {
        if (!modelsRoot.isDirectory) return emptyList()
        return modelsRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(ID_PREFIX) }
            .mapNotNull { dir -> readManifest(dir) }
            .sortedBy { it.importedAt }
    }

    fun importFromTreeUri(treeUriString: String): ImportedLive2DModel {
        val raw = treeUriString.trim()
        require(raw.isNotEmpty()) { "未选择模型文件夹。" }
        require(list().size < MAX_IMPORTED_MODELS) { "本地导入模型已达 $MAX_IMPORTED_MODELS 个上限，请先移除不用的模型。" }

        val uri = Uri.parse(raw)
        val entries = when (uri.scheme?.lowercase()) {
            "file" -> collectFromDirectory(File(requireNotNull(uri.path) { "目录路径无效。" }))
            "content" -> collectFromDocumentTree(requireNotNull(resolver) { "当前环境不支持读取系统目录。" }, uri)
            else -> throw IllegalArgumentException("不支持的目录地址，请重新选择模型文件夹。")
        }
        return stageValidateAndCommit(entries)
    }

    fun delete(modelId: String): Boolean {
        val id = modelId.trim()
        if (!id.startsWith(ID_PREFIX) || '/' in id || '\\' in id || ".." in id) return false
        val target = File(modelsRoot, id)
        if (!isChildPath(modelsRoot, target) || !target.exists()) return false
        return target.deleteRecursively()
    }

    private fun stageValidateAndCommit(entries: List<SourceEntry>): ImportedLive2DModel {
        require(entries.isNotEmpty()) { "所选文件夹为空，请选择包含 .model3.json 和 .moc3 的模型目录。" }
        val (modelRootPrefix, modelJsonEntry) = locateModelJson(entries)
        val scopedEntries = entries.mapNotNull { entry ->
            val rel = if (modelRootPrefix.isEmpty()) entry.relativePath
            else if (entry.relativePath.startsWith(modelRootPrefix)) entry.relativePath.removePrefix(modelRootPrefix)
            else return@mapNotNull null
            val safe = sanitizeRelativePath(rel) ?: return@mapNotNull null
            entry.copy(relativePath = safe)
        }
        require(scopedEntries.size <= MAX_FILES_PER_MODEL) { "文件夹内文件超过 $MAX_FILES_PER_MODEL 个，请仅选择单个 Live2D 模型目录。" }

        val id = ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(10)
        modelsRoot.mkdirs()
        val staging = File(modelsRoot, ".staging-$id")
        val target = File(modelsRoot, id)
        try {
            staging.mkdirs()
            var totalBytes = 0L
            for (entry in scopedEntries) {
                val dest = File(staging, entry.relativePath)
                require(isChildPath(staging, dest)) { "检测到不安全的文件路径：${entry.relativePath}" }
                dest.parentFile?.mkdirs()
                entry.open().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            totalBytes += read
                            require(totalBytes <= MAX_BYTES_PER_MODEL) { "模型体积超过 80 MB 上限，请确认选择的是单个 Live2D 模型文件夹。" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }

            val modelJsonRel = modelJsonEntry.relativePath.removePrefix(modelRootPrefix)
            val modelJsonFile = File(staging, modelJsonRel)
            validateModelPackage(staging, modelJsonFile)

            val rawName = modelJsonFile.name.removeSuffix(".model3.json").trim()
            val displayName = rawName.ifBlank { "本地模型" }.take(32)
            val importedAt = System.currentTimeMillis()
            val manifest = buildJsonObject {
                put("id", id)
                put("displayName", displayName)
                put("modelJson", modelJsonRel)
                put("importedAt", importedAt)
            }.toString()
            File(staging, MANIFEST_FILE).writeText(manifest, Charsets.UTF_8)

            check(staging.renameTo(target)) { "保存模型到私有目录失败，请重试。" }
            return ImportedLive2DModel(
                id = id,
                displayName = displayName,
                directoryPath = target.absolutePath,
                modelJson = modelJsonRel,
                importedAt = importedAt,
            )
        } catch (error: Exception) {
            staging.deleteRecursively()
            target.deleteRecursively()
            throw error
        }
    }

    private fun locateModelJson(entries: List<SourceEntry>): Pair<String, SourceEntry> {
        val candidates = entries.filter { it.relativePath.endsWith(".model3.json", ignoreCase = true) }
        val topLevel = candidates.firstOrNull { '/' !in it.relativePath }
        if (topLevel != null) return "" to topLevel
        val oneLevelDown = candidates.firstOrNull { it.relativePath.count { ch -> ch == '/' } == 1 }
        if (oneLevelDown != null) {
            val prefix = oneLevelDown.relativePath.substringBefore('/') + "/"
            return prefix to oneLevelDown
        }
        throw IllegalArgumentException("未在所选目录找到 .model3.json，请选择解压后的单个 Live2D 模型文件夹。")
    }

    private fun validateModelPackage(root: File, modelJsonFile: File) {
        require(modelJsonFile.isFile) { "缺少模型描述文件（.model3.json）。" }
        val rootObj = runCatching { Json.parseToJsonElement(modelJsonFile.readText(Charsets.UTF_8)).jsonObject }
            .getOrElse { throw IllegalArgumentException("模型描述文件（.model3.json）格式无效。") }
        val refs = rootObj["FileReferences"]?.jsonObject
            ?: throw IllegalArgumentException(".model3.json 缺少 FileReferences 字段。")

        val mocRel = refs["Moc"]?.jsonPrimitive?.contentOrNull?.let(::sanitizeRelativePath)
            ?: throw IllegalArgumentException(".model3.json 未指定有效的 .moc3 文件。")
        require(mocRel.endsWith(".moc3", ignoreCase = true)) { "模型入口必须为 .moc3 文件：$mocRel" }
        val mocFile = File(root, mocRel)
        require(isChildPath(root, mocFile) && mocFile.isFile && mocFile.length() > 0L) {
            "缺少有效的模型文件：$mocRel"
        }

        val textures = refs["Textures"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }.orEmpty()
        require(textures.isNotEmpty()) { ".model3.json 缺少纹理列表（Textures）。" }
        textures.forEach { tex ->
            val safeTex = sanitizeRelativePath(tex) ?: throw IllegalArgumentException("纹理路径无效：$tex")
            val texFile = File(root, safeTex)
            require(isChildPath(root, texFile) && texFile.isFile && texFile.length() > 0L) {
                "缺少模型纹理文件：$safeTex"
            }
        }
    }

    private fun collectFromDirectory(root: File): List<SourceEntry> {
        require(root.isDirectory) { "所选路径不是有效文件夹。" }
        val result = mutableListOf<SourceEntry>()
        fun walk(dir: File, prefix: String, depth: Int) {
            if (depth > MAX_SCAN_DEPTH) return
            dir.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                if (child.name.startsWith(".")) return@forEach
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    walk(child, rel, depth + 1)
                } else if (child.isFile) {
                    require(result.size < MAX_SCAN_FILES) { "所选目录文件过多，请直接选择单个 Live2D 模型文件夹。" }
                    result += SourceEntry(rel) { FileInputStream(child) }
                }
            }
        }
        walk(root, "", 0)
        return result
    }

    private fun collectFromDocumentTree(resolver: ContentResolver, treeUri: Uri): List<SourceEntry> {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw IllegalArgumentException("无法读取所选目录，请重新授权选择文件夹。") }
        val result = mutableListOf<SourceEntry>()
        fun walk(parentDocId: String, prefix: String, depth: Int) {
            if (depth > MAX_SCAN_DEPTH) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol)?.trim().orEmpty()
                    if (name.isEmpty() || name.startsWith(".")) continue
                    val mime = cursor.getString(mimeCol)
                    val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(docId, rel, depth + 1)
                    } else {
                        require(result.size < MAX_SCAN_FILES) { "所选目录文件过多，请直接选择单个 Live2D 模型文件夹。" }
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        result += SourceEntry(rel) {
                            resolver.openInputStream(docUri) ?: throw IllegalStateException("无法读取文件：$rel")
                        }
                    }
                }
            }
        }
        walk(rootDocId, "", 0)
        return result
    }

    private fun readManifest(dir: File): ImportedLive2DModel? {
        val file = File(dir, MANIFEST_FILE)
        if (!file.isFile) return null
        val obj = runCatching { Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it == dir.name } ?: return null
        val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val modelJson = obj["modelJson"]?.jsonPrimitive?.contentOrNull?.let(::sanitizeRelativePath) ?: return null
        val importedAt = obj["importedAt"]?.jsonPrimitive?.longOrNull ?: 0L
        val jsonFile = File(dir, modelJson)
        if (!isChildPath(dir, jsonFile) || !jsonFile.isFile) return null
        return ImportedLive2DModel(id, displayName, dir.absolutePath, modelJson, importedAt)
    }

    private fun sanitizeRelativePath(raw: String): String? {
        val normalized = raw.replace('\\', '/').trim().removePrefix("./")
        if (normalized.isEmpty() || normalized.startsWith('/')) return null
        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }

    private fun isChildPath(parent: File, child: File): Boolean {
        val parentPath = parent.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        return childPath.startsWith(parentPath) && childPath != parentPath
    }

    companion object {
        private const val ID_PREFIX = "local-"
        private const val MANIFEST_FILE = ".life-archive-model.json"
        private const val MAX_IMPORTED_MODELS = 20
        private const val MAX_FILES_PER_MODEL = 400
        private const val MAX_SCAN_FILES = 600
        private const val MAX_SCAN_DEPTH = 4
        private const val MAX_BYTES_PER_MODEL = 80L * 1024L * 1024L
    }
}
