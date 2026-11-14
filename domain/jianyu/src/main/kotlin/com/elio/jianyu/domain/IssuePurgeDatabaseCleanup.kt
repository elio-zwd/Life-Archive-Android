package com.elio.jianyu.domain

import com.elio.jianyu.domain.RepositoryResult

fun interface IssuePurgeDatabaseCleanup {
    suspend fun purge(operationId: String, purgedAt: Long): RepositoryResult<Unit>
}

/**
 * 彻底清除数据库阶段。
 *
 * 调用前必须完成所有正式文件清理。全部 SQL 位于同一 Room 事务；任一失败会保留 Issue、
 * Lifecycle 与 Purge Operation，供协调器记录 `purge_database_failed` 并显式重试。
 */
