package com.elio.jianyu.lifecycle



data class IssueLifecycleActiveTasks(
    val issueId: String,
    val activeStandardRunIds: List<String>,
    val activeCollaborationRunIds: List<String>,
    val activeDiscussionIds: List<String>,
    val pendingMessageIds: List<Long>,
    val pendingAudioAssetIds: List<String>,
    val revision: String,
) {
    val hasActiveWork: Boolean
        get() = activeStandardRunIds.isNotEmpty() ||
            activeCollaborationRunIds.isNotEmpty() ||
            activeDiscussionIds.isNotEmpty() ||
            pendingMessageIds.isNotEmpty() ||
            pendingAudioAssetIds.isNotEmpty()
}

sealed interface IssueLifecycleTaskStopResult {
    data class Stopped(val latest: IssueLifecycleActiveTasks) : IssueLifecycleTaskStopResult
    data class Failure(val code: String) : IssueLifecycleTaskStopResult
}

interface IssueLifecycleTaskController {
    suspend fun inspect(issueId: String): IssueLifecycleActiveTasks
    suspend fun stopAll(snapshot: IssueLifecycleActiveTasks): IssueLifecycleTaskStopResult
}

/**
 * 生命周期流程只编排既有正式 Stop/Cancel 接口，不在 Repository 事务中取消网络或 WorkManager。
 *
 * 官方 Skill 目录加载失败时 Execution/Collaboration Coordinator 可能为空；此时仍允许只读检查，
 * 但若确实存在相关活动任务，停止请求必须显式失败，不能把任务伪装成终态。
 */
