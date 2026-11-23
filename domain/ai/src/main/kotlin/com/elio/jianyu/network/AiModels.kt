package com.elio.jianyu.network

// 公共 AI 契约保留原命名空间，避免改写现有见域调用方和持久化枚举。

enum class AiProvider(
    val displayName: String,
    val storageId: String,
) {
    GEMINI("Gemini", "gemini"),
    DEEPSEEK("DeepSeek", "deepseek"),
}

/** 用户可选择的文本模型。模型 ID 直接传给对应提供商。 */
enum class AiModel(
    val provider: AiProvider,
    val modelId: String,
    val displayName: String,
    val supportsWebGrounding: Boolean = false,
) {
    GEMINI_38_FLASH(
        provider = AiProvider.GEMINI,
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        supportsWebGrounding = true,
    ),
    GEMINI_37_FLASH(
        provider = AiProvider.GEMINI,
        modelId = "gemini-3.7-flash",
        displayName = "Gemini 3.7 Flash",
        supportsWebGrounding = true,
    ),
    GEMINI_36_FLASH(
        provider = AiProvider.GEMINI,
        modelId = "gemini-3.6-flash",
        displayName = "Gemini 3.6 Flash",
        supportsWebGrounding = true,
    ),
    GEMINI_35_FLASH(
        provider = AiProvider.GEMINI,
        modelId = "gemini-3.5-flash",
        displayName = "Gemini 3.5 Flash",
        supportsWebGrounding = true,
    ),
    GEMINI_35_FLASH_LITE(
        provider = AiProvider.GEMINI,
        modelId = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash Lite",
        supportsWebGrounding = true,
    ),
    GEMINI_31_FLASH_LITE(
        provider = AiProvider.GEMINI,
        modelId = "gemini-3.1-flash-lite",
        displayName = "Gemini 3.1 Flash Lite",
        supportsWebGrounding = true,
    ),
    GEMINI_25_FLASH(
        provider = AiProvider.GEMINI,
        modelId = "gemini-2.5-flash",
        displayName = "Gemini 2.5 Flash",
        supportsWebGrounding = true,
    ),
    GEMINI_25_FLASH_LITE(
        provider = AiProvider.GEMINI,
        modelId = "gemini-2.5-flash-lite",
        displayName = "Gemini 2.5 Flash Lite",
        supportsWebGrounding = true,
    ),
    DEEPSEEK_V4_FLASH(
        provider = AiProvider.DEEPSEEK,
        modelId = "deepseek-v4-flash",
        displayName = "DeepSeek V4 Flash",
    ),
    DEEPSEEK_V4_PRO(
        provider = AiProvider.DEEPSEEK,
        modelId = "deepseek-v4-pro",
        displayName = "DeepSeek V4 Pro",
    ),
}

data class AiRuntimeConfiguration(
    private val models: Map<AiUseCase, AiModel>,
) {
    fun modelFor(useCase: AiUseCase): AiModel = models.getValue(useCase)
}

/** 每一种文本调用用途都有独立模型选择，避免用全局开关隐式改变其他功能。 */
enum class AiUseCase(
    val displayName: String,
    val description: String,
    val supportedProviders: Set<AiProvider>,
) {
    SESSION_TITLE("对话标题", "首次提问后的会话标题提炼", setOf(AiProvider.GEMINI, AiProvider.DEEPSEEK)),
    MATERIAL_BROKER("联网决策", "判断联网检索需求并生成搜索任务", setOf(AiProvider.GEMINI, AiProvider.DEEPSEEK)),
    WEB_GROUNDING("联网检索", "调用 Google Search 获取实时信息", setOf(AiProvider.GEMINI)),
    ROUNDTABLE_ANSWER("对话角色回答", "Skill 角色的最终文本回答", setOf(AiProvider.GEMINI, AiProvider.DEEPSEEK)),
    ISSUE_EXECUTION("成果生成", "生成并整理可保存的成果内容", setOf(AiProvider.GEMINI, AiProvider.DEEPSEEK)),
    PET_REPLY("伙伴对话", "宠物日常交流与触摸回应", setOf(AiProvider.GEMINI, AiProvider.DEEPSEEK)),
    PET_GREETING("伙伴主动短句", "桌面陪伴的低频问候", setOf(AiProvider.GEMINI, AiProvider.DEEPSEEK)),
}

/** 只持久化用户明确选择的提供商与模型，不保存任何密钥。 */
