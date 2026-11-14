package com.elio.jianyu.storage

import android.content.Context
import java.io.File

/** 旧消息保存绝对音频路径，使用前仍必须核对该路径属于见域。 */
internal fun Context.ownedAudioFile(path: String): File? = runCatching {
    val file = File(path)
    if (!file.isAbsolute || !file.isFile) return@runCatching null
    val canonical = file.canonicalFile.toPath()
    val roots = mutableListOf(File(jianyuFilesDir, "jianyu-audio"), jianyuCacheDir)
    getExternalFilesDir(null)?.let { roots.add(File(it, "jianyu")) }
    file.takeIf { roots.any { root -> canonical.startsWith(root.canonicalFile.toPath()) && canonical != root.canonicalFile.toPath() } }
}.getOrNull()
