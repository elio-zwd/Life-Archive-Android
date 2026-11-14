package com.elio.jianyu.storage

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException

/** 两个业务共享进程和包名，存储范围仍须由数据所有者显式限定。 */
val Context.jianyuFilesDir: File get() = filesDir.jianyuDirectory()
val Context.jianyuCacheDir: File get() = cacheDir.jianyuDirectory()
val Context.jianyuNoBackupFilesDir: File get() = noBackupFilesDir.jianyuDirectory()

fun Context.jianyuExternalFilesDir(type: String?): File =
    (getExternalFilesDir(type) ?: throw IOException("见域外部文件目录不可用")).jianyuDirectory()

fun Context.jianyuPreferences(name: String, mode: Int): SharedPreferences {
    require(name.matches(Regex("[A-Za-z0-9_.-]+"))) { "无效的见域设置名称" }
    return getSharedPreferences("jianyu_$name", mode)
}

private fun File.jianyuDirectory(): File = File(this, "jianyu").also {
    if (!it.isDirectory && !it.mkdirs() && !it.isDirectory) throw IOException("无法创建见域存储目录")
}
