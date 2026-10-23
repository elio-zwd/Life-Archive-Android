package com.elio.lifearchive.feature.editor

import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** 根文档复用已核验的编辑快照，嵌入文档每次独立核验，不缓存展开后的整页。 */
internal class ReaderDocumentLoader(private val notes: NotesRepository, private val cache: ReaderCacheRepository?, private val scope: CoroutineScope) {
    suspend fun load(id: NoteId, root: NoteId, current: ReaderSnapshot): JSONObject? {
        // 在读取、摘要及解析前捕获代次，清理期间尚未完成的投影也不能落回。
        val token = try { cache?.token() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val source: String; val path: String; val version: String; val digest: String
        if (id == root && current.path != null) {
            source = current.source; path = current.path; version = current.version.orEmpty(); digest = current.digest
        } else {
            val read = notes.read(id) as? NoteResult.Success ?: return null
            source = if (id == root) current.source else read.value.document.text
            path = read.value.summary.path.value; version = read.value.summary.version.value; digest = documentDigest(source)
        }
        val key = ReaderArtifactKey(ReaderArtifactKind.HTML, documentDigest("${HtmlProjection.VERSION}\u0000${id.value}\u0000$path\u0000$digest"))
        val cached = try { if (token != null) cache?.get(token, key)?.let(::JSONObject) else null } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val hit = cached != null && cached.optString("digest") == digest && cached.has("html") && cached.has("blocks") && cached.has("ranges")
        val result = if (hit) cached!! else {
            val projected = withContext(Dispatchers.Default) { HtmlProjection.project(source) }
            JSONObject().put("html", projected.html).put("digest", projected.digest)
                .put("ranges", JSONArray(projected.ranges.map { JSONObject().put("start", it.start).put("end", it.end) }))
                .put("blocks", JSONArray(projected.blocks.map { JSONObject().put("name", it.name).put("start", it.start).put("end", it.end) }))
                .also { value -> if (token != null) { val encoded = value.toString(); scope.launch(Dispatchers.IO) { try { cache?.put(token, key, encoded) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* 派生写入失败不影响阅读。 */ } } } }
        }
        return result.put("id", id.value).put("revision", if (id == root) current.revision else version).put("path", path)
            .put("cacheHit", hit).put("cacheNamespace", token?.namespace).put("projectionKey", key.digest)
    }
}
