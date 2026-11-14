package com.elio.jianyu.lifecycle



object IssuePurgeWorkPolicy {
    const val OPERATION_ID_KEY = "purge_operation_id"
    private const val UNIQUE_WORK_PREFIX = "jianyu-issue-purge-"

    fun uniqueWorkName(operationId: String): String =
        UNIQUE_WORK_PREFIX + operationId.requireStableWorkId()
}

interface IssuePurgeScheduler {
    suspend fun isActive(operationId: String): Boolean
    suspend fun schedule(operationId: String): Boolean
    suspend fun cancel(operationId: String): Boolean
}


private fun String.requireStableWorkId(): String {
    require(isNotBlank()) { "清理操作 ID 不能为空" }
    require(length <= 128) { "清理操作 ID 过长" }
    require(Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(this)) {
        "清理操作 ID 包含不安全字符"
    }
    return this
}
