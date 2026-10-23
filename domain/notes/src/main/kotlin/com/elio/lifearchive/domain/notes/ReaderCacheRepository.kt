package com.elio.lifearchive.domain.notes

/** 缓存代次跨普通重启保留，清理或替换资料后换代；不属于需要备份的应用状态。 */
data class ReaderCacheToken(val namespace: String)
enum class ReaderArtifactKind { HTML, FORMULA, DIAGRAM, GEOMETRY }
data class ReaderArtifactKey(val kind: ReaderArtifactKind, val digest: String)
data class ReaderCacheUsage(val memoryBytes: Long = 0, val diskBytes: Long = 0, val hits: Long = 0, val misses: Long = 0)

interface ReaderCacheRepository {
    suspend fun token(): ReaderCacheToken
    suspend fun get(token: ReaderCacheToken, key: ReaderArtifactKey): String?
    suspend fun put(token: ReaderCacheToken, key: ReaderArtifactKey, value: String): Boolean
    suspend fun usage(): ReaderCacheUsage
    suspend fun clear()
}
