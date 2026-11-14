package com.elio.jianyu.lifecycle

import com.elio.jianyu.domain.IssueLifecycleV12Repository

data class JianyuLifecycleRuntime(
    val repository: IssueLifecycleV12Repository,
    val taskController: IssueLifecycleTaskController,
    val archiveCoordinator: IssueArchiveCoordinator,
    val impactCalculator: IssuePurgeImpactProvider,
    val purgeCoordinator: IssuePurgeCoordinator,
    val purgeCancellationService: IssuePurgeCancellationService,
    val purgeScheduler: IssuePurgeScheduler,
)
