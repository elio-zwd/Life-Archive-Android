package com.elio.lifearchive.data.ai

import com.elio.jianyu.network.*
import com.elio.jianyu.network.keys.ApiKeyLease
import com.elio.jianyu.network.retry.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

/** 公共重试执行器：业务只适配预算、占用和诊断，不能再拥有另一份 Key 池。 */
class ProviderRequestExecutor(private val provider: AiProvider, private val keys: ProviderKeyRepository) {
    suspend fun <T> execute(
        sessionId: Long,
        plan: List<ApiKeyLease>,
        operation: String,
        classify: (Exception) -> ApiCallFailure,
        select: suspend (ApiKeyLease) -> Boolean = { true },
        prepare: suspend (ApiKeyLease) -> Unit = {},
        beforeAttempt: suspend () -> Unit = {},
        canRetry: () -> Boolean = { true },
        wait: suspend (Long) -> Unit = { delay(it) },
        jitter: () -> Long = { 0L },
        outcome: (ApiKeyLease, Exception?, Boolean) -> Unit = { _, _, _ -> },
        propagate: (Exception) -> Boolean = { false },
        block: suspend (String) -> T,
    ): T {
        var last: ApiCallFailure? = null
        var lastKey: String? = null
        fun failed(failure: ApiCallFailure, key: String?) = ApiExecutionException(
            failure, operation, key, Exception("AI 请求失败：${failure.safeCategory()}"))
        keyLoop@ for (lease in plan) {
            require(lease.provider == provider)
            var retries = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!select(lease) || keys.availableSecretFor(lease.keyId) == null) continue@keyLoop
                var attempted = false
                try {
                    prepare(lease)
                    if (!select(lease)) continue@keyLoop
                    val secret = keys.availableSecretFor(lease.keyId) ?: continue@keyLoop
                    currentCoroutineContext().ensureActive()
                    beforeAttempt()
                    attempted = true
                    val result = block(secret)
                    outcome(lease, null, true)
                    keys.recordSuccess(sessionId, lease.keyId)
                    return result
                } catch (cancelled: CancellationException) {
                    outcome(lease, cancelled, attempted)
                    throw cancelled
                } catch (error: Exception) {
                    outcome(lease, error, attempted)
                    if (propagate(error)) throw error
                    if (!attempted) throw error
                    val failure = classify(error)
                    last = failure
                    lastKey = lease.keyId
                    keys.recordFailure(lease.keyId, failure)
                    val decision = ApiRetryPolicy.getDecision(failure, retries)
                    if (decision == ApiRetryDecision.STOP_REQUEST || !canRetry()) throw failed(failure, lastKey)
                    when (decision) {
                        ApiRetryDecision.RETRY_SAME_KEY -> {
                            retries++
                            val base = if (failure is ApiCallFailure.Http && failure.code in 500..599) (1L shl (retries - 1)) * 1000L else 1000L
                            wait(saturatingAddMs(maxOf(base, (failure as? ApiCallFailure.Http)?.retryAfterMs ?: 0L), jitter()))
                        }
                        else -> {
                            if (failure is ApiCallFailure.Http && failure.code in 500..599 && lease != plan.last()) {
                                failure.retryAfterMs?.let { wait(saturatingAddMs(it, jitter())) }
                            }
                            break
                        }
                    }
                }
            }
        }
        throw failed(last ?: ApiCallFailure.Unknown(Exception("无可用凭据")), lastKey)
    }
}
