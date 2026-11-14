package com.elio.jianyu.viewmodel

import android.content.Context
import com.elio.jianyu.data.ChatDao
import com.elio.jianyu.domain.ContextContentHasher
import com.elio.jianyu.network.AiModel
import com.elio.jianyu.network.completeInteractionWithContinuation
import com.elio.jianyu.network.CreateInteractionRequest
import com.elio.jianyu.network.GeminiInteractionsTransport
import com.elio.jianyu.network.InteractionGenerationConfig
import com.elio.jianyu.network.InteractionPostBudget
import com.elio.jianyu.network.StreamedInteraction
import com.elio.jianyu.network.geminiInteractionThinkingLevel
import com.elio.jianyu.network.keys.ApiKeyLease
import com.elio.jianyu.network.recordConversationPhase
import com.elio.jianyu.roundtable.CONVERSATION_COMPACTION_PROMPT
import com.elio.jianyu.roundtable.ConversationContextInput
import com.elio.jianyu.roundtable.ConversationContextManager
import com.elio.jianyu.roundtable.ConversationDeadline
import com.elio.jianyu.roundtable.ConversationStageTimeoutException
import com.elio.jianyu.roundtable.ConversationTimeouts
import com.elio.jianyu.roundtable.RequestBudgetTracker
import com.elio.jianyu.roundtable.recoverConversationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

/** 对话层持有记忆边界；网络传输只接收显式的本轮输入与 previous_interaction_id。 */
internal class GeminiConversationResponder(
    private val context: Context,
    private val chatDao: ChatDao,
) {
    private val memory = ConversationContextManager(
        load = chatDao::getConversationSummary,
        save = chatDao::saveConversationSummary,
        report = { phase, duration, success -> recordConversationPhase(phase, duration, success) },
    )

    fun clearSession(sessionId: Long) = memory.clearSession(sessionId)
    fun clearAll() = memory.clearAll()

    fun preferredKeyId(sessionId: Long, characterId: String): String? =
        memory.preferredKeyId(sessionId, characterId)

    suspend fun answer(
        sessionId: Long,
        characterId: String,
        questionId: Long,
        prompt: String,
        selectedContext: String,
        selectedSnapshotIds: List<String>,
        systemInstruction: String,
        model: AiModel,
        thinkingLevel: String,
        maxOutputTokens: Int,
        attemptPlan: List<ApiKeyLease>,
        tracker: RequestBudgetTracker,
        onAttemptStarted: suspend () -> Unit,
        onTextUpdate: suspend (String) -> Unit,
    ): String {
        require(attemptPlan.isNotEmpty()) { "没有可用的 Gemini 密钥" }
        val messages = chatDao.getMessagesForChat(sessionId)
        val questionIndex = messages.indexOfFirst { it.id == questionId && it.senderId == "user" }
        require(questionIndex >= 0) { "原问题不存在" }
        val relevantIds = selectedSnapshotIds.toSet() + messages.take(questionIndex)
            .filter { it.senderId == characterId }.flatMap { message ->
                runCatching { Json.decodeFromString<List<String>>(message.conversationContextSnapshotIds ?: "[]") }
                    .getOrDefault(emptyList())
            }
        val snapshots = chatDao.getConversationContextSnapshots(
            "dialog-session-$sessionId", "dialog-node-$sessionId", "dialog-question-",
        ).filter { it.id in relevantIds }
        val availableContents = snapshots.mapNotNull { snapshot ->
            val content = snapshot.content?.takeIf(String::isNotBlank)
            if (content != null && snapshot.contentState == "available" && snapshot.networkAllowed &&
                snapshot.contentHash == ContextContentHasher.hash(content)
            ) snapshot.id to "【资料：${snapshot.title}】\n$content" else null
        }.toMap()
        val sources = snapshots.associate { snapshot ->
            val availableHash = availableContents[snapshot.id]?.let(ContextContentHasher::hash) ?: "unavailable"
            snapshot.id to "${snapshot.contentHash}:${snapshot.contentState}:${snapshot.networkAllowed}:${snapshot.sourceId}:$availableHash"
        }
        val input = ConversationContextInput(
            sessionId, characterId, questionId, messages, prompt, selectedContext,
            model.modelId, attemptPlan.first().keyId, sources, selectedSnapshotIds, availableContents,
        )
        // 轮换 Key 时仍共享同一压缩时间预算，不因每次准备请求而重新计时。
        var compactRemaining = ConversationTimeouts.COMPACTION_MS
        suspend fun summarize(text: String): String {
            if (compactRemaining <= 0L) throw ConversationStageTimeoutException("compaction")
            val started = System.nanoTime()
            try {
                return withTimeoutOrNull(compactRemaining) {
                    val response = GeminiInteractionsTransport.createInteraction(
                        context = context,
                        request = CreateInteractionRequest(
                            model = model.modelId,
                            input = JsonPrimitive(text),
                            systemInstruction = CONVERSATION_COMPACTION_PROMPT,
                            store = false,
                            generationConfig = InteractionGenerationConfig(maxOutputTokens = 4096, thinkingLevel = "low"),
                        ),
                        sessionId = sessionId,
                        attemptPlan = attemptPlan,
                        tracker = tracker,
                        operationName = "ConversationCompaction",
                        postBudget = InteractionPostBudget(2),
                        isRequired = false,
                    )
                    check(response.status == "completed") { "摘要未完整生成" }
                    response.outputText
                } ?: throw ConversationStageTimeoutException("compaction")
            } finally {
                compactRemaining -= ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            }
        }
        var prepared = memory.prepare(input, summarize = ::summarize)
        val posts = InteractionPostBudget(4)
        val request = CreateInteractionRequest(
            model = model.modelId,
            systemInstruction = systemInstruction,
            store = true,
            generationConfig = InteractionGenerationConfig(
                maxOutputTokens = maxOutputTokens,
                thinkingLevel = model.geminiInteractionThinkingLevel(thinkingLevel),
                thinkingSummaries = "auto",
            ),
        )
        var chainRebuilt = false
        suspend fun generate(): StreamedInteraction = GeminiInteractionsTransport.createInteraction(
            context = context,
            request = request,
            sessionId = sessionId,
            attemptPlan = attemptPlan,
            tracker = tracker,
            operationName = "MainAnswer-$characterId",
            postBudget = posts,
            prepareRequestForKey = { lease, template ->
                if (prepared.input.keyId != lease.keyId) {
                    prepared = memory.prepare(input.copy(keyId = lease.keyId), forceRebuild = true, summarize = ::summarize)
                }
                template.copy(input = JsonPrimitive(prepared.text), previousInteractionId = prepared.previousInteractionId)
            },
            onAttemptStarted = onAttemptStarted,
            onTextUpdate = onTextUpdate,
        )
        val answerStarted = System.currentTimeMillis()
        val response = try {
            withTimeoutOrNull(ConversationTimeouts.ANSWER_MS) {
                recoverConversationContext(
                    hasPrevious = { prepared.previousInteractionId != null },
                    rebuild = {
                        chainRebuilt = true
                        memory.invalidate(prepared)
                        prepared = memory.prepare(prepared.input, forceRebuild = true, summarize = ::summarize)
                    },
                    generate = ::generate,
                )
            } ?: throw ConversationStageTimeoutException("answer")
        } catch (error: Exception) {
            recordConversationPhase(
                if (chainRebuilt) "chain_recovery" else "answer", System.currentTimeMillis() - answerStarted,
                false, attempts = posts.used,
            )
            throw error
        }
        recordConversationPhase(
            if (chainRebuilt) "chain_recovery" else "answer", System.currentTimeMillis() - answerStarted,
            true, response.usage, posts.used,
        )
        val continuationTimeout = currentCoroutineContext()[ConversationDeadline]
            ?.optionalTimeoutMs(ConversationTimeouts.CONTINUATION_MS) ?: ConversationTimeouts.CONTINUATION_MS
        val finalResponse = completeInteractionWithContinuation(response, continuationTimeout) {
            GeminiInteractionsTransport.createInteraction(
                context = context,
                request = request.copy(input = JsonPrimitive("请继续"), previousInteractionId = response.id),
                sessionId = sessionId,
                // 续写不能携带原 Key 的游标跨 Key 轮换。
                attemptPlan = attemptPlan.filter { it.keyId == response.keyId },
                tracker = tracker,
                operationName = "ContinueAnswer-$characterId",
                postBudget = InteractionPostBudget(1),
                isRequired = false,
                onAttemptStarted = { onTextUpdate(response.outputText) },
                onTextUpdate = { onTextUpdate(response.outputText + it) },
            )
        }
        val text = finalResponse.outputText
        onTextUpdate(text)
        memory.completed(
            prepared, finalResponse.id, requireNotNull(finalResponse.keyId), text,
            finalResponse.usage?.totalInputTokens ?: 0, finalResponse.status == "completed",
        )
        return text
    }
}
