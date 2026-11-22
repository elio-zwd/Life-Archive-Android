package com.elio.lifearchive

import android.content.Context
import com.elio.lifearchive.data.ai.SharedAiRuntime
import com.elio.lifearchive.data.pet.AndroidCompanionRepository
import com.elio.lifearchive.domain.pet.CompanionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object CompanionModule {
    @Provides @Singleton fun companion(@ApplicationContext context: Context): CompanionRepository =
        AndroidCompanionRepository(context, SharedAiRuntime.get(context))
}
