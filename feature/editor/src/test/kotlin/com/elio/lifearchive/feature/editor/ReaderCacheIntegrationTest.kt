package com.elio.lifearchive.feature.editor

import com.elio.lifearchive.core.document.documentDigest
import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReaderCacheIntegrationTest {
    private class Cache : ReaderCacheRepository {
        var current = ReaderCacheToken("first"); val values = mutableMapOf<ReaderArtifactKey, String>()
        override suspend fun token() = current
        override suspend fun get(token: ReaderCacheToken, key: ReaderArtifactKey) = if (token == current) values[key] else null
        override suspend fun put(token: ReaderCacheToken, key: ReaderArtifactKey, value: String): Boolean { if (token != current) return false; values[key] = value; return true }
        override suspend fun usage() = ReaderCacheUsage()
        override suspend fun clear() { current = ReaderCacheToken("next"); values.clear() }
    }
    @Test fun verifiedRootSnapshotIsNotReadAgainAndReopeningUsesAStableProjectionKey() = runTest {
        var reads = 0
        val repo = object : MemoryNotes() { override suspend fun read(id: NoteId): NoteResult<NoteSnapshot> { reads++; return super.read(id) } }
        val cache = Cache(); val loader = ReaderDocumentLoader(repo, cache, backgroundScope)
        val first = ReaderSnapshot("# 正文\n\n**粗体**", "view-1", "目录/笔记.md", "v1")
        val value = loader.load(repo.note.summary.id, repo.note.summary.id, first)!!
        runCurrent()
        assertEquals(0, reads); assertFalse(value.getBoolean("cacheHit"))
        val reopened = loader.load(repo.note.summary.id, repo.note.summary.id, first.copy(revision = "view-2"))!!
        assertTrue(reopened.getBoolean("cacheHit")); assertEquals("view-2", reopened.getString("revision"))
        assertEquals(value.getString("html"), reopened.getString("html")); assertEquals(0, reads)
        val edited = loader.load(repo.note.summary.id, repo.note.summary.id, first.copy(source = "更新的正文"))!!
        assertFalse(edited.getBoolean("cacheHit")); assertNotEquals(value.getString("projectionKey"), edited.getString("projectionKey"))
        loader.load(NoteId("missing-embed"), repo.note.summary.id, first)
        assertEquals(1, reads)
    }

    @Test fun messageBoundaryRejectsFramesOriginsOldRevisionsNamespacesAndHtmlWrites() = runTest {
        val cache = Cache()
        val value = JSONObject().put("revision", "r").put("namespace", "first").put("kind", "FORMULA").put("key", documentDigest("formula")).put("value", "<svg></svg>")
        assertTrue(ReaderCacheMessages.allowed(true, "https://appassets.androidplatform.net", "r", value))
        assertFalse(ReaderCacheMessages.allowed(false, "https://appassets.androidplatform.net", "r", value))
        assertFalse(ReaderCacheMessages.allowed(true, "https://other.example", "r", value))
        assertFalse(ReaderCacheMessages.allowed(true, "https://appassets.androidplatform.net", "new", value))
        assertTrue(ReaderCacheMessages.put(value, cache))
        value.put("kind", "HTML"); assertFalse(ReaderCacheMessages.put(value, cache))
        value.put("kind", "FORMULA").put("key", "../../escape"); assertFalse(ReaderCacheMessages.put(value, cache))
        value.put("key", documentDigest("formula")); cache.clear(); assertFalse(ReaderCacheMessages.put(value, cache))
    }

    @Test fun failedCacheWritesAndMalformedEntriesFallBackToNormalProjection() = runTest {
        val repo = MemoryNotes(); val cache = Cache()
        val broken = object : ReaderCacheRepository by cache {
            override suspend fun put(token: ReaderCacheToken, key: ReaderArtifactKey, value: String): Boolean = error("磁盘空间不足")
        }
        val loader = ReaderDocumentLoader(repo, broken, backgroundScope)
        val snapshot = ReaderSnapshot("正文 **仍可阅读**", path = "笔记.md")
        val value = loader.load(repo.note.summary.id, repo.note.summary.id, snapshot)!!
        runCurrent(); assertTrue(value.getString("html").contains("仍可阅读")); assertFalse(value.getBoolean("cacheHit"))
        cache.values[ReaderArtifactKey(ReaderArtifactKind.HTML, value.getString("projectionKey"))] = "损坏 JSON"
        val retried = loader.load(repo.note.summary.id, repo.note.summary.id, snapshot)!!
        assertEquals(value.getString("html"), retried.getString("html")); assertFalse(retried.getBoolean("cacheHit"))
    }

    @Test fun clearDuringDocumentReadInvalidatesThePendingProjectionWrite() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : MemoryNotes() { override suspend fun read(id: NoteId): NoteResult<NoteSnapshot> { entered.complete(Unit); release.await(); return super.read(id) } }
        val cache = Cache(); val loader = ReaderDocumentLoader(repo, cache, backgroundScope)
        val loading = async { loader.load(repo.note.summary.id, repo.note.summary.id, ReaderSnapshot("正文")) }
        entered.await(); cache.clear(); release.complete(Unit); assertNotNull(loading.await()); runCurrent()
        assertTrue(cache.values.isEmpty())
    }
}
