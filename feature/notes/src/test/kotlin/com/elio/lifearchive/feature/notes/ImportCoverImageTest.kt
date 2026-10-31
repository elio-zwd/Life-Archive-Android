package com.elio.lifearchive.feature.notes

import android.graphics.Bitmap
import android.media.ExifInterface
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.domain.notes.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import com.elio.lifearchive.core.document.LinkSyntax
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportCoverImageTest {
    @Test fun cameraPhotoOrientationMatchesTheReaderInsteadOfTurningTheThumbnailSideways() = runBlocking {
        val file = File.createTempFile("cover-orientation-", ".jpg")
        try {
            val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
            ExifInterface(file.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
            val bytes = file.readBytes()
            val library = java.lang.reflect.Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, _ ->
                when (method.name.substringBefore('-')) {
                    "resolve" -> NoteResult.Success(ResolvedLink(attachment = LibraryPath("相机.jpg")))
                    "openResource" -> NoteResult.Success(ResourceStream(ResourceInfo(LibraryPath("相机.jpg"), bytes.size.toLong(), "image/jpeg"), bytes.inputStream()))
                    else -> error("意外操作 ${method.name}")
                }
            } as LibraryRepository
            val loaded = HomeThumbnailLoader(library).load(NoteId("camera"), "相机.jpg", LinkSyntax.WIKI)!!
            assertEquals(32, loaded.width); assertEquals(64, loaded.height)
        } finally { file.delete() }
    }

    @Test fun pickerBytesAreCopiedWithAnIndependentLibraryPathAndClosed() = runBlocking {
        val bytes = ByteArrayOutputStream().also { out ->
            val image = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
            image.compress(Bitmap.CompressFormat.PNG, 100, out); image.recycle()
        }.toByteArray()
        var closed = false
        var stored: ByteArray? = null
        val notes = java.lang.reflect.Proxy.newProxyInstance(NotesRepository::class.java.classLoader, arrayOf(NotesRepository::class.java)) { _, method, args ->
            check(method.name.substringBefore('-') == "importAttachment")
            stored = (args!![1] as java.io.InputStream).readBytes()
            NoteResult.Success(LibraryPath(args[0] as String))
        } as NotesRepository
        val input = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
        val result = importCoverImage(notes, input, "手机图片.png") as NoteResult.Success
        assertTrue(result.value.path.value.startsWith("_covers/")); assertTrue(closed)
        assertArrayEquals(bytes, stored)
    }

    @Test fun brokenOrOversizedInputIsClosedWithoutImportingAnything() = runBlocking {
        val notes = java.lang.reflect.Proxy.newProxyInstance(NotesRepository::class.java.classLoader, arrayOf(NotesRepository::class.java)) { _, method, _ -> error("失败输入不得写入：${method.name}") } as NotesRepository
        for (bytes in listOf(byteArrayOf(1, 2, 3), ByteArray(8 * 1024 * 1024 + 1))) {
            var closed = false
            val input = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
            assertTrue(importCoverImage(notes, input, "无效图片") is NoteResult.StorageFailure)
            assertTrue(closed)
        }
    }
}
