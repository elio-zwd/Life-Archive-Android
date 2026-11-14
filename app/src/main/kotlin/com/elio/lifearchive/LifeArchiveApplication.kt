package com.elio.lifearchive

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LifeArchiveApplication : Application(), androidx.work.Configuration.Provider {
    // 移除默认启动器后，仅在见域实际使用后台任务时创建 WorkManager。
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()
}
