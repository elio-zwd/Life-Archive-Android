package com.elio.jianyu.lifecycle

import com.elio.jianyu.domain.RepositoryResult

fun interface IssuePurgeImpactProvider {
    suspend fun inspect(issueId: String): RepositoryResult<IssuePurgeImpactSnapshot>
}

/**
 * 从实际 Room 与受控音频文件服务生成不可恢复清理预览。
 *
 * Orphan 仅进入独立报告，不进入正式文件删除集合；外部 Material 定位符只计数，不读取或删除。
 */
