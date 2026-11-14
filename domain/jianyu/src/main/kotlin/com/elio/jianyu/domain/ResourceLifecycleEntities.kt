package com.elio.jianyu.domain


data class MaterialReferenceEntity(
     val id: String,
    val issueId: String,
    val stageId: String? = null,
    val title: String,
    val sourceType: String,
    val sourceLocator: String? = null,
    val content: String,
    val contentHash: String,
    val sourcePublishedAt: Long? = null,
    val sourceCapturedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,

    val lifecycleState: ContextSourceLifecycle = ContextSourceLifecycle.ACTIVE,

    val sensitive: Boolean = false,
    val disabledAt: Long? = null,
    val archivedAt: Long? = null,
    val deletedAt: Long? = null,
    val purgeRequestedAt: Long? = null,
    val purgedAt: Long? = null
)


data class MaterialUsageSnapshotEntity(
     val id: String,
    val issueId: String,
    val stageId: String,
    val runId: String? = null,
    val materialReferenceId: String? = null,
    val titleSnapshot: String,
    val sourceTypeSnapshot: String,
    val sourceLocatorSnapshot: String? = null,
    val contentSnapshot: String?,
    val contentHash: String,

    val contentState: SnapshotContentState = SnapshotContentState.AVAILABLE,
    val sourcePublishedAtSnapshot: Long? = null,
    val sourceCapturedAtSnapshot: Long? = null,
    val userConfirmedAt: Long,
    val createdAt: Long,

    val networkAllowed: Boolean = false,

    val sensitive: Boolean = true
)


data class PersonalContextEntryEntity(
     val id: String,
    val title: String,
    val content: String,
    val contentHash: String,

    val isEnabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,

    val lifecycleState: ContextSourceLifecycle = ContextSourceLifecycle.ACTIVE,

    val sensitive: Boolean = false,
    val disabledAt: Long? = null,
    val archivedAt: Long? = null,
    val deletedAt: Long? = null,
    val purgeRequestedAt: Long? = null,
    val purgedAt: Long? = null
)


data class PersonalContextUsageSnapshotEntity(
     val id: String,
    val issueId: String,
    val stageId: String,
    val runId: String? = null,
    val personalContextEntryId: String? = null,
    val titleSnapshot: String,
    val contentSnapshot: String?,
    val contentHash: String,

    val contentState: SnapshotContentState = SnapshotContentState.AVAILABLE,
    val userConfirmedAt: Long,
    val createdAt: Long,

    val networkAllowed: Boolean = false,

    val sensitive: Boolean = true
)



data class SkillKnowledgeUsageSnapshotEntity(
     val id: String,
    val issueId: String,
    val stageId: String,
    val runId: String? = null,
    val sourceSkillId: String,
    val documentId: String,
    val titleSnapshot: String,
    val relativePathSnapshot: String,
    val contentSnapshot: String,
    val contentHash: String,
    val userConfirmedAt: Long,
    val createdAt: Long
)


data class StageSummaryDraftEntity(
     val id: String,
    val issueId: String,
    val stageId: String,
    val content: String,
    val revisionNumber: Int,
    val createdAt: Long,
    val updatedAt: Long
)


data class StageSummaryDraftRevisionEntity(
     val id: String,
    val issueId: String,
    val stageId: String,
    val draftIdSnapshot: String,
    val revisionNumber: Int,
    val contentSnapshot: String,
    val createdAt: Long
)


data class ConfirmedArtifactEntity(
     val id: String,
    val issueId: String,
    val stageId: String,
    val title: String,
    val content: String,
    val artifactType: String,
    val contentFormat: String,
    val confirmedAt: Long,
    val revisionOfArtifactId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)


data class ArtifactMessageSourceEntity(
    val artifactId: String,
    val issueId: String,
    val messageId: Long,
    val createdAt: Long
)


data class ArtifactRunSourceEntity(
    val artifactId: String,
    val issueId: String,
    val runId: String,
    val createdAt: Long
)


data class ArtifactDraftSourceEntity(
    val artifactId: String,
    val issueId: String,
    val draftRevisionId: String,
    val createdAt: Long
)


data class ArtifactMaterialSourceEntity(
    val artifactId: String,
    val issueId: String,
    val materialUsageSnapshotId: String,
    val createdAt: Long
)


data class AudioAssetEntity(
     val id: String,
    val issueId: String,
    val stageId: String,
    val sourceMessageId: Long? = null,
    val sourceArtifactId: String? = null,
    val storagePath: String,
    val mimeType: String,
    val format: String,
    val sizeBytes: Long,

    val fileState: AudioFileState = AudioFileState.PENDING,
    val generationKey: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val purgeRequestedAt: Long? = null
)


data class OfficialSkillCombinationEntity(
     val id: String,
    val name: String,

    val isEnabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)


data class OfficialSkillCombinationMemberEntity(
    val combinationId: String,
    val officialSkillId: String,
    val position: Int,
    val defaultResponsibility: String? = null,
    val createdAt: Long
)


data class IssueLifecycleEntity(
     val issueId: String,

    val state: IssueLifecycleState = IssueLifecycleState.ACTIVE,
    val previousState: IssueLifecycleState? = null,
    val stateChangedAt: Long,
    val updatedAt: Long,
    val archivedAt: Long? = null,
    val trashedAt: Long? = null,
    val purgeRequestedAt: Long? = null
)
