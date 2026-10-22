package com.elio.lifearchive.domain.notes

import java.io.InputStream

data class CachedRemote(val key: String, val url: String, val page: Boolean, val fetched: Long, val partial: Boolean, val bytes: Long)
data class CacheStream(val mime: String, val length: Long, val stream: InputStream)
interface RemoteCacheRepository {
    suspend fun find(url: String, page: Boolean): CachedRemote?
    suspend fun fetch(url: String, page: Boolean, refresh: Boolean = false): NoteResult<CachedRemote>
    suspend fun open(key: String, path: String = "content", offset: Long = 0, length: Long? = null): CacheStream?
    suspend fun size(): Long
    suspend fun clear()
}
