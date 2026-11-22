package com.elio.lifearchive.domain.ai

import com.elio.jianyu.network.AiUseCase
import kotlinx.coroutines.flow.Flow

data class AiRequestId(val business: String, val session: String, val operation: String) {
    init { require(business.isNotBlank() && session.isNotBlank() && operation.isNotBlank()) }
}
enum class AiRole { USER, ASSISTANT }
data class AiMessage(val role: AiRole, val text: String)
data class AiRequest(
    val id: AiRequestId,
    val useCase: AiUseCase,
    val instruction: String,
    val messages: List<AiMessage>,
)
sealed interface AiEvent {
    data class Text(val text: String) : AiEvent
    data class Complete(val text: String) : AiEvent
}

/** 取消收集即取消该请求；实现不得取消另一业务域的任务或读取其上下文。 */
fun interface AiGateway { fun generate(request: AiRequest): Flow<AiEvent> }

enum class AiFailure { NO_KEY, NETWORK, AUTHENTICATION, RATE_LIMITED, INVALID_RESPONSE, SERVICE }
class AiRequestException(val failure: AiFailure) : Exception(when (failure) {
    AiFailure.NO_KEY -> "请先在公共 AI 设置中配置可用的 Key。"
    AiFailure.NETWORK -> "网络连接中断，请重试。"
    AiFailure.AUTHENTICATION -> "AI 凭据不可用，请检查公共 AI 设置。"
    AiFailure.RATE_LIMITED -> "AI 服务暂时限流，请稍后重试。"
    AiFailure.INVALID_RESPONSE -> "AI 未返回完整回答，请重试。"
    AiFailure.SERVICE -> "AI 服务暂时不可用，请重试。"
})
