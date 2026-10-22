package com.elio.lifearchive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.domain.notes.*
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class PlatformFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun sharingCleanupPreservesNewCopiesAndDirectories() {
        val directory = temp.newFolder()
        val now = System.currentTimeMillis()
        val old = File(directory, "旧副本.pdf").apply { writeText("旧"); setLastModified(now - 48L * 60 * 60 * 1000) }
        val recent = File(directory, "新副本.pdf").apply { writeText("新") }
        val nested = File(directory, "保留目录").apply { mkdir() }
        cleanSharedCopies(directory, now)
        assertFalse(old.exists()); assertTrue(recent.isFile); assertTrue(nested.isDirectory)
    }

    @Test fun failedShareClosesTheSourceAndRemovesItsIncompleteCopy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val before = directory.list()!!.toSet()
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("受控读取失败")
            override fun close() { closed = true }
        }
        val path = LibraryPath("资料.pdf")
        val stream = ResourceStream(ResourceInfo(path, 10, "application/pdf"), input)
        val library = Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, _ ->
            if (method.name.substringBefore('-') == "openResource") NoteResult.Success(stream) else error(method.name)
        } as LibraryRepository
        try { openAttachment(context, library, path); fail("应报告读取失败") } catch (_: java.io.IOException) { }
        assertTrue(closed); assertEquals(before, directory.list()!!.toSet())
    }
}
