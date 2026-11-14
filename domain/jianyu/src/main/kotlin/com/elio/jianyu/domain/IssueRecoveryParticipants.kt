package com.elio.jianyu.domain

/**
 * 已持久化成功成员的稳定参与者快照 ID。
 *
 * Pending 消息不视为成功；一个成员只要存在至少一条已完成消息即可在恢复后识别为成功。
 */
fun IssueRecoveryCore.successfulParticipantSnapshotIds(): Set<String> {
    return messages.asSequence()
        .filterNot { it.isPending }
        .mapNotNull { it.participantSnapshotId }
        .toSet()
}

/**
 * 当前活跃或可恢复 Run 中尚无成功消息的稳定参与者快照 ID。
 *
 * 该集合只描述持久化恢复数据，不决定模型重试、预算或网络策略。
 */
fun IssueRecoveryCore.retryableParticipantSnapshotIds(): Set<String> {
    val recoverableRunIds = activeOrRecoverableRuns.mapTo(mutableSetOf()) { it.id }
    val succeeded = successfulParticipantSnapshotIds()
    return participants.asSequence()
        .filter { it.runId in recoverableRunIds }
        .map { it.id }
        .filterNot { it in succeeded }
        .toSet()
}
