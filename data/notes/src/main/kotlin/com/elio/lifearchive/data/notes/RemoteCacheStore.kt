package com.elio.lifearchive.data.notes

import com.elio.lifearchive.core.backup.atomicWrite
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.domain.notes.*
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/** 此目录是可丢弃的派生缓存，不能注册为备份参与者。网络下载不占用笔记 StorageGate。 */
class RemoteCacheStore(private val root: File, private val capacity: Long = 512L * 1024 * 1024,
    private val download: suspend (String) -> Download = { network(it) },
) : RemoteCacheRepository {
    data class Download(val mime: String, val input: InputStream, val finalUrl: String)
    @Serializable private data class Metadata(val key: String, val url: String, val page: Boolean, val fetched: Long, val partial: Boolean, val bytes: Long, val mime: String, val resources: Map<String, String> = emptyMap())
    private val mutex = Mutex()
    private val downloads = Semaphore(2)
    private var generation = 0L
    private fun key(url: String, page: Boolean) = MessageDigest.getInstance("SHA-256").digest(("$page:$url").toByteArray()).joinToString("") { "%02x".format(it) }
    private fun Metadata.public() = CachedRemote(key, url, page, fetched, partial, bytes)
    private fun metadata(directory: File): Metadata? = runCatching { Json.decodeFromString<Metadata>(File(directory, "metadata.json").readText()) }.getOrNull()
    override suspend fun find(url: String, page: Boolean): CachedRemote? = withContext(Dispatchers.IO) { mutex.withLock {
        val directory = File(root, key(url, page)); val value = metadata(directory)
        if (value != null && File(directory, "content").isFile) { directory.setLastModified(System.currentTimeMillis()); value.public() } else null
    } }
    override suspend fun size(): Long = withContext(Dispatchers.IO) { mutex.withLock { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
    override suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock { generation++; check(!root.exists() || root.deleteRecursively()); Unit } }
    override suspend fun fetch(url: String, page: Boolean, refresh: Boolean): NoteResult<CachedRemote> = downloads.withPermit { withContext(Dispatchers.IO) {
        if (!refresh) find(url, page)?.let { return@withContext NoteResult.Success(it) }
        val epoch = mutex.withLock { generation }
        val staging = File(root.parentFile, "remote-pending-${UUID.randomUUID()}")
        try {
            validateUrl(url); check(staging.mkdirs())
            var total = 0L
            suspend fun save(input: InputStream, file: File, limit: Long = capacity) {
                input.use { source -> file.outputStream().use { output ->
                    val buffer = ByteArray(65536); var count = 0L
                    while (true) { currentCoroutineContext().ensureActive(); val n = source.read(buffer); if (n < 0) break; count += n; total += n; require(count <= limit && total <= capacity) { "资源超过缓存容量" }; output.write(buffer, 0, n) }
                } }
            }
            val response = download(url)
            var mime = response.mime.substringBefore(';').ifBlank { "application/octet-stream" }; var partial = false
            val imageTypes = mutableMapOf<String, String>()
            if (!page) save(response.input, File(staging, "content")) else {
                if (!mime.contains("html") && !mime.startsWith("text/")) { response.input.close(); error("该链接不是可缓存网页") }
                val original = File(staging, "source"); save(response.input, original, 8L * 1024 * 1024)
                val charset = Regex("charset=([^; ]+)", RegexOption.IGNORE_CASE).find(response.mime)?.groupValues?.get(1) ?: "UTF-8"
                val document = Jsoup.parse(original, charset, response.finalUrl)
                document.select("script,style,nav,header,footer,aside,form,button,noscript,iframe").remove()
                val article = document.selectFirst("article") ?: document.selectFirst("main") ?: document.body()
                require(article.text().length >= 80 && article.select("p,h1,h2,h3,pre,table").isNotEmpty()) { "无法提取可阅读正文，页面可能需要登录或动态加载" }
                article.select("a[href]").forEach { it.attr("href", it.absUrl("href")) }
                article.select("img").forEach { image ->
                    val imageUrl = image.absUrl("src").ifBlank { image.absUrl("data-src") }
                    image.removeAttr("srcset"); image.removeAttr("data-src")
                    val name = "image-${UUID.randomUUID()}"
                    try { validateUrl(imageUrl); val resource = download(imageUrl); if (!resource.mime.startsWith("image/")) { resource.input.close(); error("不是图片") }; save(resource.input, File(staging, name)); imageTypes[name] = resource.mime.substringBefore(';'); image.attr("src", name) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { partial = true; image.removeAttr("src"); image.attr("alt", "图片未缓存：$imageUrl") }
                }
                val safe = Jsoup.clean(article.outerHtml(), response.finalUrl, Safelist.relaxed().addProtocols("img", "src", "https").preserveRelativeLinks(true))
                val content = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self'; style-src 'unsafe-inline'\"><style>body{font:17px/1.7 sans-serif;margin:20px;overflow-wrap:anywhere}img{max-width:100%;height:auto}pre,table{overflow:auto}a{color:#3977b5}</style></head><body><h1>${org.jsoup.nodes.Entities.escape(document.title())}</h1><p>来源：${org.jsoup.nodes.Entities.escape(response.finalUrl)}</p>$safe</body></html>"
                File(staging, "content").writeText(content); original.delete(); mime = "text/html"
            }
            val id = key(url, page); val actualBytes = staging.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val value = Metadata(id, url, page, System.currentTimeMillis(), partial, actualBytes, mime, imageTypes)
            atomicWrite(File(staging, "metadata.json"), Json.encodeToString(value).toByteArray())
            require(staging.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= capacity) { "资源超过缓存总容量" }
            mutex.withLock {
                check(epoch == generation) { "缓存已清理，本次下载已取消" }
                check(root.isDirectory || root.mkdirs())
                val target = File(root, id); val previous = File(root.parentFile, "remote-old-${UUID.randomUUID()}")
                if (target.exists()) check(target.renameTo(previous))
                try { check(staging.renameTo(target)) } catch (e: Exception) { if (previous.exists()) previous.renameTo(target); throw e }
                previous.deleteRecursively()
                var used = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                root.listFiles().orEmpty().filter { it.name != id }.sortedBy { it.lastModified() }.forEach { directory -> if (used > capacity) { val bytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }; if (directory.deleteRecursively()) used -= bytes } }
            }
            NoteResult.Success(value.public())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { NoteResult.StorageFailure(e.message ?: "远程缓存失败") }
        finally { staging.deleteRecursively() }
    } }
    override suspend fun open(key: String, path: String, offset: Long, length: Long?): CacheStream? = withContext(Dispatchers.IO) { mutex.withLock {
        if (!key.matches(Regex("[a-f0-9]{64}")) || !LibraryPath.isValid(path) || '/' in path) return@withLock null
        val directory = File(root, key); val value = metadata(directory) ?: return@withLock null
        val file = File(directory, path); if (!file.isFile || offset < 0 || offset > file.length()) return@withLock null
        directory.setLastModified(System.currentTimeMillis())
        val input = file.inputStream(); var skip = offset
        while (skip > 0) { val count = input.skip(skip); if (count <= 0) { input.close(); return@withLock null }; skip -= count }
        val stream = object : java.io.FilterInputStream(input) {
            var remaining = minOf(length ?: Long.MAX_VALUE, file.length() - offset)
            override fun read(): Int { if (remaining <= 0) return -1; val result = `in`.read(); if (result >= 0) remaining--; return result }
            override fun read(bytes: ByteArray, start: Int, count: Int): Int { if (remaining <= 0) return -1; val n = `in`.read(bytes, start, minOf(count.toLong(), remaining).toInt()); if (n > 0) remaining -= n; return n }
        }
        CacheStream(if (path == "content") value.mime else value.resources[path] ?: "application/octet-stream", file.length(), stream)
    } }
    companion object {
        private fun validateUrl(url: String) { val parsed = URI(url); require(parsed.scheme in setOf("https", "http") && !parsed.host.isNullOrBlank() && parsed.userInfo == null) { "只支持 HTTP(S) 公共资源链接" } }
        private fun network(url: String): Download {
            var current = url
            repeat(6) {
                validateUrl(current)
                val connection = URI(current).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 15000; connection.readTimeout = 15000; connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "LifeArchive/0.1 offline-reader")
                val status = connection.responseCode
                if (status in 300..399) { val next = connection.getHeaderField("Location"); connection.disconnect(); require(next != null); current = URI(current).resolve(next).toString() }
                else {
                    if (status !in 200..299) { connection.disconnect(); error("网页请求失败 HTTP $status（可能需要登录）") }
                    val stream = object : java.io.FilterInputStream(connection.inputStream) { override fun close() { try { super.close() } finally { connection.disconnect() } } }
                    return Download(connection.contentType ?: "application/octet-stream", stream, current)
                }
            }
            error("网页重定向过多")
        }
    }
}
