package com.elio.jianyu

import android.content.Context

/** 库不再拥有独立 App 版本，备份记录实际安装宿主的身份。 */
internal data class InstalledAppVersion(val name: String, val code: Long) {
    companion object {
        @Suppress("DEPRECATION")
        fun read(context: Context): InstalledAppVersion {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            return InstalledAppVersion(info.versionName.orEmpty(), code)
        }
    }
}
