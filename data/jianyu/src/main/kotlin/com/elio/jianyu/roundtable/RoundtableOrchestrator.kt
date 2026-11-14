package com.elio.jianyu.roundtable

import android.content.Context
import com.elio.jianyu.domain.Character
import com.elio.jianyu.domain.Message
import com.elio.jianyu.domain.MessageAnswerStatus
import com.elio.jianyu.network.AiManager
import com.elio.jianyu.network.AiUseCase
import com.elio.jianyu.network.keys.ApiKeyLease
import com.elio.jianyu.network.keys.ConversationKeyCoordinator
import com.elio.jianyu.network.keys.ConversationKeyContext
import com.elio.jianyu.network.keys.ConversationKeyReservation
import com.elio.jianyu.telemetry.PrivacySafeLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object OrchestratorLogger {
    fun e(tag: String, message: String, error: Throwable? = null) {
        PrivacySafeLogger.e(tag, message, error)
    }
}

interface RoundtableDatabaseGateway {
    suspend fun getMessages(sessionId: Long): List<Message>
    suspend fun insertMessage(message: Message): Long
    suspend fun deleteMessageById(id: Long)
    suspend fun updatePendingMessageText(id: Long, text: String) {}
    suspend fun completePendingMessage(
        id: Long,
        text: String,
        answerStatus: String = MessageAnswerStatus.COMPLETED,
    )
    suspend fun removePendingMessages(sessionId: Long)
    suspend fun getCharacters(): List<Character>
}

interface CharacterAnswerGateway {
    fun preferredKeyId(sessionId: Long, characterId: String): String? = null

    suspend fun prepareQuestion(sessionId: Long, questionRunId: Long, responseMode: TranscriptBuilder.ResponseMode) {}

    suspend fun callGeminiApi(
        character: Character,
        prompt: String,
        attemptPlan: List<ApiKeyLease>,
        tracker: RequestBudgetTracker,
        budget: RoundtableBudget,
        sessionId: Long,
        isRequired: Boolean = true,
        reserveForRequired: Int = 0
    ): String

    suspend fun callGeminiApiStreaming(
        character: Character,
        prompt: String,
        attemptPlan: List<ApiKeyLease>,
        tracker: RequestBudgetTracker,
        budget: RoundtableBudget,
        sessionId: Long,
        isRequired: Boolean = true,
        reserveForRequired: Int = 0,
        onAttemptStarted: suspend () -> Unit,
        onTextUpdate: suspend (String) -> Unit
    ): String {
        onAttemptStarted()
        val response = callGeminiApi(
            character = character,
            prompt = prompt,
            attemptPlan = attemptPlan,
            tracker = tracker,
            budget = budget,
            sessionId = sessionId,
            isRequired = isRequired,
            reserveForRequired = reserveForRequired
        )
        onTextUpdate(response)
        return response
    }

    suspend fun getEmbedding(
        context: Context,
        text: String,
        sessionId: Long,
        attemptPlan: List<ApiKeyLease>,
        tracker: RequestBudgetTracker,
        isRequired: Boolean = true,
        reserveForRequired: Int = 0
    ): List<Float>
}

interface DelayProvider {
    suspend fun delay(ms: Long)
}

object DefaultDelayProvider : DelayProvider {
    override suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }
}

data class OrchestrationResult(
    val completedCharacters: List<String>,
    val failedCharacters: List<String>,
    val apiCallsUsed: Int,
    val isLimitExceeded: Boolean,
    val timedOutCharacters: List<String> = emptyList()
)

class RoundtableBudgetManager(val budget: RoundtableBudget = RoundtableBudget()) {
    private val trackers = ConcurrentHashMap<Long, RequestBudgetTracker>()
    private val answeredCharacters = ConcurrentHashMap<Long, MutableSet<String>>()
    private val selectedParticipants = ConcurrentHashMap<Long, List<String>>()
    private val selectedParticipantSnapshots = ConcurrentHashMap<Long, List<Character>>()

    fun getTracker(questionRunId: Long): RequestBudgetTracker {
        return trackers.computeIfAbsent(questionRunId) {
            RequestBudgetTracker()
        }
    }

    fun getAnsweredCharacters(questionRunId: Long): MutableSet<String> {
        return answeredCharacters.computeIfAbsent(questionRunId) {
            ConcurrentHashMap.newKeySet()
        }
    }

    fun getOrSetSelectedParticipants(questionRunId: Long, activeCharIds: List<String>): List<String> {
        return selectedParticipants.computeIfAbsent(questionRunId) {
            activeCharIds.take(budget.maxCharactersPerQuestion)
        }
    }

    fun getSelectedParticipants(questionRunId: Long): List<String>? {
        return selectedParticipants[questionRunId]
    }

    fun setSelectedParticipants(questionRunId: Long, ids: List<String>) {
        selectedParticipants[questionRunId] = ids.toList()
        selectedParticipantSnapshots.remove(questionRunId)
    }

    fun getSelectedParticipantSnapshots(questionRunId: Long): List<Character>? {
        return selectedParticipantSnapshots[questionRunId]
    }

    fun setSelectedParticipantSnapshots(questionRunId: Long, participants: List<Character>) {
        val snapshots = participants.toList()
        selectedParticipantSnapshots[questionRunId] = snapshots
        selectedParticipants[questionRunId] = snapshots.map { it.id }
    }

    fun clearQuestion(questionRunId: Long) {
        trackers.remove(questionRunId)
        answeredCharacters.remove(questionRunId)
        selectedParticipants.remove(questionRunId)
        selectedParticipantSnapshots.remove(questionRunId)
    }
}

class RoundtableOrchestrator(
    private val context: Context,
    private val dbGateway: RoundtableDatabaseGateway,
    private val answerGateway: CharacterAnswerGateway,
    private val budgetManager: RoundtableBudgetManager,
    private val delayProvider: DelayProvider = DefaultDelayProvider,
    private val minIntervalMs: Long = 1000L,
    private val characterTimeoutMs: Long = ConversationTimeouts.CHARACTER_MS,
    private val createAttemptPlan: (Context, Long) -> List<ApiKeyLease> = { ctx, sessionId ->
        AiManager.keysForUseCase(ctx, AiUseCase.ROUNDTABLE_ANSWER).createAttemptPlan(sessionId)
    },
    private val independentStartIntervalMs: Long = 3000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val roundtableMutex = Mutex()

    suspend fun runRoundtableSequence(
        sessionId: Long,
        questionRunId: Long,
        isSemanticRoutingEnabled: Boolean,
        targetCharacterIds: List<String>? = null,
        responseMode: TranscriptBuilder.ResponseMode = TranscriptBuilder.ResponseMode.INDEPENDENT,
    ): OrchestrationResult = withContext(com.elio.jianyu.telemetry.DiagnosticContext(sessionId = sessionId, questionId = questionRunId)) {
        if (!roundtableMutex.tryLock()) {
            throw IllegalStateException("Roundtable sequence is already running")
        }

        val completed = ConcurrentHashMap.newKeySet<String>()
        val failed = ConcurrentHashMap.newKeySet<String>()
        val timedOut = ConcurrentHashMap.newKeySet<String>()
        var resultOrder = emptyList<String>()
        var isLimitExceeded = false

        val tracker = budgetManager.getTracker(questionRunId)
        val answered = budgetManager.getAnsweredCharacters(questionRunId)
        val budget = budgetManager.budget

        try {
            val availableCharacters = dbGateway.getCharacters()
            val messages = dbGateway.getMessages(sessionId)
            val runMessageIndex = messages.indexOfFirst { it.id == questionRunId }
            if (runMessageIndex == -1) {
                return@withContext OrchestrationResult(emptyList(), emptyList(), tracker.getUsed(), false)
            }

            val selectedCharacters = if (targetCharacterIds != null) {
                val availableMap = availableCharacters.associateBy { it.id }
                targetCharacterIds.mapNotNull { id -> availableMap[id] }
            } else {
                val cachedSnapshots = budgetManager.getSelectedParticipantSnapshots(questionRunId)
                val cachedSelectedIds = budgetManager.getSelectedParticipants(questionRunId)
                when {
                    cachedSnapshots != null -> cachedSnapshots
                    cachedSelectedIds != null -> {
                        val restored = cachedSelectedIds.mapNotNull { id ->
                            availableCharacters.firstOrNull { it.id == id }
                        }
                        if (restored.isNotEmpty()) {
                            budgetManager.setSelectedParticipantSnapshots(questionRunId, restored)
                        }
                        restored
                    }
                    else -> selectAndFreezeCharacters(
                        sessionId = sessionId,
                        questionRunId = questionRunId,
                        availableCharacters = availableCharacters,
                        questionMessage = messages[runMessageIndex],
                        semanticRoutingEnabled = isSemanticRoutingEnabled,
                        tracker = tracker,
                        budget = budget
                    )
                }
            }

            val selectedParticipantIds = selectedCharacters.map { it.id }
            if (selectedParticipantIds.isEmpty()) {
                return@withContext OrchestrationResult(emptyList(), emptyList(), tracker.getUsed(), false)
            }

            val messagesSinceRun = messages
                .subList(runMessageIndex + 1, messages.size)
                .takeWhile { it.senderId != "user" }

            val currentRound = resolveCurrentRound(messagesSinceRun, selectedParticipantIds)
            val answeredInTargetRound = messagesSinceRun
                .filter {
                    it.roundIndex == currentRound &&
                        !it.isPending && it.answerStatus == MessageAnswerStatus.COMPLETED
                }
                .map { it.senderId }
                .toSet()
            val pendingCharacters = selectedCharacters.filter { it.id !in answeredInTargetRound }.distinctBy { it.id }
            resultOrder = pendingCharacters.map { it.id }

            if (pendingCharacters.isEmpty()) {
                return@withContext OrchestrationResult(emptyList(), emptyList(), tracker.getUsed(), false)
            }
            val sequenceDeadlineNanos = nanoTime() + ConversationTimeouts.sequenceMs(pendingCharacters.size) * 1_000_000L

            // 先写入本次全部等待页，确保生成较慢的角色也有固定分页位置。
            val pendingMessageIds = pendingCharacters.associate { character ->
                character.id to dbGateway.insertMessage(
                    Message(
                        chatId = sessionId,
                        senderId = character.id,
                        senderName = character.name,
                        avatar = character.avatar,
                        text = "正在思考中...",
                        isPending = true,
                        roundIndex = currentRound,
                        questionMessageId = questionRunId,
                        responseMode = responseMode.name,
                        answerStatus = MessageAnswerStatus.WAITING,
                    )
                )
            }
            var remainingStatus = MessageAnswerStatus.FAILED
            try {
                answerGateway.prepareQuestion(sessionId, questionRunId, responseMode)
                suspend fun answerCharacter(
                    character: Character,
                    reservation: ConversationKeyReservation?,
                    preparationError: Exception?,
                    startedAtNanos: Long,
                ) {
                    val pendingMessageId = pendingMessageIds.getValue(character.id)
                    var latestPartialText = ""

                    try {
                        preparationError?.let { throw it }
                        val latestMessages = dbGateway.getMessages(sessionId)
                        val currentQuestionIndex = latestMessages.indexOfFirst { it.id == questionRunId }
                        check(currentQuestionIndex >= 0) { "Question message no longer exists" }
                        val nextQuestionIndex = latestMessages.withIndex().firstOrNull { (index, message) ->
                            index > currentQuestionIndex && message.senderId == "user"
                        }?.index
                        // 历史问题的单角色重试必须继续回答原问题，不能误用之后的新用户请求。
                        val transcriptMessages = if (nextQuestionIndex != null) {
                            latestMessages.subList(0, nextQuestionIndex)
                        } else {
                            latestMessages
                        }
                        val attemptPlan = reservation?.attemptPlan() ?: createAttemptPlan(context, sessionId)
                        val transcript = TranscriptBuilder.build(
                            messages = transcriptMessages,
                            currentCharacter = character,
                            roundIndex = currentRound,
                            responseMode = responseMode,
                            includePriorUsers = attemptPlan.firstOrNull()?.provider != com.elio.jianyu.network.AiProvider.GEMINI,
                        )
                        if (attemptPlan.isEmpty()) {
                            throw IllegalStateException("No available API key plan")
                        }

                        val deadline = minOf(sequenceDeadlineNanos, startedAtNanos + characterTimeoutMs * 1_000_000L)
                        val requestTimeoutMs = ((deadline - nanoTime()) / 1_000_000L).coerceAtLeast(0L)
                        val reply = withTimeoutOrNull(requestTimeoutMs) {
                            withContext(
                                ConversationDeadline(System.nanoTime() + requestTimeoutMs * 1_000_000L) +
                                    ConversationKeyContext(reservation) +
                                    (com.elio.jianyu.telemetry.DiagnosticContext.current() ?: com.elio.jianyu.telemetry.DiagnosticContext()).copy(characterId = character.id),
                            ) {
                                answerGateway.callGeminiApiStreaming(
                                    character = character,
                                    prompt = transcript,
                                    attemptPlan = attemptPlan,
                                    tracker = tracker,
                                    budget = budget,
                                    sessionId = sessionId,
                                    isRequired = true,
                                    reserveForRequired = 0,
                                    onAttemptStarted = {
                                        latestPartialText = ""
                                        dbGateway.updatePendingMessageText(
                                            pendingMessageId,
                                            "正在生成..."
                                        )
                                    },
                                    onTextUpdate = { partialText ->
                                        latestPartialText = partialText
                                        dbGateway.updatePendingMessageText(pendingMessageId, partialText)
                                    }
                                )
                            }
                        }

                        if (reply == null) {
                            settleInterruptedPendingMessage(
                                pendingMessageId = pendingMessageId,
                                partialText = latestPartialText,
                                notice = "回答等待超时，以上内容可能不完整。",
                                status = MessageAnswerStatus.FAILED,
                            )
                            PrivacySafeLogger.w(
                                "RoundtableOrchestrator",
                                "Character answer timed out"
                            )
                            failed.add(character.id)
                            timedOut.add(character.id)
                        } else {
                            dbGateway.completePendingMessage(pendingMessageId, reply)
                            completed.add(character.id)
                            answered.add(character.id)
                        }
                    } catch (error: CancellationException) {
                        withContext(NonCancellable) {
                            settleInterruptedPendingMessage(
                                pendingMessageId = pendingMessageId,
                                partialText = latestPartialText,
                                notice = "你已停止生成，以上内容可能不完整。",
                                status = MessageAnswerStatus.STOPPED,
                            )
                        }
                        throw error
                    } catch (error: Exception) {
                        OrchestratorLogger.e(
                            "RoundtableOrchestrator",
                            "Character answer failed",
                            error
                        )
                        settleInterruptedPendingMessage(
                            pendingMessageId = pendingMessageId,
                            partialText = latestPartialText,
                            notice = conversationFailureNotice(error) + "以上内容可能不完整。",
                            status = MessageAnswerStatus.FAILED,
                        )
                        failed.add(character.id)
                        if (error is ConversationStageTimeoutException) timedOut.add(character.id)
                    }
                }

                if (responseMode == TranscriptBuilder.ResponseMode.INDEPENDENT) {
                    val keys = ConversationKeyCoordinator { createAttemptPlan(context, sessionId) }
                    val slots = Semaphore(2)
                    // 准入由单一调度协程决定，不能依赖并发完成的 answered.size 检查角色上限。
                    val admitted = answered.toMutableSet()
                    coroutineScope {
                        var lastStartedAt: Long? = null
                        for (character in pendingCharacters) {
                            if (character.id !in admitted && admitted.size >= budget.maxCharactersPerQuestion) {
                                isLimitExceeded = true
                                break
                            }
                            slots.acquire()
                            var reservation: ConversationKeyReservation? = null
                            try {
                                lastStartedAt?.let { previous ->
                                    val remaining = independentStartIntervalMs * 1_000_000L - (nanoTime() - previous)
                                    if (remaining > 0L) delayProvider.delay((remaining + 999_999L) / 1_000_000L)
                                }
                                var preparationError: Exception? = null
                                try {
                                    reservation = keys.reserve(answerGateway.preferredKeyId(sessionId, character.id))
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    preparationError = error
                                }
                                currentCoroutineContext().ensureActive()
                                admitted.add(character.id)
                                val startedAt = nanoTime()
                                lastStartedAt = startedAt
                                val ownedKey = reservation
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    try {
                                        answerCharacter(character, ownedKey, preparationError, startedAt)
                                    } finally {
                                        ownedKey?.close()
                                        slots.release()
                                    }
                                }
                            } catch (error: Throwable) {
                                reservation?.close()
                                slots.release()
                                throw error
                            }
                        }
                    }
                } else {
                    for (character in pendingCharacters) {
                        if (character.id !in answered && answered.size >= budget.maxCharactersPerQuestion) {
                            isLimitExceeded = true
                            break
                        }
                        answerCharacter(character, null, null, nanoTime())
                        if (minIntervalMs > 0L) delayProvider.delay(minIntervalMs)
                    }
                }
            } catch (error: CancellationException) {
                remainingStatus = MessageAnswerStatus.STOPPED
                throw error
            } finally {
                // 停止或预算耗尽时保留每位角色的固定分页，并写明未完成状态。
                withContext(NonCancellable) {
                    val createdIds = pendingMessageIds.values.toSet()
                    dbGateway.getMessages(sessionId)
                        .filter { it.id in createdIds && it.isPending }
                        .forEach { pending ->
                            dbGateway.completePendingMessage(
                                pending.id,
                                if (isLimitExceeded) "本轮调用次数已达上限，可单独重试。"
                                    else if (remainingStatus == MessageAnswerStatus.STOPPED) "本轮生成已停止，可单独重试。"
                                    else "本轮回答未完成，可单独重试。",
                                remainingStatus,
                            )
                        }
                }
            }

        } finally {
            roundtableMutex.unlock()
        }

        return@withContext OrchestrationResult(
            completedCharacters = resultOrder.filter { it in completed },
            failedCharacters = resultOrder.filter { it in failed },
            apiCallsUsed = tracker.getUsed(),
            isLimitExceeded = isLimitExceeded,
            timedOutCharacters = resultOrder.filter { it in timedOut }
        )
    }

    private suspend fun settleInterruptedPendingMessage(
        pendingMessageId: Long,
        partialText: String,
        notice: String,
        status: String,
    ) {
        val visibleText = partialText.trim()
        dbGateway.completePendingMessage(
            pendingMessageId,
            if (visibleText.isBlank() || visibleText == "正在思考中...") notice
                else "$visibleText\n\n> $notice",
            status,
        )
    }

    private suspend fun selectAndFreezeCharacters(
        sessionId: Long,
        questionRunId: Long,
        availableCharacters: List<Character>,
        questionMessage: Message,
        semanticRoutingEnabled: Boolean,
        tracker: RequestBudgetTracker,
        budget: RoundtableBudget
    ): List<Character> {
        if (availableCharacters.isEmpty()) return emptyList()

        val sortedCharacters = if (semanticRoutingEnabled) {
            try {
                val attemptPlan = createAttemptPlan(context, sessionId)
                val questionVector = withTimeoutOrNull(ConversationTimeouts.RETRIEVAL_MS) {
                    answerGateway.getEmbedding(
                        context = context,
                        text = questionMessage.text,
                        sessionId = sessionId,
                        attemptPlan = attemptPlan,
                        tracker = tracker,
                        isRequired = false,
                        reserveForRequired = 0
                    )
                }.orEmpty()
                availableCharacters.map { character ->
                    val characterVector = runCatching {
                        if (character.skillDescriptionVector.isBlank()) {
                            emptyList()
                        } else {
                            character.skillDescriptionVector.split(",").map { it.toFloat() }
                        }
                    }.getOrDefault(emptyList())
                    val similarity = if (characterVector.isNotEmpty() && questionVector.isNotEmpty()) {
                        calculateCosineSimilarity(questionVector, characterVector)
                    } else {
                        0f
                    }
                    character to similarity
                }.sortedByDescending { it.second }.map { it.first }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                availableCharacters
            }
        } else {
            availableCharacters
        }

        val selected = sortedCharacters.take(budget.maxCharactersPerQuestion)
        budgetManager.setSelectedParticipantSnapshots(questionRunId, selected)
        return selected
    }

    private fun resolveCurrentRound(
        messagesSinceRun: List<Message>,
        selectedParticipantIds: List<String>
    ): Int {
        if (messagesSinceRun.isEmpty()) return 1
        val completedMessages = messagesSinceRun.filter {
            !it.isPending && it.answerStatus == MessageAnswerStatus.COMPLETED
        }
        val maxRound = completedMessages.maxOfOrNull { it.roundIndex } ?: 1
        val answeredInCurrentRound = completedMessages
            .filter { it.roundIndex == maxRound }
            .map { it.senderId }
            .toSet()
        return if (selectedParticipantIds.all { it in answeredInCurrentRound }) {
            maxRound + 1
        } else {
            maxRound
        }
    }

    private fun calculateCosineSimilarity(vectorA: List<Float>, vectorB: List<Float>): Float {
        if (vectorA.size != vectorB.size || vectorA.isEmpty()) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (index in vectorA.indices) {
            dotProduct += vectorA[index] * vectorB[index]
            normA += vectorA[index] * vectorA[index]
            normB += vectorB[index] * vectorB[index]
        }
        val denominator = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denominator == 0.0) 0f else (dotProduct / denominator).toFloat()
    }
}
