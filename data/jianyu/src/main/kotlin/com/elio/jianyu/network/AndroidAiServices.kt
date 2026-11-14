package com.elio.jianyu.network

import android.content.Context

class AndroidAiServices(context: Context) : AiServices {
    private val context = context.applicationContext
    init { AiManager.initialize(this.context) }
    override val configuration: AiConfiguration = AiManager.configuration(this.context)
    override fun keys(provider: AiProvider): ProviderKeys = AiManager.keys(context, provider)
    override suspend fun validateKeys(provider: AiProvider, keyIds: List<String>) {
        AiManager.validateKeys(context, provider, keyIds)
    }
    override suspend fun validateKey(provider: AiProvider, keyId: String) {
        AiManager.validateKey(context, provider, keyId)
    }
}
