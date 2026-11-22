package com.elio.jianyu.network

// 唯一公共 AI 实现；命名空间保留以兼容已有调用方。
import com.elio.lifearchive.data.ai.sharedAiPreferences
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AiConfigurationRepository(context: Context) : AiConfiguration {
    private companion object {
        const val PREFS_NAME = "ai_runtime_configuration"
        const val KEY_PROVIDER = "provider"
        const val KEY_MODEL = "model"
    }

    private val preferences = context.applicationContext.sharedAiPreferences(PREFS_NAME)
    private val _configuration = MutableStateFlow(loadConfiguration())
    override val configuration: StateFlow<AiRuntimeConfiguration> = _configuration.asStateFlow()

    override fun selectProvider(useCase: AiUseCase, provider: AiProvider) {
        require(provider in useCase.supportedProviders) { "该用途不支持 ${provider.displayName}" }
        selectModel(useCase, defaultModel(useCase, provider))
    }

    override fun selectModel(useCase: AiUseCase, model: AiModel) {
        require(model.provider in useCase.supportedProviders) { "该用途不支持 ${model.provider.displayName}" }
        save(_configuration.value.copyWith(useCase, model))
    }

    override fun reset(): Boolean {
        val committed = preferences.edit().clear().putBoolean("shared_owner_v1", true).commit()
        if (committed) {
            _configuration.value = defaultConfiguration()
        }
        return committed
    }

    private fun loadConfiguration(): AiRuntimeConfiguration {
        return AiRuntimeConfiguration(
            AiUseCase.entries.associateWith { useCase ->
                preferences.getString("$KEY_MODEL.${useCase.name}", null)
                    ?.let { raw -> AiModel.entries.firstOrNull { it.name == raw } }
                    ?.takeIf { it.provider in useCase.supportedProviders }
                    ?: defaultModel(useCase)
            },
        )
    }

    private fun defaultConfiguration(): AiRuntimeConfiguration = AiRuntimeConfiguration(
        AiUseCase.entries.associateWith { useCase ->
            defaultModel(useCase)
        },
    )

    private fun save(configuration: AiRuntimeConfiguration) {
        val editor = preferences.edit()
        AiUseCase.entries.forEach { useCase ->
            editor.putString("$KEY_MODEL.${useCase.name}", configuration.modelFor(useCase).name)
        }
        check(editor.remove(KEY_PROVIDER).commit()) { "公共 AI 配置保存失败" }
        _configuration.value = configuration
    }
}

private fun AiRuntimeConfiguration.copyWith(
    useCase: AiUseCase,
    model: AiModel,
): AiRuntimeConfiguration = AiRuntimeConfiguration(
    AiUseCase.entries.associateWith { entry -> if (entry == useCase) model else modelFor(entry) },
)

private val GEMINI_MODELS_WITHOUT_MINIMAL_THINKING = setOf(
    AiModel.GEMINI_38_FLASH,
    AiModel.GEMINI_37_FLASH,
    AiModel.GEMINI_25_FLASH,
    AiModel.GEMINI_25_FLASH_LITE,
)

fun AiModel.geminiInteractionThinkingLevel(requestedLevel: String): String {
    require(provider == AiProvider.GEMINI) { "只有 Gemini 模型可以使用 Interactions thinking_level" }
    require(requestedLevel in setOf("minimal", "low", "medium", "high")) {
        "Gemini 不支持思考档位：$requestedLevel"
    }
    return if (requestedLevel == "minimal" && this in GEMINI_MODELS_WITHOUT_MINIMAL_THINKING) {
        "low"
    } else {
        requestedLevel
    }
}

fun defaultModel(provider: AiProvider): AiModel = when (provider) {
    AiProvider.GEMINI -> AiModel.GEMINI_38_FLASH
    AiProvider.DEEPSEEK -> AiModel.DEEPSEEK_V4_FLASH
}

fun defaultModel(useCase: AiUseCase): AiModel =
    defaultModel(useCase, useCase.supportedProviders.first())

private fun defaultModel(
    useCase: AiUseCase,
    provider: AiProvider,
): AiModel {
    require(provider in useCase.supportedProviders) { "该用途不支持 ${provider.displayName}" }
    return if (useCase == AiUseCase.WEB_GROUNDING && provider == AiProvider.GEMINI) {
        AiModel.GEMINI_25_FLASH
    } else {
        defaultModel(provider)
    }
}
