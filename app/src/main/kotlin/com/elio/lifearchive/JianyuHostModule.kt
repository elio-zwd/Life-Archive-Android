package com.elio.lifearchive

import android.app.Application
import android.content.Context
import com.elio.jianyu.runtime.AndroidJianyuHostRuntime
import com.elio.jianyu.runtime.JianyuHostRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object JianyuHostModule {
    @Provides @Singleton
    fun jianyu(@ApplicationContext context: Context): JianyuHostRuntime =
        AndroidJianyuHostRuntime(context.applicationContext as Application)
}
