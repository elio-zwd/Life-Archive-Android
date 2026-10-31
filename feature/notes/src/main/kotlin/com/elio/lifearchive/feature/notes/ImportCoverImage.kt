package com.elio.lifearchive.feature.notes

import android.graphics.BitmapFactory
import com.elio.lifearchive.domain.notes.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 选择器的输入只在导入期间使用；不保存临时 URI，也不修改正文。 */
suspend fun importCoverImage(notes: NotesRepository, input: InputStream, name: String): NoteResult<CoverImage> = input.use { source -> withContext(Dispatchers.IO) {
    try {
        val bytes = run {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 8 * 1024 * 1024) { "请选择不超过 8 MiB 的图片" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "这张图片无法解码，请选择其他图片" }
        val extension = bounds.outMimeType?.substringAfter('/')?.let { if (it == "jpeg") "jpg" else it }?.takeIf { it in setOf("jpg", "png", "webp", "gif", "bmp", "heic", "heif", "avif") } ?: "img"
        when (val imported = notes.importAttachment("_covers/${UUID.randomUUID()}.$extension", ByteArrayInputStream(bytes))) {
            is NoteResult.Success -> NoteResult.Success(CoverImage(imported.value, name))
            else -> NoteResult.StorageFailure(imported.failureMessage())
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { NoteResult.StorageFailure(e.message ?: "图片导入失败") }
} }
