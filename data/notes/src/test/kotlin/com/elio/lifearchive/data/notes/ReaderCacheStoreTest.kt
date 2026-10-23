package com.elio.lifearchive.data.notes

import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReaderCacheStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun key(char: Char = 'a', kind: ReaderArtifactKind = ReaderArtifactKind.HTML) = ReaderArtifactKey(kind, char.toString().repeat(64))

    @Test fun persistsAcrossInstancesAndOnlyMatchingTokensCanReadOrWrite() = runTest {
        val root = temp.newFolder()
        val first = ReaderCacheStore(root)
        val token = first.token()
        assertTrue(first.put(token, key(), "正文 HTML"))
        val reopened = ReaderCacheStore(root)
        assertEquals(token, reopened.token()); assertEquals("正文 HTML", reopened.get(token, key()))
        reopened.clear()
        assertNotEquals(token, reopened.token()); assertNull(reopened.get(token, key()))
        assertFalse(reopened.put(token, key(), "迟到的旧渲染"))
        assertEquals(0, reopened.usage().diskBytes)
    }

    @Test fun corruptOrRemovedCacheRebuildsWithoutFailingReading() = runTest {
        val root = temp.newFolder(); val cache = ReaderCacheStore(root); val token = cache.token()
        assertTrue(cache.put(token, key(), "正文"))
        root.listFiles()!!.first { it.extension == "bin" }.writeText("损坏")
        assertNull(ReaderCacheStore(root).get(token, key()))
        root.deleteRecursively()
        assertNotEquals(token, cache.token()); assertFalse(cache.put(token, key(), "旧内容"))
    }

    @Test fun limitsDiskAndMemoryByRecentUseAndSeparatesArtifactTypes() = runTest {
        val root = temp.newFolder(); val cache = ReaderCacheStore(root, memoryCapacity = 100, diskCapacity = 900)
        val token = cache.token()
        assertTrue(cache.put(token, key('a'), "a".repeat(80)))
        assertTrue(cache.put(token, key('b'), "b".repeat(80)))
        assertTrue(cache.put(token, key('c'), "c".repeat(80)))
        assertTrue(cache.usage().memoryBytes <= 100); assertTrue(cache.usage().diskBytes <= 900)
        assertNull(cache.get(token, key('a', ReaderArtifactKind.FORMULA)))
        assertEquals("c".repeat(80), cache.get(token, key('c')))
    }

    @Test fun oversizedAndFailedWritesDoNotReplaceAnExistingEntry() = runTest {
        val root = temp.newFolder(); val cache = ReaderCacheStore(root, entryCapacity = 64); val token = cache.token()
        assertTrue(cache.put(token, key(), "有效副本"))
        assertFalse(cache.put(token, key(), "汉".repeat(50)))
        cache.fault = { error("写入失败") }
        assertFalse(cache.put(token, key(), "新副本"))
        assertEquals("有效副本", cache.get(token, key()))
        assertFalse(cache.put(token, ReaderArtifactKey(ReaderArtifactKind.HTML, "../outside"), "拒绝"))
        assertFalse(File(root.parentFile, "outside").exists())
    }

    @Test fun computationStartedBeforeClearCannotWriteBackAfterClear() = runTest {
        val cache = ReaderCacheStore(temp.newFolder()); val token = cache.token()
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val queued = async { started.complete(Unit); finish.await(); cache.put(token, key(), "清理前开始的计算") }
        started.await(); cache.clear(); finish.complete(Unit)
        assertFalse(queued.await()); assertEquals(0, cache.usage().diskBytes)
    }
}
