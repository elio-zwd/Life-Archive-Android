package com.elio.lifearchive.feature.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.elio.lifearchive.core.document.LinkSyntax
import com.elio.lifearchive.domain.notes.LibraryRepository
import com.elio.lifearchive.domain.notes.NoteId
import com.elio.lifearchive.domain.notes.NoteResult
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 仅缓存可丢弃的缩略图；附件只能经笔记库公开接口访问。 */
internal class HomeThumbnailLoader(private val library: LibraryRepository) {
    private data class Key(val note: NoteId, val target: String, val syntax: LinkSyntax)

    private val permits = Semaphore(2)
    private val lock = Any()
    private var generation = 0L
    private var retired = false
    private val cache = object : LruCache<Key, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    fun invalidate() = synchronized(lock) {
        generation++
        cache.evictAll()
    }

    fun retire() = synchronized(lock) {
        retired = true
        generation++
        cache.evictAll()
    }

    suspend fun load(note: NoteId, target: String, syntax: LinkSyntax): ImageBitmap? {
        currentCoroutineContext().ensureActive()
        // 远程 URL、协议相对 URL 和 data URL 不属于本地附件。
        if (target.startsWith("//") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(target)) return null
        val key = Key(note, target, syntax)
        val currentGeneration = synchronized(lock) {
            if (retired) return null
            cache.get(key)?.let { return it.asImageBitmap() }
            generation
        }
        return try {
            val image = withContext(Dispatchers.IO) {
                permits.withPermit {
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (currentGeneration != generation) return@withPermit null
                        cache.get(key)?.let { return@withPermit it.asImageBitmap() }
                    }
                    val resolved = (library.resolve(note, target, syntax) as? NoteResult.Success)?.value ?: return@withPermit null
                    val path = resolved.attachment ?: return@withPermit null
                    currentCoroutineContext().ensureActive()
                    if (synchronized(lock) { currentGeneration != generation }) return@withPermit null
                    // 打开与接管流之间不可丢失句柄；随后立即检查取消并在 use 中关闭。
                    val resource = withContext(NonCancellable) { (library.openResource(path) as? NoteResult.Success)?.value } ?: return@withPermit null
                    val bytes = resource.stream.use { input ->
                        currentCoroutineContext().ensureActive()
                        if (resource.info.length < 0 || resource.info.length > MAX_ENCODED_BYTES ||
                            (!resource.info.mime.startsWith("image/") && resource.info.mime != "application/octet-stream")) return@withPermit null
                        val output = ByteArrayOutputStream(resource.info.length.toInt())
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_ENCODED_BYTES) return@withPermit null
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withPermit null
                    var sample = 1
                    while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withPermit null
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (currentGeneration != generation) return@withPermit null
                        cache.put(key, bitmap)
                    }
                    bitmap.asImageBitmap()
                }
            }
            // 工作线程写入后、调用者恢复前仍可能发生资料换代。
            synchronized(lock) { if (currentGeneration == generation) image else null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_ENCODED_BYTES = 8 * 1024 * 1024
        const val MAX_DIMENSION = 256
    }
}
