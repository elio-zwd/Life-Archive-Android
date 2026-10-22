package com.elio.lifearchive.data.notes

import com.elio.lifearchive.domain.notes.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteCacheStoreTest {
    @Test fun duplicateLoadsShareOneDownloadAndAnOlderRefreshCannotReplaceTheNewerOne() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val store = RemoteCacheStore(temp.newFolder()) { url ->
            val revision = calls.incrementAndGet()
            if (revision == 1) { entered.complete(Unit); release.await() }
            RemoteCacheStore.Download("image/png", byteArrayOf(revision.toByte()).inputStream(), url)
        }
        val old = async { store.fetch("https://example.test/same", false) }; entered.await()
        val joined = async(start = CoroutineStart.UNDISPATCHED) { store.fetch("https://example.test/same", false) }
        val fresh = store.fetch("https://example.test/same", false, true) as NoteResult.Success
        release.complete(Unit)
        assertTrue(old.await() is NoteResult.StorageFailure); assertTrue(joined.await() is NoteResult.StorageFailure)
        assertEquals(2, calls.get())
        assertEquals(2, store.open(fresh.value.key)!!.stream.use { it.read() })
    }
    @Test fun clearInvalidatesRequestsAlreadyWaitingForADownloadSlot() = runBlocking {
        val started = kotlinx.coroutines.channels.Channel<Unit>(2); val release = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val store = RemoteCacheStore(temp.newFolder()) { url ->
            if (calls.incrementAndGet() <= 2) { started.send(Unit); release.await() }
            RemoteCacheStore.Download("image/png", byteArrayOf(1).inputStream(), url)
        }
        val first = async { store.fetch("https://example.test/a", false) }
        val second = async { store.fetch("https://example.test/b", false) }
        started.receive(); started.receive()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { store.fetch("https://example.test/c", false) }
        store.clear(); release.complete(Unit)
        assertTrue(first.await() is NoteResult.StorageFailure); assertTrue(second.await() is NoteResult.StorageFailure)
        assertTrue(queued.await() is NoteResult.StorageFailure)
        assertEquals(0L, store.size())
    }
    @get:Rule val temp = TemporaryFolder()
    private val article = "<html><head><title>网页标题</title></head><body><nav>不应保留的导航</nav><article><h1>标题</h1><p>" + "中文正文与离线副本。".repeat(30) + "</p><img src='/image.png'><script>evil()</script></article></body></html>"
    @Test fun nonHtmlPageClosesItsNetworkStreamBeforeReportingFailure() = runBlocking {
        var closed = false
        val stream = object : java.io.ByteArrayInputStream(byteArrayOf(1)) { override fun close() { closed = true; super.close() } }
        val store = RemoteCacheStore(temp.newFolder()) { url -> RemoteCacheStore.Download("application/pdf", stream, url) }
        assertTrue(store.fetch("https://example.test/file", true) is NoteResult.StorageFailure)
        assertTrue(closed); assertEquals(0L, store.size())
    }
    @Test fun publicArticleAndImagesAreCachedWithoutScriptsAndRemainReadableOffline() = runBlocking {
        var online = true
        val store = RemoteCacheStore(temp.newFolder()) { url ->
            if (!online) throw IOException("离线")
            RemoteCacheStore.Download(if (url.endsWith(".png")) "image/png" else "text/html", if (url.endsWith(".png")) byteArrayOf(1, 2, 3).inputStream() else article.byteInputStream(), url)
        }
        val result = store.fetch("https://example.test/article", true) as NoteResult.Success
        assertFalse(result.value.partial)
        online = false
        val cached = store.find("https://example.test/article", true)!!
        val html = store.open(cached.key)!!.stream.use { it.bufferedReader().readText() }
        assertTrue(html.contains("中文正文")); assertFalse(html.contains("evil()")); assertFalse(html.contains("不应保留的导航"))
        assertTrue(store.fetch("https://example.test/article", true) is NoteResult.Success)
    }
    @Test fun failedRefreshPreservesOldCopyAndMissingImageIsReportedPartial() = runBlocking {
        var failed = false
        val store = RemoteCacheStore(temp.newFolder()) { url ->
            if (failed || url.endsWith(".png")) throw IOException("获取失败")
            RemoteCacheStore.Download("text/html", article.byteInputStream(), url)
        }
        val first = (store.fetch("https://example.test/a", true) as NoteResult.Success).value
        assertTrue(first.partial)
        failed = true
        assertTrue(store.fetch("https://example.test/a", true, true) is NoteResult.StorageFailure)
        assertNotNull(store.open(first.key)?.also { it.stream.close() })
    }
    @Test fun clearingCachePreventsInFlightDownloadFromReappearing() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = RemoteCacheStore(temp.newFolder()) { url -> started.complete(Unit); release.await(); RemoteCacheStore.Download("image/png", byteArrayOf(1).inputStream(), url) }
        val job = async { store.fetch("https://example.test/image", false) }
        started.await(); store.clear(); release.complete(Unit)
        assertTrue(job.await() is NoteResult.StorageFailure)
        assertEquals(0L, store.size()); assertNull(store.find("https://example.test/image", false))
    }
    @Test fun nonArticlePagesAndOversizeDownloadsDoNotBecomeSuccessfulEntries() = runBlocking {
        val root = temp.newFolder()
        val store = RemoteCacheStore(root, 128) { url -> RemoteCacheStore.Download("text/html", "<html>登录</html>".byteInputStream(), url) }
        assertTrue(store.fetch("https://example.test/login", true) is NoteResult.StorageFailure)
        val large = RemoteCacheStore(File(root, "large"), 8) { url -> RemoteCacheStore.Download("image/png", ByteArray(32).inputStream(), url) }
        assertTrue(large.fetch("https://example.test/large", false) is NoteResult.StorageFailure)
        assertEquals(0L, large.size())
    }
}
