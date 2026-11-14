package com.elio.jianyu.backup.complete

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompleteArchiveCodecTest {
    private val password = "仅用于合成备份的密码123"
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun encryptedRoundTripPreservesAllDeclaredFilesAndMetadata() {
        val source = fixture()
        val manifest = CompleteArchiveCodec.describe(source, "com.elio.jianyu", "synthetic-18", CompleteDatabase.tables.associateWith { 0L }, listOf("原始 URI 授权不迁移"))
        val encrypted = ByteArrayOutputStream().also { CompleteArchiveCodec.write(source, manifest, password, it) }.toByteArray()
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("合成会话内容"))
        val target = temporary()
        val result = CompleteArchiveCodec.read(ByteArrayInputStream(encrypted), password, target)
        assertEquals(manifest, result)
        for (item in manifest.files) assertEquals(item.sha256, CompleteArchiveCodec.sha256(File(target, item.path)))
    }

    @Test fun wrongPasswordTruncatedHeaderAndAlteredFinalSegmentAreRejected() {
        val source = fixture()
        val manifest = CompleteArchiveCodec.describe(source, "com.elio.lifearchive", "synthetic", CompleteDatabase.tables.associateWith { 1L }, emptyList())
        val encrypted = ByteArrayOutputStream().also { CompleteArchiveCodec.write(source, manifest, password, it) }.toByteArray()
        val sentinel = File(source.parentFile, "Life-keep.md").apply { writeText("Life 正文") }
        expectFailure { CompleteArchiveCodec.read(ByteArrayInputStream(encrypted), "错误密码1234", temporary()) }
        expectFailure { CompleteArchiveCodec.read(ByteArrayInputStream(encrypted.copyOf(12)), password, temporary()) }
        expectFailure { CompleteArchiveCodec.read(ByteArrayInputStream(encrypted.copyOf(encrypted.size - 8)), password, temporary()) }
        val altered = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        expectFailure { CompleteArchiveCodec.read(ByteArrayInputStream(altered), password, temporary()) }
        assertEquals("Life 正文", sentinel.readText())
        assertEquals("合成会话内容", File(source, "database.sqlite").readText())
    }

    @Test fun portableFilesCannotTraverseToAnotherBusinessOrFollowSymlinks() {
        val source = fixture()
        for (path in listOf("../Life/keep.md", "content/../../Life/keep.md", "content/a\\b", "content//a", "/database.sqlite", "content/C:/keys")) {
            expectFailure { CompleteArchiveCodec.ownedFile(source, path) }
        }
        val unrelated = File(source.parentFile, "Life").apply { mkdirs() }
        // Windows 普通账户未获创建符号链接权限时，路径遍历用例仍完整执行。
        val link = File(source, "content/link")
        runCatching { Files.createSymbolicLink(link.toPath(), unrelated.toPath()) }.onSuccess {
            expectFailure { CompleteArchiveCodec.ownedFile(source, "content/link/keep.md") }
        }
    }

    @Test fun sourceFileChangesAfterManifestDoNotProduceSuccessfulBackup() {
        val source = fixture()
        val manifest = CompleteArchiveCodec.describe(source, "com.elio.jianyu", "synthetic", CompleteDatabase.tables.associateWith { 0L }, emptyList())
        File(source, "database.sqlite").writeText("另一个长度相同的内容")
        expectFailure { CompleteArchiveCodec.write(source, manifest, password, ByteArrayOutputStream()) }
    }

    @Test fun lifeAndLegacyLimitedFormatsAreNotAcceptedAsCompleteBackups() {
        for (magic in listOf("JYBKP\r\n\u001a", "LifeNotes")) {
            expectFailure { CompleteArchiveCodec.read(ByteArrayInputStream((magic + "x".repeat(100)).toByteArray()), password, temporary()) }
        }
    }

    private fun fixture(): File = temporary().apply {
        File(this, "database.sqlite").writeText("合成会话内容")
        File(this, "settings.json").writeText("{}")
        File(this, "content/user-profile/avatar.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1,2,3,4)) }
        File(this, "content/jianyu-audio/sample.wav").apply { parentFile!!.mkdirs(); outputStream().use { output -> repeat(160) { output.write(ByteArray(65536) { (it % 251).toByte() }) } } }
    }
    private fun temporary() = Files.createTempDirectory(context.cacheDir.toPath(), "complete-synthetic-").toFile()
    private fun expectFailure(action: () -> Unit) { try { action(); fail("应拒绝损坏或越界输入") } catch (error: Exception) { /* 受控失败，不能抛 AssertionError 混成通过。 */ } }
}
