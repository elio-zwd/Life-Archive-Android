package com.elio.jianyu.network

import android.content.Context
import com.elio.lifearchive.data.ai.SharedAiRuntime

/** 见域兼容入口；凭据、配置和实际重试执行均由公共 AI 唯一持有。 */
object AiManager {
    fun initialize(context: Context) { attachDiagnosticLookup(context) }
    fun configuration(context: Context): AiConfigurationRepository = SharedAiRuntime.get(context).configuration
    internal fun reloadConfiguration(context: Context) { initialize(context) }
    fun keys(context: Context, provider: AiProvider): ProviderKeyRepository = SharedAiRuntime.get(context).keys(provider)
    fun keysForUseCase(context: Context, useCase: AiUseCase): ProviderKeyRepository =
        keys(context, configuration(context).configuration.value.modelFor(useCase).provider)
    internal fun requests(context: Context, provider: AiProvider): AiRequestExecutor =
        AiRequestExecutor(SharedAiRuntime.get(context).executor(provider))
    suspend fun validateKey(context: Context, provider: AiProvider, keyId: String): ApiKeyValidationState {
        SharedAiRuntime.get(context).validateKey(provider, keyId)
        return keys(context, provider).summaries.value.firstOrNull { it.id == keyId }?.validationState ?: ApiKeyValidationState.INVALID
    }
    suspend fun validateKeys(context: Context, provider: AiProvider, keyIds: List<String>) =
        SharedAiRuntime.get(context).validateKeys(provider, keyIds)

    // 仅供诊断脱敏查找显示名，不初始化或持有第二套凭据仓库。
    fun findKeyIdOrNull(secret: String): String? = keyLabelLookup?.invoke(secret)
    @Volatile private var keyLabelLookup: ((String) -> String?)? = null
    fun attachDiagnosticLookup(context: Context) {
        val runtime = SharedAiRuntime.get(context)
        keyLabelLookup = { secret -> AiProvider.entries.firstNotNullOfOrNull { runtime.keys(it).findKeyIdOrNull(secret) } }
    }
}
