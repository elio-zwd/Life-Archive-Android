package com.elio.lifearchive.feature.notes

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.DocumentVersion
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.core.document.LinkSyntax
import com.elio.lifearchive.domain.notes.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeThumbnailLoaderTest {
    @Test fun localImagesAreSampledCachedAndInvalidatedWithoutReadingTheNoteBody() = runBlocking {
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray()
        var resolves = 0
        var opens = 0
        var closed = 0
        val library = java.lang.reflect.Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "resolve" -> { resolves++; assertEquals("one", args!![0]); NoteResult.Success(ResolvedLink(attachment = LibraryPath("photo.png"))) }
                "openResource" -> {
                    opens++
                    val stream = object : ByteArrayInputStream(bytes) { override fun close() { closed++; super.close() } }
                    NoteResult.Success(ResourceStream(ResourceInfo(LibraryPath("photo.png"), bytes.size.toLong(), "image/png"), stream))
                }
                else -> error("意外访问 ${method.name}")
            }
        } as LibraryRepository
        val loader = HomeThumbnailLoader(library)
        val first = loader.load(NoteId("one"), "photo.png", LinkSyntax.MARKDOWN)
        assertNotNull(first)
        assertTrue(first!!.width <= 256 && first.height <= 256)
        assertNotNull(loader.load(NoteId("one"), "photo.png", LinkSyntax.MARKDOWN))
        assertEquals(1, resolves); assertEquals(1, opens); assertEquals(1, closed)
        loader.invalidate()
        assertNotNull(loader.load(NoteId("one"), "photo.png", LinkSyntax.MARKDOWN))
        assertEquals(2, resolves); assertEquals(2, opens); assertEquals(2, closed)
    }

    @Test fun remoteAndOversizedResourcesAreNotDecodedAndStreamsAreClosed() = runBlocking {
        var resolves = 0
        var closed = false
        val library = java.lang.reflect.Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, _ ->
            when (method.name.substringBefore('-')) {
                "resolve" -> { resolves++; NoteResult.Success(ResolvedLink(attachment = LibraryPath("huge.png"))) }
                "openResource" -> NoteResult.Success(ResourceStream(ResourceInfo(LibraryPath("huge.png"), 9L * 1024 * 1024, "image/png"),
                    object : ByteArrayInputStream(byteArrayOf(1)) { override fun close() { closed = true; super.close() } }))
                else -> error("意外访问 ${method.name}")
            }
        } as LibraryRepository
        val loader = HomeThumbnailLoader(library)
        assertNull(loader.load(NoteId("one"), "https://example.com/image.png", LinkSyntax.MARKDOWN))
        assertEquals(0, resolves)
        assertNull(loader.load(NoteId("one"), "huge.png", LinkSyntax.MARKDOWN))
        assertTrue(closed)
    }

    @Test fun invalidationRejectsAnOlderInFlightReceipt() = runBlocking {
        fun image(width: Int, height: Int) = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray()
        val oldBytes = image(512, 256)
        val newBytes = image(128, 128)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val library = java.lang.reflect.Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, _ ->
            when (method.name.substringBefore('-')) {
                "resolve" -> NoteResult.Success(ResolvedLink(attachment = LibraryPath("photo.png")))
                "openResource" -> {
                    val isOld = opens.incrementAndGet() == 1
                    val bytes = if (isOld) oldBytes else newBytes
                    val stream = object : ByteArrayInputStream(bytes) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (isOld && started.count > 0) {
                                started.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                            }
                            return super.read(buffer, offset, length)
                        }
                        override fun close() { closes.incrementAndGet(); super.close() }
                    }
                    NoteResult.Success(ResourceStream(ResourceInfo(LibraryPath("photo.png"), bytes.size.toLong(), "image/png"), stream))
                }
                else -> error("意外访问 ${method.name}")
            }
        } as LibraryRepository
        val loader = HomeThumbnailLoader(library)
        val old = async(Dispatchers.Default) { loader.load(NoteId("one"), "photo.png", LinkSyntax.MARKDOWN) }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            loader.invalidate()
        } finally { release.countDown() }
        assertNull(old.await())
        val current = loader.load(NoteId("one"), "photo.png", LinkSyntax.MARKDOWN)
        assertNotNull(current)
        assertEquals(128, current!!.width)
        assertNotNull(loader.load(NoteId("one"), "photo.png", LinkSyntax.MARKDOWN))
        assertEquals(2, opens.get())
        assertEquals(2, closes.get())
        loader.retire()
        assertNull(loader.load(NoteId("one"), "photo.png", LinkSyntax.MARKDOWN))
        assertEquals(2, opens.get())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun filteringAndSortingReuseThumbnailsButStorageChangesInvalidateThem() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val initial = NoteSummary(NoteId("one"), LibraryPath("one.md"), DocumentVersion("v1"), false)
        val changes = MutableSharedFlow<List<NoteSummary>>(replay = 1).apply { tryEmit(listOf(initial)) }
        var summary = initial
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray()
        val opens = AtomicInteger()
        val repository = java.lang.reflect.Proxy.newProxyInstance(NotesRepository::class.java.classLoader, arrayOf(NotesRepository::class.java)) { _, method, _ ->
            when (method.name.substringBefore('-')) {
                "observeNotes" -> changes
                else -> error("意外访问 ${method.name}")
            }
        } as NotesRepository
        val library = java.lang.reflect.Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, _ ->
            when (method.name.substringBefore('-')) {
                "getProgress" -> flowOf(null)
                "query" -> NoteResult.Success(LibraryListing(listOf(LibraryNote(summary, false, 0, 0, null, emptyList(), null,
                    thumbnail = ThumbnailReference("photo.png", LinkSyntax.MARKDOWN))), emptyList(), emptyList(), 0))
                "resolve" -> NoteResult.Success(ResolvedLink(attachment = LibraryPath("photo.png")))
                "openResource" -> {
                    opens.incrementAndGet()
                    NoteResult.Success(ResourceStream(ResourceInfo(LibraryPath("photo.png"), bytes.size.toLong(), "image/png"), ByteArrayInputStream(bytes)))
                }
                else -> error("意外访问 ${method.name}")
            }
        } as LibraryRepository
        val model = NotesViewModel(repository, library)
        try {
            runCurrent()
            assertFalse(model.state.value.loading)
            assertNotNull(model.loadThumbnail(NoteId("one"), "photo.png", LinkSyntax.MARKDOWN))
            val epoch = model.state.value.thumbnailEpoch
            for (query in listOf(NotesQuery(folder = "本子"), NotesQuery(tag = "标签", sort = NoteSort.NAME), NotesQuery())) {
                model.query(query)
                advanceTimeBy(201)
                runCurrent()
                assertEquals(epoch, model.state.value.thumbnailEpoch)
                assertNotNull(model.loadThumbnail(NoteId("one"), "photo.png", LinkSyntax.MARKDOWN))
            }
            assertEquals(1, opens.get())
            summary = initial.copy(version = DocumentVersion("v2"))
            changes.emit(listOf(summary))
            runCurrent()
            assertEquals(epoch + 1, model.state.value.thumbnailEpoch)
            assertNotNull(model.loadThumbnail(NoteId("one"), "photo.png", LinkSyntax.MARKDOWN))
            assertEquals(2, opens.get())
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
