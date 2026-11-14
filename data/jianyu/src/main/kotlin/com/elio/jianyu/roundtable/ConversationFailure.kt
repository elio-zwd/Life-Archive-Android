package com.elio.jianyu.roundtable

import com.elio.jianyu.network.InteractionAttemptLimitException
import com.elio.jianyu.network.DeepSeekEmptyResponseException
import com.elio.jianyu.network.isInteractionContextUnavailable
import com.elio.jianyu.network.retry.ApiCallFailure
import com.elio.jianyu.network.retry.ApiExecutionException
import kotlinx.coroutines.CancellationException

internal class ConversationStageTimeoutException(val stage: String) : Exception("对话阶段等待超时：$stage")
internal class ConversationRestoreException(cause: Throwable) : Exception("会话上下文恢复失败", cause)

/** 仅允许对明确失效且确实带了游标的请求重建一次，重建失败不递归重试。 */
internal suspend fun <T> recoverConversationContext(
    hasPrevious: () -> Boolean,
    rebuild: suspend () -> Unit,
    generate: suspend () -> T,
): T = try {
    generate()
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    if (!hasPrevious() || !isInteractionContextUnavailable(error)) throw error
    try {
        rebuild()
        generate()
    } catch (retryError: CancellationException) {
        throw retryError
    } catch (retryError: Exception) {
        throw ConversationRestoreException(retryError)
    }
}

internal fun conversationFailureNotice(error: Throwable): String = when {
    error is ConversationRestoreException && (error.cause is ApiExecutionException || error.cause is ConversationStageTimeoutException) ->
        conversationFailureNotice(requireNotNull(error.cause))
    error is ConversationStageTimeoutException -> "回答等待超时，可稍后重试。"
    error is ConversationRestoreException || isInteractionContextUnavailable(error) ->
        "会话上下文恢复失败，原消息已保留，可稍后重试。"
    error is ApiExecutionException -> when (val failure = error.failure) {
        is ApiCallFailure.Network -> "网络连接中断，可检查网络后重试。"
        is ApiCallFailure.Serialization -> if (failure.cause is DeepSeekEmptyResponseException) {
            "服务未返回可展示的回答，可稍后重试。"
        } else "服务响应未能正确解析，可稍后重试。"
        is ApiCallFailure.Http -> when (failure.code) {
            429 -> "请求受到限流，请稍后重试或检查密钥配额。"
            401, 403 -> "服务鉴权或权限检查失败，请检查密钥配置。"
            400, 404 -> "服务未接受本次参数或上下文，请检查模型配置或缩短输入后重试。"
            503 -> "模型服务暂时不可用（HTTP 503），可稍后重试或在模型配置中选择其他模型。"
            in 500..599 -> "服务暂时不可用，自动重试后仍未完成，可稍后重试。"
            else -> "服务拒绝了本次请求，可检查配置后重试。"
        }
        is ApiCallFailure.Unknown -> if (failure.cause is InteractionAttemptLimitException) {
            "自动重试后仍未完成，可稍后重试。"
        } else "回答生成中断，可稍后重试。"
    }
    else -> "回答生成中断，可稍后重试。"
}
