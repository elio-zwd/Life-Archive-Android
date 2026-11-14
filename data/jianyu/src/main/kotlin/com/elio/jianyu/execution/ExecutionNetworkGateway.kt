package com.elio.jianyu.execution
import android.content.Context
import com.elio.jianyu.domain.ExecutionParticipantSnapshotEntity
import com.elio.jianyu.network.AiModel
import com.elio.jianyu.network.AiManager
import com.elio.jianyu.network.AiProvider
import com.elio.jianyu.network.AiUseCase
import com.elio.jianyu.network.CreateInteractionRequest
import com.elio.jianyu.network.DeepSeekTransport
import com.elio.jianyu.network.GeminiRestTransport
import com.elio.jianyu.network.InteractionGenerationConfig
import com.elio.jianyu.network.ProviderKeyAttemptPlanner
import com.elio.jianyu.network.GeminiInteractionsTransport
import com.elio.jianyu.network.Tool
import com.elio.jianyu.network.keys.ApiKeyLease
import com.elio.jianyu.network.outputText
import com.elio.jianyu.network.geminiInteractionThinkingLevel
import com.elio.jianyu.roundtable.RequestBudgetTracker
import com.elio.jianyu.telemetry.InteractionChainStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive

class AiExecutionNetworkGateway(
    context: Context,
) : ExecutionNetworkGateway {
    private val appContext = context.applicationContext

    override suspend fun prepare(request: ExecutionNetworkRequest): PreparedExecutionNetworkCall {
        val model = AiModel.entries.firstOrNull { it.modelId == request.model }
            ?: throw IllegalArgumentException("未配置可执行的 AI 模型")
        val keyRepository = AiManager.keys(appContext, model.provider)
        val baseAttemptPlan = keyRepository.createAttemptPlan(request.sessionId)
        val preferredKeyId = ProviderKeyAttemptPlanner.preferredKeyIdForPosition(
            basePlan = baseAttemptPlan,
            participantPosition = request.participant.position,
        )
        val attemptPlan = keyRepository.createAttemptPlan(
            sessionId = request.sessionId,
            preferredKeyId = preferredKeyId,
        )
        if (attemptPlan.isEmpty()) throw NoExecutionApiKeyException()
        val webModel = if (request.searchMode == SearchMode.OFF) {
            null
        } else {
            AiManager.configuration(appContext).configuration.value
                .modelFor(AiUseCase.WEB_GROUNDING)
        }
        val webAttemptPlan = webModel?.let { selectedModel ->
            val webKeyRepository = AiManager.keys(appContext, selectedModel.provider)
            val baseWebPlan = webKeyRepository.createAttemptPlan(request.sessionId)
            val preferredWebKeyId = ProviderKeyAttemptPlanner.preferredKeyIdForPosition(
                basePlan = baseWebPlan,
                participantPosition = request.participant.position,
            )
            webKeyRepository.createAttemptPlan(
                sessionId = request.sessionId,
                preferredKeyId = preferredWebKeyId,
            )
        }.orEmpty()
        if (request.searchMode == SearchMode.ON && webAttemptPlan.isEmpty()) {
            throw NoExecutionApiKeyException()
        }

        return PreparedExecutionNetworkCall { onAttemptStarted, onTextUpdate ->
            val webGrounding = webModel?.takeIf { webAttemptPlan.isNotEmpty() }?.let { selectedModel ->
                resolveWebGrounding(
                    request = request,
                    model = selectedModel,
                    attemptPlan = webAttemptPlan,
                    onAttemptStarted = onAttemptStarted,
                )
            }.orEmpty()
            val systemInstruction = request.modelRequest.systemInstruction +
                request.searchMode.mainInstruction(webGrounding)
            when (model.provider) {
                AiProvider.GEMINI -> {
                    val response = GeminiInteractionsTransport.createInteraction(
                        context = appContext,
                        request = CreateInteractionRequest(
                            model = model.modelId,
                            input = JsonPrimitive(request.modelRequest.userContent),
                            systemInstruction = systemInstruction,
                            generationConfig = InteractionGenerationConfig(
                                maxOutputTokens = request.maxOutputTokens,
                                thinkingLevel = model.geminiInteractionThinkingLevel(request.thinkingLevel),
                            ),
                            store = true,
                        ),
                        sessionId = request.sessionId,
                        attemptPlan = attemptPlan,
                        tracker = RequestBudgetTracker(),
                        operationName = OPERATION_NAME,
                        prepareRequestForKey = { lease, interactionRequest ->
                            interactionRequest.copy(previousInteractionId = InteractionChainStore.get(
                                request.sessionId, "${request.interactionChainKey}:${lease.keyId}",
                            ))
                        },
                        isRequired = true,
                        reserveForRequired = 0,
                        onAttemptStarted = onAttemptStarted,
                        onTextUpdate = onTextUpdate,
                    )
                    if (response.status == "completed") {
                        InteractionChainStore.put(
                            request.sessionId, "${request.interactionChainKey}:${response.keyId}", response.id,
                        )
                    }
                    ExecutionNetworkResult(
                        providerInteractionId = response.id,
                        outputText = response.outputText,
                        providerModel = response.model,
                    )
                }
                AiProvider.DEEPSEEK -> {
                    val response = DeepSeekTransport.createChatCompletion(
                        context = appContext,
                        sessionId = request.sessionId,
                        attemptPlan = attemptPlan,
                        model = model,
                        systemInstruction = systemInstruction,
                        userContent = request.modelRequest.userContent,
                        maxOutputTokens = request.maxOutputTokens,
                        thinkingLevel = request.thinkingLevel,
                        operationName = OPERATION_NAME,
                        tracker = RequestBudgetTracker(),
                        onAttemptStarted = onAttemptStarted,
                        onTextUpdate = onTextUpdate,
                    )
                    val outputText = response.choices.firstOrNull()?.message?.content
                        ?.takeIf(String::isNotBlank)
                        ?: throw ExecutionEmptyResponseException()
                    ExecutionNetworkResult(
                        providerInteractionId = response.id,
                        outputText = outputText,
                        providerModel = response.model,
                    )
                }
            }
        }
    }

    private suspend fun resolveWebGrounding(
        request: ExecutionNetworkRequest,
        model: AiModel,
        attemptPlan: List<ApiKeyLease>,
        onAttemptStarted: suspend () -> Unit,
    ): String = try {
        GeminiRestTransport.createInteraction(
            context = appContext,
            request = CreateInteractionRequest(
                model = model.modelId,
                input = JsonPrimitive(
                    "请先判断是否需要联网检索，再针对以下议题提供可供后续回答使用的事实摘要。\n" +
                        "用户问题：${request.modelRequest.userContent}",
                ),
                systemInstruction = request.searchMode.webGroundingInstruction,
                tools = listOf(Tool(type = "google_search")),
                store = false,
            ),
            sessionId = request.sessionId,
            attemptPlan = attemptPlan,
            tracker = RequestBudgetTracker(),
            operationName = "$WEB_GROUNDING_OPERATION-${request.participant.sourceId}",
            isRequired = request.searchMode == SearchMode.ON,
            onAttemptStarted = onAttemptStarted,
        ).outputText
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (request.searchMode == SearchMode.ON) throw error
        ""
    }

    private companion object {
        const val OPERATION_NAME = "JianyuExecution"
        const val WEB_GROUNDING_OPERATION = "JianyuWebGrounding"
    }
}

private val SearchMode.webGroundingInstruction: String
    get() = when (this) {
        SearchMode.AUTO -> "仅在问题依赖时效性或外部可核验事实时使用 Google Search；否则说明无需联网。"
        SearchMode.ON -> "用户已开启联网搜索。必须使用 Google Search 检索或核验后，给出带来源线索的事实摘要。"
        SearchMode.OFF -> error("关闭联网时不得请求联网检索")
    }

private fun SearchMode.mainInstruction(webGrounding: String): String = when (this) {
    SearchMode.OFF -> "\n\n本次执行未提供联网资料。不得声称已经联网检索、实时核验或访问某个来源。"
    SearchMode.AUTO,
    SearchMode.ON -> if (webGrounding.isBlank()) {
        "\n\n本次未取得联网资料。不得声称已经联网检索、实时核验或访问某个来源。"
    } else {
        "\n\n以下是已独立检索的联网资料；仅可据此表述已核验的实时事实：\n$webGrounding"
    }
}
