package com.elio.jianyu.network

import kotlinx.coroutines.flow.StateFlow

interface AiConfiguration {
    val configuration: StateFlow<AiRuntimeConfiguration>
    fun selectProvider(useCase: AiUseCase, provider: AiProvider)
    fun selectModel(useCase: AiUseCase, model: AiModel)
    fun reset(): Boolean
}

/** 管理页只看摘要和执行明确动作，不向页面公开原始 Key 或请求租约。 */
interface ProviderKeys {
    val summaries: StateFlow<List<ApiKeySummary>>
    val storageError: StateFlow<String?>
    fun importBatch(raw: String): BatchImportResult
    fun setDisabled(keyId: String, disabled: Boolean)
    fun delete(keyId: String): Boolean
    fun clear(): Boolean
    fun hasAvailableKeys(): Boolean
    fun sessionKeyAccount(sessionId: Long): String?
}

interface AiServices {
    val configuration: AiConfiguration
    fun keys(provider: AiProvider): ProviderKeys
    suspend fun validateKeys(provider: AiProvider, keyIds: List<String>)
    suspend fun validateKey(provider: AiProvider, keyId: String)
}
