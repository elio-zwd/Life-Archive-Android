package com.elio.jianyu.execution

import com.elio.jianyu.domain.ExecutionParticipantSnapshotEntity

data class ExecutionNetworkRequest(
    val sessionId: Long,
    val participant: ExecutionParticipantSnapshotEntity,
    val modelRequest: ExecutionModelRequest,
    val model: String,
    val thinkingLevel: String,
    /** 仅用于进程内的短链索引，不会发送给 Provider。 */
    val interactionChainKey: String,
    val maxOutputTokens: Int,
    val searchMode: SearchMode,
) {
    init {
        require(sessionId > 0L)
        require(model.isNotBlank())
        require(thinkingLevel in setOf("minimal", "low", "medium", "high"))
        require(interactionChainKey.isNotBlank())
        require(maxOutputTokens > 0)
    }
}

data class ExecutionNetworkResult(
    val providerInteractionId: String,
    val outputText: String,
    val providerModel: String? = null,
)

fun interface PreparedExecutionNetworkCall {
    suspend fun execute(
        onAttemptStarted: suspend () -> Unit,
        onTextUpdate: suspend (String) -> Unit,
    ): ExecutionNetworkResult
}

/**
 * 网络边界先准备 Key 尝试计划，再由调用方创建 Pending 消息。
 * 准备失败（例如无 Key）时不得留下悬挂 Pending。
 */
fun interface ExecutionNetworkGateway {
    suspend fun prepare(request: ExecutionNetworkRequest): PreparedExecutionNetworkCall
}

/**
 * 根据当前 AI 管理配置分派到 Gemini Interactions 或 DeepSeek Chat Completions。
 *
 * [RequestBudgetTracker] 仅作为旧客户端的传输层调用计数器，不是业务预算事实源；
 * 每次真实网络尝试前必须先执行 [PreparedExecutionNetworkCall.execute] 的
 * `onAttemptStarted`，由 Coordinator 原子记录 Room 调用次数。
 */
