package com.elio.jianyu.network

import com.elio.jianyu.network.keys.ApiKeyLease
import com.elio.jianyu.network.keys.ConversationKeyContext
import com.elio.jianyu.network.retry.*
import com.elio.jianyu.roundtable.DefaultDelayProvider
import com.elio.jianyu.roundtable.DelayProvider
import com.elio.jianyu.telemetry.*
import com.elio.lifearchive.data.ai.ProviderRequestExecutor
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** 见域仅适配会话预算、Key 占用和诊断；重试和冷却统一执行。 */
internal class AiRequestExecutor(private val shared: ProviderRequestExecutor) {
    suspend fun <T> execute(
        sessionId: Long,
        attemptPlan: List<ApiKeyLease>,
        operationName: String,
        delayProvider: DelayProvider = DefaultDelayProvider,
        onAttemptStarted: suspend () -> Unit = {},
        onKeySelected: suspend (ApiKeyLease) -> Unit = {},
        retryJitterMs: () -> Long = { 0L },
        canRetry: () -> Boolean = { true },
        failureClassifier: (Exception) -> ApiCallFailure = ::classifyFailure,
        block: suspend (String) -> T,
    ): T {
        val operation = operationName.substringBefore('-').filter { it.isLetterOrDigit() || it == '_' }.take(80).ifBlank { "AiOperation" }
        val reservation = currentCoroutineContext()[ConversationKeyContext]?.reservation
        val diagnostic = DiagnosticContext.current() ?: DiagnosticContext(sessionId = sessionId)
        var attempt = 0
        var secretForRedaction = ""
        var selectedKey: String? = null
        var started = System.currentTimeMillis()
        return try {
            shared.execute(sessionId, attemptPlan, operation, failureClassifier,
                select = { reservation?.select(it) != false },
                prepare = { lease -> selectedKey = lease.keyId; started = System.currentTimeMillis(); onKeySelected(lease) }, beforeAttempt = onAttemptStarted,
                canRetry = canRetry, wait = { delayProvider.delay(it) }, jitter = retryJitterMs,
                outcome = { lease, error, attempted ->
                    TelemetryRepository.record(TelemetryEvent(
                        id = java.util.UUID.randomUUID().toString(), timestamp = System.currentTimeMillis(),
                        durationMs = System.currentTimeMillis() - started, endpoint = "local/$operation", keyId = lease.keyId.takeIf { attempted },
                        provider = lease.provider.displayName, operationId = diagnostic.operationId,
                        sessionId = sessionId, questionId = diagnostic.questionId, characterId = diagnostic.characterId,
                        purpose = operation, phase = if (attempted) "application_result" else "request_preparation",
                        recoveryResult = if (error == null) "SUCCESS" else "FAILED",
                        failureType = error?.javaClass?.simpleName,
                        errorMessage = error?.let { DiagnosticCredentials.text(it.stackTraceToString(), listOf(secretForRedaction)) },
                    ))
                },
                propagate = { it is InteractionAttemptLimitException },
            ) { secret ->
                secretForRedaction = secret
                withContext(diagnostic.copy(purpose = operation, keyId = selectedKey, attempt = attempt++)) { block(secret) }
            }
        } catch (error: InteractionAttemptLimitException) {
            val failure = error.lastFailure?.let(failureClassifier) ?: ApiCallFailure.Unknown(Exception("请求预算已耗尽"))
            throw ApiExecutionException(failure, operation, error.lastKeyId, Exception("请求预算已耗尽"))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: ApiExecutionException) { throw error }
        catch (error: Exception) {
            throw ApiExecutionException(failureClassifier(error), operation, null, Exception("准备 AI 请求失败"))
        }
    }

    companion object {
        private fun classifyFailure(error: Exception): ApiCallFailure = when (error) {
            is HttpException -> ApiCallFailure.Http(error.code(), ApiRetryPolicy.parseRetryAfterMs(error.response()?.headers()?.get("Retry-After")))
            is IOException -> ApiCallFailure.Network(error)
            is SerializationException -> ApiCallFailure.Serialization(error)
            else -> ApiCallFailure.Unknown(error)
        }
    }
}
