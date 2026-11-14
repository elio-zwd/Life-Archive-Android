package com.elio.jianyu.execution

import com.elio.jianyu.domain.ExecutionParticipantSnapshotEntity
import com.elio.jianyu.skill.catalog.OfficialSkillExecutionContext

data class ExecutionSkillSelection(
    val officialSkillId: String,
    val defaultResponsibility: String = "",
    /**
     * 新运行必须提供本次上下文快照。null 仅供已经冻结并确认过的历史 Participant
     * 在点名、交叉讨论或透明整合时复用，不能作为首页新运行的默认值。
     */
    val executionContext: OfficialSkillExecutionContext? = null,
)

fun interface ExecutionSkillResolver {
    suspend fun resolve(
        runId: String,
        selections: List<ExecutionSkillSelection>,
        createdAt: Long,
    ): List<ExecutionParticipantSnapshotEntity>
}

class InvalidExecutionSkillException(
    val officialSkillId: String,
    val reasonCode: String,
) : IllegalArgumentException("Skill $officialSkillId cannot execute: $reasonCode")
