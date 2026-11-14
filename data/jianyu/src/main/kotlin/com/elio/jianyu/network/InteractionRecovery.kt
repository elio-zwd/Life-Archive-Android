package com.elio.jianyu.network

import com.elio.jianyu.network.retry.ApiCallFailure
import com.elio.jianyu.network.retry.ApiExecutionException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.elio.jianyu.telemetry.TelemetryEventFactory
import com.elio.jianyu.telemetry.TelemetryRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.io.IOException

/** SSE、非流式降级和断链重建共享一个物理生成 POST 上限，GET 查询不占用。 */
class InteractionPostBudget(private val maximum: Int = 4) {
    var used: Int = 0
        private set

    init { require(maximum > 0) }

    private var lastError: Exception? = null
    private var lastKeyId: String? = null

    @Synchronized
    internal fun recordFailure(error: Exception, keyId: String?) {
        if (error !is CancellationException && error !is InteractionAttemptLimitException) {
            lastError = error
            lastKeyId = keyId
        }
    }

    @Synchronized
    fun hasAvailable(): Boolean = used < maximum

    @Synchronized
    fun ensureAvailable() {
        if (!hasAvailable()) throw InteractionAttemptLimitException(lastError, lastKeyId)
    }

    @Synchronized
    fun consume() {
        ensureAvailable()
        used++
    }
}

internal class InteractionAttemptLimitException(
    val lastFailure: Exception? = null,
    val lastKeyId: String? = null,
) : Exception("生成重试次数已达上限", lastFailure)
internal class InteractionContextUnavailableException : Exception("云端会话上下文不可用")

/** 保活注释不刷新期限；当前协程库的 Flow.timeout 会丢失 close(cause)，因此显式传播通道错误。 */
internal fun <T> Flow<T>.withInteractionEventTimeout(timeoutMs: Long = 60_000L): Flow<T> = flow {
    coroutineScope {
        val events = Channel<T>(Channel.RENDEZVOUS)
        val producer = launch {
            try {
                collect { events.send(it) }
                events.close()
            } catch (error: Exception) {
                events.close(error)
            }
        }
        try {
            while (true) {
                // 只吸收本次等待的超时，用户停止和外层总期限照常传播。
                val next = withTimeoutOrNull(timeoutMs) { events.receiveCatching() }
                    ?: throw InteractionStreamStalledException(timeoutMs)
                next.exceptionOrNull()?.let { throw it }
                if (next.isClosed) break
                emit(next.getOrThrow())
            }
        } finally {
            events.cancel()
            producer.cancel()
        }
    }
}

internal fun isInteractionContextUnavailable(error: Throwable): Boolean = when (error) {
    is InteractionContextUnavailableException -> true
    is ApiExecutionException -> (error.failure as? ApiCallFailure.Unknown)?.cause is InteractionContextUnavailableException
    else -> false
}

/** 只在服务端明确指出 previous_interaction_id 不可用时重建；普通 400/404 不清链。 */
internal fun previousInteractionUnavailable(code: Int, body: String?): Boolean {
    if (code != 400 && code != 404 || body == null) return false
    val error = runCatching {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
    }.getOrNull() ?: return false
    val message = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
    val parameter = (error["param"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return ("previous_interaction_id" in message || "previous interaction" in message || parameter == "previous_interaction_id") &&
        listOf("not found", "expired", "invalid", "unavailable", "does not exist", "not exist", "not accessible").any { it in message }
}

internal fun interactionErrorHttpCode(code: String?): Int? = when (code?.lowercase()) {
    "internal", "internal_error", "internal_server_error", "server_error", "500" -> 500
    "unavailable", "service_unavailable", "overloaded", "overloaded_error", "503" -> 503
    "gateway_timeout", "deadline_exceeded", "504" -> 504
    "rate_limit_exceeded", "resource_exhausted", "too_many_requests", "429" -> 429
    "invalid_request", "invalid_argument", "400" -> 400
    "not_found", "404" -> 404
    "unauthenticated", "authentication_error", "401" -> 401
    "permission_denied", "permission_error", "403" -> 403
    else -> null
}

internal fun Interaction.asStreamed(): StreamedInteraction {
    if (id.isBlank()) throw InteractionStreamProtocolException("Interaction returned no id")
    if (status !in setOf("completed", "incomplete")) {
        throw interactionTerminalFailure("Interaction did not complete", error)
    }
    if (outputText.isBlank()) throw InteractionStreamProtocolException("Interaction returned no model text")
    return StreamedInteraction(id, outputText, model, status = requireNotNull(status), usage = usage)
}

internal fun interactionTerminalFailure(message: String, error: JsonObject?): InteractionStreamTerminalException {
    val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
    return InteractionStreamTerminalException(message, code, previousInteractionUnavailable(
        interactionErrorHttpCode(code) ?: 0, error?.let { JsonObject(mapOf("error" to it)).toString() },
    ))
}

/** 只有不存在、未完成、连接失败或本次查询超时才允许重新生成，不能吞掉明确的服务端错误。 */
internal suspend fun retrieveInteractionResult(
    timeoutMs: Long = 10_000L,
    fetch: suspend () -> Interaction,
): StreamedInteraction? = withTimeoutOrNull(timeoutMs) {
    try {
        val result = fetch()
        if (result.status in setOf("in_progress", "pending", "running")) null else result.asStreamed()
    } catch (error: CancellationException) {
        throw error
    } catch (error: HttpException) {
        if (error.code() == 404) null else throw error
    } catch (_: IOException) {
        null
    }
}

/** 可独立验证的断流恢复顺序：查询原请求结果，再决定是否重新生成。 */
internal suspend fun executeInteractionAttempt(
    stream: suspend ((String) -> Unit) -> StreamedInteraction,
    retrieve: suspend (String) -> StreamedInteraction?,
    fallback: suspend () -> StreamedInteraction,
): StreamedInteraction {
    var createdId: String? = null
    return try {
        stream { createdId = it }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (!shouldFallbackToNonStreaming(error)) throw error
        currentCoroutineContext().ensureActive()
        val recovered = createdId?.let { retrieve(it) }
        recovered ?: run {
            currentCoroutineContext().ensureActive()
            fallback()
        }
    }
}

/** 附加续写的失败不能撤销主回答；withTimeoutOrNull 只吸收自己的期限，父任务取消仍传播。 */
internal suspend fun completeInteractionWithContinuation(
    response: StreamedInteraction,
    timeoutMs: Long = 15_000L,
    continueRequest: suspend () -> StreamedInteraction?,
): StreamedInteraction {
    if (response.status != "incomplete") return response
    val startedAt = System.currentTimeMillis()
    val continuation = withTimeoutOrNull(timeoutMs) {
        try {
            continueRequest()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
    val text = response.outputText + continuation?.outputText.orEmpty() +
        if (continuation?.status == "completed") "" else "\n\n> 续写未完成，已保留生成内容。"
    recordConversationPhase("continuation", System.currentTimeMillis() - startedAt, continuation?.status == "completed", continuation?.usage)
    return (continuation ?: response).copy(outputText = text)
}

/** 这里只记录阶段与稳定分类，不记录压缩输入、摘要或服务端原始错误正文。 */
internal fun recordConversationPhase(
    phase: String,
    durationMs: Long,
    success: Boolean,
    usage: InteractionUsage? = null,
    attempts: Int = 0,
) {
    TelemetryEventFactory.create(
        level = TelemetryRepository.currentLevel(),
        id = UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis() - durationMs,
        durationMs = durationMs,
        endpoint = "conversation/$phase",
        model = null,
        keyId = null,
        statusCode = null,
        failureType = if (success) null else "CONVERSATION_STAGE_FAILED",
        requestPreview = null,
        responsePreview = null,
        hasThoughtStep = false,
        contentExpiresAt = null,
        phase = phase,
        recoveryResult = if (success) "SUCCESS" else "FAILED",
        retryCount = (attempts - 1).coerceAtLeast(0),
        inputTokens = usage?.totalInputTokens,
        outputTokens = usage?.totalOutputTokens,
    )?.let(TelemetryRepository::record)
}
