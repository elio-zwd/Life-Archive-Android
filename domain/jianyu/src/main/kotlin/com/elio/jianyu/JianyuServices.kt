package com.elio.jianyu

import com.elio.jianyu.audio.workspace.AudioAssetWorkspaceOperations
import com.elio.jianyu.collaboration.IssueCollaborationCoordinator
import com.elio.jianyu.domain.JianyuRepository
import com.elio.jianyu.execution.ExecutionRunCoordinator
import com.elio.jianyu.lifecycle.JianyuLifecycleRuntime
import com.elio.jianyu.preferences.JianyuPreferences
import com.elio.jianyu.result.StageResultService
import com.elio.jianyu.skill.catalog.OfficialSkillCatalogRuntimeResult
import com.elio.jianyu.skill.knowledge.SkillKnowledgeRepository
import com.elio.jianyu.profile.AvatarRepository
import com.elio.jianyu.conversation.ConversationReadingRepository
import com.elio.jianyu.material.TextMaterialReader
import com.elio.jianyu.network.AiServices
import com.elio.jianyu.skill.role.RolePresentationLoader
import com.elio.jianyu.telemetry.DiagnosticServices
import com.elio.jianyu.privacy.JianyuDataMaintenance
import com.elio.jianyu.backup.JianyuBackupService

/** app 按运行世代装配的公开业务能力，不含数据库、DAO 或实现查找入口。 */
data class JianyuServices(
    val repository: JianyuRepository,
    val officialSkillCatalogRuntimeResult: OfficialSkillCatalogRuntimeResult,
    val executionCoordinator: ExecutionRunCoordinator?,
    val collaborationCoordinator: IssueCollaborationCoordinator?,
    val skillKnowledgeRepository: SkillKnowledgeRepository,
    val stageResultService: StageResultService,
    val audioWorkspace: AudioAssetWorkspaceOperations,
    val lifecycleRuntime: JianyuLifecycleRuntime,
    val preferences: JianyuPreferences,
    val avatars: AvatarRepository,
    val readingState: ConversationReadingRepository,
    val materials: TextMaterialReader,
    val rolePresentation: RolePresentationLoader,
    val ai: AiServices,
    val diagnostics: DiagnosticServices,
    val maintenance: JianyuDataMaintenance,
    val backup: JianyuBackupService,
)
