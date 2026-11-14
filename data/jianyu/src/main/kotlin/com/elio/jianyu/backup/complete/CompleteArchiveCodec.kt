package com.elio.jianyu.backup.complete

import com.elio.jianyu.backup.BackupCrypto
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** 完整格式与旧有限 .jybak 分开识别；所有明文只存在于调用者拥有的 noBackup 暂存。 */
internal object CompleteArchiveCodec {
    const val EXTENSION = ".jyfull"
    const val SCHEMA = "3b63f950997a41754eb5aa792a99bd8f"
    const val MAX_FILES = 20000
    const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    private val magic = "JYFULL01".toByteArray(Charsets.US_ASCII)

    data class Item(val path: String, val size: Long, val sha256: String)
    data class Manifest(
        val sourcePackage: String,
        val sourceVersion: String,
        val createdAt: Long,
        val rows: Map<String, Long>,
        val files: List<Item>,
        val notes: List<String> = emptyList(),
    ) {
        fun json(): String = JSONObject().put("format", "jianyu-full").put("version", 1)
            .put("roomVersion", 18).put("schema", SCHEMA).put("sourcePackage", sourcePackage)
            .put("sourceVersion", sourceVersion).put("createdAt", createdAt).put("rows", JSONObject(rows))
            .put("files", JSONArray(files.map { JSONObject().put("path", it.path).put("size", it.size).put("sha256", it.sha256) }))
            .put("notes", JSONArray(notes)).put("excluded", JSONArray(listOf("credentials", "device-keystore", "diagnostics", "derived-cache", "device-snapshots", "host-position"))).toString()
    }

    fun write(directory: File, manifest: Manifest, password: String, output: OutputStream) {
        require(password.length >= 8 && password.toByteArray(Charsets.UTF_8).size <= 1024) { "备份密码须至少 8 位且不超过 1024 字节" }
        validate(manifest)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val header = magic + salt
        output.write(header)
        val key = BackupCrypto.derivePortableKek(password, salt)
        try {
            val cipher = AesGcmHkdfStreaming(key, "HmacSha256", 32, 1048576, 0)
            cipher.newEncryptingStream(NonClosingOutput(output), header).use { encrypted ->
                ZipOutputStream(encrypted).use { zip ->
                    zip.setLevel(3)
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    val metadata = manifest.json().toByteArray(Charsets.UTF_8)
                    require(metadata.size <= MAX_MANIFEST_BYTES)
                    zip.write(metadata); zip.closeEntry()
                    manifest.files.forEach { item ->
                        val file = ownedFile(directory, item.path)
                        check(file.isFile && file.length() == item.size) { "备份源文件发生变化" }
                        zip.putNextEntry(ZipEntry(item.path))
                        val digest = MessageDigest.getInstance("SHA-256")
                        val bytes = file.inputStream().use { copyBounded(it, zip, item.size, digest) }
                        check(bytes == item.size && hex(digest.digest()) == item.sha256) { "备份源文件校验失败" }
                        zip.closeEntry()
                    }
                }
            }
        } finally { key.fill(0) }
    }

    /** 解密认证全部完成后才返回；ZIP 结束不等于流式 AEAD 最后一段已通过认证。 */
    fun read(input: InputStream, password: String, directory: File): Manifest {
        check(directory.isDirectory && directory.listFiles().orEmpty().isEmpty()) { "恢复暂存目录必须为空" }
        val header = ByteArray(24)
        DataInputStream(input).readFully(header)
        require(header.copyOfRange(0, 8).contentEquals(magic)) { "不是完整见域备份；旧有限导出和 Life 备份不能在此恢复" }
        val key = BackupCrypto.derivePortableKek(password, header.copyOfRange(8, 24))
        try {
            val cipher = AesGcmHkdfStreaming(key, "HmacSha256", 32, 1048576, 0)
            cipher.newDecryptingStream(NonClosingInput(input), header).use { decrypted ->
                lateinit var manifest: Manifest
                ZipInputStream(NonClosingInput(decrypted)).use { zip ->
                    require(zip.nextEntry?.name == "manifest.json") { "缺少完整清单" }
                    val metadata = ByteArrayOutputStream()
                    copyBounded(zip, metadata, MAX_MANIFEST_BYTES.toLong())
                    manifest = parse(metadata.toString("UTF-8"))
                    validate(manifest)
                    val expected = manifest.files.associateBy { it.path }
                    val seen = mutableSetOf<String>()
                    var total = 0L
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val item = expected[entry.name]
                        require(!entry.isDirectory && item != null && seen.add(entry.name)) { "未知或重复文件" }
                        val file = ownedFile(directory, item.path)
                        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建恢复暂存目录" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        val written = FileOutputStream(file).use { sink ->
                            val count = copyBounded(zip, sink, item.size, digest)
                            sink.fd.sync(); count
                        }
                        require(written == item.size && hex(digest.digest()) == item.sha256) { "文件损坏或不完整：${item.path}" }
                        total += written; require(total <= MAX_TOTAL_BYTES)
                    }
                    require(seen == expected.keys) { "备份缺少文件" }
                }
                // ZipInputStream 可能缓存中央目录，但底层仍须读到密文 EOF 以验证末段。
                val drain = ByteArray(64 * 1024)
                while (decrypted.read(drain) != -1) Unit
                return manifest
            }
        } finally { key.fill(0) }
    }

    fun describe(directory: File, sourcePackage: String, sourceVersion: String, rows: Map<String, Long>, notes: List<String>): Manifest {
        val files = directory.walkTopDown().onEnter { folder ->
            require(folder.canonicalFile == folder.absoluteFile && folder.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "备份源目录包含越界链接" }; true
        }.filter(File::isFile).map { file ->
            val path = file.relativeTo(directory).invariantSeparatorsPath
            ownedFile(directory, path)
            Item(path, file.length(), sha256(file))
        }.sortedBy(Item::path).toList()
        return Manifest(sourcePackage, sourceVersion, System.currentTimeMillis(), rows, files, notes).also(::validate)
    }

    fun parse(raw: String): Manifest {
        val value = JSONObject(raw)
        require(value.getString("format") == "jianyu-full" && value.getInt("version") == 1 && value.getInt("roomVersion") == 18 && value.getString("schema") == SCHEMA) { "不支持的见域完整备份版本或 Schema" }
        val rows = value.getJSONObject("rows").let { entries -> entries.keys().asSequence().associateWith { entries.getLong(it) } }
        val files = value.getJSONArray("files").let { array ->
            require(array.length() <= MAX_FILES)
            (0 until array.length()).map { array.getJSONObject(it).let { item -> Item(item.getString("path"), item.getLong("size"), item.getString("sha256")) } }
        }
        val notes = value.optJSONArray("notes")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
        return Manifest(value.getString("sourcePackage"), value.getString("sourceVersion"), value.getLong("createdAt"), rows, files, notes)
    }

    fun ownedFile(directory: File, path: String): File {
        require(path.isNotBlank() && path.length <= 1024 && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c == '\\' || c == ':' || c == '\u0000' } }) { "备份路径越界" }
        require(path == "database.sqlite" || path == "settings.json" || path.startsWith("content/")) { "备份路径不属于见域" }
        require(directory.canonicalFile == directory.absoluteFile && !Files.isSymbolicLink(directory.toPath())) { "备份根目录包含符号链接" }
        val root = directory.canonicalFile
        val file = File(root, path)
        var ancestor = root
        require(!Files.isSymbolicLink(root.toPath()))
        for (segment in path.split('/')) { ancestor = File(ancestor, segment); require(!Files.isSymbolicLink(ancestor.toPath())) { "备份路径包含符号链接" } }
        require(file.canonicalFile.toPath().startsWith(root.toPath()) && file.canonicalFile == file.absoluteFile) { "备份路径越界或包含符号链接" }
        return file
    }

    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        copyBounded(input, object : OutputStream() { override fun write(value: Int) = Unit; override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit }, MAX_FILE_BYTES, digest)
        hex(digest.digest())
    }

    private fun validate(manifest: Manifest) {
        require(manifest.sourcePackage in setOf("com.elio.jianyu", "com.elio.lifearchive")) { "不支持的来源应用" }
        require(manifest.rows.size == 38 && manifest.rows.all { it.key.matches(Regex("[a-z_]+")) && it.value >= 0 }) { "数据库清单不完整" }
        require(manifest.files.size in 2..MAX_FILES && manifest.files.map(Item::path).toSet().size == manifest.files.size) { "文件清单重复或过大" }
        require(manifest.files.any { it.path == "database.sqlite" } && manifest.files.any { it.path == "settings.json" }) { "缺少数据库或设置" }
        var total = 0L
        manifest.files.forEach { item ->
            require(item.size in 0..MAX_FILE_BYTES && item.sha256.matches(Regex("[a-f0-9]{64}"))) { "非法文件清单" }
            total = Math.addExact(total, item.size); require(total <= MAX_TOTAL_BYTES)
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maximum: Long, digest: MessageDigest? = null): Long {
        val buffer = ByteArray(64 * 1024); var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("完整备份操作中断")
            val read = input.read(buffer); if (read == -1) break
            total += read; require(total <= maximum) { "备份文件超出声明大小或限制" }
            digest?.update(buffer, 0, read); output.write(buffer, 0, read)
        }
        return total
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private class NonClosingInput(input: InputStream) : FilterInputStream(input) { override fun close() = Unit }
    private class NonClosingOutput(output: OutputStream) : FilterOutputStream(output) { override fun close() { flush() } }
}
