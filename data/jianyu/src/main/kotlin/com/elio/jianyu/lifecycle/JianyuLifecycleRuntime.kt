package com.elio.jianyu.lifecycle
import android.content.Context
import com.elio.jianyu.audio.runtime.JianyuAudioRuntime
import com.elio.jianyu.collaboration.IssueCollaborationCoordinator
import com.elio.jianyu.data.IssuePurgeDatabaseCleaner
import com.elio.jianyu.domain.JianyuRepository
import com.elio.jianyu.data.RoomIssueLifecycleV12Repository
import com.elio.jianyu.data.RoundtableDatabase
import com.elio.jianyu.execution.ExecutionRunCoordinator

fun createJianyuLifecycleRuntime(
    context: Context,
    database: RoundtableDatabase,
    repository: JianyuRepository,
    audioRuntime: JianyuAudioRuntime,
    executionCoordinator: ExecutionRunCoordinator?,
    collaborationCoordinator: IssueCollaborationCoordinator?,
): JianyuLifecycleRuntime {
    val lifecycleRepository = RoomIssueLifecycleV12Repository(database)
    val taskController = DefaultIssueLifecycleTaskController(
        repository = repository,
        executionCoordinator = executionCoordinator,
        collaborationCoordinator = collaborationCoordinator,
        audioCoordinator = audioRuntime.generationCoordinator,
    )
    val impactCalculator = IssuePurgeImpactCalculator(
        database = database,
        audioLifecycleService = audioRuntime.lifecycleService,
    )
    val scheduler = WorkManagerIssuePurgeScheduler(context)
    val purgeCoordinator = IssuePurgeCoordinator(
        repository = lifecycleRepository,
        impactCalculator = impactCalculator,
        taskController = taskController,
        fileCleaner = IssuePurgeFileCleaner(
            lifecycleService = audioRuntime.lifecycleService,
            fileStore = audioRuntime.fileStore,
        ),
        databaseCleaner = IssuePurgeDatabaseCleaner(database),
        scheduler = scheduler,
    )
    return JianyuLifecycleRuntime(
        repository = lifecycleRepository,
        taskController = taskController,
        archiveCoordinator = IssueArchiveCoordinator(
            repository = repository,
            lifecycleRepository = lifecycleRepository,
            taskController = taskController,
        ),
        impactCalculator = impactCalculator,
        purgeCoordinator = purgeCoordinator,
        purgeCancellationService = DatabaseIssuePurgeCancellationService(database, scheduler),
        purgeScheduler = scheduler,
    )
}
