package com.elio.jianyu.network

import com.elio.jianyu.network.keys.ApiKeyLease
import com.elio.jianyu.network.keys.ConversationKeyContext
import com.elio.jianyu.network.retry.ApiCallFailure
import com.elio.jianyu.network.retry.ApiExecutionException
import com.elio.jianyu.network.retry.ApiRetryDecision
import com.elio.jianyu.network.retry.ApiRetryPolicy
import com.elio.jianyu.network.retry.safeCategory
import com.elio.jianyu.network.retry.saturatingAddMs
import com.elio.jianyu.roundtable.DefaultDelayProvider
import com.elio.jianyu.roundtable.DelayProvider
import com.elio.jianyu.telemetry.PrivacySafeLogger
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

internal interface ProviderKeyAttemptReporter {
    fun secretFor(keyId: String): String?
    fun recordSuccess(sessionId: Long, keyId: String)
    fun recordFailure(keyId: String, failure: ApiCallFailure)
}

/**
 * 所有文本提供商共用的请求执行器：负责 Key 尝试顺序、重试、冷却回写和安全错误包装。
 * transport 只提供单次协议请求，不得自行轮换或修改 Key 状态。
 */
internal class AiRequestExecutor(
    private val provider: AiProvider,
    private val keyRepository: ProviderKeyAttemptReporter,
) {
    suspend fun <T> execute(
        sessionId: Long,
        attemptPlan: List<ApiKeyLease>,
        operationName: String,
        delayProvider: DelayProvider = DefaultDelayProvider,
        onAttemptStarted: suspend () -> Unit = {},
        onKeySelected: suspend (ApiKeyLease) -> Unit = {},
        retryJitterMs: () -> Long = { 0L },
        canRetry: () -> Boolean = { true },
        failureClassifier: (Exception) -> ApiCallFailure = { error -> classifyFailure(error) },
        block: suspend (String) -> T,
    ): T {
        val safeOperationName = sanitizeOperationName(operationName)
        if (attemptPlan.isEmpty()) {
            throw executionException(
                failure = ApiCallFailure.Unknown(Exception("No available ${provider.displayName} keys")),
                operationName = safeOperationName,
                keyId = null,
            )
        }

        val reservation = currentCoroutineContext()[ConversationKeyContext]?.reservation
        val diagnostic = com.elio.jianyu.telemetry.DiagnosticContext.current()
            ?: com.elio.jianyu.telemetry.DiagnosticContext(sessionId = sessionId)
        var physicalAttempt = 0
        var lastFailure: ApiCallFailure? = null
        var lastFailedKeyId: String? = null
        keyLoop@ for (lease in attemptPlan) {
            require(lease.provider == provider) { "密钥计划与提供商不一致" }
            var sameKeyAttemptCount = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (reservation?.select(lease) == false) continue@keyLoop
                // 等待另一位角色释放 Key 后重新解析，不能使用等待前捕获的密钥。
                var secret = keyRepository.secretFor(lease.keyId) ?: continue@keyLoop
                var attempted = false
                val diagnosticStarted = System.currentTimeMillis()
                fun recordOutcome(error: Exception?, phase: String = "application_result", diagnosticKeyId: String? = lease.keyId) {
                    val event = com.elio.jianyu.telemetry.TelemetryEvent(
                        id = java.util.UUID.randomUUID().toString(), timestamp = diagnosticStarted,
                        durationMs = System.currentTimeMillis() - diagnosticStarted, endpoint = "local/$operationName",
                        keyId = diagnosticKeyId, provider = provider.displayName, operationId = diagnostic.operationId,
                        sessionId = sessionId, questionId = diagnostic.questionId, characterId = diagnostic.characterId,
                        purpose = operationName, phase = phase,
                        recoveryResult = if (error == null) "SUCCESS" else "FAILED",
                        failureType = error?.let { when (it) {
                            is kotlinx.coroutines.TimeoutCancellationException -> "TIMEOUT"
                            is CancellationException -> "CANCELLED"
                            else -> it.javaClass.simpleName
                        } },
                        errorMessage = error?.let { com.elio.jianyu.telemetry.DiagnosticCredentials.text(it.stackTraceToString(), listOf(secret)) },
                    )
                    com.elio.jianyu.telemetry.TelemetryRepository.record(event)
                }
                try {
                    onKeySelected(lease)
                    // 准备上下文可能嵌套调用摘要并轮换 Key，真正发起前重新确认占用。
                    if (reservation?.select(lease) == false) continue@keyLoop
                    secret = keyRepository.secretFor(lease.keyId) ?: continue@keyLoop
                    currentCoroutineContext().ensureActive()
                    onAttemptStarted()
                    attempted = true
                    val result = kotlinx.coroutines.withContext(diagnostic.copy(
                        purpose = operationName, keyId = lease.keyId, attempt = physicalAttempt++,
                    )) { block(secret) }
                    recordOutcome(null)
                    keyRepository.recordSuccess(sessionId, lease.keyId)
                    return result
                } catch (error: CancellationException) {
                    recordOutcome(error, phase = if (attempted) "application_result" else "request_preparation", diagnosticKeyId = lease.keyId.takeIf { attempted })
                    throw error
                } catch (error: Exception) {
                    // 尚未发送下一次请求时耗尽预算，不覆盖真实失败，也不处罚未使用的 Key。
                    if (error is InteractionAttemptLimitException) {
                        val actualFailure = error.lastFailure?.let(failureClassifier) ?: lastFailure ?: failureClassifier(error)
                        val actualKey = error.lastKeyId ?: lastFailedKeyId
                        recordOutcome(error, phase = "request_budget", diagnosticKeyId = actualKey)
                        throw executionException(
                            actualFailure, safeOperationName, actualKey,
                        )
                    }
                    if (!attempted) {
                        recordOutcome(error, phase = "request_preparation", diagnosticKeyId = null)
                        // 准备阶段可能嵌套调用其他 Key；其失败不能归罪于尚未发送的外层 Key。
                        if (error is ApiExecutionException) throw error
                        throw executionException(failureClassifier(error), safeOperationName, null)
                    }
                    recordOutcome(error)
                    val failure = failureClassifier(error)
                    lastFailure = failure
                    lastFailedKeyId = lease.keyId
                    keyRepository.recordFailure(lease.keyId, failure)
                    PrivacySafeLogger.e(
                        "AiRequestExecutor",
                        "${provider.displayName} 请求失败 (operation=$safeOperationName, category=${failure.safeCategory()})",
                    )
                    val decision = ApiRetryPolicy.getDecision(failure, sameKeyAttemptCount)
                    if (decision != ApiRetryDecision.STOP_REQUEST && !canRetry()) {
                        recordOutcome(InteractionAttemptLimitException(error, lease.keyId), phase = "request_budget")
                        throw executionException(failure, safeOperationName, lease.keyId)
                    }
                    suspend fun waitBeforeRetry(baseMs: Long) {
                        val serverMs = (failure as? ApiCallFailure.Http)?.retryAfterMs ?: 0L
                        val waitMs = saturatingAddMs(maxOf(baseMs, serverMs), retryJitterMs())
                        if (waitMs <= 0L) return
                        val started = System.currentTimeMillis()
                        val event = com.elio.jianyu.telemetry.TelemetryEvent(
                            id = java.util.UUID.randomUUID().toString(), timestamp = started, durationMs = 0,
                            endpoint = "local/retry_wait", keyId = lease.keyId, provider = provider.displayName,
                            operationId = diagnostic.operationId, sessionId = sessionId, questionId = diagnostic.questionId,
                            characterId = diagnostic.characterId, purpose = operationName, phase = "retry_wait",
                            recoveryResult = "WAITING", captureState = "RECORDING",
                            phaseDetails = "原因=${failure.safeCategory()}；计划等待=${waitMs}ms；服务端提示=${serverMs}ms",
                        )
                        com.elio.jianyu.telemetry.TelemetryRepository.record(event)
                        try {
                            delayProvider.delay(waitMs)
                            currentCoroutineContext().ensureActive()
                            com.elio.jianyu.telemetry.TelemetryRepository.record(event.copy(
                                durationMs = System.currentTimeMillis() - started, recoveryResult = "SUCCESS", captureState = "COMPLETE"))
                        } catch (cancelled: CancellationException) {
                            com.elio.jianyu.telemetry.TelemetryRepository.record(event.copy(
                                durationMs = System.currentTimeMillis() - started, recoveryResult = "FAILED",
                                captureState = "COMPLETE",
                                failureType = if (cancelled is kotlinx.coroutines.TimeoutCancellationException) "TIMEOUT" else "CANCELLED"))
                            throw cancelled
                        }
                    }
                    when (decision) {
                        ApiRetryDecision.STOP_REQUEST -> throw executionException(
                            failure,
                            safeOperationName,
                            lease.keyId,
                        )
                        ApiRetryDecision.RETRY_SAME_KEY -> {
                            sameKeyAttemptCount++
                            waitBeforeRetry(retryDelayMs(failure, sameKeyAttemptCount))
                        }
                        ApiRetryDecision.TRY_NEXT_KEY -> {
                            if (failure is ApiCallFailure.Http && failure.code in 500..599 && failure.retryAfterMs != null && lease != attemptPlan.last()) {
                                waitBeforeRetry(0L)
                            }
                            break
                        }
                        ApiRetryDecision.COOLDOWN_AND_TRY_NEXT_KEY -> break
                    }
                }
            }
        }

        throw executionException(
            failure = lastFailure ?: ApiCallFailure.Unknown(Exception("All ${provider.displayName} keys failed")),
            operationName = safeOperationName,
            keyId = lastFailedKeyId,
        )
    }

    private fun classifyFailure(error: Exception): ApiCallFailure = when (error) {
        is HttpException -> ApiCallFailure.Http(
            code = error.code(),
            retryAfterMs = ApiRetryPolicy.parseRetryAfterMs(error.response()?.headers()?.get("Retry-After")),
        )
        is IOException -> ApiCallFailure.Network(error)
        is SerializationException -> ApiCallFailure.Serialization(error)
        else -> ApiCallFailure.Unknown(error)
    }

    private fun retryDelayMs(failure: ApiCallFailure, retryCount: Int): Long =
        if (failure is ApiCallFailure.Http && failure.code in 500..599) (1L shl (retryCount - 1)) * 1000L else 1000L

    private fun executionException(
        failure: ApiCallFailure,
        operationName: String,
        keyId: String?,
    ): ApiExecutionException = ApiExecutionException(
        failure = failure,
        operationName = operationName,
        keyId = keyId,
        cause = Exception("操作 [$operationName] 失败（${failure.safeCategory()}）。"),
    )

    private fun sanitizeOperationName(operationName: String): String = operationName
        .substringBefore('-')
        .filter { it.isLetterOrDigit() || it == '_' }
        .take(80)
        .ifBlank { "AiOperation" }
}
