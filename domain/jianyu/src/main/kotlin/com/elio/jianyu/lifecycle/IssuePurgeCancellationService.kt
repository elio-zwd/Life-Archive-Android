package com.elio.jianyu.lifecycle

import com.elio.jianyu.domain.IssueLifecycleEntity
import com.elio.jianyu.domain.RepositoryResult

interface IssuePurgeCancellationService {
    suspend fun cancel(operationId: String, canceledAt: Long): RepositoryResult<IssueLifecycleEntity>
}
