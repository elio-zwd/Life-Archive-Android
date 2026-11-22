package com.elio.lifearchive.data.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import java.io.File

/** 只在首次接管时读取旧位置；后续见域恢复写旧设置不会覆盖公共配置。 */
@Synchronized
internal fun Context.sharedAiPreferences(name: String): SharedPreferences {
    val target = getSharedPreferences("shared_ai_$name", Context.MODE_PRIVATE)
    if (!target.getBoolean("shared_owner_v1", false)) {
        val legacy = getSharedPreferences("jianyu_$name", Context.MODE_PRIVATE)
        val editor = target.edit()
        for ((key, value) in legacy.all) when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
        }
        check(editor.putBoolean("shared_owner_v1", true).commit()) { "公共 AI 配置接管失败" }
    }
    return target
}

@Synchronized
internal fun Context.sharedAiKeyFile(name: String): File {
    val directory = File(noBackupFilesDir, "shared-ai")
    check(directory.isDirectory || directory.mkdirs()) { "无法创建公共 AI 凭据目录" }
    val target = AtomicFile(File(directory, name))
    val legacy = AtomicFile(File(noBackupFilesDir, "jianyu/$name"))
    if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists() &&
        (legacy.baseFile.exists() || File(legacy.baseFile.path + ".bak").exists())) {
        val bytes = legacy.readFully()
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
    }
    // 只保留一个权威文件；先可靠落盘再删除旧密文，重启可重试。
    if (target.baseFile.exists()) legacy.delete()
    return target.baseFile
}
